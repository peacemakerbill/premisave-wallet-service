package com.premisave.wallet.commercial.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;
import java.util.Locale;

/**
 * Merchant onboarding (commercial.onboarding.*).
 *
 * requiredDocuments: document types that must be uploaded before an application can be submitted.
 * staffNotifyEmails: addresses told when an application is submitted or resubmitted. Leave empty to skip.
 * mailFrom: sender address of onboarding emails. Empty uses the mail account's own address.
 * roles: which staff roles may do what. Role names come from the X-Actor-Role header, compared without case.
 * drive: where uploaded documents are stored.
 */
@ConfigurationProperties(prefix = "commercial.onboarding")
public record CommercialOnboardingProperties(
        Boolean enabled,
        List<String> requiredDocuments,
        Long maxDocumentBytes,
        Integer maxDocuments,
        List<String> staffNotifyEmails,
        String mailFrom,
        Roles roles,
        Drive drive) {

    /** Documents travel as base64 in a JSON body capped at 12 MB by the request filter, which allows files up to 8 MB. */
    public static final long MAX_DOCUMENT_BYTES_LIMIT = 8L * 1024 * 1024;

    public CommercialOnboardingProperties {
        if (enabled == null) enabled = Boolean.TRUE;
        requiredDocuments = requiredDocuments == null
                ? List.of("BUSINESS_REGISTRATION", "TAX_COMPLIANCE", "DIRECTOR_ID")
                : requiredDocuments.stream().filter(s -> s != null && !s.isBlank())
                        .map(s -> s.trim().toUpperCase(Locale.ROOT)).toList();
        if (maxDocumentBytes == null || maxDocumentBytes < 1024) maxDocumentBytes = 5L * 1024 * 1024;
        if (maxDocumentBytes > MAX_DOCUMENT_BYTES_LIMIT) maxDocumentBytes = MAX_DOCUMENT_BYTES_LIMIT;
        if (maxDocuments == null || maxDocuments < 1) maxDocuments = 20;
        staffNotifyEmails = staffNotifyEmails == null ? List.of()
                : staffNotifyEmails.stream().filter(s -> s != null && !s.isBlank()).map(String::trim).toList();
        mailFrom = mailFrom == null || mailFrom.isBlank() ? null : mailFrom.trim();
        if (roles == null) roles = new Roles(null, null, null, null, null);
        if (drive == null) drive = new Drive(null, null, null, null, null, null, null, null);
    }

    /**
     * review: start a review, add notes, record a verdict on a document.
     * returnForCorrection: send an application back with items to fix.
     * approve: approve an application, which switches production on.
     * reject: reject an application.
     * reopen: bring a rejected application back into review.
     */
    public record Roles(List<String> review, List<String> returnForCorrection, List<String> approve,
                        List<String> reject, List<String> reopen) {
        public Roles {
            review = normalise(review, List.of("FINANCE", "OPERATIONS", "ADMIN"));
            returnForCorrection = normalise(returnForCorrection, List.of("FINANCE", "OPERATIONS", "ADMIN"));
            approve = normalise(approve, List.of("FINANCE", "ADMIN"));
            reject = normalise(reject, List.of("FINANCE", "OPERATIONS", "ADMIN"));
            reopen = normalise(reopen, List.of("ADMIN"));
        }

        private static List<String> normalise(List<String> in, List<String> fallback) {
            if (in == null) return fallback;
            return in.stream().filter(s -> s != null && !s.isBlank()).map(s -> s.trim().toUpperCase(Locale.ROOT)).toList();
        }
    }

    /**
     * Google Drive storage through a service account. Give the key either inline (serviceAccountJson, for
     * example from an environment variable) or as a file (serviceAccountJsonPath). folderId is the Drive
     * folder, or shared drive folder, the service account has been shared on with Editor access.
     */
    public record Drive(Boolean enabled, String serviceAccountJson, String serviceAccountJsonPath, String folderId,
                        Integer httpTimeoutSeconds, String tokenUrl, String apiBase, String uploadBase) {
        public Drive {
            if (enabled == null) enabled = Boolean.FALSE;
            if (httpTimeoutSeconds == null || httpTimeoutSeconds < 2) httpTimeoutSeconds = 30;
            if (tokenUrl == null || tokenUrl.isBlank()) tokenUrl = "https://oauth2.googleapis.com/token";
            if (apiBase == null || apiBase.isBlank()) apiBase = "https://www.googleapis.com/drive/v3";
            if (uploadBase == null || uploadBase.isBlank()) uploadBase = "https://www.googleapis.com/upload/drive/v3";
        }

        public boolean configured() {
            boolean hasKey = (serviceAccountJson != null && !serviceAccountJson.isBlank())
                    || (serviceAccountJsonPath != null && !serviceAccountJsonPath.isBlank());
            return enabled && hasKey && folderId != null && !folderId.isBlank();
        }
    }

    public boolean allows(List<String> allowedRoles, String role) {
        return role != null && allowedRoles.contains(role.trim().toUpperCase(Locale.ROOT));
    }
}
