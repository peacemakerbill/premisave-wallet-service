package com.premisave.wallet.commercial.dto;

import com.premisave.wallet.commercial.entity.CallbackRegistration;
import com.premisave.wallet.commercial.entity.CommercialEvent;
import com.premisave.wallet.commercial.entity.CommercialTransaction;
import com.premisave.wallet.commercial.entity.LedgerLine;
import com.premisave.wallet.commercial.entity.Limits;
import com.premisave.wallet.commercial.entity.MerchantProfile;
import com.premisave.wallet.commercial.entity.MerchantWallet;
import com.premisave.wallet.commercial.entity.Quote;
import com.premisave.wallet.commercial.entity.SettlementDestination;
import com.premisave.wallet.commercial.entity.TimelineEntry;
import com.premisave.wallet.commercial.entity.TxResult;
import com.premisave.wallet.commercial.enums.Environment;
import com.premisave.wallet.commercial.util.Masking;
import com.premisave.wallet.commercial.util.Money;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds the JSON shapes in COMMERCIAL_API_ENDPOINTS.md section 2. Views are
 * plain maps of strings, numbers, booleans, lists and maps, so the same object
 * can be returned to the caller and stored as the data of an event.
 */
public final class Views {

    private Views() {}

    public static Map<String, Object> payment(CommercialTransaction tx) {
        Map<String, Object> m = common(tx);
        m.put("netAmount", Money.format(tx.getSettleCents()));
        m.put("method", method(tx));
        m.put("customer", maskedCustomer(tx.getCustomer()));
        m.put("paymentLinkId", tx.getRelations().get("paymentLinkId"));
        m.put("nextAction", tx.getNextAction());
        m.put("result", result(tx.getResult()));
        m.put("providerReference", tx.getProviderReference());
        m.put("refundedAmount", Money.format(tx.getRefundedCents()));
        m.put("description", tx.getDescription());
        m.put("callbackUrl", tx.getCallbackUrl());
        m.put("notifyRegistered", tx.isNotifyRegistered());
        m.put("metadata", tx.getMetadata());
        m.put("appId", tx.getAppId());
        m.put("livemode", tx.isLivemode());
        m.put("version", tx.getVersion());
        m.put("createdAt", iso(tx.getCreatedAt()));
        m.put("updatedAt", iso(tx.getUpdatedAt()));
        m.put("expiresAt", iso(tx.getExpiresAt()));
        return m;
    }

    public static Map<String, Object> payout(CommercialTransaction tx) {
        Map<String, Object> m = common(tx);
        m.put("totalDebit", Money.format(tx.getSettleCents()));
        m.put("destination", maskedDestination(tx.getDestination()));
        m.put("remarks", tx.getRemarks());
        m.put("result", result(tx.getResult()));
        m.put("beneficiary", beneficiary(tx));
        m.put("batchId", tx.getRelations().get("batchId"));
        m.put("providerReference", tx.getProviderReference());
        m.put("callbackUrl", tx.getCallbackUrl());
        m.put("notifyRegistered", tx.isNotifyRegistered());
        m.put("metadata", tx.getMetadata());
        m.put("appId", tx.getAppId());
        m.put("livemode", tx.isLivemode());
        m.put("version", tx.getVersion());
        m.put("createdAt", iso(tx.getCreatedAt()));
        m.put("updatedAt", iso(tx.getUpdatedAt()));
        return m;
    }

