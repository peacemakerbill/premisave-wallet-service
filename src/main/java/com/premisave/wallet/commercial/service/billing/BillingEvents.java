package com.premisave.wallet.commercial.service.billing;

import com.premisave.wallet.commercial.dto.billing.BillingViews;
import com.premisave.wallet.commercial.entity.billing.CustomerPaymentMethod;
import com.premisave.wallet.commercial.entity.billing.Invoice;
import com.premisave.wallet.commercial.entity.billing.Subscription;
import com.premisave.wallet.commercial.service.EventService;
import com.premisave.wallet.commercial.service.EventTypes;
import com.premisave.wallet.commercial.util.Hashing;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Writes the subscription, invoice and payment method events. Event ids are worked out from what
 * happened, so reporting the same thing twice (after a crash, or a retried step) stores one event.
 * They go to the subscription's own callbackUrl and to the app's registered callbacks.
 */
@Component("commercialBillingEvents")
public class BillingEvents {

    private final EventService events;

    public BillingEvents(EventService events) {
        this.events = events;
    }

    /** The version in the id makes each change of the subscription a separate event. */
    public void subscription(String type, Subscription s) {
        send(type, id(s.getId(), type, String.valueOf(s.getVersion())), s.getMerchantId(), s.getAppId(), s.isLivemode(),
                s.getId(), BillingViews.subscription(s), s.getCallbackUrl());
    }

    /** One event per invoice, type and attempt, so a failed attempt and its retries are reported separately. */
    public void invoice(String type, Invoice inv, Subscription s, int attemptNo) {
        send(type, id(inv.getId(), type, String.valueOf(attemptNo)), inv.getMerchantId(), s.getAppId(), inv.isLivemode(),
                inv.getId(), BillingViews.invoice(inv), s.getCallbackUrl());
    }

    /** The next invoice is a few days away. There is no invoice yet, so the event is about the subscription. */
    public void upcoming(Subscription s, SubscriptionRules.Cycle cycle) {
        Map<String, Object> data = new java.util.LinkedHashMap<>();
        data.put("subscriptionId", s.getId());
        data.put("customerId", s.getCustomerId());
        data.put("planId", cycle.planId());
        data.put("billingPeriod", cycle.label());
        data.put("amount", cycle.amount().toPlainString());
        data.put("currency", cycle.terms().getCurrency());
        data.put("dueAt", cycle.start().toString());
        send(EventTypes.INVOICE_UPCOMING, id(s.getId(), EventTypes.INVOICE_UPCOMING, cycle.label()), s.getMerchantId(),
                s.getAppId(), s.isLivemode(), s.getId(), data, s.getCallbackUrl());
    }

    /** Reported to the app's registered callbacks only; adding a payment method has no callbackUrl of its own. */
    public void paymentMethodActivated(CustomerPaymentMethod pm) {
        if (pm.getAppId() == null) return;
        send(EventTypes.PAYMENT_METHOD_ACTIVATED, id(pm.getPaymentMethodId(), EventTypes.PAYMENT_METHOD_ACTIVATED, "1"),
                pm.getMerchantId(), pm.getAppId(), pm.isLivemode(), pm.getPaymentMethodId(),
                BillingViews.paymentMethod(pm), null);
    }

    private void send(String type, String eventId, String merchantId, String appId, boolean livemode, String objectId,
                      Map<String, Object> data, String callbackUrl) {
        events.emitWithId(eventId, type, merchantId, appId, livemode, objectId, data,
                events.targetsFor(appId, livemode, type, callbackUrl, true));
    }

    private static String id(String object, String type, String discriminator) {
        return "evt_" + Hashing.sha256Hex((object + "|" + type + "|" + discriminator).getBytes()).substring(0, 16);
    }
}
