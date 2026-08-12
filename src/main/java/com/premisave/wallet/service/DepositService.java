package com.premisave.wallet.service;

import com.premisave.wallet.dto.B2BExpressCheckoutResponse;
import com.premisave.wallet.dto.DepositRequest;
import com.premisave.wallet.dto.MpesaStkPushRequest;
import com.premisave.wallet.dto.PaymentResponse;
import com.premisave.wallet.entity.SavedCard;
import com.premisave.wallet.entity.Transaction;
import com.premisave.wallet.entity.Wallet;
import com.premisave.wallet.enums.Currency;
import com.premisave.wallet.enums.TransactionStatus;
import com.premisave.wallet.enums.TransactionType;
import com.premisave.wallet.exception.PaypalCaptureException;
import com.premisave.wallet.exception.WalletFrozenException;
import com.premisave.wallet.exception.WalletNotFoundException;
import com.premisave.wallet.repository.SavedCardRepository;
import com.premisave.wallet.repository.TransactionRepository;
import com.premisave.wallet.repository.WalletRepository;
import com.stripe.model.PaymentIntent;
import com.stripe.model.PaymentMethod;
import com.stripe.model.SetupIntent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class DepositService {

    private final WalletRepository walletRepository;
    private final TransactionRepository transactionRepository;
    private final SavedCardRepository savedCardRepository;
    private final MpesaService mpesaService;
    private final StripeService stripeService;
    private final PaypalService paypalService;
    private final FlutterwaveService flutterwaveService;
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
            case "FLUTTERWAVE" -> initiateFlutterwaveDeposit(userId, userEmail, request, wallet, idempotencyKey);
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

        SetupIntent si = stripeService.createSetupIntent(customerId);
        return Map.of("clientSecret", si.getClientSecret(), "setupIntentId", si.getId());
    }

    @Transactional
    public void confirmStripeSetupIntent(String setupIntentId, String userId) {
        Wallet wallet = walletRepository.findByUserId(userId)
                .orElseThrow(() -> new WalletNotFoundException("Wallet not found for userId: " + userId));

        SetupIntent si = stripeService.retrieveSetupIntent(setupIntentId);
        if (!si.getCustomer().equals(wallet.getStripeCustomerId())) {
            throw new IllegalArgumentException("This SetupIntent does not belong to the authenticated user");
        }

        if (si.getPaymentMethod() != null) {
            String paymentMethodId = (String) si.getPaymentMethod();
            String brand = null;
            String last4 = null;
            try {
                PaymentMethod pm = stripeService.retrievePaymentMethod(paymentMethodId);
                if (pm.getCard() != null) {
                    brand = pm.getCard().getBrand();
                    last4 = pm.getCard().getLast4();
                }
            } catch (Exception e) {
                log.warn("Setup intent confirmed but failed to fetch card display details: {}", e.getMessage());
            }

            upsertSavedCardAsDefault(wallet, paymentMethodId, brand, last4);
            walletRepository.save(wallet);
            log.info("Stripe setup intent confirmed and card saved: userId={} setupIntentId={}", userId, setupIntentId);
        }
    }

    // ─── Saved cards (multiple per wallet) ──────────────────────────────────

    /**
     * Creates or updates the SavedCard row for this PaymentMethod, and makes
     * it the active default — matching common app UX where "add a card"
     * means "use this one now." Demotes every other SavedCard for the
     * wallet, and refreshes Wallet's denormalized cache fields
     * (stripeDefaultPaymentMethodId/CardBrand/CardLast4) to match.
     *
     * Idempotent, and shared across the three places a PaymentMethod can
     * independently be "first seen": confirmStripeSetupIntent above, the
     * setup_intent.succeeded webhook (attachSavedCardByCustomerId below),
     * and a first-time deposit that saves a new card as a side effect
     * (creditWalletFromStripeCallback below) — all three converge on the
     * same SavedCard row rather than creating duplicates, since
     * stripePaymentMethodId is uniquely indexed.
     *
     * Caller is still responsible for walletRepository.save(wallet)
     * afterward — kept consistent with every other method in this class
     * doing its own save at the end, rather than this helper reaching
     * outside its own concern.
     */
    private void upsertSavedCardAsDefault(Wallet wallet, String paymentMethodId, String brand, String last4) {
        SavedCard card = savedCardRepository.findByStripePaymentMethodId(paymentMethodId)
                .orElseGet(SavedCard::new);
        card.setWalletId(wallet.getId());
        card.setUserId(wallet.getUserId());
        card.setStripePaymentMethodId(paymentMethodId);
        if (brand != null) card.setBrand(brand);
        if (last4 != null) card.setLast4(last4);
        card.setDefault(true);
        card = savedCardRepository.save(card);

        String newDefaultId = card.getId();
        for (SavedCard other : savedCardRepository.findByWalletIdOrderByCreatedAtDesc(wallet.getId())) {
            if (!other.getId().equals(newDefaultId) && other.isDefault()) {
                other.setDefault(false);
                savedCardRepository.save(other);
            }
        }

        wallet.setStripeDefaultPaymentMethodId(paymentMethodId);
        wallet.setStripeCardBrand(card.getBrand());
        wallet.setStripeCardLast4(card.getLast4());
    }

    /** GET /wallet/stripe/cards — every saved card for this wallet, newest first. */
    public List<SavedCard> listSavedCards(String userId) {
        Wallet wallet = walletRepository.findByUserId(userId)
                .orElseThrow(() -> new WalletNotFoundException("Wallet not found for userId: " + userId));
        return savedCardRepository.findByWalletIdOrderByCreatedAtDesc(wallet.getId());
    }

    /**
     * Removes one specific saved card — DELETE /wallet/stripe/cards/{paymentMethodId}.
     * Detaches it on Stripe's side first (StripeService.detachPaymentMethod),
     * then deletes the SavedCard row. If the removed card was the default,
     * promotes the next-most-recent remaining card automatically rather
     * than leaving the wallet with no default and silently breaking the
     * next off-session deposit attempt.
     *
     * SECURITY: paymentMethodId is looked up scoped to the caller's own
     * wallet, not fetched globally — a caller can't detach another user's
     * card by guessing or reusing a pm_xxx id.
     */
    @Transactional
    public void removeSavedCard(String userId, String paymentMethodId) {
        Wallet wallet = walletRepository.findByUserId(userId)
                .orElseThrow(() -> new WalletNotFoundException("Wallet not found for userId: " + userId));

        SavedCard card = savedCardRepository.findByStripePaymentMethodId(paymentMethodId)
                .filter(c -> c.getWalletId().equals(wallet.getId()))
                .orElseThrow(() -> new IllegalStateException("This card is not linked to your wallet."));

        stripeService.detachPaymentMethod(paymentMethodId);
        savedCardRepository.delete(card);

        if (card.isDefault()) {
            List<SavedCard> remaining = savedCardRepository.findByWalletIdOrderByCreatedAtDesc(wallet.getId());
            if (!remaining.isEmpty()) {
                SavedCard newDefault = remaining.get(0);
                newDefault.setDefault(true);
                savedCardRepository.save(newDefault);
                wallet.setStripeDefaultPaymentMethodId(newDefault.getStripePaymentMethodId());
                wallet.setStripeCardBrand(newDefault.getBrand());
                wallet.setStripeCardLast4(newDefault.getLast4());
            } else {
                wallet.setStripeDefaultPaymentMethodId(null);
                wallet.setStripeCardBrand(null);
                wallet.setStripeCardLast4(null);
            }
            walletRepository.save(wallet);
        }

        log.info("Stripe saved card removed: userId={} paymentMethodId={}", userId, paymentMethodId);
    }

    /**
     * Sets a specific already-saved card as the one
     * DepositService.initiateStripeDeposit will charge —
     * PUT /wallet/stripe/cards/{paymentMethodId}/default. Doesn't call
     * Stripe at all — this is purely an app-level concept; Stripe's own
     * "default payment method" is for invoicing, not what this off-session
     * deposit flow reads from.
     */
    @Transactional
    public void setDefaultSavedCard(String userId, String paymentMethodId) {
        Wallet wallet = walletRepository.findByUserId(userId)
                .orElseThrow(() -> new WalletNotFoundException("Wallet not found for userId: " + userId));

        SavedCard card = savedCardRepository.findByStripePaymentMethodId(paymentMethodId)
                .filter(c -> c.getWalletId().equals(wallet.getId()))
                .orElseThrow(() -> new IllegalStateException("This card is not linked to your wallet."));

        for (SavedCard other : savedCardRepository.findByWalletIdOrderByCreatedAtDesc(wallet.getId())) {
            if (other.isDefault() && !other.getId().equals(card.getId())) {
                other.setDefault(false);
                savedCardRepository.save(other);
            }
        }

        card.setDefault(true);
        savedCardRepository.save(card);

        wallet.setStripeDefaultPaymentMethodId(card.getStripePaymentMethodId());
        wallet.setStripeCardBrand(card.getBrand());
        wallet.setStripeCardLast4(card.getLast4());
        walletRepository.save(wallet);

        log.info("Stripe default card changed: userId={} paymentMethodId={}", userId, paymentMethodId);
    }

    // ─── Stripe Connect (linking a bank account for withdrawals) ───────────

    /**
     * Starts (or resumes) Stripe Connect onboarding for this wallet, so the
     * user can later withdraw to their own international bank account — see
     * DisbursementService's STRIPE branch. Reuses the wallet's existing
     * connected account id if one's already on file (e.g. the user exited
     * onboarding partway through and is picking it back up), rather than
     * creating a duplicate Stripe Account every time this is called.
     *
     * The returned onboardingUrl is single-use — the caller must redirect
     * the user to it immediately, not cache/re-show it.
     */
    @Transactional
    public Map<String, String> createStripeConnectLink(String userId, String email) {
        Wallet wallet = walletRepository.findByUserId(userId)
                .orElseThrow(() -> new WalletNotFoundException("Wallet not found. Please create a wallet first."));

        StripeService.ConnectAccountLinkResult result = stripeService.createConnectedAccountAndOnboardingLink(
                wallet.getStripeConnectedAccountId(), email, userId);

        if (!result.accountId().equals(wallet.getStripeConnectedAccountId())) {
            wallet.setStripeConnectedAccountId(result.accountId());
            wallet.setStripePayoutsEnabled(false);
            walletRepository.save(wallet);
            log.info("Stripe Connect account linked to wallet: userId={} accountId={}", userId, result.accountId());
        }

        return Map.of("accountId", result.accountId(), "onboardingUrl", result.onboardingUrl());
    }

    @Transactional
    public void creditWalletFromStripeCallback(String reference, BigDecimal amount, String paymentIntentId,
                                                String currency, String customerId, String paymentMethodId) {
        Transaction tx = transactionRepository.findByReference(reference)
                .orElseThrow(() -> new IllegalStateException("No pending transaction found for reference=" + reference));

        if (tx.getStatus() == TransactionStatus.COMPLETED) {
            log.info("Stripe deposit already processed for reference={} — skipping duplicate credit", reference);
            return;
        }

        Wallet wallet = walletRepository.findById(tx.getWalletId())
                .orElseThrow(() -> new WalletNotFoundException("Wallet not found: " + tx.getWalletId()));

        wallet.setBalance(wallet.getBalance().add(amount));

        if (customerId != null && !customerId.equals(wallet.getStripeCustomerId())) {
            wallet.setStripeCustomerId(customerId);
        }
        if (paymentMethodId != null) {
            String brand = null;
            String last4 = null;
            try {
                PaymentMethod pm = stripeService.retrievePaymentMethod(paymentMethodId);
                if (pm.getCard() != null) {
                    brand = pm.getCard().getBrand();
                    last4 = pm.getCard().getLast4();
                }
            } catch (Exception e) {
                log.warn("Deposit succeeded but failed to fetch card display details: {}", e.getMessage());
            }
            upsertSavedCardAsDefault(wallet, paymentMethodId, brand, last4);
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

    /**
     * Marks a Stripe deposit as failed — the counterpart to
     * markStkTransactionFailed / markPaypalTransactionFailed /
     * markFlutterwaveTransactionFailed above, which Stripe was missing
     * until now. Triggered by the payment_intent.payment_failed webhook
     * (see PaymentCallbackController.stripeWebhook) — a declined card, an
     * abandoned 3DS challenge that Stripe itself gives up on, etc. No
     * refund logic needed: the wallet is never credited for a PENDING
     * Stripe deposit (see initiateStripeDeposit), same reasoning as every
     * other provider's failure path.
     */
    @Transactional
    public void markStripeTransactionFailed(String reference, String reason) {
        transactionRepository.findByReference(reference).ifPresentOrElse(tx -> {
            if (tx.getStatus() == TransactionStatus.COMPLETED) {
                log.warn("Ignoring failure callback for already-completed Stripe reference={}", reference);
                return;
            }
            tx.setStatus(TransactionStatus.FAILED);
            tx.setDescription("Stripe deposit failed: " + reason);
            transactionRepository.save(tx);
            log.warn("Stripe deposit failed: reference={} reason={}", reference, reason);
        }, () -> log.warn("Failure callback for unknown Stripe reference={}: {}", reference, reason));
    }

    // ─── Stuck-deposit sweeper (Stripe only — abandoned card collection) ────

    /**
     * Auto-fails Stripe deposits that have sat PENDING beyond the cutoff.
     * markStripeTransactionFailed above only fires on an EXPLICIT decline
     * (payment_intent.payment_failed) — a user who opens the card widget
     * and simply closes the app never triggers that webhook at all, since
     * nothing was actually attempted on Stripe's side. Without this, that
     * transaction would sit PENDING forever with no terminal state.
     *
     * Safe to auto-fail here — unlike DisbursementService.
     * flagStuckDisbursements, which only logs and never auto-resolves
     * (because money may already have moved for a disbursement) — since
     * the wallet is never credited for a PENDING deposit in the first
     * place (see initiateStripeDeposit). If a payment genuinely succeeded
     * but the webhook was somehow missed, confirmStripeDeposit can still
     * reconcile it manually after the fact; auto-failing here doesn't
     * foreclose that.
     *
     * Identifies Stripe deposits by description prefix (set in
     * initiateStripeDeposit's savePendingTransaction call) rather than a
     * dedicated provider field — Transaction doesn't currently store which
     * provider a deposit used, unlike Disbursement, which has both
     * provider and channel.
     */
    @Scheduled(fixedDelay = 15 * 60 * 1000)
    public void autoFailStuckStripeDeposits() {
        LocalDateTime cutoff = LocalDateTime.now().minusMinutes(30);
        List<Transaction> stuck = transactionRepository
                .findByTypeAndStatusAndCreatedAtBefore(TransactionType.DEPOSIT, TransactionStatus.PENDING, cutoff)
                .stream()
                .filter(tx -> tx.getDescription() != null && tx.getDescription().startsWith("Stripe deposit"))
                .toList();

        for (Transaction tx : stuck) {
            tx.setStatus(TransactionStatus.FAILED);
            tx.setDescription("Stripe deposit failed: abandoned — no confirmation within 30 minutes");
            transactionRepository.save(tx);
        }

        if (!stuck.isEmpty()) {
            log.warn("{} Stripe deposit(s) auto-failed after sitting PENDING beyond 30 minutes: {}",
                    stuck.size(), stuck.stream().map(Transaction::getId).toList());
        }
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

    @Transactional
    public void attachSavedCardByCustomerId(String stripeCustomerId, String paymentMethodId) {
        walletRepository.findByStripeCustomerId(stripeCustomerId).ifPresentOrElse(wallet -> {
            String brand = null;
            String last4 = null;
            try {
                PaymentMethod pm = stripeService.retrievePaymentMethod(paymentMethodId);
                if (pm.getCard() != null) {
                    brand = pm.getCard().getBrand();
                    last4 = pm.getCard().getLast4();
                }
            } catch (Exception e) {
                log.warn("SetupIntent succeeded but failed to fetch card display details: {}", e.getMessage());
            }
            upsertSavedCardAsDefault(wallet, paymentMethodId, brand, last4);
            walletRepository.save(wallet);
            log.info("Stripe card saved via webhook: customerId={} paymentMethodId={}", stripeCustomerId, paymentMethodId);
        }, () -> log.warn("setup_intent.succeeded webhook: no wallet found for customerId={}", stripeCustomerId));
    }

    // ─── Flutterwave ─────────────────────────────────────────────────────────

    /**
     * Initiates a Flutterwave mobile-money deposit via v4's General Flow.
     *
     * CURRENCY: per Flutterwave's Mobile Money docs, "the charge currency
     * must match the currency_code used when creating the payment method."
     * There is no USD-denominated mobile money charge — a mismatched
     * currency is rejected outright (REQUEST_NOT_VALID). This path
     * therefore requires the caller to supply the correct LOCAL currency
     * for the target network/country (e.g. GHS for Ghana MTN, UGX for
     * Uganda, etc. — NOT KES, since Kenyan mobile money goes through the
     * direct M-Pesa STK push path instead, see provider=MPESA).
     *
     * The wallet always operates in KES, so — same pattern as the PayPal
     * branch above, just with the local currency as the base instead of
     * USD — the live FX rate from the local currency to KES is used to
     * compute what actually gets credited to the wallet. The FX rate is
     * logged for reconciliation against the eventual webhook payout amount.
     *
     * chargeId is stored as Transaction.providerReference immediately —
     * v4 only supports verifying a charge by ITS OWN id (GET
     * /charges/{id}), not by our own reference the way v3's
     * verify_by_reference worked, so confirmFlutterwaveDeposit below needs
     * it on record.
     *
     * REQUIRES two fields on DepositRequest: flutterwaveCountryCode
     * (e.g. "233") and flutterwaveMobileNetwork (e.g. "MTN") — see
     * DepositRequest.java. customerName/customerPhone are expected to
     * already exist on that DTO. request.currency is now required and
     * must be the local currency described above.
     */
    private PaymentResponse initiateFlutterwaveDeposit(String userId, String userEmail, DepositRequest request,
                                                         Wallet wallet, String idempotencyKey) {
        String localCurrency = request.getCurrency() != null ? request.getCurrency().toUpperCase() : null;
        if (localCurrency == null || localCurrency.isBlank()) {
            throw new IllegalArgumentException(
                    "currency is required for Flutterwave mobile-money deposits — must be the local currency "
                            + "for the target network/country (e.g. GHS for Ghana MTN), not USD.");
        }
        if (request.getCustomerPhone() == null || request.getCustomerPhone().isBlank()) {
            throw new IllegalArgumentException("customerPhone is required for Flutterwave mobile-money deposits");
        }
        if (request.getFlutterwaveCountryCode() == null || request.getFlutterwaveCountryCode().isBlank()) {
            throw new IllegalArgumentException("flutterwaveCountryCode (e.g., \"233\" for Ghana) is required");
        }
        if (request.getFlutterwaveMobileNetwork() == null || request.getFlutterwaveMobileNetwork().isBlank()) {
            throw new IllegalArgumentException("flutterwaveMobileNetwork (e.g., \"MTN\") is required");
        }

        BigDecimal chargeAmount = request.getAmount(); // amount in localCurrency
        BigDecimal fxRate;
        BigDecimal kesEquivalent;
        if ("KES".equals(localCurrency)) {
            fxRate = BigDecimal.ONE;
            kesEquivalent = chargeAmount;
        } else {
            fxRate = fxRateService.getRate(localCurrency, "KES");
            kesEquivalent = chargeAmount.multiply(fxRate).setScale(2, RoundingMode.HALF_UP);
        }

        String txRef = idempotencyKey;
        String customerName = request.getCustomerName() != null ? request.getCustomerName() : userEmail;

        FlutterwaveService.CheckoutResult result = flutterwaveService.initiateMobileMoneyCharge(
                chargeAmount, localCurrency, txRef, userEmail, customerName,
                request.getFlutterwaveCountryCode(), request.getFlutterwaveMobileNetwork(),
                request.getCustomerPhone());

        if (!result.success()) {
            log.warn("Flutterwave charge initiation rejected: userId={} reason={}", userId, result.message());
            return new PaymentResponse(false, null, "Flutterwave charge initiation failed: " + result.message());
        }

        log.info("Flutterwave charge created: userId={} txRef={} chargeId={} localAmount={} {} kesEquivalent={} rate={} nextAction={}",
                userId, txRef, result.chargeId(), chargeAmount, localCurrency, kesEquivalent, fxRate, result.nextActionType());

        Transaction tx = new Transaction();
        tx.setUserId(userId);
        tx.setWalletId(wallet.getId());
        tx.setType(TransactionType.DEPOSIT);
        tx.setStatus(TransactionStatus.PENDING);
        tx.setAmount(kesEquivalent);
        tx.setCurrency(Currency.KES);
        tx.setReference(txRef);
        tx.setProviderReference(result.chargeId());
        tx.setDescription("Flutterwave deposit (pending) - " + localCurrency + " " + chargeAmount
                + " @ live rate " + fxRate);
        transactionRepository.save(tx);

        // Return the redirect URL from Flutterwave's next_action, if present.
        // Otherwise, return payment_instruction if it's a prompt-on-phone scenario.
        String redirectUrl = result.redirectUrl();
        String instructionNote = result.paymentInstructionNote();
        String userFacingMessage = redirectUrl != null
                ? "Redirect to " + redirectUrl + " to authorize the charge."
                : instructionNote != null
                ? "Approve the charge on your phone: " + instructionNote
                : localCurrency + " " + chargeAmount + " charge initiated (KES " + kesEquivalent + ").";

        return new PaymentResponse(true, redirectUrl != null ? redirectUrl : txRef, userFacingMessage);
    }

    @Transactional
    public PaymentResponse confirmFlutterwaveDeposit(String txRef, String callerUserId) {
        Transaction tx = transactionRepository.findByReference(txRef)
                .orElseThrow(() -> new IllegalStateException(
                        "No pending transaction found for Flutterwave txRef=" + txRef));

        if (!tx.getUserId().equals(callerUserId)) {
            throw new IllegalArgumentException("This Flutterwave charge does not belong to the authenticated user");
        }

        if (tx.getStatus() == TransactionStatus.COMPLETED) {
            return new PaymentResponse(true, tx.getId(), "Deposit already completed");
        }

        if (tx.getStatus() == TransactionStatus.FAILED) {
            return new PaymentResponse(false, tx.getId(),
                    "This Flutterwave charge previously failed and cannot be retried with the same reference.");
        }

        String chargeId = tx.getProviderReference();
        if (chargeId == null) {
            log.error("Flutterwave confirm: pending transaction txRef={} has no chargeId recorded", txRef);
            return new PaymentResponse(false, tx.getId(),
                    "This charge is in an inconsistent state and needs manual review. Please contact support.");
        }

        FlutterwaveService.VerifyResult verifyResult = flutterwaveService.verifyChargeById(chargeId);
        if (!verifyResult.success()) {
            markFlutterwaveTransactionFailed(txRef, "Charge verification failed: " + verifyResult.message());
            return new PaymentResponse(false, tx.getId(),
                    "Flutterwave charge verification failed: " + verifyResult.message());
        }

        creditWalletFromFlutterwaveCallback(txRef, chargeId);
        return new PaymentResponse(true, tx.getId(), "Flutterwave deposit successful");
    }

    /**
     * CRITICAL: Called from two paths:
     *  1. confirmFlutterwaveDeposit (frontend redirect confirm) — after
     *     server-side charge verification (see above).
     *  2. Webhook handler in PaymentCallbackController (charge.completed
     *     event) — after server-side verification by the webhook handler.
     *
     * Both ensure verifyChargeById has been called first, preventing
     * spoofed/rejected charges from crediting the wallet. Safe idempotency:
     * if a charge is already COMPLETED, this method no-ops — same pattern as
     * confirmFlutterwaveDeposit (frontend redirect confirm) and by the
     * charge.completed webhook handler in PaymentCallbackController.
     * The credited amount always comes from the transaction record created
     * at initiation time (computed from a live FX rate at that moment),
     * same reasoning as before — this method only takes txRef and a
     * provider reference for the audit trail, not an amount.
     */
    @Transactional
    public void creditWalletFromFlutterwaveCallback(String txRef, String providerReference) {
        Transaction tx = transactionRepository.findByReference(txRef).orElse(null);

        if (tx == null) {
            log.warn("Flutterwave reconciliation: no pending transaction found for txRef={} (providerReference={}) — " +
                    "cannot credit; needs manual review", txRef, providerReference);
            return;
        }

        if (tx.getStatus() == TransactionStatus.COMPLETED) {
            log.info("Flutterwave deposit already processed for txRef={} — skipping duplicate credit", txRef);
            return;
        }

        if (tx.getStatus() == TransactionStatus.FAILED) {
            log.warn("Flutterwave credit attempted for previously-failed txRef={} — ignoring, needs manual review", txRef);
            return;
        }

        Wallet wallet = walletRepository.findById(tx.getWalletId())
                .orElseThrow(() -> new WalletNotFoundException("Wallet not found: " + tx.getWalletId()));

        wallet.setBalance(wallet.getBalance().add(tx.getAmount()));
        walletRepository.save(wallet);

        tx.setStatus(TransactionStatus.COMPLETED);
        tx.setProviderReference(providerReference);
        tx.setDescription("Flutterwave deposit (charge " + providerReference + ")");
        transactionRepository.save(tx);

        log.info("Wallet credited via Flutterwave: txRef={} amount={} providerReference={} (from initiationRate)",
                txRef, tx.getAmount(), providerReference);
    }

    @Transactional
    public void markFlutterwaveTransactionFailed(String txRef, String reason) {
        transactionRepository.findByReference(txRef).ifPresentOrElse(tx -> {
            if (tx.getStatus() == TransactionStatus.COMPLETED) {
                log.warn("Ignoring failure callback for already-completed Flutterwave txRef={}", txRef);
                return;
            }
            tx.setStatus(TransactionStatus.FAILED);
            tx.setDescription("Flutterwave deposit failed: " + reason);
            transactionRepository.save(tx);
            log.warn("Flutterwave deposit failed: txRef={} reason={}", txRef, reason);
        }, () -> log.warn("Failure callback for unknown Flutterwave txRef={}: {}", txRef, reason));
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