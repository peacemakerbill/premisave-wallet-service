package com.premisave.wallet.commercial.rail.flutterwave;

import com.premisave.wallet.commercial.enums.ResultCode;
import com.premisave.wallet.commercial.enums.TxStatus;
import com.premisave.wallet.commercial.rail.RailOutcome;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FlutterwaveMapperTest {

    @Test
    void dialCodesPerCurrency() {
        assertEquals("233", FlutterwaveMapper.dialCode("GHS"));
        assertEquals("256", FlutterwaveMapper.dialCode("ugx"));
        assertNull(FlutterwaveMapper.dialCode("USD"));
        assertNull(FlutterwaveMapper.dialCode(null));
    }

    @Test
    void chargePhoneNumbersLoseTheirCountryCodeAndZero() {
        assertEquals("244123456", FlutterwaveMapper.nationalNumber("+233 244 123 456", "233"));
        assertEquals("244123456", FlutterwaveMapper.nationalNumber("0244123456", "233"));
        assertEquals("244123456", FlutterwaveMapper.nationalNumber("244123456", "233"));
        assertEquals("", FlutterwaveMapper.nationalNumber(null, "233"));
    }

    @Test
    void transferPhoneNumbersGainTheirCountryCode() {
        assertEquals("233244123456", FlutterwaveMapper.fullNumber("0244123456", "233"));
        assertEquals("233244123456", FlutterwaveMapper.fullNumber("+233244123456", "233"));
        assertEquals("233244123456", FlutterwaveMapper.fullNumber("244123456", "233"));
    }

    @Test
    void namesSplitIntoFirstAndLast() {
        assertEquals(Arrays.asList("Ama", "Mensah Boateng"), Arrays.asList(FlutterwaveMapper.splitName("Ama Mensah Boateng", "Recipient")));
        assertEquals(Arrays.asList("Ama", null), Arrays.asList(FlutterwaveMapper.splitName("Ama", "Recipient")));
        assertEquals(Arrays.asList("Recipient", null), Arrays.asList(FlutterwaveMapper.splitName("  ", "Recipient")));
    }

    @Test
    void placeholderEmailUsesTheTransactionId() {
        assertEquals("c.pay_abc@payments.premisave.com", FlutterwaveMapper.placeholderEmail("pay_abc", "payments.premisave.com"));
    }

    @Test
    void succeededChargeForTheRightAmountIsPaid() {
        RailOutcome o = FlutterwaveMapper.charge("succeeded", new BigDecimal("100.00"), "GHS", new BigDecimal("100"), "GHS", "chg_1");
        assertEquals(TxStatus.SUCCESS, o.status());
        assertEquals("chg_1", o.providerReference());
    }

    @Test
    void succeededChargeForAnotherAmountOrCurrencyIsNotApplied() {
        assertNull(FlutterwaveMapper.charge("succeeded", new BigDecimal("90"), "GHS", new BigDecimal("100"), "GHS", "chg_1"));
        assertNull(FlutterwaveMapper.charge("succeeded", new BigDecimal("100"), "NGN", new BigDecimal("100"), "GHS", "chg_1"));
        assertTrue(FlutterwaveMapper.paidWrongAmount("succeeded", new BigDecimal("90"), "GHS", new BigDecimal("100"), "GHS"));
        assertTrue(FlutterwaveMapper.paidWrongAmount("succeeded", new BigDecimal("100"), "NGN", new BigDecimal("100"), "GHS"));
        assertFalse(FlutterwaveMapper.paidWrongAmount("succeeded", new BigDecimal("100.00"), "GHS", new BigDecimal("100"), "GHS"));
        assertFalse(FlutterwaveMapper.paidWrongAmount("failed", new BigDecimal("90"), "GHS", new BigDecimal("100"), "GHS"));
    }

    @Test
    void failedAndPendingCharges() {
        RailOutcome failed = FlutterwaveMapper.charge("failed", null, null, new BigDecimal("100"), "GHS", "chg_1");
        assertEquals(TxStatus.FAILED, failed.status());
        assertEquals(ResultCode.DECLINED, failed.code());
        assertNull(FlutterwaveMapper.charge("pending", null, null, new BigDecimal("100"), "GHS", "chg_1"));
        assertNull(FlutterwaveMapper.charge(null, null, null, new BigDecimal("100"), "GHS", "chg_1"));
    }

    @Test
    void transferStatusIsReadFromTheLookupMessage() {
        assertEquals("SUCCESSFUL", FlutterwaveMapper.transferStatus("Transfer status: successful"));
        assertEquals("NEW", FlutterwaveMapper.transferStatus("Transfer status: NEW"));
        assertNull(FlutterwaveMapper.transferStatus("Query failed: timeout"));
        assertNull(FlutterwaveMapper.transferStatus("Transfer status: "));
        assertNull(FlutterwaveMapper.transferStatus(null));
    }

    @Test
    void transferOutcomes() {
        assertEquals(TxStatus.SUCCESS, FlutterwaveMapper.transfer("SUCCESSFUL", "trf_1").status());
        assertEquals(TxStatus.FAILED, FlutterwaveMapper.transfer("FAILED", "trf_1").status());
        assertNull(FlutterwaveMapper.transfer("PENDING", "trf_1"));
        assertNull(FlutterwaveMapper.transfer("NEW", "trf_1"));
        assertNull(FlutterwaveMapper.transfer(null, "trf_1"));
    }
}
