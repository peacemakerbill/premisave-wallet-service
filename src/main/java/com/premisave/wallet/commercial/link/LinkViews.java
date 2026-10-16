package com.premisave.wallet.commercial.link;

import com.premisave.wallet.commercial.dto.Views;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The shapes of sections 2.5 and 2.6 of the endpoint reference. */
public final class LinkViews {

    private LinkViews() {}

    public static Map<String, Object> link(PaymentLink l, String url, Instant now) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", l.getId());
        m.put("url", url);
        m.put("merchantReference", l.getMerchantReference());
        m.put("amountType", l.getAmountType().name());
        m.put("amount", plain(l.getAmount()));
        m.put("minAmount", plain(l.getMinAmount()));
        m.put("maxAmount", plain(l.getMaxAmount()));
        m.put("currency", l.getCurrency());
        m.put("description", l.getDescription());
        m.put("allowedMethods", l.getAllowedMethods().stream().map(Enum::name).sorted().toList());
        m.put("usage", l.getUsage().name());
        m.put("usageCount", l.getUsageCount());
        m.put("callbackUrl", l.getCallbackUrl());
        m.put("status", PaymentLinkRules.effectiveStatus(l, now));
        m.put("expiresAt", Views.iso(l.getExpiresAt()));
        m.put("metadata", l.getMetadata());
        m.put("appId", l.getAppId());
        m.put("livemode", l.isLivemode());
        m.put("createdAt", Views.iso(l.getCreatedAt()));
        m.put("updatedAt", Views.iso(l.getUpdatedAt()));
        return m;
    }

    public static Map<String, Object> linkDetail(PaymentLink l, String url, Instant now, long usageCount, List<String> paymentIds) {
        Map<String, Object> m = link(l, url, now);
        m.put("usageCount", usageCount);
        m.put("payments", paymentIds);
        return m;
    }

    public static Map<String, Object> accountReference(AccountReference r, Instant now) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("reference", r.getReference());
        m.put("merchantReference", r.getMerchantReference());
        m.put("expectedAmount", r.getExpectedAmount() == null ? null : plain(r.getExpectedAmount()));
        m.put("currency", r.getCurrency());
        m.put("status", r.getExpiresAt() != null && !r.getExpiresAt().isAfter(now) && "OPEN".equals(r.getStatus())
                ? "EXPIRED" : r.getStatus());
        Map<String, Object> payTo = new LinkedHashMap<>();
        payTo.put("type", r.getMethodType());
        payTo.put("provider", r.getProvider());
        payTo.put("shortcode", r.getShortcode());
        payTo.put("accountNumber", r.getReference());
        m.put("payTo", payTo);
        m.put("expiresAt", Views.iso(r.getExpiresAt()));
        m.put("appId", r.getAppId());
        m.put("livemode", r.isLivemode());
        m.put("createdAt", Views.iso(r.getCreatedAt()));
        m.put("updatedAt", Views.iso(r.getUpdatedAt()));
        return m;
    }

    private static String plain(BigDecimal d) {
        return d == null ? null : d.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString();
    }
}
