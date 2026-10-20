package com.premisave.wallet.commercial.dto.refund;

import com.premisave.wallet.commercial.dto.Views;
import com.premisave.wallet.commercial.entity.CommercialTransaction;
import com.premisave.wallet.commercial.util.Money;

import java.util.LinkedHashMap;
import java.util.Map;

/** The Refund object (section 2.15). */
public final class RefundViews {

    private RefundViews() {}

    public static Map<String, Object> refund(CommercialTransaction tx) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", tx.getId());
        m.put("paymentId", tx.getRelations().get("paymentId"));
        m.put("status", tx.getStatus().name());
        m.put("amount", Money.format(tx.getAmountCents()));
        m.put("currency", Money.BASE_CURRENCY);
        m.put("nativeAmount", tx.getNativeAmount() == null ? null : tx.getNativeAmount().toPlainString());
        m.put("nativeCurrency", tx.getNativeCurrency());
        m.put("fxRate", tx.getFxRate() == null ? null : tx.getFxRate().toPlainString());
        m.put("reason", tx.getDescription());
        m.put("method", tx.getRelations().get("refundMethod"));
        m.put("requiresApproval", "true".equals(tx.getRelations().get("requiresApproval")));
        m.put("merchantReference", tx.getMerchantReference());
        m.put("destination", Views.maskedDestination(tx.getDestination()));
        m.put("result", Views.result(tx.getResult()));
        m.put("providerReference", tx.getProviderReference());
        m.put("callbackUrl", tx.getCallbackUrl());
        m.put("metadata", tx.getMetadata());
        m.put("appId", tx.getAppId());
        m.put("livemode", tx.isLivemode());
        m.put("version", tx.getVersion());
        m.put("createdAt", Views.iso(tx.getCreatedAt()));
        m.put("updatedAt", Views.iso(tx.getUpdatedAt()));
        return m;
    }
}
