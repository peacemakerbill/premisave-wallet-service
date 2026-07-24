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
    private final TransactionStatus transactionStatus = new TransactionStatus();
    private final AccountBalance accountBalance = new AccountBalance();
    private final Reversal reversal = new Reversal();
    private final B2Pochi b2Pochi = new B2Pochi();
    private final PullTransactions pullTransactions = new PullTransactions();

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

    /**
     * Transaction Status — secondary reconciliation mechanism for when a
     * B2C/B2B/C2B/Reversal ResultURL callback never arrives. Query by either
     * TransactionID (M-Pesa receipt) or OriginalConversationID.
     * See https://developer.safaricom.co.ke/apis/TransactionStatus
     */
    @Data
    public static class TransactionStatus {
        private String initiatorName;
        private String initiatorPassword;
        /** Own shortcode being queried against — PartyA. */
        private String partyA;
        /** 1=MSISDN, 2=Till, 4=Shortcode. Shortcodes use 4. */
        private String identifierType = "4";
        private String queueTimeoutUrl;
        private String resultUrl;
    }

    /**
     * Account Balance — real-time balance inquiry across the Working (MMF),
     * Utility, and Charges Paid accounts for our own shortcode.
     * See https://developer.safaricom.co.ke/apis/AccountBalance
     */
    @Data
    public static class AccountBalance {
        private String initiatorName;
        private String initiatorPassword;
        private String partyA;
        private String identifierType = "4";
        private String queueTimeoutUrl;
        private String resultUrl;
    }

    /**
     * Reversal — reverses a completed C2B transaction, refunding the
     * customer and debiting our shortcode. B2C payouts cannot be reversed
     * via this API (Safaricom portal only).
     * See https://developer.safaricom.co.ke/apis/Reversal
     */
    @Data
    public static class Reversal {
        private String initiatorName;
        private String initiatorPassword;
        /** Our own shortcode — the ReceiverParty debited by the reversal. */
        private String receiverParty;
        /** Must be "11" per Safaricom's Reversal spec. */
        private String receiverIdentifierType = "11";
        private String queueTimeoutUrl;
        private String resultUrl;
    }

    /**
     * B2Pochi (Business to Pochi la Biashara) — a B2C variant that pays
     * directly into a customer's Pochi business wallet instead of their
     * main M-Pesa balance. Requires a B2C shortcode / "one account" capable
     * of both receiving and disbursing.
     * See https://developer.safaricom.co.ke/apis/BusinessToPochi
     */
    @Data
    public static class B2Pochi {
        private String initiatorName;
        private String initiatorPassword;
        /** B2C shortcode money is sent FROM. */
        private String partyA;
        private String queueTimeoutUrl;
        private String resultUrl;

        private java.math.BigDecimal minAmount = new java.math.BigDecimal("10");
        private java.math.BigDecimal maxAmount = new java.math.BigDecimal("250000");
    }

    /**
     * Pull Transactions — reconciliation API that lets us query all C2B
     * transactions performed under our shortcode within the last 48 hours,
     * to recover any that failed to reach our C2B confirmation callback.
     * Unlike every other "operational" M-Pesa API in this config, this one
     * needs NO initiator name/password/SecurityCredential — auth is via the
     * normal OAuth bearer token only, same as STK Push and C2B Register URL.
     * See https://developer.safaricom.co.ke/apis/PullTransaction
     */
    @Data
    public static class PullTransactions {
        /** Defaults to the root shortcode if unset. */
        private String shortcode;
        /**
         * The Safaricom MSISDN associated with the organization account,
         * found on the M-PESA portal under the shortcode's KYC details.
         * Required by Safaricom's Register Pull request.
         */
        private String nominatedNumber;
        /** Our own webhook — required by Register Pull; exact push payload is undocumented by Safaricom. */
        private String callbackUrl;
        /** Default lookback window (days) when a query doesn't specify start/end dates. Max 2 (48h retention). */
        private int pullDays = 1;
        private int offsetValue = 0;
    }

    // TODO: registration of PullTransactions is a one-time step per shortcode —
    // see MpesaService.registerPullTransactions() / PullTransactionService.
}