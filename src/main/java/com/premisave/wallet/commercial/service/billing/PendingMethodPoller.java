package com.premisave.wallet.commercial.service.billing;

import com.premisave.wallet.commercial.entity.billing.CustomerPaymentMethod;
import com.premisave.wallet.commercial.enums.Environment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Finishes live payment methods that are waiting for the customer (a card set up, a PayPal approval). Webhooks
 * finish most of them at once; this asks the provider about the rest, so a missed webhook does not leave a
 * customer's method pending for ever. A setup that has waited longer than its provider allows is failed.
 */
@Component("commercialPendingMethodPoller")
@ConditionalOnProperty(prefix = "commercial.rails.polling", name = "enabled", havingValue = "true", matchIfMissing = true)
public class PendingMethodPoller {

    private static final Logger log = LoggerFactory.getLogger(PendingMethodPoller.class);
    private static final int BATCH = 50;

    private final MongoTemplate mongo;
    private final List<CredentialSetup> setups;
    private final PaymentMethodService methods;

    public PendingMethodPoller(MongoTemplate mongo, List<CredentialSetup> setups, PaymentMethodService methods) {
        this.mongo = mongo;
        this.setups = setups;
        this.methods = methods;
    }

    @Scheduled(fixedDelayString = "${commercial.rails.polling.interval-ms:20000}")
    public void run() {
        try {
            sweep();
        } catch (RuntimeException e) {
            log.error("Checking pending payment methods failed", e);
        }
    }

    int sweep() {
        Criteria c = Criteria.where("livemode").is(true)
                .and("status").is(CustomerPaymentMethod.Status.PENDING_AUTHORISATION.name());
        Query q = Query.query(c).with(PageRequest.of(0, BATCH, Sort.by(Sort.Direction.ASC, "createdAt")));
        int moved = 0;
        for (CustomerPaymentMethod pm : mongo.find(q, CustomerPaymentMethod.class)) {
            CredentialSetup setup = setups.stream().filter(s -> s.supports(Environment.LIVE, pm.getType())).findFirst().orElse(null);
            if (setup == null) continue;
            try {
                CredentialSetup.Progress p = setup.poll(pm);
                switch (p.kind()) {
                    case DONE -> { methods.activate(pm.getPaymentMethodId(), p.label(), p.token()); moved++; }
                    case FAILED -> { methods.fail(pm.getPaymentMethodId(), p.reason()); moved++; }
                    default -> { }
                }
            } catch (RuntimeException e) {
                log.warn("Payment method {} could not be checked: {}", pm.getPaymentMethodId(), e.getMessage());
            }
        }
        return moved;
    }
}
