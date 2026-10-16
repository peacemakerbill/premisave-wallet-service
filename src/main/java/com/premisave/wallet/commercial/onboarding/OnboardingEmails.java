package com.premisave.wallet.commercial.onboarding;

import java.util.List;

/** Builds the onboarding emails. Everything typed by a person is escaped before it reaches the page. */
public final class OnboardingEmails {

    private OnboardingEmails() {}

    public record Email(String subject, String html) {}

    public static Email received(String businessName, String merchantNumber, boolean resubmission) {
        String lead = resubmission
                ? "Thank you. We received your corrections and the review has resumed."
                : "Thank you. We received your merchant application and our team will review it.";
        return new Email("We received your merchant application - Premisave",
                page("Application received", lead, businessName, merchantNumber, List.of(),
                        "We will email you as soon as there is an update."));
    }

    public static Email reviewStarted(String businessName, String merchantNumber) {
        return new Email("Your merchant application is being reviewed - Premisave",
                page("Review started", "A member of our team has started reviewing your application.",
                        businessName, merchantNumber, List.of(), "No action is needed from you right now."));
    }

    public static Email returned(String businessName, String merchantNumber, String message, List<ReturnItem> items) {
        List<String> lines = items.stream().map(i -> label(i.getField()) + ": " + i.getMessage()).toList();
        return new Email("Action needed on your merchant application - Premisave",
                page("Please correct your application", message, businessName, merchantNumber, lines,
                        "Open your application, fix the items above and submit it again."));
    }

    public static Email approved(String businessName, String merchantNumber, String message) {
        String lead = blank(message) ? "Your application has been approved. Production is now switched on for your business."
                : "Your application has been approved. " + message;
        return new Email("Your merchant application is approved - Premisave",
                page("Application approved", lead, businessName, merchantNumber, List.of(),
                        "You can now create production apps with your merchant number."));
    }

    public static Email rejected(String businessName, String merchantNumber, String reason) {
        return new Email("Update on your merchant application - Premisave",
                page("Application not approved", "We are unable to approve your application at this time.",
                        businessName, merchantNumber, List.of("Reason: " + reason),
                        "If you believe this is a mistake, reply to this email or contact support."));
    }

    public static Email reopened(String businessName, String merchantNumber, String reason) {
        return new Email("Your merchant application is back in review - Premisave",
                page("Application reopened", "Your application has been reopened and is being reviewed again.",
                        businessName, merchantNumber, blank(reason) ? List.of() : List.of("Note: " + reason),
                        "We will email you when there is an update."));
    }

    public static Email staffAlert(String businessName, String merchantNumber, boolean resubmission) {
        String lead = resubmission ? "An applicant resubmitted after corrections." : "A new merchant application was submitted.";
        return new Email("Merchant application waiting for review - " + safeSubject(merchantNumber),
                page("Review needed", lead, businessName, merchantNumber, List.of(), "Open it in the review queue."));
    }

    static String label(String field) {
        if (field == null) return "";
        return field.startsWith("document:") ? "Document " + field.substring("document:".length()) : field;
    }

    static String page(String heading, String lead, String businessName, String merchantNumber,
                       List<String> bullets, String closing) {
        StringBuilder sb = new StringBuilder(1024);
        sb.append("<div style=\"font-family:Arial,Helvetica,sans-serif;max-width:560px;margin:0 auto;padding:24px;color:#1a1a1a\">");
        sb.append("<h2 style=\"margin:0 0 16px\">").append(esc(heading)).append("</h2>");
        sb.append("<p>").append(esc(lead)).append("</p>");
        sb.append("<table style=\"width:100%;border-collapse:collapse;margin:16px 0\">")
                .append(row("Business", businessName))
                .append(row("Merchant number", merchantNumber))
                .append("</table>");
        if (!bullets.isEmpty()) {
            sb.append("<ul style=\"padding-left:20px\">");
            for (String b : bullets) sb.append("<li style=\"margin-bottom:6px\">").append(esc(b)).append("</li>");
            sb.append("</ul>");
        }
        sb.append("<p>").append(esc(closing)).append("</p>");
        sb.append("<p style=\"color:#6b7280;font-size:12px\">Premisave</p></div>");
        return sb.toString();
    }

    private static String row(String label, String value) {
        if (blank(value)) return "";
        return "<tr><td style=\"padding:8px 0;color:#6b7280;border-bottom:1px solid #f0f1f3\">" + esc(label)
                + "</td><td style=\"padding:8px 0;font-weight:600;text-align:right;border-bottom:1px solid #f0f1f3\">"
                + esc(value) + "</td></tr>";
    }

    static String esc(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (char c : s.toCharArray()) {
            switch (c) {
                case '&' -> sb.append("&amp;");
                case '<' -> sb.append("&lt;");
                case '>' -> sb.append("&gt;");
                case '"' -> sb.append("&quot;");
                case '\'' -> sb.append("&#39;");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }

    private static String safeSubject(String s) {
        return s == null ? "" : s.replaceAll("[\\r\\n]", " ");
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
