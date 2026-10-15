package com.premisave.wallet.commercial.billing;

import com.premisave.wallet.commercial.enums.MethodType;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BillingViewsTest {

    private static final Instant T = Instant.parse("2026-10-14T19:00:00Z");

    private static Plan plan() {
        Plan p = new Plan();
        p.setPlanId("plan_pro_monthly");
        p.setName("Pro monthly");
        p.setAmount(new BigDecimal("10.00"));
        p.setCurrency("USD");
        p.setInterval(Plan.Interval.MONTH);
        p.setIntervalCount(1);
        p.setTrialDays(7);
        p.setSetupFee(new BigDecimal("0.00"));
        p.setAllowedMethods(EnumSet.of(MethodType.MOBILE_MONEY, MethodType.CARD));
        p.setRetryScheduleDays(List.of(1, 3, 5));
        p.setCreatedAt(T);
        return p;
    }

    @Test
    void planMatchesTheDocumentedShape() {
        Map<String, Object> v = BillingViews.plan(plan());
        assertEquals("plan_pro_monthly", v.get("planId"));
        assertEquals("10.00", v.get("amount"));
        assertEquals("MONTH", v.get("interval"));
        assertEquals(List.of("CARD", "MOBILE_MONEY"), v.get("allowedMethods"));
        assertEquals(List.of(1, 3, 5), v.get("retryScheduleDays"));
        assertEquals("CANCEL", v.get("onExhausted"));
        assertEquals("ACTIVE", v.get("status"));
        assertEquals("2026-10-14T19:00:00Z", v.get("createdAt"));
    }

    @Test
    void customerViewMasksThePhoneAndListsLiveMethodsOnly() {
        BillingCustomer c = new BillingCustomer();
        c.setCustomerId("cus_1");
        c.setName("Jane Doe");
        c.setEmail("jane@example.com");
        c.setPhone("254712345678");
        CustomerPaymentMethod active = method("pm_1", CustomerPaymentMethod.Status.ACTIVE);
        active.setDefaultMethod(true);
        CustomerPaymentMethod removed = method("pm_2", CustomerPaymentMethod.Status.REMOVED);
        Map<String, Object> v = BillingViews.customer(c, List.of(active, removed));
        assertEquals("jane@example.com", v.get("email"));
        String phone = String.valueOf(v.get("phone"));
        assertTrue(phone.contains("*"));
        assertFalse(phone.contains("123456"));
        List<?> methods = (List<?>) v.get("paymentMethods");
        assertEquals(1, methods.size());
        assertEquals(true, ((Map<?, ?>) methods.get(0)).get("default"));
    }

    private static CustomerPaymentMethod method(String id, CustomerPaymentMethod.Status status) {
        CustomerPaymentMethod pm = new CustomerPaymentMethod();
        pm.setPaymentMethodId(id);
        pm.setCustomerId("cus_1");
        pm.setType(MethodType.CARD);
        pm.setProvider("STRIPE");
        pm.setLabel("Visa ending 4242");
        pm.setStatus(status);
        pm.setCreatedAt(T);
        return pm;
    }

    @Test
    void theNextActionShowsOnlyWhileTheMethodIsPending() {
        CustomerPaymentMethod pm = method("pm_1", CustomerPaymentMethod.Status.PENDING_AUTHORISATION);
        pm.setNextAction(Map.of("type", "CLIENT_SECRET"));
        assertEquals(Map.of("type", "CLIENT_SECRET"), BillingViews.paymentMethod(pm).get("nextAction"));
        pm.setStatus(CustomerPaymentMethod.Status.ACTIVE);
        assertNull(BillingViews.paymentMethod(pm).get("nextAction"));
    }

    @Test
    void thePaymentMethodViewNeverShowsTheProviderToken() {
        CustomerPaymentMethod pm = method("pm_1", CustomerPaymentMethod.Status.ACTIVE);
        pm.setProviderToken("tok_secret_value");
        pm.getDetails().put("phoneNumber", "254712345678");
        Map<String, Object> v = BillingViews.paymentMethod(pm);
        assertFalse(v.toString().contains("tok_secret_value"));
        assertTrue(String.valueOf(v.get("phoneNumber")).contains("*"));
    }

    private static Subscription subscription(Subscription.State state) {
        Subscription s = new Subscription();
        s.setId("sub_1");
        s.setCustomerId("cus_1");
        s.setPlanId("plan_pro_monthly");
        s.setPaymentMethodId("pm_1");
        s.setState(state);
        s.setTerms(Subscription.Terms.of(plan()));
        s.setPeriodStart(T);
        s.setPeriodEnd(T.plusSeconds(86400L * 31));
        s.setNextBillingAt(T.plusSeconds(86400L * 31));
        s.setCreatedAt(T);
        s.setUpdatedAt(T);
        return s;
    }

    @Test
    void subscriptionViewShowsThePeriodAndTerms() {
        Map<String, Object> v = BillingViews.subscription(subscription(Subscription.State.ACTIVE));
        assertEquals("ACTIVE", v.get("state"));
        assertEquals("2026-10-14T19:00:00Z", ((Map<?, ?>) v.get("currentPeriod")).get("start"));
        assertEquals("2026-11-14T19:00:00Z", v.get("nextBillingAt"));
        assertEquals("10.00", ((Map<?, ?>) v.get("terms")).get("amount"));
        assertEquals(false, v.get("cancelAtPeriodEnd"));
    }

    @Test
    void anEndedSubscriptionHasNoNextBillingDate() {
        Map<String, Object> v = BillingViews.subscription(subscription(Subscription.State.CANCELLED));
        assertNull(v.get("nextBillingAt"));
    }

    @Test
    void invoiceViewListsAttempts() {
        Invoice inv = new Invoice();
        inv.setId("inv_1");
        inv.setSubscriptionId("sub_1");
        inv.setBillingPeriod("2026-10");
        inv.setStatus(Invoice.Status.PAID);
        inv.setAmount(new BigDecimal("10.00"));
        inv.setCurrency("USD");
        inv.setFeeCents(45);
        inv.setDueAt(T);
        inv.setRetryScheduleDays(List.of(1));
        Invoice.Attempt a = InvoiceRules.begin(inv, "pm_1", T);
        InvoiceRules.success(inv, 1, "pay_92bf", 45, T.plusSeconds(5));
        Map<String, Object> v = BillingViews.invoice(inv);
        assertEquals("PAID", v.get("status"));
        assertEquals("0.45", v.get("fee"));
        assertEquals("pay_92bf", v.get("paymentId"));
        List<?> attempts = (List<?>) v.get("attempts");
        assertEquals(1, attempts.size());
        assertEquals("SUCCESS", ((Map<?, ?>) attempts.get(0)).get("status"));
        assertEquals(1, a.getAttempt());
        assertNull(v.get("nextRetryAt"));
    }
}
