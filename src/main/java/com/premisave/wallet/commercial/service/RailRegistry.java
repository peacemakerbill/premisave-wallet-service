package com.premisave.wallet.commercial.service;

import com.premisave.wallet.commercial.enums.Direction;
import com.premisave.wallet.commercial.enums.Environment;
import com.premisave.wallet.commercial.enums.ErrorCode;
import com.premisave.wallet.commercial.enums.MethodType;
import com.premisave.wallet.commercial.enums.RailId;
import com.premisave.wallet.commercial.exception.CommercialException;
import com.premisave.wallet.commercial.rail.PaymentRail;
import com.premisave.wallet.commercial.rail.RailCapability;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Chooses the rail for a request. Today the first matching rail wins; health
 * based failover between rails is a later addition and slots in here.
 */
@Service("commercialRailRegistry")
public class RailRegistry {

    private final List<PaymentRail> rails;

    public RailRegistry(List<PaymentRail> rails) {
        this.rails = rails;
    }

    /** A rail able to carry the request, honouring a named provider unless it is AUTO. */
    public PaymentRail select(Environment env, Direction direction, MethodType method, String provider,
                              String country, String currency) {
        for (PaymentRail rail : rails) {
            if (!rail.environments().contains(env)) continue;
            if (!providerAccepts(rail, method, provider)) continue;
            for (RailCapability cap : rail.capabilities()) {
                if (cap.covers(direction, method, country, currency)) return rail;
            }
        }
        throw new CommercialException(ErrorCode.METHOD_UNAVAILABLE,
                "No rail is available for " + method + " " + direction.name().toLowerCase(Locale.ROOT)
                        + (provider == null || provider.equalsIgnoreCase("AUTO") ? "" : " via " + provider)
                        + " in this environment");
    }

    public PaymentRail byId(RailId id) {
        return rails.stream().filter(r -> r.id() == id).findFirst()
                .orElseThrow(() -> new CommercialException(ErrorCode.METHOD_UNAVAILABLE, "Rail " + id + " is not available"));
    }

    /** What is available in an environment, filtered and flattened for the capabilities endpoint. */
    public List<Entry> available(Environment env, Direction direction, String country, String currency) {
        List<Entry> out = new ArrayList<>();
        for (PaymentRail rail : rails) {
            if (!rail.environments().contains(env)) continue;
            for (RailCapability cap : rail.capabilities()) {
                if (direction != null && cap.direction() != direction) continue;
                if (country != null && !cap.countries().contains("*") && !cap.countries().contains(country.toUpperCase(Locale.ROOT))) continue;
                if (currency != null && !cap.currencies().contains("*") && !cap.currencies().contains(currency.toUpperCase(Locale.ROOT))) continue;
                out.add(new Entry(rail, cap));
            }
        }
        return out;
    }

    public record Entry(PaymentRail rail, RailCapability capability) {}

    private static boolean providerAccepts(PaymentRail rail, MethodType method, String provider) {
        if (provider == null || provider.isBlank() || provider.equalsIgnoreCase("AUTO")) return true;
        return rail.providerName(method).equalsIgnoreCase(provider) || rail.id().name().equalsIgnoreCase(provider);
    }
}
