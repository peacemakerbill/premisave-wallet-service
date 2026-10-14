package com.premisave.wallet.commercial.rail;

import com.premisave.wallet.commercial.enums.MethodType;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

public record CollectCommand(String transactionId, String merchantId, boolean livemode, MethodType method,
                             long usdCents, BigDecimal nativeAmount, String nativeCurrency,
                             Map<String, Object> customer, Map<String, Object> methodOptions,
                             String description, Instant expiresAt) {
}
