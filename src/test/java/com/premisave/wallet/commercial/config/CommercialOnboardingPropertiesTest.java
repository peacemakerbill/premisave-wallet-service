package com.premisave.wallet.commercial.config;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommercialOnboardingPropertiesTest {

    private static CommercialOnboardingProperties defaults() {
        return new CommercialOnboardingProperties(null, null, null, null, null, null, null, null);
    }

    @Test
    void defaultsAreSafe() {
        CommercialOnboardingProperties p = defaults();
        assertTrue(p.enabled());
        assertEquals(List.of("BUSINESS_REGISTRATION", "TAX_COMPLIANCE", "DIRECTOR_ID"), p.requiredDocuments());
        assertEquals(5L * 1024 * 1024, p.maxDocumentBytes().longValue());
        assertEquals(20, p.maxDocuments());
        assertFalse(p.drive().configured());
        assertEquals("https://oauth2.googleapis.com/token", p.drive().tokenUrl());
    }

    @Test
    void documentSizeCannotExceedWhatTheRequestFilterCarries() {
        CommercialOnboardingProperties p = new CommercialOnboardingProperties(null, null, 50L * 1024 * 1024,
                null, null, null, null, null);
        assertEquals(CommercialOnboardingProperties.MAX_DOCUMENT_BYTES_LIMIT, p.maxDocumentBytes().longValue());
    }

    @Test
    void defaultRolesSplitTheWork() {
        CommercialOnboardingProperties.Roles r = defaults().roles();
        assertTrue(r.review().contains("OPERATIONS"));
        assertTrue(r.approve().contains("FINANCE"));
        assertFalse(r.approve().contains("OPERATIONS"));
        assertEquals(List.of("ADMIN"), r.reopen());
    }

    @Test
    void roleNamesAreComparedWithoutCase() {
        CommercialOnboardingProperties.Roles r = new CommercialOnboardingProperties.Roles(
                List.of(" compliance "), null, List.of("cfo"), null, null);
        assertEquals(List.of("COMPLIANCE"), r.review());
        assertEquals(List.of("CFO"), r.approve());
        assertTrue(defaults().allows(List.of("ADMIN"), " admin "));
        assertFalse(defaults().allows(List.of("ADMIN"), null));
    }

    @Test
    void driveNeedsKeyAndFolderAndTheSwitch() {
        assertFalse(new CommercialOnboardingProperties.Drive(true, "{}", null, null, null, null, null, null).configured());
        assertFalse(new CommercialOnboardingProperties.Drive(true, null, null, "folder", null, null, null, null).configured());
        assertFalse(new CommercialOnboardingProperties.Drive(false, "{}", null, "folder", null, null, null, null).configured());
        assertTrue(new CommercialOnboardingProperties.Drive(true, "{}", null, "folder", null, null, null, null).configured());
        assertTrue(new CommercialOnboardingProperties.Drive(true, null, "/keys/sa.json", "folder", null, null, null, null).configured());
    }
}
