package com.premisave.wallet.commercial.dispute;

import com.premisave.wallet.commercial.dto.SubmitEvidenceRequest;
import com.premisave.wallet.commercial.enums.ErrorCode;
import com.premisave.wallet.commercial.exception.CommercialException;
import org.bson.Document;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DisputeRulesTest {

    private static SubmitEvidenceRequest.Attachment file(String url) {
        return new SubmitEvidenceRequest.Attachment("delivery-receipt.pdf", url, "application/pdf");
    }

    private static SubmitEvidenceRequest req(String type, String text, List<SubmitEvidenceRequest.Attachment> files) {
        return new SubmitEvidenceRequest(type, text, files, true);
    }

    @Test
    void goodEvidenceIsTidied() {
        DisputeRules.Clean c = DisputeRules.clean(req(" proof_of_delivery ", "  Parcel delivered.  ",
                List.of(file("https://files.example.com/dr-77.pdf"))));
        assertEquals("PROOF_OF_DELIVERY", c.type());
        assertEquals("Parcel delivered.", c.text());
        assertEquals(1, c.attachments().size());
        assertEquals("application/pdf", c.attachments().get(0).getMimeType());
    }

    @Test
    void textAloneOrFilesAloneIsEnoughButNeitherIsNot() {
        DisputeRules.clean(req("NOTE", "Customer confirmed by phone.", null));
        DisputeRules.clean(req("RECEIPT", null, List.of(file("https://files.example.com/r.pdf"))));
        CommercialException e = assertThrows(CommercialException.class, () -> DisputeRules.clean(req("NOTE", "   ", List.of())));
        assertEquals(ErrorCode.VALIDATION_FAILED, e.code());
    }

    @Test
    void theTypeMustLookLikeATypeCode() {
        for (String bad : new String[]{"", "1ABC", "has space", "x", "A".repeat(41), "drop;table"}) {
            CommercialException e = assertThrows(CommercialException.class,
                    () -> DisputeRules.clean(req(bad, "text", null)), bad);
            assertEquals(ErrorCode.VALIDATION_FAILED, e.code());
        }
    }

    @Test
    void attachmentLinksMustBePlainHttps() {
        for (String bad : new String[]{"http://files.example.com/a.pdf", "ftp://x.example.com/a", "javascript:alert(1)",
                "https://user:pw@files.example.com/a.pdf", "https:///nohost", "not a url"}) {
            CommercialException e = assertThrows(CommercialException.class,
                    () -> DisputeRules.clean(req("NOTE", "t", List.of(file(bad)))), bad);
            assertEquals(ErrorCode.VALIDATION_FAILED, e.code());
        }
    }

    @Test
    void theMimeTypeMustBeAMediaType() {
        SubmitEvidenceRequest.Attachment bad = new SubmitEvidenceRequest.Attachment("a.pdf", "https://f.example.com/a.pdf", "pdf");
        assertThrows(CommercialException.class, () -> DisputeRules.clean(req("NOTE", "t", List.of(bad))));
    }

    @Test
    void evidenceIsAcceptedOnlyWhileWaitingAndBeforeTheDeadline() {
        Instant due = Instant.parse("2026-10-28T23:59:59Z");
        DisputeRules.requireOpenForEvidence(Dispute.Status.NEEDS_EVIDENCE, due, Instant.parse("2026-10-20T10:00:00Z"));
        DisputeRules.requireOpenForEvidence(Dispute.Status.NEEDS_EVIDENCE, due, due);

        CommercialException late = assertThrows(CommercialException.class, () -> DisputeRules.requireOpenForEvidence(
                Dispute.Status.NEEDS_EVIDENCE, due, due.plusSeconds(1)));
        assertEquals(ErrorCode.INVALID_STATE, late.code());
        for (Dispute.Status s : new Dispute.Status[]{Dispute.Status.UNDER_REVIEW, Dispute.Status.WON, Dispute.Status.LOST}) {
            CommercialException e = assertThrows(CommercialException.class, () -> DisputeRules.requireOpenForEvidence(s, due, Instant.parse("2026-10-20T00:00:00Z")));
            assertEquals(ErrorCode.INVALID_STATE, e.code(), s.name());
        }
    }

    @Test
    void theDeadlineIsTheEndOfTheUtcDay() {
        assertEquals(Instant.parse("2026-10-28T23:59:59Z"), DisputeRules.dueBy(Instant.parse("2026-10-14T20:30:00Z"), 14));
        assertEquals(Instant.parse("2026-11-01T23:59:59Z"), DisputeRules.dueBy(Instant.parse("2026-10-31T23:59:59Z"), 1));
    }

    @Test
    @SuppressWarnings("unchecked")
    void anEntryIsStoredWithItsFiles() {
        Dispute.Evidence e = new Dispute.Evidence("ev_1", "NOTE", "text",
                List.of(new Dispute.Attachment("a.pdf", "https://f.example.com/a.pdf", "application/pdf")),
                false, Instant.parse("2026-10-14T20:30:00Z"), null);
        Document d = DisputeRules.toDocument(e);
        assertEquals("ev_1", d.get("evidenceId"));
        assertEquals(false, d.get("submitted"));
        assertEquals(null, d.get("submittedAt"));
        assertEquals(1, ((List<Document>) d.get("attachments")).size());
        assertEquals("a.pdf", ((List<Document>) d.get("attachments")).get(0).get("name"));
    }

    @Test
    void markingAnEntrySentKeepsItsContent() {
        Dispute.Evidence e = new Dispute.Evidence("ev_1", "NOTE", "text", List.of(), false, Instant.parse("2026-10-14T20:30:00Z"), null);
        Dispute.Evidence sent = e.sentAt(Instant.parse("2026-10-15T08:00:00Z"));
        assertTrue(sent.isSubmitted());
        assertFalse(e.isSubmitted());
        assertEquals("ev_1", sent.getId());
        assertEquals(Instant.parse("2026-10-15T08:00:00Z"), sent.getSubmittedAt());
    }
}
