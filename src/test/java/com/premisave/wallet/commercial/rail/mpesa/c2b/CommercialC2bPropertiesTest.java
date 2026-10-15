package com.premisave.wallet.commercial.rail.mpesa.c2b;

import com.premisave.wallet.commercial.config.CommercialC2bProperties;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommercialC2bPropertiesTest {

    private static CommercialC2bProperties of(String shortcode, String responseType, Integer deadline) {
        return new CommercialC2bProperties(true, shortcode, responseType, deadline, null, null, null);
    }

    @Test
    void defaultsAreSafe() {
        CommercialC2bProperties p = new CommercialC2bProperties(null, null, null, null, null, null, null);
        assertFalse(p.enabled());
        assertEquals("Cancelled", p.responseType());
        assertEquals(3000, p.validationDeadlineMs().intValue());
        assertTrue(p.merchantValidation());
        assertFalse(p.registerOnStartup());
        assertEquals(60, p.reprocessAfterSeconds().intValue());
    }

    @Test
    void responseTypeIsOnlyCompletedOrCancelled() {
        assertEquals("Completed", of("600123", " completed ", null).responseType());
        assertEquals("Cancelled", of("600123", "anything", null).responseType());
    }

    @Test
    void deadlineIsKeptInRange() {
        assertEquals(3000, of("600123", null, 100).validationDeadlineMs().intValue());
        assertEquals(3000, of("600123", null, 60_000).validationDeadlineMs().intValue());
        assertEquals(5000, of("600123", null, 5000).validationDeadlineMs().intValue());
    }

    @Test
    void theShortcodeMustBeAPaybillNumber() {
        assertNull(of("600123", null, null).problem());
        assertNull(of(" 4012345 ", null, null).problem());
        assertNotNull(of(null, null, null).problem());
        assertNotNull(of("12", null, null).problem());
        assertNotNull(of("abc123", null, null).problem());
    }
}
