package com.premisave.wallet.commercial.rail.stripe;

import com.premisave.wallet.commercial.enums.ResultCode;
import com.premisave.wallet.commercial.enums.TxStatus;
import com.premisave.wallet.commercial.rail.RailOutcome;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Turns Stripe objects (already parsed to maps) into the provider neutral results of the rail seam. */
public final class StripeMapper {

    private static final int EVIDENCE_FIELD_MAX = 19_000;

    /** What a PaymentIntent says about the payment. */
    public enum Verdict { WAITING, SUCCESS, FAILED, CANCELLED }

    public record IntentState(Verdict verdict, ResultCode code, String description, String providerCode) {
        static IntentState waiting() { return new IntentState(Verdict.WAITING, null, null, null); }
    }

    private StripeMapper() {}

    public static IntentState intent(Map<String, Object> pi) {
        String status = text(pi, "status");
        switch (status) {
            case "succeeded":
                return new IntentState(Verdict.SUCCESS, ResultCode.SUCCESS, "Paid by card", null);
            case "canceled":
                return new IntentState(Verdict.CANCELLED, ResultCode.CUSTOMER_CANCELLED,
                        "The card payment was cancelled", text(pi, "cancellation_reason"));
            case "requires_payment_method": {
                Map<String, Object> error = map(pi, "last_payment_error");
                if (error.isEmpty()) return IntentState.waiting();
                String decline = text(error, "decline_code");
                String code = text(error, "code");
                return new IntentState(Verdict.FAILED, declineCode(code, decline),
                        text(error, "message").isBlank() ? "The card was declined" : text(error, "message"),
                        decline.isBlank() ? code : decline);
            }
            default:
                return IntentState.waiting();
        }
    }

    /** Provider neutral code for a Stripe card error. */
    public static ResultCode declineCode(String code, String declineCode) {
        String c = code == null ? "" : code;
        String d = declineCode == null ? "" : declineCode;
        if (d.equals("insufficient_funds") || c.equals("insufficient_funds")) return ResultCode.INSUFFICIENT_FUNDS;
        if (c.equals("processing_error") || c.equals("api_error") || d.equals("processing_error")) return ResultCode.PROVIDER_ERROR;
        return ResultCode.DECLINED;
    }

    public static RailOutcome outcome(IntentState s, String providerReference) {
        TxStatus status = switch (s.verdict()) {
            case SUCCESS -> TxStatus.SUCCESS;
            case FAILED -> TxStatus.FAILED;
            case CANCELLED -> TxStatus.CANCELLED;
            case WAITING -> null;
        };
        if (status == null) return null;
        return new RailOutcome(status, s.code(), s.description(), s.providerCode(), null, providerReference);
    }

    /** A refund that Stripe has settled one way or the other, or null while it is still in progress. */
    public static RailOutcome refund(Map<String, Object> refund) {
        String status = text(refund, "status");
        String id = text(refund, "id");
        return switch (status) {
            case "succeeded" -> new RailOutcome(TxStatus.SUCCESS, ResultCode.SUCCESS, "Refunded", null, null, id.isBlank() ? null : id);
            case "failed", "canceled" -> new RailOutcome(TxStatus.FAILED, ResultCode.PROVIDER_ERROR,
                    "The refund did not go through" + (text(refund, "failure_reason").isBlank() ? "" : ": " + text(refund, "failure_reason")),
                    text(refund, "failure_reason"), null, id.isBlank() ? null : id);
            default -> null;
        };
    }

    /** The share of the payment a dispute covers, in USD cents, when the dispute is in the charge currency. */
    public static long disputeUsdCents(long disputedMinor, long chargedMinor, long paymentUsdCents) {
        if (chargedMinor <= 0 || disputedMinor >= chargedMinor) return paymentUsdCents;
        long usd = Math.round((double) disputedMinor * paymentUsdCents / chargedMinor);
        return Math.max(1, Math.min(paymentUsdCents, usd));
    }

    /**
     * Stripe evidence fields from the merchant's entries (type, text, attachments). Types with a text field of
     * their own go there; everything else, and every attachment link, goes into the uncategorised text.
     */
    public static Map<String, String> evidenceFields(List<Map<String, Object>> entries) {
        Map<String, StringBuilder> fields = new LinkedHashMap<>();
        for (Map<String, Object> e : entries) {
            String type = text(e, "type").toUpperCase(Locale.ROOT);
            String field = switch (type) {
                case "PRODUCT_DESCRIPTION" -> "product_description";
                case "CANCELLATION_REBUTTAL" -> "cancellation_rebuttal";
                case "REFUND_REFUSAL_EXPLANATION" -> "refund_refusal_explanation";
                case "DUPLICATE_CHARGE_EXPLANATION" -> "duplicate_charge_explanation";
                case "ACCESS_ACTIVITY_LOG" -> "access_activity_log";
                default -> "uncategorized_text";
            };
            StringBuilder sb = fields.computeIfAbsent(field, k -> new StringBuilder());
            if (sb.length() > 0) sb.append("\n\n");
            if (field.equals("uncategorized_text") && !type.isBlank()) sb.append(type).append(": ");
            sb.append(text(e, "text"));
            Object attachments = e.get("attachments");
            if (attachments instanceof List<?> list) {
                for (Object a : list) sb.append("\nAttachment: ").append(a);
            }
        }
        Map<String, String> out = new LinkedHashMap<>();
        fields.forEach((k, v) -> {
            String s = v.toString().trim();
            if (s.isEmpty()) return;
            out.put("evidence[" + k + "]", s.length() > EVIDENCE_FIELD_MAX ? s.substring(0, EVIDENCE_FIELD_MAX) : s);
        });
        return out;
    }

    // ------------------------------------------------------------------ plumbing

    @SuppressWarnings("unchecked")
    static Map<String, Object> map(Map<String, Object> parent, String key) {
        Object v = parent == null ? null : parent.get(key);
        return v instanceof Map<?, ?> ? (Map<String, Object>) v : Map.of();
    }

    static String text(Map<String, Object> map, String key) {
        Object v = map == null ? null : map.get(key);
        return v == null ? "" : String.valueOf(v);
    }
}
