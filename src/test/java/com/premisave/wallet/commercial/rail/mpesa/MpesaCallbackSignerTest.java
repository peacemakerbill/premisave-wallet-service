package com.premisave.wallet.commercial.rail.mpesa;

import com.premisave.wallet.commercial.config.CommercialMpesaProperties;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MpesaCallbackSignerTest {

    private static MpesaCallbackSigner signer(String secret) {
        return new MpesaCallbackSigner(new CommercialMpesaProperties(true, "https://wallet.example.com/", secret, null, null, null));
    }

    @Test
    void urlHasKindTransactionAndSignature() {
        String url = signer("0123456789abcdef").urlFor(MpesaCallbackSigner.STK, "txn_abc");
        assertTrue(url.startsWith("https://wallet.example.com/payments/commercial/rail/stk/txn_abc/"));
        assertFalse(url.toLowerCase().contains("mpesa"));
        String sig = url.substring(url.lastIndexOf('/') + 1);
        assertEquals(32, sig.length());
        assertTrue(signer("0123456789abcdef").verify(MpesaCallbackSigner.STK, "txn_abc", sig));
    }

    @Test
    void signatureBindsKindAndTransaction() {
        MpesaCallbackSigner s = signer("0123456789abcdef");
        String sig = s.signature(MpesaCallbackSigner.STK, "txn_abc");
        assertFalse(s.verify(MpesaCallbackSigner.B2C_RESULT, "txn_abc", sig));
        assertFalse(s.verify(MpesaCallbackSigner.STK, "txn_other", sig));
        assertFalse(s.verify(MpesaCallbackSigner.STK, "txn_abc", null));
        assertFalse(s.verify(MpesaCallbackSigner.STK, "txn_abc", "deadbeef"));
    }

    @Test
    void paybillAddressesAreSignedPerKindAndShortcode() {
        MpesaCallbackSigner s = signer("0123456789abcdef");
        String validation = s.urlFor(MpesaCallbackSigner.C2B_VALIDATION, "600123");
        String confirmation = s.urlFor(MpesaCallbackSigner.C2B_CONFIRMATION, "600123");
        assertTrue(validation.startsWith("https://wallet.example.com/payments/commercial/rail/c2b-validation/600123/"));
        assertTrue(confirmation.startsWith("https://wallet.example.com/payments/commercial/rail/c2b-confirmation/600123/"));
        assertFalse(validation.toLowerCase().contains("mpesa"));
        String sig = validation.substring(validation.lastIndexOf('/') + 1);
        assertTrue(s.verify(MpesaCallbackSigner.C2B_VALIDATION, "600123", sig));
        assertFalse(s.verify(MpesaCallbackSigner.C2B_CONFIRMATION, "600123", sig));
        assertFalse(s.verify(MpesaCallbackSigner.C2B_VALIDATION, "600124", sig));
    }

    @Test
    void differentSecretsGiveDifferentSignatures() {
        String a = signer("0123456789abcdef").signature(MpesaCallbackSigner.STK, "txn_abc");
        String b = signer("fedcba9876543210").signature(MpesaCallbackSigner.STK, "txn_abc");
        assertFalse(a.equals(b));
    }

    @Test
    void refusesToStartWithWeakSettings() {
        assertThrows(IllegalStateException.class, () -> signer("short"));
        assertThrows(IllegalStateException.class,
                () -> new MpesaCallbackSigner(new CommercialMpesaProperties(true, "http://insecure.example.com", "0123456789abcdef", null, null, null)));
        assertNull(new CommercialMpesaProperties(true, "https://x.example.com", "0123456789abcdef", null, null, null).problem());
    }
}
