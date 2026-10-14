package com.premisave.wallet.commercial.rail;

import com.premisave.wallet.commercial.enums.DestinationType;

import java.math.BigDecimal;
import java.util.Map;

public record PayoutCommand(String transactionId, String merchantId, boolean livemode, DestinationType destinationType,
                            long usdCents, BigDecimal nativeAmount, String nativeCurrency,
                            Map<String, Object> destination, String remarks) {
}
