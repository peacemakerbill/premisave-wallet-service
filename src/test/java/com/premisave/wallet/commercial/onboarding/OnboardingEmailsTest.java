package com.premisave.wallet.commercial.onboarding;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OnboardingEmailsTest {

    private static ReturnItem item(String field, String message) {
        ReturnItem i = new ReturnItem();
        i.setItemId("itm_1");
        i.setField(field);
        i.setMessage(message);
        return i;
    }

    @Test
    void everyEmailCarriesTheMerchantNumberAndBusiness() {
        for (OnboardingEmails.Email e : List.of(
                OnboardingEmails.received("Acme Ltd", "PRM-0000000007", false),
                OnboardingEmails.reviewStarted("Acme Ltd", "PRM-0000000007"),
                OnboardingEmails.approved("Acme Ltd", "PRM-0000000007", null),
                OnboardingEmails.rejected("Acme Ltd", "PRM-0000000007", "Documents do not match"),
                OnboardingEmails.reopened("Acme Ltd", "PRM-0000000007", "Second look"),
                OnboardingEmails.staffAlert("Acme Ltd", "PRM-0000000007", true))) {
            assertTrue(e.html().contains("PRM-0000000007"), e.subject());
            assertTrue(e.html().contains("Acme Ltd"), e.subject());
        }
    }

    @Test
    void theReturnEmailTellsTheCustomerWhatToCorrect() {
        OnboardingEmails.Email e = OnboardingEmails.returned("Acme Ltd", "PRM-0000000007", "Two things need fixing.",
                List.of(item("taxPin", "The PIN does not match the certificate"), item("document:DIRECTOR_ID", "The scan is blurred")));
        assertTrue(e.html().contains("Two things need fixing."));
        assertTrue(e.html().contains("taxPin: The PIN does not match the certificate"));
        assertTrue(e.html().contains("Document DIRECTOR_ID: The scan is blurred"));
        assertTrue(e.subject().contains("Action needed"));
    }

    @Test
    void textTypedByAPersonCannotInjectMarkup() {
        OnboardingEmails.Email e = OnboardingEmails.returned("<b>Evil</b> & Co", "PRM-0000000007",
                "<script>alert(1)</script>", List.of(item("taxPin", "a\"b'c<d")));
        assertFalse(e.html().contains("<script>"));
        assertFalse(e.html().contains("<b>Evil"));
        assertTrue(e.html().contains("&lt;script&gt;"));
        assertTrue(e.html().contains("&amp; Co"));
        assertTrue(e.html().contains("a&quot;b&#39;c&lt;d"));
    }

    @Test
    void anApprovalMessageIsIncludedWhenGiven() {
        assertTrue(OnboardingEmails.approved("Acme", "PRM-0000000001", "Welcome aboard").html().contains("Welcome aboard"));
        assertTrue(OnboardingEmails.approved("Acme", "PRM-0000000001", " ").html().contains("Production is now switched on"));
    }

    @Test
    void subjectsNeverCarryLineBreaks() {
        OnboardingEmails.Email e = OnboardingEmails.staffAlert("Acme", "PRM-1\r\nBcc: x@evil.test", false);
        assertFalse(e.subject().contains("\r") || e.subject().contains("\n"));
    }
}
