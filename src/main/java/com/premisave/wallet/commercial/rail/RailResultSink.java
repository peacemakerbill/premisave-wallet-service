package com.premisave.wallet.commercial.rail;

/**
 * Where rails report results that arrive after the original call: provider
 * callbacks, status polls and sandbox simulations. Applying a result twice
 * is harmless; the first one wins.
 */
public interface RailResultSink {
    void onOutcome(String transactionId, RailOutcome outcome);
}
