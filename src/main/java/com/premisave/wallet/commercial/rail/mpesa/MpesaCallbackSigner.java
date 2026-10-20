package com.premisave.wallet.commercial.rail.mpesa;

import com.premisave.wallet.commercial.config.properties.CommercialMpesaProperties;
import com.premisave.wallet.commercial.util.Hashing;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Builds and checks the callback addresses given to Safaricom. Each address
 * names one transaction and carries a signature of that transaction and of
 * the kind of callback, so a forged or replayed-elsewhere call is refused
 * before anything is parsed.
 *
 * Address shape: {base}/payments/commercial/rail/{kind}/{transactionId}/{signature}
 * The path avoids the word "mpesa" on purpose: Safaricom's sandbox rejects
 * callback URLs that contain it.
 */
@Component("commercialMpesaCallbackSigner")
@ConditionalOnProperty(prefix = "commercial.mpesa", name = "enabled", havingValue = "true")
public class MpesaCallbackSigner {

    public static final String BASE_PATH = "/payments/commercial/rail";
    public static final String STK = "stk";
    public static final String B2C_RESULT = "b2c-result";
    public static final String B2C_TIMEOUT = "b2c-timeout";
    public static final String C2B_VALIDATION = "c2b-validation";
    public static final String C2B_CONFIRMATION = "c2b-confirmation";

    private final String base;
    private final String secret;

    public MpesaCallbackSigner(CommercialMpesaProperties props) {
        String problem = props.problem();
        if (problem != null) {
            throw new IllegalStateException("The commercial M-Pesa rail is enabled but " + problem);
        }
        String b = props.callbackBaseUrl().trim();
        this.base = b.endsWith("/") ? b.substring(0, b.length() - 1) : b;
        this.secret = props.callbackSecret();
    }

    public String urlFor(String kind, String transactionId) {
        return base + BASE_PATH + "/" + kind + "/" + transactionId + "/" + signature(kind, transactionId);
    }

    public boolean verify(String kind, String transactionId, String signature) {
        return signature != null && Hashing.constantTimeEquals(signature(kind, transactionId), signature);
    }

    String signature(String kind, String transactionId) {
        return Hashing.hmacSha256Hex(secret, kind + ":" + transactionId).substring(0, 32);
    }
}
