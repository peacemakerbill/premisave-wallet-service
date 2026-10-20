package com.premisave.wallet.commercial.service.settlement;

import com.premisave.wallet.commercial.config.properties.CommercialProperties;
import com.premisave.wallet.commercial.dto.settlement.CreateSettlementRequest;
import com.premisave.wallet.commercial.dto.settlement.SettlementScheduleRequest;
import com.premisave.wallet.commercial.dto.settlement.SettlementViews;
import com.premisave.wallet.commercial.entity.CommercialTransaction;
import com.premisave.wallet.commercial.entity.SettlementDestination;
import com.premisave.wallet.commercial.entity.settlement.SettlementSchedule;
import com.premisave.wallet.commercial.enums.DestinationType;
import com.premisave.wallet.commercial.enums.Direction;
import com.premisave.wallet.commercial.enums.ErrorCode;
import com.premisave.wallet.commercial.enums.MethodType;
import com.premisave.wallet.commercial.enums.TxStatus;
import com.premisave.wallet.commercial.enums.TxType;
import com.premisave.wallet.commercial.exception.CommercialException;
import com.premisave.wallet.commercial.rail.PaymentRail;
import com.premisave.wallet.commercial.rail.PayoutCommand;
import com.premisave.wallet.commercial.repository.settlement.SettlementScheduleRepository;
import com.premisave.wallet.commercial.security.CommercialContext;
import com.premisave.wallet.commercial.service.CommercialIdempotencyService;
import com.premisave.wallet.commercial.service.CommercialPaymentService;
import com.premisave.wallet.commercial.service.FxConverter;
import com.premisave.wallet.commercial.service.HeldDebit;
import com.premisave.wallet.commercial.service.LimitService;
import com.premisave.wallet.commercial.service.Priced;
import com.premisave.wallet.commercial.service.PricingService;
import com.premisave.wallet.commercial.service.RailRegistry;
import com.premisave.wallet.commercial.service.TransactionQueryService;
import com.premisave.wallet.commercial.util.Ids;
import com.premisave.wallet.commercial.util.Money;
import com.premisave.wallet.commercial.util.RequestValidators;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Settlements: moving money from the merchant balance to one of its registered
 * destinations. It is a payout whose destination is fixed in advance, so it
 * holds amount plus fee first and uses the same rails, results and recovery.
 * A settlement request can never carry destination details of its own.
 */
@Service("commercialSettlementService")
public class SettlementService {

    public static final String TRIGGER_MERCHANT = "MERCHANT";
    public static final String TRIGGER_SCHEDULE = "SCHEDULE";
    private static final Pattern TIME = Pattern.compile("^([01]\\d|2[0-3]):[0-5]\\d$");

    private final CommercialIdempotencyService idempotency;
    private final PricingService pricing;
    private final LimitService limits;
    private final RailRegistry rails;
    private final HeldDebit held;
    private final TransactionQueryService queries;
    private final FxConverter fx;
    private final CommercialProperties props;
    private final SettlementScheduleRepository schedules;

    public SettlementService(CommercialIdempotencyService idempotency, PricingService pricing, LimitService limits,
                             RailRegistry rails, HeldDebit held, TransactionQueryService queries, FxConverter fx,
                             CommercialProperties props, SettlementScheduleRepository schedules) {
        this.idempotency = idempotency;
        this.pricing = pricing;
        this.limits = limits;
        this.rails = rails;
        this.held = held;
        this.queries = queries;
        this.fx = fx;
        this.props = props;
        this.schedules = schedules;
    }

    public CommercialIdempotencyService.Outcome<Map<String, Object>> create(CommercialContext ctx, CreateSettlementRequest req,
                                                                   String idempotencyKey, String bodyHash) {
        return idempotency.execute(ctx, idempotencyKey, bodyHash, "SETTLEMENT",
                () -> start(ctx, req.amount(), req.currency(), destination(ctx, req.destinationId()),
                        req.merchantReference(), TRIGGER_MERCHANT),
                id -> SettlementViews.settlement(queries.get(ctx, id, TxType.SETTLEMENT)));
    }

