package com.premisave.wallet.service;

import com.premisave.wallet.dto.ActivityResponse;
import com.premisave.wallet.entity.Deposit;
import com.premisave.wallet.entity.Disbursement;
import com.premisave.wallet.entity.Payment;
import com.premisave.wallet.entity.Transaction;
import com.premisave.wallet.entity.Transfer;
import com.premisave.wallet.enums.TransactionType;
import com.premisave.wallet.repository.DepositRepository;
import com.premisave.wallet.repository.DisbursementRepository;
import com.premisave.wallet.repository.PaymentRepository;
import com.premisave.wallet.repository.TransactionRepository;
import com.premisave.wallet.repository.TransferRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * A person's wallet activity in one list. It is built from the records of each kind (deposits, withdrawals, transfers and
 * payments), so a pending or failed attempt shows up too, plus the refunds and manual adjustments that only exist as
 * transaction rows. The transaction rows that confirm a deposit, withdrawal, transfer or payment are left out, because
 * the record they confirm is already in the list.
 */
@Service
@RequiredArgsConstructor
public class TransactionService {

    private final TransactionRepository transactionRepository;
    private final DepositRepository depositRepository;
    private final DisbursementRepository disbursementRepository;
    private final TransferRepository transferRepository;
    private final PaymentRepository paymentRepository;

    public List<ActivityResponse> getTransactionHistory(String userId) {
        List<ActivityResponse> out = new ArrayList<>();

        for (Deposit d : depositRepository.findByUserIdOrderByCreatedAtDesc(userId)) {
            out.add(new ActivityResponse(d.getId(), "DEPOSIT", "IN", name(d.getStatus()), d.getAmount(),
                    name(d.getCurrency(), "USD"), "Deposit via " + label(d.getProvider()), d.getProvider(), d.getCreatedAt())
                    .with(null, null, d.getSenderName(), d.getChannel(), d.getReference(), d.getFailureReason()));
        }
        for (Disbursement d : disbursementRepository.findByUserIdOrderByCreatedAtDesc(userId)) {
            BigDecimal amount = d.getTotalDebited() != null ? d.getTotalDebited() : d.getAmount();
            out.add(new ActivityResponse(d.getId(), "WITHDRAWAL", "OUT", name(d.getStatus()), amount,
                    d.getCurrency() == null || d.getCurrency().isBlank() ? "USD" : d.getCurrency(),
                    "Withdrawal via " + label(d.getProvider()), d.getProvider(), d.getCreatedAt())
                    .with(null, null, d.getVerifiedRecipientName(), d.getDestination(), d.getReference(), d.getFailureReason()));
        }
        for (Transfer t : transferRepository.findBySenderIdOrRecipientIdOrderByCreatedAtDesc(userId, userId)) {
            boolean sent = userId.equals(t.getSenderId());
            String other = sent ? t.getRecipientEmail() : t.getSenderEmail();
            String text = (sent ? "Sent to " : "Received from ") + (other == null ? "another wallet" : other);
            out.add(new ActivityResponse(t.getId(), "TRANSFER", sent ? "OUT" : "IN", name(t.getStatus()),
                    sent && t.getTotalDebited() != null ? t.getTotalDebited() : t.getAmount(),
                    name(t.getCurrency(), "USD"), text, null, t.getCreatedAt())
                    .with(sent ? t.getRecipientId() : t.getSenderId(), other, null, null, t.getReference(), null));
        }
        for (Payment p : paymentRepository.findByUserIdOrderByCreatedAtDesc(userId)) {
            String text = p.getDescription() != null && !p.getDescription().isBlank() ? p.getDescription() : "Payment for " + label(p.getService());
            out.add(new ActivityResponse(p.getId(), "PAYMENT", "OUT", name(p.getStatus()), p.getAmount(),
                    name(p.getCurrency(), "USD"), text, p.getService(), p.getCreatedAt())
                    .with(null, null, null, p.getService(), p.getReference(), p.getFailureReason()));
        }
        for (Transaction tx : transactionRepository.findByUserIdOrderByCreatedAtDesc(userId)) {
            boolean adjustment = tx.getReference() != null && tx.getReference().startsWith("ADJ-");
            if (tx.getType() != TransactionType.REFUND && !adjustment) continue;
            BigDecimal amount = tx.getAmount();
            out.add(new ActivityResponse(tx.getId(), tx.getType() == null ? "REFUND" : tx.getType().name(),
                    amount != null && amount.signum() < 0 ? "OUT" : "IN", name(tx.getStatus()),
                    amount == null ? null : amount.abs(), name(tx.getCurrency(), "USD"), tx.getDescription(), null, tx.getCreatedAt()));
        }

        out.sort(Comparator.comparing(ActivityResponse::getCreatedAt, Comparator.nullsLast(Comparator.<LocalDateTime>reverseOrder())));
        return out;
    }

    private static String name(Enum<?> e) {
        return e == null ? "PENDING" : e.name();
    }

    private static String name(Enum<?> e, String fallback) {
        return e == null ? fallback : e.name();
    }

    private static String label(String s) {
        return s == null || s.isBlank() ? "the wallet" : s;
    }
}
