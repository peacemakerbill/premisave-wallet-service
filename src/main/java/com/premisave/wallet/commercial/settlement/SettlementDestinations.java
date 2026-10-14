package com.premisave.wallet.commercial.settlement;

import com.premisave.wallet.commercial.entity.SettlementDestination;
import com.premisave.wallet.commercial.enums.DestinationType;
import com.premisave.wallet.commercial.enums.MethodType;
import com.premisave.wallet.commercial.exception.CommercialException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Turns a merchant's registered settlement destination into the destination
 * shape payouts use, so a settlement travels over exactly the same rails and
 * validation as a payout. The destination itself can only be changed by an
 * operator; a settlement request names one by id and never supplies details.
 */
public final class SettlementDestinations {

    private SettlementDestinations() {}

    public static DestinationType destinationType(MethodType method) {
        return switch (method) {
            case MOBILE_MONEY -> DestinationType.MOBILE_MONEY;
            case BANK_TRANSFER -> DestinationType.BANK_ACCOUNT;
            case PAYPAL -> DestinationType.PAYPAL;
            case PREMISAVE_WALLET -> DestinationType.PREMISAVE_WALLET;
            case CARD, CRYPTO -> throw CommercialException.validation("destinationId",
                    method.name() + " cannot be used as a settlement destination");
        };
    }

    /** The raw destination map that RequestValidators.destination checks and cleans. */
    public static Map<String, Object> rawDestination(SettlementDestination d) {
        Map<String, String> details = d.getDetails() == null ? Map.of() : d.getDetails();
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("type", destinationType(d.getType()).name());
        if (d.getCountry() != null) raw.put("country", d.getCountry());
        switch (d.getType()) {
            case MOBILE_MONEY -> raw.put("phoneNumber", details.get("phoneNumber"));
            case BANK_TRANSFER -> {
                raw.put("bankCode", details.get("bankCode"));
                raw.put("accountNumber", details.get("accountNumber"));
            }
            case PAYPAL -> raw.put("email", details.get("email"));
            case PREMISAVE_WALLET -> raw.put("accountRef",
                    details.get("email") != null ? details.get("email") : details.get("accountNumber"));
            default -> { }
        }
        return raw;
    }

    /** The currency a destination country is paid in, or null when it is not known. */
    public static String localCurrency(String country) {
        if (country == null) return null;
        return switch (country.toUpperCase()) {
            case "KE" -> "KES";
            default -> null;
        };
    }

    /**
     * A USD amount in a local currency that has no minor units in practice (whole shillings),
     * rounded down so the merchant is never debited for more than the destination receives.
     */
    public static BigDecimal toLocalWholeUnits(BigDecimal usd, BigDecimal localPerUsd) {
        return usd.multiply(localPerUsd).setScale(0, RoundingMode.DOWN);
    }
}
