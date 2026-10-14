package com.premisave.wallet.commercial.repository;

import com.premisave.wallet.commercial.entity.Quote;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface QuoteRepository extends MongoRepository<Quote, String> {
}
