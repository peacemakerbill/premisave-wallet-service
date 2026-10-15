package com.premisave.wallet.commercial.transfer;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class CommercialTransferServiceTest {

    @Test
    void maskKeepsOnlyTheFirstLetterOfEachWord() {
        assertEquals("S***** O**", CommercialTransferService.maskName("SELLER ONE"));
        assertEquals("J*** D**", CommercialTransferService.maskName("  John   Doe "));
    }

    @Test
    void singleLetterWordsSurvive() {
        assertEquals("A B**", CommercialTransferService.maskName("A Bob"));
    }

    @Test
    void blankOrMissingNameGivesNull() {
        assertNull(CommercialTransferService.maskName(null));
        assertNull(CommercialTransferService.maskName("   "));
    }
}
