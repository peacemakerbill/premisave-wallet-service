package com.premisave.wallet.commercial.service;

import com.premisave.wallet.commercial.dto.CreatePaymentRequest;
import com.premisave.wallet.commercial.dto.CustomerInfo;
import com.premisave.wallet.commercial.dto.MethodSpec;
import com.premisave.wallet.commercial.dto.Views;
import com.premisave.wallet.commercial.entity.CommercialTransaction;
import com.premisave.wallet.commercial.entity.Quote;
import com.premisave.wallet.commercial.enums.Direction;
import com.premisave.wallet.commercial.enums.ErrorCode;
import com.premisave.wallet.commercial.enums.MethodType;
import com.premisave.wallet.commercial.enums.ResultCode;
import com.premisave.wallet.commercial.enums.TxStatus;
import com.premisave.wallet.commercial.enums.TxType;
import com.premisave.wallet.commercial.exception.CommercialException;
import com.premisave.wallet.commercial.rail.CollectCommand;
import com.premisave.wallet.commercial.rail.PaymentRail;
import com.premisave.wallet.commercial.rail.RailException;
import com.premisave.wallet.commercial.rail.RailOutcome;
import com.premisave.wallet.commercial.rail.RailResponse;
import com.premisave.wallet.commercial.security.CommercialContext;
import com.premisave.wallet.commercial.util.CallbackUrlValidator;
import com.premisave.wallet.commercial.util.Ids;
import com.premisave.wallet.commercial.util.RequestValidators;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/** Collections: taking money from a customer through whichever rail serves the chosen method. */
@Service
public class CommercialPaymentService {

    private static final int DEFAULT_EXPIRY_SECONDS = 3600;
    private static final int MOBILE_MONEY_EXPIRY_SECONDS = 300;

    private final IdempotencyService idempotency;
    private final PricingService pricing;
    private final QuoteService quotes;
    private final LimitService limits;
    private final RailRegistry rails;
    private final CommercialTransactionStore store;
    private final TransactionOutcomeService outcomes;
    private final TransactionQueryService queries;

    public CommercialPaymentService(IdempotencyService idempotency, PricingService pricing, QuoteService quotes,
                                    LimitService limits, RailRegistry rails, CommercialTransactionStore store,
                                    TransactionOutcomeService outcomes, TransactionQueryService queries) {
        this.idempotency = idempotency;
        this.pricing = pricing;
        this.quotes = quotes;
        this.limits = limits;
        this.rails = rails;
        this.store = store;
        this.outcomes = outcomes;
        this.queries = queries;
    }

    public IdempotencyService.Outcome<Map<String, Object>> create(CommercialContext ctx, CreatePaymentRequest req,
                                                                    String idempotencyKey, String bodyHash) {
        return idempotency.execute(ctx, idempotencyKey, bodyHash, "PAYMENT",
                () -> doCreate(ctx, req),
                id -> Views.payment(queries.get(ctx, id, TxType.PAYMENT)));
    }

