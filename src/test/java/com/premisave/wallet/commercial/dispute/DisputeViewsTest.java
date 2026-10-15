package com.premisave.wallet.commercial.dispute;

import com.premisave.wallet.commercial.enums.RailId;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DisputeViewsTest {

    private static Dispute dispute() {
        Dispute d = new Dispute();
        d.setId("dp_1");
        d.setPaymentId("pay_92bf");
        d.setAmountCents(1000);
        d.setReason("PRODUCT_NOT_RECEIVED");
        d.setRail(RailId.SANDBOX);
        d.setEvidenceDueBy(Instant.parse("2026-10-28T23:59:59Z"));
        d.setCreatedAt(Instant.parse("2026-10-14T20:30:00Z"));
        d.setUpdatedAt(Instant.parse("2026-10-14T20:30:00Z"));
        return d;
    }

    @Test
    @SuppressWarnings("unchecked")
    void aNewDisputeLooksLikeTheSpecifiedObject() {
        Map<String, Object> v = DisputeViews.dispute(dispute());
        assertEquals("dp_1", v.get("id"));
        assertEquals("pay_92bf", v.get("paymentId"));
        assertEquals("NEEDS_EVIDENCE", v.get("status"));
        assertEquals("10.00", v.get("amount"));
        assertEquals("USD", v.get("currency"));
        assertEquals("PRODUCT_NOT_RECEIVED", v.get("reason"));
        assertEquals("2026-10-28T23:59:59Z", v.get("evidenceDueBy"));
        assertTrue(((List<Object>) v.get("evidence")).isEmpty());
        assertNull(v.get("holdId"));
        assertNull(v.get("unrecovered"));
        assertNull(v.get("resolvedAt"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void evidenceShowsFilesAndWhetherItWasSent() {
        Dispute d = dispute();
        d.setEvidence(List.of(new Dispute.Evidence("ev_1", "PROOF_OF_DELIVERY", "Delivered.",
                List.of(new Dispute.Attachment("dr.pdf", "https://f.example.com/dr.pdf", "application/pdf")),
                true, Instant.parse("2026-10-15T08:00:00Z"), Instant.parse("2026-10-15T08:00:00Z"))));
        List<Map<String, Object>> evidence = (List<Map<String, Object>>) DisputeViews.dispute(d).get("evidence");
        assertEquals(1, evidence.size());
        assertEquals("ev_1", evidence.get(0).get("id"));
        assertEquals(true, evidence.get(0).get("submitted"));
        assertEquals("dr.pdf", ((List<Map<String, Object>>) evidence.get(0).get("attachments")).get(0).get("name"));
    }

    @Test
    void onlyOpenDisputesAreActive() {
        assertTrue(Dispute.ACTIVE.contains(Dispute.Status.NEEDS_EVIDENCE));
        assertTrue(Dispute.ACTIVE.contains(Dispute.Status.UNDER_REVIEW));
        assertEquals(2, Dispute.ACTIVE.size());
        assertTrue(Dispute.Status.WON.isFinal());
        assertTrue(Dispute.Status.LOST.isFinal());
    }
}
