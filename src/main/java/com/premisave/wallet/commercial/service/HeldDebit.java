package com.premisave.wallet.commercial.service;

import com.premisave.wallet.commercial.entity.CommercialTransaction;
import com.premisave.wallet.commercial.entity.MerchantHold;
import com.premisave.wallet.commercial.entity.MerchantWallet;
import com.premisave.wallet.commercial.enums.ErrorCode;
import com.premisave.wallet.commercial.enums.ResultCode;
import com.premisave.wallet.commercial.enums.TxStatus;
import com.premisave.wallet.commercial.exception.CommercialException;
import com.premisave.wallet.commercial.rail.PaymentRail;
import com.premisave.wallet.commercial.rail.PayoutCommand;
import com.premisave.wallet.commercial.rail.RailException;
import com.premisave.wallet.commercial.rail.RailOutcome;
import com.premisave.wallet.commercial.repository.MerchantHoldRepository;
import com.premisave.wallet.commercial.util.Ids;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * The shared first half of every transaction that sends money out of a merchant
 * balance: reserve amount plus fee, record the transaction with its hold, and
 * hand it to a rail. Settlements, transfers and bulk items use this so the
 * hold-then-capture-or-release rule is written once. Payouts keep their own
 * copy of the same steps in CommercialPayoutService.
 */
@Component("commercialHeldDebit")
public class HeldDebit {

    private final LedgerService ledger;
    private final MerchantHoldRepository holds;
    private final CommercialTransactionStore store;
    private final TransactionOutcomeService outcomes;

    public HeldDebit(LedgerService ledger, MerchantHoldRepository holds, CommercialTransactionStore store,
                     TransactionOutcomeService outcomes) {
        this.ledger = ledger;
        this.holds = holds;
        this.store = store;
        this.outcomes = outcomes;
    }

    /**
     * Reserves settleCents on the wallet (failing with INSUFFICIENT_BALANCE when it is not there),
     * then stores the transaction and its hold. If recording fails the money is given back.
     * The transaction needs its id and amounts set; this sets the hold id.
     */
    public CommercialTransaction open(String walletId, CommercialTransaction tx, String description) {
        String holdId = Ids.next("hold");
        MerchantWallet wallet = ledger.hold(walletId, tx.getId(), tx.getSettleCents(), description).wallet();
        tx.setHoldId(holdId);
        boolean inserted = false;
        try {
            store.insert(tx);
            inserted = true;
            holds.insert(new MerchantHold(holdId, wallet, tx.getId(), tx.getSettleCents(), tx.getType().name(), Instant.now()));
        } catch (RuntimeException e) {
            if (inserted) {
                // The transaction exists, so finish it as failed and let the normal path release the money.
                outcomes.apply(tx, RailOutcome.of(TxStatus.FAILED, ResultCode.PROVIDER_ERROR,
                        "The " + tx.getType().label().toLowerCase() + " could not be recorded"));
            } else {
                ledger.release(walletId, tx.getId(), tx.getSettleCents(),
                        tx.getType().label() + " " + tx.getMerchantReference() + " could not be recorded");
            }
            throw e;
        }
        return tx;
    }

    /**
     * Sends the transaction to a rail. A rail that refuses fails the transaction (and releases the
     * money); a rail that is simply unreachable does the same and also reports 503 to the caller.
     */
    public void dispatch(PaymentRail rail, PayoutCommand command, CommercialTransaction tx) {
        send(() -> rail.payout(command), tx);
    }

    /** Same as dispatch for any rail call that answers with a RailResponse, for example a provider refund. */
    public void send(java.util.function.Supplier<com.premisave.wallet.commercial.rail.RailResponse> call, CommercialTransaction tx) {
        try {
            outcomes.applyRailResponse(tx, call.get());
        } catch (RailException e) {
            outcomes.apply(tx, RailOutcome.of(TxStatus.FAILED,
                    e.code() != null ? e.code() : ResultCode.PROVIDER_ERROR, e.getMessage()));
            if (e.kind() == RailException.Kind.UNAVAILABLE) {
                throw new CommercialException(ErrorCode.PROVIDER_UNAVAILABLE,
                        "The provider is unavailable; nothing was sent");
            }
        }
    }
}
