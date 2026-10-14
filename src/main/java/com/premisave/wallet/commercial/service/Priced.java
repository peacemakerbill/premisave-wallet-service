package com.premisave.wallet.commercial.service;

import java.math.BigDecimal;

/** Result of pricing one amount: the USD figure, the merchant's own figure and rate, the fee and what moves. */
public record Priced(long usdCents, String currency, BigDecimal nativeAmount, String nativeCurrency,
                              BigDecimal fxRate, long feeCents, long settleCents) {
}
