package com.premisave.wallet.service;

import com.premisave.wallet.dto.DisbursementRequest;
import com.premisave.wallet.dto.DisbursementResponse;
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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
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

    @Transactional
    public DisbursementResponse processDisbursement(String userId, DisbursementRequest request) {
        idempotencyService.checkIdempotency(request.getReference());

        Wallet wallet = walletRepository.findByUserId(userId)
                .orElseThrow(() -> new WalletNotFoundException("Wallet not found for userId: " + userId));

        if (wallet.isFrozen()) throw new WalletFrozenException("Wallet is frozen");
        if (wallet.getBalance().compareTo(request.getAmount()) < 0)
            throw new InsufficientFundsException("Insufficient funds for disbursement");

        // Deduct balance upfront — refund on failure
        wallet.setBalance(wallet.getBalance().subtract(request.getAmount()));
        walletRepository.save(wallet);

        String reference = request.getReference() != null ? request.getReference() : UUID.randomUUID().toString();
        String provider  = request.getProvider() != null ? request.getProvider().toUpperCase() : "MPESA";

        Disbursement disbursement = buildDisbursement(userId, wallet.getId(), request, reference, provider);

        ProviderResult result = switch (provider) {
            case "MPESA"  -> disburseMpesa(request);
            case "STRIPE" -> disburseStripe(request);
            case "PAYPAL" -> disbursePaypal(request);
            default -> new ProviderResult(false, "Unsupported provider: " + provider, null);
        };

        if (result.success()) {
            disbursement.setStatus(DisbursementStatus.SUCCESS);
            disbursement.setProviderReference(result.providerRef());
            saveDisbursementTransaction(userId, wallet.getId(), request.getAmount(), disbursement, reference);
        } else {
            // Refund
            wallet.setBalance(wallet.getBalance().add(request.getAmount()));
            walletRepository.save(wallet);
            disbursement.setStatus(DisbursementStatus.FAILED);
            log.warn("Disbursement failed for userId={}, refunded. Reason: {}", userId, result.message());
        }

        disbursementRepository.save(disbursement);

        return new DisbursementResponse(disbursement.getId(), disbursement.getStatus().name(), result.message());
    }

    // ─── Provider dispatch ────────────────────────────────────────────────────

    private ProviderResult disburseMpesa(DisbursementRequest request) {
        try {
            var response = mpesaService.sendB2C(request.getDestination(), request.getAmount());
            return new ProviderResult(response.isSuccess(), response.getMessage(), response.getConversationId());
        } catch (Exception e) {
            return new ProviderResult(false, e.getMessage(), null);
        }
    }

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

    private Disbursement buildDisbursement(String userId, String walletId,
                                            DisbursementRequest request, String reference, String provider) {
        Disbursement d = new Disbursement();
        d.setUserId(userId);
        d.setWalletId(walletId);
        d.setAmount(request.getAmount());
        d.setDestination(request.getDestination());
        d.setProvider(provider);
        d.setStatus(DisbursementStatus.PENDING);
        d.setReference(reference);
        return d;
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