    /** Used by the scheduler: the amount is already worked out in USD cents. */
    public String startScheduled(CommercialContext ctx, SettlementDestination dest, long usdCents, String reference) {
        return start(ctx, Money.fromCents(usdCents), Money.BASE_CURRENCY, dest, reference, TRIGGER_SCHEDULE);
    }

    public static SettlementDestination destination(CommercialContext ctx, String destinationId) {
        return ctx.merchant().destinationsFor(ctx.environment()).stream()
                .filter(d -> d.getId().equals(destinationId))
                .findFirst()
                .orElseThrow(() -> CommercialException.notFound("Settlement destination"));
    }

    private String start(CommercialContext ctx, BigDecimal amount, String currencyInput, SettlementDestination dest,
                         String reference, String trigger) {
        MethodType method = dest.getType();
        DestinationType destType = SettlementDestinations.destinationType(method);
        Map<String, Object> destination = RequestValidators.destination(destType, SettlementDestinations.rawDestination(dest));
        String country = destination.get("country") == null ? null : String.valueOf(destination.get("country"));

        Route route = route(ctx, method, country, amount, pricing.normaliseCurrency(currencyInput));
        Priced priced = pricing.price(Direction.PAYOUT, method, route.amount(), route.currency(), null);
        limits.check(ctx, TxType.SETTLEMENT, priced.usdCents());

        String id = Ids.next("stl");
        CommercialTransaction tx = new CommercialTransaction();
        tx.setId(id);
        tx.setMerchantId(ctx.merchantId());
        tx.setAppId(ctx.appId());
        tx.setLivemode(ctx.livemode());
        tx.setType(TxType.SETTLEMENT);
        tx.setStatus(TxStatus.PENDING);
        tx.setMerchantReference(reference);
        tx.setRemarks("Settlement " + reference);
        CommercialPaymentService.applyPricing(tx, priced, null);
        tx.setMethodType(method);
        tx.setRail(route.rail().id());
        tx.setProvider(route.rail().providerName(method));
        tx.setDestination(destination);
        Map<String, String> relations = new LinkedHashMap<>();
        relations.put("destinationId", dest.getId());
        relations.put("destinationType", method.name());
        relations.put("destinationLabel", dest.getLabel());
        relations.put("trigger", trigger);
        tx.setRelations(relations);

        held.open(ctx.walletId(), tx, "Settlement " + reference + " (amount " + Money.format(priced.usdCents())
                + ", fee " + Money.format(priced.feeCents()) + ")");

        PayoutCommand cmd = new PayoutCommand(id, ctx.merchantId(), ctx.livemode(), destType, priced.usdCents(),
                priced.nativeAmount(), priced.nativeCurrency(), destination, tx.getRemarks());
        held.dispatch(route.rail(), cmd, tx);
        return id;
    }

    record Route(BigDecimal amount, String currency, PaymentRail rail) {}

    /**
     * Picks the rail. When none takes the amount in USD but the destination country has a local
     * currency, the amount is converted (whole units, rounded down) and the rail tried again, so a
     * merchant can settle "500.00 USD" to a Kenyan M-Pesa number without doing the sum itself.
     */
    private Route route(CommercialContext ctx, MethodType method, String country, BigDecimal amount, String currency) {
        try {
            return new Route(amount, currency, rails.select(ctx.environment(), Direction.PAYOUT, method, null, country, currency));
        } catch (CommercialException unavailable) {
            String local = SettlementDestinations.localCurrency(country);
            if (unavailable.code() != ErrorCode.METHOD_UNAVAILABLE || !Money.BASE_CURRENCY.equals(currency)
                    || local == null || !props.supportsCurrency(local)) {
                throw unavailable;
            }
            BigDecimal rate;
            try {
                rate = fx.nativePerUsd(local);
                if (rate == null || rate.signum() <= 0) throw new IllegalStateException("non-positive rate");
            } catch (RuntimeException e) {
                throw new CommercialException(ErrorCode.PROVIDER_UNAVAILABLE,
                        "The exchange rate for " + local + " is not available right now");
            }
            BigDecimal converted = SettlementDestinations.toLocalWholeUnits(amount, rate);
            if (converted.signum() <= 0) throw CommercialException.validation("amount", "is too small once converted to " + local);
            return new Route(converted, local, rails.select(ctx.environment(), Direction.PAYOUT, method, null, country, local));
        }
    }

