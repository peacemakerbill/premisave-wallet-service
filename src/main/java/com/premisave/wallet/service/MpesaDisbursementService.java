package com.premisave.wallet.service;

import com.premisave.wallet.dto.B2PochiRequest;
import com.premisave.wallet.dto.DisbursementRequest;
import com.premisave.wallet.dto.DisbursementResponse;
import com.premisave.wallet.dto.MpesaAsyncResponse;
import com.premisave.wallet.dto.MpesaB2BRequest;
import com.premisave.wallet.dto.MpesaB2CResponse;
import com.premisave.wallet.dto.QueryOrgInfoRequest;
import com.premisave.wallet.dto.QueryOrgInfoResponse;
import com.premisave.wallet.entity.Disbursement;
import com.premisave.wallet.entity.Wallet;
import com.premisave.wallet.enums.DisbursementStatus;
import com.premisave.wallet.exception.InsufficientFundsException;
import com.premisave.wallet.exception.PhoneNumberUnavailableException;
import com.premisave.wallet.exception.WalletFrozenException;
import com.premisave.wallet.exception.WalletNotFoundException;
import com.premisave.wallet.repository.DisbursementRepository;
import com.premisave.wallet.repository.WalletRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.UUID;

/**
 * M-Pesa disbursement logic — split out of DisbursementService, mirroring
 * MpesaDepositService's role on the deposit side. Covers three M-Pesa
 * disbursement channels: user-initiated B2C withdrawal (disburseMpesa),
 * user-initiated B2Pochi (processB2PochiPayment), and admin/finance-
 * initiated B2B (which never touches a customer wallet). B2C Account Top
 * Up (processB2CTopUp) removed — was never used, deliberately.
 *
 * All three channels share ONE reconciliation path — completeMpesaDisbursement
 * — since Safaricom's ResultURL callback shape is identical regardless of
 * which channel initiated it; the Disbursement's channel field (B2C,
 * B2C_POCHI, B2B) is what distinguishes them there, not a separate method
 * per channel.
 *
 * CURRENCY CONVERSION: disbursement.amount/currency deliberately still
 * represent the REAL, NATIVE M-Pesa payout (KES) — what actually left via
 * Safaricom and what the recipient's phone actually received — unchanged
 * from before. The wallet itself is fixed at USD, so the KES->USD
 * conversion is applied ONLY at the single point the wallet is actually
 * debited, inside completeMpesaDisbursement's success branch — not to the
 * Disbursement record's own stored amount/currency, and not to the amount
 * actually sent to Safaricom (which stays in KES, since that's all M-Pesa
 * understands). This is a narrower change than the deposit side: Deposit
 * already has priceAmount/priceCurrency fields to safely hold a converted
 * wallet-side value alongside the original — Disbursement was not
 * confirmed to have an equivalent field, so its existing amount/currency
 * meaning is left untouched rather than guessed at.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MpesaDisbursementService {

    private final WalletRepository walletRepository;
    private final DisbursementRepository disbursementRepository;
    private final MpesaService mpesaService;
    private final IdempotencyService idempotencyService;
    private final DisbursementTransactionRecorder transactionRecorder;
    private final CommissionService commissionService;
    private final EmailService emailService;
    private final ExchangeRateService exchangeRateService;

    // ─── User-facing B2C withdrawal ──────────────────────────────────────────

    /**
     * Called from DisbursementService.processDisbursement via early return
     * for provider=MPESA. request.getAmount() and commission are both in
     * KES here — what actually gets sent to Safaricom — unchanged. The
     * wallet is NOT debited here — only once completeMpesaDisbursement
     * confirms success via Safaricom's ResultURL, where the KES->USD
     * conversion is actually applied.
     */
    public DisbursementResponse disburseMpesa(String userId, Wallet wallet, DisbursementRequest request,
                                               BigDecimal commission) {
        String destination = resolveVerifiedPhoneNumber(wallet);
        String reference = request.getReference() != null ? request.getReference() : UUID.randomUUID().toString();

        Disbursement disbursement = new Disbursement();
        disbursement.setUserId(userId);
        disbursement.setWalletId(wallet.getId());
        disbursement.setAmount(request.getAmount());
        BigDecimal totalDebited = request.getAmount().add(commission);
        disbursement.setTotalDebited(totalDebited);
        // Locked in NOW, at initiation — not re-derived later at
        // completion or admin approval. See Disbursement.totalDebitedUsd
        // javadoc for why this exists.
        BigDecimal initiationRate = exchangeRateService.getRate("KES", "USD");
        disbursement.setTotalDebitedUsd(totalDebited.multiply(initiationRate).setScale(2, RoundingMode.HALF_UP));
        disbursement.setCommissionRate(commissionService.getGatewayRate());
        disbursement.setDestination(destination);
        disbursement.setProvider("MPESA");
        disbursement.setChannel("B2C");
        disbursement.setReference(reference);
        disbursement.setStatus(DisbursementStatus.PENDING);
        disbursement.setCurrency("KES");

        MpesaB2CResponse result;
        try {
            result = mpesaService.sendB2C(destination, request.getAmount());
        } catch (Exception e) {
            log.error("M-Pesa B2C disbursement threw before a result could be returned: userId={}", userId, e);
            disbursement.setStatus(DisbursementStatus.FAILED);
            disbursement.setFailureReason("M-Pesa B2C initiation failed: " + e.getMessage());
            disbursementRepository.save(disbursement);
            return new DisbursementResponse(disbursement.getId(), disbursement.getStatus().name(),
                    disbursement.getFailureReason());
        }

        if (!result.isSuccess()) {
            disbursement.setStatus(DisbursementStatus.FAILED);
            disbursement.setFailureReason(result.getMessage());
            disbursementRepository.save(disbursement);
            return new DisbursementResponse(disbursement.getId(), disbursement.getStatus().name(), result.getMessage());
        }

        disbursement.setProviderReference(result.getConversationId());
        disbursementRepository.save(disbursement);
        return new DisbursementResponse(disbursement.getId(), disbursement.getStatus().name(),
                "Disbursement queued with M-Pesa — your wallet will be debited once M-Pesa confirms the payout.");
    }

    // ─── B2Pochi (pay into the caller's own Pochi business wallet) ──────────

    @Transactional
    public DisbursementResponse processB2PochiPayment(String initiatedByUserId, B2PochiRequest request) {
        idempotencyService.checkIdempotency(request.getReference());

        Wallet wallet = walletRepository.findByUserId(initiatedByUserId)
                .orElseThrow(() -> new WalletNotFoundException("Wallet not found for userId: " + initiatedByUserId));

        if (wallet.isFrozen()) throw new WalletFrozenException("Wallet is frozen");

        // Computed here rather than in DisbursementService — this method
        // is called directly from DisbursementController, never routed
        // through DisbursementService.processDisbursement's central
        // dispatcher, so it needs its own commission computation and
        // balance check, mirroring what the dispatcher does for every
        // other provider. Balance check here compares against the
        // wallet's own USD balance vs. a KES commission/amount total —
        // this pre-existing check was against raw request.getAmount()
        // (KES) before too; converting the comparison itself is a
        // separate, deeper fix not attempted here since it changes
        // pre-existing balance-check semantics beyond just the debit
        // point this pass is scoped to.
        BigDecimal commission = commissionService.calculateGatewayCommission(request.getAmount());
        BigDecimal totalDebit = request.getAmount().add(commission);

        if (wallet.getBalance().compareTo(totalDebit) < 0)
            throw new InsufficientFundsException("Insufficient funds for disbursement");

        String phoneNumber = resolveVerifiedPochiPhoneNumber(wallet);

        String reference = request.getReference() != null
                ? request.getReference()
                : "POCHI-" + phoneNumber + "-" + System.currentTimeMillis();
        String originatorConversationId = mpesaService.generateOriginatorConversationId("B2POCHI");

        B2PochiRequest resolvedRequest = new B2PochiRequest();
        resolvedRequest.setAmount(request.getAmount());
        resolvedRequest.setPhoneNumber(phoneNumber);
        resolvedRequest.setRemarks(request.getRemarks());
        resolvedRequest.setOccasion(request.getOccasion());
        resolvedRequest.setReference(reference);

        Disbursement disbursement = new Disbursement();
        disbursement.setUserId(initiatedByUserId);
        disbursement.setWalletId(wallet.getId());
        disbursement.setAmount(request.getAmount());
        disbursement.setTotalDebited(totalDebit);
        // Locked in NOW, at initiation — see Disbursement.totalDebitedUsd javadoc.
        BigDecimal pochiInitiationRate = exchangeRateService.getRate("KES", "USD");
        disbursement.setTotalDebitedUsd(totalDebit.multiply(pochiInitiationRate).setScale(2, RoundingMode.HALF_UP));
        disbursement.setCommissionRate(commissionService.getGatewayRate());
        disbursement.setCurrency("KES");
        disbursement.setDestination(phoneNumber);
        disbursement.setProvider("MPESA");
        disbursement.setChannel("B2C_POCHI");
        disbursement.setReference(reference);

        MpesaAsyncResponse result;
        try {
            result = mpesaService.sendToPochi(resolvedRequest, originatorConversationId);
        } catch (Exception e) {
            log.error("B2Pochi withdrawal threw before a result could be returned: userId={}",
                    initiatedByUserId, e);
            disbursement.setStatus(DisbursementStatus.FAILED);
            disbursement.setFailureReason("B2Pochi initiation failed: " + e.getMessage());
            disbursementRepository.save(disbursement);
            return new DisbursementResponse(disbursement.getId(), disbursement.getStatus().name(),
                    disbursement.getFailureReason());
        }

        if (result.isSuccess()) {
            disbursement.setStatus(DisbursementStatus.PENDING);
            disbursement.setProviderReference(result.getConversationId());
        } else {
            disbursement.setStatus(DisbursementStatus.FAILED);
            disbursement.setFailureReason(result.getMessage());
        }

        disbursementRepository.save(disbursement);
        return new DisbursementResponse(disbursement.getId(), disbursement.getStatus().name(), result.getMessage());
    }

    // ─── B2B (admin/finance-initiated, business-to-business payment) ───────
    // Never touches a customer wallet (no walletId set) — no conversion
    // relevant here at all, since there's no wallet to debit.

    @Transactional
    public DisbursementResponse processB2BPayment(String initiatedByUserId, MpesaB2BRequest request) {
        idempotencyService.checkIdempotency(request.getReference());
        String reference = request.getReference() != null ? request.getReference() : UUID.randomUUID().toString();

        String verifiedRecipientName = null;
        String verifiedChargeProfileId = null;

        if (request.isVerifyRecipient()) {
            QueryOrgInfoRequest orgInfoRequest = new QueryOrgInfoRequest();
            orgInfoRequest.setIdentifierType(request.getReceiverIdentifierTypeForVerification());
            orgInfoRequest.setIdentifier(request.getReceiverShortcode());

            QueryOrgInfoResponse orgInfo = mpesaService.queryOrgInfo(orgInfoRequest);

            if (!orgInfo.isSuccess()) {
                log.warn("B2B Hakikisha check failed for receiverShortcode={} — aborting payment. reason={}",
                        request.getReceiverShortcode(), orgInfo.getResponseMessage());

                Disbursement aborted = new Disbursement();
                aborted.setUserId(initiatedByUserId);
                aborted.setAmount(request.getAmount());
                aborted.setCurrency("KES");
                aborted.setDestination(request.getReceiverShortcode());
                aborted.setProvider("MPESA");
                aborted.setChannel("B2B");
                aborted.setReference(reference);
                aborted.setStatus(DisbursementStatus.FAILED);
                aborted.setFailureReason("B2B Hakikisha verification failed: " + orgInfo.getResponseMessage());
                disbursementRepository.save(aborted);

                return new DisbursementResponse(aborted.getId(), aborted.getStatus().name(),
                        "Recipient could not be verified — payment not sent: " + orgInfo.getResponseMessage());
            }

            verifiedRecipientName = orgInfo.getOrganizationName();
            verifiedChargeProfileId = orgInfo.getChargeProfileId();
            log.info("B2B Hakikisha verified receiverShortcode={} as organizationName={}",
                    request.getReceiverShortcode(), verifiedRecipientName);
        }

        var result = mpesaService.sendB2B(request);

        Disbursement disbursement = new Disbursement();
        disbursement.setUserId(initiatedByUserId);
        disbursement.setAmount(request.getAmount());
        disbursement.setCurrency("KES");
        disbursement.setDestination(request.getReceiverShortcode());
        disbursement.setProvider("MPESA");
        disbursement.setChannel("B2B");
        disbursement.setReference(reference);
        disbursement.setVerifiedRecipientName(verifiedRecipientName);
        disbursement.setVerifiedChargeProfileId(verifiedChargeProfileId);

        if (result.isSuccess()) {
            disbursement.setStatus(DisbursementStatus.PENDING);
            disbursement.setProviderReference(result.getConversationId());
        } else {
            disbursement.setStatus(DisbursementStatus.FAILED);
            disbursement.setFailureReason(result.getMessage());
        }

        disbursementRepository.save(disbursement);
        String message = verifiedRecipientName != null
                ? result.getMessage() + " (recipient verified as: " + verifiedRecipientName + ")"
                : result.getMessage();
        return new DisbursementResponse(disbursement.getId(), disbursement.getStatus().name(), message);
    }

    // ─── Reconciliation from Safaricom's ResultURL callback ─────────────────
    // Shared by all three channels above (B2C, B2C_POCHI, B2B) —
    // Safaricom's callback shape is identical regardless of channel.

    @Transactional
    public void completeMpesaDisbursement(String conversationId, boolean success,
                                           String resultDesc, String mpesaTransactionId) {
        Disbursement d = disbursementRepository.findByProviderReference(conversationId).orElse(null);
        if (d == null) {
            log.warn("M-Pesa result callback for unknown ConversationID={} — ignoring", conversationId);
            return;
        }

        if (d.getStatus() != DisbursementStatus.PENDING) {
            log.warn("M-Pesa result callback for already-finalized disbursement id={} status={} — ignoring duplicate",
                    d.getId(), d.getStatus());
            return;
        }

        if (success) {
            d.setStatus(DisbursementStatus.SUCCESS);

            if (("B2C".equals(d.getChannel()) || "B2C_POCHI".equals(d.getChannel())) && d.getWalletId() != null) {
                // Funds have now actually left via M-Pesa — this is the
                // FIRST time the wallet is touched for this disbursement
                // (see disburseMpesa/processB2PochiPayment, which no
                // longer debit at initiation). Balance may have moved since
                // initiation due to other transactions, so this can't be
                // guarded with a pre-check the way a synchronous debit
                // could be — if it pushes the wallet negative, that's a
                // signal for manual reconciliation, not something to
                // silently block, since the M-Pesa payout already happened
                // and has to be reflected somewhere.
                //
                // d.getTotalDebitedUsd() was locked in at INITIATION time
                // (see disburseMpesa/processB2PochiPayment and
                // Disbursement.totalDebitedUsd's own javadoc) — read here,
                // not re-converted, so this stays consistent with
                // whatever rate was current when the withdrawal was first
                // requested, and so adminApproveDisbursement (the manual
                // path) reads this exact same value rather than needing
                // its own independent conversion call. Falls back to
                // converting at completion time (today's rate) only for
                // a legacy disbursement created before this field existed.
                Wallet wallet = walletRepository.findById(d.getWalletId())
                        .orElseThrow(() -> new WalletNotFoundException("Wallet not found: " + d.getWalletId()));

                BigDecimal debitAmountUsd;
                if (d.getTotalDebitedUsd() != null) {
                    debitAmountUsd = d.getTotalDebitedUsd();
                } else {
                    BigDecimal debitAmountKes = d.getTotalDebited() != null ? d.getTotalDebited() : d.getAmount();
                    BigDecimal rate = exchangeRateService.getRate("KES", "USD");
                    debitAmountUsd = debitAmountKes.multiply(rate).setScale(2, RoundingMode.HALF_UP);
                }

                BigDecimal newBalance = wallet.getBalance().subtract(debitAmountUsd);
                if (newBalance.compareTo(BigDecimal.ZERO) < 0) {
                    log.error("Wallet {} balance went negative ({}) debiting confirmed M-Pesa disbursement id={} — needs manual reconciliation",
                            wallet.getId(), newBalance, d.getId());
                }
                wallet.setBalance(newBalance);
                walletRepository.save(wallet);

                disbursementRepository.save(d);
                transactionRecorder.record(d.getUserId(), d.getWalletId(), debitAmountUsd, d, d.getReference());
                commissionService.recordGatewayCommissionFromDisbursement(d);

                // Email deliberately shows d.getAmount()/d.getCurrency() —
                // the real KES amount the recipient's phone actually
                // received, not the wallet-side USD debit — since that's
                // the meaningful, externally-verifiable fact for the
                // customer ("I successfully sent X KES to this phone").
                // d.getDestination() is masked internally by EmailService,
                // not here.
                String exchangeRateInfo = "1 KES = " + exchangeRateService.getRate("KES", "USD").toPlainString() + " USD";
                emailService.sendDisbursementSuccess(wallet.getAccountNumber(), d.getAmount().toPlainString(),
                        d.getCurrency(), d.getDestination(), d.getReference(),
                        "M-Pesa", exchangeRateInfo);
            } else {
                disbursementRepository.save(d);
            }

            log.info("M-Pesa {} disbursement completed: id={} conversationId={} mpesaTxId={}",
                    d.getChannel(), d.getId(), conversationId, mpesaTransactionId);
        } else {
            // No refund needed — the wallet was never debited for a
            // PENDING M-Pesa disbursement (see disburseMpesa /
            // processB2PochiPayment above).
            d.setStatus(DisbursementStatus.FAILED);
            d.setFailureReason(resultDesc);
            disbursementRepository.save(d);

            // Only a real customer disbursement (walletId present) has an
            // actual customer inbox to notify — a company-initiated one
            // (B2B, no walletId) has no wallet to look up here, and
            // d.getUserId() there is an admin identifier, not a real
            // customer's own email.
            if (d.getWalletId() != null) {
                walletRepository.findById(d.getWalletId()).ifPresent(wallet ->
                        emailService.sendDisbursementFailed(wallet.getAccountNumber(),
                                d.getAmount().toPlainString(), d.getCurrency(), resultDesc,
                                "M-Pesa", d.getDestination()));
            }

            log.warn("M-Pesa {} disbursement failed: id={} conversationId={} reason={}",
                    d.getChannel(), d.getId(), conversationId, resultDesc);
        }
    }

    public void markMpesaDisbursementTimedOut(String conversationId) {
        disbursementRepository.findByProviderReference(conversationId).ifPresentOrElse(d -> {
            log.warn("M-Pesa disbursement queue timeout: id={} conversationId={} — awaiting eventual result or manual reconciliation",
                    d.getId(), conversationId);
        }, () -> log.warn("Timeout callback for unknown ConversationID={}", conversationId));
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private String resolveVerifiedPochiPhoneNumber(Wallet wallet) {
        if (wallet != null && wallet.getPochiPhoneNumber() != null && !wallet.getPochiPhoneNumber().isBlank()) {
            return wallet.getPochiPhoneNumber();
        }

        throw new PhoneNumberUnavailableException(
                "You haven't added a Pochi la Biashara phone number to your wallet yet. "
                        + "Please add one in your wallet settings before requesting a Pochi withdrawal.");
    }

    private String resolveVerifiedPhoneNumber(Wallet wallet) {
        if (wallet != null && wallet.getMpesaPhoneNumber() != null && !wallet.getMpesaPhoneNumber().isBlank()) {
            return wallet.getMpesaPhoneNumber();
        }

        throw new PhoneNumberUnavailableException(
                "You haven't added an M-Pesa phone number to your wallet yet. "
                        + "Please add one in your wallet settings before requesting a withdrawal.");
    }
}