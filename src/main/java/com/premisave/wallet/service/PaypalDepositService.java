package com.premisave.wallet.service;

import com.premisave.wallet.dto.DepositRequest;
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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;

/**
 * PayPal deposit business logic — order creation, vault-based account
 * linking, capture, and webhook reconciliation. Split out of the former
 * all-providers DepositService, mirroring PaypalService's existing role at
 * the API-integration layer.
 *
 * Called from DepositService.initiateDeposit (dispatcher) for initiation,
 * and directly from WalletController/PaymentCallbackController for the
 * confirm endpoints and webhook handlers.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaypalDepositService {

    private final WalletRepository walletRepository;
    private final TransactionRepository transactionRepository;
    private final PaypalService paypalService;
    private final FxRateService fxRateService;

    public PaymentResponse initiatePaypalDeposit(String userId, DepositRequest request,
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
}