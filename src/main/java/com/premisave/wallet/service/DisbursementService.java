package com.premisave.wallet.service;

import com.premisave.wallet.client.UserProfileClient;
import com.premisave.wallet.dto.B2CTopUpRequest;
import com.premisave.wallet.dto.B2PochiRequest;
import com.premisave.wallet.dto.DisbursementRequest;
import com.premisave.wallet.dto.DisbursementResponse;
import com.premisave.wallet.dto.MpesaAsyncResponse;
import com.premisave.wallet.dto.MpesaB2BRequest;
import com.premisave.wallet.dto.MpesaB2CResponse;
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

    /**
     * Terminal PayPal payout item transaction_status values (or, equivalently,
     * PAYMENT.PAYOUTS-ITEM.* webhook event suffixes) that will never resolve
     * on their own — treated the same as a hard failure: refund the wallet
     * and mark the disbursement FAILED. Deliberately excludes UNCLAIMED and
     * HELD/ONHOLD, since those can still turn into SUCCESS or FAILED later
     * (recipient claims it, hold gets released/reviewed) — a disbursement in
     * one of those states is left PENDING for a subsequent webhook event to
     * finalize.
     */
    private static final List<String> PAYPAL_TERMINAL_FAILURE_STATUSES =
            List.of("FAILED", "DENIED", "BLOCKED", "RETURNED", "REFUNDED", "REVERSED", "CANCELED");

    // No static PayPal rate here anymore — see disbursePaypal, which now
    // fetches a live rate from Frankfurter (FxRateService) on every call.

