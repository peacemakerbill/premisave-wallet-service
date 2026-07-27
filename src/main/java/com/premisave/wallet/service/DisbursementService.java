package com.premisave.wallet.service;

import com.premisave.wallet.client.UserProfileClient;
import com.premisave.wallet.dto.B2CTopUpRequest;
import com.premisave.wallet.dto.B2PochiRequest;
import com.premisave.wallet.dto.DisbursementRequest;
import com.premisave.wallet.dto.DisbursementResponse;
import com.premisave.wallet.dto.MpesaAsyncResponse;
import com.premisave.wallet.dto.MpesaB2BRequest;
import com.premisave.wallet.dto.QueryOrgInfoRequest;
import com.premisave.wallet.dto.QueryOrgInfoResponse;
import com.premisave.wallet.entity.Disbursement;
import com.premisave.wallet.entity.Transaction;
import com.premisave.wallet.entity.Wallet;
import com.premisave.wallet.enums.Currency;
import com.premisave.wallet.enums.DisbursementStatus;
import com.premisave.wallet.enums.TransactionStatus;
import com.premisave.wallet.enums.TransactionType;
import com.premisave.wallet.exception.InsufficientFundsException;
import com.premisave.wallet.exception.PhoneNumberUnavailableException;
import com.premisave.wallet.exception.WalletFrozenException;
import com.premisave.wallet.exception.WalletNotFoundException;
import com.premisave.wallet.repository.DisbursementRepository;
import com.premisave.wallet.repository.TransactionRepository;
import com.premisave.wallet.repository.WalletRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class DisbursementService {

    private final WalletRepository walletRepository;
    private final DisbursementRepository disbursementRepository;
    private final TransactionRepository transactionRepository;
    private final MpesaService mpesaService;
    private final StripeService stripeService;
    private final PaypalService paypalService;
    private final IdempotencyService idempotencyService;
    private final UserProfileClient userProfileClient;
    private final FxRateService fxRateService;

    // No static PayPal rate here anymore — see disbursePaypal, which now
    // fetches a live rate from Frankfurter (FxRateService) on every call.

    private static final java.util.regex.Pattern EMAIL_PATTERN =
            java.util.regex.Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

    // ─── User-facing disbursement (phone / PayPal / Stripe) ─────────────────

    @Transactional
    public DisbursementResponse processDisbursement(String userId, DisbursementRequest request) {
        idempotencyService.checkIdempotency(request.getReference());

        Wallet wallet = walletRepository.findByUserId(userId)
                .orElseThrow(() -> new WalletNotFoundException("Wallet not found for userId: " + userId));

        if (wallet.isFrozen()) throw new WalletFrozenException("Wallet is frozen");
        if (wallet.getBalance().compareTo(request.getAmount()) < 0)
            throw new InsufficientFundsException("Insufficient funds for disbursement");

        String provider = request.getProvider() != null ? request.getProvider().toUpperCase() : "MPESA";

        // M-Pesa payouts are KES-only via Daraja — reject mismatched currency instead
        // of silently sending the wrong amount.
        if ("MPESA".equals(provider) && request.getCurrency() != null
                && !"KES".equalsIgnoreCase(request.getCurrency())) {
            throw new IllegalArgumentException("M-Pesa disbursements must be in KES");
        }

        // Resolve the actual payout destination. MPESA always uses the caller's
        // own verified profile phone number — request.getDestination() is ignored
        // entirely and doesn't need to be sent. STRIPE/PAYPAL still require an
        // explicit destination since there's no equivalent "profile" identifier
        // for those providers on this platform.
        String destination;
        if ("MPESA".equals(provider)) {
            destination = resolveVerifiedPhoneNumber(userId);
        } else {
            if (request.getDestination() == null || request.getDestination().isBlank()) {
                throw new IllegalArgumentException("destination is required for " + provider + " disbursements");
            }
            if ("PAYPAL".equals(provider) && !EMAIL_PATTERN.matcher(request.getDestination()).matches()) {
                throw new IllegalArgumentException("destination must be a valid email address for PayPal disbursements");
            }
            destination = request.getDestination();
        }

        // Deduct upfront — refunded on outright rejection or async failure.
        wallet.setBalance(wallet.getBalance().subtract(request.getAmount()));
        walletRepository.save(wallet);

        String reference = request.getReference() != null ? request.getReference() : UUID.randomUUID().toString();

        Disbursement disbursement = new Disbursement();
        disbursement.setUserId(userId);
        disbursement.setWalletId(wallet.getId());
        disbursement.setAmount(request.getAmount());
        disbursement.setDestination(destination);
        disbursement.setProvider(provider);
        disbursement.setReference(reference);
        disbursement.setStatus(DisbursementStatus.PENDING);

        if ("MPESA".equals(provider)) {
            disbursement.setChannel("B2C");
            var result = mpesaService.sendB2C(destination, request.getAmount());

            if (!result.isSuccess()) {
                // Rejected outright (bad params, amount out of range, etc.) — refund now.
                refund(wallet, request.getAmount());
                disbursement.setStatus(DisbursementStatus.FAILED);
                disbursement.setFailureReason(result.getMessage());
                disbursementRepository.save(disbursement);
                return new DisbursementResponse(disbursement.getId(), disbursement.getStatus().name(), result.getMessage());
            }

            // Accepted for processing — stays PENDING. Do NOT record a completed
            // transaction yet; that happens in completeMpesaDisbursement() once
            // the real ResultURL callback arrives.
            disbursement.setProviderReference(result.getConversationId());
            disbursementRepository.save(disbursement);
            return new DisbursementResponse(disbursement.getId(), disbursement.getStatus().name(),
                    "Disbursement queued with M-Pesa — awaiting confirmation");
        }

        // Stripe/PayPal payout APIs here are treated as synchronous per the
        // existing provider service methods. (Their real APIs are also async in
        // production — same PENDING/callback pattern applies if/when those get
        // wired to webhooks; out of scope for this pass.)
        ProviderResult result = switch (provider) {
            case "STRIPE" -> disburseStripe(request);
            case "PAYPAL" -> disbursePaypal(request);
            default -> new ProviderResult(false, "Unsupported provider: " + provider, null);
        };

        disbursement.setChannel(provider + "_PAYOUT");

        if (result.success()) {
            disbursement.setStatus(DisbursementStatus.SUCCESS);
            disbursement.setProviderReference(result.providerRef());
            saveDisbursementTransaction(userId, wallet.getId(), request.getAmount(), disbursement, reference);
        } else {
            refund(wallet, request.getAmount());
            disbursement.setStatus(DisbursementStatus.FAILED);
            disbursement.setFailureReason(result.message());
            log.warn("Disbursement failed for userId={}, refunded. Reason: {}", userId, result.message());
        }

        disbursementRepository.save(disbursement);
        return new DisbursementResponse(disbursement.getId(), disbursement.getStatus().name(), result.message());
    }

    // ─── B2B (admin/finance-initiated, business-to-business payment) ───────

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
                // Hakikisha couldn't confirm the recipient — abort before Safaricom's
                // B2B endpoint is ever called, rather than sending money to an
                // unverified shortcode/till.
                log.warn("B2B Hakikisha check failed for receiverShortcode={} — aborting payment. reason={}",
                        request.getReceiverShortcode(), orgInfo.getResponseMessage());

                Disbursement aborted = new Disbursement();
                aborted.setUserId(initiatedByUserId);
                aborted.setAmount(request.getAmount());
                aborted.setCurrency(Currency.KES);
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
        disbursement.setCurrency(Currency.KES);
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

    // ─── B2C Account Top Up (admin/finance-initiated) ───────────────────────

    @Transactional
    public DisbursementResponse processB2CTopUp(String initiatedByUserId, B2CTopUpRequest request) {
        idempotencyService.checkIdempotency(request.getReference());
        String reference = request.getReference() != null ? request.getReference() : UUID.randomUUID().toString();

        var result = mpesaService.topUpB2CAccount(request.getAmount(), request.getReceivingShortcode(),
                request.getRequester(), request.getAccountReference(), request.getRemarks());

        Disbursement disbursement = new Disbursement();
        disbursement.setUserId(initiatedByUserId);
        disbursement.setAmount(request.getAmount());
        disbursement.setCurrency(Currency.KES);
        disbursement.setDestination(request.getReceivingShortcode() != null
                ? request.getReceivingShortcode() : "B2C-DEFAULT");
        disbursement.setProvider("MPESA");
        disbursement.setChannel("B2C_TOPUP");
        disbursement.setReference(reference);

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

    // ─── B2Pochi (pay into the caller's own Pochi business wallet) ──────────────

    @Transactional
    public DisbursementResponse processB2PochiPayment(String initiatedByUserId, B2PochiRequest request) {
        idempotencyService.checkIdempotency(request.getReference());

        String phoneNumber = resolveVerifiedPhoneNumber(initiatedByUserId);

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

        MpesaAsyncResponse result = mpesaService.sendToPochi(resolvedRequest, originatorConversationId);

        Disbursement disbursement = new Disbursement();
        disbursement.setUserId(initiatedByUserId);
        disbursement.setAmount(request.getAmount());
        disbursement.setCurrency(Currency.KES);
        disbursement.setDestination(phoneNumber);
        disbursement.setProvider("MPESA");
        disbursement.setChannel("B2C_POCHI");
        disbursement.setReference(reference);

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

    // ─── Reconciliation from Safaricom's ResultURL callback ─────────────────

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
            disbursementRepository.save(d);

            if ("B2C".equals(d.getChannel()) && d.getWalletId() != null) {
                saveDisbursementTransaction(d.getUserId(), d.getWalletId(), d.getAmount(), d, d.getReference());
            }
            log.info("M-Pesa {} disbursement completed: id={} conversationId={} mpesaTxId={}",
                    d.getChannel(), d.getId(), conversationId, mpesaTransactionId);
        } else {
            if (d.getWalletId() != null) {
                Wallet wallet = walletRepository.findById(d.getWalletId())
                        .orElseThrow(() -> new WalletNotFoundException("Wallet not found: " + d.getWalletId()));
                refund(wallet, d.getAmount());
            }
            d.setStatus(DisbursementStatus.FAILED);
            d.setFailureReason(resultDesc);
            disbursementRepository.save(d);
            log.warn("M-Pesa {} disbursement failed, refunded where applicable: id={} conversationId={} reason={}",
                    d.getChannel(), d.getId(), conversationId, resultDesc);
        }
    }

    public void markMpesaDisbursementTimedOut(String conversationId) {
        disbursementRepository.findByProviderReference(conversationId).ifPresentOrElse(d -> {
            log.warn("M-Pesa disbursement queue timeout: id={} conversationId={} — awaiting eventual result or manual reconciliation",
                    d.getId(), conversationId);
        }, () -> log.warn("Timeout callback for unknown ConversationID={}", conversationId));
    }

    // ─── Stuck-disbursement sweeper ──────────────────────────────────────────

    @Scheduled(fixedDelay = 15 * 60 * 1000) // every 15 minutes
    public void flagStuckDisbursements() {
        LocalDateTime cutoff = LocalDateTime.now().minusMinutes(30);
        List<Disbursement> stuck = disbursementRepository.findByStatusAndCreatedAtBefore(
                DisbursementStatus.PENDING, cutoff);

        if (!stuck.isEmpty()) {
            log.warn("{} disbursement(s) stuck in PENDING beyond 30 minutes — needs manual reconciliation: {}",
                    stuck.size(), stuck.stream().map(Disbursement::getId).toList());
        }
    }

    // ─── Provider dispatch (Stripe/PayPal) ───────────────────────────────────

    private ProviderResult disburseStripe(DisbursementRequest request) {
        try {
            String currency = request.getCurrency() != null ? request.getCurrency() : "kes";
            String payoutId = stripeService.processPayout(request.getAmount(), currency);
            return new ProviderResult(true, "Stripe payout initiated", payoutId);
        } catch (Exception e) {
            return new ProviderResult(false, e.getMessage(), null);
        }
    }

    private ProviderResult disbursePaypal(DisbursementRequest request) {
        try {
            // request.getAmount() is in KES (already deducted from the wallet's
            // KES balance by processDisbursement above). PayPal Payouts require
            // USD — convert using a live Frankfurter rate fetched fresh for this
            // payout. If Frankfurter is unreachable, this throws, is caught below,
            // and the caller's existing failure path refunds the wallet — same
            // as any other PayPal payout failure.
            BigDecimal usdToKesRate = fxRateService.getRate("USD", "KES");
            BigDecimal usdAmount = request.getAmount()
                    .divide(usdToKesRate, 2, java.math.RoundingMode.HALF_UP);
            String batchId = paypalService.processPayout(request.getDestination(), usdAmount, "USD");
            log.info("PayPal payout: kesAmount={} usdAmount={} rate={} batchId={}",
                    request.getAmount(), usdAmount, usdToKesRate, batchId);
            return new ProviderResult(true, "PayPal payout initiated (USD " + usdAmount + ")", batchId);
        } catch (Exception e) {
            return new ProviderResult(false, e.getMessage(), null);
        }
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private String resolveVerifiedPhoneNumber(String userId) {
        Map<String, Object> profile;
        try {
            profile = userProfileClient.getPublicProfile(userId);
        } catch (Exception e) {
            log.error("Failed to fetch profile for userId={} while resolving disbursement phone number", userId, e);
            throw new PhoneNumberUnavailableException(
                    "Could not verify your phone number right now — please try again shortly.");
        }

        Object phoneObj = profile != null ? profile.get("phoneNumber") : null;
        String phone = phoneObj != null ? String.valueOf(phoneObj).trim() : null;

        if (phone == null || phone.isBlank()) {
            throw new PhoneNumberUnavailableException(
                    "No phone number is set on your profile — please add one before requesting an M-Pesa disbursement.");
        }
        return phone;
    }

    private void refund(Wallet wallet, BigDecimal amount) {
        wallet.setBalance(wallet.getBalance().add(amount));
        walletRepository.save(wallet);
    }

    private void saveDisbursementTransaction(String userId, String walletId, BigDecimal amount,
                                              Disbursement disbursement, String reference) {
        Transaction tx = new Transaction();
        tx.setUserId(userId);
        tx.setWalletId(walletId);
        tx.setType(TransactionType.DISBURSEMENT);
        tx.setStatus(TransactionStatus.COMPLETED);
        tx.setAmount(amount);
        tx.setCurrency(Currency.KES);
        tx.setDescription("Disbursement via " + disbursement.getProvider() + " to " + disbursement.getDestination());
        tx.setReference(reference);
        tx.setProviderReference(disbursement.getProviderReference());
        transactionRepository.save(tx);
    }

    private record ProviderResult(boolean success, String message, String providerRef) {}
}