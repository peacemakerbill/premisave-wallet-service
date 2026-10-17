package com.premisave.wallet.commercial.onboarding;

import com.premisave.wallet.commercial.config.CommercialOnboardingProperties;
import com.premisave.wallet.commercial.entity.MerchantProfile;
import com.premisave.wallet.commercial.enums.Environment;
import com.premisave.wallet.commercial.enums.ErrorCode;
import com.premisave.wallet.commercial.enums.MerchantStatus;
import com.premisave.wallet.commercial.exception.CommercialException;
import com.premisave.wallet.commercial.repository.MerchantProfileRepository;
import com.premisave.wallet.commercial.repository.OnboardingApplicationRepository;
import com.premisave.wallet.commercial.repository.OnboardingEventRepository;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Walks an application through the whole life cycle with in-memory repositories and storage. */
class OnboardingFlowTest {

    private static final byte[] PDF = "%PDF-1.7 sample document".getBytes();

    private final Map<String, OnboardingApplication> applicationStore = new HashMap<>();
    private final Map<String, MerchantProfile> merchantStore = new HashMap<>();
    private final List<OnboardingEvent> eventStore = new ArrayList<>();
    private final Map<String, byte[]> drive = new HashMap<>();
    private final List<String> toApplicant = new ArrayList<>();
    private final List<String> toStaff = new ArrayList<>();
    /** Wallets by account number, true when frozen. */
    private final Map<String, Boolean> walletStore = new HashMap<>();

    private final OnboardingService service;
    private final OnboardingReviewService review;
    private final MerchantProfile merchant;

    private static final Actor OWNER = Actor.applicant("owner-1");
    private static final Actor FINANCE = Actor.staff("fin-1", "Fiona", "FINANCE");
    private static final Actor OPS = Actor.staff("ops-1", "Omar", "OPERATIONS");
    private static final Actor ADMIN = Actor.staff("adm-1", "Ada", "ADMIN");

    OnboardingFlowTest() {
        OnboardingApplicationRepository applications = Fake.of(OnboardingApplicationRepository.class, Map.of(
                "insert", a -> store((OnboardingApplication) a[0]),
                "save", a -> store((OnboardingApplication) a[0]),
                "findById", a -> Optional.ofNullable(applicationStore.get((String) a[0])),
                "findByMerchantId", a -> applicationStore.values().stream().filter(x -> x.getMerchantId().equals(a[0])).findFirst(),
                "findByMerchantNumber", a -> applicationStore.values().stream().filter(x -> x.getMerchantNumber().equals(a[0])).findFirst()));
        MerchantProfileRepository merchants = Fake.of(MerchantProfileRepository.class, Map.of(
                "findById", a -> Optional.ofNullable(merchantStore.get((String) a[0])),
                "save", a -> {
                    MerchantProfile p = (MerchantProfile) a[0];
                    merchantStore.put(p.getMerchantId(), p);
                    return p;
                }));
        OnboardingEventRepository events = Fake.of(OnboardingEventRepository.class, Map.of(
                "insert", a -> {
                    eventStore.add((OnboardingEvent) a[0]);
                    return a[0];
                },
                "findByApplicationIdOrderByAtAsc", a -> eventStore.stream().filter(e -> e.getApplicationId().equals(a[0])).toList()));

        CommercialOnboardingProperties props = new CommercialOnboardingProperties(null,
                List.of("BUSINESS_REGISTRATION", "TAX_COMPLIANCE"), null, null, List.of("review-queue@premisave.test"),
                null, null, null);
        OnboardingTimeline timeline = new OnboardingTimeline(events);
        OnboardingNotifier notifier = new OnboardingNotifier(null, props, timeline) {
            @Override
            public void toApplicant(OnboardingApplication app, String to, OnboardingEmails.Email email) {
                toApplicant.add(to + " | " + email.subject());
            }

            @Override
            public void toStaff(OnboardingApplication app, OnboardingEmails.Email email) {
                toStaff.add(email.subject());
            }
        };
        DocumentStorage storage = new DocumentStorage() {
            @Override
            public StoredFile upload(String fileName, String contentType, byte[] bytes) {
                String id = "file" + (drive.size() + 1) + "abcdefghij";
                drive.put(id, bytes);
                return new StoredFile(id, "https://drive.example/" + id);
            }

            @Override
            public byte[] download(String fileId) {
                return drive.get(fileId);
            }

            @Override
            public void delete(String fileId) {
                drive.remove(fileId);
            }

            @Override
            public boolean available() {
                return true;
            }
        };
        WalletDirectory wallets = account -> account == null || !walletStore.containsKey(account.toLowerCase())
                ? Optional.empty()
                : Optional.of(new WalletDirectory.WalletRef("wal_" + account.toLowerCase(), account.toLowerCase(),
                        walletStore.get(account.toLowerCase())));
        service = new OnboardingService(applications, merchants, props, storage, timeline, notifier, new MerchantNumberService(null), wallets);
        review = new OnboardingReviewService(applications, merchants, props, new RolePolicy(props), timeline, notifier, service);

        merchant = new MerchantProfile();
        merchant.setMerchantId("mer_1");
        merchant.setMerchantNumber("PRM-0000000001");
        merchant.setBusinessName("Acme Traders Ltd");
        merchant.setCountry("KE");
        merchant.setContactEmail("owner@acme.test");
        merchantStore.put("mer_1", merchant);
        walletStore.put("owner@acme.test", false);
        walletStore.put("frozen@acme.test", true);
        walletStore.put("someone@else.test", false);
        service.createFor(merchant);
    }

