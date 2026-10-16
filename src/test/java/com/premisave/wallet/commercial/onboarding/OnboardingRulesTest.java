package com.premisave.wallet.commercial.onboarding;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OnboardingRulesTest {

    private static final List<String> REQUIRED = List.of("BUSINESS_REGISTRATION", "TAX_COMPLIANCE");

    static BusinessForm completeForm() {
        BusinessForm f = new BusinessForm();
        f.setBusinessType(BusinessType.LIMITED_COMPANY);
        f.setRegisteredName("Acme Traders Ltd");
        f.setRegistrationNumber("CPR/2020/1234");
        f.setTaxPin("P051234567Z");
        f.setCountry("KE");
        f.setCity("Nairobi");
        f.setPhysicalAddress("Moi Avenue 12");
        f.setIndustry("Retail");
        f.setBusinessDescription("Sells household goods online");
        f.setContactName("Jane Doe");
        f.setContactEmail("jane@acme.test");
        f.setContactPhone("254712345678");
        f.setExpectedMonthlyVolumeUsd(25_000L);
        Director d = new Director();
        d.setFullName("Jane Doe");
        d.setIdNumber("12345678");
        d.setOwnershipPercent(100);
        f.setDirectors(new ArrayList<>(List.of(d)));
        return f;
    }

    static OnboardingDocument doc(DocumentType type, DocumentVerdict verdict) {
        OnboardingDocument d = new OnboardingDocument();
        d.setDocumentId("doc_" + type + verdict);
        d.setType(type);
        d.setVerdict(verdict);
        return d;
    }

    private static List<String> fields(List<Map<String, String>> missing) {
        return missing.stream().map(m -> m.get("field")).toList();
    }

    @Test
    void aCompleteApplicationHasNothingMissing() {
        List<OnboardingDocument> docs = List.of(doc(DocumentType.BUSINESS_REGISTRATION, DocumentVerdict.PENDING),
                doc(DocumentType.TAX_COMPLIANCE, DocumentVerdict.ACCEPTED));
        assertTrue(OnboardingRules.missing(completeForm(), docs, REQUIRED).isEmpty());
    }

    @Test
    void anEmptyFormListsEveryRequiredField() {
        List<String> fields = fields(OnboardingRules.missing(new BusinessForm(), List.of(), REQUIRED));
        for (String f : List.of("businessType", "registeredName", "registrationNumber", "taxPin", "country", "city",
                "physicalAddress", "industry", "businessDescription", "contactName", "contactEmail", "contactPhone",
                "expectedMonthlyVolumeUsd", "directors", "document:BUSINESS_REGISTRATION", "document:TAX_COMPLIANCE")) {
            assertTrue(fields.contains(f), f + " should be reported");
        }
    }

    @Test
    void aRejectedDocumentDoesNotCountAsUploaded() {
        List<OnboardingDocument> docs = List.of(doc(DocumentType.BUSINESS_REGISTRATION, DocumentVerdict.REJECTED),
                doc(DocumentType.TAX_COMPLIANCE, DocumentVerdict.PENDING));
        assertEquals(List.of("document:BUSINESS_REGISTRATION"), fields(OnboardingRules.missing(completeForm(), docs, REQUIRED)));
    }

    @Test
    void directorsNeedANameAnIdAndSensibleOwnership() {
        BusinessForm f = completeForm();
        f.getDirectors().get(0).setIdNumber(" ");
        Director second = new Director();
        second.setFullName("John Roe");
        second.setIdNumber("999");
        second.setOwnershipPercent(30);
        f.getDirectors().add(second);
        List<String> fields = fields(OnboardingRules.missing(f, List.of(), List.of()));
        assertTrue(fields.contains("directors[0].idNumber"));
        assertTrue(fields.contains("directors"), "130 percent ownership is refused");
        assertEquals(130, OnboardingRules.ownershipTotal(f.getDirectors()));
    }

    @Test
    void approvalNeedsAnAcceptedDocumentOfEachRequiredType() {
        List<OnboardingDocument> docs = List.of(doc(DocumentType.BUSINESS_REGISTRATION, DocumentVerdict.ACCEPTED),
                doc(DocumentType.TAX_COMPLIANCE, DocumentVerdict.PENDING));
        assertEquals(List.of("TAX_COMPLIANCE"), OnboardingRules.unverifiedRequired(docs, REQUIRED));
        assertTrue(OnboardingRules.unverifiedRequired(docs, List.of("BUSINESS_REGISTRATION")).isEmpty());
    }

    @Test
    void onlyPdfJpegAndPngAreAllowed() {
        assertTrue(OnboardingRules.allowedContentType("application/pdf"));
        assertTrue(OnboardingRules.allowedContentType("IMAGE/PNG"));
        assertFalse(OnboardingRules.allowedContentType("image/gif"));
        assertFalse(OnboardingRules.allowedContentType("text/html"));
        assertFalse(OnboardingRules.allowedContentType(null));
    }

    @Test
    void fileContentMustMatchTheClaimedType() {
        byte[] pdf = "%PDF-1.7 rest of file".getBytes();
        byte[] png = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0};
        byte[] jpg = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 0x10, 'J', 'F', 'I', 'F'};
        assertTrue(OnboardingRules.matchesSignature("application/pdf", pdf));
        assertTrue(OnboardingRules.matchesSignature("image/png", png));
        assertTrue(OnboardingRules.matchesSignature("image/jpeg", jpg));
        assertFalse(OnboardingRules.matchesSignature("application/pdf", png), "a PNG sent as a PDF");
        assertFalse(OnboardingRules.matchesSignature("image/jpeg", pdf));
        assertFalse(OnboardingRules.matchesSignature("application/pdf", "<html><script>".getBytes()));
        assertFalse(OnboardingRules.matchesSignature("application/pdf", new byte[3]));
        assertFalse(OnboardingRules.matchesSignature("image/gif", pdf));
    }

    @Test
    void fileNamesLoseAnyPathAndOddCharacters() {
        assertEquals("cert.pdf", OnboardingRules.safeFileName("C:\\Users\\jane\\cert.pdf"));
        assertEquals("passwd", OnboardingRules.safeFileName("../../etc/passwd"));
        assertEquals("my_certificate__1_.pdf", OnboardingRules.safeFileName("my certificate (1).pdf"));
        assertEquals("document", OnboardingRules.safeFileName("   "));
        assertEquals("document", OnboardingRules.safeFileName(null));
        assertFalse(OnboardingRules.safeFileName("..hidden..name").contains(".."));
        assertEquals(100, OnboardingRules.safeFileName("a".repeat(300) + ".pdf").length());
    }

    @Test
    void returnItemsPointAtAFormFieldOrADocument() {
        assertTrue(OnboardingRules.validReturnField("taxPin"));
        assertTrue(OnboardingRules.validReturnField("directors[0].idNumber"));
        assertTrue(OnboardingRules.validReturnField("document:TAX_COMPLIANCE"));
        assertTrue(OnboardingRules.validReturnField("document:tax_compliance"));
        assertFalse(OnboardingRules.validReturnField("document:PASSPORT_PHOTO"));
        assertFalse(OnboardingRules.validReturnField("tax pin"));
        assertFalse(OnboardingRules.validReturnField(""));
        assertFalse(OnboardingRules.validReturnField(null));
    }

    @Test
    void transitionsStartFromTheRightStatuses() {
        assertEquals(java.util.Set.of(OnboardingStatus.DRAFT, OnboardingStatus.RETURNED), OnboardingRules.sourcesFor("submit"));
        assertEquals(java.util.Set.of(OnboardingStatus.SUBMITTED), OnboardingRules.sourcesFor("startReview"));
        assertEquals(java.util.Set.of(OnboardingStatus.IN_REVIEW), OnboardingRules.sourcesFor("approve"));
        assertEquals(java.util.Set.of(OnboardingStatus.IN_REVIEW), OnboardingRules.sourcesFor("return"));
        assertEquals(java.util.Set.of(OnboardingStatus.SUBMITTED, OnboardingStatus.IN_REVIEW), OnboardingRules.sourcesFor("reject"));
        assertEquals(java.util.Set.of(OnboardingStatus.REJECTED), OnboardingRules.sourcesFor("reopen"));
        assertTrue(OnboardingRules.sourcesFor("unknown").isEmpty());
        assertTrue(OnboardingStatus.DRAFT.editable() && OnboardingStatus.RETURNED.editable());
        assertFalse(OnboardingStatus.SUBMITTED.editable() || OnboardingStatus.IN_REVIEW.editable());
        assertTrue(OnboardingStatus.APPROVED.finished() && OnboardingStatus.REJECTED.finished());
    }
}
