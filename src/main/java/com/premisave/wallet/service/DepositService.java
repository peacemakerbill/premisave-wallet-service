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
import com.stripe.model.PaymentMethod;
import com.stripe.model.SetupIntent;
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

    public PaymentResponse initiateDeposit(String userId, String userEmail, DepositRequest request) {
        Wallet wallet = walletRepository.findByUserId(userId)
                .orElseThrow(() -> new WalletNotFoundException("Wallet not found. Please create a wallet first."));

        String provider = request.getProvider() != null ? request.getProvider().toUpperCase() : "MPESA";
        String idempotencyKey = UUID.randomUUID().toString();

        return switch (provider) {
            case "MPESA" -> initiateMpesaDeposit(userId, request, wallet, idempotencyKey);
            case "MPESA_TILL" -> initiateExpressCheckoutDeposit(userId, request, wallet);
            case "STRIPE" -> initiateStripeDeposit(userId, userEmail, request, wallet, idempotencyKey);
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

    /**
     * If the wallet already has a saved card (stripeDefaultPaymentMethodId),
     * attempts an off-session charge immediately — no client_secret, no
     * Stripe.js round trip, the deposit just completes. If no card is saved
     * yet, returns a client_secret for a normal Stripe.js confirmation, and
     * requests setup_future_usage so this deposit itself saves the card for
     * next time. Ensures a Stripe Customer exists on the wallet first,
     * creating one lazily if needed.
     */
    private PaymentResponse initiateStripeDeposit(String userId, String userEmail, DepositRequest request,
                                                    Wallet wallet, String idempotencyKey) {
        String currency = request.getCurrency() != null ? request.getCurrency() : "kes";

        String customerId = wallet.getStripeCustomerId();
        if (customerId == null) {
            customerId = stripeService.createCustomer(userEmail, userId);
            wallet.setStripeCustomerId(customerId);
            walletRepository.save(wallet);
        }

        StripeService.StripePaymentIntentResult result = stripeService.createOrChargePaymentIntent(
                customerId, wallet.getStripeDefaultPaymentMethodId(), request.getAmount(), currency, idempotencyKey, userId);

        log.info("Stripe deposit attempt: userId={} piId={} status={} requiresAction={}",
                userId, result.paymentIntentId(), result.status(), result.requiresAction());

        savePendingTransaction(userId, wallet.getId(), TransactionType.DEPOSIT,
                request.getAmount(), Currency.KES, "Stripe deposit (pending payment confirmation)", idempotencyKey);

        if ("succeeded".equals(result.status())) {
            // Off-session charge on the saved card went through immediately.
            creditWalletFromStripeCallback(idempotencyKey, request.getAmount(), result.paymentIntentId(),
                    currency, result.customerId(), result.paymentMethodId());
            return new PaymentResponse(true, result.paymentIntentId(), "Deposit successful (charged saved card).");
        }

        return new PaymentResponse(true, result.clientSecret(),
                "Stripe PaymentIntent created. Use the client_secret to confirm payment.");
    }

    /**
     * Starts a SetupIntent so the frontend can attach a card to this wallet's
     * Stripe Customer via Stripe.js/Elements WITHOUT making a payment —
     * useful for a "manage payment method" settings screen. Creates the
     * Customer lazily if this wallet doesn't have one yet.
     */
    @Transactional
    public Map<String, String> createStripeSetupIntent(String userId, String email) {
        Wallet wallet = walletRepository.findByUserId(userId)
                .orElseThrow(() -> new WalletNotFoundException("Wallet not found. Please create a wallet first."));

        String customerId = wallet.getStripeCustomerId();
        if (customerId == null) {
            customerId = stripeService.createCustomer(email, userId);
            wallet.setStripeCustomerId(customerId);
            walletRepository.save(wallet);
        }

        SetupIntent setupIntent = stripeService.createSetupIntent(customerId);
        return Map.of("clientSecret", setupIntent.getClientSecret(), "setupIntentId", setupIntent.getId());
    }

    /**
     * Called by the frontend after Stripe.js confirms the SetupIntent
     * (stripe.confirmCardSetup). Verifies it belongs to this user's Stripe
     * Customer, then saves the resulting PaymentMethod as the wallet's
     * default for future one-click deposits, along with display-only
     * brand/last4. The webhook handler below is a backstop for the same flow.
     */
    @Transactional
    public void confirmStripeSetupIntent(String setupIntentId, String userId) {
        SetupIntent setupIntent = stripeService.retrieveSetupIntent(setupIntentId);

        if (!"succeeded".equals(setupIntent.getStatus())) {
            throw new IllegalStateException("Card setup has not completed yet (status: " + setupIntent.getStatus() + ")");
        }

        Wallet wallet = walletRepository.findByUserId(userId)
                .orElseThrow(() -> new WalletNotFoundException("Wallet not found for userId: " + userId));

        if (!setupIntent.getCustomer().equals(wallet.getStripeCustomerId())) {
            throw new IllegalArgumentException("This setup intent does not belong to the authenticated user");
        }

        attachSavedCard(wallet, setupIntent.getPaymentMethod());
    }

    /**
     * Webhook backstop for setup_intent.succeeded — resolves the wallet by
     * Stripe customerId rather than by an authenticated caller, same
     * reasoning as the PayPal webhook having no ownership check.
     */
    @Transactional
    public void attachSavedCardByCustomerId(String customerId, String paymentMethodId) {
        walletRepository.findByStripeCustomerId(customerId).ifPresentOrElse(
                wallet -> attachSavedCard(wallet, paymentMethodId),
                () -> log.warn("setup_intent.succeeded webhook: no wallet found for Stripe customerId={}", customerId));
    }

    private void attachSavedCard(Wallet wallet, String paymentMethodId) {
        if (paymentMethodId == null) return;

        wallet.setStripeDefaultPaymentMethodId(paymentMethodId);
        try {
            PaymentMethod pm = stripeService.retrievePaymentMethod(paymentMethodId);
            if (pm.getCard() != null) {
                wallet.setStripeCardBrand(pm.getCard().getBrand());
                wallet.setStripeCardLast4(pm.getCard().getLast4());
            }
        } catch (Exception e) {
            // Non-fatal — the payment method is still usable even if we
            // couldn't fetch display details for the UI.
            log.warn("Saved card attached but failed to fetch display details: {}", e.getMessage());
        }
        walletRepository.save(wallet);
        log.info("Stripe saved card attached: walletId={} paymentMethodId={}", wallet.getId(), paymentMethodId);
    }

    /**
     * Reconciles a Stripe deposit — called synchronously from
     * initiateStripeDeposit (off-session success), by the webhook
     * (payment_intent.succeeded), or by confirmStripeDeposit. Matched back
     * to the pending Transaction via `reference` (the idempotencyKey stored
     * as PaymentIntent metadata at initiation). Idempotent: an
     * already-COMPLETED transaction is a no-op. When a paymentMethodId is
     * present, also keeps the wallet's saved-card fields in sync.
     */
    @Transactional
    public void creditWalletFromStripeCallback(String reference, BigDecimal amount, String paymentIntentId,
                                                String currency, String customerId, String paymentMethodId) {
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

        if (paymentMethodId != null && !paymentMethodId.equals(wallet.getStripeDefaultPaymentMethodId())) {
            wallet.setStripeDefaultPaymentMethodId(paymentMethodId);
            if (customerId != null) wallet.setStripeCustomerId(customerId);
            try {
                PaymentMethod pm = stripeService.retrievePaymentMethod(paymentMethodId);
                if (pm.getCard() != null) {
                    wallet.setStripeCardBrand(pm.getCard().getBrand());
                    wallet.setStripeCardLast4(pm.getCard().getLast4());
                }
            } catch (Exception e) {
                log.warn("Deposit succeeded but failed to fetch card display details: {}", e.getMessage());
            }
        }

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
     * Frontend-triggered confirm — mirrors confirmPaypalDeposit. Retrieves
     * the PaymentIntent directly from Stripe, verifies ownership, then
     * reconciles via the same path the webhook uses.
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
        creditWalletFromStripeCallback(reference, amount, paymentIntentId, pi.getCurrency(),
                pi.getCustomer(), pi.getPaymentMethod());

        return new PaymentResponse(true, tx.getId(), "Stripe deposit successful");
    }

    // ─── PayPal ──────────────────────────────────────────────────────────────

    private PaymentResponse initiatePaypalDeposit(String userId, DepositRequest request,
                                                    Wallet wallet, String idempotencyKey) {
        String requestedCurrency = request.getCurrency() != null ? request.getCurrency().toUpperCase() : "USD";
        if (!"USD".equals(requestedCurrency)) {
            throw new IllegalArgumentException("PayPal deposits must be in USD (got: " + requestedCurrency + ")");
        }

        BigDecimal usdAmount = request.getAmount();
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

    @Transactional
    public PaymentResponse confirmPaypalDeposit(String orderId) {
        Transaction tx = transactionRepository.findByReference(orderId)
                .orElseThrow(() -> new IllegalStateException(
                        "No pending transaction found for PayPal orderId=" + orderId));
        return confirmPaypalDepositInternal(tx, orderId);
    }

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