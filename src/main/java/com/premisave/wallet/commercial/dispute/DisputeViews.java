package com.premisave.wallet.commercial.dispute;

import com.premisave.wallet.commercial.dto.Views;
import com.premisave.wallet.commercial.util.Money;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The Dispute object (section 2.16) with its evidence. */
public final class DisputeViews {

    private DisputeViews() {}

    public static Map<String, Object> dispute(Dispute d) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", d.getId());
        m.put("paymentId", d.getPaymentId());
        m.put("status", d.getStatus().name());
        m.put("amount", Money.format(d.getAmountCents()));
        m.put("currency", Money.BASE_CURRENCY);
        m.put("reason", d.getReason());
        m.put("evidenceDueBy", Views.iso(d.getEvidenceDueBy()));
        List<Map<String, Object>> evidence = new ArrayList<>();
        for (Dispute.Evidence e : d.getEvidence()) evidence.add(evidence(e));
        m.put("evidence", evidence);
        m.put("holdId", d.getHoldId());
        m.put("held", Money.format(d.getHeldCents()));
        m.put("unrecovered", d.getUnrecoveredCents() == 0 ? null : Money.format(d.getUnrecoveredCents()));
        m.put("appId", d.getAppId());
        m.put("livemode", d.isLivemode());
        m.put("version", d.getVersion());
        m.put("createdAt", Views.iso(d.getCreatedAt()));
        m.put("updatedAt", Views.iso(d.getUpdatedAt()));
        m.put("resolvedAt", Views.iso(d.getResolvedAt()));
        return m;
    }

    public static Map<String, Object> evidence(Dispute.Evidence e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", e.getId());
        m.put("type", e.getType());
        m.put("text", e.getText());
        List<Map<String, Object>> files = new ArrayList<>();
        for (Dispute.Attachment a : e.getAttachments()) {
            Map<String, Object> f = new LinkedHashMap<>();
            f.put("name", a.getName());
            f.put("url", a.getUrl());
            f.put("mimeType", a.getMimeType());
            files.add(f);
        }
        m.put("attachments", files);
        m.put("submitted", e.isSubmitted());
        m.put("createdAt", Views.iso(e.getCreatedAt()));
        m.put("submittedAt", Views.iso(e.getSubmittedAt()));
        return m;
    }
}
