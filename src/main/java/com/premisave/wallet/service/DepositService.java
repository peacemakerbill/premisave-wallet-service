package com.premisave.wallet.service;

import com.premisave.wallet.dto.B2BExpressCheckoutResponse;
import com.premisave.wallet.dto.DepositRequest;
import com.premisave.wallet.dto.MpesaStkPushRequest;
import com.premisave.wallet.dto.PaymentResponse;
import com.premisave.wallet.entity.Transaction;
import com.premisave.wallet.entity.Wallet;
import com.premisave.wallet.enums.Currency;
import com.premisave.wallet.enums.TransactionStatus;
import com.premisave.wallet.enums.TransactionType;
import com.premisave.wallet.exception.PaypalCaptureException;
import com.premisave.wallet.exception.WalletNotFoundException;
import com.premisave.wallet.repository.TransactionRepository;
import com.premisave.wallet.repository.WalletRepository;
import com.stripe.model.PaymentIntent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class DepositService {

    private final WalletRepository walletRepository;
    private final TransactionRepository transactionRepository;
    private final MpesaService mpesaService;
    private final StripeService stripeService;
    private final PaypalService paypalService;
    private final FxRateService fxRateService;

    // No static PayPal rate here anymore — see initiatePaypalDeposit, which
    // now fetches a live rate from Frankfurter (FxRateService) on every call.

    /**
     * Routes deposit initiation to the correct payment provider.
     *
     * Response meanings by provider:
     *  - MPESA      → reference = CheckoutRequestID (STK push sent to phone)
     *  - MPESA_TILL → reference = our generated RequestRefID (USSD push sent to till)
     *  - STRIPE     → reference = Stripe client_secret (frontend confirms with Stripe.js)
     *  - PAYPAL     → reference = PayPal approveUrl (redirect user to PayPal)
     */
    public PaymentResponse initiateDeposit(String userId, String userEmail, DepositRequest request) {
        Wallet wallet = walletRepository.findByUserId(userId)
                .orElseThrow(() -> new WalletNotFoundException("Wallet not found. Please create a wallet first."));

        String provider = request.getProvider() != null ? request.getProvider().toUpperCase() : "MPESA";
        String idempotencyKey = UUID.randomUUID().toString();

        return switch (provider) {
            case "MPESA" -> initiateMpesaDeposit(userId, request, wallet, idempotencyKey);
            case "MPESA_TILL" -> initiateExpressCheckoutDeposit(userId, request, wallet);
            case "STRIPE" -> initiateStripeDeposit(userId, request, wallet, idempotencyKey);
            case "PAYPAL" -> initiatePaypalDeposit(userId, request, wallet, idempotencyKey);
            default -> new PaymentResponse(false, null, "Unsupported deposit provider: " + provider);
        };
    }

    // ─── M-Pesa STK Push ─────────────────────────────────────────────────────

    private PaymentResponse initiateMpesaDeposit(String userId, DepositRequest request,
                                                   Wallet wallet, String idempotencyKey) {
        if (request.getPhoneNumber() == null || request.getPhoneNumber().isBlank()) {
            throw new IllegalArgumentException("phoneNumber is required for M-Pesa deposits");
        }

        MpesaStkPushRequest stkRequest = new MpesaStkPushRequest();
        stkRequest.setPhoneNumber(request.getPhoneNumber());
        stkRequest.setAmount(request.getAmount());
        stkRequest.setAccountReference("PREMISAVE-" + userId);

        String checkoutId = mpesaService.initiateStkPush(stkRequest);
        log.info("M-Pesa STK push: userId={} checkoutId={}", userId, checkoutId);

        savePendingTransaction(userId, wallet.getId(), TransactionType.DEPOSIT,
                request.getAmount(), Currency.KES, "M-Pesa deposit (pending STK confirmation)", checkoutId);

        return new PaymentResponse(true, checkoutId,
                "M-Pesa STK push sent. Enter your PIN to complete the deposit.");
    }

    // ─── M-Pesa B2B Express Checkout (USSD Push to Till) ────────────────────

    private PaymentResponse initiateExpressCheckoutDeposit(String userId, DepositRequest request, Wallet wallet) {
        if (request.getPayerTillNumber() == null || request.getPayerTillNumber().isBlank()) {
            throw new IllegalArgumentException("payerTillNumber is required for MPESA_TILL deposits");
        }

        String requestRefId = UUID.randomUUID().toString();
        String paymentRef = "PREMISAVE-" + userId;

        B2BExpressCheckoutResponse result = mpesaService.initiateExpressCheckout(
                request.getPayerTillNumber(), request.getAmount(), paymentRef, requestRefId);

        if (!result.isSuccess()) {
            return new PaymentResponse(false, requestRefId, result.getMessage());
        }

        savePendingTransaction(userId, wallet.getId(), TransactionType.DEPOSIT,
                request.getAmount(), Currency.KES, "M-Pesa till deposit (pending USSD confirmation)", requestRefId);

        return new PaymentResponse(true, requestRefId,
                "USSD push sent to your till. Approve on your phone to complete the deposit.");
    }

    // ─── Stripe ──────────────────────────────────────────────────────────────

    private PaymentResponse initiateStripeDeposit(String userId, DepositRequest request,
                                                    Wallet wallet, String idempotencyKey) {
        String currency = request.getCurrency() != null ? request.getCurrency() : "kes";
        String clientSecret = stripeService.createPaymentIntent(request.getAmount(), currency, idempotencyKey, userId);

        log.info("Stripe PaymentIntent created: userId={}", userId);

        savePendingTransaction(userId, wallet.getId(), TransactionType.DEPOSIT,
                request.getAmount(), Currency.KES, "Stripe deposit (pending payment confirmation)", idempotencyKey);

        return new PaymentResponse(true, clientSecret,
                "Stripe PaymentIntent created. Use the client_secret to confirm payment.");
    }

    /**
     * Reconciles a Stripe deposit — called by the webhook (payment_intent.succeeded,
     * see PaymentCallbackController.stripeWebhook) or by confirmStripeDeposit
     * as a synchronous backstop. Matched back to the pending Transaction via
     * `reference`, which is the same idempotencyKey stored as the
     * PaymentIntent's `idempotency_key` metadata at initiation — same
     * matching pattern as the M-Pesa STK and PayPal flows. Idempotent: an
     * already-COMPLETED transaction is a no-op, so a webhook retry or a
     * webhook/confirm-endpoint race both resolve safely without double-crediting.
     */
    @Transactional
    public void creditWalletFromStripeCallback(String reference, BigDecimal amount,
                                                String paymentIntentId, String currency) {
        Transaction tx = transactionRepository.findByReference(reference).orElse(null);

        if (tx == null) {
            log.warn("Stripe reconciliation: no pending transaction found for reference={} (paymentIntentId={}) — " +
                    "cannot credit; needs manual review", reference, paymentIntentId);
            return;
        }

        if (tx.getStatus() == TransactionStatus.COMPLETED) {
            log.info("Stripe deposit already processed for reference={} — skipping duplicate delivery", reference);
            return;
        }

        Wallet wallet = walletRepository.findById(tx.getWalletId())
                .orElseThrow(() -> new WalletNotFoundException("Wallet not found: " + tx.getWalletId()));

        wallet.setBalance(wallet.getBalance().add(amount));
        walletRepository.save(wallet);

        tx.setStatus(TransactionStatus.COMPLETED);
        tx.setAmount(amount);
        tx.setCurrency(resolveCurrency(currency));
        tx.setProviderReference(paymentIntentId);
        tx.setDescription("Stripe deposit (PaymentIntent " + paymentIntentId + ")");
        transactionRepository.save(tx);

        log.info("Wallet credited via Stripe: reference={} amount={} piId={}", reference, amount, paymentIntentId);
    }

    /**
     * Frontend-triggered confirm — mirrors confirmPaypalDeposit. Retrieves the
     * PaymentIntent directly from Stripe (no waiting on the webhook), verifies
     * ownership, then reconciles via the same path the webhook uses.
     * Essential for sandbox testing where webhook delivery isn't configured.
     */
    @Transactional
    public PaymentResponse confirmStripeDeposit(String paymentIntentId, String callerUserId) {
        PaymentIntent pi = stripeService.retrievePaymentIntent(paymentIntentId);

        String reference = pi.getMetadata() != null ? pi.getMetadata().get("idempotency_key") : null;
        if (reference == null) {
            return new PaymentResponse(false, paymentIntentId,
                    "This PaymentIntent has no idempotency_key metadata and cannot be reconciled.");
        }

        Transaction tx = transactionRepository.findByReference(reference)
                .orElseThrow(() -> new IllegalStateException("No pending transaction found for Stripe reference=" + reference));

        if (!tx.getUserId().equals(callerUserId)) {
            throw new IllegalArgumentException("This Stripe payment does not belong to the authenticated user");
        }

        if (tx.getStatus() == TransactionStatus.COMPLETED) {
            return new PaymentResponse(true, tx.getId(), "Deposit already completed");
        }

        if (!"succeeded".equals(pi.getStatus())) {
            return new PaymentResponse(false, tx.getId(),
                    "Payment has not completed yet (status: " + pi.getStatus() + ")");
        }

        BigDecimal amount = BigDecimal.valueOf(pi.getAmount()).divide(BigDecimal.valueOf(100));
        creditWalletFromStripeCallback(reference, amount, paymentIntentId, pi.getCurrency());

        return new PaymentResponse(true, tx.getId(), "Stripe deposit successful");
    }

    // ─── PayPal ──────────────────────────────────────────────────────────────

    /**
     * PayPal doesn't support KES as a transaction currency for Kenyan
     * merchant accounts, so the requested amount is always processed with
     * PayPal in USD and converted to the wallet's KES balance using a live
     * Frankfurter rate (see FxRateService), fetched fresh for every deposit —
     * no caching, no static fallback. The rate is fetched BEFORE creating the
     * PayPal order: if Frankfurter is unreachable, we abort here rather than
     * leaving an orphaned PayPal order with no way to price it in KES.
     * Currency is enforced to USD explicitly rather than silently
     * reinterpreted, to avoid mis-crediting on a mismatched request.
     */
    private PaymentResponse initiatePaypalDeposit(String userId, DepositRequest request,
                                                    Wallet wallet, String idempotencyKey) {
        String requestedCurrency = request.getCurrency() != null ? request.getCurrency().toUpperCase() : "USD";
        if (!"USD".equals(requestedCurrency)) {
            throw new IllegalArgumentException("PayPal deposits must be in USD (got: " + requestedCurrency + ")");
        }

        BigDecimal usdAmount = request.getAmount();

        // Fetch the live rate first — fail fast before touching PayPal at all
        // if Frankfurter is down.
        BigDecimal usdToKesRate = fxRateService.getRate("USD", "KES");

        Map<String, String> result = paypalService.createOrder(usdAmount, "USD", idempotencyKey);

        String orderId    = result.get("orderId");
        String approveUrl = result.get("approveUrl");

        BigDecimal kesEquivalent = usdAmount.multiply(usdToKesRate).setScale(2, RoundingMode.HALF_UP);

        log.info("PayPal Order created: userId={} orderId={} usdAmount={} kesEquivalent={} rate={}",
                userId, orderId, usdAmount, kesEquivalent, usdToKesRate);

        Transaction tx = new Transaction();
        tx.setUserId(userId);
        tx.setWalletId(wallet.getId());
        tx.setType(TransactionType.DEPOSIT);
        tx.setStatus(TransactionStatus.PENDING);
        tx.setAmount(kesEquivalent);
        tx.setCurrency(Currency.KES);
        tx.setDescription("PayPal deposit (pending approval) - USD " + usdAmount
                + " @ live rate " + usdToKesRate);
        tx.setReference(orderId);
        transactionRepository.save(tx);

        return new PaymentResponse(true, approveUrl,
                "Redirect the user to the PayPal approval URL to complete the deposit. "
                        + "USD " + usdAmount + " will be credited as approximately KES " + kesEquivalent + ".");
    }

    /**
     * Reconciles a PayPal deposit — called by the frontend immediately after
     * the user approves on PayPal (see WalletController.confirmPaypalDeposit),
     * or by the PayPal webhook as a backstop if the frontend call never
     * happens. Matched back to the pending Transaction via orderId (stored
     * as `reference` at initiation), same pattern as the M-Pesa STK callback.
     * Idempotent: an already-completed transaction, or PayPal reporting
     * "already captured", both no-op safely rather than double-crediting.
     *
     * No ownership check — used by the webhook, which has no authenticated
     * caller. See the overload below for the user-facing confirm endpoint.
     */
    @Transactional
    public PaymentResponse confirmPaypalDeposit(String orderId) {
        Transaction tx = transactionRepository.findByReference(orderId)
                .orElseThrow(() -> new IllegalStateException(
                        "No pending transaction found for PayPal orderId=" + orderId));
        return confirmPaypalDepositInternal(tx, orderId);
    }

    /**
     * Same as above, but verifies the caller actually owns this PayPal order
     * before capturing — used by the user-facing confirm endpoint so one
     * authenticated user can't trigger a capture against another user's
     * pending deposit.
     */
    @Transactional
    public PaymentResponse confirmPaypalDeposit(String orderId, String callerUserId) {
        Transaction tx = transactionRepository.findByReference(orderId)
                .orElseThrow(() -> new IllegalStateException(
                        "No pending transaction found for PayPal orderId=" + orderId));

        if (!tx.getUserId().equals(callerUserId)) {
            throw new IllegalArgumentException("This PayPal order does not belong to the authenticated user");
        }
        return confirmPaypalDepositInternal(tx, orderId);
    }

    private PaymentResponse confirmPaypalDepositInternal(Transaction tx, String orderId) {
        if (tx.getStatus() == TransactionStatus.COMPLETED) {
            log.info("PayPal deposit already processed for orderId={} — skipping duplicate capture", orderId);
            return new PaymentResponse(true, tx.getId(), "Deposit already completed");
        }

        if (tx.getStatus() == TransactionStatus.FAILED) {
            return new PaymentResponse(false, tx.getId(),
                    "This PayPal deposit previously failed and cannot be retried with the same order.");
        }

        String captureId;
        try {
            captureId = paypalService.captureOrder(orderId);
        } catch (PaypalCaptureException e) {
            if (e.isAlreadyCaptured()) {
                // PayPal says it's captured but our own record is still PENDING —
                // an inconsistent state (e.g. webhook and frontend confirm raced,
                // or a prior attempt crashed after PayPal-side capture but before
                // our DB write). Log loudly for manual reconciliation rather than
                // silently crediting twice or guessing which figure is correct.
                log.error("PayPal reports orderId={} already captured but local transaction {} is still {} — " +
                        "manual reconciliation required", orderId, tx.getId(), tx.getStatus());
                return new PaymentResponse(false, tx.getId(),
                        "This deposit is in an inconsistent state and needs manual review. Please contact support.");
            }
            markPaypalTransactionFailed(orderId, e.getMessage());
            return new PaymentResponse(false, tx.getId(), "PayPal capture failed: " + e.getMessage());
        } catch (Exception e) {
            markPaypalTransactionFailed(orderId, e.getMessage());
            return new PaymentResponse(false, tx.getId(), "PayPal capture failed: " + e.getMessage());
        }

        Wallet wallet = walletRepository.findById(tx.getWalletId())
                .orElseThrow(() -> new WalletNotFoundException("Wallet not found: " + tx.getWalletId()));

        wallet.setBalance(wallet.getBalance().add(tx.getAmount()));
        walletRepository.save(wallet);

        tx.setStatus(TransactionStatus.COMPLETED);
        tx.setProviderReference(captureId);
        tx.setDescription("PayPal deposit (capture " + captureId + ")");
        transactionRepository.save(tx);

        log.info("Wallet credited via PayPal: orderId={} amount={} captureId={}", orderId, tx.getAmount(), captureId);
        return new PaymentResponse(true, tx.getId(), "PayPal deposit successful");
    }

    @Transactional
    public void markPaypalTransactionFailed(String orderId, String reason) {
        transactionRepository.findByReference(orderId).ifPresentOrElse(tx -> {
            if (tx.getStatus() == TransactionStatus.COMPLETED) {
                log.warn("Ignoring failure callback for already-completed PayPal orderId={}", orderId);
                return;
            }
            tx.setStatus(TransactionStatus.FAILED);
            tx.setDescription("PayPal deposit failed: " + reason);
            transactionRepository.save(tx);
            log.warn("PayPal deposit failed: orderId={} reason={}", orderId, reason);
        }, () -> log.warn("Failure callback for unknown PayPal orderId={}: {}", orderId, reason));
    }

    // ─── Other callbacks ─────────────────────────────────────────────────────

    @Transactional
    public void creditWalletFromStkCallback(String checkoutRequestId, BigDecimal amount,
                                             String mpesaReceiptNumber, String phoneNumber) {
        Transaction tx = transactionRepository.findByReference(checkoutRequestId)
                .orElseThrow(() -> new IllegalStateException(
                        "No pending transaction found for CheckoutRequestID=" + checkoutRequestId));

        if (tx.getStatus() == TransactionStatus.COMPLETED) {
            log.warn("STK callback already processed for CheckoutRequestID={} — skipping duplicate credit",
                    checkoutRequestId);
            return;
        }

        Wallet wallet = walletRepository.findById(tx.getWalletId())
                .orElseThrow(() -> new WalletNotFoundException("Wallet not found: " + tx.getWalletId()));

        wallet.setBalance(wallet.getBalance().add(amount));
        walletRepository.save(wallet);

        tx.setStatus(TransactionStatus.COMPLETED);
        tx.setAmount(amount);
        tx.setProviderReference(mpesaReceiptNumber);
        tx.setDescription("M-Pesa STK deposit from " + phoneNumber + " (receipt " + mpesaReceiptNumber + ")");
        transactionRepository.save(tx);

        log.info("Wallet credited via M-Pesa STK: checkoutRequestId={} amount={} receipt={}",
                checkoutRequestId, amount, mpesaReceiptNumber);
    }

    @Transactional
    public void markStkTransactionFailed(String checkoutRequestId, String resultDesc) {
        transactionRepository.findByReference(checkoutRequestId).ifPresentOrElse(tx -> {
            tx.setStatus(TransactionStatus.FAILED);
            tx.setDescription("M-Pesa STK push failed: " + resultDesc);
            transactionRepository.save(tx);
            log.warn("STK push failed: checkoutRequestId={} reason={}", checkoutRequestId, resultDesc);
        }, () -> log.warn("STK failure callback for unknown CheckoutRequestID={}: {}", checkoutRequestId, resultDesc));
    }

    @Transactional
    public void creditWalletFromExpressCheckout(String requestRefId, BigDecimal amount,
                                                 String transactionId, String resultDesc, boolean success) {
        Transaction tx = transactionRepository.findByReference(requestRefId)
                .orElseThrow(() -> new IllegalStateException(
                        "No pending transaction found for RequestRefID=" + requestRefId));

        if (tx.getStatus() == TransactionStatus.COMPLETED) {
            log.warn("Express Checkout callback already processed for RequestRefID={} — skipping duplicate credit",
                    requestRefId);
            return;
        }

        if (!success) {
            tx.setStatus(TransactionStatus.FAILED);
            tx.setDescription("M-Pesa till deposit failed: " + resultDesc);
            transactionRepository.save(tx);
            log.warn("Express Checkout deposit failed: requestRefId={} reason={}", requestRefId, resultDesc);
            return;
        }

        Wallet wallet = walletRepository.findById(tx.getWalletId())
                .orElseThrow(() -> new WalletNotFoundException("Wallet not found: " + tx.getWalletId()));

        wallet.setBalance(wallet.getBalance().add(amount));
        walletRepository.save(wallet);

        tx.setStatus(TransactionStatus.COMPLETED);
        tx.setAmount(amount);
        tx.setProviderReference(transactionId);
        tx.setDescription("M-Pesa till deposit (receipt " + transactionId + ")");
        transactionRepository.save(tx);

        log.info("Wallet credited via B2B Express Checkout: requestRefId={} amount={} receipt={}",
                requestRefId, amount, transactionId);
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private void savePendingTransaction(String userId, String walletId, TransactionType type,
                                         BigDecimal amount, Currency currency,
                                         String description, String reference) {
        Transaction tx = new Transaction();
        tx.setUserId(userId);
        tx.setWalletId(walletId);
        tx.setType(type);
        tx.setStatus(TransactionStatus.PENDING);
        tx.setAmount(amount);
        tx.setCurrency(currency);
        tx.setDescription(description);
        tx.setReference(reference);
        transactionRepository.save(tx);
    }

    private Currency resolveCurrency(String currency) {
        if (currency == null) return Currency.KES;
        return switch (currency.toUpperCase()) {
            case "USD" -> Currency.USD;
            case "EUR" -> Currency.EUR;
            default    -> Currency.KES;
        };
    }
}