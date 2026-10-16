package com.premisave.wallet.commercial.repository;

import com.premisave.wallet.commercial.entity.AppSnapshot;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface AppSnapshotRepository extends MongoRepository<AppSnapshot, String> {
}
