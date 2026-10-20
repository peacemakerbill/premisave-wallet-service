package com.premisave.wallet.commercial.rail.flutterwave;

import com.premisave.wallet.commercial.config.properties.CommercialRailsProperties;
import com.premisave.wallet.commercial.entity.CommercialTransaction;
import com.premisave.wallet.commercial.enums.DestinationType;
import com.premisave.wallet.commercial.enums.Direction;
import com.premisave.wallet.commercial.enums.Environment;
import com.premisave.wallet.commercial.enums.MethodType;
import com.premisave.wallet.commercial.enums.RailId;
import com.premisave.wallet.commercial.enums.ResultCode;
import com.premisave.wallet.commercial.enums.TxType;
import com.premisave.wallet.commercial.rail.CollectCommand;
import com.premisave.wallet.commercial.rail.PaymentRail;
import com.premisave.wallet.commercial.rail.PayoutCommand;
import com.premisave.wallet.commercial.rail.RailCapability;
import com.premisave.wallet.commercial.rail.RailException;
import com.premisave.wallet.commercial.rail.RailOutcome;
import com.premisave.wallet.commercial.rail.RailResponse;
import com.premisave.wallet.service.FlutterwaveService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Flutterwave for the commercial API: mobile money collections outside Kenya (the customer approves a prompt
 * on their phone), and payouts to bank accounts and to mobile money wallets in local currency. It wraps the
 * wallet's FlutterwaveService, so the same credentials, token handling and request shapes apply.
 *
 * Flutterwave's webhook address is shared with the wallet service, so results are found by looking them up
 * (RailPoller). Collections cannot be recalled once the customer has been prompted, and Flutterwave payments
 * cannot be refunded here; a mobile money refund falls back to a payout where one exists for that currency.
 *
 * Kenyan mobile money stays on the M-Pesa rail. Serves the live environment only; Flutterwave's sandbox
 * credentials exercise the whole path.
 */
@Component("commercialFlutterwaveRail")
@ConditionalOnProperty(prefix = "commercial.rails.flutterwave", name = "enabled", havingValue = "true")
public class FlutterwaveRail implements PaymentRail {

    private static final Logger log = LoggerFactory.getLogger(FlutterwaveRail.class);

    private final FlutterwaveService flutterwave;
    private final CommercialRailsProperties.Flutterwave props;

    public FlutterwaveRail(FlutterwaveService flutterwave, CommercialRailsProperties props) {
        this.flutterwave = flutterwave;
        this.props = props.flutterwave();
    }

    @Override
    public RailId id() { return RailId.FLUTTERWAVE; }

    @Override
    public Set<Environment> environments() { return Set.of(Environment.LIVE); }

    @Override
    public List<RailCapability> capabilities() {
        List<String> momo = props.mobileMoneyCurrencies().stream().filter(c -> !c.equals("KES")).toList();
        return List.of(
                new RailCapability(MethodType.MOBILE_MONEY, Direction.COLLECTION, List.of("*"), momo,
                        props.minUsdCents(), props.maxUsdCents(), "INSTANT", "CUSTOMER_PROMPT"),
                new RailCapability(MethodType.MOBILE_MONEY, Direction.PAYOUT, List.of("*"), momo,
                        props.minUsdCents(), props.maxUsdCents(), "INSTANT", "NONE"),
                new RailCapability(MethodType.BANK_TRANSFER, Direction.PAYOUT, List.of("*"), props.bankCurrencies(),
                        props.minUsdCents(), props.maxUsdCents(), "INSTANT", "NONE"));
    }

    @Override
    public String providerName(MethodType method) { return "FLUTTERWAVE"; }

    @Override
    public boolean pollable() { return true; }

    // ------------------------------------------------------------------ collecting

    @Override
    public RailResponse collect(CollectCommand c) {
        if (c.method() != MethodType.MOBILE_MONEY) throw rejected(ResultCode.DECLINED, "The Flutterwave rail takes mobile money payments only");
        String currency = c.nativeCurrency() == null ? null : c.nativeCurrency().toUpperCase(Locale.ROOT);
        String dial = FlutterwaveMapper.dialCode(currency);
        if (currency == null || dial == null || !props.mobileMoneyCurrencies().contains(currency)) {
            throw rejected(ResultCode.DECLINED, "Flutterwave mobile money is not available in " + (currency == null ? "USD" : currency));
        }
        String network = option(c, "network");
        if (network == null) throw rejected(ResultCode.DECLINED, "method.network is required for mobile money in " + currency + " (for example MTN)");
        Map<String, Object> customer = c.customer() == null ? Map.of() : c.customer();
        String phone = FlutterwaveMapper.nationalNumber(asText(customer.get("phone")), dial);
        if (phone.length() < 6) throw rejected(ResultCode.INVALID_DESTINATION, "customer.phone is not a valid number for " + currency);
        String email = asText(customer.get("email")) != null ? asText(customer.get("email"))
                : FlutterwaveMapper.placeholderEmail(c.transactionId(), props.customerEmailDomain());

        FlutterwaveService.CheckoutResult r;
        try {
            r = flutterwave.initiateMobileMoneyCharge(c.nativeAmount(), currency, c.transactionId(), email,
                    asText(customer.get("name")), dial, network, phone);
        } catch (RuntimeException e) {
            throw new RailException(RailException.Kind.UNAVAILABLE, ResultCode.PROVIDER_ERROR, "Flutterwave is not available right now");
        }
        if (!r.success()) {
            String message = r.message() == null ? "Flutterwave refused the payment" : r.message();
            if (message.startsWith("Charge initiation failed")) {
                // The wallet service folds network faults into this message; the charge may not exist.
                log.warn("Flutterwave charge for {} could not be started: {}", c.transactionId(), message);
                throw new RailException(RailException.Kind.UNAVAILABLE, ResultCode.PROVIDER_ERROR, "Flutterwave is not available right now");
            }
            throw rejected(ResultCode.DECLINED, message);
        }
        Map<String, Object> next = new LinkedHashMap<>();
        if ("redirect_url".equals(r.nextActionType()) && r.redirectUrl() != null) {
            next.put("type", "REDIRECT");
            next.put("url", r.redirectUrl());
        } else {
            next.put("type", "CUSTOMER_PROMPT");
            next.put("instructions", r.paymentInstructionNote() != null ? r.paymentInstructionNote()
                    : "Approve the payment on your phone.");
        }
        return RailResponse.requiresAction(next, r.chargeId());
    }

