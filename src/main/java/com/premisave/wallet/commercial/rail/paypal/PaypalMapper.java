package com.premisave.wallet.commercial.rail.paypal;

import com.premisave.wallet.commercial.enums.ResultCode;
import com.premisave.wallet.commercial.enums.TxStatus;
import com.premisave.wallet.commercial.rail.RailOutcome;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Turns PayPal objects (already parsed to maps) into the provider neutral results of the rail seam. */
public final class PaypalMapper {

    private static final Set<String> NO_DECIMALS = Set.of("HUF", "JPY", "TWD");

    /** What an order says. APPROVED means the customer agreed and the merchant still has to capture. */
    public enum Kind { WAITING, APPROVED, COMPLETED, FAILED }

    public record OrderState(Kind kind, String captureId, ResultCode code, String description) {}

    private PaypalMapper() {}

    /** An amount the way PayPal wants it: a string, two decimals (none for a few currencies). */
    public static String value(BigDecimal amount, String currency) {
        int scale = NO_DECIMALS.contains(currency.toUpperCase(Locale.ROOT)) ? 0 : 2;
        return amount.setScale(scale, RoundingMode.HALF_UP).toPlainString();
    }

    public static OrderState order(Map<String, Object> order) {
        String status = text(order, "status");
        switch (status) {
            case "COMPLETED": {
                Map<String, Object> capture = firstCapture(order);
                String captureStatus = text(capture, "status");
                String id = text(capture, "id");
                switch (captureStatus) {
                    case "DECLINED", "FAILED":
                        return new OrderState(Kind.FAILED, null, ResultCode.DECLINED, "PayPal declined the payment");
                    case "PENDING":
                        return new OrderState(Kind.WAITING, id.isBlank() ? null : id, null, null);
                    default:
                        return new OrderState(Kind.COMPLETED, id.isBlank() ? null : id, ResultCode.SUCCESS, "Paid with PayPal");
                }
            }
            case "APPROVED":
                return new OrderState(Kind.APPROVED, null, null, null);
            case "VOIDED":
                return new OrderState(Kind.FAILED, null, ResultCode.CUSTOMER_CANCELLED, "The PayPal order was voided");
            default:
                return new OrderState(Kind.WAITING, null, null, null);
        }
    }

    public static RailOutcome outcome(OrderState s, String orderId) {
        return switch (s.kind()) {
            case COMPLETED -> new RailOutcome(TxStatus.SUCCESS, ResultCode.SUCCESS, s.description(), null, null, orderId);
            case FAILED -> new RailOutcome(TxStatus.FAILED, s.code(), s.description(), null, null, orderId);
            default -> null;
        };
    }

    /** The result code for a PayPal error response, from the first detail's issue. */
    public static ResultCode errorCode(Map<String, Object> body) {
        String issue = firstIssue(body);
        if (issue.contains("INSUFFICIENT")) return ResultCode.INSUFFICIENT_FUNDS;
        return ResultCode.DECLINED;
    }

    public static String errorMessage(Map<String, Object> body, int status) {
        String issue = firstIssue(body);
        String message = text(body, "message");
        if (!issue.isBlank()) return "PayPal refused the request (" + issue + ")";
        return message.isBlank() ? "PayPal answered " + status : "PayPal refused the request: " + message;
    }

    public static String firstIssue(Map<String, Object> body) {
        Object details = body == null ? null : body.get("details");
        if (details instanceof List<?> list && !list.isEmpty() && list.get(0) instanceof Map<?, ?> first) {
            Object issue = first.get("issue");
            return issue == null ? "" : String.valueOf(issue);
        }
        return "";
    }

    /** The first link with one of the given relations (PayPal uses "payer-action" or "approve"). */
    public static String link(Map<String, Object> body, String... rels) {
        Object links = body == null ? null : body.get("links");
        if (links instanceof List<?> list) {
            for (String rel : rels) {
                for (Object o : list) {
                    if (o instanceof Map<?, ?> m && rel.equals(String.valueOf(m.get("rel")))) {
                        Object href = m.get("href");
                        if (href != null) return String.valueOf(href);
                    }
                }
            }
        }
        return null;
    }

    public static RailOutcome refund(Map<String, Object> refund) {
        String id = text(refund, "id");
        return switch (text(refund, "status")) {
            case "COMPLETED" -> new RailOutcome(TxStatus.SUCCESS, ResultCode.SUCCESS, "Refunded", null, null, null);
            case "FAILED", "CANCELLED" -> new RailOutcome(TxStatus.FAILED, ResultCode.PROVIDER_ERROR, "The refund did not go through",
                    id, null, null);
            default -> null;
        };
    }

    /** A payout batch's single item: final once PayPal has paid it, refused it or got it back. */
    public static RailOutcome payout(Map<String, Object> batch, String batchId) {
        Object items = batch.get("items");
        String itemStatus = "";
        if (items instanceof List<?> list && !list.isEmpty() && list.get(0) instanceof Map<?, ?> first) {
            Object s = first.get("transaction_status");
            itemStatus = s == null ? "" : String.valueOf(s);
        }
        if (itemStatus.isBlank()) {
            // The batch can be refused outright before it has any item status.
            String batchStatus = text(map(batch, "batch_header"), "batch_status");
            if (batchStatus.equals("DENIED") || batchStatus.equals("CANCELED")) {
                return new RailOutcome(TxStatus.FAILED, ResultCode.DECLINED, "PayPal refused the payout", batchStatus, null, batchId);
            }
            return null;
        }
        return switch (itemStatus) {
            case "SUCCESS" -> new RailOutcome(TxStatus.SUCCESS, ResultCode.SUCCESS, "Paid by PayPal", null, null, batchId);
            case "FAILED", "BLOCKED", "DENIED" -> new RailOutcome(TxStatus.FAILED, ResultCode.DECLINED,
                    "PayPal could not pay this recipient", itemStatus, null, batchId);
            case "RETURNED", "REFUNDED", "REVERSED" -> new RailOutcome(TxStatus.FAILED, ResultCode.REVERSED,
                    "The payout came back from the recipient", itemStatus, null, batchId);
            default -> null; // PENDING, ONHOLD, UNCLAIMED: still in PayPal's hands
        };
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> firstCapture(Map<String, Object> order) {
        Object units = order.get("purchase_units");
        if (units instanceof List<?> ul && !ul.isEmpty() && ul.get(0) instanceof Map<?, ?> unit) {
            Object payments = unit.get("payments");
            if (payments instanceof Map<?, ?> p) {
                Object captures = p.get("captures");
                if (captures instanceof List<?> cl && !cl.isEmpty() && cl.get(0) instanceof Map<?, ?> cap) {
                    return (Map<String, Object>) cap;
                }
            }
        }
        return Map.of();
    }

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
