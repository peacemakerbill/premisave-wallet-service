package com.premisave.wallet.commercial.onboarding;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** The checks on an application, kept free of storage and web code so they can be tested alone. */
public final class OnboardingRules {

    private static final Set<String> ALLOWED_TYPES = Set.of("application/pdf", "image/jpeg", "image/png");

    private OnboardingRules() {}

    /** What is still missing before the application can be submitted: one entry per field with the issue. */
    public static List<Map<String, String>> missing(BusinessForm f, List<OnboardingDocument> docs, List<String> requiredDocuments) {
        List<Map<String, String>> out = new ArrayList<>();
        if (f.getBusinessType() == null) add(out, "businessType", "is required");
        text(out, "registeredName", f.getRegisteredName());
        text(out, "registrationNumber", f.getRegistrationNumber());
        text(out, "taxPin", f.getTaxPin());
        text(out, "country", f.getCountry());
        text(out, "city", f.getCity());
        text(out, "physicalAddress", f.getPhysicalAddress());
        text(out, "industry", f.getIndustry());
        text(out, "businessDescription", f.getBusinessDescription());
        text(out, "contactName", f.getContactName());
        text(out, "contactEmail", f.getContactEmail());
        text(out, "contactPhone", f.getContactPhone());
        if (f.getExpectedMonthlyVolumeUsd() == null) add(out, "expectedMonthlyVolumeUsd", "is required");

        List<Director> directors = f.getDirectors() == null ? List.of() : f.getDirectors();
        if (directors.isEmpty()) {
            add(out, "directors", "at least one director or owner is required");
        } else {
            for (int i = 0; i < directors.size(); i++) {
                Director d = directors.get(i);
                if (blank(d.getFullName())) add(out, "directors[" + i + "].fullName", "is required");
                if (blank(d.getIdNumber())) add(out, "directors[" + i + "].idNumber", "is required");
            }
            if (ownershipTotal(directors) > 100) add(out, "directors", "ownership adds up to more than 100 percent");
        }

        for (String type : requiredDocuments) {
            if (!hasUsable(docs, type)) add(out, "document:" + type, "a " + type + " document is required");
        }
        return out;
    }

    /** Required document types that do not yet have an accepted document. Used before approving. */
    public static List<String> unverifiedRequired(List<OnboardingDocument> docs, List<String> requiredDocuments) {
        List<String> out = new ArrayList<>();
        for (String type : requiredDocuments) {
            boolean accepted = docs.stream().anyMatch(d -> d.getType() != null && d.getType().name().equals(type)
                    && d.getVerdict() == DocumentVerdict.ACCEPTED);
            if (!accepted) out.add(type);
        }
        return out;
    }

    public static int ownershipTotal(List<Director> directors) {
        int total = 0;
        for (Director d : directors) if (d.getOwnershipPercent() != null) total += d.getOwnershipPercent();
        return total;
    }

    /** A document counts toward a type unless a reviewer rejected it. */
    static boolean hasUsable(List<OnboardingDocument> docs, String type) {
        return docs.stream().anyMatch(d -> d.getType() != null && d.getType().name().equals(type)
                && d.getVerdict() != DocumentVerdict.REJECTED);
    }

    public static boolean allowedContentType(String contentType) {
        return contentType != null && ALLOWED_TYPES.contains(contentType.trim().toLowerCase(Locale.ROOT));
    }

    /** Checks the first bytes of the file against the type it claims, so a renamed file is not accepted. */
    public static boolean matchesSignature(String contentType, byte[] b) {
        if (contentType == null || b == null || b.length < 8) return false;
        return switch (contentType.trim().toLowerCase(Locale.ROOT)) {
            case "application/pdf" -> b[0] == '%' && b[1] == 'P' && b[2] == 'D' && b[3] == 'F' && b[4] == '-';
            case "image/jpeg" -> (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8 && (b[2] & 0xFF) == 0xFF;
            case "image/png" -> (b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G'
                    && b[4] == 0x0D && b[5] == 0x0A && b[6] == 0x1A && b[7] == 0x0A;
            default -> false;
        };
    }

    /** A file name safe to use in Drive and in a download header: letters, digits, dot, dash and underscore. */
    public static String safeFileName(String original) {
        String base = original == null ? "" : original;
        int slash = Math.max(base.lastIndexOf('/'), base.lastIndexOf('\\'));
        if (slash >= 0) base = base.substring(slash + 1);
        base = base.trim().replaceAll("[^A-Za-z0-9._-]", "_");
        base = base.replaceAll("\\.{2,}", ".");
        if (base.startsWith(".")) base = "_" + base.substring(1);
        if (base.replace("_", "").replace(".", "").isBlank()) base = "document";
        return base.length() > 100 ? base.substring(base.length() - 100) : base;
    }

    /** The field names the applicant may be asked to fix: form fields or document:TYPE. */
    public static boolean validReturnField(String field) {
        if (field == null) return false;
        String f = field.trim();
        if (f.startsWith("document:")) {
            try {
                DocumentType.valueOf(f.substring("document:".length()).toUpperCase(Locale.ROOT));
                return true;
            } catch (IllegalArgumentException e) {
                return false;
            }
        }
        return f.matches("^[A-Za-z][A-Za-z0-9_.\\[\\]]{0,59}$");
    }

    /** Which statuses a transition may start from. */
    public static Set<OnboardingStatus> sourcesFor(String action) {
        return switch (action) {
            case "submit" -> EnumSet.of(OnboardingStatus.DRAFT, OnboardingStatus.RETURNED);
            case "startReview" -> EnumSet.of(OnboardingStatus.SUBMITTED);
            case "return", "approve" -> EnumSet.of(OnboardingStatus.IN_REVIEW);
            case "reject" -> EnumSet.of(OnboardingStatus.SUBMITTED, OnboardingStatus.IN_REVIEW);
            case "reopen" -> EnumSet.of(OnboardingStatus.REJECTED);
            default -> EnumSet.noneOf(OnboardingStatus.class);
        };
    }

    private static void text(List<Map<String, String>> out, String field, String value) {
        if (blank(value)) add(out, field, "is required");
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private static void add(List<Map<String, String>> out, String field, String issue) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("field", field);
        m.put("issue", issue);
        out.add(m);
    }
}
