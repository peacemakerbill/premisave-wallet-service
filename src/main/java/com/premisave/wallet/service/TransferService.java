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
 * typed exceptions as before, with NO Transfer record created — same as
 * Disbursement not creating a record for a request that fails before the
 * actual operation is attempted. A Transfer completes entirely within one
 * request (no external provider, no webhook to wait on), so unlike
 * Deposit/Disbursement there's no genuine PENDING window to capture —
 * the record is created once, with its final status, right when the
 * balance mutation actually happens.
 *
 * Two public entry points, one shared implementation:
 *  - transfer() — user-initiated, sender resolved from their own JWT
 *    (via WalletController.resolveUserId), initiatedBy="USER".
 *  - transferInternal() — another Premisave service calling via
 *    POST /internal/transfer (see InternalController). Requires an
 *    explicit senderUserId in the request body, since
 *    InternalApiKeyFilter authenticates the CALLING SERVICE, not an end
 *    user — there's no JWT to resolve a sender from.
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

        // Centralized validation — checks sender exists/not frozen/sufficient balance.
        walletService.validateWalletForTransaction(senderUserId, amount);

        if (recipient.isFrozen()) {
            throw new WalletFrozenException("Recipient wallet is currently frozen and cannot receive funds");
        }

        String reference = requestedReference != null ? requestedReference : UUID.randomUUID().toString();

        // Perform transfer
        sender.setBalance(sender.getBalance().subtract(amount));
        recipient.setBalance(recipient.getBalance().add(amount));

        walletRepository.save(sender);
        walletRepository.save(recipient);

        Transfer transfer = new Transfer();
        transfer.setSenderId(senderUserId);
        transfer.setSenderWalletId(sender.getId());
        transfer.setRecipientId(recipient.getUserId());
        transfer.setRecipientWalletId(recipient.getId());
        transfer.setAmount(amount);
        transfer.setCurrency(Currency.KES);
        transfer.setDescription(description);
        transfer.setStatus(TransferStatus.SUCCESS);
        transfer.setReference(reference);
        transfer.setInitiatedBy(initiatedBy);
        transferRepository.save(transfer);

        transferTransactionRecorder.record(transfer, sender, recipient);

        log.info("Transfer completed successfully | Sender: {} | Recipient: {} | Amount: {} | Ref: {} | InitiatedBy: {}",
                sender.getAccountNumber(), recipient.getAccountNumber(), amount, reference, initiatedBy);

        return new PaymentResponse(true, reference, "Transfer successful");
    }
}