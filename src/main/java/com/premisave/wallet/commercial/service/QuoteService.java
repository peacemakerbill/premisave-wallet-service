package com.premisave.wallet.commercial.service;

import com.premisave.wallet.commercial.config.CommercialProperties;
import com.premisave.wallet.commercial.dto.MethodSpec;
import com.premisave.wallet.commercial.dto.QuoteRequest;
import com.premisave.wallet.commercial.dto.Views;
import com.premisave.wallet.commercial.entity.Quote;
import com.premisave.wallet.commercial.enums.DestinationType;
import com.premisave.wallet.commercial.enums.Direction;
import com.premisave.wallet.commercial.enums.ErrorCode;
import com.premisave.wallet.commercial.enums.MethodType;
import com.premisave.wallet.commercial.exception.CommercialException;
import com.premisave.wallet.commercial.rail.PaymentRail;
import com.premisave.wallet.commercial.repository.CommercialQuoteRepository;
import com.premisave.wallet.commercial.security.CommercialContext;
import com.premisave.wallet.commercial.util.Ids;
import com.premisave.wallet.commercial.util.RequestValidators;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

/** Prices a request ahead of time and lets a later payment or payout hold the quoted rate and fee. */
@Service("commercialQuoteService")
public class QuoteService {

    private final CommercialProperties props;
    private final PricingService pricing;
    private final RailRegistry rails;
    private final CommercialQuoteRepository quotes;

    public QuoteService(CommercialProperties props, PricingService pricing, RailRegistry rails, CommercialQuoteRepository quotes) {
        this.props = props;
        this.pricing = pricing;
        this.rails = rails;
        this.quotes = quotes;
    }

    public Map<String, Object> create(CommercialContext ctx, QuoteRequest req) {
        MethodType method;
        String provider;
        if (req.direction() == Direction.COLLECTION) {
            MethodSpec spec = req.method();
            if (spec == null) throw CommercialException.validation("method", "is required for a collection quote");
            method = spec.type();
            provider = spec.provider();
        } else {
            if (req.destination() == null) throw CommercialException.validation("destination", "is required for a payout quote");
            DestinationType dt = RequestValidators.destinationType(req.destination());
            method = dt.method();
            Object p = req.destination().get("provider");
            provider = p == null ? null : String.valueOf(p);
        }
        if (!MethodPolicy.isEnabled(ctx.merchant(), ctx.app(), ctx.environment(), method)) {
            throw new CommercialException(ErrorCode.METHOD_NOT_ENABLED, method + " is not enabled for this merchant or app");
        }

        Priced priced = pricing.price(req.direction(), method, req.amount(), req.currency(), null);
        PaymentRail rail = rails.select(ctx.environment(), req.direction(), method, provider, req.country(), priced.currency());

        Instant now = Instant.now();
        Quote q = new Quote();
        q.setQuoteId(Ids.next("qt"));
        q.setMerchantId(ctx.merchantId());
        q.setLivemode(ctx.livemode());
        q.setDirection(req.direction());
        q.setMethodType(method);
        q.setRail(rail.id().name());
        q.setProvider(rail.providerName(method));
        q.setCountry(req.country());
        q.setAmount(req.amount());
        q.setCurrency(priced.currency());
        q.setUsdCents(priced.usdCents());
        q.setNativeAmount(priced.nativeAmount());
        q.setNativeCurrency(priced.nativeCurrency());
        q.setFxRate(priced.fxRate());
        q.setFeeCents(priced.feeCents());
        q.setNetCents(req.direction() == Direction.COLLECTION ? priced.settleCents() : priced.usdCents());
        q.setCreatedAt(now);
        q.setExpiresAt(now.plusSeconds(props.quoteTtlSeconds()));
        return Views.quote(quotes.insert(q));
    }

    /** Loads a quote a request names, and checks it is theirs, unexpired and for this very request. */
    public Quote requireUsable(CommercialContext ctx, String quoteId, Direction direction, MethodType method,
                               BigDecimal amount, String currency) {
        Quote q = quotes.findById(quoteId).orElse(null);
        String normalised = pricing.normaliseCurrency(currency);
        boolean usable = q != null
                && q.getMerchantId().equals(ctx.merchantId())
                && q.isLivemode() == ctx.livemode()
                && q.getDirection() == direction
                && q.getMethodType() == method
                && q.getCurrency().equals(normalised)
                && q.getAmount().compareTo(amount) == 0
                && q.getExpiresAt().isAfter(Instant.now());
        if (!usable) {
            throw CommercialException.validation("quoteId", "is unknown, expired, or does not match this request");
        }
        return q;
    }
}
