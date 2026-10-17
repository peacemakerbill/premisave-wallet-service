package com.premisave.wallet.commercial.service;

import com.premisave.wallet.commercial.entity.MerchantProfile;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Tells whether two applications are for the same business. A developer account may apply for many businesses, but not
 * twice for the same one. The key is the business name with case, spaces and punctuation ignored, plus the country, so
 * "Acme Ltd." and "ACME LTD" in the same country are one business and a second "Acme" in another country is not.
 */
public final class BusinessKeys {

    private BusinessKeys() {}

    public static String of(String businessName, String country) {
        StringBuilder name = new StringBuilder();
        String text = businessName == null ? "" : businessName.toLowerCase(Locale.ROOT);
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            if (Character.isLetterOrDigit(cp)) name.appendCodePoint(cp);
            i += Character.charCount(cp);
        }
        String c = country == null ? "" : country.trim().toUpperCase(Locale.ROOT);
        return name + "|" + c;
    }

    /** The key of a stored merchant. Merchants created before keys existed get theirs worked out from name and country. */
    public static String of(MerchantProfile p) {
        return p.getApplicationKey() != null ? p.getApplicationKey() : of(p.getBusinessName(), p.getCountry());
    }

    /** The merchant in the list that is already the same business, if any. */
    public static Optional<MerchantProfile> findSame(List<MerchantProfile> existing, String key) {
        return existing.stream().filter(m -> of(m).equals(key)).findFirst();
    }
}
