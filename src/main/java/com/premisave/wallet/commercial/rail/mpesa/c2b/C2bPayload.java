package com.premisave.wallet.commercial.rail.mpesa.c2b;

import java.math.BigDecimal;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The fields of a Daraja C2B validation or confirmation request that matter here. Daraja sends every value
 * as a string. In production the MSISDN is a hash of the number, not the number, so it is only kept when it
 * looks like a phone number.
 */
public record C2bPayload(String transId, String transTime, BigDecimal amount, String shortcode, String billRef,
                         String msisdn, String firstName, String middleName, String lastName) {

    private static final Pattern PHONE = Pattern.compile("\\+?\\d{9,15}");

    public static C2bPayload parse(Map<String, Object> body) {
        return new C2bPayload(
                text(body, "TransID"),
                text(body, "TransTime"),
                decimal(body.get("TransAmount")),
                text(body, "BusinessShortCode"),
                text(body, "BillRefNumber"),
                text(body, "MSISDN"),
                text(body, "FirstName"),
                text(body, "MiddleName"),
                text(body, "LastName"));
    }

    /** The payer's name as Safaricom gives it, or null. */
    public String payerName() {
        StringBuilder sb = new StringBuilder();
        for (String part : new String[]{firstName, middleName, lastName}) {
            if (part != null && !part.isBlank()) {
                if (sb.length() > 0) sb.append(' ');
                sb.append(part.trim());
            }
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    /** The payer's phone number when Safaricom sent a real one, otherwise null (a hash is useless as a number). */
    public String payerPhone() {
        return msisdn != null && PHONE.matcher(msisdn.trim()).matches() ? msisdn.trim().replace("+", "") : null;
    }

    private static String text(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return v == null ? null : String.valueOf(v).trim();
    }

    private static BigDecimal decimal(Object v) {
        try {
            return v == null ? null : new BigDecimal(String.valueOf(v).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
