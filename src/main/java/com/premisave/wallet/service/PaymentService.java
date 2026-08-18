package com.premisave.wallet.service;

import com.premisave.wallet.dto.InternalPaymentRequest;
import com.premisave.wallet.dto.PaymentInitiateRequest;
import com.premisave.wallet.dto.PaymentResponse;
import com.premisave.wallet.entity.Payment;
import com.premisave.wallet.entity.Wallet;
import com.premisave.wallet.enums.Currency;
import com.premisave.wallet.enums.PaymentStatus;
import com.premisave.wallet.exception.WalletNotFoundException;
import com.premisave.wallet.repository.PaymentRepository;
import com.premisave.wallet.repository.WalletRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Wallet-to-platform payment logic (ad subscriptions, booking fees, etc.)
 * — mirrors TransferService's structure and reasoning exactly, one wallet
 * touched instead of two. No commission concept here — unlike a Transfer
 * or Disbursement, a Payment IS company revenue directly; there's no
 * "cut of someone else's money" to take on top of it.
 *
 * Now uses the dedicated Payment entity (Stage 2) instead of the bare
 * Transaction row this used to create directly — same migration
 * Deposit/Disbursement/Transfer already went through. A validation
 * failure (wallet not found, insufficient funds) still throws the same
 * typed exception as before, with no Payment record created — same
 * "record only once the real operation happens" reasoning as everywhere
 * else in this codebase.
 *
 * Two public entry points, one shared implementation:
 *  - deductFromWallet() — user-initiated, via POST /payments/deduct,
 *    payer resolved from their own JWT.
 *  - payInternal() — another Premisave service calling via
 *    POST /internal/payment. Requires an explicit userId in the request
 *    body, since InternalApiKeyFilter authenticates the CALLING SERVICE,
 *    not an end user.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentService {

    private final WalletRepository walletRepository;
    private final PaymentRepository paymentRepository;
    private final WalletService walletService;
    private final IdempotencyService idempotencyService;
    private final PaymentTransactionRecorder paymentTransactionRecorder;

    @Transactional
    public PaymentResponse deductFromWallet(String userId, PaymentInitiateRequest request) {
        return executePayment(userId, request.getAmount(), request.getService(), request.getDescription(),
                request.getReference(), "USER");
    }

    @Transactional
    public PaymentResponse payInternal(InternalPaymentRequest request) {
        return executePayment(request.getUserId(), request.getAmount(), request.getService(),
                request.getDescription(), request.getReference(), request.getInitiatedBy());
    }

    private PaymentResponse executePayment(String userId, BigDecimal amount, String service, String description,
                                            String requestedReference, String initiatedBy) {
        idempotencyService.checkIdempotency(requestedReference);

        Wallet wallet = walletRepository.findByUserId(userId)
                .orElseThrow(() -> new WalletNotFoundException("Wallet not found for userId: " + userId));

        walletService.validateWalletForTransaction(userId, amount);

        // Falls back to a generated UUID if no reference was supplied,
        // same as TransferService — the original version of this method
        // saved a null reference outright when one wasn't given, leaving
        // that specific payment with no idempotency protection at all.
        String reference = requestedReference != null ? requestedReference : UUID.randomUUID().toString();

        wallet.setBalance(wallet.getBalance().subtract(amount));
        walletRepository.save(wallet);

        Payment payment = new Payment();
        payment.setUserId(userId);
        payment.setWalletId(wallet.getId());
        payment.setAmount(amount);
        payment.setCurrency(Currency.KES);
        payment.setService(service);
        payment.setDescription(description);
        payment.setStatus(PaymentStatus.SUCCESS);
        payment.setReference(reference);
        payment.setInitiatedBy(initiatedBy);
        paymentRepository.save(payment);

        paymentTransactionRecorder.record(payment);

        log.info("Payment deducted successfully: userId={} | amount={} | service={} | ref={} | initiatedBy={}",
                userId, amount, service, reference, initiatedBy);

        return new PaymentResponse(true, payment.getId(), "Payment successful");
    }
}