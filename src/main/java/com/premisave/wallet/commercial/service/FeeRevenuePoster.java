package com.premisave.wallet.commercial.service;

import com.premisave.wallet.commercial.entity.CommercialTransaction;
import com.premisave.wallet.commercial.enums.TxType;
import com.premisave.wallet.commercial.util.Money;
import com.premisave.wallet.entity.CompanyLedgerEntry;
import com.premisave.wallet.enums.Currency;
import com.premisave.wallet.repository.CompanyLedgerRepository;
import org.springframework.stereotype.Component;

/**
 * Posts merchant fees to the company ledger so they appear next to the
 * existing commission entries in finance reports. Live transactions only:
 * sandbox fees are not revenue. Posting twice is harmless because an entry
 * for the same source is looked up first.
 */
@Component
public class FeeRevenuePoster {

    static final String SOURCE_TYPE = "COMMERCIAL_TRANSACTION";

    private final CompanyLedgerRepository ledger;

    public FeeRevenuePoster(CompanyLedgerRepository ledger) {
        this.ledger = ledger;
    }

    public void post(CommercialTransaction tx) {
        if (!tx.isLivemode() || tx.getFeeCents() <= 0) return;
        if (!ledger.findBySourceTypeAndSourceId(SOURCE_TYPE, tx.getId()).isEmpty()) return;

        CompanyLedgerEntry entry = new CompanyLedgerEntry();
        entry.setAmount(Money.fromCents(tx.getFeeCents()));
        entry.setCurrency(Currency.USD);
        entry.setType(tx.getType() == TxType.PAYOUT ? "MERCHANT_FEE_PAYOUT" : "MERCHANT_FEE_COLLECTION");
        entry.setDescription("Fee on " + tx.getType().name().toLowerCase() + " " + tx.getId()
                + " for merchant " + tx.getMerchantId());
        entry.setSourceType(SOURCE_TYPE);
        entry.setSourceId(tx.getId());
        entry.setSourceReference(tx.getMerchantReference());
        entry.setGrossAmount(Money.fromCents(tx.getAmountCents()));
        entry.setUserId(tx.getMerchantId());
        ledger.save(entry);
    }
}
