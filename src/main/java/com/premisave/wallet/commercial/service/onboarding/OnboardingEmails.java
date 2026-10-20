package com.premisave.wallet.commercial.service.onboarding;

import com.premisave.wallet.commercial.entity.onboarding.ReturnItem;
import com.premisave.wallet.util.EmailLayout;
import com.premisave.wallet.util.EmailLayout.Tone;

import java.util.List;

/** Builds the onboarding emails. Everything typed by a person is escaped before it reaches the page. */
public final class OnboardingEmails {

    private static final String FOOTNOTE =
            "You are receiving this email about a Premisave merchant application. This is an automated message, please do not reply.";

    private OnboardingEmails() {}

    public record Email(String subject, String html) {}

    public static Email received(String businessName, String reference, boolean resubmission) {
        String lead = resubmission
                ? "Thank you. We received your corrections and the review has resumed."
                : "Thank you. We received your merchant application and our team will review it.";
        return new Email("We received your merchant application - Premisave",
                page(Tone.INFO, "Received", "Application received", lead, businessName, reference, List.of(),
                        "We will email you as soon as there is an update."));
    }

    /** Sent to the owner of the wallet named on an application, so they know it exists and where to follow it. */
    public static Email walletOwnerNotice(String businessName, boolean onBehalf, String applicantRole) {
        String lead = onBehalf
                ? "Your Premisave wallet was named on a merchant application made on behalf of a business"
                  + (blank(applicantRole) ? "." : " by its " + applicantRole + ".")
                : "Your Premisave wallet was named on a merchant application.";
        return new Email("Your wallet is named on a merchant application - Premisave",
                page(Tone.INFO, "For your information", "A merchant application names your wallet", lead, businessName,
                        null, List.of(),
                        "You can follow its progress in the Premisave app. If you do not recognise it, contact support."));
    }

    public static Email reviewStarted(String businessName, String reference) {
        return new Email("Your merchant application is being reviewed - Premisave",
                page(Tone.INFO, "In review", "Review started", "A member of our team has started reviewing your application.",
                        businessName, reference, List.of(), "No action is needed from you right now."));
    }

    public static Email returned(String businessName, String reference, String message, List<ReturnItem> items) {
        List<String> lines = items.stream().map(i -> label(i.getField()) + ": " + i.getMessage()).toList();
        return new Email("Action needed on your merchant application - Premisave",
                page(Tone.WARNING, "Action required", "Please correct your application", message, businessName, reference,
                        lines, "Open your application, fix the items above and submit it again."));
    }

    public static Email approved(String businessName, String reference, String message) {
        String lead = blank(message) ? "Your application has been approved. Production is now switched on for your business."
                : "Your application has been approved. " + message;
        return new Email("Your merchant application is approved - Premisave",
                page(Tone.SUCCESS, "Approved", "Application approved", lead, businessName, reference, List.of(),
                        "You can now create production apps with your merchant number."));
    }

    public static Email rejected(String businessName, String reference, String reason) {
        return new Email("Update on your merchant application - Premisave",
                page(Tone.DANGER, "Not approved", "Application not approved", "We are unable to approve your application at this time.",
                        businessName, reference, List.of("Reason: " + reason),
                        "If you believe this is a mistake, reply to this email or contact support."));
    }

    public static Email reopened(String businessName, String reference, String reason) {
        return new Email("Your merchant application is back in review - Premisave",
                page(Tone.INFO, "Reopened", "Application reopened", "Your application has been reopened and is being reviewed again.",
                        businessName, reference, blank(reason) ? List.of() : List.of("Note: " + reason),
                        "We will email you when there is an update."));
    }

    public static Email staffAlert(String businessName, String reference, boolean resubmission) {
        String lead = resubmission ? "An applicant resubmitted after corrections." : "A new merchant application was submitted.";
        return new Email("Merchant application waiting for review - " + safeSubject(reference),
                page(Tone.WARNING, "Review needed", "Review needed", lead, businessName, reference, List.of(),
                        "Open it in the review queue."));
    }

    static String label(String field) {
        if (field == null) return "";
        return field.startsWith("document:") ? "Document " + field.substring("document:".length()) : field;
    }

    /** The finished page: status label, heading, lead, the business, optional points, and a closing line. */
    static String page(Tone tone, String status, String heading, String lead, String businessName, String reference,
                       List<String> bullets, String closing) {
        StringBuilder body = new StringBuilder(2048);
        body.append(EmailLayout.pill(tone, status))
                .append(EmailLayout.heading(heading))
                .append(EmailLayout.paragraph(lead))
                .append(EmailLayout.details("Business", businessName, referenceLabel(reference), reference));
        if (!bullets.isEmpty()) body.append(EmailLayout.note(tone, null, bullets));
        body.append(EmailLayout.rule()).append(EmailLayout.small(closing));
        return EmailLayout.wrap("Merchants", heading, lead, FOOTNOTE, body.toString());
    }

    /**
     * The number is issued at approval, so before that an email carries the application id instead. The two are told apart
     * by shape: only a real merchant number is called one.
     */
    private static String referenceLabel(String reference) {
        return MerchantNumbers.isReal(reference) ? "Merchant number" : "Application reference";
    }

    private static String safeSubject(String s) {
        return s == null ? "" : s.replaceAll("[\\r\\n]", " ");
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}