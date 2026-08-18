package com.premisave.wallet.service;

import com.premisave.wallet.dto.InternalTransferRequest;
import com.premisave.wallet.dto.PaymentResponse;
import com.premisave.wallet.dto.TransferRequest;
import com.premisave.wallet.entity.Transfer;
import com.premisave.wallet.entity.Wallet;
import com.premisave.wallet.enums.Currency;
import com.premisave.wallet.enums.TransferStatus;
import com.premisave.wallet.exception.WalletFrozenException;
import com.premisave.wallet.exception.WalletNotFoundException;
import com.premisave.wallet.repository.TransferRepository;
import com.premisave.wallet.repository.WalletRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Wallet-to-wallet transfer logic — mirrors DisbursementService's real
 * behavior (not just its status enum): validation failures (recipient not
 * found, self-transfer, frozen, insufficient funds) still throw the same
 * typed exceptions as before, with NO Transfer record created. A
 * Transfer completes entirely within one request (no external provider,
 * no webhook to wait on), so the record is created once, with its final
 * status, right when the balance mutation actually happens.
 *
 * COMMISSION: charged ON TOP of the stated amount, confirmed explicitly
 * — the sender pays amount + commission; the recipient receives exactly
 * `amount`, completely unaffected. See CommissionService's javadoc for
 * the full reasoning. Nothing is credited to any real company wallet —
 * the commission is only ever recorded in CompanyLedgerEntry for
 * reporting, confirmed explicitly.
 *
 * Two public entry points, one shared implementation:
 *  - transfer() — user-initiated, sender resolved from their own JWT.
 *  - transferInternal() — another Premisave service calling via
 *    POST /internal/transfer. Requires an explicit senderUserId in the
 *    request body, since InternalApiKeyFilter authenticates the CALLING
 *    SERVICE, not an end user.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TransferService {

    private final WalletRepository walletRepository;
    private final TransferRepository transferRepository;
    private final WalletService walletService;
    private final IdempotencyService idempotencyService;
    private final TransferTransactionRecorder transferTransactionRecorder;
    private final CommissionService commissionService;

    @Transactional
    public PaymentResponse transfer(String senderUserId, TransferRequest request) {
        return executeTransfer(senderUserId, request.getRecipientAccountNumber(), request.getAmount(),
                request.getDescription(), request.getReference(), "USER");
    }

    @Transactional
    public PaymentResponse transferInternal(InternalTransferRequest request) {
        return executeTransfer(request.getSenderUserId(), request.getRecipientAccountNumber(), request.getAmount(),
                request.getDescription(), request.getReference(), request.getInitiatedBy());
    }

    private PaymentResponse executeTransfer(String senderUserId, String recipientAccountNumber, BigDecimal amount,
                                             String description, String requestedReference, String initiatedBy) {
        idempotencyService.checkIdempotency(requestedReference);

        Wallet sender = walletRepository.findByUserId(senderUserId)
                .orElseThrow(() -> new WalletNotFoundException("Sender wallet not found for userId: " + senderUserId));

        Wallet recipient = walletRepository.findByAccountNumber(recipientAccountNumber)
                .orElseThrow(() -> new WalletNotFoundException(
                        "Recipient wallet not found for account: " + recipientAccountNumber));

        if (sender.getId().equals(recipient.getId())) {
            throw new IllegalArgumentException("Cannot transfer funds to your own wallet");
        }

        BigDecimal commission = commissionService.calculateInternalTransferCommission(amount);
        BigDecimal totalDebit = amount.add(commission);

        // Validated against the TOTAL the sender actually needs — amount
        // + commission, not just the nominal transfer amount — since the
        // commission is charged on top, not deducted from the transfer.
        walletService.validateWalletForTransaction(senderUserId, totalDebit);

        if (recipient.isFrozen()) {
            throw new WalletFrozenException("Recipient wallet is currently frozen and cannot receive funds");
        }

        String reference = requestedReference != null ? requestedReference : UUID.randomUUID().toString();

        // Perform transfer — sender pays amount PLUS commission;
        // recipient receives exactly the stated amount, unaffected.
        sender.setBalance(sender.getBalance().subtract(totalDebit));
        recipient.setBalance(recipient.getBalance().add(amount));

        walletRepository.save(sender);
        walletRepository.save(recipient);

        Transfer transfer = new Transfer();
        transfer.setSenderId(senderUserId);
        transfer.setSenderWalletId(sender.getId());
        transfer.setSenderEmail(sender.getAccountNumber());
        transfer.setRecipientId(recipient.getUserId());
        transfer.setRecipientWalletId(recipient.getId());
        transfer.setRecipientEmail(recipient.getAccountNumber());
        transfer.setAmount(amount);
        transfer.setTotalDebited(totalDebit);
        transfer.setCurrency(Currency.KES);
        transfer.setDescription(description);
        transfer.setStatus(TransferStatus.SUCCESS);
        transfer.setReference(reference);
        transfer.setInitiatedBy(initiatedBy);
        transferRepository.save(transfer);

        transferTransactionRecorder.record(transfer, sender, recipient);

        commissionService.recordCommission("COMMISSION_TRANSFER", commission,
                commissionService.getInternalTransferRate(), amount, "TRANSFER", transfer.getId(), reference,
                senderUserId, "Commission on transfer to " + recipient.getAccountNumber());

        log.info("Transfer completed | Sender: {} | Recipient: {} | Amount: {} | Commission: {} | TotalDebited: {} | Ref: {} | InitiatedBy: {}",
                sender.getAccountNumber(), recipient.getAccountNumber(), amount, commission, totalDebit, reference, initiatedBy);

        return new PaymentResponse(true, reference, "Transfer successful");
    }
}