//    private static final java.util.regex.Pattern EMAIL_PATTERN =
//            java.util.regex.Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

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

        if ("MPESA".equals(provider) && request.getCurrency() != null
                && !"KES".equalsIgnoreCase(request.getCurrency())) {
            throw new IllegalArgumentException("M-Pesa disbursements must be in KES");
        }

        // Resolve the actual payout destination — MPESA and PAYPAL both resolve
        // from the wallet's own verified profile fields, never from the request,
        // eliminating typo/mistargeted-payout risk for both. STRIPE still
        // requires an explicit destination (Stripe Connect isn't wired up yet,
        // so there's no per-user Stripe payout identity to resolve from).
        String destination;
        if ("MPESA".equals(provider)) {
            destination = resolveVerifiedPhoneNumber(userId, wallet);
        } else if ("PAYPAL".equals(provider)) {
            if (wallet.getPaypalEmail() == null || wallet.getPaypalEmail().isBlank()) {
                throw new IllegalArgumentException(
                        "No PayPal email is set on your wallet — add one before requesting a PayPal disbursement.");
            }
            destination = wallet.getPaypalEmail();
        } else {
            if (request.getDestination() == null || request.getDestination().isBlank()) {
                throw new IllegalArgumentException("destination is required for " + provider + " disbursements");
            }
            destination = request.getDestination();
        }

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

        disbursement.setCurrency(Currency.KES);

        if ("MPESA".equals(provider)) {
            disbursement.setChannel("B2C");

            // sendB2C's own Safaricom HTTP call is already wrapped in a
            // try/catch internally and always returns a result — but
            // getAccessToken() (OAuth, retried 3x then throws) and the
            // SecurityCredential RSA encryption both run BEFORE that
            // try/catch even starts. Either one throwing here would
            // otherwise propagate straight out of this method, past the
            // point where the wallet was already debited and saved above,
            // with no Disbursement record ever created and no refund.
            MpesaB2CResponse result;
            try {
                result = mpesaService.sendB2C(destination, request.getAmount());
            } catch (Exception e) {
                log.error("M-Pesa B2C disbursement threw before a result could be returned — refunding: userId={}",
                        userId, e);
                refund(wallet, request.getAmount());
                disbursement.setStatus(DisbursementStatus.FAILED);
                disbursement.setFailureReason("M-Pesa B2C initiation failed: " + e.getMessage());
                disbursementRepository.save(disbursement);
                return new DisbursementResponse(disbursement.getId(), disbursement.getStatus().name(),
                        disbursement.getFailureReason());
            }

            if (!result.isSuccess()) {
                refund(wallet, request.getAmount());
                disbursement.setStatus(DisbursementStatus.FAILED);
                disbursement.setFailureReason(result.getMessage());
                disbursementRepository.save(disbursement);
                return new DisbursementResponse(disbursement.getId(), disbursement.getStatus().name(), result.getMessage());
            }

            disbursement.setProviderReference(result.getConversationId());
            disbursementRepository.save(disbursement);
            return new DisbursementResponse(disbursement.getId(), disbursement.getStatus().name(),
                    "Disbursement queued with M-Pesa — awaiting confirmation");
        }

        ProviderResult result = switch (provider) {
            case "STRIPE" -> disburseStripe(request);
            case "PAYPAL" -> disbursePaypal(request, destination);
            default -> new ProviderResult(false, "Unsupported provider: " + provider, null);
        };

        disbursement.setChannel(provider + "_PAYOUT");

        if (result.success()) {
            disbursement.setProviderReference(result.providerRef());

            if ("PAYPAL".equals(provider)) {
                // The Payouts API is asynchronous — a successful response here
                // only means PayPal accepted and queued the batch, not that the
                // recipient was actually paid. Stay PENDING until a
                // PAYMENT.PAYOUTS-ITEM.* webhook reconciles the real outcome
                // (see completePaypalDisbursement below) — same PENDING-until-
                // callback pattern as M-Pesa B2C above. The transaction record
                // is created there too, only once success is confirmed, not here.
                disbursement.setStatus(DisbursementStatus.PENDING);
                disbursementRepository.save(disbursement);
                return new DisbursementResponse(disbursement.getId(), disbursement.getStatus().name(),
                        "Disbursement queued with PayPal — awaiting confirmation");
            }

            disbursement.setStatus(DisbursementStatus.SUCCESS);
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

        // This withdraws the caller's OWN wallet funds to their OWN Pochi la
        // Biashara account — same checks as the generic phone-number M-Pesa
        // withdrawal in processDisbursement above (wallet must exist, not be
        // frozen, and have sufficient balance). Previously this method never
        // debited the wallet at all, meaning the payout came free out of
        // Premisave's own M-Pesa float instead of the user's balance.
        Wallet wallet = walletRepository.findByUserId(initiatedByUserId)
                .orElseThrow(() -> new WalletNotFoundException("Wallet not found for userId: " + initiatedByUserId));

        if (wallet.isFrozen()) throw new WalletFrozenException("Wallet is frozen");
        if (wallet.getBalance().compareTo(request.getAmount()) < 0)
            throw new InsufficientFundsException("Insufficient funds for disbursement");

        String phoneNumber = resolveVerifiedPochiPhoneNumber(initiatedByUserId, wallet);

        wallet.setBalance(wallet.getBalance().subtract(request.getAmount()));
        walletRepository.save(wallet);

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
        disbursement.setCurrency(Currency.KES);
        disbursement.setDestination(phoneNumber);
        disbursement.setProvider("MPESA");
        disbursement.setChannel("B2C_POCHI");
        disbursement.setReference(reference);

        MpesaAsyncResponse result;
        try {
            result = mpesaService.sendToPochi(resolvedRequest, originatorConversationId);
        } catch (Exception e) {
            log.error("B2Pochi withdrawal threw before a result could be returned — refunding: userId={}",
                    initiatedByUserId, e);
            refund(wallet, request.getAmount());
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
            refund(wallet, request.getAmount());
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

            if (("B2C".equals(d.getChannel()) || "B2C_POCHI".equals(d.getChannel())) && d.getWalletId() != null) {
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

    // ─── Reconciliation from PayPal's Payouts webhook ────────────────────────

    /**
     * Reconciles a PayPal Payouts item webhook (PAYMENT.PAYOUTS-ITEM.*) back
     * to the disbursement that started it, looked up by payout_batch_id
     * (stored as providerReference — see processDisbursement's PAYPAL branch
     * above). Safe to key off the batch id rather than the item id because
     * disbursePaypal only ever sends a single item per batch.
     *
     * transactionStatus is PayPal's resource.transaction_status value
     * (SUCCESS/FAILED/DENIED/BLOCKED/RETURNED/REFUNDED/UNCLAIMED/ONHOLD/...).
     * SUCCESS credits nothing extra (the wallet was already debited
     * up-front in processDisbursement) — it just flips the disbursement to
     * SUCCESS and records the completed transaction, same as
     * completeMpesaDisbursement's B2C branch. A terminal failure status
     * refunds the wallet and marks FAILED. Anything else (UNCLAIMED, held
     * for review, etc.) is logged only — the disbursement stays PENDING for
     * a later webhook event to resolve.
     */
    @Transactional
    public void completePaypalDisbursement(String payoutBatchId, String transactionStatus,
                                            String paypalTransactionId, String errorMessage) {
        Disbursement d = disbursementRepository.findByProviderReference(payoutBatchId).orElse(null);
        if (d == null) {
            log.warn("PayPal payout webhook for unknown payout_batch_id={} — ignoring", payoutBatchId);
            return;
        }

        if (d.getStatus() != DisbursementStatus.PENDING) {
            log.warn("PayPal payout webhook for already-finalized disbursement id={} status={} — ignoring duplicate",
                    d.getId(), d.getStatus());
            return;
        }

        if ("SUCCESS".equals(transactionStatus)) {
            d.setStatus(DisbursementStatus.SUCCESS);
            disbursementRepository.save(d);

            if (d.getWalletId() != null) {
                saveDisbursementTransaction(d.getUserId(), d.getWalletId(), d.getAmount(), d, d.getReference());
            }
            log.info("PayPal disbursement completed: id={} payoutBatchId={} paypalTransactionId={}",
                    d.getId(), payoutBatchId, paypalTransactionId);
        } else if (PAYPAL_TERMINAL_FAILURE_STATUSES.contains(transactionStatus)) {
            if (d.getWalletId() != null) {
                Wallet wallet = walletRepository.findById(d.getWalletId())
                        .orElseThrow(() -> new WalletNotFoundException("Wallet not found: " + d.getWalletId()));
                refund(wallet, d.getAmount());
            }
            d.setStatus(DisbursementStatus.FAILED);
            d.setFailureReason(errorMessage != null && !errorMessage.isBlank() ? errorMessage : transactionStatus);
            disbursementRepository.save(d);
            log.warn("PayPal disbursement failed ({}), refunded where applicable: id={} payoutBatchId={} reason={}",
                    transactionStatus, d.getId(), payoutBatchId, errorMessage);
        } else {
            log.info("PayPal disbursement id={} payoutBatchId={} in non-terminal state={} — awaiting further webhook",
                    d.getId(), payoutBatchId, transactionStatus);
        }
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

    private ProviderResult disbursePaypal(DisbursementRequest request, String destinationEmail) {
        try {
            BigDecimal usdToKesRate = fxRateService.getRate("USD", "KES");
            BigDecimal usdAmount = request.getAmount()
                    .divide(usdToKesRate, 2, java.math.RoundingMode.HALF_UP);
            String batchId = paypalService.processPayout(destinationEmail, usdAmount, "USD");
            log.info("PayPal payout: kesAmount={} usdAmount={} rate={} batchId={}",
                    request.getAmount(), usdAmount, usdToKesRate, batchId);
            return new ProviderResult(true, "PayPal payout initiated (USD " + usdAmount + ")", batchId);
        } catch (Exception e) {
            return new ProviderResult(false, e.getMessage(), null);
        }
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    /**
     * Resolves the phone number a B2Pochi disbursement should be sent to.
     * Prefers the wallet's own pochiPhoneNumber (set via PUT
     * /wallet/pochi-phone) since a user's Pochi la Biashara account can be
     * registered under a different line than their regular mpesaPhoneNumber
     * (used for STK deposits and phone withdrawals) — same "resolved
     * authoritatively here, never from the request" reasoning as
     * mpesaPhoneNumber/paypalEmail elsewhere. Falls back to
     * resolveVerifiedPhoneNumber (mpesaPhoneNumber, then the auth-service
     * profile) if no dedicated Pochi number has been set yet, so nothing
     * breaks for users who haven't configured one.
     */
    private String resolveVerifiedPochiPhoneNumber(String userId, Wallet wallet) {
        if (wallet != null && wallet.getPochiPhoneNumber() != null && !wallet.getPochiPhoneNumber().isBlank()) {
            return wallet.getPochiPhoneNumber();
        }
        return resolveVerifiedPhoneNumber(userId, wallet);
    }

    /**
     * Resolves the phone number a plain M-Pesa phone withdrawal (the generic
     * /disbursements endpoint) should be sent to. Prefers the wallet's own
     * mpesaPhoneNumber (set via PUT /wallet/mpesa-phone) if present — same
     * reasoning as the PayPal email field: the user's own verified choice of
     * destination, resolved authoritatively here rather than taken from the
     * disbursement request. Falls back to the auth-service profile's phone
     * number if the wallet doesn't have one set yet, so nothing breaks for
     * existing users who haven't set a wallet phone number.
     *
     * Also used as the fallback layer for B2Pochi withdrawals — see
     * resolveVerifiedPochiPhoneNumber above, which prefers a dedicated
     * pochiPhoneNumber first and only falls back to this method.
     */
    private String resolveVerifiedPhoneNumber(String userId, Wallet wallet) {
        if (wallet != null && wallet.getMpesaPhoneNumber() != null && !wallet.getMpesaPhoneNumber().isBlank()) {
            return wallet.getMpesaPhoneNumber();
        }

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
                    "No phone number is set on your profile — please add one before requesting for an M-Pesa disbursement.");
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