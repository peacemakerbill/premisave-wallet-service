package com.premisave.wallet.commercial.rail;

import com.premisave.wallet.commercial.enums.Direction;
import com.premisave.wallet.commercial.enums.MethodType;

import java.util.List;

/**
 * One thing a rail can do. countries and currencies may contain "*" for
 * "any". Limits are in USD cents and reflect the provider's own bounds.
 */
public record RailCapability(MethodType method, Direction direction, List<String> countries, List<String> currencies,
                             long minUsdCents, long maxUsdCents, String settlement, String requiresAction) {

    public boolean covers(Direction d, MethodType m, String country, String currency) {
        if (direction != d || method != m) return false;
        if (country != null && !countries.contains("*") && !countries.contains(country.toUpperCase())) return false;
        return currency == null || currencies.contains("*") || currencies.contains(currency.toUpperCase());
    }
}
