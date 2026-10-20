package com.premisave.wallet.commercial.repository.billing;

import com.premisave.wallet.commercial.entity.billing.Invoice;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.Optional;

public interface BillingInvoiceRepository extends MongoRepository<Invoice, String> {
    Optional<Invoice> findByIdAndMerchantIdAndLivemode(String id, String merchantId, boolean livemode);

    Optional<Invoice> findBySubscriptionIdAndBillingPeriod(String subscriptionId, String billingPeriod);
}