    private OnboardingApplication store(OnboardingApplication a) {
        applicationStore.put(a.getApplicationId(), a);
        return a;
    }

    private OnboardingApplication app() {
        return app("mer_1");
    }

    private OnboardingApplication app(String merchantId) {
        return applicationStore.values().stream().filter(a -> a.getMerchantId().equals(merchantId)).findFirst().orElseThrow();
    }

    private void fillForm() {
        service.saveForm("mer_1", new OnboardingFormRequest(BusinessType.LIMITED_COMPANY, null, null, "CPR/2020/1234",
                "P051234567Z", null, "Nairobi", "Moi Avenue 12", "https://acme.test", "Retail", "Sells goods online",
                25_000L, "Jane Doe", null, "254712345678",
                List.of(new DirectorDto("Jane Doe", "12345678", "Director", 100)), "Owner@Acme.test", null, null), OWNER);
    }

    private String upload(String type) {
        Map<String, Object> d = service.uploadDocument("mer_1", type, "doc.pdf", "application/pdf", PDF, OWNER);
        return (String) d.get("documentId");
    }

    private void readyAndSubmitted() {
        fillForm();
        upload("BUSINESS_REGISTRATION");
        upload("TAX_COMPLIANCE");
        service.submit("mer_1", null, OWNER);
    }

    private static ErrorCode codeOf(Runnable r) {
        return assertThrows(CommercialException.class, r::run).code();
    }

    @Test
    void anApplicationStartsAsADraftPrefilledFromTheMerchant() {
        OnboardingApplication a = app();
        assertEquals(OnboardingStatus.DRAFT, a.getStatus());
        assertEquals("PRM-0000000001", a.getMerchantNumber());
        assertEquals("Acme Traders Ltd", a.getForm().getRegisteredName());
        assertEquals("owner@acme.test", a.getForm().getContactEmail());
        assertEquals(1, eventStore.size());
        assertEquals(OnboardingEventType.APPLICATION_CREATED, eventStore.get(0).getType());
    }

    @Test
    void anExistingMerchantWithoutAnApplicationCanStartOne() {
        MerchantProfile old = new MerchantProfile();
        old.setMerchantId("mer_old");
        old.setMerchantNumber("PRM-0000000002");
        old.setBusinessName("Old Co");
        old.setCountry("KE");
        old.setContactEmail("old@co.test");
        merchantStore.put("mer_old", old);

        OnboardingService.Started first = service.start("mer_old");
        assertTrue(first.created());
        assertEquals("DRAFT", first.application().get("status"));
        assertEquals("PRM-0000000002", first.application().get("merchantNumber"));
        OnboardingService.Started again = service.start("mer_old");
        assertFalse(again.created(), "a second call returns the same application");
        assertEquals(first.application().get("applicationId"), again.application().get("applicationId"));
        assertEquals(ErrorCode.MERCHANT_NOT_FOUND, codeOf(() -> service.start("mer_missing")));
    }

