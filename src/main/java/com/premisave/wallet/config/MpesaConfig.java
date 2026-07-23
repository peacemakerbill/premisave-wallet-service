package com.premisave.wallet.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "mpesa.daraja")
public class MpesaConfig {
    private String consumerKey;
    private String consumerSecret;
    private String shortcode;
    private String passkey;
    private String callbackUrl;
    private String environment; // "sandbox" or "production"

    // Bound from mpesa.daraja.c2b.* — must be initialized (not left null) so
    // Spring Boot's relaxed binder has a live object to populate.
    private final C2b c2b = new C2b();

    public String baseUrl() {
        return "sandbox".equalsIgnoreCase(environment)
                ? "https://sandbox.safaricom.co.ke"
                : "https://api.safaricom.co.ke";
    }

    /**
     * C2B (Customer to Business — Pay Bill) config: Register URL shortcode,
     * response type, and the validation/confirmation URLs to register with Safaricom.
     *
     * NOTE: in Daraja sandbox, the STK Push test shortcode (174379) is NOT the
     * same as your C2B test shortcode. C2B Register URL / Simulate only work
     * against the dedicated C2B test shortcode issued under the C2B API's
     * "Test Credentials" tab in the Daraja portal — set MPESA_C2B_SHORTCODE to that.
     */
    @Data
    public static class C2b {
        private String shortcode;
        private String responseType;
        private String validationUrl;
        private String confirmationUrl;
    }

    // TODO: add nested B2c / B2b / TransactionStatus / PullTransactions config
    // classes here once those flows get service/controller implementations —
    // application.yml already defines mpesa.daraja.b2c.*, .b2b.*, etc.
}