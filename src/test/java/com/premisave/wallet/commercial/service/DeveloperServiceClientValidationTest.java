package com.premisave.wallet.commercial.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeveloperServiceClientValidationTest {

    @Test
    void acceptIsRecognised() {
        DeveloperServiceClient.ValidationReply r = DeveloperServiceClient.parseReply("{\"result\": \"ACCEPT\"}");
        assertTrue(r.accepted());
        assertNull(r.reason());
    }

    @Test
    void rejectCarriesItsReason() {
        DeveloperServiceClient.ValidationReply r = DeveloperServiceClient.parseReply("{\"result\":\"REJECT\",\"reason\":\"Invoice already paid\"}");
        assertFalse(r.accepted());
        assertEquals("Invoice already paid", r.reason());
    }

    @Test
    void caseDoesNotMatterAndEscapedQuotesAreRestored() {
        DeveloperServiceClient.ValidationReply r = DeveloperServiceClient.parseReply("{\"result\":\"reject\",\"reason\":\"Said \\\"no\\\"\"}");
        assertFalse(r.accepted());
        assertEquals("Said \"no\"", r.reason());
    }

    @Test
    void anythingElseIsNoAnswer() {
        assertNull(DeveloperServiceClient.parseReply(null));
        assertNull(DeveloperServiceClient.parseReply(""));
        assertNull(DeveloperServiceClient.parseReply("{\"result\":\"MAYBE\"}"));
        assertNull(DeveloperServiceClient.parseReply("<html>error</html>"));
    }
}
