package com.premisave.wallet.service;

import com.premisave.wallet.entity.Transaction;
import com.premisave.wallet.entity.Transfer;
import com.premisave.wallet.entity.Wallet;
import com.premisave.wallet.enums.Currency;
import com.premisave.wallet.enums.TransactionStatus;
import com.premisave.wallet.enums.TransactionType;
import com.premisave.wallet.repository.TransactionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Records BOTH Transaction rows (debit + credit) for a confirmed transfer
 * — mirrors DepositTransactionRecorder/DisbursementTransactionRecorder in
 * spirit, but genuinely different in shape: a transfer touches two
 * wallets in one operation, so it needs two Transaction rows per call,
 * not one. Matches exactly what TransferService's own buildTransaction
 * helper used to do inline, now centralized here.
 */
@Service
@RequiredArgsConstructor
public class TransferTransactionRecorder {

    private final TransactionRepository transactionRepository;

    public void record(Transfer transfer, Wallet sender, Wallet recipient) {
        String reasonSuffix = transfer.getReason() != null && !transfer.getReason().isBlank()
                ? " - " + transfer.getReason() : "";

        Transaction debit = new Transaction();
        debit.setUserId(transfer.getSenderId());
        debit.setWalletId(sender.getId());
        debit.setType(TransactionType.TRANSFER);
        debit.setStatus(TransactionStatus.COMPLETED);
        debit.setAmount(transfer.getAmount().negate());
        debit.setCurrency(Currency.KES);
        debit.setDescription("Transfer to " + recipient.getAccountNumber() + reasonSuffix);
        debit.setReference(transfer.getReference());
        transactionRepository.save(debit);

        Transaction credit = new Transaction();
        credit.setUserId(transfer.getRecipientId());
        credit.setWalletId(recipient.getId());
        credit.setType(TransactionType.TRANSFER);
        credit.setStatus(TransactionStatus.COMPLETED);
        credit.setAmount(transfer.getAmount());
        credit.setCurrency(Currency.KES);
        credit.setDescription("Transfer from " + sender.getAccountNumber() + reasonSuffix);
        credit.setReference(transfer.getReference());
        transactionRepository.save(credit);
    }
}