    @Test
    @SuppressWarnings("unchecked")
    void theShortSummaryShowsWhatIsStillNeededAndWhenTheApplicationCanBeSent() {
        Map<String, Object> draft = service.applicantSummary(app());
        assertEquals("DRAFT", draft.get("status"));
        assertEquals(false, draft.get("readyToSubmit"));
        List<Map<String, String>> missing = (List<Map<String, String>>) draft.get("missing");
        assertTrue(missing.stream().anyMatch(m -> "taxPin".equals(m.get("field"))));

        fillForm();
        upload("BUSINESS_REGISTRATION");
        upload("TAX_COMPLIANCE");
        Map<String, Object> ready = service.applicantSummary(app());
        assertEquals(true, ready.get("readyToSubmit"));
        assertTrue(((List<?>) ready.get("missing")).isEmpty());

        service.submit("mer_1", null, OWNER);
        Map<String, Object> sent = service.applicantSummary(app());
        assertEquals("SUBMITTED", sent.get("status"));
        assertEquals(false, sent.get("readyToSubmit"), "nothing more to send once it is with the reviewer");
    }

    @Test
    void eachBusinessOfAnAccountHasItsOwnApplication() {
        MerchantProfile second = new MerchantProfile();
        second.setMerchantId("mer_2");
        second.setMerchantNumber("PRM-0000000002");
        second.setBusinessName("Acme Logistics Ltd");
        second.setCountry("UG");
        second.setContactEmail("owner@acme.test");
        merchantStore.put("mer_2", second);
        OnboardingApplication other = service.createFor(second);

        service.saveForm("mer_2", new OnboardingFormRequest(BusinessType.PARTNERSHIP, null, null, "REG-2", "P0999",
                null, "Kampala", null, null, null, null, null, null, null, null, null), OWNER);

        assertEquals(2, applicationStore.size());
        assertEquals("Acme Traders Ltd", app("mer_1").getForm().getRegisteredName());
        assertEquals("Acme Logistics Ltd", other.getForm().getRegisteredName());
        assertNull(app("mer_1").getForm().getTaxPin());
        assertEquals("P0999", app("mer_2").getForm().getTaxPin());
        assertEquals("UG", app("mer_2").getForm().getCountry());
        readyAndSubmitted();
        assertEquals(OnboardingStatus.SUBMITTED, app("mer_1").getStatus());
        assertEquals(OnboardingStatus.DRAFT, app("mer_2").getStatus(), "submitting one does not submit the other");
    }

    @Test
    void submittingAnIncompleteApplicationListsWhatIsMissing() {
        CommercialException e = assertThrows(CommercialException.class, () -> service.submit("mer_1", null, OWNER));
        assertEquals(ErrorCode.APPLICATION_INCOMPLETE, e.code());
        assertTrue(e.details().stream().anyMatch(d -> "taxPin".equals(d.get("field"))));
        assertTrue(e.details().stream().anyMatch(d -> "document:TAX_COMPLIANCE".equals(d.get("field"))));
        assertEquals(OnboardingStatus.DRAFT, app().getStatus());
    }

    @Test
    void aSubmittedApplicationIsLockedAndBothSidesAreEmailed() {
        readyAndSubmitted();
        assertEquals(OnboardingStatus.SUBMITTED, app().getStatus());
        assertEquals(1, app().getSubmissions());
        assertEquals(1, toApplicant.size());
        assertTrue(toApplicant.get(0).startsWith("owner@acme.test"));
        assertEquals(1, toStaff.size());
        assertEquals(ErrorCode.INVALID_STATE, codeOf(() -> fillForm()));
        assertEquals(ErrorCode.INVALID_STATE, codeOf(() -> upload("DIRECTOR_ID")));
        assertEquals(ErrorCode.INVALID_STATE, codeOf(() -> service.submit("mer_1", null, OWNER)));
    }

    @Test
    void uploadsAreCheckedBeforeAnythingReachesStorage() {
        assertEquals(ErrorCode.VALIDATION_FAILED, codeOf(() -> service.uploadDocument("mer_1", "PASSPORT", "a.pdf", "application/pdf", PDF, OWNER)));
        assertEquals(ErrorCode.VALIDATION_FAILED, codeOf(() -> service.uploadDocument("mer_1", "TAX_COMPLIANCE", "a.gif", "image/gif", PDF, OWNER)));
        assertEquals(ErrorCode.VALIDATION_FAILED, codeOf(() -> service.uploadDocument("mer_1", "TAX_COMPLIANCE", "a.pdf", "application/pdf", "<html>not a pdf</html>".getBytes(), OWNER)));
        assertEquals(ErrorCode.VALIDATION_FAILED, codeOf(() -> service.uploadDocument("mer_1", "TAX_COMPLIANCE", "a.pdf", "application/pdf", new byte[0], OWNER)));
        assertEquals(ErrorCode.VALIDATION_FAILED, codeOf(() -> service.uploadDocument("mer_1", "TAX_COMPLIANCE", "a.pdf", "application/pdf", new byte[6 * 1024 * 1024], OWNER)));
        assertTrue(drive.isEmpty());
    }

