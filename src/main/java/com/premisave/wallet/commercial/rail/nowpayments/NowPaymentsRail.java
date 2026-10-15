package com.premisave.wallet.commercial.rail.nowpayments;

import com.premisave.wallet.commercial.config.CommercialRailsProperties;
import com.premisave.wallet.commercial.entity.CommercialTransaction;
import com.premisave.wallet.commercial.enums.Direction;
import com.premisave.wallet.commercial.enums.Environment;
import com.premisave.wallet.commercial.enums.MethodType;
import com.premisave.wallet.commercial.enums.RailId;
import com.premisave.wallet.commercial.enums.ResultCode;
import com.premisave.wallet.commercial.enums.TxType;
import com.premisave.wallet.commercial.rail.CollectCommand;
import com.premisave.wallet.commercial.rail.PayoutCommand;
import com.premisave.wallet.commercial.rail.PaymentRail;
import com.premisave.wallet.commercial.rail.RailCapability;
import com.premisave.wallet.commercial.rail.RailException;
import com.premisave.wallet.commercial.rail.RailOutcome;
import com.premisave.wallet.commercial.rail.RailResponse;
import com.premisave.wallet.commercial.util.Money;
import com.premisave.wallet.service.NowPaymentsService;
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
 * Crypto payments through NOWPayments. The customer gets a deposit address and an exact amount of the coin
 * they chose (methodOptions payCurrency, for example usdttrc20) and pays from their own wallet. It wraps the
 * wallet's NowPaymentsService. The merchant price is always in USD.
 *
 * Results are found by looking the payment up (RailPoller): the notification address in the wallet's
 * NOWPayments settings is shared with the wallet service. A payment that arrives short (partially_paid) is
 * not credited and expires unpaid, so an operator can sort it out with the customer. Crypto payouts are not
 * offered: NOWPayments will not release a payout until a person enters a 2FA code, which an API cannot do.
 *
 * Serves the live environment only. NOWPayments' sandbox host (nowpayments.base-url) exercises the path.
 */
@Component("commercialNowPaymentsRail")
@ConditionalOnProperty(prefix = "commercial.rails.nowpayments", name = "enabled", havingValue = "true")
public class NowPaymentsRail implements PaymentRail {

    private static final Logger log = LoggerFactory.getLogger(NowPaymentsRail.class);

    private final NowPaymentsService nowPayments;
    private final CommercialRailsProperties.NowPayments props;

    public NowPaymentsRail(NowPaymentsService nowPayments, CommercialRailsProperties props) {
        this.nowPayments = nowPayments;
        this.props = props.nowpayments();
    }

    @Override
    public RailId id() { return RailId.NOWPAYMENTS; }

    @Override
    public Set<Environment> environments() { return Set.of(Environment.LIVE); }

    @Override
    public List<RailCapability> capabilities() {
        return List.of(new RailCapability(MethodType.CRYPTO, Direction.COLLECTION, List.of("*"), List.of("USD"),
                props.minUsdCents(), props.maxUsdCents(), "INSTANT", "CRYPTO_ADDRESS"));
    }

    @Override
    public String providerName(MethodType method) { return "NOWPAYMENTS"; }

    @Override
    public boolean pollable() { return true; }

    @Override
    public RailResponse collect(CollectCommand c) {
        if (c.method() != MethodType.CRYPTO) throw rejected(ResultCode.DECLINED, "The NOWPayments rail takes crypto payments only");
        if (c.nativeCurrency() != null) throw rejected(ResultCode.DECLINED, "Crypto payments are priced in USD");
        String payCurrency = option(c, "payCurrency");
        payCurrency = (payCurrency == null ? props.defaultPayCurrency() : payCurrency).toLowerCase(Locale.ROOT);

        NowPaymentsService.CreatePaymentResult r;
        try {
            r = nowPayments.createPayment(Money.fromCents(c.usdCents()), "usd", payCurrency, c.transactionId(),
                    c.description(), null);
        } catch (RuntimeException e) {
            throw new RailException(RailException.Kind.UNAVAILABLE, ResultCode.PROVIDER_ERROR, "NOWPayments is not available right now");
        }
        if (!r.success()) {
            String message = r.message() == null ? "NOWPayments refused the payment" : r.message();
            if (message.startsWith("NOWPayments createPayment error")) {
                log.warn("NOWPayments payment {} could not be started: {}", c.transactionId(), message);
                throw new RailException(RailException.Kind.UNAVAILABLE, ResultCode.PROVIDER_ERROR, "NOWPayments is not available right now");
            }
            throw rejected(ResultCode.DECLINED, message);
        }
        Map<String, Object> next = new LinkedHashMap<>();
        next.put("type", "CRYPTO_ADDRESS");
        next.put("network", r.payCurrency() == null ? payCurrency : r.payCurrency());
        next.put("payCurrency", r.payCurrency() == null ? payCurrency : r.payCurrency());
        next.put("payAmount", r.payAmount());
        next.put("address", r.payAddress());
        return RailResponse.requiresAction(next, r.paymentId());
    }

    @Override
    public RailResponse payout(PayoutCommand command) {
        throw rejected(ResultCode.INVALID_DESTINATION, "Crypto payouts are not available");
    }

    @Override
    public RailOutcome poll(CommercialTransaction tx) {
        String ref = tx.getProviderReference();
        if (tx.getType() != TxType.PAYMENT || ref == null || ref.isBlank()) return null;
        NowPaymentsService.PaymentStatusResult s = nowPayments.getPaymentStatus(ref);
        if (!s.success()) return null;
        if (NowPaymentsMapper.partiallyPaid(s.paymentStatus())) {
            log.warn("Crypto payment {} was only partly paid ({} received); it will expire unpaid for an operator to review",
                    tx.getId(), s.actuallyPaid());
            return null;
        }
        return NowPaymentsMapper.payment(s.paymentStatus(), ref);
    }

    private static String option(CollectCommand c, String key) {
        Object v = c.methodOptions() == null ? null : c.methodOptions().get(key);
        return v == null || String.valueOf(v).isBlank() ? null : String.valueOf(v);
    }

    private static RailException rejected(ResultCode code, String message) {
        return new RailException(RailException.Kind.REJECTED, code, message);
    }
}
