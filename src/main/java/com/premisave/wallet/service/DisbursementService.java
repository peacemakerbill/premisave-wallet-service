package com.premisave.wallet.service;

import com.premisave.wallet.dto.B2CTopUpRequest;
import com.premisave.wallet.dto.DisbursementRequest;
import com.premisave.wallet.dto.DisbursementResponse;
import com.premisave.wallet.dto.MpesaB2BRequest;
import com.premisave.wallet.entity.Disbursement;
import com.premisave.wallet.entity.Transaction;
import com.premisave.wallet.entity.Wallet;
import com.premisave.wallet.enums.Currency;
import com.premisave.wallet.enums.DisbursementStatus;
import com.premisave.wallet.enums.TransactionStatus;
import com.premisave.wallet.enums.TransactionType;
import com.premisave.wallet.exception.InsufficientFundsException;
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

        // Deduct upfront — refunded on outright rejection or async failure.
        wallet.setBalance(wallet.getBalance().subtract(request.getAmount()));
        walletRepository.save(wallet);

        String reference = request.getReference() != null ? request.getReference() : UUID.randomUUID().toString();

        Disbursement disbursement = new Disbursement();
        disbursement.setUserId(userId);
        disbursement.setWalletId(wallet.getId());
        disbursement.setAmount(request.getAmount());
        disbursement.setDestination(request.getDestination());
        disbursement.setProvider(provider);
        disbursement.setReference(reference);
        disbursement.setStatus(DisbursementStatus.PENDING);

        if ("MPESA".equals(provider)) {
            disbursement.setChannel("B2C");
            var result = mpesaService.sendB2C(request.getDestination(), request.getAmount());

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

        var result = mpesaService.sendB2B(request);

        Disbursement disbursement = new Disbursement();
        disbursement.setUserId(initiatedByUserId);
        disbursement.setAmount(request.getAmount());
        disbursement.setCurrency(Currency.KES);
        disbursement.setDestination(request.getReceiverShortcode());
        disbursement.setProvider("MPESA");
        disbursement.setChannel("B2B");
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

    // ─── B2C Account Top Up (admin/finance-initiated) ───────────────────────

    /**
     * Tops up a B2C shortcode's utility account from Premisave's working
     * account (CommandID BusinessPayToBulk). Purely an internal float-management
     * operation — no end-user wallet is touched, so no refund logic applies on
     * failure. We still record a Disbursement for audit trail;
     * completeMpesaDisbursement() will mark it SUCCESS/FAILED via the existing
     * B2B result callback, and correctly skips creating a wallet Transaction
     * since channel != "B2C".
     *
     * See https://developer.safaricom.co.ke/apis/B2CAccountTopUp
     */
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

    // ─── Reconciliation from Safaricom's ResultURL callback ─────────────────

    /**
     * Called by PaymentCallbackController when Safaricom's real B2C/B2B
     * result arrives. This is the ONLY place a disbursement should be marked
     * SUCCESS or given a completed Transaction record for M-Pesa payouts.
     * Also used to reconcile B2C Account Top Ups (channel="B2C_TOPUP") — those
     * intentionally never get a wallet Transaction since no user wallet is
     * involved.
     */
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

            // B2B/B2C_TOPUP disbursements have no user wallet (userId is the admin
            // who triggered it) — only create a wallet-side Transaction for
            // user-initiated (B2C) payouts.
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

    /**
     * Called on M-Pesa's timeout URL — Safaricom couldn't reach the result URL
     * in time. Leaves the disbursement PENDING (money stays held); the sweeper
     * below will flag it for manual reconciliation if it's still stuck later.
     */
    public void markMpesaDisbursementTimedOut(String conversationId) {
        disbursementRepository.findByProviderReference(conversationId).ifPresentOrElse(d -> {
            log.warn("M-Pesa disbursement queue timeout: id={} conversationId={} — awaiting eventual result or manual reconciliation",
                    d.getId(), conversationId);
        }, () -> log.warn("Timeout callback for unknown ConversationID={}", conversationId));
    }

    // ─── Stuck-disbursement sweeper ──────────────────────────────────────────

    /**
     * Safety net: if Safaricom's ResultURL callback never arrives (network
     * issue, misconfigured URL, etc.), a disbursement could stay PENDING
     * forever with funds held. This doesn't auto-resolve it — resolving
     * definitively requires the M-Pesa Transaction Status API (not yet wired,
     * see MpesaConfig TODO) — but it surfaces anything stuck past 30 minutes
     * so ops can check manually or via Safaricom's portal instead of it going
     * unnoticed.
     */
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
            String currency = request.getCurrency() != null ? request.getCurrency() : "USD";
            String batchId = paypalService.processPayout(request.getDestination(), request.getAmount(), currency);
            return new ProviderResult(true, "PayPal payout queued", batchId);
        } catch (Exception e) {
            return new ProviderResult(false, e.getMessage(), null);
        }
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

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