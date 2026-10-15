package com.premisave.wallet.commercial.rail.mpesa.c2b;

import com.premisave.wallet.commercial.config.CommercialC2bProperties;
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

import java.time.Instant;
import java.util.List;

/**
 * Finishes paybill payments that were written down but not credited, for example because the service stopped
 * between the two steps or a price could not be had at the time. A payment that still fails after many tries
 * is moved to UNMATCHED so an operator sees it.
 */
@Component("commercialC2bReceiptSweeper")
@ConditionalOnProperty(name = {"commercial.c2b.enabled", "commercial.mpesa.enabled"}, havingValue = "true")
public class C2bReceiptSweeper {

    private static final Logger log = LoggerFactory.getLogger(C2bReceiptSweeper.class);

    private final MongoTemplate mongo;
    private final C2bReceiptRepository receipts;
    private final C2bService service;
    private final CommercialC2bProperties props;

    public C2bReceiptSweeper(MongoTemplate mongo, C2bReceiptRepository receipts, C2bService service, CommercialC2bProperties props) {
        this.mongo = mongo;
        this.receipts = receipts;
        this.service = service;
        this.props = props;
    }

    @Scheduled(fixedDelayString = "${commercial.c2b.sweep-interval-ms:30000}")
    public void run() {
        try {
            sweep(Instant.now());
        } catch (RuntimeException e) {
            log.error("Sweeping paybill receipts failed", e);
        }
    }

    int sweep(Instant now) {
        Criteria c = Criteria.where("status").is(C2bReceipt.Status.RECEIVED.name())
                .and("receivedAt").lt(now.minusSeconds(props.reprocessAfterSeconds()));
        List<C2bReceipt> due = mongo.find(Query.query(c).with(PageRequest.of(0, 50, Sort.by(Sort.Direction.ASC, "receivedAt"))), C2bReceipt.class);
        int done = 0;
        for (C2bReceipt r : due) {
            try {
                if (r.getAttempts() >= C2bService.MAX_ATTEMPTS) {
                    r.setStatus(C2bReceipt.Status.UNMATCHED);
                    r.setReason("PROCESSING_FAILED");
                    r.setProcessedAt(now);
                    receipts.save(r);
                    log.error("Paybill payment {} could not be credited after {} tries and needs an operator", r.getTransId(), r.getAttempts());
                    continue;
                }
                service.process(r);
                done++;
            } catch (RuntimeException e) {
                log.warn("Paybill payment {} is still not credited: {}", r.getTransId(), e.getMessage());
                try {
                    receipts.save(r);
                } catch (RuntimeException ignored) {
                    // The attempt count is lost, nothing else.
                }
            }
        }
        return done;
    }
}
