package com.premisave.wallet.commercial.rail.mpesa;

import com.premisave.wallet.commercial.enums.ResultCode;
import com.premisave.wallet.commercial.enums.TxStatus;
import com.premisave.wallet.commercial.rail.RailOutcome;

/**
 * Turns Safaricom result codes into the provider neutral outcomes the rest of
 * the commercial API understands. Codes that are not listed here are reported
 * as PROVIDER_ERROR with Safaricom's own code and text attached, so nothing is
 * guessed about a code whose meaning is not certain.
 */
public final class MpesaResultMapper {

    private MpesaResultMapper() {}

    /**
     * The answer to an STK push, from the callback or from a status query.
     * receipt is the M-Pesa receipt number on success and may be null otherwise.
     */
    public static RailOutcome stk(String resultCode, String resultDesc, String receipt) {
        String code = resultCode == null ? "" : resultCode.trim();
        String desc = resultDesc == null || resultDesc.isBlank() ? "No description from M-Pesa" : resultDesc;
        return switch (code) {
            case "0" -> new RailOutcome(TxStatus.SUCCESS, ResultCode.SUCCESS, "Payment completed", code, desc, receipt);
            case "1032" -> failed(ResultCode.CUSTOMER_CANCELLED, "The customer cancelled the payment prompt", code, desc);
            case "1037" -> new RailOutcome(TxStatus.TIMED_OUT, ResultCode.CUSTOMER_TIMEOUT,
                    "The customer did not respond to the payment prompt", code, desc, null);
            case "1" -> failed(ResultCode.INSUFFICIENT_FUNDS, "The customer has insufficient funds", code, desc);
            case "2001" -> failed(ResultCode.DECLINED, "The customer entered a wrong PIN", code, desc);
            default -> failed(ResultCode.PROVIDER_ERROR, "M-Pesa could not complete the payment", code, desc);
        };
    }

    /** The final result of a B2C payout. transactionId is the M-Pesa receipt on success. */
    public static RailOutcome b2c(String resultCode, String resultDesc, String transactionId) {
        String code = resultCode == null ? "" : resultCode.trim();
        String desc = resultDesc == null || resultDesc.isBlank() ? "No description from M-Pesa" : resultDesc;
        if (code.equals("0")) {
            return new RailOutcome(TxStatus.SUCCESS, ResultCode.SUCCESS, "Payout completed", code, desc, transactionId);
        }
        // The funds that pay a payout come from our own M-Pesa float, so a shortage there is
        // reported as a provider problem, never as the merchant's or the recipient's.
        String message = code.equals("1")
                ? "The payout could not be sent because the provider account has insufficient funds"
                : "M-Pesa could not complete the payout";
        return failed(ResultCode.PROVIDER_ERROR, message, code, desc);
    }

    /** The queue timeout notice for a B2C request: Safaricom says the request was not processed. */
    public static RailOutcome b2cQueueTimeout() {
        return new RailOutcome(TxStatus.TIMED_OUT, ResultCode.TIMED_OUT,
                "M-Pesa could not process the payout in time and did not send it", null, null, null);
    }

    private static RailOutcome failed(ResultCode code, String message, String providerCode, String providerDesc) {
        return new RailOutcome(TxStatus.FAILED, code, message, providerCode, providerDesc, null);
    }
}
