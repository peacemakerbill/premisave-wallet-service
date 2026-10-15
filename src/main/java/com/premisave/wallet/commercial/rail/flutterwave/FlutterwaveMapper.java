package com.premisave.wallet.commercial.rail.flutterwave;

import com.premisave.wallet.commercial.enums.ResultCode;
import com.premisave.wallet.commercial.enums.TxStatus;
import com.premisave.wallet.commercial.rail.RailOutcome;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.Map;

/** Pure helpers for the Flutterwave rail: phone numbers, names and the provider's status words. */
public final class FlutterwaveMapper {

    /** Dialling code of the country each mobile money currency belongs to. */
    private static final Map<String, String> DIAL_CODE = Map.of(
            "GHS", "233", "UGX", "256", "TZS", "255", "RWF", "250", "ZMW", "260", "KES", "254");

    private static final String TRANSFER_PREFIX = "Transfer status: ";

    private FlutterwaveMapper() {}

    public static String dialCode(String currency) {
        return currency == null ? null : DIAL_CODE.get(currency.toUpperCase(Locale.ROOT));
    }

    /** The number without its country code or leading zero, which is how Flutterwave takes a charge phone number. */
    public static String nationalNumber(String raw, String dial) {
        String digits = raw == null ? "" : raw.replaceAll("[^0-9]", "");
        if (dial != null && digits.startsWith(dial) && digits.length() > dial.length()) digits = digits.substring(dial.length());
        if (digits.startsWith("0")) digits = digits.substring(1);
        return digits;
    }

    /** The full number with its country code, which is how Flutterwave takes a transfer msisdn. */
    public static String fullNumber(String raw, String dial) {
        String digits = raw == null ? "" : raw.replaceAll("[^0-9]", "");
        if (dial == null || digits.startsWith(dial)) return digits;
        return dial + (digits.startsWith("0") ? digits.substring(1) : digits);
    }

    /** First and last name from one name. A single word has no last name. */
    public static String[] splitName(String name, String fallback) {
        String n = name == null || name.isBlank() ? fallback : name.trim();
        String[] parts = n.split("\\s+", 2);
        return new String[]{parts[0], parts.length > 1 ? parts[1] : null};
    }

    /** The placeholder email Flutterwave needs when the customer gave only a phone number. */
    public static String placeholderEmail(String transactionId, String domain) {
        return "c." + transactionId + "@" + domain;
    }

    /** A charge's verdict. Null while it is still pending or its state is not a word we know. */
    public static RailOutcome charge(String status, BigDecimal paid, String paidCurrency, BigDecimal expected,
                                     String expectedCurrency, String chargeId) {
        if (status == null) return null;
        switch (status.toLowerCase(Locale.ROOT)) {
            case "succeeded":
                if (paid != null && expected != null && paid.compareTo(expected) != 0) return null; // never applied automatically
                if (paidCurrency != null && expectedCurrency != null && !paidCurrency.equalsIgnoreCase(expectedCurrency)) return null;
                return new RailOutcome(TxStatus.SUCCESS, ResultCode.SUCCESS, "Paid by mobile money", null, null, chargeId);
            case "failed":
                return new RailOutcome(TxStatus.FAILED, ResultCode.DECLINED, "The mobile money payment was declined or not approved",
                        null, null, chargeId);
            default:
                return null;
        }
    }

    /** True when a charge says it succeeded but for a different amount or currency than was asked. */
    public static boolean paidWrongAmount(String status, BigDecimal paid, String paidCurrency, BigDecimal expected, String expectedCurrency) {
        if (status == null || !status.equalsIgnoreCase("succeeded")) return false;
        boolean amountDiffers = paid != null && expected != null && paid.compareTo(expected) != 0;
        boolean currencyDiffers = paidCurrency != null && expectedCurrency != null && !paidCurrency.equalsIgnoreCase(expectedCurrency);
        return amountDiffers || currencyDiffers;
    }

    /** The word after "Transfer status: " in the wallet service's transfer lookup message, upper cased, or null. */
    public static String transferStatus(String message) {
        if (message == null || !message.startsWith(TRANSFER_PREFIX)) return null;
        String s = message.substring(TRANSFER_PREFIX.length()).trim();
        return s.isEmpty() ? null : s.toUpperCase(Locale.ROOT);
    }

    public static RailOutcome transfer(String status, String transferId) {
        if (status == null) return null;
        return switch (status) {
            case "SUCCESSFUL" -> new RailOutcome(TxStatus.SUCCESS, ResultCode.SUCCESS, "Paid", null, null, transferId);
            case "FAILED" -> new RailOutcome(TxStatus.FAILED, ResultCode.DECLINED, "The transfer failed", null, null, transferId);
            default -> null; // NEW, PENDING and anything unknown stay open
        };
    }
}
