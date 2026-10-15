package com.premisave.wallet.commercial.billing;

import org.bson.Document;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.BasicUpdate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * Reads and writes invoices. Every change after creation goes through mutate: read, apply a rule from
 * InvoiceRules, write back only if nobody else wrote in between (a check on version), and start over if
 * somebody did. That is what keeps the scheduler, the payment listener and a merchant's retry from
 * overwriting each other.
 */
@Component("commercialInvoiceStore")
public class InvoiceStore {

    private static final int MAX_TRIES = 6;

    private final BillingInvoiceRepository invoices;
    private final MongoTemplate mongo;

    public InvoiceStore(BillingInvoiceRepository invoices, MongoTemplate mongo) {
        this.invoices = invoices;
        this.mongo = mongo;
    }

    public record Inserted(Invoice invoice, boolean created) {}

    public Optional<Invoice> find(String id) {
        return invoices.findById(id);
    }

    /** Creates the invoice, or returns the one that already exists for this subscription and period. */
    public Inserted insertOrExisting(Invoice inv) {
        try {
            return new Inserted(invoices.insert(inv), true);
        } catch (DuplicateKeyException e) {
            Invoice existing = invoices.findBySubscriptionIdAndBillingPeriod(inv.getSubscriptionId(), inv.getBillingPeriod())
                    .orElseThrow(() -> e);
            return new Inserted(existing, false);
        }
    }

    /**
     * Applies change to the stored invoice. change returns true when it altered the invoice. The result is
     * the invoice as written, or empty when the invoice is missing or change had nothing to do. change may
     * run more than once, so it must work from the invoice it is given and nothing else.
     */
    public Optional<Invoice> mutate(String id, Predicate<Invoice> change) {
        for (int i = 0; i < MAX_TRIES; i++) {
            Invoice inv = invoices.findById(id).orElse(null);
            if (inv == null) return Optional.empty();
            long expected = inv.getVersion();
            if (!change.test(inv)) return Optional.empty();
            if (write(inv, expected)) {
                inv.setVersion(expected + 1);
                return Optional.of(inv);
            }
        }
        throw new IllegalStateException("Invoice " + id + " is being changed too often to update");
    }

    /** Unpaid invoices of a subscription that are still waiting to be collected. */
    public List<Invoice> openFor(String subscriptionId) {
        return mongo.find(Query.query(Criteria.where("subscriptionId").is(subscriptionId)
                .and("status").is(Invoice.Status.OPEN.name())), Invoice.class);
    }

    public int voidOpenFor(String subscriptionId) {
        int voided = 0;
        for (Invoice open : openFor(subscriptionId)) {
            if (mutate(open.getId(), InvoiceRules::voidInvoice).isPresent()) voided++;
        }
        return voided;
    }

    private boolean write(Invoice inv, long expectedVersion) {
        List<Document> attempts = new ArrayList<>();
        for (Invoice.Attempt a : inv.getAttempts()) {
            Document d = new Document();
            mongo.getConverter().write(a, d);
            d.remove("_class");
            attempts.add(d);
        }
        Document set = new Document("status", inv.getStatus().name())
                .append("attempts", attempts)
                .append("nextRetryAt", date(inv.getNextRetryAt()))
                .append("paymentId", inv.getPaymentId())
                .append("paidAt", date(inv.getPaidAt()))
                .append("feeCents", inv.getFeeCents())
                .append("updatedAt", Date.from(Instant.now()));
        Document update = new Document("$set", set).append("$inc", new Document("version", 1L));
        return mongo.updateFirst(Query.query(Criteria.where("id").is(inv.getId()).and("version").is(expectedVersion)),
                new BasicUpdate(update), Invoice.class).getModifiedCount() == 1;
    }

    private static Date date(Instant t) {
        return t == null ? null : Date.from(t);
    }
}
