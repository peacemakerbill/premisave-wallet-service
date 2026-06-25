package com.premisave.wallet.service;

import com.premisave.wallet.dto.WalletBalanceResponse;
import com.premisave.wallet.dto.WalletResponse;
import com.premisave.wallet.entity.Wallet;
import com.premisave.wallet.exception.WalletNotFoundException;
import com.premisave.wallet.repository.WalletRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class WalletService {

    private final WalletRepository walletRepository;

    public WalletResponse getWallet(String accountNumber) {
        Wallet wallet = walletRepository.findByAccountNumber(accountNumber)
                .orElseThrow(() -> new WalletNotFoundException("Wallet not found for: " + accountNumber));
        return mapToResponse(wallet);
    }

    public WalletBalanceResponse getBalance(String accountNumber) {
        Wallet wallet = walletRepository.findByAccountNumber(accountNumber)
                .orElseThrow(() -> new WalletNotFoundException("Wallet not found for: " + accountNumber));
        return new WalletBalanceResponse(wallet.getBalance(), wallet.getCurrency().name(), wallet.isFrozen());
    }

    /**
     * Creates a wallet if one doesn't already exist for this user.
     * userId comes from the JWT claim — no placeholder needed.
     */
    public WalletResponse createWallet(String userId, String email) {
        Optional<Wallet> existing = walletRepository.findByAccountNumber(email);
        if (existing.isPresent()) {
            log.info("Wallet already exists for {}", email);
            return mapToResponse(existing.get());
        }

        Wallet wallet = new Wallet();
        wallet.setUserId(userId);
        wallet.setAccountNumber(email);
        wallet.setBalance(BigDecimal.ZERO);

        wallet = walletRepository.save(wallet);
        log.info("Created wallet for userId={} email={}", userId, email);
        return mapToResponse(wallet);
    }

    public WalletResponse freezeWallet(String userId) {
        Wallet wallet = walletRepository.findByUserId(userId)
                .orElseThrow(() -> new WalletNotFoundException("Wallet not found for userId: " + userId));
        wallet.setFrozen(true);
        walletRepository.save(wallet);
        log.info("Wallet frozen for userId={}", userId);
        return mapToResponse(wallet);
    }

    public WalletResponse unfreezeWallet(String userId) {
        Wallet wallet = walletRepository.findByUserId(userId)
                .orElseThrow(() -> new WalletNotFoundException("Wallet not found for userId: " + userId));
        wallet.setFrozen(false);
        walletRepository.save(wallet);
        log.info("Wallet unfrozen for userId={}", userId);
        return mapToResponse(wallet);
    }

    private WalletResponse mapToResponse(Wallet wallet) {
        WalletResponse response = new WalletResponse();
        response.setId(wallet.getId());
        response.setAccountNumber(wallet.getAccountNumber());
        response.setUserId(wallet.getUserId());
        response.setBalance(wallet.getBalance());
        response.setCurrency(wallet.getCurrency());
        response.setFrozen(wallet.isFrozen());
        return response;
    }
}