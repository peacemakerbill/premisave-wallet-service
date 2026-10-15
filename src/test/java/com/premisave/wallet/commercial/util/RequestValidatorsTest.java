package com.premisave.wallet.commercial.util;

import com.premisave.wallet.commercial.enums.DestinationType;
import com.premisave.wallet.commercial.enums.ErrorCode;
import com.premisave.wallet.commercial.enums.MethodType;
import com.premisave.wallet.commercial.exception.CommercialException;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RequestValidatorsTest {

    private static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    @Test
    void phoneKeepsDigitsAndDropsPlus() {
        assertEquals("254712345678", RequestValidators.normalisePhone("phone", "+254712345678"));
        assertEquals("254712345678", RequestValidators.normalisePhone("phone", " 254712345678 "));
    }

    @Test
    void phoneRejectsBadNumbers() {
        CommercialException e = assertThrows(CommercialException.class, () -> RequestValidators.normalisePhone("phone", "12-34"));
        assertEquals(ErrorCode.VALIDATION_FAILED, e.code());
        assertThrows(CommercialException.class, () -> RequestValidators.normalisePhone("phone", null));
        assertThrows(CommercialException.class, () -> RequestValidators.normalisePhone("phone", "1234567"));
    }

    @Test
    void mobileMoneyCustomerNeedsPhone() {
        Map<String, Object> c = RequestValidators.customerFor(MethodType.MOBILE_MONEY, "Jane", null, "254712345678", null, null, null);
        assertEquals("254712345678", c.get("phone"));
        assertEquals("Jane", c.get("name"));
        assertThrows(CommercialException.class,
                () -> RequestValidators.customerFor(MethodType.MOBILE_MONEY, "Jane", "jane@example.com", null, null, null, null));
    }

    @Test
    void cardAndCryptoCustomersNeedEmail() {
        assertEquals("jane@example.com",
                RequestValidators.customerFor(MethodType.CARD, null, "jane@example.com", null, null, null, null).get("email"));
        assertThrows(CommercialException.class,
                () -> RequestValidators.customerFor(MethodType.CRYPTO, null, "not-an-email", null, null, null, null));
    }

    @Test
    void paypalNeedsReturnAndCancelUrls() {
        assertThrows(CommercialException.class,
                () -> RequestValidators.customerFor(MethodType.PAYPAL, null, "a@b.co", null, null, null, "https://x.test/c"));
        assertThrows(CommercialException.class,
                () -> RequestValidators.customerFor(MethodType.PAYPAL, null, "a@b.co", null, null, "https://x.test/r", null));
        RequestValidators.customerFor(MethodType.PAYPAL, null, "a@b.co", null, null, "https://x.test/r", "https://x.test/c");
    }

    @Test
    void bankTransferNeedsNameAndEmail() {
        assertThrows(CommercialException.class,
                () -> RequestValidators.customerFor(MethodType.BANK_TRANSFER, null, "a@b.co", null, null, null, null));
        assertEquals("Jane Doe",
                RequestValidators.customerFor(MethodType.BANK_TRANSFER, "Jane Doe", "a@b.co", null, null, null, null).get("name"));
    }

    @Test
    void mobileMoneyDestination() {
        Map<String, Object> d = RequestValidators.destination(DestinationType.MOBILE_MONEY,
                map("country", "ke", "phoneNumber", "+254712345678"));
        assertEquals("KE", d.get("country"));
        assertEquals("254712345678", d.get("phoneNumber"));
    }

    @Test
    void mobileMoneyDestinationKeepsAnOptionalNetwork() {
        Map<String, Object> d = RequestValidators.destination(DestinationType.MOBILE_MONEY,
                map("country", "GH", "phoneNumber", "233244123456", "network", " mtn "));
        assertEquals("MTN", d.get("network"));
        assertFalse(RequestValidators.destination(DestinationType.MOBILE_MONEY,
                map("country", "KE", "phoneNumber", "254712345678")).containsKey("network"));
        assertThrows(CommercialException.class, () -> RequestValidators.destination(DestinationType.MOBILE_MONEY,
                map("country", "GH", "phoneNumber", "233244123456", "network", "A".repeat(21))));
    }

    @Test
    void paybillNeedsShortcodeAndAccount() {
        Map<String, Object> d = RequestValidators.destination(DestinationType.BUSINESS_ACCOUNT,
                map("country", "KE", "subtype", "PAYBILL", "shortcode", "123456", "accountNumber", "INV-1"));
        assertEquals("123456", d.get("shortcode"));
        assertThrows(CommercialException.class, () -> RequestValidators.destination(DestinationType.BUSINESS_ACCOUNT,
                map("country", "KE", "subtype", "PAYBILL", "shortcode", "123456")));
        assertThrows(CommercialException.class, () -> RequestValidators.destination(DestinationType.BUSINESS_ACCOUNT,
                map("country", "KE", "subtype", "TILL", "shortcode", "12")));
        RequestValidators.destination(DestinationType.BUSINESS_ACCOUNT,
                map("country", "KE", "subtype", "TILL", "shortcode", "123456"));
    }

    @Test
    void bankAccountDestination() {
        RequestValidators.destination(DestinationType.BANK_ACCOUNT,
                map("country", "NG", "bankCode", "044", "accountNumber", "0123456789"));
        assertThrows(CommercialException.class, () -> RequestValidators.destination(DestinationType.BANK_ACCOUNT,
                map("country", "NG", "bankCode", "044", "accountNumber", "12ab")));
    }

    @Test
    void cryptoDestinationUppercasesCurrencyAndNetwork() {
        Map<String, Object> d = RequestValidators.destination(DestinationType.CRYPTO,
                map("currency", "usdt", "network", "trc20", "address", "TXyz1234567890abcdef"));
        assertEquals("USDT", d.get("currency"));
        assertEquals("TRC20", d.get("network"));
        assertThrows(CommercialException.class, () -> RequestValidators.destination(DestinationType.CRYPTO,
                map("currency", "usdt", "network", "trc20", "address", "short")));
    }

    @Test
    void destinationTypeParsing() {
        assertEquals(DestinationType.PAYPAL, RequestValidators.destinationType(map("type", "paypal")));
        assertThrows(CommercialException.class, () -> RequestValidators.destinationType(map("type", "carrier-pigeon")));
        assertThrows(CommercialException.class, () -> RequestValidators.destinationType(map()));
    }

    @Test
    void metadataRules() {
        Map<String, String> ok = new HashMap<>();
        ok.put("order_id", "1042");
        assertEquals("1042", RequestValidators.metadata(ok).get("order_id"));
        assertTrue(RequestValidators.metadata(null).isEmpty());

        Map<String, String> badKey = new HashMap<>();
        badKey.put("bad key!", "x");
        assertThrows(CommercialException.class, () -> RequestValidators.metadata(badKey));

        Map<String, String> tooMany = new HashMap<>();
        for (int i = 0; i < 21; i++) tooMany.put("k" + i, "v");
        assertThrows(CommercialException.class, () -> RequestValidators.metadata(tooMany));

        Map<String, String> tooLong = new HashMap<>();
        tooLong.put("k", "x".repeat(501));
        assertThrows(CommercialException.class, () -> RequestValidators.metadata(tooLong));
    }
}
