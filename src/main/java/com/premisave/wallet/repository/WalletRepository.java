package com.premisave.wallet.repository;

import com.premisave.wallet.entity.Wallet;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.Optional;

public interface WalletRepository extends MongoRepository<Wallet, String> {
    Optional<Wallet> findByAccountNumber(String accountNumber);
    Optional<Wallet> findByUserId(String userId);
}