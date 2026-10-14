package com.premisave.wallet.commercial.rail.mpesa;

import com.premisave.wallet.commercial.config.CommercialMpesaProperties;
import com.premisave.wallet.commercial.enums.Direction;
import com.premisave.wallet.commercial.enums.DestinationType;
import com.premisave.wallet.commercial.enums.Environment;
import com.premisave.wallet.commercial.enums.MethodType;
import com.premisave.wallet.commercial.enums.RailId;
import com.premisave.wallet.commercial.enums.ResultCode;
import com.premisave.wallet.commercial.rail.CollectCommand;
import com.premisave.wallet.commercial.rail.PaymentRail;
import com.premisave.wallet.commercial.rail.PayoutCommand;
import com.premisave.wallet.commercial.rail.RailCapability;
import com.premisave.wallet.commercial.rail.RailException;
import com.premisave.wallet.commercial.rail.RailResponse;
import com.premisave.wallet.commercial.service.FxConverter;
import com.premisave.wallet.commercial.util.Masking;
import com.premisave.wallet.config.MpesaConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Live M-Pesa for the commercial API: customer payments by STK push and
 * payouts to a phone number by B2C. Results arrive on the signed callback
 * addresses handled by MpesaCallbackController, with MpesaStkReconciler as a
 * safety net for payment callbacks that never come.
 *
 * Serves the live environment only. Pointing mpesa.daraja.environment at
 * Safaricom's sandbox lets the whole live path be exercised with test money.
 *
 * Not covered yet: payouts to paybills and tills (B2B) are refused with a clear message.
 */
@Component("commercialMpesaRail")
@ConditionalOnProperty(prefix = "commercial.mpesa", name = "enabled", havingValue = "true")
public class MpesaRail implements PaymentRail {

    private static final Logger log = LoggerFactory.getLogger(MpesaRail.class);
    private static final Pattern KENYAN_MSISDN = Pattern.compile("^254[17]\\d{8}$");
    private static final BigDecimal FALLBACK_KES_PER_USD = new BigDecimal("129");

    private final MpesaDarajaClient client;
    private final MpesaCallbackSigner signer;
    private final MpesaConfig config;
    private final FxConverter fx;
    private final CommercialMpesaProperties props;

    public MpesaRail(MpesaDarajaClient client, MpesaCallbackSigner signer, MpesaConfig config, FxConverter fx,
                     CommercialMpesaProperties props) {
        this.client = client;
        this.signer = signer;
        this.config = config;
        this.fx = fx;
        this.props = props;
    }

    @Override
    public RailId id() { return RailId.MPESA; }

    @Override
    public Set<Environment> environments() { return Set.of(Environment.LIVE); }

    @Override
    public List<RailCapability> capabilities() {
        BigDecimal rate = kesPerUsd();
        MpesaConfig.B2c b2c = config.getB2c();
        return List.of(
                new RailCapability(MethodType.MOBILE_MONEY, Direction.COLLECTION, List.of("KE"), List.of("KES"),
                        usdCents(BigDecimal.ONE, rate), usdCents(BigDecimal.valueOf(props.collectionMaxKes()), rate),
                        "INSTANT", "CUSTOMER_PROMPT"),
                new RailCapability(MethodType.MOBILE_MONEY, Direction.PAYOUT, List.of("KE"), List.of("KES"),
                        usdCents(b2c.getMinAmount(), rate), usdCents(b2c.getMaxAmount(), rate),
                        "INSTANT", "NONE"));
    }

    @Override
    public String providerName(MethodType method) { return "MPESA"; }

    @Override
    public RailResponse collect(CollectCommand c) {
        if (c.method() != MethodType.MOBILE_MONEY) {
            throw rejected(ResultCode.DECLINED, "The M-Pesa rail only takes mobile money payments");
        }
        long kes = wholeShillings(c.nativeAmount(), c.nativeCurrency());
        if (kes > props.collectionMaxKes()) {
            throw rejected(ResultCode.LIMIT_EXCEEDED, "M-Pesa payments are limited to KES " + props.collectionMaxKes());
        }
        String phone = msisdn(c.customer() == null ? null : c.customer().get("phone"), "customer.phone");
        String reference = c.customer() == null ? null : asText(c.customer().get("accountRef"));

        MpesaDarajaClient.StkAccepted accepted;
        try {
            accepted = client.stkPush(phone, kes, reference, c.description(), signer.urlFor(MpesaCallbackSigner.STK, c.transactionId()));
        } catch (MpesaDarajaClient.NotSent e) {
            throw new RailException(RailException.Kind.UNAVAILABLE, ResultCode.PROVIDER_ERROR, e.getMessage());
        } catch (MpesaDarajaClient.Unconfirmed e) {
            // The prompt may have reached the customer. Keep the payment open: the signed callback
            // still finds it by id, and it expires on its own if nothing ever arrives.
            log.warn("STK push for {} is unconfirmed ({}); waiting for the callback", c.transactionId(), e.getMessage());
            return RailResponse.requiresAction(prompt(phone), null);
        }
        return RailResponse.requiresAction(prompt(phone), accepted.checkoutRequestId());
    }

