package com.premisave.wallet.commercial.billing;

import com.premisave.wallet.commercial.entity.CommercialTransaction;
import com.premisave.wallet.commercial.service.TransactionFinishListener;
import org.springframework.stereotype.Component;

/** Hands finished payments that belong to an invoice attempt to InvoiceSettlement. */
@Component("commercialInvoicePaymentListener")
public class InvoicePaymentListener implements TransactionFinishListener {

    private final InvoiceSettlement settlement;

    public InvoicePaymentListener(InvoiceSettlement settlement) {
        this.settlement = settlement;
    }

    @Override
    public void onFinished(CommercialTransaction tx) {
        settlement.onPaymentFinished(tx);
    }
}
