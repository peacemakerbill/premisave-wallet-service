package com.premisave.wallet.commercial.service;

import com.premisave.wallet.commercial.entity.CommercialTransaction;

/**
 * Something that needs to know when a transaction reaches a final status, after its money
 * and event steps are done. Payment links use it to count successful payments, and later
 * features (invoices, refunds) hook in the same way. A listener must be safe to call more
 * than once for the same transaction; its failure is logged and never undoes the transaction.
 */
public interface TransactionFinishListener {
    void onFinished(CommercialTransaction tx);
}
