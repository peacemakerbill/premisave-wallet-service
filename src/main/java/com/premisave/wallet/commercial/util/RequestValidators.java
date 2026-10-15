package com.premisave.wallet.commercial.util;

import com.premisave.wallet.commercial.enums.DestinationType;
import com.premisave.wallet.commercial.enums.MethodType;
import com.premisave.wallet.commercial.exception.CommercialException;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Checks the parts of a request that depend on a method or destination type,
 * and returns cleaned values. Everything here is pure so it can be tested
 * without a running service.
 */
public final class RequestValidators {

    private static final Pattern PHONE = Pattern.compile("^\\+?\\d{9,15}$");
    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
    private static final Pattern COUNTRY = Pattern.compile("^[A-Za-z]{2}$");
    private static final Pattern DIGITS = Pattern.compile("^\\d+$");
    private static final Pattern METADATA_KEY = Pattern.compile("^[A-Za-z0-9_\\-]{1,40}$");
    private static final int MAX_METADATA_KEYS = 20;
    private static final int MAX_METADATA_VALUE = 500;

    private RequestValidators() {}

    /** Strips a leading plus and checks the length: 254712345678 and +254712345678 are the same number. */
    public static String normalisePhone(String field, String phone) {
        if (phone == null || !PHONE.matcher(phone.trim()).matches()) {
            throw CommercialException.validation(field, "must be 9 to 15 digits, optionally starting with +");
        }
        String p = phone.trim();
        return p.startsWith("+") ? p.substring(1) : p;
    }

    public static boolean isEmail(String s) {
        return s != null && EMAIL.matcher(s).matches();
    }

    /** Applies the per method customer requirements from the endpoint reference and returns the cleaned customer. */
    public static Map<String, Object> customerFor(MethodType method, String name, String email, String phone,
                                                  String accountRef, String returnUrl, String cancelUrl) {
        Map<String, Object> c = new LinkedHashMap<>();
        switch (method) {
            case MOBILE_MONEY -> c.put("phone", normalisePhone("customer.phone", phone));
            case CARD -> c.put("email", requireEmail(email));
            case PAYPAL -> {
                c.put("email", requireEmail(email));
                if (returnUrl == null || returnUrl.isBlank()) throw CommercialException.validation("method.returnUrl", "is required for PAYPAL");
                if (cancelUrl == null || cancelUrl.isBlank()) throw CommercialException.validation("method.cancelUrl", "is required for PAYPAL");
            }
            case BANK_TRANSFER -> {
                if (name == null || name.isBlank()) throw CommercialException.validation("customer.name", "is required for BANK_TRANSFER");
                c.put("name", name.trim());
                c.put("email", requireEmail(email));
            }
            case CRYPTO -> c.put("email", requireEmail(email));
            case PREMISAVE_WALLET -> {
                if (accountRef == null || accountRef.isBlank()) throw CommercialException.validation("customer.accountRef", "is required for PREMISAVE_WALLET");
                c.put("accountRef", accountRef.trim());
            }
        }
        // Optional extras are kept when present and valid enough.
        if (name != null && !name.isBlank()) c.putIfAbsent("name", name.trim());
        if (email != null && !email.isBlank() && isEmail(email)) c.putIfAbsent("email", email.trim());
        if (phone != null && !phone.isBlank() && !c.containsKey("phone") && PHONE.matcher(phone.trim()).matches()) {
            c.put("phone", normalisePhone("customer.phone", phone));
        }
        return c;
    }

    private static String requireEmail(String email) {
        if (!isEmail(email)) throw CommercialException.validation("customer.email", "must be a valid email address");
        return email.trim();
    }

