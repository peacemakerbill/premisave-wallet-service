package com.premisave.wallet.commercial.billing;

import com.premisave.wallet.commercial.exception.CommercialException;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BillingInputTest {

    @Test
    void parsesIsoTimesAndTreatsBlankAsNothing() {
        assertEquals(Instant.parse("2026-12-01T00:00:00Z"), BillingInput.instant("startAt", " 2026-12-01T00:00:00Z "));
        assertNull(BillingInput.instant("startAt", null));
        assertNull(BillingInput.instant("startAt", "  "));
    }

    @Test
    void rejectsOtherDateShapes() {
        CommercialException e = assertThrows(CommercialException.class, () -> BillingInput.instant("startAt", "01/12/2026"));
        assertEquals("startAt", e.details().get(0).get("field"));
    }

    @Test
    void subscriptionMetadataLeavesRoomForBillingKeys() {
        Map<String, String> ok = new LinkedHashMap<>();
        for (int i = 0; i < 16; i++) ok.put("k" + i, "v");
        assertEquals(16, BillingInput.subscriptionMetadata(ok).size());
        ok.put("k16", "v");
        assertThrows(CommercialException.class, () -> BillingInput.subscriptionMetadata(ok));
    }

    @Test
    void blankBecomesNull() {
        assertNull(BillingInput.blankToNull("  "));
        assertEquals("x", BillingInput.blankToNull(" x "));
    }
}
