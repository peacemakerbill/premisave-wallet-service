package com.premisave.wallet.commercial.dto;

import com.premisave.wallet.commercial.entity.CommercialTransaction;
import com.premisave.wallet.commercial.entity.TxResult;
import com.premisave.wallet.commercial.settlement.SettlementSchedule;
import com.premisave.wallet.commercial.util.Masking;
import com.premisave.wallet.commercial.util.Money;

import java.util.LinkedHashMap;
import java.util.Map;

/** The shapes of sections 2.17 and 2.18 of the endpoint reference, plus the saved schedule. */
public final class SettlementViews {

    private SettlementViews() {}

    public static Map<String, Object> settlement(CommercialTransaction tx) {
        Map<String, String> rel = tx.getRelations();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", tx.getId());
        m.put("status", tx.getStatus().name());
        m.put("amount", Money.format(tx.getAmountCents()));
        m.put("currency", Money.BASE_CURRENCY);
        m.put("nativeAmount", tx.getNativeAmount() == null ? null : tx.getNativeAmount().toPlainString());
        m.put("nativeCurrency", tx.getNativeCurrency());
        m.put("fxRate", tx.getFxRate() == null ? null : tx.getFxRate().toPlainString());
        m.put("fee", Money.format(tx.getFeeCents()));
        m.put("totalDebit", Money.format(tx.getSettleCents()));
        Map<String, Object> dest = new LinkedHashMap<>();
        dest.put("id", rel.get("destinationId"));
        dest.put("type", rel.get("destinationType"));
        dest.put("label", rel.get("destinationLabel"));
        m.put("destination", dest);
        m.put("trigger", rel.get("trigger"));
        m.put("merchantReference", tx.getMerchantReference());
        m.put("result", result(tx.getResult()));
        m.put("providerReference", tx.getProviderReference());
        m.put("appId", tx.getAppId());
        m.put("livemode", tx.isLivemode());
        m.put("version", tx.getVersion());
        m.put("createdAt", Views.iso(tx.getCreatedAt()));
        m.put("updatedAt", Views.iso(tx.getUpdatedAt()));
        return m;
    }

    public static Map<String, Object> transfer(CommercialTransaction tx) {
        Map<String, String> rel = tx.getRelations();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", tx.getId());
        m.put("status", tx.getStatus().name());
        m.put("amount", Money.format(tx.getAmountCents()));
        m.put("currency", Money.BASE_CURRENCY);
        m.put("fee", Money.format(tx.getFeeCents()));
        m.put("totalDebit", Money.format(tx.getSettleCents()));
        Map<String, Object> to = new LinkedHashMap<>();
        to.put("type", "PREMISAVE_WALLET");
        Object ref = tx.getDestination() == null ? null : tx.getDestination().get("accountRef");
        to.put("accountNumber", ref == null ? null : Masking.email(String.valueOf(ref)));
        to.put("name", rel.get("recipientName"));
        m.put("to", to);
        m.put("merchantReference", tx.getMerchantReference());
        m.put("note", tx.getRemarks());
        m.put("result", result(tx.getResult()));
        m.put("appId", tx.getAppId());
        m.put("livemode", tx.isLivemode());
        m.put("version", tx.getVersion());
        m.put("createdAt", Views.iso(tx.getCreatedAt()));
        m.put("updatedAt", Views.iso(tx.getUpdatedAt()));
        return m;
    }

    public static Map<String, Object> schedule(SettlementSchedule s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("mode", s.getMode().name());
        m.put("destinationId", s.getDestinationId());
        m.put("minimumAmount", Money.format(s.getMinimumCents()));
        m.put("keepReserve", Money.format(s.getKeepReserveCents()));
        m.put("runAtUtc", s.getRunAtUtc());
        m.put("dayOfWeek", s.getDayOfWeek() == null ? null : s.getDayOfWeek().name());
        m.put("thresholdAmount", s.getThresholdCents() > 0 ? Money.format(s.getThresholdCents()) : null);
        m.put("lastRunAt", Views.iso(s.getLastRunAt()));
        m.put("updatedAt", Views.iso(s.getUpdatedAt()));
        return m;
    }

    private static Map<String, Object> result(TxResult r) {
        if (r == null) return null;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("code", r.getCode().name());
        m.put("description", r.getDescription());
        m.put("providerCode", r.getProviderCode());
        m.put("providerDescription", r.getProviderDescription());
        return m;
    }
}
