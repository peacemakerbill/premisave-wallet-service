package com.premisave.wallet.service;

import com.premisave.wallet.dto.PendingReconciliationItem;
import com.premisave.wallet.entity.Deposit;
import com.premisave.wallet.entity.Disbursement;
import com.premisave.wallet.entity.MpesaOperation;
import com.premisave.wallet.entity.Payment;
import com.premisave.wallet.entity.Transfer;
import com.premisave.wallet.enums.DepositStatus;
import com.premisave.wallet.enums.DisbursementStatus;
import com.premisave.wallet.enums.MpesaOperationType;
import com.premisave.wallet.enums.PaymentStatus;
import com.premisave.wallet.enums.TransferStatus;
import com.premisave.wallet.repository.DepositRepository;
import com.premisave.wallet.repository.DisbursementRepository;
import com.premisave.wallet.repository.MpesaOperationRepository;
import com.premisave.wallet.repository.PaymentRepository;
import com.premisave.wallet.repository.TransferRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Cross-entity "what's currently pending, across the whole system" view —
 * a genuinely new, dedicated concern rather than something that
 * belonged to any one existing service, since no single service owns
 * all five repositories this needs. Pure read — no resolution logic
 * lives here at all, only discovery.
 *
 * Uses findAll() + Java-side filtering uniformly across all five
 * repositories rather than assuming each one has its own dedicated
 * findByStatus(...) method — some do (Disbursement's
 * findByStatusAndCreatedAtBefore, used by its own sweeper), but rather
 * than assume the others also do and risk a compile failure, this stays
 * uniformly safe. Admin-only, low-frequency dashboard query, not a hot
 * path — the efficiency tradeoff is deliberate and acceptable here.
 *
 * resolvableViaApi is true only where a real, safe, already-built
 * resolution endpoint exists right now: Disbursement (built earlier),
 * Deposit (built alongside this), and M-Pesa Reversal specifically
 * (built alongside this, reusing MpesaOperationsService's own
 * applyReversalToWallet logic). Transfer, Payment, and M-Pesa's
 * non-Reversal operation types (Account Balance, Transaction Status —
 * pure queries with no wallet-affecting outcome, nothing to approve)
 * are listed for visibility but explicitly flagged as not yet
 * resolvable through this API.
 */
@Service
@RequiredArgsConstructor
public class ReconciliationService {

    private final DepositRepository depositRepository;
    private final DisbursementRepository disbursementRepository;
    private final TransferRepository transferRepository;
    private final PaymentRepository paymentRepository;
    private final MpesaOperationRepository mpesaOperationRepository;

    public List<PendingReconciliationItem> getAllPending() {
        List<PendingReconciliationItem> items = new ArrayList<>();

        for (Deposit d : depositRepository.findAll()) {
            if (d.getStatus() == DepositStatus.PENDING) {
                items.add(toItem("DEPOSIT", d.getId(), d.getUserId(), d.getAmount(), d.getProvider(),
                        d.getReference(), d.getCreatedAt(), true,
                        "POST /admin/reconciliation/deposits/{id}/approve or /reject"));
            }
        }

        for (Disbursement d : disbursementRepository.findAll()) {
            if (d.getStatus() == DisbursementStatus.PENDING) {
                items.add(toItem("DISBURSEMENT", d.getId(), d.getUserId(), d.getAmount(), d.getProvider(),
                        d.getReference(), d.getCreatedAt(), true,
                        "POST /admin/reconciliation/disbursements/{id}/approve or /reject"));
            }
        }

        for (Transfer t : transferRepository.findAll()) {
            if (t.getStatus() == TransferStatus.PENDING) {
                items.add(toItem("TRANSFER", t.getId(), t.getSenderId(), t.getAmount(), null,
                        t.getReference(), t.getCreatedAt(), false,
                        "No safe resolution endpoint exists yet — TransferService.java hasn't been reviewed to "
                                + "confirm what a safe reject would need to do (e.g. whether the sender was "
                                + "already debited before this became PENDING)."));
            }
        }

        for (Payment p : paymentRepository.findAll()) {
            if (p.getStatus() == PaymentStatus.PENDING) {
                items.add(toItem("PAYMENT", p.getId(), p.getUserId(), p.getAmount(), null,
                        p.getReference(), p.getCreatedAt(), false,
                        "No safe resolution endpoint exists yet — same reasoning as Transfer above."));
            }
        }

        for (MpesaOperation op : mpesaOperationRepository.findAll()) {
            if (op.getStatus() == DisbursementStatus.PENDING) {
                boolean isReversal = op.getType() == MpesaOperationType.REVERSAL;
                items.add(toItem("MPESA_" + op.getType(), op.getId(), null, null, "MPESA",
                        op.getConversationId(), op.getCreatedAt(), true,
                        isReversal
                                ? "POST /admin/reconciliation/mpesa-operations/{id}/approve-reversal or /reject-reversal"
                                : "POST /admin/reconciliation/mpesa-operations/{id}/close — no wallet-affecting "
                                        + "outcome for this type (pure query), so this just stops the recurring "
                                        + "stuck-operation log warning rather than approving/rejecting anything."));
            }
        }

        items.sort((a, b) -> a.getCreatedAt().compareTo(b.getCreatedAt()));
        return items;
    }

    private PendingReconciliationItem toItem(String entityType, String id, String userId, BigDecimal amount,
                                              String provider, String reference, LocalDateTime createdAt,
                                              boolean resolvableViaApi, String resolutionHint) {
        PendingReconciliationItem item = new PendingReconciliationItem();
        item.setEntityType(entityType);
        item.setId(id);
        item.setUserId(userId);
        item.setAmount(amount);
        item.setProvider(provider);
        item.setReference(reference);
        item.setCreatedAt(createdAt);
        item.setMinutesPending(Duration.between(createdAt, LocalDateTime.now()).toMinutes());
        item.setResolvableViaApi(resolvableViaApi);
        item.setResolutionHint(resolutionHint);
        return item;
    }
}