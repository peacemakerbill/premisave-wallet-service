package com.premisave.wallet.commercial.refund;

import com.premisave.wallet.commercial.entity.CommercialTransaction;
import com.premisave.wallet.commercial.enums.TxStatus;
import com.premisave.wallet.commercial.enums.TxType;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.BasicUpdate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;

/**
 * Keeps the refundable balance of a payment honest when refunds race.
 *
 * Each refund first reserves its amount on the payment with one guarded update ("add this much, but
 * only if the total stays within the payment"), so two refunds can never both take the last of it.
 * A refund that fails gives its reservation back, and the flag on the refund document makes sure that
 * happens once. If the process stops between flipping the flag and returning the amount, the payment
 * is left refundable for slightly less than it should be, never for more; repair() puts that right
 * from the refunds themselves once the payment has been quiet for a few minutes.
 */
@Component("commercialRefundReservations")
public class RefundReservations {

    static final Duration QUIET_BEFORE_REPAIR = Duration.ofMinutes(5);
    private static final List<String> HOLDING = List.of(
            TxStatus.REQUIRES_ACTION.name(), TxStatus.PENDING.name(), TxStatus.SUCCESS.name());

    private final MongoTemplate mongo;

    public RefundReservations(MongoTemplate mongo) {
        this.mongo = mongo;
    }

    /** Takes cents from the payment's refundable balance. False when that much is not left. */
    public boolean reserve(String paymentId, long cents, long paymentCents) {
        long room = paymentCents - cents;
        if (room < 0) return false;
        Criteria c = Criteria.where("id").is(paymentId).and("type").is(TxType.PAYMENT.name())
                .and("status").is(TxStatus.SUCCESS.name())
                .orOperator(Criteria.where("refundedCents").lte(room), Criteria.where("refundedCents").exists(false));
        Document update = new Document("$inc", new Document("refundedCents", cents))
                .append("$set", new Document("refundTouchedAt", Date.from(Instant.now())));
        return mongo.updateFirst(Query.query(c), new BasicUpdate(update), CommercialTransaction.class).getModifiedCount() == 1;
    }

    /** Gives back a reservation for a refund that was never recorded. */
    public void releaseUnrecorded(String paymentId, long cents) {
        Document update = new Document("$inc", new Document("refundedCents", -cents))
                .append("$set", new Document("refundTouchedAt", Date.from(Instant.now())));
        mongo.updateFirst(Query.query(Criteria.where("id").is(paymentId).and("refundedCents").gte(cents)),
                new BasicUpdate(update), CommercialTransaction.class);
    }

    /** Gives back the reservation of a refund that did not succeed. Safe to call any number of times. */
    public void releaseFor(CommercialTransaction refund) {
        String paymentId = refund.getRelations().get("paymentId");
        if (paymentId == null) return;
        // Only the call that flips the flag returns the amount.
        long flipped = mongo.updateFirst(
                Query.query(Criteria.where("id").is(refund.getId()).and("relations.reserved").is("true")),
                new BasicUpdate(new Document("$set", new Document("relations.reserved", "false"))),
                CommercialTransaction.class).getModifiedCount();
        if (flipped == 1) releaseUnrecorded(paymentId, refund.getAmountCents());
    }

    /**
     * Recomputes the reserved total from the refunds themselves. Only done when the payment has seen no
     * reservation for a while, so a refund that has reserved but not yet been recorded is never missed.
     * Returns true when the figure changed.
     */
    public boolean repair(String paymentId, Instant now) {
        CommercialTransaction payment = mongo.findById(paymentId, CommercialTransaction.class);
        if (payment == null || payment.getType() != TxType.PAYMENT) return false;
        Instant touched = payment.getRefundTouchedAt();
        if (touched != null && touched.isAfter(now.minus(QUIET_BEFORE_REPAIR))) return false;

        long observed = payment.getRefundedCents();
        long actual = 0;
        for (CommercialTransaction r : mongo.find(Query.query(Criteria.where("type").is(TxType.REFUND.name())
                .and("relations.paymentId").is(paymentId).and("status").in(HOLDING)), CommercialTransaction.class)) {
            actual += r.getAmountCents();
        }
        if (actual == observed) return false;

        Criteria guard = Criteria.where("id").is(paymentId);
        guard = observed == 0
                ? guard.orOperator(Criteria.where("refundedCents").is(0L), Criteria.where("refundedCents").exists(false))
                : guard.and("refundedCents").is(observed);
        return mongo.updateFirst(Query.query(guard),
                new BasicUpdate(new Document("$set", new Document("refundedCents", actual))),
                CommercialTransaction.class).getModifiedCount() == 1;
    }
}
