package com.premisave.wallet.commercial.onboarding;

import com.premisave.wallet.commercial.entity.MerchantProfile;
import com.premisave.wallet.commercial.enums.Environment;
import com.premisave.wallet.commercial.enums.ErrorCode;
import com.premisave.wallet.commercial.enums.MerchantStatus;
import com.premisave.wallet.commercial.exception.CommercialException;
import com.premisave.wallet.commercial.repository.MerchantProfileRepository;
import com.premisave.wallet.commercial.repository.OnboardingApplicationRepository;
import com.premisave.wallet.commercial.repository.OnboardingEventRepository;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What the owner of a wallet sees of the applications that name it. */
class OwnerApplicationServiceTest {

    private final Map<String, OnboardingApplication> applicationStore = new HashMap<>();
    private final Map<String, MerchantProfile> merchantStore = new HashMap<>();
    private final List<OnboardingEvent> eventStore = new ArrayList<>();
    private final OwnerApplicationService service;

    OwnerApplicationServiceTest() {
        OnboardingApplicationRepository applications = Fake.of(OnboardingApplicationRepository.class, Map.of(
                "findByLinkedWalletAccountOrderByCreatedAtDesc", a -> applicationStore.values().stream()
                        .filter(x -> a[0].equals(x.getLinkedWalletAccount()))
                        .sorted(Comparator.comparing(OnboardingApplication::getCreatedAt).reversed()).toList()));
        MerchantProfileRepository merchants = Fake.of(MerchantProfileRepository.class, Map.of(
                "findById", a -> Optional.ofNullable(merchantStore.get((String) a[0]))));
        OnboardingEventRepository events = Fake.of(OnboardingEventRepository.class, Map.of(
                "findByApplicationIdOrderByAtAsc", a -> eventStore.stream().filter(e -> e.getApplicationId().equals(a[0])).toList()));
        service = new OwnerApplicationService(applications, merchants, new OnboardingTimeline(events));
    }

    private OnboardingApplication application(String id, String merchantId, String number, String wallet,
                                              OnboardingStatus status, long createdSeconds) {
        OnboardingApplication a = new OnboardingApplication();
        a.setApplicationId(id);
        a.setMerchantId(merchantId);
        a.setMerchantNumber(number);
        a.setStatus(status);
        a.setLinkedWalletAccount(wallet);
        a.setCreatedAt(Instant.parse("2026-10-01T00:00:00Z").plusSeconds(createdSeconds));
        a.setUpdatedAt(a.getCreatedAt());
        BusinessForm f = a.getForm();
        f.setRegisteredName("Biz " + id);
        f.setCountry("KE");
        f.setTaxPin("P051234567Z");
        f.setContactName("Jane Doe");
        Director d = new Director();
        d.setFullName("Jane Doe");
        d.setIdNumber("12345678");
        d.setRole("Director");
        d.setOwnershipPercent(100);
        f.getDirectors().add(d);
        applicationStore.put(id, a);
        MerchantProfile p = new MerchantProfile();
        p.setMerchantId(merchantId);
        p.setMerchantNumber(number);
        p.setStatusFor(Environment.LIVE, status == OnboardingStatus.APPROVED ? MerchantStatus.ACTIVE : MerchantStatus.PENDING_APPROVAL);
        merchantStore.put(merchantId, p);
        return a;
    }

    private void event(OnboardingApplication a, OnboardingEventType type, String message, boolean visible) {
        OnboardingEvent e = new OnboardingEvent();
        e.setEventId("e" + eventStore.size());
        e.setApplicationId(a.getApplicationId());
        e.setType(type);
        e.setActorType(ActorType.SYSTEM);
        e.setMessage(message);
        e.setVisibleToApplicant(visible);
        e.setAt(Instant.parse("2026-10-02T00:00:00Z").plusSeconds(eventStore.size()));
        eventStore.add(e);
    }

    @Test
    @SuppressWarnings("unchecked")
    void theOwnerSeesOnlyApplicationsThatNameTheirWalletNewestFirst() {
        application("onb_1", "mer_1", "PRM-0000000001", "owner@acme.test", OnboardingStatus.IN_REVIEW, 0);
        application("onb_2", "mer_2", "PRM-0000000002", "owner@acme.test", OnboardingStatus.DRAFT, 10);
        application("onb_3", "mer_3", "PRM-0000000003", "other@acme.test", OnboardingStatus.DRAFT, 20);
        Map<String, Object> out = service.list("  Owner@Acme.test ");
        assertEquals(2, out.get("count"));
        List<Map<String, Object>> rows = (List<Map<String, Object>>) out.get("applications");
        assertEquals("onb_2", rows.get(0).get("applicationId"));
        assertEquals("onb_1", rows.get(1).get("applicationId"));
        assertEquals(0, ((Map<?, ?>) service.list("nobody@acme.test")).get("count"));
        assertEquals(0, ((Map<?, ?>) service.list(null)).get("count"));
    }

