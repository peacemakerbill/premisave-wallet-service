package com.premisave.wallet.service;

import com.premisave.wallet.dto.DepositRequest;
import com.premisave.wallet.dto.MpesaStkPushRequest;
import com.premisave.wallet.dto.PaymentResponse;
import com.premisave.wallet.entity.Transaction;
import com.premisave.wallet.entity.Wallet;
import com.premisave.wallet.enums.Currency;
import com.premisave.wallet.enums.TransactionStatus;
import com.premisave.wallet.enums.TransactionType;
import com.premisave.wallet.exception.WalletNotFoundException;
import com.premisave.wallet.repository.TransactionRepository;
import com.premisave.wallet.repository.WalletRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
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

    /**
     * Routes deposit initiation to the correct payment provider.
     *
     * Response meanings by provider:
     *  - MPESA  → reference = CheckoutRequestID (STK push sent to phone)
     *  - STRIPE → reference = Stripe client_secret (frontend confirms with Stripe.js)
     *  - PAYPAL → reference = PayPal approveUrl (redirect user to PayPal)
     */
    public PaymentResponse initiateDeposit(String userId, String userEmail, DepositRequest request) {
        Wallet wallet = walletRepository.findByUserId(userId)
                .orElseThrow(() -> new WalletNotFoundException("Wallet not found. Please create a wallet first."));

        String provider = request.getProvider() != null ? request.getProvider().toUpperCase() : "MPESA";
        String idempotencyKey = UUID.randomUUID().toString();

        return switch (provider) {
            case "MPESA" -> initiateMpesaDeposit(userId, request, wallet, idempotencyKey);
            case "STRIPE" -> initiateStripeDeposit(userId, request, wallet, idempotencyKey);
            case "PAYPAL" -> initiatePaypalDeposit(userId, request, wallet, idempotencyKey);
            default -> new PaymentResponse(false, null, "Unsupported deposit provider: " + provider);
        };
    }

    // ─── M-Pesa ──────────────────────────────────────────────────────────────

    private PaymentResponse initiateMpesaDeposit(String userId, DepositRequest request,
                                                   Wallet wallet, String idempotencyKey) {
        MpesaStkPushRequest stkRequest = new MpesaStkPushRequest();
        // Phone number should be in the request; fall back to accountNumber (email) placeholder
        stkRequest.setPhoneNumber(request.getPhoneNumber() != null
                ? request.getPhoneNumber()
                : wallet.getAccountNumber());
        stkRequest.setAmount(request.getAmount());
        stkRequest.setAccountReference("PREMISAVE-" + userId);

        String checkoutId = mpesaService.initiateStkPush(stkRequest);
        log.info("M-Pesa STK push: userId={} checkoutId={}", userId, checkoutId);

        // Save PENDING transaction — it is completed by the callback
        savePendingTransaction(userId, wallet.getId(), TransactionType.DEPOSIT,
                request.getAmount(), "M-Pesa deposit (pending STK confirmation)", checkoutId);

        return new PaymentResponse(true, checkoutId,
                "M-Pesa STK push sent. Enter your PIN to complete the deposit.");
    }

    // ─── Stripe ──────────────────────────────────────────────────────────────

    private PaymentResponse initiateStripeDeposit(String userId, DepositRequest request,
                                                    Wallet wallet, String idempotencyKey) {
        String currency = request.getCurrency() != null ? request.getCurrency() : "kes";
        String clientSecret = stripeService.createPaymentIntent(request.getAmount(), currency, idempotencyKey);

        log.info("Stripe PaymentIntent created: userId={}", userId);

        // Save PENDING transaction — completed by Stripe webhook (payment_intent.succeeded)
        savePendingTransaction(userId, wallet.getId(), TransactionType.DEPOSIT,
                request.getAmount(), "Stripe deposit (pending payment confirmation)", idempotencyKey);

        return new PaymentResponse(true, clientSecret,
                "Stripe PaymentIntent created. Use the client_secret to confirm payment.");
    }

    // ─── PayPal ──────────────────────────────────────────────────────────────

    private PaymentResponse initiatePaypalDeposit(String userId, DepositRequest request,
                                                    Wallet wallet, String idempotencyKey) {
        String currency = request.getCurrency() != null ? request.getCurrency() : "USD";
        Map<String, String> result = paypalService.createOrder(request.getAmount(), currency, idempotencyKey);

        String orderId     = result.get("orderId");
        String approveUrl  = result.get("approveUrl");

        log.info("PayPal Order created: userId={} orderId={}", userId, orderId);

        // Save PENDING transaction — completed by PayPal webhook (CHECKOUT.ORDER.APPROVED)
        savePendingTransaction(userId, wallet.getId(), TransactionType.DEPOSIT,
                request.getAmount(), "PayPal deposit (pending approval)", orderId);

        return new PaymentResponse(true, approveUrl,
                "Redirect the user to the PayPal approval URL to complete the deposit.");
    }

    // ─── Callbacks ───────────────────────────────────────────────────────────

    /**
     * Called by the M-Pesa callback controller after a successful STK push confirmation.
     * Credits the wallet and marks the transaction COMPLETED.
     */
    @Transactional
    public void creditWalletFromCallback(String accountNumber, BigDecimal amount,
                                          String mpesaRef, String description) {
        Wallet wallet = walletRepository.findByAccountNumber(accountNumber)
                .orElseThrow(() -> new WalletNotFoundException("Wallet not found for: " + accountNumber));

        wallet.setBalance(wallet.getBalance().add(amount));
        walletRepository.save(wallet);

        Transaction tx = new Transaction();
        tx.setUserId(wallet.getUserId());
        tx.setWalletId(wallet.getId());
        tx.setType(TransactionType.DEPOSIT);
        tx.setStatus(TransactionStatus.COMPLETED);
        tx.setAmount(amount);
        tx.setCurrency(Currency.KES);
        tx.setDescription(description != null ? description : "M-Pesa deposit");
        tx.setProviderReference(mpesaRef);
        transactionRepository.save(tx);

        log.info("Wallet credited via M-Pesa: account={} amount={} ref={}", accountNumber, amount, mpesaRef);
    }

    /**
     * Called by the Stripe webhook handler when payment_intent.succeeded fires.
     * providerReference = Stripe PaymentIntent ID.
     */
    @Transactional
    public void creditWalletFromStripe(String userId, BigDecimal amount,
                                        String paymentIntentId, String currency) {
        Wallet wallet = walletRepository.findByUserId(userId)
                .orElseThrow(() -> new WalletNotFoundException("Wallet not found for userId: " + userId));

        wallet.setBalance(wallet.getBalance().add(amount));
        walletRepository.save(wallet);

        Transaction tx = new Transaction();
        tx.setUserId(wallet.getUserId());
        tx.setWalletId(wallet.getId());
        tx.setType(TransactionType.DEPOSIT);
        tx.setStatus(TransactionStatus.COMPLETED);
        tx.setAmount(amount);
        tx.setCurrency(resolveCurrency(currency));
        tx.setDescription("Stripe deposit");
        tx.setProviderReference(paymentIntentId);
        transactionRepository.save(tx);

        log.info("Wallet credited via Stripe: userId={} amount={} piId={}", userId, amount, paymentIntentId);
    }

    /**
     * Called by the PayPal webhook handler when CHECKOUT.ORDER.APPROVED fires.
     * Captures the order and credits the wallet.
     */
    @Transactional
    public void creditWalletFromPaypal(String userId, BigDecimal amount,
                                        String orderId, String currency) {
        // Capture the PayPal order first
        String captureId = paypalService.captureOrder(orderId);

        Wallet wallet = walletRepository.findByUserId(userId)
                .orElseThrow(() -> new WalletNotFoundException("Wallet not found for userId: " + userId));

        wallet.setBalance(wallet.getBalance().add(amount));
        walletRepository.save(wallet);

        Transaction tx = new Transaction();
        tx.setUserId(wallet.getUserId());
        tx.setWalletId(wallet.getId());
        tx.setType(TransactionType.DEPOSIT);
        tx.setStatus(TransactionStatus.COMPLETED);
        tx.setAmount(amount);
        tx.setCurrency(resolveCurrency(currency));
        tx.setDescription("PayPal deposit");
        tx.setProviderReference(captureId);
        transactionRepository.save(tx);

        log.info("Wallet credited via PayPal: userId={} amount={} captureId={}", userId, amount, captureId);
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private void savePendingTransaction(String userId, String walletId, TransactionType type,
                                         BigDecimal amount, String description, String reference) {
        Transaction tx = new Transaction();
        tx.setUserId(userId);
        tx.setWalletId(walletId);
        tx.setType(type);
        tx.setStatus(TransactionStatus.PENDING);
        tx.setAmount(amount);
        tx.setCurrency(Currency.KES);
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