    // ------------------------------------------------------------------ payouts

    @Override
    public RailResponse payout(PayoutCommand c) {
        String currency = c.nativeCurrency() == null ? null : c.nativeCurrency().toUpperCase(Locale.ROOT);
        if (currency == null) {
            throw rejected(ResultCode.DECLINED, "Flutterwave pays in local currency; request the payout in the recipient's currency");
        }
        Map<String, Object> d = c.destination() == null ? Map.of() : c.destination();
        String ref = c.transactionId();
        String narration = c.remarks();
        String[] name = FlutterwaveMapper.splitName(asText(d.get("accountName")), "Recipient");
        FlutterwaveService.TransferResult r;
        try {
            if (c.destinationType() == DestinationType.BANK_ACCOUNT) {
                if (!props.bankCurrencies().contains(currency)) throw rejected(ResultCode.DECLINED, "Bank payouts are not enabled in " + currency);
                r = flutterwave.initiateBankTransfer(asText(d.get("accountNumber")), asText(d.get("bankCode")),
                        props.sourceCurrency(), currency, c.nativeAmount(), ref, narration, name[0], name[1]);
            } else if (c.destinationType() == DestinationType.MOBILE_MONEY) {
                String dial = FlutterwaveMapper.dialCode(currency);
                String network = asText(d.get("network"));
                if (dial == null || !props.mobileMoneyCurrencies().contains(currency)) {
                    throw rejected(ResultCode.DECLINED, "Mobile money payouts are not enabled in " + currency);
                }
                if (network == null) throw rejected(ResultCode.INVALID_DESTINATION, "destination.network is required (for example MTN)");
                r = flutterwave.initiateTransfer(FlutterwaveMapper.fullNumber(asText(d.get("phoneNumber")), dial), network,
                        props.sourceCurrency(), currency, c.nativeAmount(), ref, narration, name[0], name[1]);
            } else {
                throw rejected(ResultCode.INVALID_DESTINATION, "The Flutterwave rail pays bank accounts and mobile money wallets only");
            }
        } catch (RailException e) {
            throw e;
        } catch (RuntimeException e) {
            // The transfer may already be on its way. Failing it would release the held money while the
            // recipient could still be paid, so it stays pending; the reference makes a repeat safe.
            log.error("Flutterwave payout {} is unconfirmed ({}); keeping it pending", ref, e.getMessage());
            return RailResponse.pending(null);
        }
        if (!r.success()) throw rejected(ResultCode.DECLINED, r.message() == null ? "Flutterwave refused the transfer" : r.message());
        return RailResponse.pending(r.transferId());
    }

    // ------------------------------------------------------------------ results

    @Override
    public RailOutcome poll(CommercialTransaction tx) {
        String ref = tx.getProviderReference();
        if (ref == null || ref.isBlank()) {
            if (tx.getType() == TxType.PAYOUT) {
                log.error("Flutterwave payout {} has no transfer id; check it in the Flutterwave dashboard", tx.getId());
            }
            return null;
        }
        if (tx.getType() == TxType.PAYMENT) {
            FlutterwaveService.VerifyResult v = flutterwave.verifyChargeById(ref);
            if (FlutterwaveMapper.paidWrongAmount(v.status(), v.amount(), v.currency(), tx.getNativeAmount(), tx.getNativeCurrency())) {
                log.error("Flutterwave reports payment {} succeeded for {} {} but {} {} was expected; not applying it automatically",
                        tx.getId(), v.amount(), v.currency(), tx.getNativeAmount(), tx.getNativeCurrency());
                return null;
            }
            return FlutterwaveMapper.charge(v.status(), v.amount(), v.currency(), tx.getNativeAmount(), tx.getNativeCurrency(), ref);
        }
        if (tx.getType() == TxType.PAYOUT) {
            FlutterwaveService.TransferResult t = flutterwave.getTransferStatus(ref);
            return FlutterwaveMapper.transfer(FlutterwaveMapper.transferStatus(t.message()), ref);
        }
        return null;
    }

    // ------------------------------------------------------------------ helpers

    private static String option(CollectCommand c, String key) {
        Object v = c.methodOptions() == null ? null : c.methodOptions().get(key);
        return v == null || String.valueOf(v).isBlank() ? null : String.valueOf(v);
    }

    private static String asText(Object o) {
        return o == null || String.valueOf(o).isBlank() ? null : String.valueOf(o);
    }

    private static RailException rejected(ResultCode code, String message) {
        return new RailException(RailException.Kind.REJECTED, code, message);
    }
}
