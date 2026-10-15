package com.premisave.wallet.commercial.rail.nowpayments;

import com.premisave.wallet.commercial.enums.ResultCode;
import com.premisave.wallet.commercial.enums.TxStatus;
import com.premisave.wallet.commercial.rail.RailOutcome;

import java.util.Locale;

/** Pure helpers for the NOWPayments rail: the provider's payment status words. */
public final class NowPaymentsMapper {

    private NowPaymentsMapper() {}

    /**
     * A payment's verdict. waiting, confirming, confirmed and sending are all still in progress; finished is paid.
     * partially_paid is deliberately left open: part of the money arrived, which needs a person to decide, so
     * the payment expires unpaid rather than being credited in full.
     */
    public static RailOutcome payment(String status, String paymentId) {
        if (status == null) return null;
        switch (status.toLowerCase(Locale.ROOT)) {
            case "finished":
                return new RailOutcome(TxStatus.SUCCESS, ResultCode.SUCCESS, "Paid in crypto", null, null, paymentId);
            case "failed":
                return new RailOutcome(TxStatus.FAILED, ResultCode.DECLINED, "The crypto payment failed", null, null, paymentId);
            case "expired":
                return new RailOutcome(TxStatus.TIMED_OUT, ResultCode.CUSTOMER_TIMEOUT, "The crypto payment expired before it was paid",
                        null, null, paymentId);
            case "refunded":
                return new RailOutcome(TxStatus.FAILED, ResultCode.REVERSED, "The crypto payment was refunded", null, null, paymentId);
            default:
                return null;
        }
    }

    public static boolean partiallyPaid(String status) {
        return status != null && status.equalsIgnoreCase("partially_paid");
    }
}
