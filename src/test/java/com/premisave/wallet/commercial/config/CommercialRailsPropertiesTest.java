package com.premisave.wallet.commercial.config;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommercialRailsPropertiesTest {

    private static CommercialRailsProperties empty() {
        return new CommercialRailsProperties(null, null, null, null, null, null, null);
    }

    @Test
    void everyRailIsOffByDefault() {
        CommercialRailsProperties p = empty();
        assertFalse(p.stripe().enabled());
        assertFalse(p.paypal().enabled());
        assertFalse(p.flutterwave().enabled());
        assertFalse(p.nowpayments().enabled());
        assertFalse(p.premisave().enabled());
    }

    @Test
    void pollingIsOnWithSaneDefaults() {
        CommercialRailsProperties.Polling p = empty().polling();
        assertTrue(p.enabled());
        assertEquals(20_000L, p.intervalMs().longValue());
        assertEquals(15, p.afterSeconds().intValue());
        assertEquals(50, p.batchSize().intValue());
    }

    @Test
    void outOfRangeValuesFallBack() {
        CommercialRailsProperties.Polling p = new CommercialRailsProperties.Polling(null, 5L, 99_999, 0);
        assertEquals(20_000L, p.intervalMs().longValue());
        assertEquals(15, p.afterSeconds().intValue());
        assertEquals(50, p.batchSize().intValue());
        CommercialRailsProperties.Premisave w = new CommercialRailsProperties.Premisave(true, 0, -1L, 0L, 0L, 0);
        assertEquals(15, w.consentMinutes().intValue());
        assertEquals(50_000L, w.mandateMaxUsdCents().longValue());
        assertEquals(1L, w.minUsdCents().longValue());
        assertEquals(5_000_000L, w.maxUsdCents().longValue());
        assertEquals(48, w.mandateHours().intValue());
        assertEquals(20, new CommercialRailsProperties(null, null, null, null, null, null, 1).httpTimeoutSeconds().intValue());
    }

    @Test
    void currencyListsFallBackWhenBlank() {
        assertEquals(List.of("USD"), CommercialRailsProperties.normalise(List.of(" ", ""), List.of("USD")));
        assertEquals(List.of("USD"), CommercialRailsProperties.normalise(null, List.of("USD")));
        assertEquals(List.of("GHS", "UGX"), CommercialRailsProperties.normalise(List.of("ghs", "UGX"), List.of("USD")));
    }

    @Test
    void flutterwaveDefaults() {
        CommercialRailsProperties.Flutterwave f = empty().flutterwave();
        assertTrue(f.mobileMoneyCurrencies().contains("GHS"));
        assertFalse(f.mobileMoneyCurrencies().contains("KES"));
        assertEquals("USD", f.sourceCurrency());
    }
}
