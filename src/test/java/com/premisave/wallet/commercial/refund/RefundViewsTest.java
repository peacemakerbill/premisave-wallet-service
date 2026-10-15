package com.premisave.wallet.commercial.refund;

import com.premisave.wallet.commercial.entity.CommercialTransaction;
import com.premisave.wallet.commercial.enums.ResultCode;
import com.premisave.wallet.commercial.enums.TxStatus;
import com.premisave.wallet.commercial.enums.TxType;
import com.premisave.wallet.commercial.entity.TxResult;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RefundViewsTest {

    private static CommercialTransaction refund() {
        CommercialTransaction t = new CommercialTransaction();
        t.setId("rf_1");
        t.setType(TxType.REFUND);
        t.setStatus(TxStatus.PENDING);
        t.setAmountCents(1000);
        t.setNativeAmount(new BigDecimal("1290.00"));
        t.setNativeCurrency("KES");
        t.setFxRate(new BigDecimal("129.00"));
        t.setDescription("Customer returned item");
        t.setMerchantReference("RETURN-1042");
        Map<String, Object> dest = new LinkedHashMap<>();
        dest.put("type", "MOBILE_MONEY");
        dest.put("phoneNumber", "254712345678");
        t.setDestination(dest);
        t.getRelations().put("paymentId", "pay_81ae");
        t.getRelations().put("refundMethod", "PAYOUT_TO_CUSTOMER");
        t.getRelations().put("requiresApproval", "true");
        return t;
    }

    @Test
    @SuppressWarnings("unchecked")
    void theViewCarriesTheSpecifiedFieldsAndMasksThePhone() {
        Map<String, Object> v = RefundViews.refund(refund());
        assertEquals("rf_1", v.get("id"));
        assertEquals("pay_81ae", v.get("paymentId"));
        assertEquals("PENDING", v.get("status"));
        assertEquals("10.00", v.get("amount"));
        assertEquals("USD", v.get("currency"));
        assertEquals("1290.00", v.get("nativeAmount"));
        assertEquals("Customer returned item", v.get("reason"));
        assertEquals("PAYOUT_TO_CUSTOMER", v.get("method"));
        assertEquals(true, v.get("requiresApproval"));
        assertNull(v.get("result"));
        String phone = String.valueOf(((Map<String, Object>) v.get("destination")).get("phoneNumber"));
        assertFalse(phone.contains("254712345678"));
    }

    @Test
    void approvalIsFalseWhenNotFlaggedAndResultAppearsWhenFinal() {
        CommercialTransaction t = refund();
        t.getRelations().remove("requiresApproval");
        t.setStatus(TxStatus.FAILED);
        t.setResult(new TxResult(ResultCode.DECLINED, "Declined", null, null));
        Map<String, Object> v = RefundViews.refund(t);
        assertEquals(false, v.get("requiresApproval"));
        assertTrue(v.get("result") != null);
    }
}
