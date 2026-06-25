package com.premisave.wallet.repository;

import com.premisave.wallet.entity.Transaction;
import com.premisave.wallet.enums.TransactionStatus;
import com.premisave.wallet.enums.TransactionType;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;
import java.util.Optional;

public interface TransactionRepository extends MongoRepository<Transaction, String> {
    List<Transaction> findByUserIdOrderByCreatedAtDesc(String userId);
    List<Transaction> findByWalletIdOrderByCreatedAtDesc(String walletId);
    Optional<Transaction> findByReference(String reference);
    List<Transaction> findByUserIdAndTypeOrderByCreatedAtDesc(String userId, TransactionType type);
    List<Transaction> findByUserIdAndStatusOrderByCreatedAtDesc(String userId, TransactionStatus status);
    boolean existsByReference(String reference);
}