package com.premisave.wallet.repository;

import com.premisave.wallet.entity.Disbursement;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;

public interface DisbursementRepository extends MongoRepository<Disbursement, String> {
    List<Disbursement> findByUserIdOrderByCreatedAtDesc(String userId);
}