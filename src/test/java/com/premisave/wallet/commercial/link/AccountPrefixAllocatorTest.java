package com.premisave.wallet.commercial.link;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AccountPrefixAllocatorTest {

    @Test
    void randomDigitsHaveTheRequestedWidthAndNeverStartWithZero() {
        for (int i = 0; i < 500; i++) {
            String d = AccountPrefixAllocator.randomDigits(4);
            assertEquals(4, d.length());
            assertNotEquals('0', d.charAt(0));
            assertTrue(d.chars().allMatch(Character::isDigit));
        }
    }

    @Test
    void theKeySeparatesEnvironments() {
        assertNotEquals(AccountPrefix.keyFor("mrc_1", true), AccountPrefix.keyFor("mrc_1", false));
    }
}