    @Test
    void anUploadedDocumentCanBeReadBackAndRemoved() {
        String id = upload("TAX_COMPLIANCE");
        assertEquals(1, drive.size());
        OnboardingService.DocumentFile f = service.download("mer_1", id);
        assertEquals("application/pdf", f.contentType());
        assertEquals(PDF.length, f.bytes().length);
        assertEquals(1, app().getDocuments().size());
        assertEquals(64, app().getDocuments().get(0).getSha256().length());
        service.removeDocument("mer_1", id, OWNER);
        assertTrue(app().getDocuments().isEmpty());
        assertTrue(drive.isEmpty());
        assertEquals(ErrorCode.NOT_FOUND, codeOf(() -> service.removeDocument("mer_1", id, OWNER)));
    }

    @Test
    void theApplicantViewHidesStaffIdentitiesAndInternalNotes() {
        readyAndSubmitted();
        review.startReview(app().getApplicationId(), FINANCE);
        review.addNote(app().getApplicationId(), "Looks like a shell company, double check", OPS);

        Map<String, Object> applicant = service.get("mer_1");
        assertFalse(applicant.containsKey("reviewer"));
        Map<String, Object> staff = review.get(app().getApplicationId());
        assertEquals("Fiona", ((Map<?, ?>) staff.get("reviewer")).get("name"));

        List<Map<String, Object>> applicantEvents = service.timeline("mer_1");
        assertTrue(applicantEvents.stream().noneMatch(e -> String.valueOf(e.get("message")).contains("shell company")));
        assertTrue(applicantEvents.stream().noneMatch(e -> e.containsKey("actor")));
        assertTrue(applicantEvents.stream().anyMatch(e -> "Premisave review team".equals(e.get("by"))));
        assertTrue(review.timeline(app().getApplicationId()).stream()
                .anyMatch(e -> String.valueOf(e.get("message")).contains("shell company")));
    }

    @Test
    void reviewStepsNeedTheRightRole() {
        readyAndSubmitted();
        String id = app().getApplicationId();
        assertEquals(ErrorCode.UNAUTHENTICATED, codeOf(() -> review.startReview(id, OWNER)));
        assertEquals(ErrorCode.ROLE_NOT_PERMITTED, codeOf(() -> review.startReview(id, Actor.staff("x", null, "SUPPORT"))));
        review.startReview(id, OPS);
        assertEquals(ErrorCode.ROLE_NOT_PERMITTED, codeOf(() -> review.approve(id, null, OPS)));
        assertEquals(ErrorCode.ROLE_NOT_PERMITTED, codeOf(() -> review.reopen(id, new DecisionRequest("x"), FINANCE)));
    }

    @Test
    void returnedApplicationsGoBackWithMessagesAndCanBeFixedAndResubmitted() {
        readyAndSubmitted();
        String id = app().getApplicationId();
        review.startReview(id, OPS);
        toApplicant.clear();

        assertEquals(ErrorCode.VALIDATION_FAILED, codeOf(() -> review.returnForCorrection(id,
                new ReturnApplicationRequest("Please fix", List.of()), OPS)));
        assertEquals(ErrorCode.VALIDATION_FAILED, codeOf(() -> review.returnForCorrection(id,
                new ReturnApplicationRequest("Please fix", List.of(new ReturnItemRequest("not a field", "x"))), OPS)));

        review.returnForCorrection(id, new ReturnApplicationRequest("Two things to fix", List.of(
                new ReturnItemRequest("taxPin", "The PIN does not match the certificate"),
                new ReturnItemRequest("document:TAX_COMPLIANCE", "The scan is blurred"))), OPS);
        assertEquals(OnboardingStatus.RETURNED, app().getStatus());
        assertEquals(2, app().getReturnItems().size());
        assertEquals(1, toApplicant.size());
        assertTrue(toApplicant.get(0).contains("Action needed"));
        assertEquals(2, ((List<?>) service.get("mer_1").get("returnItems")).size());

        service.saveForm("mer_1", new OnboardingFormRequest(null, null, null, null, "P059999999A", null, null, null,
                null, null, null, null, null, null, null, null), OWNER);
        String firstItem = app().getReturnItems().get(0).getItemId();
        assertEquals(ErrorCode.VALIDATION_FAILED, codeOf(() -> service.submit("mer_1",
                new SubmitApplicationRequest(null, List.of(new ItemResponse("itm_unknown", "x"))), OWNER)));
        service.submit("mer_1", new SubmitApplicationRequest("Fixed the PIN and rescanned",
                List.of(new ItemResponse(firstItem, "Corrected the PIN"))), OWNER);

        assertEquals(OnboardingStatus.SUBMITTED, app().getStatus());
        assertEquals(2, app().getSubmissions());
        assertTrue(app().getReturnItems().stream().allMatch(ReturnItem::isAddressed));
        assertEquals("Corrected the PIN", app().getReturnItems().get(0).getResponse());
        assertTrue(eventStore.stream().anyMatch(e -> e.getType() == OnboardingEventType.RESUBMITTED));
        assertNull(app().getReviewerId());
    }

