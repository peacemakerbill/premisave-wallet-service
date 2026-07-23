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
                request.getAmount(), "M-Pesa deposit (pending STK confirmation)", checkoutId);

        return new PaymentResponse(true, checkoutId,
                "M-Pesa STK push sent. Enter your PIN to complete the deposit.");
    }

    // ─── M-Pesa B2B Express Checkout (USSD Push to Till) ────────────────────

    /**
     * Deposit via B2B Express Checkout — the customer pays from their own
     * till number (not a personal M-Pesa line) via a USSD PIN prompt.
     * We generate our own RequestRefID up front since the callback (which
     * carries no account/email either) is matched back purely by that value.
     */
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
                request.getAmount(), "M-Pesa till deposit (pending USSD confirmation)", requestRefId);

        return new PaymentResponse(true, requestRefId,
                "USSD push sent to your till. Approve on your phone to complete the deposit.");
    }

    // ─── Stripe ──────────────────────────────────────────────────────────────

    private PaymentResponse initiateStripeDeposit(String userId, DepositRequest request,
                                                    Wallet wallet, String idempotencyKey) {
        String currency = request.getCurrency() != null ? request.getCurrency() : "kes";
        String clientSecret = stripeService.createPaymentIntent(request.getAmount(), currency, idempotencyKey);

        log.info("Stripe PaymentIntent created: userId={}", userId);

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

        savePendingTransaction(userId, wallet.getId(), TransactionType.DEPOSIT,
                request.getAmount(), "PayPal deposit (pending approval)", orderId);

        return new PaymentResponse(true, approveUrl,
                "Redirect the user to the PayPal approval URL to complete the deposit.");
    }

    // ─── Callbacks ───────────────────────────────────────────────────────────

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

    /**
     * Called by PaymentCallbackController when a B2B Express Checkout (USSD
     * Push) callback arrives. Matched back to the pending transaction via the
     * RequestRefID generated at initiation time — the callback carries no
     * account/email, same constraint as the STK callback.
     */
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

    @Transactional
    public void creditWalletFromPaypal(String userId, BigDecimal amount,
                                        String orderId, String currency) {
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