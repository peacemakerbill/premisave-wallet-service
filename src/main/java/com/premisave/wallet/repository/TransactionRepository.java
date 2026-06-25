package com.premisave.wallet.repository;

import com.premisave.wallet.entity.Transaction;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;

public interface TransactionRepository extends MongoRepository<Transaction, String> {
    List<Transaction> findByUserIdOrderByCreatedAtDesc(String userId);
    List<Transaction> findByWalletIdOrderByCreatedAtDesc(String walletId);
}