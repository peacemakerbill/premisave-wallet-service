package com.premisave.wallet.commercial.service;

import com.premisave.wallet.commercial.enums.Direction;
import com.premisave.wallet.commercial.exception.CommercialException;
import com.premisave.wallet.commercial.rail.RailCapability;
import com.premisave.wallet.commercial.security.CommercialContext;
import com.premisave.wallet.commercial.util.Money;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** What this merchant and app can use right now. */
@Service("commercialCapabilityService")
public class CapabilityService {

    private final RailRegistry rails;
    private final PricingService pricing;

    public CapabilityService(RailRegistry rails, PricingService pricing) {
        this.rails = rails;
        this.pricing = pricing;
    }

    public Map<String, Object> methods(CommercialContext ctx, String country, String currency, String direction) {
        Direction dir = null;
        if (direction != null && !direction.isBlank()) {
            try {
                dir = Direction.valueOf(direction.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw CommercialException.validation("direction", "must be COLLECTION or PAYOUT");
            }
        }
        if (country != null && !country.isBlank() && !country.trim().matches("^[A-Za-z]{2}$")) {
            throw CommercialException.validation("country", "must be a 2 letter country code");
        }
        String cur = currency == null || currency.isBlank() ? null : pricing.normaliseCurrency(currency);

        List<Map<String, Object>> methods = new ArrayList<>();
        for (RailRegistry.Entry e : rails.available(ctx.environment(), dir,
                country == null || country.isBlank() ? null : country.trim(), cur)) {
            RailCapability c = e.capability();
            if (!MethodPolicy.isEnabled(ctx.merchant(), ctx.app(), ctx.environment(), c.method())) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("type", c.method().name());
            m.put("direction", c.direction().name());
            m.put("providers", List.of(e.rail().providerName(c.method())));
            m.put("countries", c.countries());
            m.put("currencies", c.currencies());
            m.put("available", true);
            Map<String, Object> limits = new LinkedHashMap<>();
            limits.put("minUsd", Money.format(c.minUsdCents()));
            limits.put("maxUsd", Money.format(c.maxUsdCents()));
            m.put("limits", limits);
            m.put("settlement", c.settlement());
            m.put("requiresAction", c.requiresAction());
            methods.add(m);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("methods", methods);
        return out;
    }
}
