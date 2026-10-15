package com.premisave.wallet.commercial.rail.mpesa.c2b;

import org.springframework.data.mongodb.repository.MongoRepository;

public interface C2bReceiptRepository extends MongoRepository<C2bReceipt, String> {
}
