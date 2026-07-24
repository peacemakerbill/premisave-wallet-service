package com.premisave.wallet.service;

import com.premisave.wallet.dto.MpesaAsyncResponse;
import com.premisave.wallet.dto.MpesaReversalRequest;
import com.premisave.wallet.dto.TransactionStatusRequest;
import com.premisave.wallet.entity.MpesaOperation;
import com.premisave.wallet.entity.Transaction;
import com.premisave.wallet.entity.Wallet;
import com.premisave.wallet.enums.Currency;
import com.premisave.wallet.enums.DisbursementStatus;
import com.premisave.wallet.enums.MpesaOperationType;
import com.premisave.wallet.enums.TransactionStatus;
import com.premisave.wallet.enums.TransactionType;
import com.premisave.wallet.repository.MpesaOperationRepository;
import com.premisave.wallet.repository.TransactionRepository;
import com.premisave.wallet.repository.WalletRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class MpesaOperationsService {

    private final MpesaOperationRepository operationRepository;
    private final WalletRepository walletRepository;
    private final TransactionRepository transactionRepository;
    private final MpesaService mpesaService;
    private final IdempotencyService idempotencyService;

    // ─── Account Balance ─────────────────────────────────────────────────────

    @Transactional
    public MpesaAsyncResponse queryAccountBalance(String initiatedBy) {
        MpesaAsyncResponse result = mpesaService.queryAccountBalance();
        saveOperation(MpesaOperationType.ACCOUNT_BALANCE, initiatedBy, result, Map.of(), null);
        return result;
    }

    // ─── Transaction Status ──────────────────────────────────────────────────

    @Transactional
    public MpesaAsyncResponse queryTransactionStatus(String initiatedBy, TransactionStatusRequest request) {
        MpesaAsyncResponse result = mpesaService.queryTransactionStatus(request);
        Map<String, Object> summary = new HashMap<>();
        summary.put("transactionId", request.getTransactionId());
        summary.put("originatorConversationId", request.getOriginatorConversationId());
        saveOperation(MpesaOperationType.TRANSACTION_STATUS, initiatedBy, result, summary, null);
        return result;
    }

    // ─── Reversal ─────────────────────────────────────────────────────────────

    /**
     * Initiates a C2B reversal. If a completed DEPOSIT transaction is found
     * whose providerReference matches the given M-Pesa receipt, it's linked
     * so the wallet can be debited automatically once the reversal succeeds
     * (see completeOperation). If no matching transaction is found, the
     * reversal still proceeds — useful for M-Pesa payments that landed on
     * our shortcode without ever being credited to a wallet — but no
     * automatic wallet adjustment will happen.
     */
    @Transactional
    public MpesaAsyncResponse initiateReversal(String initiatedBy, MpesaReversalRequest request) {
        idempotencyService.checkIdempotency(request.getReference());

        Transaction original = transactionRepository.findByProviderReference(request.getTransactionId())
                .filter(tx -> tx.getStatus() == TransactionStatus.COMPLETED && tx.getType() == TransactionType.DEPOSIT)
                .orElse(null);

        MpesaAsyncResponse result = mpesaService.initiateReversal(request);

        Map<String, Object> summary = new HashMap<>();
        summary.put("transactionId", request.getTransactionId());
        summary.put("amount", request.getAmount());
        summary.put("reference", request.getReference());

        saveOperation(MpesaOperationType.REVERSAL, initiatedBy, result, summary,
                original != null ? original.getId() : null);

        if (original == null) {
            log.warn("Reversal initiated for M-Pesa receipt={} but no matching completed deposit " +
                    "transaction was found locally — wallet will NOT be auto-debited on success",
                    request.getTransactionId());
        }

        return result;
    }

    // ─── Reconciliation from Safaricom's ResultURL callback ─────────────────

    /**
     * Shared reconciliation entrypoint for AccountBalance, TransactionStatus,
     * and Reversal callbacks — all three share the same Result envelope
     * shape (see MpesaResultCallbackRequest), so PaymentCallbackController
     * routes all of them here regardless of which endpoint received them.
     */
    @Transactional
    public void completeOperation(String conversationId, boolean success, String resultCode,
                                   String resultDesc, Map<String, Object> resultParameters) {
        MpesaOperation op = operationRepository.findByConversationId(conversationId).orElse(null);
        if (op == null) {
            log.warn("M-Pesa operation callback for unknown ConversationID={} — ignoring", conversationId);
            return;
        }

        if (op.getStatus() != DisbursementStatus.PENDING) {
            log.warn("M-Pesa operation callback for already-finalized operation id={} status={} — ignoring duplicate",
                    op.getId(), op.getStatus());
            return;
        }

        op.setResultCode(resultCode);
        op.setResultDesc(resultDesc);
        op.setResultData(resultParameters);

        if (!success) {
            op.setStatus(DisbursementStatus.FAILED);
            operationRepository.save(op);
            log.warn("M-Pesa {} operation failed: id={} conversationId={} reason={}",
                    op.getType(), op.getId(), conversationId, resultDesc);
            return;
        }

        op.setStatus(DisbursementStatus.SUCCESS);
        operationRepository.save(op);
        log.info("M-Pesa {} operation completed: id={} conversationId={}", op.getType(), op.getId(), conversationId);

        if (op.getType() == MpesaOperationType.REVERSAL && op.getRelatedTransactionId() != null) {
            applyReversalToWallet(op, resultParameters);
        }
    }

    private void applyReversalToWallet(MpesaOperation op, Map<String, Object> resultParameters) {
        Transaction original = transactionRepository.findById(op.getRelatedTransactionId()).orElse(null);
        if (original == null) {
            log.error("Reversal succeeded but original transaction id={} no longer exists — manual reconciliation needed",
                    op.getRelatedTransactionId());
            return;
        }

        Wallet wallet = walletRepository.findById(original.getWalletId()).orElse(null);
        if (wallet == null) {
            log.error("Reversal succeeded but wallet id={} no longer exists — manual reconciliation needed",
                    original.getWalletId());
            return;
        }

        BigDecimal amount = original.getAmount().abs();
        wallet.setBalance(wallet.getBalance().subtract(amount));
        walletRepository.save(wallet);

        if (wallet.getBalance().compareTo(BigDecimal.ZERO) < 0) {
            log.warn("Wallet {} balance went negative ({}) after reversal debit — funds were likely already spent",
                    wallet.getId(), wallet.getBalance());
        }

        String mpesaTxId = resultParameters != null ? String.valueOf(resultParameters.getOrDefault("TransactionID", ""))
                : "";

        Transaction refund = new Transaction();
        refund.setUserId(wallet.getUserId());
        refund.setWalletId(wallet.getId());
        refund.setType(TransactionType.REFUND);
        refund.setStatus(TransactionStatus.COMPLETED);
        refund.setAmount(amount.negate());
        refund.setCurrency(original.getCurrency() != null ? original.getCurrency() : Currency.KES);
        refund.setDescription("Reversal of deposit " + original.getId() + " (M-Pesa receipt " + original.getProviderReference() + ")");
        refund.setReference(UUID.randomUUID().toString());
        refund.setProviderReference(mpesaTxId);
        transactionRepository.save(refund);

        log.info("Wallet {} debited {} following successful reversal of transaction {}",
                wallet.getId(), amount, original.getId());
    }

    public void markOperationTimedOut(String conversationId) {
        operationRepository.findByConversationId(conversationId).ifPresentOrElse(op ->
                log.warn("M-Pesa {} operation queue timeout: id={} conversationId={} — awaiting eventual result or manual reconciliation",
                        op.getType(), op.getId(), conversationId),
                () -> log.warn("Timeout callback for unknown ConversationID={}", conversationId));
    }

    // ─── Lookup for admins polling a submitted operation ────────────────────

    public MpesaOperation getOperation(String conversationId) {
        return operationRepository.findByConversationId(conversationId).orElse(null);
    }

    // ─── Stuck-operation sweeper (mirrors DisbursementService's) ────────────

    @Scheduled(fixedDelay = 15 * 60 * 1000) // every 15 minutes
    public void flagStuckOperations() {
        LocalDateTime cutoff = LocalDateTime.now().minusMinutes(30);
        List<MpesaOperation> stuck = operationRepository.findByStatusAndCreatedAtBefore(
                DisbursementStatus.PENDING, cutoff);

        if (!stuck.isEmpty()) {
            log.warn("{} M-Pesa operation(s) stuck in PENDING beyond 30 minutes — needs manual reconciliation: {}",
                    stuck.size(), stuck.stream().map(MpesaOperation::getId).toList());
        }
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private void saveOperation(MpesaOperationType type, String initiatedBy, MpesaAsyncResponse result,
                                Map<String, Object> requestSummary, String relatedTransactionId) {
        MpesaOperation op = new MpesaOperation();
        op.setType(type);
        op.setInitiatedBy(initiatedBy);
        op.setRequestSummary(requestSummary);
        op.setRelatedTransactionId(relatedTransactionId);
        String originatorId = blankToNull(result.getOriginatorConversationId());
        String conversationId = blankToNull(result.getConversationId());
        op.setOriginatorConversationId(originatorId);
        op.setConversationId(conversationId);
        op.setStatus(result.isSuccess() && conversationId != null
                ? DisbursementStatus.PENDING : DisbursementStatus.FAILED);
        if (!result.isSuccess()) {
            op.setResultDesc(result.getMessage());
        }
        operationRepository.save(op);
    }

    private String blankToNull(String value) {
        return (value == null || value.isBlank()) ? null : value;
    }
}