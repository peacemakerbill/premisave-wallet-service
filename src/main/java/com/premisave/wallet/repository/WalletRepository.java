package com.premisave.wallet.repository;

import com.premisave.wallet.entity.Wallet;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.Optional;

public interface WalletRepository extends MongoRepository<Wallet, String> {
    Optional<Wallet> findByAccountNumber(String accountNumber);
    Optional<Wallet> findByUserId(String userId);
    Optional<Wallet> findByStripeCustomerId(String stripeCustomerId);

    /**
     * Resolves which wallet a VAULT.PAYMENT-TOKEN.CREATED webhook belongs
     * to, when PayPal finalizes vaulting asynchronously after a capture
     * that returned vault.status="APPROVED" rather than "VAULTED" — see
     * DepositService.attachPaypalVaultToken.
     */
    Optional<Wallet> findByPaypalCustomerId(String paypalCustomerId);
}