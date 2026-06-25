package com.premisave.wallet.service;

import com.premisave.wallet.dto.DepositRequest;
import com.premisave.wallet.dto.MpesaStkPushRequest;
import com.premisave.wallet.dto.PaymentResponse;
import com.premisave.wallet.entity.Transaction;
import com.premisave.wallet.entity.Wallet;
import com.premisave.wallet.enums.TransactionStatus;
import com.premisave.wallet.enums.TransactionType;
import com.premisave.wallet.exception.WalletNotFoundException;
import com.premisave.wallet.repository.TransactionRepository;
import com.premisave.wallet.repository.WalletRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class DepositService {

    private final WalletRepository walletRepository;
    private final TransactionRepository transactionRepository;
    private final MpesaService mpesaService;

    /**
     * Initiates an M-Pesa STK push deposit.
     * Wallet balance is credited via the M-Pesa callback — not here.
     */
    public PaymentResponse initiateDeposit(String userId, String userEmail, DepositRequest request) {
        // Ensure wallet exists before initiating
        walletRepository.findByUserId(userId)
                .orElseThrow(() -> new WalletNotFoundException("Wallet not found. Please create a wallet first."));

        String provider = request.getProvider() != null ? request.getProvider().toUpperCase() : "MPESA";

        if ("MPESA".equals(provider)) {
            MpesaStkPushRequest stkRequest = new MpesaStkPushRequest();
            stkRequest.setPhoneNumber(userEmail); // placeholder — real impl needs phone from profile
            stkRequest.setAmount(request.getAmount());
            stkRequest.setAccountReference("PREMISAVE-" + userId);

            String result = mpesaService.initiateStkPush(stkRequest);
            log.info("STK push initiated for userId={} amount={} result={}", userId, request.getAmount(), result);
            return new PaymentResponse(true, result, "M-Pesa STK push initiated. Check your phone.");
        }

        return new PaymentResponse(false, null, "Unsupported deposit provider: " + provider);
    }

    /**
     * Called by M-Pesa callback after successful payment — credits the wallet.
     */
    public void creditWalletFromCallback(String accountNumber, java.math.BigDecimal amount,
                                          String mpesaRef, String description) {
        Wallet wallet = walletRepository.findByAccountNumber(accountNumber)
                .orElseThrow(() -> new WalletNotFoundException("Wallet not found for: " + accountNumber));

        wallet.setBalance(wallet.getBalance().add(amount));
        walletRepository.save(wallet);

        Transaction tx = new Transaction();
        tx.setUserId(wallet.getUserId());
        tx.setWalletId(wallet.getId());
        tx.setType(TransactionType.DEPOSIT);
        tx.setStatus(TransactionStatus.COMPLETED);
        tx.setAmount(amount);
        tx.setDescription(description != null ? description : "M-Pesa deposit");
        tx.setProviderReference(mpesaRef);
        transactionRepository.save(tx);

        log.info("Wallet credited: accountNumber={} amount={} ref={}", accountNumber, amount, mpesaRef);
    }
}