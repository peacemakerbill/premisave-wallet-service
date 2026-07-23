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
    private final ExpressCheckout expressCheckout = new ExpressCheckout();
    private final AccountTopUp accountTopUp = new AccountTopUp();

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
     *
     * commandId must be one of Safaricom's officially supported values:
     * BusinessPayBill, BusinessBuyGoods, DisburseFundsToBusiness,
     * BusinessToBusinessTransfer, BusinessTransferFromMMFToUtility,
     * BusinessTransferFromUtilityToMMF, MerchantToMerchantTransfer,
     * MerchantTransferFromMerchantToWorking, MerchantServicesMMFAccountTransfer,
     * AgencyFloatAdvance.
     * ("BusinessPayToBulk" is a separate concern — see AccountTopUp below;
     * despite living on the same endpoint, Safaricom treats it as a distinct
     * product: B2C Account Top Up, not a generic B2B payment.)
     */
    @Data
    public static class B2b {
        private String initiatorName;
        private String initiatorPassword;
        private String shortcode;
        private String commandId; // BusinessPayBill | BusinessBuyGoods | MerchantToMerchantTransfer | ...
        private String senderIdentifierType = "4";
        private String receiverIdentifierType = "4";
        private String queueTimeoutUrl;
        private String resultUrl;

        private java.math.BigDecimal minAmount = new java.math.BigDecimal("10");
        private java.math.BigDecimal maxAmount = new java.math.BigDecimal("150000");
    }

    /**
     * B2B Express Checkout (USSD Push to Till) — prompts a merchant
     * (identified by their own till number) to pay one of our shortcodes
     * directly from their till via a USSD PIN prompt, instead of STK Push.
     * See https://developer.safaricom.co.ke/apis/B2BExpressCheckout
     *
     * No initiator/security credential needed here — auth happens at the
     * Daraja/Apigee layer via the normal OAuth bearer token (see
     * MpesaService.getAccessToken()).
     */
    @Data
    public static class ExpressCheckout {
        /** Our paybill/shortcode receiving the funds — the API's "receiverShortCode". */
        private String receiverShortCode;
        /** Our organization's friendly name, shown to the paying merchant in the USSD prompt. */
        private String partnerName;
        private String callbackUrl;
    }

    /**
     * B2C Account Top Up — loads funds from Premisave's working/MMF account
     * into a B2C shortcode's utility account, so disbursements (B2C payouts)
     * don't run dry. Uses CommandID "BusinessPayToBulk" — despite the name,
     * this is NOT a bulk-payment operation, it's the official top-up mechanism.
     * See https://developer.safaricom.co.ke/apis/B2CAccountTopUp
     */
    @Data
    public static class AccountTopUp {
        private String initiatorName;
        private String initiatorPassword;
        /** The funding shortcode — money moves FROM here (our working/MMF account). */
        private String partyA;
        private String queueTimeoutUrl;
        private String resultUrl;
    }

    // TODO: add nested TransactionStatus / PullTransactions config classes
    // once those flows get service/controller implementations —
    // application.yml already defines mpesa.daraja.transaction-status.*, etc.
}