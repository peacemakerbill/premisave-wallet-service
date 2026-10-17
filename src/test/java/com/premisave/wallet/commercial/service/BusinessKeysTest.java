package com.premisave.wallet.commercial.service;

import com.premisave.wallet.commercial.entity.MerchantProfile;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BusinessKeysTest {

    private static MerchantProfile merchant(String id, String name, String country, String key) {
        MerchantProfile p = new MerchantProfile();
        p.setMerchantId(id);
        p.setBusinessName(name);
        p.setCountry(country);
        p.setApplicationKey(key);
        return p;
    }

    @Test
    void caseSpacesAndPunctuationDoNotMakeABusinessNew() {
        String key = BusinessKeys.of("Acme Traders Ltd.", "KE");
        assertEquals(key, BusinessKeys.of("  ACME   traders, LTD ", "ke"));
        assertEquals(key, BusinessKeys.of("acme-traders ltd", "KE"));
    }

    @Test
    void theSameNameInAnotherCountryIsAnotherBusiness() {
        assertNotEquals(BusinessKeys.of("Acme Ltd", "KE"), BusinessKeys.of("Acme Ltd", "UG"));
    }

    @Test
    void differentNamesAreDifferentBusinesses() {
        assertNotEquals(BusinessKeys.of("Acme Ltd", "KE"), BusinessKeys.of("Acme Holdings Ltd", "KE"));
    }

    @Test
    void lettersOutsideAsciiCount() {
        assertEquals(BusinessKeys.of("Café Müller", "KE"), BusinessKeys.of("CAFÉ  MÜLLER", "KE"));
        assertNotEquals(BusinessKeys.of("Café Müller", "KE"), BusinessKeys.of("Cafe Muller", "KE"));
    }

    @Test
    void missingValuesDoNotBreakTheKey() {
        assertEquals("|", BusinessKeys.of((String) null, null));
        assertEquals("|KE", BusinessKeys.of("  ", "KE"));
    }

    @Test
    void aStoredKeyIsUsedAndAnOlderMerchantWithoutOneGetsItWorkedOut() {
        assertEquals("stored", BusinessKeys.of(merchant("m1", "Acme", "KE", "stored")));
        assertEquals("acme|KE", BusinessKeys.of(merchant("m2", "Acme", "KE", null)));
    }

    @Test
    void anOlderMerchantStillCountsAsTheSameBusiness() {
        List<MerchantProfile> existing = List.of(
                merchant("m1", "Old Shop", "KE", null),
                merchant("m2", "Acme Ltd", "KE", BusinessKeys.of("Acme Ltd", "KE")));
        assertEquals("m1", BusinessKeys.findSame(existing, BusinessKeys.of("OLD SHOP", "KE")).orElseThrow().getMerchantId());
        assertEquals("m2", BusinessKeys.findSame(existing, BusinessKeys.of("acme ltd.", "KE")).orElseThrow().getMerchantId());
        assertTrue(BusinessKeys.findSame(existing, BusinessKeys.of("Third Co", "KE")).isEmpty());
        assertTrue(BusinessKeys.findSame(List.of(), BusinessKeys.of("Acme Ltd", "KE")).isEmpty());
    }
}
