package com.premisave.wallet.service;

import com.premisave.wallet.dto.PullTransactionRecord;
import com.premisave.wallet.dto.PullTransactionResponse;
import com.premisave.wallet.entity.Transaction;
import com.premisave.wallet.entity.Wallet;
import com.premisave.wallet.enums.Currency;
import com.premisave.wallet.enums.TransactionStatus;
import com.premisave.wallet.enums.TransactionType;
import com.premisave.wallet.repository.TransactionRepository;
import com.premisave.wallet.repository.WalletRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class PullTransactionService {

    private final MpesaService mpesaService;
    private final WalletRepository walletRepository;
    private final TransactionRepository transactionRepository;

    // ─── Registration (one-time per shortcode) ──────────────────────────────

    public PullTransactionResponse register() {
        return mpesaService.registerPullTransactions();
    }

    // ─── Query + reconciliation ──────────────────────────────────────────────

    /**
     * Pulls C2B transactions for the given window (defaults applied by the
     * caller — see queryAndReconcileDefault) and reconciles each one against
     * our local Transaction records:
     *  - Already recorded (existsByProviderReference)? Skip as duplicate.
     *  - New, and billreference matches a wallet's account number (email)?
     *    Credit the wallet now — this is exactly what would have happened
     *    had the original C2B confirmation callback reached us.
     *  - New, but no matching wallet? Left unmatched — logged for manual
     *    follow-up rather than guessed at.
     *
     * Only "c2b-pay-bill-debit" / "c2b-buy-goods-debit" style customer
     * payment records make sense to credit; anything else pulled back is
     * logged but not credited.
     */
    @Transactional
    public PullTransactionResponse queryAndReconcile(LocalDateTime startDate, LocalDateTime endDate, int offsetValue) {
        PullTransactionResponse result = mpesaService.queryPullTransactions(startDate, endDate, offsetValue);

        if (!result.isSuccess()) {
            return result;
        }

        List<PullTransactionRecord> records = result.getTransactions() != null ? result.getTransactions() : List.of();
        int recovered = 0, duplicates = 0, unmatched = 0;

        for (PullTransactionRecord record : records) {
            String txId = record.getTransactionId();
            if (txId == null || txId.isBlank()) {
                continue;
            }

            if (transactionRepository.existsByProviderReference(txId)) {
                duplicates++;
                continue;
            }

            if (!isCustomerPaymentType(record.getTransactionType())) {
                log.info("Pull Transactions: skipping non-C2B-debit record transactionId={} type={}",
                        txId, record.getTransactionType());
                continue;
            }

            String email = record.getBillReference() != null ? record.getBillReference().trim().toLowerCase() : null;
            Wallet wallet = email != null ? walletRepository.findByAccountNumber(email).orElse(null) : null;

            if (wallet == null) {
                unmatched++;
                log.warn("Pull Transactions: recovered record transactionId={} billreference={} has no matching " +
                        "wallet locally — NOT credited, needs manual review", txId, record.getBillReference());
                continue;
            }

            creditRecoveredTransaction(wallet, record);
            recovered++;
            log.info("Pull Transactions: recovered and credited missed C2B transactionId={} wallet={} amount={}",
                    txId, wallet.getId(), record.getAmount());
        }

        result.setRecovered(recovered);
        result.setDuplicates(duplicates);
        result.setUnmatched(unmatched);
        return result;
    }

    /** Convenience overload — defaults to a 1-day lookback window and offset 0 (Safaricom retains max 48h). */
    public PullTransactionResponse queryAndReconcileDefault() {
        LocalDateTime end = LocalDateTime.now();
        LocalDateTime start = end.minusDays(1);
        return queryAndReconcile(start, end, 0);
    }

    /**
     * Best-effort handler for whatever Safaricom posts to our registered
     * CallBackURL. Safaricom's docs don't specify this payload's shape —
     * only the Query response shape is documented — so this reuses the same
     * parser and reconciliation logic on the assumption the shape matches.
     * Logs the raw payload regardless so nothing is silently lost if the
     * assumption turns out wrong.
     */
    @Transactional
    public void handleCallback(String rawPayload) {
        log.info("Pull Transactions callback received (raw): {}", rawPayload);
        try {
            PullTransactionResponse parsed = mpesaService.parsePullTransactionsResponse(rawPayload);
            List<PullTransactionRecord> records = parsed.getTransactions() != null ? parsed.getTransactions() : List.of();

            int recovered = 0, duplicates = 0, unmatched = 0;
            for (PullTransactionRecord record : records) {
                String txId = record.getTransactionId();
                if (txId == null || txId.isBlank() || transactionRepository.existsByProviderReference(txId)) {
                    duplicates++;
                    continue;
                }
                if (!isCustomerPaymentType(record.getTransactionType())) {
                    continue;
                }
                String email = record.getBillReference() != null ? record.getBillReference().trim().toLowerCase() : null;
                Wallet wallet = email != null ? walletRepository.findByAccountNumber(email).orElse(null) : null;
                if (wallet == null) {
                    unmatched++;
                    continue;
                }
                creditRecoveredTransaction(wallet, record);
                recovered++;
            }
            log.info("Pull Transactions callback reconciled: recovered={} duplicates={} unmatched={}",
                    recovered, duplicates, unmatched);
        } catch (Exception e) {
            log.error("Failed to parse/reconcile Pull Transactions callback payload — raw payload logged above", e);
        }
    }

    // ─── Scheduled sweep ──────────────────────────────────────────────────────

    /**
     * Runs the reconciliation automatically every 6 hours so missed C2B
     * confirmations get recovered even if nobody remembers to trigger it
     * manually. Registration must have already succeeded at least once —
     * if not, Safaricom will simply return an error here, which is logged
     * and otherwise harmless.
     */
    @Scheduled(cron = "0 0 */6 * * *")
    public void scheduledReconciliation() {
        try {
            PullTransactionResponse result = queryAndReconcileDefault();
            if (!result.isSuccess()) {
                log.warn("Scheduled Pull Transactions reconciliation failed: {}", result.getMessage());
                return;
            }
            log.info("Scheduled Pull Transactions reconciliation complete: recovered={} duplicates={} unmatched={}",
                    result.getRecovered(), result.getDuplicates(), result.getUnmatched());
        } catch (Exception e) {
            log.error("Scheduled Pull Transactions reconciliation threw an exception", e);
        }
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private boolean isCustomerPaymentType(String transactionType) {
        if (transactionType == null) return false;
        String t = transactionType.toLowerCase();
        return t.contains("pay-bill-debit") || t.contains("buy-goods-debit") || t.contains("c2b");
    }

    private void creditRecoveredTransaction(Wallet wallet, PullTransactionRecord record) {
        BigDecimal amount = new BigDecimal(record.getAmount());

        wallet.setBalance(wallet.getBalance().add(amount));
        walletRepository.save(wallet);

        Transaction tx = new Transaction();
        tx.setUserId(wallet.getUserId());
        tx.setWalletId(wallet.getId());
        tx.setType(TransactionType.DEPOSIT);
        tx.setStatus(TransactionStatus.COMPLETED);
        tx.setAmount(amount);
        tx.setCurrency(Currency.KES);
        tx.setDescription("M-Pesa C2B deposit recovered via Pull Transactions reconciliation ("
                + record.getMsisdn() + ")");
        tx.setProviderReference(record.getTransactionId());
        tx.setReference(record.getTransactionId());
        transactionRepository.save(tx);
    }
}