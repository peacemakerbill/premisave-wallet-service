package com.premisave.wallet.commercial.dispute;

import com.premisave.wallet.commercial.dto.SubmitEvidenceRequest;
import com.premisave.wallet.commercial.exception.CommercialException;
import org.bson.Document;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** The rules of a dispute that need no database: what evidence is acceptable, when it may be sent, how it is stored. */
public final class DisputeRules {

    private static final Pattern TYPE = Pattern.compile("^[A-Z][A-Z0-9_]{1,39}$");
    private static final Pattern MIME = Pattern.compile("^[a-zA-Z0-9][a-zA-Z0-9!#$&^_.+-]*/[a-zA-Z0-9][a-zA-Z0-9!#$&^_.+-]*$");

    private DisputeRules() {}

    public record Clean(String type, String text, List<Dispute.Attachment> attachments) {}

    /** Checks and tidies a piece of evidence. Throws VALIDATION_FAILED naming the field. */
    public static Clean clean(SubmitEvidenceRequest req) {
        String type = req.type() == null ? "" : req.type().trim().toUpperCase(Locale.ROOT);
        if (!TYPE.matcher(type).matches()) {
            throw CommercialException.validation("type", "must be upper case words joined by underscores, such as PROOF_OF_DELIVERY");
        }
        String text = req.text() == null || req.text().isBlank() ? null : req.text().trim();
        List<Dispute.Attachment> files = new ArrayList<>();
        if (req.attachments() != null) {
            for (int i = 0; i < req.attachments().size(); i++) {
                SubmitEvidenceRequest.Attachment a = req.attachments().get(i);
                String field = "attachments[" + i + "]";
                String name = a.name().trim();
                if (name.isEmpty() || name.chars().anyMatch(Character::isISOControl)) {
                    throw CommercialException.validation(field + ".name", "must be a plain file name");
                }
                String url = a.url().trim();
                if (!isHttpsUrl(url)) {
                    throw CommercialException.validation(field + ".url", "must be an https URL");
                }
                String mime = a.mimeType().trim();
                if (!MIME.matcher(mime).matches()) {
                    throw CommercialException.validation(field + ".mimeType", "must be a media type such as application/pdf");
                }
                files.add(new Dispute.Attachment(name, url, mime));
            }
        }
        if (text == null && files.isEmpty()) {
            throw CommercialException.validation("text", "or at least one attachment is required");
        }
        return new Clean(type, text, files);
    }

    static boolean isHttpsUrl(String url) {
        try {
            URI u = new URI(url);
            return "https".equalsIgnoreCase(u.getScheme()) && u.getHost() != null && !u.getHost().isBlank()
                    && u.getUserInfo() == null;
        } catch (URISyntaxException e) {
            return false;
        }
    }

    /** Evidence is taken only while the dispute is waiting for it and the deadline has not passed. */
    public static void requireOpenForEvidence(Dispute.Status status, Instant dueBy, Instant now) {
        if (status != Dispute.Status.NEEDS_EVIDENCE) {
            throw CommercialException.invalidState(status.isFinal()
                    ? "The dispute has been decided (" + status + ") and takes no more evidence"
                    : "The evidence was already submitted and the dispute is " + status);
        }
        if (dueBy != null && now.isAfter(dueBy)) {
            throw CommercialException.invalidState("The evidence deadline of " + dueBy + " has passed");
        }
    }

    /** The end of the UTC day that is the given number of days away, for example 2026-10-28T23:59:59Z. */
    public static Instant dueBy(Instant now, int days) {
        LocalDate day = now.atZone(ZoneOffset.UTC).toLocalDate().plusDays(days);
        return day.atTime(23, 59, 59).toInstant(ZoneOffset.UTC);
    }

    /** The stored form of an evidence entry, used when the list is written back with a guarded update. */
    public static Document toDocument(Dispute.Evidence e) {
        List<Document> files = new ArrayList<>();
        for (Dispute.Attachment a : e.getAttachments()) {
            files.add(new Document("name", a.getName()).append("url", a.getUrl()).append("mimeType", a.getMimeType()));
        }
        return new Document("evidenceId", e.getId()).append("type", e.getType()).append("text", e.getText())
                .append("attachments", files).append("submitted", e.isSubmitted())
                .append("createdAt", e.getCreatedAt() == null ? null : Date.from(e.getCreatedAt()))
                .append("submittedAt", e.getSubmittedAt() == null ? null : Date.from(e.getSubmittedAt()));
    }
}
