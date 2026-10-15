package com.premisave.wallet.commercial.refund;

import com.premisave.wallet.commercial.entity.CommercialTransaction;
import com.premisave.wallet.commercial.enums.TxStatus;
import com.premisave.wallet.commercial.enums.TxType;
import com.premisave.wallet.commercial.service.TransactionFinishListener;
import org.springframework.stereotype.Component;

/**
 * When a refund ends without paying the customer (failed, timed out, cancelled or reversed by the
 * provider) the amount becomes refundable again. Kept apart from RefundService because the outcome
 * service needs the listener and RefundService needs the outcome service.
 */
@Component("commercialRefundReservationListener")
public class RefundReservationListener implements TransactionFinishListener {

    private final RefundReservations reservations;

    public RefundReservationListener(RefundReservations reservations) {
        this.reservations = reservations;
    }

    @Override
    public void onFinished(CommercialTransaction tx) {
        if (tx.getType() != TxType.REFUND || tx.getStatus() == TxStatus.SUCCESS) return;
        reservations.releaseFor(tx);
    }
}
