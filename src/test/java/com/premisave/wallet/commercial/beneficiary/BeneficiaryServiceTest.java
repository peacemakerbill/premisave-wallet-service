package com.premisave.wallet.commercial.beneficiary;

import com.premisave.wallet.commercial.enums.DestinationType;
import com.premisave.wallet.commercial.enums.Environment;
import com.premisave.wallet.commercial.rail.RailException;
import com.premisave.wallet.commercial.util.RequestValidators;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BeneficiaryServiceTest {

    private static BeneficiaryCheck check(boolean resolved, boolean verified, String matchName, String expected) {
        BeneficiaryCheck c = new BeneficiaryCheck();
        c.setResolved(resolved);
        c.setVerified(verified);
        c.setMatchName(matchName);
        c.setExpectedName(expected);
        return c;
    }

    @Test
    void theSameDestinationAlwaysGivesTheSameKeyWhateverTheFieldOrderOrCase() {
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("type", "BANK_ACCOUNT");
        a.put("country", "KE");
        a.put("bankCode", "01");
        a.put("accountNumber", "1234567890");
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("accountNumber", "1234567890");
        b.put("bankCode", "01");
        b.put("country", "ke");
        b.put("type", "BANK_ACCOUNT");
        assertEquals(BeneficiaryService.destinationKey(DestinationType.BANK_ACCOUNT, a),
                BeneficiaryService.destinationKey(DestinationType.BANK_ACCOUNT, b));
    }

    @Test
    void differentAccountsOrTypesGiveDifferentKeys() {
        Map<String, Object> a = RequestValidators.destination(DestinationType.MOBILE_MONEY,
                Map.of("country", "KE", "phoneNumber", "254712345678"));
        Map<String, Object> b = RequestValidators.destination(DestinationType.MOBILE_MONEY,
                Map.of("country", "KE", "phoneNumber", "254712345679"));
        assertNotEquals(BeneficiaryService.destinationKey(DestinationType.MOBILE_MONEY, a),
                BeneficiaryService.destinationKey(DestinationType.MOBILE_MONEY, b));
        assertNotEquals(BeneficiaryService.destinationKey(DestinationType.MOBILE_MONEY, a),
                BeneficiaryService.destinationKey(DestinationType.BUSINESS_ACCOUNT, a));
    }

    @Test
    void aPhoneWithAndWithoutPlusIsTheSameDestination() {
        Map<String, Object> a = RequestValidators.destination(DestinationType.MOBILE_MONEY,
                Map.of("country", "KE", "phoneNumber", "+254712345678"));
        Map<String, Object> b = RequestValidators.destination(DestinationType.MOBILE_MONEY,
                Map.of("country", "KE", "phoneNumber", "254712345678"));
        assertEquals(BeneficiaryService.destinationKey(DestinationType.MOBILE_MONEY, a),
                BeneficiaryService.destinationKey(DestinationType.MOBILE_MONEY, b));
    }

    @Test
    void aLookupThatGotNoAnswerIsUnavailableWhateverWasExpected() {
        assertEquals(NameMatcher.Match.UNAVAILABLE, BeneficiaryService.matchFor(check(false, false, null, "John Doe")));
        assertEquals(NameMatcher.Match.UNAVAILABLE, BeneficiaryService.matchFor(check(false, false, null, null)));
    }

    @Test
    void anAccountThatDoesNotExistMismatchesOnlyWhenAFNameWasExpected() {
        assertEquals(NameMatcher.Match.MISMATCH, BeneficiaryService.matchFor(check(true, false, null, "John Doe")));
        assertEquals(NameMatcher.Match.NOT_REQUESTED, BeneficiaryService.matchFor(check(true, false, null, null)));
    }

    @Test
    void anExistingAccountIsComparedByName() {
        assertEquals(NameMatcher.Match.EXACT, BeneficiaryService.matchFor(check(true, true, "JOHN DOE", "john doe")));
        assertEquals(NameMatcher.Match.MISMATCH, BeneficiaryService.matchFor(check(true, true, "JOHN DOE", "Alice Wambui")));
        assertEquals(NameMatcher.Match.NOT_REQUESTED, BeneficiaryService.matchFor(check(true, true, "JOHN DOE", null)));
    }

    @Test
    void aCryptoAddressHasNoNameToCompare() {
        assertEquals(NameMatcher.Match.UNAVAILABLE, BeneficiaryService.matchFor(check(true, true, null, "John Doe")));
        assertEquals(NameMatcher.Match.NOT_REQUESTED, BeneficiaryService.matchFor(check(true, true, null, null)));
    }

    @Test
    void sandboxAccountsAreJohnDoeExceptTheMagicEndings() {
        SandboxBeneficiaryResolver r = new SandboxBeneficiaryResolver();
        Map<String, Object> ok = RequestValidators.destination(DestinationType.MOBILE_MONEY,
                Map.of("country", "KE", "phoneNumber", "254712345678"));
        BeneficiaryResolver.Resolution found = r.resolve(Environment.SANDBOX, DestinationType.MOBILE_MONEY, ok);
        assertTrue(found.found());
        assertEquals("JOHN DOE", found.matchName());

        Map<String, Object> none = RequestValidators.destination(DestinationType.MOBILE_MONEY,
                Map.of("country", "KE", "phoneNumber", "254712340001"));
        BeneficiaryResolver.Resolution missing = r.resolve(Environment.SANDBOX, DestinationType.MOBILE_MONEY, none);
        assertFalse(missing.found());
        assertNull(missing.matchName());

        Map<String, Object> down = RequestValidators.destination(DestinationType.MOBILE_MONEY,
                Map.of("country", "KE", "phoneNumber", "254712340002"));
        RailException e = assertThrows(RailException.class,
                () -> r.resolve(Environment.SANDBOX, DestinationType.MOBILE_MONEY, down));
        assertEquals(RailException.Kind.UNAVAILABLE, e.kind());
    }

    @Test
    void theSandboxResolverServesOnlyTheSandbox() {
        SandboxBeneficiaryResolver r = new SandboxBeneficiaryResolver();
        assertTrue(r.supports(Environment.SANDBOX, DestinationType.BANK_ACCOUNT));
        assertFalse(r.supports(Environment.LIVE, DestinationType.BANK_ACCOUNT));
        assertFalse(r.supports(Environment.SANDBOX, DestinationType.CRYPTO));
    }
}