    /** Validates a payout destination object and returns the cleaned copy with its type resolved. */
    public static Map<String, Object> destination(DestinationType type, Map<String, Object> raw) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("type", type.name());
        switch (type) {
            case MOBILE_MONEY -> {
                d.put("country", country(raw));
                d.put("phoneNumber", normalisePhone("destination.phoneNumber", str(raw, "phoneNumber")));
                String network = str(raw, "network");
                if (network != null && !network.isBlank()) {
                    if (network.trim().length() > 20) throw CommercialException.validation("destination.network", "is longer than 20 characters");
                    d.put("network", network.trim().toUpperCase(Locale.ROOT));
                }
            }
            case BUSINESS_ACCOUNT -> {
                d.put("country", country(raw));
                String subtype = str(raw, "subtype");
                if (subtype == null || !(subtype.equals("PAYBILL") || subtype.equals("TILL"))) {
                    throw CommercialException.validation("destination.subtype", "must be PAYBILL or TILL");
                }
                d.put("subtype", subtype);
                String shortcode = str(raw, "shortcode");
                if (shortcode == null || !DIGITS.matcher(shortcode).matches() || shortcode.length() < 5 || shortcode.length() > 7) {
                    throw CommercialException.validation("destination.shortcode", "must be 5 to 7 digits");
                }
                d.put("shortcode", shortcode);
                if (subtype.equals("PAYBILL")) {
                    String account = str(raw, "accountNumber");
                    if (account == null || account.isBlank() || account.length() > 40) {
                        throw CommercialException.validation("destination.accountNumber", "is required for PAYBILL (up to 40 characters)");
                    }
                    d.put("accountNumber", account.trim());
                }
            }
            case BANK_ACCOUNT -> {
                d.put("country", country(raw));
                String bank = str(raw, "bankCode");
                if (bank == null || bank.isBlank() || bank.length() > 20) {
                    throw CommercialException.validation("destination.bankCode", "is required (up to 20 characters)");
                }
                d.put("bankCode", bank.trim());
                String acct = str(raw, "accountNumber");
                if (acct == null || !DIGITS.matcher(acct).matches() || acct.length() < 6 || acct.length() > 20) {
                    throw CommercialException.validation("destination.accountNumber", "must be 6 to 20 digits");
                }
                d.put("accountNumber", acct);
                String name = str(raw, "accountName");
                if (name != null && !name.isBlank()) d.put("accountName", name.trim());
            }
            case PAYPAL -> {
                String email = str(raw, "email");
                if (!isEmail(email)) throw CommercialException.validation("destination.email", "must be a valid email address");
                d.put("email", email.trim());
            }
            case CRYPTO -> {
                String currency = str(raw, "currency");
                String network = str(raw, "network");
                String address = str(raw, "address");
                if (currency == null || currency.isBlank()) throw CommercialException.validation("destination.currency", "is required");
                if (network == null || network.isBlank()) throw CommercialException.validation("destination.network", "is required");
                if (address == null || address.trim().length() < 10 || address.length() > 128) {
                    throw CommercialException.validation("destination.address", "must be 10 to 128 characters");
                }
                d.put("currency", currency.trim().toUpperCase(Locale.ROOT));
                d.put("network", network.trim().toUpperCase(Locale.ROOT));
                d.put("address", address.trim());
            }
            case PREMISAVE_WALLET -> {
                String ref = str(raw, "accountRef");
                if (ref == null || ref.isBlank()) throw CommercialException.validation("destination.accountRef", "is required");
                d.put("accountRef", ref.trim());
            }
        }
        return d;
    }

    public static DestinationType destinationType(Map<String, Object> raw) {
        String t = str(raw, "type");
        if (t == null) throw CommercialException.validation("destination.type", "is required");
        try {
            return DestinationType.valueOf(t.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw CommercialException.validation("destination.type", "is not a known destination type");
        }
    }

    /** At most 20 keys; keys of letters, digits, _ and -; values up to 500 characters. Returns a safe copy. */
    public static Map<String, String> metadata(Map<String, String> raw) {
        Map<String, String> m = new LinkedHashMap<>();
        if (raw == null) return m;
        if (raw.size() > MAX_METADATA_KEYS) throw CommercialException.validation("metadata", "allows at most " + MAX_METADATA_KEYS + " keys");
        for (Map.Entry<String, String> e : raw.entrySet()) {
            if (e.getKey() == null || !METADATA_KEY.matcher(e.getKey()).matches()) {
                throw CommercialException.validation("metadata", "keys must be 1 to 40 letters, digits, _ or -");
            }
            String v = e.getValue() == null ? "" : e.getValue();
            if (v.length() > MAX_METADATA_VALUE) throw CommercialException.validation("metadata." + e.getKey(), "is longer than " + MAX_METADATA_VALUE + " characters");
            m.put(e.getKey(), v);
        }
        return m;
    }

    private static String country(Map<String, Object> raw) {
        String c = str(raw, "country");
        if (c == null || !COUNTRY.matcher(c).matches()) {
            throw CommercialException.validation("destination.country", "must be a 2 letter country code");
        }
        return c.toUpperCase(Locale.ROOT);
    }

    private static String str(Map<String, Object> raw, String key) {
        Object v = raw.get(key);
        return v == null ? null : String.valueOf(v);
    }
}