    @Test
    void approvalNeedsAcceptedDocumentsThenSwitchesProductionOn() {
        readyAndSubmitted();
        String id = app().getApplicationId();
        assertEquals(ErrorCode.INVALID_STATE, codeOf(() -> review.approve(id, null, FINANCE)));
        review.startReview(id, FINANCE);
        assertEquals(ErrorCode.APPLICATION_INCOMPLETE, codeOf(() -> review.approve(id, null, FINANCE)));

        assertEquals(MerchantStatus.PENDING_APPROVAL, merchant.statusFor(Environment.LIVE));
        for (OnboardingDocument d : app().getDocuments()) {
            review.documentVerdict(id, d.getDocumentId(), new DocumentVerdictRequest(DocumentVerdict.ACCEPTED, null), FINANCE);
        }
        toApplicant.clear();
        review.approve(id, new DecisionRequest("Welcome to Premisave"), FINANCE);

        assertEquals(OnboardingStatus.APPROVED, app().getStatus());
        assertEquals(MerchantStatus.ACTIVE, merchant.statusFor(Environment.LIVE));
        assertEquals("fin-1", app().getDecisionBy());
        assertEquals(1, toApplicant.size());
        assertTrue(toApplicant.get(0).contains("approved"));
        assertTrue(eventStore.stream().anyMatch(e -> e.getType() == OnboardingEventType.PRODUCTION_ENABLED));
        assertEquals(ErrorCode.INVALID_STATE, codeOf(() -> review.reject(id, new DecisionRequest("changed my mind"), ADMIN)));
    }

    @Test
    void approvalIsRefusedWhenProductionIsSuspended() {
        readyAndSubmitted();
        String id = app().getApplicationId();
        review.startReview(id, FINANCE);
        for (OnboardingDocument d : app().getDocuments()) {
            review.documentVerdict(id, d.getDocumentId(), new DocumentVerdictRequest(DocumentVerdict.ACCEPTED, null), FINANCE);
        }
        merchant.setStatusFor(Environment.LIVE, MerchantStatus.SUSPENDED);
        assertEquals(ErrorCode.INVALID_STATE, codeOf(() -> review.approve(id, null, FINANCE)));
        assertEquals(OnboardingStatus.IN_REVIEW, app().getStatus());
    }

    @Test
    void aRejectedDocumentNeedsANoteAndStopsApproval() {
        readyAndSubmitted();
        String id = app().getApplicationId();
        review.startReview(id, FINANCE);
        String docId = app().getDocuments().get(0).getDocumentId();
        assertEquals(ErrorCode.VALIDATION_FAILED, codeOf(() -> review.documentVerdict(id, docId,
                new DocumentVerdictRequest(DocumentVerdict.REJECTED, " "), FINANCE)));
        review.documentVerdict(id, docId, new DocumentVerdictRequest(DocumentVerdict.REJECTED, "Expired"), FINANCE);
        assertEquals(ErrorCode.APPLICATION_INCOMPLETE, codeOf(() -> review.approve(id, null, FINANCE)));
        Map<?, ?> view = (Map<?, ?>) ((List<?>) review.get(id).get("documents")).get(0);
        assertEquals("REJECTED", view.get("verdict"));
        assertEquals("Expired", view.get("verdictNote"));
    }

