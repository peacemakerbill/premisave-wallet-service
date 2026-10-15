package com.premisave.wallet.commercial.rail;

import com.premisave.wallet.commercial.entity.CommercialTransaction;
import com.premisave.wallet.commercial.enums.TxStatus;
import com.premisave.wallet.commercial.enums.TxType;
import com.premisave.wallet.commercial.service.RailRegistry;
import com.premisave.wallet.commercial.service.TransactionFinishListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

/**
 * When a payment expires before the customer finished it, the provider is asked to withdraw its side too, so a
 * customer who completes the payment afterwards is not charged for something already closed here. Best effort:
 * a rail that cannot recall a payment simply says so, and the call is skipped.
 */
@Component("commercialRecallTimedOutCollections")
public class RecallTimedOutCollections implements TransactionFinishListener {

    private static final Logger log = LoggerFactory.getLogger(RecallTimedOutCollections.class);

    // Lazy because rails report back to the outcome service, which in turn holds the finish listeners.
    private final RailRegistry rails;

    public RecallTimedOutCollections(@Lazy RailRegistry rails) {
        this.rails = rails;
    }

    @Override
    public void onFinished(CommercialTransaction tx) {
        if (tx.getType() != TxType.PAYMENT || tx.getStatus() != TxStatus.TIMED_OUT || tx.getRail() == null) return;
        try {
            if (rails.byId(tx.getRail()).cancelCollection(tx)) {
                log.info("Withdrew expired payment {} from {}", tx.getId(), tx.getRail());
            }
        } catch (RuntimeException e) {
            log.warn("Could not withdraw expired payment {} from {}: {}", tx.getId(), tx.getRail(), e.getMessage());
        }
    }
}
