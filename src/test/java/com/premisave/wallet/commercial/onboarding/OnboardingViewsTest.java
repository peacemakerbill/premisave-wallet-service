package com.premisave.wallet.commercial.onboarding;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.premisave.wallet.commercial.dto.Views;
import com.premisave.wallet.commercial.entity.MerchantProfile;

class OnboardingViewsTest {

    private static OnboardingApplication sample() {
        OnboardingApplication a = new OnboardingApplication();
        a.setApplicationId("onb_1");
        a.setMerchantId("mer_1");
        a.setMerchantNumber("PRM-0000000001");
        a.setStatus(OnboardingStatus.IN_REVIEW);
        a.setReviewerId("fin-1");
        a.setReviewerName("Fiona");
        a.setReviewerRole("FINANCE");
        a.setForm(OnboardingRulesTest.completeForm());
        OnboardingDocument d = OnboardingRulesTest.doc(DocumentType.TAX_COMPLIANCE, DocumentVerdict.ACCEPTED);
        d.setDriveFileId("fileabcdefghij");
        d.setDriveViewLink("https://drive.example/x");
        d.setSha256("ab".repeat(32));
        d.setVerdictBy("fin-1");
        d.setUploadedBy("owner-1");
        d.setUploadedAt(Instant.parse("2026-10-16T08:00:00Z"));
        a.getDocuments().add(d);
        return a;
    }

    @Test
    void applicantsDoNotSeeReviewersOrDriveDetails() {
        Map<String, Object> v = OnboardingViews.application(sample(), false, List.of("TAX_COMPLIANCE"), List.of());
        assertFalse(v.containsKey("reviewer"));
        Map<?, ?> doc = (Map<?, ?>) ((List<?>) v.get("documents")).get(0);
        assertFalse(doc.containsKey("driveFileId"));
        assertFalse(doc.containsKey("driveViewLink"));
        assertFalse(doc.containsKey("verdictBy"));
        assertEquals("ACCEPTED", doc.get("verdict"));
        assertEquals("PRM-0000000001", v.get("merchantNumber"));
    }

    @Test
    void staffSeeReviewersAndWhereTheFileLives() {
        Map<String, Object> v = OnboardingViews.application(sample(), true, List.of("TAX_COMPLIANCE"), null);
        assertEquals("FINANCE", ((Map<?, ?>) v.get("reviewer")).get("role"));
        Map<?, ?> doc = (Map<?, ?>) ((List<?>) v.get("documents")).get(0);
        assertEquals("https://drive.example/x", doc.get("driveViewLink"));
        assertEquals("fin-1", doc.get("verdictBy"));
        assertFalse(v.containsKey("missing"));
    }

    @Test
    void readyToSubmitOnlyWhenNothingIsMissingAndEditable() {
        OnboardingApplication a = sample();
        a.setStatus(OnboardingStatus.DRAFT);
        assertEquals(true, OnboardingViews.application(a, false, List.of(), List.of()).get("readyToSubmit"));
        assertEquals(false, OnboardingViews.application(a, false, List.of(),
                List.of(Map.of("field", "taxPin", "issue", "is required"))).get("readyToSubmit"));
        a.setStatus(OnboardingStatus.SUBMITTED);
        assertEquals(false, OnboardingViews.application(a, false, List.of(), List.of()).get("readyToSubmit"));
    }

    @Test
    void merchantViewShowsTheMerchantNumber() {
        MerchantProfile p = new MerchantProfile();
        p.setMerchantId("mer_1");
        p.setMerchantNumber("PRM-0000000001");
        assertEquals("PRM-0000000001", Views.merchant(p).get("merchantNumber"));
    }

    @Test
    void theApplicantTimelineNamesNoStaffMember() {
        OnboardingEvent e = new OnboardingEvent();
        e.setEventId("oev_1");
        e.setType(OnboardingEventType.REVIEW_STARTED);
        e.setActorType(ActorType.STAFF);
        e.setActorId("fin-1");
        e.setActorName("Fiona");
        e.setActorRole("FINANCE");
        e.setAt(Instant.parse("2026-10-16T09:00:00Z"));
        Map<String, Object> applicant = OnboardingViews.event(e, false);
        assertEquals("Premisave review team", applicant.get("by"));
        assertFalse(applicant.containsKey("actor"));
        Map<String, Object> staff = OnboardingViews.event(e, true);
        assertEquals("Fiona", ((Map<?, ?>) staff.get("actor")).get("name"));
    }
}