    @Test
    void rejectionNeedsAReasonAndOnlyAnAdminCanReopen() {
        readyAndSubmitted();
        String id = app().getApplicationId();
        assertEquals(ErrorCode.VALIDATION_FAILED, codeOf(() -> review.reject(id, new DecisionRequest(" "), FINANCE)));
        toApplicant.clear();
        review.reject(id, new DecisionRequest("Business type is not supported"), FINANCE);
        assertEquals(OnboardingStatus.REJECTED, app().getStatus());
        assertEquals("Business type is not supported", app().getDecisionMessage());
        assertTrue(toApplicant.get(0).contains("Update on your merchant application"));
        assertEquals(MerchantStatus.PENDING_APPROVAL, merchant.statusFor(Environment.LIVE));
        assertEquals(ErrorCode.INVALID_STATE, codeOf(() -> fillForm()));

        assertEquals(ErrorCode.ROLE_NOT_PERMITTED, codeOf(() -> review.reopen(id, new DecisionRequest("second look"), FINANCE)));
        review.reopen(id, new DecisionRequest("Appeal accepted"), ADMIN);
        assertEquals(OnboardingStatus.IN_REVIEW, app().getStatus());
        assertEquals("adm-1", app().getReviewerId());
        assertNull(app().getDecisionMessage());
    }

    @Test
    void everyStepLeavesATimelineEntryInOrder() {
        readyAndSubmitted();
        String id = app().getApplicationId();
        review.startReview(id, FINANCE);
        review.returnForCorrection(id, new ReturnApplicationRequest("Fix", List.of(new ReturnItemRequest("taxPin", "wrong"))), FINANCE);
        List<OnboardingEventType> types = eventStore.stream().map(OnboardingEvent::getType).toList();
        assertEquals(OnboardingEventType.APPLICATION_CREATED, types.get(0));
        assertTrue(types.contains(OnboardingEventType.FORM_SAVED));
        assertEquals(2, types.stream().filter(t -> t == OnboardingEventType.DOCUMENT_UPLOADED).count());
        assertTrue(types.indexOf(OnboardingEventType.SUBMITTED) < types.indexOf(OnboardingEventType.REVIEW_STARTED));
        assertTrue(types.indexOf(OnboardingEventType.REVIEW_STARTED) < types.indexOf(OnboardingEventType.RETURNED));
        for (OnboardingEvent e : eventStore) {
            assertNotNull(e.getEventId());
            assertNotNull(e.getAt());
            assertEquals("mer_1", e.getMerchantId());
        }
        OnboardingEvent returned = eventStore.stream().filter(e -> e.getType() == OnboardingEventType.RETURNED).findFirst().orElseThrow();
        assertEquals(OnboardingStatus.IN_REVIEW, returned.getFromStatus());
        assertEquals(OnboardingStatus.RETURNED, returned.getToStatus());
        assertEquals("FINANCE", returned.getActorRole());
    }

    @Test
    void startingReviewTwiceBySomeoneElseIsRefused() {
        readyAndSubmitted();
        String id = app().getApplicationId();
        review.startReview(id, FINANCE);
        review.startReview(id, FINANCE);
        assertEquals(ErrorCode.INVALID_STATE, codeOf(() -> review.startReview(id, OPS)));
    }

    private static OnboardingFormRequest walletOnly(String account) {
        return new OnboardingFormRequest(null, null, null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, account, null, null);
    }

    @Test
    void theFormLinksAnExistingWalletByItsAccountNumberInLowerCase() {
        service.saveForm("mer_1", walletOnly(" Someone@Else.test "), OWNER);
        assertEquals("someone@else.test", app().getForm().getWalletAccountNumber());
        assertEquals("someone@else.test", app().getLinkedWalletAccount());
        assertEquals("wal_someone@else.test", app().getLinkedWalletId());
    }

    @Test
    void anUnknownOrFrozenWalletCannotBeLinked() {
        assertEquals(ErrorCode.VALIDATION_FAILED, codeOf(() -> service.saveForm("mer_1", walletOnly("nobody@nowhere.test"), OWNER)));
        assertEquals(ErrorCode.VALIDATION_FAILED, codeOf(() -> service.saveForm("mer_1", walletOnly("frozen@acme.test"), OWNER)));
        assertNull(app().getLinkedWalletAccount());
    }

