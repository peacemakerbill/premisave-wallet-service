package com.premisave.wallet.service;

import com.premisave.wallet.dto.WalletResponse;
import com.premisave.wallet.entity.Wallet;
import com.premisave.wallet.repository.WalletRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class WalletService {

    private final WalletRepository walletRepository;

    public WalletResponse getWallet(String accountNumber) {
        Wallet wallet = walletRepository.findByAccountNumber(accountNumber)
                .orElseThrow(() -> new RuntimeException("Wallet not found"));
        return mapToResponse(wallet);
    }

    public WalletResponse createWallet(String userId, String email) {
        Optional<Wallet> existing = walletRepository.findByAccountNumber(email);
        if (existing.isPresent()) {
            return mapToResponse(existing.get());
        }

        Wallet wallet = new Wallet();
        wallet.setUserId(userId);
        wallet.setAccountNumber(email);
        wallet.setBalance(BigDecimal.ZERO);

        wallet = walletRepository.save(wallet);
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