    @Test
    void theMerchantNumberStaysHiddenUntilTheApplicationIsApproved() {
        application("onb_1", "mer_1", "PRM-0000000001", "owner@acme.test", OnboardingStatus.IN_REVIEW, 0);
        application("onb_2", "mer_2", "PRM-0000000002", "owner@acme.test", OnboardingStatus.APPROVED, 10);
        assertNull(service.get("owner@acme.test", "onb_1").get("merchantNumber"));
        assertEquals("PRM-0000000002", service.get("owner@acme.test", "onb_2").get("merchantNumber"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void theDetailsHideIdNumbersAndMaskTheTaxPin() {
        application("onb_1", "mer_1", "PRM-0000000001", "owner@acme.test", OnboardingStatus.SUBMITTED, 0);
        Map<String, Object> v = service.get("owner@acme.test", "onb_1");
        Map<String, Object> form = (Map<String, Object>) v.get("form");
        assertEquals(11, ((String) form.get("taxPin")).length());
        assertTrue(((String) form.get("taxPin")).endsWith("67Z"));
        assertFalse(((String) form.get("taxPin")).contains("P05"));
        Map<String, Object> director = ((List<Map<String, Object>>) form.get("directors")).get(0);
        assertEquals("Jane Doe", director.get("fullName"));
        assertFalse(director.containsKey("idNumber"));
        assertEquals("SUBMITTED", ((Map<String, Object>) v.get("progress")).get("currentStep"));
    }

    @Test
    void anApplicationThatDoesNotNameTheWalletIsNotFound() {
        application("onb_3", "mer_3", "PRM-0000000003", "other@acme.test", OnboardingStatus.DRAFT, 0);
        CommercialException e = assertThrows(CommercialException.class, () -> service.get("owner@acme.test", "onb_3"));
        assertEquals(ErrorCode.APPLICATION_NOT_FOUND, e.code());
        assertThrows(CommercialException.class, () -> service.get("owner@acme.test", "onb_missing"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void theTimelineShowsWhatTheApplicantMaySeeWithoutEmailEntries() {
        OnboardingApplication a = application("onb_1", "mer_1", "PRM-0000000001", "owner@acme.test", OnboardingStatus.SUBMITTED, 0);
        event(a, OnboardingEventType.SUBMITTED, "Application submitted", true);
        event(a, OnboardingEventType.EMAIL_SENT, "Email sent to owner@acme.test", true);
        event(a, OnboardingEventType.NOTE_ADDED, "internal note", false);
        List<Map<String, Object>> tl = (List<Map<String, Object>>) service.get("owner@acme.test", "onb_1").get("timeline");
        assertEquals(1, tl.size());
        assertEquals("SUBMITTED", tl.get(0).get("type"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void merchantNumbersListsOnlyApprovedApplicationsWithTheirProductionStatus() {
        application("onb_1", "mer_1", "PRM-0000000001", "owner@acme.test", OnboardingStatus.IN_REVIEW, 0);
        OnboardingApplication ok = application("onb_2", "mer_2", "PRM-0000000002", "owner@acme.test", OnboardingStatus.APPROVED, 10);
        ok.setDecidedAt(Instant.parse("2026-10-05T10:00:00Z"));
        Map<String, Object> out = service.merchantNumbers("owner@acme.test");
        assertEquals(1, out.get("count"));
        Map<String, Object> row = ((List<Map<String, Object>>) out.get("merchantNumbers")).get(0);
        assertEquals("PRM-0000000002", row.get("merchantNumber"));
        assertEquals("Biz onb_2", row.get("businessName"));
        assertEquals("ACTIVE", row.get("productionStatus"));
        assertEquals("2026-10-05T10:00:00Z", String.valueOf(row.get("approvedAt")).replace(".000Z", "Z"));
    }

    @Test
    void maskingKeepsOnlyTheLastCharacters() {
        assertEquals("********67Z", OwnerApplicationService.mask("P051234567Z"));
        assertEquals("***", OwnerApplicationService.mask("AB12"));
        assertNull(OwnerApplicationService.mask(" "));
        assertNull(OwnerApplicationService.mask(null));
    }
}