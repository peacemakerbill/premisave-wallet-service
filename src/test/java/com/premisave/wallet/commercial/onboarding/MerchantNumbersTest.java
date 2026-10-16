package com.premisave.wallet.commercial.onboarding;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MerchantNumbersTest {

    @Test
    void numbersCountUpFromOneWithTenDigits() {
        assertEquals("PRM-0000000001", MerchantNumbers.format(1));
        assertEquals("PRM-0000000002", MerchantNumbers.format(2));
        assertEquals("PRM-0001234567", MerchantNumbers.format(1_234_567));
        assertEquals("PRM-9999999999", MerchantNumbers.format(MerchantNumbers.MAX));
    }

    @Test
    void zeroAndOverflowAreRefused() {
        assertThrows(IllegalArgumentException.class, () -> MerchantNumbers.format(0));
        assertThrows(IllegalArgumentException.class, () -> MerchantNumbers.format(-5));
        assertThrows(IllegalArgumentException.class, () -> MerchantNumbers.format(MerchantNumbers.MAX + 1));
    }

    @Test
    void onlyPrmPlusTenDigitsIsARealNumber() {
        assertTrue(MerchantNumbers.isReal("PRM-0000000001"));
        assertTrue(MerchantNumbers.isReal(" prm-0000000042 "));
        assertFalse(MerchantNumbers.isReal("PRM-000000001"));
        assertFalse(MerchantNumbers.isReal("PRM-00000000011"));
        assertFalse(MerchantNumbers.isReal("PRM-TEST-0001"));
        assertFalse(MerchantNumbers.isReal(null));
    }

    @Test
    void sequenceCanBeReadBack() {
        assertEquals(42L, MerchantNumbers.sequenceOf("PRM-0000000042"));
        assertEquals(-1L, MerchantNumbers.sequenceOf("PRM-TEST-0001"));
        assertEquals(-1L, MerchantNumbers.sequenceOf(null));
    }

    @Test
    void normaliseTrimsAndUppercases() {
        assertEquals("PRM-0000000007", MerchantNumbers.normalise("  prm-0000000007 "));
        assertEquals(null, MerchantNumbers.normalise(null));
    }
}