    /** The merchant ledger view (section 2.20): one shape for every kind of transaction. */
    public static Map<String, Object> transaction(CommercialTransaction tx) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", tx.getId());
        m.put("type", tx.getType().name());
        m.put("status", tx.getStatus().name());
        m.put("merchantReference", tx.getMerchantReference());
        m.put("amount", Money.format(tx.getAmountCents()));
        m.put("currency", Money.BASE_CURRENCY);
        m.put("fee", Money.format(tx.getFeeCents()));
        long net = tx.getType().isDebit() ? -tx.getSettleCents() : tx.getSettleCents();
        m.put("net", Money.format(net));
        m.put("method", method(tx));
        m.put("objectId", tx.getId());
        m.put("appId", tx.getAppId());
        List<Map<String, Object>> timeline = new ArrayList<>();
        for (TimelineEntry t : tx.getTimeline()) {
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("at", iso(t.getAt()));
            e.put("status", t.getStatus().name());
            e.put("note", t.getNote());
            timeline.add(e);
        }
        m.put("timeline", timeline);
        m.put("livemode", tx.isLivemode());
        m.put("createdAt", iso(tx.getCreatedAt()));
        return m;
    }

    public static Map<String, Object> merchant(MerchantProfile p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("merchantId", p.getMerchantId());
        m.put("merchantNumber", p.getMerchantNumber());
        m.put("businessName", p.getBusinessName());
        m.put("displayName", p.getDisplayName());
        m.put("country", p.getCountry());
        m.put("defaultCurrency", p.getDefaultCurrency());
        m.put("sandbox", Map.of("status", p.statusFor(Environment.SANDBOX).name(), "walletId", String.valueOf(p.walletIdFor(Environment.SANDBOX))));
        m.put("live", Map.of("status", p.statusFor(Environment.LIVE).name(), "walletId", String.valueOf(p.walletIdFor(Environment.LIVE))));
        m.put("enabledMethods", p.getEnabledMethods().stream().map(Enum::name).sorted().toList());
        m.put("limits", Map.of("sandbox", limits(p.limitsFor(Environment.SANDBOX)), "live", limits(p.limitsFor(Environment.LIVE))));
        m.put("feePlanId", p.getFeePlanId());
        m.put("requireBeneficiaryCheck", p.isRequireBeneficiaryCheck());
        m.put("settlementDestinations", Map.of(
                "sandbox", destinations(p.destinationsFor(Environment.SANDBOX)),
                "live", destinations(p.destinationsFor(Environment.LIVE))));
        m.put("createdAt", iso(p.getCreatedAt()));
        m.put("updatedAt", iso(p.getUpdatedAt()));
        return m;
    }

    public static Map<String, Object> balance(MerchantWallet w, boolean livemode) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("currency", Money.BASE_CURRENCY);
        m.put("available", Money.format(w.getAvailableCents()));
        m.put("pending", Money.format(w.getPendingCents()));
        m.put("reserved", Money.format(w.getHeldCents()));
        m.put("total", Money.format(w.totalCents()));
        m.put("asOf", iso(Instant.now()));
        m.put("livemode", livemode);
        return m;
    }

    public static Map<String, Object> ledgerLine(LedgerLine l) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("at", iso(l.getCreatedAt()));
        m.put("type", l.getEntryType().name());
        m.put("objectId", l.getObjectId());
        m.put("description", l.getDescription());
        m.put("credit", l.getAvailableDeltaCents() > 0 ? Money.format(l.getAvailableDeltaCents()) : null);
        m.put("debit", l.getAvailableDeltaCents() < 0 ? Money.format(-l.getAvailableDeltaCents()) : null);
        m.put("held", l.getHeldDeltaCents() == 0 ? null : Money.format(l.getHeldDeltaCents()));
        m.put("fee", l.getFeeCents() == 0 ? null : Money.format(l.getFeeCents()));
        m.put("balanceAfter", Money.format(l.getAvailableAfterCents()));
        return m;
    }

    public static Map<String, Object> quote(Quote q) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("quoteId", q.getQuoteId());
        m.put("direction", q.getDirection().name());
        m.put("amount", q.getAmount().setScale(2, java.math.RoundingMode.HALF_UP).toPlainString());
        m.put("currency", q.getCurrency());
        m.put("usdAmount", Money.format(q.getUsdCents()));
        m.put("nativeAmount", q.getNativeAmount() == null ? null : q.getNativeAmount().toPlainString());
        m.put("nativeCurrency", q.getNativeCurrency());
        m.put("fxRate", q.getFxRate() == null ? null : q.getFxRate().toPlainString());
        m.put("fee", Money.format(q.getFeeCents()));
        m.put("netAmount", Money.format(q.getNetCents()));
        m.put("rail", q.getRail());
        m.put("method", Map.of("type", q.getMethodType().name(), "provider", String.valueOf(q.getProvider())));
        m.put("expiresAt", iso(q.getExpiresAt()));
        return m;
    }

    public static Map<String, Object> registration(CallbackRegistration r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.getId());
        m.put("type", r.getType().name());
        m.put("url", r.getUrl());
        m.put("eventTypes", r.getEventTypes());
        m.put("description", r.getDescription());
        m.put("status", r.getStatus().name());
        m.put("statusReason", r.getStatusReason());
        m.put("onTimeout", r.getOnTimeout());
        m.put("verifiedAt", iso(r.getVerifiedAt()));
        m.put("appId", r.getAppId());
        m.put("livemode", r.isLivemode());
        m.put("createdAt", iso(r.getCreatedAt()));
        m.put("updatedAt", iso(r.getUpdatedAt()));
        return m;
    }

    public static Map<String, Object> event(CommercialEvent e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("eventId", e.getEventId());
        m.put("type", e.getType());
        m.put("createdAt", iso(e.getCreatedAt()));
        m.put("livemode", e.isLivemode());
        m.put("merchantId", e.getMerchantId());
        m.put("appId", e.getAppId());
        m.put("data", e.getData());
        List<Map<String, Object>> targets = new ArrayList<>();
        e.getTargets().forEach(t -> {
            Map<String, Object> tm = new LinkedHashMap<>();
            tm.put("url", t.getUrl());
            tm.put("source", t.getSource());
            tm.put("registrationId", t.getRegistrationId());
            targets.add(tm);
        });
        m.put("targets", targets);
        m.put("forwardStatus", e.getForwardStatus().name());
        m.put("attempts", e.getAttempts());
        m.put("lastError", e.getLastError());
        return m;
    }

    public static Map<String, Object> limits(Limits l) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("singleTransactionUsd", l.getSingleTransactionUsd() == null ? null : l.getSingleTransactionUsd().toPlainString());
        m.put("dailyVolumeUsd", l.getDailyVolumeUsd() == null ? null : l.getDailyVolumeUsd().toPlainString());
        m.put("monthlyVolumeUsd", l.getMonthlyVolumeUsd() == null ? null : l.getMonthlyVolumeUsd().toPlainString());
        m.put("dailyPayoutCount", l.getDailyPayoutCount());
        return m;
    }

    public static String iso(Instant t) {
        return t == null ? null : t.toString();
    }

    // Building blocks

    private static Map<String, Object> common(CommercialTransaction tx) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", tx.getId());
        m.put("merchantReference", tx.getMerchantReference());
        m.put("status", tx.getStatus().name());
        m.put("amount", Money.format(tx.getAmountCents()));
        m.put("currency", Money.BASE_CURRENCY);
        m.put("nativeAmount", decimal(tx.getNativeAmount()));
        m.put("nativeCurrency", tx.getNativeCurrency());
        m.put("fxRate", decimal(tx.getFxRate()));
        m.put("fee", Money.format(tx.getFeeCents()));
        return m;
    }

    /** The beneficiary check a payout relied on, or null when it had none. */
    private static Map<String, Object> beneficiary(CommercialTransaction tx) {
        String checkId = tx.getRelations().get("beneficiaryCheckId");
        if (checkId == null) return null;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("checkId", checkId);
        m.put("registeredName", tx.getRelations().get("beneficiaryName"));
        m.put("match", tx.getRelations().get("beneficiaryMatch"));
        return m;
    }

    private static Map<String, Object> method(CommercialTransaction tx) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", tx.getMethodType() == null ? null : tx.getMethodType().name());
        m.put("provider", tx.getProvider());
        return m;
    }

    public static Map<String, Object> result(TxResult r) {
        if (r == null) return null;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("code", r.getCode().name());
        m.put("description", r.getDescription());
        m.put("providerCode", r.getProviderCode());
        m.put("providerDescription", r.getProviderDescription());
        return m;
    }

    private static Map<String, Object> maskedCustomer(Map<String, Object> c) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (c == null) return m;
        c.forEach((k, v) -> m.put(k, switch (k) {
            case "phone" -> Masking.phone(String.valueOf(v));
            case "email" -> Masking.email(String.valueOf(v));
            default -> v;
        }));
        return m;
    }

    public static Map<String, Object> maskedDestination(Map<String, Object> d) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (d == null) return m;
        d.forEach((k, v) -> m.put(k, switch (k) {
            case "phoneNumber" -> Masking.phone(String.valueOf(v));
            case "accountNumber", "shortcode" -> Masking.account(String.valueOf(v));
            case "email" -> Masking.email(String.valueOf(v));
            case "address" -> Masking.address(String.valueOf(v));
            case "accountRef" -> String.valueOf(v).contains("@") ? Masking.email(String.valueOf(v)) : Masking.account(String.valueOf(v));
            default -> v;
        }));
        return m;
    }

    private static List<Map<String, Object>> destinations(List<SettlementDestination> list) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (SettlementDestination d : list) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", d.getId());
            m.put("type", d.getType().name());
            m.put("label", d.getLabel());
            m.put("country", d.getCountry());
            Map<String, Object> masked = new LinkedHashMap<>();
            d.getDetails().forEach((k, v) -> masked.put(k, switch (k) {
                case "phoneNumber" -> Masking.phone(v);
                case "accountNumber" -> Masking.account(v);
                default -> v;
            }));
            m.put("details", masked);
            out.add(m);
        }
        return out;
    }

    private static String decimal(BigDecimal d) {
        return d == null ? null : d.toPlainString();
    }
}
