package com.premisave.wallet.commercial.rail.mpesa;

import com.premisave.wallet.commercial.rail.RailException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MpesaRailHelpersTest {

    @Test
    void wholeShillingsAcceptsWholeAmountsInKes() {
        assertEquals(500L, MpesaRail.wholeShillings(new BigDecimal("500"), "KES"));
        assertEquals(500L, MpesaRail.wholeShillings(new BigDecimal("500.00"), "kes"));
    }

    @Test
    void wholeShillingsRefusesFractionsOtherCurrenciesAndZero() {
        assertThrows(RailException.class, () -> MpesaRail.wholeShillings(new BigDecimal("500.50"), "KES"));
        assertThrows(RailException.class, () -> MpesaRail.wholeShillings(new BigDecimal("500"), "USD"));
        assertThrows(RailException.class, () -> MpesaRail.wholeShillings(BigDecimal.ZERO, "KES"));
        assertThrows(RailException.class, () -> MpesaRail.wholeShillings(null, "KES"));
    }

    @Test
    void msisdnAcceptsKenyanNumbersInCommonForms() {
        assertEquals("254712345678", MpesaRail.msisdn("254712345678", "f"));
        assertEquals("254712345678", MpesaRail.msisdn("+254712345678", "f"));
        assertEquals("254712345678", MpesaRail.msisdn("0712345678", "f"));
        assertEquals("254112345678", MpesaRail.msisdn("0112345678", "f"));
    }

    @Test
    void msisdnRefusesOtherNumbers() {
        assertThrows(RailException.class, () -> MpesaRail.msisdn("255712345678", "f"));
        assertThrows(RailException.class, () -> MpesaRail.msisdn("25471234", "f"));
        assertThrows(RailException.class, () -> MpesaRail.msisdn(null, "f"));
    }

    @Test
    void usdCentsRoundsUpAndNeverReturnsZero() {
        BigDecimal rate = new BigDecimal("129");
        assertEquals(78L, MpesaRail.usdCents(BigDecimal.valueOf(100), rate));
        assertEquals(1L, MpesaRail.usdCents(BigDecimal.ONE, rate));
    }

    @Test
    void abbreviateTrimsToTheProviderLimit() {
        assertEquals("Premisave", MpesaDarajaClient.abbreviate(null, 12, "Premisave"));
        assertEquals("Order-123456", MpesaDarajaClient.abbreviate("Order-1234567890", 12, "x"));
        assertEquals("Pay", MpesaDarajaClient.abbreviate("  Pay ", 13, "x"));
    }
}
