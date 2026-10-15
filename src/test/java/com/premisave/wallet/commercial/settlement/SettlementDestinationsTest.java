package com.premisave.wallet.commercial.settlement;

import com.premisave.wallet.commercial.entity.SettlementDestination;
import com.premisave.wallet.commercial.enums.DestinationType;
import com.premisave.wallet.commercial.enums.MethodType;
import com.premisave.wallet.commercial.exception.CommercialException;
import com.premisave.wallet.commercial.util.RequestValidators;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SettlementDestinationsTest {

    private static SettlementDestination dest(MethodType type, String country, Map<String, String> details) {
        SettlementDestination d = new SettlementDestination();
        d.setId("dst_1");
        d.setType(type);
        d.setCountry(country);
        d.setDetails(details);
        return d;
    }

    @Test
    void mobileMoneyDestinationPassesPayoutValidation() {
        SettlementDestination d = dest(MethodType.MOBILE_MONEY, "KE", Map.of("phoneNumber", "254712345678"));
        Map<String, Object> cleaned = RequestValidators.destination(DestinationType.MOBILE_MONEY,
                SettlementDestinations.rawDestination(d));
        assertEquals("254712345678", cleaned.get("phoneNumber"));
        assertEquals("KE", cleaned.get("country"));
    }

    @Test
    void bankDestinationBecomesABankAccount() {
        SettlementDestination d = dest(MethodType.BANK_TRANSFER, "KE", Map.of("bankCode", "01", "accountNumber", "1234567890"));
        assertEquals(DestinationType.BANK_ACCOUNT, SettlementDestinations.destinationType(d.getType()));
        Map<String, Object> cleaned = RequestValidators.destination(DestinationType.BANK_ACCOUNT,
                SettlementDestinations.rawDestination(d));
        assertEquals("1234567890", cleaned.get("accountNumber"));
    }

    @Test
    void walletDestinationUsesTheEmailAsAccountRef() {
        SettlementDestination d = dest(MethodType.PREMISAVE_WALLET, null, Map.of("email", "finance@acme.test"));
        assertEquals("finance@acme.test", SettlementDestinations.rawDestination(d).get("accountRef"));
    }

    @Test
    void cardAndCryptoAreNotSettlementDestinations() {
        assertThrows(CommercialException.class, () -> SettlementDestinations.destinationType(MethodType.CARD));
        assertThrows(CommercialException.class, () -> SettlementDestinations.destinationType(MethodType.CRYPTO));
    }

    @Test
    void destinationWithoutCountryFailsValidationNotWithAnNpe() {
        SettlementDestination d = dest(MethodType.MOBILE_MONEY, null, Map.of("phoneNumber", "254712345678"));
        assertThrows(CommercialException.class, () -> RequestValidators.destination(DestinationType.MOBILE_MONEY,
                SettlementDestinations.rawDestination(d)));
    }

    @Test
    void localCurrencyKnownForKenyaOnly() {
        assertEquals("KES", SettlementDestinations.localCurrency("ke"));
        assertNull(SettlementDestinations.localCurrency("UG"));
        assertNull(SettlementDestinations.localCurrency(null));
    }

    @Test
    void conversionRoundsDownToWholeUnits() {
        assertEquals(new BigDecimal("64498"), SettlementDestinations.toLocalWholeUnits(new BigDecimal("499.99"), new BigDecimal("129")));
        assertEquals(new BigDecimal("0"), SettlementDestinations.toLocalWholeUnits(new BigDecimal("0.001"), new BigDecimal("129")));
    }
}
