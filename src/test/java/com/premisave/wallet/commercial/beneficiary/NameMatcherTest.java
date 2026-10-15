package com.premisave.wallet.commercial.beneficiary;

import com.premisave.wallet.commercial.beneficiary.NameMatcher.Match;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NameMatcherTest {

    @Test
    void sameNameIgnoringCaseOrderAndPunctuationIsExact() {
        assertEquals(Match.EXACT, NameMatcher.compare("John Doe", "JOHN DOE"));
        assertEquals(Match.EXACT, NameMatcher.compare("Doe, John", "JOHN DOE"));
        assertEquals(Match.EXACT, NameMatcher.compare("  john   doe ", "JOHN DOE"));
    }

    @Test
    void accentsAreIgnored() {
        assertEquals(Match.EXACT, NameMatcher.compare("José Álvarez", "JOSE ALVAREZ"));
    }

    @Test
    void aMiddleNameOnOneSideIsPartial() {
        assertEquals(Match.PARTIAL, NameMatcher.compare("John Doe", "JOHN KAMAU DOE"));
        assertEquals(Match.PARTIAL, NameMatcher.compare("John Kamau Doe", "JOHN DOE"));
    }

    @Test
    void maskedAndInitialFormsFromProvidersMatchAsPartial() {
        assertEquals(Match.PARTIAL, NameMatcher.compare("John Doe", "JOHN D***"));
        assertEquals(Match.PARTIAL, NameMatcher.compare("J Doe", "JOHN DOE"));
        assertEquals(Match.PARTIAL, NameMatcher.compare("John Doe", "J*** D**"));
    }

    @Test
    void aSharedFirstNameAloneIsNotEnough() {
        assertEquals(Match.MISMATCH, NameMatcher.compare("John", "JOHN DOE"));
        assertEquals(Match.MISMATCH, NameMatcher.compare("John Smith", "JOHN DOE"));
        assertEquals(Match.MISMATCH, NameMatcher.compare("John Doe Kamau", "JOHN DOE MWANGI"));
    }

    @Test
    void aSingleWordNameCanStillMatchItself() {
        assertEquals(Match.EXACT, NameMatcher.compare("Madonna", "MADONNA"));
        assertEquals(Match.PARTIAL, NameMatcher.compare("M***", "MADONNA"));
    }

    @Test
    void oneTypoInALongWordIsPartialButNotInAShortOne() {
        assertEquals(Match.PARTIAL, NameMatcher.compare("Wanjiku Kamau", "WANJIKU KAMAUU"));
        assertEquals(Match.PARTIAL, NameMatcher.compare("Wanjiru Kamau", "WANJIKU KAMAU"), "one letter off in a long word");
        assertEquals(Match.MISMATCH, NameMatcher.compare("Jon Doe", "JOHN DOE"), "short words must match exactly");
    }

    @Test
    void completelyDifferentNamesMismatch() {
        assertEquals(Match.MISMATCH, NameMatcher.compare("Alice Wambui", "JOHN DOE"));
    }

    @Test
    void noExpectedNameMeansNotRequestedAndNoRegisteredNameMeansUnavailable() {
        assertEquals(Match.NOT_REQUESTED, NameMatcher.compare(null, "JOHN DOE"));
        assertEquals(Match.NOT_REQUESTED, NameMatcher.compare("  ", "JOHN DOE"));
        assertEquals(Match.UNAVAILABLE, NameMatcher.compare("John Doe", null));
        assertEquals(Match.UNAVAILABLE, NameMatcher.compare("John Doe", ""));
    }

    @Test
    void onlyExactAndPartialLetAPayoutThrough() {
        assertTrue(Match.EXACT.passes());
        assertTrue(Match.PARTIAL.passes());
        assertFalse(Match.MISMATCH.passes());
        assertFalse(Match.NOT_REQUESTED.passes());
        assertFalse(Match.UNAVAILABLE.passes());
    }

    @Test
    void oneEditHelper() {
        assertTrue(NameMatcher.withinOneEdit("KAMAU", "KAMAUU"));
        assertTrue(NameMatcher.withinOneEdit("KAMAU", "KAMOU"));
        assertTrue(NameMatcher.withinOneEdit("KAMAU", "KAAU"));
        assertFalse(NameMatcher.withinOneEdit("KAMAU", "KAMOO"));
        assertFalse(NameMatcher.withinOneEdit("KAMAU", "KAM"));
    }
}