    @Test
    void aBlankAccountNumberUnlinksTheWalletAndLeavingItOutKeepsIt() {
        service.saveForm("mer_1", walletOnly("owner@acme.test"), OWNER);
        service.saveForm("mer_1", walletOnly(null), OWNER);
        assertEquals("owner@acme.test", app().getLinkedWalletAccount());
        service.saveForm("mer_1", walletOnly(" "), OWNER);
        assertNull(app().getLinkedWalletAccount());
        assertNull(app().getForm().getWalletAccountNumber());
    }

    @Test
    void theWalletToLinkIsRequiredToSubmit() {
        fillForm();
        upload("BUSINESS_REGISTRATION");
        upload("TAX_COMPLIANCE");
        service.saveForm("mer_1", walletOnly(" "), OWNER);
        CommercialException e = assertThrows(CommercialException.class, () -> service.submit("mer_1", null, OWNER));
        assertEquals(ErrorCode.APPLICATION_INCOMPLETE, e.code());
        assertTrue(e.details().stream().anyMatch(m -> "walletAccountNumber".equals(m.get("field"))));
    }

    @Test
    @SuppressWarnings("unchecked")
    void applyingOnBehalfNeedsARoleAndAnAuthorisationLetter() {
        fillForm();
        upload("BUSINESS_REGISTRATION");
        upload("TAX_COMPLIANCE");
        assertTrue(((List<?>) service.applicantSummary(app()).get("missing")).isEmpty());

        service.saveForm("mer_1", new OnboardingFormRequest(null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, true, null), OWNER);
        List<Map<String, String>> missing = (List<Map<String, String>>) service.applicantSummary(app()).get("missing");
        assertTrue(missing.stream().anyMatch(m -> "applicantRole".equals(m.get("field"))));
        assertTrue(missing.stream().anyMatch(m -> "document:AUTHORISATION_LETTER".equals(m.get("field"))));

        service.saveForm("mer_1", new OnboardingFormRequest(null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, " Accountant "), OWNER);
        upload("AUTHORISATION_LETTER");
        assertEquals("Accountant", app().getForm().getApplicantRole());
        assertTrue(((List<?>) service.applicantSummary(app()).get("missing")).isEmpty());
        assertEquals(true, service.applicantSummary(app()).get("readyToSubmit"));
    }

    @Test
    void theSummaryCarriesTheWalletAndContactForTheDeveloperService() {
        fillForm();
        Map<String, Object> s = service.applicantSummary(app());
        assertEquals("owner@acme.test", s.get("walletAccount"));
        assertEquals("owner@acme.test", s.get("contactEmail"));
    }

    @Test
    void theWalletOwnerIsToldWhenAnApplicationNamesTheirWalletAndOnlyWhenItIsNotTheApplicantsOwnAddress() {
        fillForm();
        upload("BUSINESS_REGISTRATION");
        upload("TAX_COMPLIANCE");
        service.submit("mer_1", null, OWNER);
        assertEquals(1, toApplicant.size(), "the wallet is the contact's own address, so one email only");

        MerchantProfile second = new MerchantProfile();
        second.setMerchantId("mer_2");
        second.setMerchantNumber("PRM-0000000002");
        second.setBusinessName("Agency Client Ltd");
        second.setCountry("KE");
        second.setContactEmail("agent@agency.test");
        merchantStore.put("mer_2", second);
        service.createFor(second);
        service.saveForm("mer_2", new OnboardingFormRequest(BusinessType.LIMITED_COMPANY, null, null, "REG-9", "P09",
                null, "Nairobi", "Kenyatta Ave", null, "Retail", "Shop", 1000L, "Agent", null, "254700000000",
                List.of(new DirectorDto("Client Owner", "999", "Director", 100)), "someone@else.test", true, "Agent"), OWNER);
        service.uploadDocument("mer_2", "BUSINESS_REGISTRATION", "a.pdf", "application/pdf", PDF, OWNER);
        service.uploadDocument("mer_2", "TAX_COMPLIANCE", "b.pdf", "application/pdf", PDF, OWNER);
        service.uploadDocument("mer_2", "AUTHORISATION_LETTER", "c.pdf", "application/pdf", PDF, OWNER);
        toApplicant.clear();
        service.submit("mer_2", null, OWNER);
        assertEquals(2, toApplicant.size());
        assertTrue(toApplicant.stream().anyMatch(t -> t.startsWith("someone@else.test") && t.contains("wallet is named")));
    }
}
