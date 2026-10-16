package com.premisave.wallet.commercial.billing;

import com.premisave.wallet.commercial.dto.CreatePaymentRequest;
import com.premisave.wallet.commercial.enums.MethodType;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BillingEngineRequestTest {

    private static Subscription subscription() {
        Plan p = new Plan();
        p.setName("Pro monthly");
        p.setAmount(new BigDecimal("10.00"));
        p.setCurrency("USD");
        p.setInterval(Plan.Interval.MONTH);
        p.setRetryScheduleDays(List.of(1));
        Subscription s = new Subscription();
        s.setId("sub_1");
        s.setCustomerId("cus_1");
        s.setTerms(Subscription.Terms.of(p));
        s.setCallbackUrl("https://merchant.example.com/hooks");
        s.getMetadata().put("tier", "pro");
        s.getMetadata().put("invoiceId", "should_be_replaced");
        return s;
    }

    private static Invoice invoice() {
        Invoice inv = new Invoice();
        inv.setId("inv_1");
        inv.setBillingPeriod("2026-10");
        inv.setAmount(new BigDecimal("10.00"));
        inv.setCurrency("USD");
        return inv;
    }

    private static BillingCustomer customer() {
        BillingCustomer c = new BillingCustomer();
        c.setCustomerId("cus_1");
        c.setName("Jane Doe");
        c.setEmail("jane@example.com");
        c.setPhone("254700000000");
        return c;
    }

    private static CustomerPaymentMethod method(MethodType type) {
        CustomerPaymentMethod pm = new CustomerPaymentMethod();
        pm.setPaymentMethodId("pm_1");
        pm.setType(type);
        pm.setProvider("X");
        pm.setStatus(CustomerPaymentMethod.Status.ACTIVE);
        return pm;
    }

    @Test
    void aCardIsChargedByItsProviderToken() {
        CustomerPaymentMethod pm = method(MethodType.CARD);
        pm.setProviderToken("tok_abc");
        CreatePaymentRequest r = BillingEngine.paymentRequest(subscription(), invoice(), customer(), pm);
        assertEquals(MethodType.CARD, r.method().type());
        assertEquals("tok_abc", r.method().paymentMethodId());
        assertEquals("X", r.method().provider());
        assertEquals("jane@example.com", r.customer().email());
        assertEquals(new BigDecimal("10.00"), r.amount());
        assertEquals("USD", r.currency());
        assertEquals("inv_1", r.merchantReference());
    }

    @Test
    void withoutATokenThePaymentMethodIdIsUsed() {
        CreatePaymentRequest r = BillingEngine.paymentRequest(subscription(), invoice(), customer(), method(MethodType.CARD));
        assertEquals("pm_1", r.method().paymentMethodId());
    }

    @Test
    void mobileMoneyPromptsTheNumberSavedOnTheMethod() {
        CustomerPaymentMethod pm = method(MethodType.MOBILE_MONEY);
        pm.getDetails().put("phoneNumber", "254711111111");
        CreatePaymentRequest r = BillingEngine.paymentRequest(subscription(), invoice(), customer(), pm);
        assertEquals("254711111111", r.customer().phone());
    }

    @Test
    void aWalletMethodSendsItsAccountReference() {
        CustomerPaymentMethod pm = method(MethodType.PREMISAVE_WALLET);
        pm.getDetails().put("accountRef", "PS-1234");
        CreatePaymentRequest r = BillingEngine.paymentRequest(subscription(), invoice(), customer(), pm);
        assertEquals("PS-1234", r.customer().accountRef());
    }

    @Test
    void payPalReturnsToTheSavedReturnUrl() {
        CustomerPaymentMethod pm = method(MethodType.PAYPAL);
        pm.setReturnUrl("https://merchant.example.com/return");
        CreatePaymentRequest r = BillingEngine.paymentRequest(subscription(), invoice(), customer(), pm);
        assertEquals("https://merchant.example.com/return", r.method().returnUrl());
        assertEquals("https://merchant.example.com/return", r.method().cancelUrl());
    }

    @Test
    void theSubscriptionsCallbackIsUsedAndRegisteredOnesAreNotified() {
        CreatePaymentRequest r = BillingEngine.paymentRequest(subscription(), invoice(), customer(), method(MethodType.CARD));
        assertEquals("https://merchant.example.com/hooks", r.callbackUrl());
        assertEquals(Boolean.TRUE, r.notifyRegistered());
        assertNull(r.quoteId());
    }

    @Test
    void paymentMetadataCarriesTheBillingKeysAndTheMerchantsOwn() {
        CreatePaymentRequest r = BillingEngine.paymentRequest(subscription(), invoice(), customer(), method(MethodType.CARD));
        assertEquals("pro", r.metadata().get("tier"));
        assertEquals("inv_1", r.metadata().get("invoiceId"));
        assertEquals("sub_1", r.metadata().get("subscriptionId"));
        assertEquals("cus_1", r.metadata().get("customerId"));
        assertEquals("2026-10", r.metadata().get("billingPeriod"));
    }

    @Test
    void theDescriptionNamesThePlanAndPeriodAndStaysShort() {
        CreatePaymentRequest r = BillingEngine.paymentRequest(subscription(), invoice(), customer(), method(MethodType.CARD));
        assertEquals("Pro monthly 2026-10", r.description());
        Subscription s = subscription();
        s.getTerms();
        Invoice inv = invoice();
        inv.setBillingPeriod("x".repeat(300));
        assertTrue(BillingEngine.paymentRequest(s, inv, customer(), method(MethodType.CARD)).description().length() <= 200);
    }
}