    private String doCreate(CommercialContext ctx, CreatePaymentRequest req) {
        MethodSpec spec = req.method();
        MethodType method = spec.type();
        if (!MethodPolicy.isEnabled(ctx.merchant(), ctx.app(), ctx.environment(), method)) {
            throw new CommercialException(ErrorCode.METHOD_NOT_ENABLED, method + " is not enabled for this merchant or app");
        }

        CustomerInfo c = req.customer() == null ? new CustomerInfo(null, null, null, null) : req.customer();
        Map<String, Object> customer = RequestValidators.customerFor(method, c.name(), c.email(), c.phone(),
                c.accountRef(), spec.returnUrl(), spec.cancelUrl());
        Map<String, String> metadata = RequestValidators.metadata(req.metadata());
        checkCallbackUrl(ctx, req.callbackUrl());

        Quote quote = req.quoteId() == null ? null
                : quotes.requireUsable(ctx, req.quoteId(), Direction.COLLECTION, method, req.amount(), req.currency());
        Priced priced = pricing.price(Direction.COLLECTION, method, req.amount(), req.currency(), quote);
        limits.check(ctx, TxType.PAYMENT, priced.usdCents());
        PaymentRail rail = rails.select(ctx.environment(), Direction.COLLECTION, method, spec.provider(), null, priced.currency());

        Instant now = Instant.now();
        int expiry = req.expiresInSeconds() != null ? req.expiresInSeconds()
                : (method == MethodType.MOBILE_MONEY ? MOBILE_MONEY_EXPIRY_SECONDS : DEFAULT_EXPIRY_SECONDS);

        CommercialTransaction tx = new CommercialTransaction();
        tx.setId(Ids.next("pay"));
        tx.setMerchantId(ctx.merchantId());
        tx.setAppId(ctx.appId());
        tx.setLivemode(ctx.livemode());
        tx.setType(TxType.PAYMENT);
        tx.setStatus(TxStatus.PENDING);
        tx.setMerchantReference(req.merchantReference());
        tx.setDescription(req.description());
        applyPricing(tx, priced, quote);
        tx.setMethodType(method);
        tx.setRail(rail.id());
        tx.setProvider(rail.providerName(method));
        tx.setCustomer(customer);
        tx.setCallbackUrl(blankToNull(req.callbackUrl()));
        tx.setNotifyRegistered(Boolean.TRUE.equals(req.notifyRegistered()));
        tx.setMetadata(metadata);
        tx.setExpiresAt(now.plusSeconds(expiry));
        store.insert(tx);

        Map<String, Object> options = new LinkedHashMap<>();
        if (spec.returnUrl() != null) options.put("returnUrl", spec.returnUrl());
        if (spec.cancelUrl() != null) options.put("cancelUrl", spec.cancelUrl());
        if (spec.payCurrency() != null) options.put("payCurrency", spec.payCurrency());
        if (spec.network() != null) options.put("network", spec.network());
        if (spec.paymentMethodId() != null) options.put("paymentMethodId", spec.paymentMethodId());

        CollectCommand cmd = new CollectCommand(tx.getId(), ctx.merchantId(), ctx.livemode(), method, priced.usdCents(),
                priced.nativeAmount(), priced.nativeCurrency(), customer, options, req.description(), tx.getExpiresAt());
        try {
            RailResponse response = rail.collect(cmd);
            outcomes.applyRailResponse(tx, response);
        } catch (RailException e) {
            outcomes.apply(tx, RailOutcome.of(TxStatus.FAILED, e.code() != null ? e.code() : ResultCode.PROVIDER_ERROR, e.getMessage()));
            if (e.kind() == RailException.Kind.UNAVAILABLE) {
                throw new CommercialException(ErrorCode.PROVIDER_UNAVAILABLE, "The payment provider is unavailable; nothing was charged");
            }
        }
        return tx.getId();
    }

    public Map<String, Object> cancel(CommercialContext ctx, String id) {
        CommercialTransaction tx = queries.get(ctx, id, TxType.PAYMENT);
        if (!TxStatus.OPEN.contains(tx.getStatus())) {
            throw CommercialException.invalidState("A payment that is " + tx.getStatus() + " cannot be cancelled");
        }
        PaymentRail rail = rails.byId(tx.getRail());
        if (!rail.cancelCollection(tx)) {
            throw CommercialException.invalidState("This payment can no longer be recalled; it will expire on its own");
        }
        CommercialTransaction after = outcomes.apply(tx, RailOutcome.of(TxStatus.CANCELLED,
                ResultCode.CUSTOMER_CANCELLED, "Cancelled by the merchant"));
        if (after.getStatus() != TxStatus.CANCELLED) {
            throw CommercialException.invalidState("The payment changed to " + after.getStatus() + " before it could be cancelled");
        }
        return Views.payment(after);
    }

    static void applyPricing(CommercialTransaction tx, Priced priced, Quote quote) {
        tx.setAmountCents(priced.usdCents());
        tx.setFeeCents(priced.feeCents());
        tx.setSettleCents(priced.settleCents());
        tx.setCurrency(priced.currency());
        tx.setNativeAmount(priced.nativeAmount());
        tx.setNativeCurrency(priced.nativeCurrency());
        tx.setFxRate(priced.fxRate());
        tx.setQuoteId(quote == null ? null : quote.getQuoteId());
    }

    static void checkCallbackUrl(CommercialContext ctx, String url) {
        if (url == null || url.isBlank()) return;
        CallbackUrlValidator.check(url, ctx.environment()).ifPresent(reason -> {
            throw new CommercialException(ErrorCode.INVALID_CALLBACK_URL, "callbackUrl: " + reason);
        });
    }

    static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
