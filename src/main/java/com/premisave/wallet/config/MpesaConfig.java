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

    /**
     * Path to Safaricom's public certificate (.cer) used to RSA-encrypt the
     * initiator password into a SecurityCredential for B2C/B2B/TransactionStatus.
     * Sandbox and production use DIFFERENT certificates — download both from
     * https://developer.safaricom.co.ke and point this at the correct one
     * per environment (e.g. via MPESA_CERT_PATH env var per deploy).
     */
    private String certificatePath;

    private final C2b c2b = new C2b();
    private final B2c b2c = new B2c();
    private final B2b b2b = new B2b();

    public String baseUrl() {
        return "sandbox".equalsIgnoreCase(environment)
                ? "https://sandbox.safaricom.co.ke"
                : "https://api.safaricom.co.ke";
    }

    @Data
    public static class C2b {
        private String shortcode;
        private String responseType;
        private String validationUrl;
        private String confirmationUrl;
    }

    /**
     * B2C (Business to Customer — disbursements/payouts to a phone number).
     */
    @Data
    public static class B2c {
        private String initiatorName;
        private String initiatorPassword;
        private String shortcode;
        private String commandId; // BusinessPayment | SalaryPayment | PromotionPayment
        private String queueTimeoutUrl;
        private String resultUrl;

        /** Per-transaction limits — confirm actual tier limits with Safaricom for your shortcode. */
        private java.math.BigDecimal minAmount = new java.math.BigDecimal("10");
        private java.math.BigDecimal maxAmount = new java.math.BigDecimal("150000");
    }

    /**
     * B2B (Business to Business — payments to another paybill/till shortcode).
     * NOTE: B2B is a permissioned API — must be explicitly enabled for your
     * shortcode by Safaricom before this will work in production.
     */
    @Data
    public static class B2b {
        private String initiatorName;
        private String initiatorPassword;
        private String shortcode;
        private String commandId; // BusinessPayBill | BusinessBuyGoods | MerchantToMerchantTransfer
        private String senderIdentifierType = "4";
        private String receiverIdentifierType = "4";
        private String queueTimeoutUrl;
        private String resultUrl;

        private java.math.BigDecimal minAmount = new java.math.BigDecimal("10");
        private java.math.BigDecimal maxAmount = new java.math.BigDecimal("150000");
    }

    // TODO: add nested TransactionStatus / PullTransactions config classes
    // once those flows get service/controller implementations —
    // application.yml already defines mpesa.daraja.transaction-status.*, etc.
}