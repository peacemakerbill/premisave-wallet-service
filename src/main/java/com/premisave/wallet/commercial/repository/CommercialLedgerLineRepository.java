package com.premisave.wallet.commercial.repository;

import com.premisave.wallet.commercial.entity.LedgerLine;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.time.Instant;

public interface CommercialLedgerLineRepository extends MongoRepository<LedgerLine, String> {
    Page<LedgerLine> findByWalletIdAndCreatedAtBetweenOrderByCreatedAtDesc(
            String walletId, Instant from, Instant to, Pageable pageable);
}
