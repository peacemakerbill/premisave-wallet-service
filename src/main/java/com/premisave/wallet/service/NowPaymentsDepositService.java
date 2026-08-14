package com.premisave.wallet.service;

import com.premisave.wallet.dto.DepositRequest;
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

/**
 * NOWPayments (crypto) deposit business logic — mirrors
 * FlutterwaveDepositService's structure, one level up from
 * NowPaymentsService's raw API calls.
 *
 * Unlike every other provider here, NOWPayments deposits are priced in
 * KES directly (price_currency=kes) and NOWPayments itself quotes the
 * crypto-equivalent amount at checkout time — no FxRateService needed on
 * this side, the conversion happens on NOWPayments' end, not ours.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NowPaymentsDepositService {

    private final WalletRepository walletRepository;
    private final TransactionRepository transactionRepository;
    private final NowPaymentsService nowPaymentsService;

    /**
     * Initiates a crypto deposit. request.getCurrency() is the
     * CRYPTOCURRENCY the customer will pay in (e.g. "usdttrc20", "btc") —
     * NOT the wallet's base currency. The wallet is always credited in
     * KES; NOWPayments quotes what that KES amount costs in the chosen
     * crypto and returns a one-time deposit address.
     *
     * order_id is set to the idempotencyKey, same role as every other
     * provider's txRef/reference — this is what the IPN webhook and any
     * manual status check key back off of.
     *
     * request.getNowPaymentsSandboxCase() (sandbox only) is forwarded
     * straight through to NOWPayments' Create Payment "case" parameter —
     * set it to "finished"/"failed"/"partially_paid"/etc. to get an
     * instant synthetic IPN callback simulating that outcome, no real
     * crypto required. Null in production, where it's simply not sent.
     */
    public PaymentResponse initiateNowPaymentsDeposit(String userId, DepositRequest request, Wallet wallet,
                                                        String idempotencyKey) {
        String payCurrency = request.getCurrency() != null ? request.getCurrency().toLowerCase() : null;
        if (payCurrency == null || payCurrency.isBlank()) {
            throw new IllegalArgumentException(
                    "currency is required for NOWPayments deposits — the CRYPTOCURRENCY the customer will pay in "
                            + "(e.g. \"usdttrc20\", \"btc\"), not the wallet's KES balance currency.");
        }

        NowPaymentsService.CreatePaymentResult result = nowPaymentsService.createPayment(
                request.getAmount(), "kes", payCurrency, idempotencyKey, "Premisave wallet deposit",
                request.getNowPaymentsSandboxCase());

        if (!result.success()) {
            log.warn("NOWPayments payment creation rejected: userId={} reason={}", userId, result.message());
            return new PaymentResponse(false, null, "NOWPayments payment creation failed: " + result.message());
        }

        Transaction tx = new Transaction();
        tx.setUserId(userId);
        tx.setWalletId(wallet.getId());
        tx.setType(TransactionType.DEPOSIT);
        tx.setStatus(TransactionStatus.PENDING);
        tx.setAmount(request.getAmount()); // KES amount, credited as-is on confirmation
        tx.setCurrency(Currency.KES);
        tx.setReference(idempotencyKey);
        tx.setProviderReference(result.paymentId());
        tx.setDescription("NOWPayments deposit (pending) - " + result.payAmount() + " " + result.payCurrency());
        transactionRepository.save(tx);

        return new PaymentResponse(true, result.paymentId(),
                "Send " + result.payAmount() + " " + result.payCurrency() + " to " + result.payAddress()
                        + " to complete this deposit.");
    }

    /**
     * Called from the IPN webhook when payment_status is "finished" — the
     * terminal success state (funds converted and sent to your outcome
     * wallet). "confirmed" is deliberately NOT treated as success here: it
     * means the blockchain transaction is confirmed but NOWPayments hasn't
     * yet converted/settled it — same "wait for the actual terminal state,
     * not an intermediate one" reasoning already applied to every other
     * provider's webhook handling in this codebase.
     */
    @Transactional
    public void creditWalletFromNowPaymentsCallback(String orderId, String paymentId) {
        Transaction tx = transactionRepository.findByReference(orderId).orElse(null);

        if (tx == null) {
            log.warn("NOWPayments IPN: no pending transaction found for orderId={} (paymentId={}) — cannot credit; needs manual review",
                    orderId, paymentId);
            return;
        }

        if (tx.getStatus() == TransactionStatus.COMPLETED) {
            log.info("NOWPayments deposit already processed for orderId={} — skipping duplicate credit", orderId);
            return;
        }

        if (tx.getStatus() == TransactionStatus.FAILED) {
            log.warn("NOWPayments credit attempted for previously-failed orderId={} — ignoring, needs manual review", orderId);
            return;
        }

        Wallet wallet = walletRepository.findById(tx.getWalletId())
                .orElseThrow(() -> new WalletNotFoundException("Wallet not found: " + tx.getWalletId()));

        wallet.setBalance(wallet.getBalance().add(tx.getAmount()));
        walletRepository.save(wallet);

        tx.setStatus(TransactionStatus.COMPLETED);
        tx.setProviderReference(paymentId);
        tx.setDescription("NOWPayments deposit (payment " + paymentId + ")");
        transactionRepository.save(tx);

        log.info("Wallet credited via NOWPayments: orderId={} amount={} paymentId={}", orderId, tx.getAmount(), paymentId);
    }

    @Transactional
    public void markNowPaymentsTransactionFailed(String orderId, String reason) {
        transactionRepository.findByReference(orderId).ifPresentOrElse(tx -> {
            if (tx.getStatus() == TransactionStatus.COMPLETED) {
                log.warn("Ignoring failure callback for already-completed NOWPayments orderId={}", orderId);
                return;
            }
            tx.setStatus(TransactionStatus.FAILED);
            tx.setDescription("NOWPayments deposit failed: " + reason);
            transactionRepository.save(tx);
            log.warn("NOWPayments deposit failed: orderId={} reason={}", orderId, reason);
        }, () -> log.warn("Failure callback for unknown NOWPayments orderId={}: {}", orderId, reason));
    }

    /**
     * Flags a payment for manual review rather than crediting or failing it
     * outright — used for payment_status="partially_paid" (customer sent
     * less than the quoted crypto amount, e.g. due to price movement during
     * the payment window). NOWPayments holds these funds; per their docs,
     * resolving this (refund vs. request the shortfall vs. accept it) is a
     * manual dashboard action, not something to silently auto-resolve.
     */
    @Transactional
    public void flagNowPaymentsPartialPayment(String orderId, String paymentId) {
        transactionRepository.findByReference(orderId).ifPresentOrElse(tx -> {
            if (tx.getStatus() == TransactionStatus.COMPLETED || tx.getStatus() == TransactionStatus.FAILED) {
                return;
            }
            tx.setDescription("NOWPayments deposit UNDERPAID (payment " + paymentId
                    + ") — needs manual review in NOWPayments dashboard, do not auto-credit");
            transactionRepository.save(tx);
            log.warn("NOWPayments partial payment flagged for manual review: orderId={} paymentId={}", orderId, paymentId);
        }, () -> log.warn("Partial payment callback for unknown NOWPayments orderId={}: paymentId={}", orderId, paymentId));
    }
}