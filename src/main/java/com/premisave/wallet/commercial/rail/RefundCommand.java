package com.premisave.wallet.commercial.rail;

import java.math.BigDecimal;

/** A refund the provider performs itself against the original payment. */
public record RefundCommand(String transactionId, String merchantId, boolean livemode, String paymentId,
                            String paymentProviderReference, long usdCents, BigDecimal nativeAmount,
                            String nativeCurrency, String reason) {
}
