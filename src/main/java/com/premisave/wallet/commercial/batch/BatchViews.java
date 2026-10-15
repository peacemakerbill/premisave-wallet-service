package com.premisave.wallet.commercial.batch;

import com.premisave.wallet.commercial.dto.Views;
import com.premisave.wallet.commercial.entity.CommercialTransaction;
import com.premisave.wallet.commercial.util.Money;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The shapes of sections 6.2 and 6.5 of the endpoint reference. */
public final class BatchViews {

    private BatchViews() {}

    /** The 202 response of a bulk request. */
    public static Map<String, Object> created(PayoutBatch b, List<CommercialTransaction> accepted) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("batchId", b.getBatchId());
        m.put("merchantReference", b.getMerchantReference());
        m.put("mode", b.getMode().name());
        m.put("status", b.getStatus().name());
        m.put("totals", totals(b));
        List<Map<String, Object>> ok = new ArrayList<>();
        for (CommercialTransaction tx : accepted) {
            Map<String, Object> a = new LinkedHashMap<>();
            a.put("merchantReference", tx.getMerchantReference());
            a.put("payoutId", tx.getId());
            ok.add(a);
        }
        m.put("accepted", ok);
        m.put("rejected", rejected(b));
        return m;
    }

    /** The batch as read back, and as the data of payout.batch.completed. */
    public static Map<String, Object> detail(PayoutBatch b, BatchRules.Counters c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("batchId", b.getBatchId());
        m.put("merchantReference", b.getMerchantReference());
        m.put("mode", b.getMode().name());
        m.put("status", b.getStatus().name());
        m.put("totals", totals(b));
        Map<String, Object> counters = new LinkedHashMap<>();
        counters.put("pending", c.pending());
        counters.put("success", c.success());
        counters.put("failed", c.failed());
        counters.put("timedOut", c.timedOut());
        m.put("counters", counters);
        m.put("rejected", rejected(b));
        m.put("callbackUrl", b.getCallbackUrl());
        m.put("appId", b.getAppId());
        m.put("livemode", b.isLivemode());
        m.put("createdAt", Views.iso(b.getCreatedAt()));
        m.put("updatedAt", Views.iso(b.getUpdatedAt()));
        m.put("completedAt", Views.iso(b.getCompletedAt()));
        return m;
    }

    public static Map<String, Object> item(CommercialTransaction tx) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("payoutId", tx.getId());
        m.put("merchantReference", tx.getMerchantReference());
        m.put("status", tx.getStatus().name());
        if (tx.getResult() != null) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("code", tx.getResult().getCode().name());
            r.put("description", tx.getResult().getDescription());
            m.put("result", r);
        }
        return m;
    }

    private static Map<String, Object> totals(PayoutBatch b) {
        Map<String, Object> t = new LinkedHashMap<>();
        t.put("count", b.getAcceptedCount());
        t.put("amount", Money.format(b.getTotalAmountCents()));
        t.put("currency", Money.BASE_CURRENCY);
        t.put("fee", Money.format(b.getTotalFeeCents()));
        return t;
    }

    private static List<Map<String, Object>> rejected(PayoutBatch b) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (PayoutBatch.Rejection r : b.getRejected()) {
            Map<String, Object> x = new LinkedHashMap<>();
            x.put("merchantReference", r.getMerchantReference());
            x.put("code", r.getCode());
            x.put("message", r.getMessage());
            out.add(x);
        }
        return out;
    }
}
