package com.premisave.wallet.commercial.service;

import java.math.BigDecimal;

/** Source of exchange rates for the commercial API. */
public interface FxConverter {

    /** How many units of {@code currency} equal one US dollar. Throws IllegalArgumentException for a currency that cannot be priced. */
    BigDecimal nativePerUsd(String currency);
}
