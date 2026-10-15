package com.premisave.wallet.commercial.rail;

import com.premisave.wallet.commercial.entity.CommercialTransaction;
import com.premisave.wallet.commercial.enums.Environment;
import com.premisave.wallet.commercial.enums.MethodType;
import com.premisave.wallet.commercial.enums.RailId;

import java.util.List;
import java.util.Set;

/**
 * The only seam between the commercial API and a payment provider. Adapters
 * wrap the existing provider services and keep every provider specific detail
 * (request shapes, codes, webhooks) on their side. Results that arrive later
 * go to the RailResultSink.
 */
public interface PaymentRail {

    RailId id();

    /** Environments this rail serves. A rail never serves both a test and a live provider account. */
    Set<Environment> environments();

    List<RailCapability> capabilities();

    /** Starts taking money from a customer. */
    RailResponse collect(CollectCommand command) throws RailException;

    /** Starts sending money to a destination. */
    RailResponse payout(PayoutCommand command) throws RailException;

    /** Withdraws an unpaid collection. Returns false when the provider cannot recall it. */
    default boolean cancelCollection(CommercialTransaction tx) {
        return false;
    }

    /** What this rail can do to return the money of a payment it collected. */
    default RailRefundMode refundMode(CommercialTransaction payment) {
        return RailRefundMode.NONE;
    }

    /** Asks the provider to refund or reverse a payment. Only called when refundMode is not NONE. */
    default RailResponse refund(RefundCommand command) throws RailException {
        throw new RailException(RailException.Kind.UNAVAILABLE, null, id() + " cannot refund payments");
    }

    /** Sends dispute evidence to the provider. */
    default void submitDisputeEvidence(DisputeEvidenceCommand command) throws RailException {
        throw new RailException(RailException.Kind.UNAVAILABLE, null, id() + " does not accept dispute evidence");
    }

    /** True when this rail's later results are found by asking the provider; RailPoller then calls poll. */
    default boolean pollable() {
        return false;
    }

    /**
     * Asks the provider what became of an open transaction. Returns the outcome once the provider has a final
     * answer, or null while it is still in progress or cannot be reached. Must be safe to call repeatedly.
     */
    default RailOutcome poll(CommercialTransaction tx) {
        return null;
    }

    /** The provider name shown to merchants for a method on this rail. */
    default String providerName(MethodType method) {
        return id().name();
    }
}