    // ------------------------------------------------------------------ schedule

    public Map<String, Object> saveSchedule(CommercialContext ctx, SettlementScheduleRequest req) {
        SettlementSchedule.Mode mode = parseMode(req.mode());
        SettlementSchedule s = schedules.findById(SettlementSchedule.idFor(ctx.merchantId(), ctx.livemode()))
                .orElseGet(SettlementSchedule::new);
        s.setId(SettlementSchedule.idFor(ctx.merchantId(), ctx.livemode()));
        s.setMerchantId(ctx.merchantId());
        s.setLivemode(ctx.livemode());
        s.setAppId(ctx.appId());
        s.setMode(mode);

        if (mode != SettlementSchedule.Mode.MANUAL) {
            if (req.destinationId() == null || req.destinationId().isBlank()) {
                throw CommercialException.validation("destinationId", "is required for " + mode);
            }
            SettlementDestination dest = destination(ctx, req.destinationId());
            SettlementDestinations.destinationType(dest.getType());
            s.setDestinationId(dest.getId());
        } else if (req.destinationId() != null && !req.destinationId().isBlank()) {
            s.setDestinationId(destination(ctx, req.destinationId()).getId());
        }

        s.setMinimumCents(cents("minimumAmount", req.minimumAmount()));
        s.setKeepReserveCents(cents("keepReserve", req.keepReserve()));

        s.setRunAtUtc(null);
        s.setDayOfWeek(null);
        s.setThresholdCents(0);
        if (mode == SettlementSchedule.Mode.DAILY || mode == SettlementSchedule.Mode.WEEKLY) {
            String at = req.runAtUtc() == null || req.runAtUtc().isBlank() ? "06:00" : req.runAtUtc().trim();
            if (!TIME.matcher(at).matches()) throw CommercialException.validation("runAtUtc", "must look like 06:00");
            s.setRunAtUtc(at);
        }
        if (mode == SettlementSchedule.Mode.WEEKLY) s.setDayOfWeek(parseDay(req.dayOfWeek()));
        if (mode == SettlementSchedule.Mode.THRESHOLD) {
            long threshold = cents("thresholdAmount", req.thresholdAmount());
            if (threshold <= 0) throw CommercialException.validation("thresholdAmount", "is required and must be greater than zero for THRESHOLD");
            s.setThresholdCents(threshold);
        }
        s.setUpdatedAt(Instant.now());
        return SettlementViews.schedule(schedules.save(s));
    }

    static SettlementSchedule.Mode parseMode(String raw) {
        try {
            return SettlementSchedule.Mode.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException | NullPointerException e) {
            throw CommercialException.validation("mode", "must be MANUAL, DAILY, WEEKLY or THRESHOLD");
        }
    }

    static DayOfWeek parseDay(String raw) {
        if (raw == null || raw.isBlank()) throw CommercialException.validation("dayOfWeek", "is required for WEEKLY");
        String v = raw.trim().toUpperCase(Locale.ROOT);
        for (DayOfWeek d : DayOfWeek.values()) {
            if (d.name().equals(v) || d.name().substring(0, 3).equals(v)) return d;
        }
        throw CommercialException.validation("dayOfWeek", "must be a day such as MONDAY or MON");
    }

    private static long cents(String field, BigDecimal value) {
        if (value == null) return 0;
        if (value.signum() < 0) throw CommercialException.validation(field, "cannot be negative");
        if (Money.hasMoreThanCents(value)) throw CommercialException.validation(field, "must have at most 2 decimal places");
        return Money.toCents(value);
    }
}
