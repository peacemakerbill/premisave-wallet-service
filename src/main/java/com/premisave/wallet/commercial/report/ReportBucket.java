package com.premisave.wallet.commercial.report;

import com.premisave.wallet.commercial.enums.MethodType;
import com.premisave.wallet.commercial.enums.TxStatus;
import com.premisave.wallet.commercial.enums.TxType;

/**
 * Transactions of one kind that finished on one day: how many, and their
 * total amount and fee in USD cents. The database groups by these keys so a
 * report never has to read individual transactions.
 */
public record ReportBucket(String day, TxType type, TxStatus status, MethodType method, String appId,
                           long count, long amountCents, long feeCents) {
}