    @Override
    public RailResponse payout(PayoutCommand c) {
        if (c.destinationType() != DestinationType.MOBILE_MONEY) {
            throw rejected(ResultCode.INVALID_DESTINATION,
                    "The M-Pesa rail can pay to a phone number only; paybill and till payouts are not available yet");
        }
        long kes = wholeShillings(c.nativeAmount(), c.nativeCurrency());
        MpesaConfig.B2c b2c = config.getB2c();
        if (BigDecimal.valueOf(kes).compareTo(b2c.getMinAmount()) < 0 || BigDecimal.valueOf(kes).compareTo(b2c.getMaxAmount()) > 0) {
            throw rejected(ResultCode.LIMIT_EXCEEDED,
                    "M-Pesa payouts must be between KES " + b2c.getMinAmount().toPlainString() + " and KES " + b2c.getMaxAmount().toPlainString());
        }
        String phone = msisdn(c.destination() == null ? null : c.destination().get("phoneNumber"), "destination.phoneNumber");

        MpesaDarajaClient.B2cAccepted accepted;
        try {
            accepted = client.b2c(phone, kes, c.remarks(), c.transactionId(),
                    signer.urlFor(MpesaCallbackSigner.B2C_RESULT, c.transactionId()),
                    signer.urlFor(MpesaCallbackSigner.B2C_TIMEOUT, c.transactionId()));
        } catch (MpesaDarajaClient.NotSent e) {
            throw new RailException(RailException.Kind.UNAVAILABLE, ResultCode.PROVIDER_ERROR, e.getMessage());
        } catch (MpesaDarajaClient.Unconfirmed e) {
            // The payout may already be on its way. Failing it here would release the held money
            // while the recipient could still be paid, so it stays pending for the result callback.
            log.error("B2C for {} is unconfirmed ({}); keeping it pending for the result callback", c.transactionId(), e.getMessage());
            return RailResponse.pending(null);
        }
        return RailResponse.pending(accepted.conversationId());
    }

    // ------------------------------------------------------------------ helpers

    private static Map<String, Object> prompt(String phone) {
        Map<String, Object> n = new LinkedHashMap<>();
        n.put("type", "CUSTOMER_PROMPT");
        n.put("instructions", "A payment prompt was sent to " + Masking.phone(phone) + ". Enter your M-Pesa PIN to approve.");
        return n;
    }

    /** M-Pesa moves whole shillings only, and only in KES. */
    static long wholeShillings(BigDecimal amount, String currency) {
        if (currency == null || !currency.equalsIgnoreCase("KES")) {
            throw rejected(ResultCode.DECLINED, "M-Pesa works in KES only");
        }
        if (amount == null || amount.signum() <= 0) {
            throw rejected(ResultCode.DECLINED, "The amount must be greater than zero");
        }
        if (amount.stripTrailingZeros().scale() > 0) {
            throw rejected(ResultCode.DECLINED, "M-Pesa amounts must be whole shillings");
        }
        return amount.setScale(0, RoundingMode.UNNECESSARY).longValueExact();
    }

    /** Accepts 2547XXXXXXXX or 2541XXXXXXXX, also with a leading plus or zero. */
    static String msisdn(Object raw, String field) {
        String digits = raw == null ? "" : String.valueOf(raw).replaceAll("[^0-9]", "");
        if (digits.startsWith("0") && digits.length() == 10) digits = "254" + digits.substring(1);
        if (!KENYAN_MSISDN.matcher(digits).matches()) {
            throw rejected(ResultCode.INVALID_DESTINATION, field + " must be a Kenyan M-Pesa number such as 254712345678");
        }
        return digits;
    }

    private static RailException rejected(ResultCode code, String message) {
        return new RailException(RailException.Kind.REJECTED, code, message);
    }

    private static String asText(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private BigDecimal kesPerUsd() {
        try {
            BigDecimal rate = fx.nativePerUsd("KES");
            return rate != null && rate.signum() > 0 ? rate : FALLBACK_KES_PER_USD;
        } catch (RuntimeException e) {
            return FALLBACK_KES_PER_USD;
        }
    }

    /** USD cents for a KES amount, rounded up for a minimum and down never below one cent. */
    static long usdCents(BigDecimal kes, BigDecimal kesPerUsd) {
        long cents = kes.multiply(BigDecimal.valueOf(100)).divide(kesPerUsd, 0, RoundingMode.CEILING).longValue();
        return Math.max(1, cents);
    }
}
