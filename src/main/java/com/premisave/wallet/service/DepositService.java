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
import com.premisave.wallet.exception.WalletFrozenException;
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

        if (wallet.isFrozen()) {
            throw new WalletFrozenException("Wallet is frozen — deposits are not allowed until it is unfrozen");
        }

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
        String phoneNumber = (request.getPhoneNumber() != null && !request.getPhoneNumber().isBlank())
                ? request.getPhoneNumber()
                : wallet.getMpesaPhoneNumber();

        if (phoneNumber == null || phoneNumber.isBlank()) {
            throw new IllegalArgumentException(
                    "Please enter a phone number for this M-Pesa deposit, or save one in your wallet settings so you don't have to type it every time.");
        }

        MpesaStkPushRequest stkRequest = new MpesaStkPushRequest();
        stkRequest.setPhoneNumber(phoneNumber);
        stkRequest.setAmount(request.getAmount());
        stkRequest.setAccountReference("PREMISAVE");

        MpesaService.StkPushResult result = mpesaService.initiateStkPush(stkRequest);

        if (!result.success()) {
            log.warn("M-Pesa STK push rejected: userId={} reason={}", userId, result.errorMessage());
            return new PaymentResponse(false, null, "M-Pesa STK push failed: " + result.errorMessage());
        }

        log.info("M-Pesa STK push: userId={} checkoutId={}", userId, result.checkoutRequestId());

        savePendingTransaction(userId, wallet.getId(), TransactionType.DEPOSIT,
                request.getAmount(), Currency.KES, "M-Pesa deposit (pending STK confirmation)",
                result.checkoutRequestId());

        String message = (result.customerMessage() != null && !result.customerMessage().isBlank())
                ? result.customerMessage()
                : "M-Pesa STK push sent. Enter your PIN to complete the deposit.";

        return new PaymentResponse(true, result.checkoutRequestId(), message);
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
            creditWalletFromStripeCallback(idempotencyKey, request.getAmount(), result.paymentIntentId(),
                    currency, result.customerId(), result.paymentMethodId());
            return new PaymentResponse(true, result.paymentIntentId(), "Deposit successful (charged saved card).");
        }

        return new PaymentResponse(true, result.clientSecret(),
                "Stripe PaymentIntent created. Use the client_secret to confirm payment.");
    }

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
            log.warn("Saved card attached but failed to fetch display details: {}", e.getMessage());
        }
        walletRepository.save(wallet);
        log.info("Stripe saved card attached: walletId={} paymentMethodId={}", wallet.getId(), paymentMethodId);
    }

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

    /**
     * Initiates a PayPal deposit. If the wallet already has a saved PayPal
     * account (walletPaypalVaultId set), reuses it via the vault_id path.
     *
     * IMPORTANT: when a saved vault_id is reused and no re-authentication is
     * required, PayPal captures the order synchronously as part of the
     * createOrder() response itself (approveUrl == null AND status ==
     * "COMPLETED") — there is no separate capture step to perform, and
     * calling captureOrder() on it would fail with ORDER_ALREADY_CAPTURED.
     * So credit directly from the create-order response in that case. If a
     * saved vault_id exists but capture didn't complete synchronously for
     * some reason, fall through to the normal confirm/capture path, which
     * itself now reconciles an ORDER_ALREADY_CAPTURED race if one occurs
     * (see confirmPaypalDepositInternal).
     */
    private PaymentResponse initiatePaypalDeposit(String userId, DepositRequest request,
                                                    Wallet wallet, String idempotencyKey) {
        String requestedCurrency = request.getCurrency() != null ? request.getCurrency().toUpperCase() : "USD";
        if (!"USD".equals(requestedCurrency)) {
            throw new IllegalArgumentException("PayPal deposits must be in USD (got: " + requestedCurrency + ")");
        }

        BigDecimal usdAmount = request.getAmount();
        BigDecimal usdToKesRate = fxRateService.getRate("USD", "KES");

        String existingVaultId = wallet.getPaypalVaultId();
        String existingCustomerId = wallet.getPaypalCustomerId();

        PaypalService.CreateOrderResult result = paypalService.createOrder(
                usdAmount, "USD", idempotencyKey, existingVaultId, existingCustomerId, true);

        String orderId = result.orderId();
        BigDecimal kesEquivalent = usdAmount.multiply(usdToKesRate).setScale(2, RoundingMode.HALF_UP);

        log.info("PayPal Order created: userId={} orderId={} usdAmount={} kesEquivalent={} rate={} vaulted={} requiresAction={}",
                userId, orderId, usdAmount, kesEquivalent, usdToKesRate,
                existingVaultId != null, result.approveUrl() != null);

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

        if (result.approveUrl() == null) {
            if ("COMPLETED".equals(result.status()) && result.captureId() != null) {
                // Vault reuse auto-captured synchronously at order creation —
                // credit directly from what createOrder() already returned
                // instead of calling captureOrder() again.
                PaypalService.CaptureResult captureResult = new PaypalService.CaptureResult(
                        result.captureId(), result.vaultId(), result.customerId(),
                        result.payerEmail(), result.vaultStatus());
                PaymentResponse credited = creditPaypalTransaction(tx, captureResult);
                if (credited.isSuccess()) {
                    return new PaymentResponse(true, orderId,
                            "Deposit successful using your saved PayPal account.");
                }
                return credited;
            }

            // No payer action required but not reported completed above
            // (shouldn't normally happen) — fall back to the confirm path,
            // which now reconciles ORDER_ALREADY_CAPTURED races too.
            PaymentResponse captured = confirmPaypalDepositInternal(tx, orderId);
            if (captured.isSuccess()) {
                return new PaymentResponse(true, orderId,
                        "Deposit successful using your saved PayPal account.");
            }
            return captured;
        }

        return new PaymentResponse(true, result.approveUrl(),
                "Redirect the user to the PayPal approval URL to complete the deposit. "
                        + "USD " + usdAmount + " will be credited as approximately KES " + kesEquivalent + ".");
    }
    
    /**
     * Starts a standalone PayPal account link — no payment, mirrors
     * createStripeSetupIntent. Rejects up front if an account is already
     * linked, so the frontend can show the message before ever redirecting
     * the user to PayPal.
     *
     * Persists the issued setupTokenId on the wallet as
     * pendingPaypalSetupTokenId — confirmPaypalLink checks the setupTokenId
     * submitted at confirm time against this value, since PayPal's
     * payment-token response does not reliably echo back our merchant
     * customer.id for PayPal-wallet vaulting (see confirmPaypalLink).
     */
    @Transactional
    public Map<String, String> createPaypalLinkToken(String userId) {
        Wallet wallet = walletRepository.findByUserId(userId)
                .orElseThrow(() -> new WalletNotFoundException("Wallet not found. Please create a wallet first."));

        if (wallet.getPaypalVaultId() != null) {
            throw new IllegalStateException(
                    "A PayPal account (" + wallet.getPaypalConnectedEmail() + ") is already linked to this wallet. "
                            + "Disconnect it first before linking a new one.");
        }

        PaypalService.SetupTokenResult result = paypalService.createSetupToken(userId);
        log.info("PayPal setup token created for account linking: userId={} setupTokenId={}", userId, result.setupTokenId());

        wallet.setPendingPaypalSetupTokenId(result.setupTokenId());
        walletRepository.save(wallet);

        return Map.of("setupTokenId", result.setupTokenId(), "approveUrl", result.approveUrl());
    }

    /**
     * Called by the frontend after the user approves the PayPal Vault
     * setup token (returns from PayPal's approval redirect). Exchanges it
     * for the real vault_id and saves it on the wallet.
     *
     * Security: verifies the submitted setupTokenId matches the one this
     * wallet was issued by createPaypalLinkToken (pendingPaypalSetupTokenId)
     * — NOT PayPal's returned customer.id. For PayPal-wallet vaulting
     * (unlike card vaulting), PayPal's payment-token response returns its
     * own PayPal-generated customer.id, unrelated to whatever merchant
     * customer.id was supplied at setup-token creation, so that value can't
     * be used to verify ownership. Checking against the setupTokenId we
     * persisted ourselves is the reliable binding.
     */
    @Transactional
    public void confirmPaypalLink(String setupTokenId, String userId) {
        Wallet wallet = walletRepository.findByUserId(userId)
                .orElseThrow(() -> new WalletNotFoundException("Wallet not found for userId: " + userId));

        if (wallet.getPaypalVaultId() != null) {
            throw new IllegalStateException(
                    "A PayPal account (" + wallet.getPaypalConnectedEmail() + ") is already linked to this wallet. "
                            + "Disconnect it first before linking a new one.");
        }

        if (setupTokenId == null || !setupTokenId.equals(wallet.getPendingPaypalSetupTokenId())) {
            log.error("PayPal link confirm rejected — setupTokenId mismatch: walletId={} expected={} got={}",
                    wallet.getId(), wallet.getPendingPaypalSetupTokenId(), setupTokenId);
            throw new IllegalArgumentException("This PayPal setup token does not belong to the authenticated user");
        }

        PaypalService.LinkAccountResult result = paypalService.createPaymentTokenFromSetupToken(setupTokenId);

        wallet.setPaypalVaultId(result.vaultId());
        wallet.setPaypalCustomerId(result.customerId());
        wallet.setPaypalConnectedEmail(result.payerEmail());
        wallet.setPendingPaypalSetupTokenId(null);
        walletRepository.save(wallet);

        log.info("PayPal account linked: walletId={} vaultId={} customerId={} email={}",
                wallet.getId(), result.vaultId(), result.customerId(), result.payerEmail());
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

    /**
     * Captures the order and credits the wallet.
     *
     * If PayPal reports ORDER_ALREADY_CAPTURED (a race between this call and
     * the webhook, or — the common case — a vault-reuse order that was
     * actually auto-captured at creation time and reached this path anyway),
     * this no longer gives up: it fetches the order's actual capture details
     * via getOrder() and credits against that, same as a normal capture.
     */
    private PaymentResponse confirmPaypalDepositInternal(Transaction tx, String orderId) {
        if (tx.getStatus() == TransactionStatus.COMPLETED) {
            log.info("PayPal deposit already processed for orderId={} — skipping duplicate capture", orderId);
            return new PaymentResponse(true, tx.getId(), "Deposit already completed");
        }

        if (tx.getStatus() == TransactionStatus.FAILED) {
            return new PaymentResponse(false, tx.getId(),
                    "This PayPal deposit previously failed and cannot be retried with the same order.");
        }

        PaypalService.CaptureResult captureResult;
        try {
            captureResult = paypalService.captureOrder(orderId);
        } catch (PaypalCaptureException e) {
            if (e.isAlreadyCaptured()) {
                log.warn("PayPal reports orderId={} already captured — reconciling from order details instead of failing",
                        orderId);
                try {
                    captureResult = paypalService.getOrder(orderId);
                } catch (Exception fetchEx) {
                    log.error("PayPal reports orderId={} already captured but reconciliation fetch failed — " +
                            "manual review required", orderId, fetchEx);
                    return new PaymentResponse(false, tx.getId(),
                            "This deposit is in an inconsistent state and needs manual review. Please contact support.");
                }
            } else {
                markPaypalTransactionFailed(orderId, e.getMessage());
                return new PaymentResponse(false, tx.getId(), "PayPal capture failed: " + e.getMessage());
            }
        } catch (Exception e) {
            markPaypalTransactionFailed(orderId, e.getMessage());
            return new PaymentResponse(false, tx.getId(), "PayPal capture failed: " + e.getMessage());
        }

        return creditPaypalTransaction(tx, captureResult);
    }

    /**
     * Shared credit path for a PayPal deposit, regardless of whether the
     * capture details came from captureOrder(), a create-order
     * auto-capture, or a getOrder() reconciliation fetch.
     */
    private PaymentResponse creditPaypalTransaction(Transaction tx, PaypalService.CaptureResult captureResult) {
        if (tx.getStatus() == TransactionStatus.COMPLETED) {
            log.info("PayPal deposit already processed for orderId={} — skipping duplicate credit", tx.getReference());
            return new PaymentResponse(true, tx.getId(), "Deposit already completed");
        }

        Wallet wallet = walletRepository.findById(tx.getWalletId())
                .orElseThrow(() -> new WalletNotFoundException("Wallet not found: " + tx.getWalletId()));

        wallet.setBalance(wallet.getBalance().add(tx.getAmount()));

        if (captureResult.vaultId() != null && !captureResult.vaultId().equals(wallet.getPaypalVaultId())) {
            wallet.setPaypalVaultId(captureResult.vaultId());
            if (captureResult.customerId() != null) {
                wallet.setPaypalCustomerId(captureResult.customerId());
            }
            if (captureResult.payerEmail() != null) {
                wallet.setPaypalConnectedEmail(captureResult.payerEmail());
            }
            log.info("PayPal account connected: walletId={} vaultId={} email={} status={}",
                    wallet.getId(), captureResult.vaultId(), captureResult.payerEmail(), captureResult.vaultStatus());
        }

        walletRepository.save(wallet);

        tx.setStatus(TransactionStatus.COMPLETED);
        tx.setProviderReference(captureResult.captureId());
        tx.setDescription("PayPal deposit (capture " + captureResult.captureId() + ")");
        transactionRepository.save(tx);

        log.info("Wallet credited via PayPal: orderId={} amount={} captureId={}",
                tx.getReference(), tx.getAmount(), captureResult.captureId());
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

    @Transactional
    public void attachPaypalVaultToken(String vaultId, String customerId, String payerEmail) {
        if (customerId == null) {
            log.warn("VAULT.PAYMENT-TOKEN.CREATED webhook missing customer.id — cannot resolve wallet for vaultId={}", vaultId);
            return;
        }
        walletRepository.findByPaypalCustomerId(customerId).ifPresentOrElse(wallet -> {
            wallet.setPaypalVaultId(vaultId);
            if (payerEmail != null) {
                wallet.setPaypalConnectedEmail(payerEmail);
            }
            walletRepository.save(wallet);
            log.info("PayPal vault token finalized via webhook: walletId={} vaultId={}", wallet.getId(), vaultId);
        }, () -> log.warn("VAULT.PAYMENT-TOKEN.CREATED webhook: no wallet found for customerId={}", customerId));
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