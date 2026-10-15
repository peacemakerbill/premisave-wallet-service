package com.premisave.wallet.commercial.billing;

import com.premisave.wallet.commercial.dto.Views;
import com.premisave.wallet.commercial.util.Masking;
import com.premisave.wallet.commercial.util.Money;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Builds the JSON shapes of sections 2.10 to 2.14. Plain maps, so they can also be the data of an event. */
public final class BillingViews {

    private BillingViews() {}

    public static Map<String, Object> plan(Plan p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("planId", p.getPlanId());
        m.put("name", p.getName());
        m.put("amount", p.getAmount().toPlainString());
        m.put("currency", p.getCurrency());
        m.put("interval", p.getInterval().name());
        m.put("intervalCount", p.getIntervalCount());
        m.put("trialDays", p.getTrialDays());
        m.put("setupFee", p.getSetupFee() == null ? "0.00" : p.getSetupFee().toPlainString());
        m.put("allowedMethods", p.getAllowedMethods().stream().map(Enum::name).sorted().toList());
        m.put("retryScheduleDays", p.getRetryScheduleDays());
        m.put("onExhausted", p.getOnExhausted().name());
        m.put("status", p.getStatus().name());
        m.put("metadata", p.getMetadata());
        m.put("createdAt", Views.iso(p.getCreatedAt()));
        return m;
    }

    public static Map<String, Object> customer(BillingCustomer c, List<CustomerPaymentMethod> methods) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("customerId", c.getCustomerId());
        m.put("name", c.getName());
        m.put("email", c.getEmail());
        m.put("phone", c.getPhone() == null ? null : Masking.phone(c.getPhone()));
        m.put("merchantReference", c.getMerchantReference());
        List<Map<String, Object>> list = new ArrayList<>();
        for (CustomerPaymentMethod pm : methods) {
            if (pm.getStatus() == CustomerPaymentMethod.Status.REMOVED) continue;
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("paymentMethodId", pm.getPaymentMethodId());
            s.put("type", pm.getType().name());
            s.put("label", pm.getLabel());
            s.put("status", pm.getStatus().name());
            s.put("default", pm.isDefaultMethod());
            list.add(s);
        }
        m.put("paymentMethods", list);
        m.put("metadata", c.getMetadata());
        m.put("createdAt", Views.iso(c.getCreatedAt()));
        return m;
    }

    public static Map<String, Object> paymentMethod(CustomerPaymentMethod pm) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("paymentMethodId", pm.getPaymentMethodId());
        m.put("customerId", pm.getCustomerId());
        m.put("type", pm.getType().name());
        m.put("provider", pm.getProvider());
        m.put("label", pm.getLabel());
        m.put("status", pm.getStatus().name());
        m.put("default", pm.isDefaultMethod());
        m.put("nextAction", pm.getStatus() == CustomerPaymentMethod.Status.PENDING_AUTHORISATION ? pm.getNextAction() : null);
        String phone = pm.getDetails().get("phoneNumber");
        if (phone != null) m.put("phoneNumber", Masking.phone(phone));
        String account = pm.getDetails().get("accountRef");
        if (account != null) m.put("accountRef", Masking.account(account));
        m.put("failureReason", pm.getFailureReason());
        m.put("createdAt", Views.iso(pm.getCreatedAt()));
        return m;
    }

    public static Map<String, Object> subscription(Subscription s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", s.getId());
        m.put("customerId", s.getCustomerId());
        m.put("planId", s.getPlanId());
        m.put("paymentMethodId", s.getPaymentMethodId());
        m.put("state", s.getState().name());
        m.put("quantity", s.getQuantity());
        if (s.getTerms() != null) {
            Map<String, Object> terms = new LinkedHashMap<>();
            terms.put("name", s.getTerms().getPlanName());
            terms.put("amount", s.getTerms().getAmount().toPlainString());
            terms.put("currency", s.getTerms().getCurrency());
            terms.put("interval", s.getTerms().getInterval().name());
            terms.put("intervalCount", s.getTerms().getIntervalCount());
            m.put("terms", terms);
        }
        Map<String, Object> period = new LinkedHashMap<>();
        period.put("start", Views.iso(s.getPeriodStart()));
        period.put("end", Views.iso(s.getPeriodEnd()));
        m.put("currentPeriod", period);
        m.put("nextBillingAt", s.getState().isTerminal() ? null : Views.iso(s.getNextBillingAt()));
        m.put("trialEnd", Views.iso(s.getTrialEnd()));
        m.put("cancelAtPeriodEnd", s.isCancelAtPeriodEnd());
        m.put("pendingPlanId", s.getPendingPlanId());
        m.put("pausedAt", Views.iso(s.getPausedAt()));
        m.put("resumeAt", Views.iso(s.getResumeAt()));
        m.put("cancelledAt", Views.iso(s.getCancelledAt()));
        m.put("cancelReason", s.getCancelReason());
        m.put("callbackUrl", s.getCallbackUrl());
        m.put("metadata", s.getMetadata());
        m.put("appId", s.getAppId());
        m.put("livemode", s.isLivemode());
        m.put("version", s.getVersion());
        m.put("createdAt", Views.iso(s.getCreatedAt()));
        m.put("updatedAt", Views.iso(s.getUpdatedAt()));
        return m;
    }

    public static Map<String, Object> invoice(Invoice inv) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", inv.getId());
        m.put("subscriptionId", inv.getSubscriptionId());
        m.put("billingPeriod", inv.getBillingPeriod());
        m.put("status", inv.getStatus().name());
        m.put("amount", inv.getAmount().toPlainString());
        m.put("currency", inv.getCurrency());
        m.put("fee", Money.format(inv.getFeeCents()));
        m.put("periodStart", Views.iso(inv.getPeriodStart()));
        m.put("periodEnd", Views.iso(inv.getPeriodEnd()));
        m.put("dueAt", Views.iso(inv.getDueAt()));
        List<Map<String, Object>> attempts = new ArrayList<>();
        for (Invoice.Attempt a : inv.getAttempts()) {
            Map<String, Object> x = new LinkedHashMap<>();
            x.put("attempt", a.getAttempt());
            x.put("at", Views.iso(a.getAt()));
            x.put("status", a.getStatus());
            x.put("paymentId", a.getPaymentId());
            x.put("code", a.getCode());
            attempts.add(x);
        }
        m.put("attempts", attempts);
        m.put("nextRetryAt", Views.iso(inv.getNextRetryAt()));
        m.put("paymentId", inv.getPaymentId());
        m.put("paidAt", Views.iso(inv.getPaidAt()));
        m.put("createdAt", Views.iso(inv.getCreatedAt()));
        return m;
    }
}
