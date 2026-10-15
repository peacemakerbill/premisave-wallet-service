package com.premisave.wallet.commercial.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CommercialLinksPropertiesTest {

    @Test
    void liveLinksUseTheCheckoutAddress() {
        CommercialLinksProperties p = new CommercialLinksProperties("https://pay.example.com/", null);
        assertEquals("https://pay.example.com/pay/plink_1", p.urlFor(true, "plink_1"));
    }

    @Test
    void sandboxLinksUseTheirOwnAddressThenFallBack() {
        assertEquals("https://sandbox.pay.example.com/pay/plink_1",
                new CommercialLinksProperties("https://pay.example.com", "https://sandbox.pay.example.com").urlFor(false, "plink_1"));
        assertEquals("https://pay.example.com/pay/plink_1",
                new CommercialLinksProperties("https://pay.example.com", " ").urlFor(false, "plink_1"));
    }

    @Test
    void withNoAddressTheUrlIsRelative() {
        assertEquals("/pay/plink_1", new CommercialLinksProperties(null, null).urlFor(true, "plink_1"));
    }

    @Test
    void batchAndBeneficiaryDefaultsAreSane() {
        CommercialBatchProperties b = new CommercialBatchProperties(null, null, null);
        assertEquals(10, b.itemsPerRun());
        assertEquals(60, b.stuckAfterMinutes());
        assertEquals(500, b.maxItems());
        assertEquals(500, new CommercialBatchProperties(0, 1, 9999).maxItems());
        assertEquals(24, new CommercialBeneficiaryProperties(null).cacheHours());
        assertEquals(24, new CommercialBeneficiaryProperties(1000).cacheHours());
    }
}
