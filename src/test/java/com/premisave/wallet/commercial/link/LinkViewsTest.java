package com.premisave.wallet.commercial.link;

import com.premisave.wallet.commercial.enums.MethodType;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class LinkViewsTest {

    private static final Instant NOW = Instant.parse("2026-10-15T10:00:00Z");

    @Test
    void linkViewShowsTheEffectiveStatusAndPlainAmounts() {
        PaymentLink l = new PaymentLink();
        l.setId("plink_a");
        l.setMerchantReference("INV-1");
        l.setAmountType(PaymentLink.AmountType.FIXED);
        l.setAmount(new BigDecimal("12.5"));
        l.setCurrency("USD");
        l.setAllowedMethods(EnumSet.of(MethodType.CARD, MethodType.MOBILE_MONEY));
        l.setExpiresAt(NOW.minusSeconds(1));
        Map<String, Object> v = LinkViews.link(l, "https://pay.example.com/pay/plink_a", NOW);
        assertEquals("EXPIRED", v.get("status"));
        assertEquals("12.50", v.get("amount"));
        assertNull(v.get("minAmount"));
        assertEquals(List.of("CARD", "MOBILE_MONEY"), v.get("allowedMethods"));
        assertEquals("https://pay.example.com/pay/plink_a", v.get("url"));
    }

    @Test
    void detailAddsPaymentIdsAndTheLiveUsageCount() {
        PaymentLink l = new PaymentLink();
        l.setId("plink_a");
        l.setAmountType(PaymentLink.AmountType.OPEN);
        l.setCurrency("USD");
        Map<String, Object> v = LinkViews.linkDetail(l, "/pay/plink_a", NOW, 3, List.of("pay_1", "pay_2"));
        assertEquals(3L, v.get("usageCount"));
        assertEquals(List.of("pay_1", "pay_2"), v.get("payments"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void accountReferenceViewPointsAtTheSharedPaybill() {
        AccountReference r = new AccountReference();
        r.setReference("PS1234-INV001");
        r.setMerchantReference("INV001");
        r.setCurrency("KES");
        r.setMethodType("MOBILE_MONEY");
        r.setProvider("MPESA");
        r.setShortcode("174379");
        Map<String, Object> v = LinkViews.accountReference(r, NOW);
        assertEquals("OPEN", v.get("status"));
        Map<String, Object> payTo = (Map<String, Object>) v.get("payTo");
        assertEquals("PS1234-INV001", payTo.get("accountNumber"));
        assertEquals("174379", payTo.get("shortcode"));
        r.setExpiresAt(NOW.minusSeconds(1));
        assertEquals("EXPIRED", LinkViews.accountReference(r, NOW).get("status"));
    }
}