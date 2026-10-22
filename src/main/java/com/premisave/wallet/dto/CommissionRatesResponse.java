package com.premisave.wallet.dto;

import java.math.BigDecimal;

/**
 * Body of GET /internal/commission/rates.
 *
 * internalTransferRate: rate on wallet-to-wallet transfers (decimal fraction, 0.01 means 1%).
 * gatewayRate: rate on gateway-bound disbursements (M-Pesa, PayPal, Stripe, Flutterwave, NOWPayments).
 * currency: wallets are USD, so commission amounts are USD too.
 * chargedOn: always ON_TOP, the sender pays amount plus commission and the recipient receives the full amount.
 * commissionScale, commissionRounding: commission is amount times rate, rounded to this many decimals with this
 * mode, once per transfer. Use the same rule to predict the commission of a transfer exactly.
 */
public record CommissionRatesResponse(
        BigDecimal internalTransferRate,
        BigDecimal gatewayRate,
        String currency,
        String chargedOn,
        int commissionScale,
        String commissionRounding) {
}