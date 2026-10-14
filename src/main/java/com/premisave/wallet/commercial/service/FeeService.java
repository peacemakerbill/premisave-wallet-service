package com.premisave.wallet.commercial.service;

import com.premisave.wallet.commercial.config.CommercialProperties;
import com.premisave.wallet.commercial.config.CommercialProperties.FeeRule;
import com.premisave.wallet.commercial.enums.Direction;
import com.premisave.wallet.commercial.enums.MethodType;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.EnumMap;
import java.util.Map;

/**
 * Merchant pricing. The numbers below are placeholders so the platform works
 * end to end; real values are a business decision and are overridden per
 * method through commercial.fees.collection.* and commercial.fees.payout.*.
 * Per merchant fee plans replace this later without changing callers.
 */
@Service("commercialFeeService")
public class FeeService {

    private static final Map<MethodType, FeeRule> DEFAULT_COLLECTION = new EnumMap<>(MethodType.class);
    private static final Map<MethodType, FeeRule> DEFAULT_PAYOUT = new EnumMap<>(MethodType.class);

    static {
        DEFAULT_COLLECTION.put(MethodType.MOBILE_MONEY, new FeeRule(200, 0L, 10L, null));
        DEFAULT_COLLECTION.put(MethodType.CARD, new FeeRule(290, 30L, null, null));
        DEFAULT_COLLECTION.put(MethodType.PAYPAL, new FeeRule(349, 49L, null, null));
        DEFAULT_COLLECTION.put(MethodType.BANK_TRANSFER, new FeeRule(100, 0L, 50L, 500L));
        DEFAULT_COLLECTION.put(MethodType.CRYPTO, new FeeRule(100, 0L, null, null));
        DEFAULT_COLLECTION.put(MethodType.PREMISAVE_WALLET, new FeeRule(100, 0L, null, null));

        DEFAULT_PAYOUT.put(MethodType.MOBILE_MONEY, new FeeRule(100, 20L, null, null));
        DEFAULT_PAYOUT.put(MethodType.BANK_TRANSFER, new FeeRule(50, 50L, null, null));
        DEFAULT_PAYOUT.put(MethodType.PAYPAL, new FeeRule(100, 25L, null, null));
        DEFAULT_PAYOUT.put(MethodType.CRYPTO, new FeeRule(100, 100L, null, null));
        DEFAULT_PAYOUT.put(MethodType.CARD, new FeeRule(100, 25L, null, null));
        DEFAULT_PAYOUT.put(MethodType.PREMISAVE_WALLET, new FeeRule(50, 0L, null, null));
    }

    private final CommercialProperties props;

    public FeeService(CommercialProperties props) {
        this.props = props;
    }

    /** Fee in USD cents for moving {@code amountCents} by {@code method} in the given direction. */
    public long fee(Direction direction, MethodType method, long amountCents) {
        return compute(ruleFor(direction, method), amountCents);
    }

    FeeRule ruleFor(Direction direction, MethodType method) {
        boolean collection = direction == Direction.COLLECTION;
        Map<String, FeeRule> configured = collection ? props.fees().collection() : props.fees().payout();
        FeeRule override = configured.get(method.name());
        if (override != null) return override;
        return (collection ? DEFAULT_COLLECTION : DEFAULT_PAYOUT).get(method);
    }

    /** percent of the amount (basis points, half up to the cent) plus the flat part, clamped to min and max. */
    public static long compute(FeeRule rule, long amountCents) {
        long percentPart = BigDecimal.valueOf(amountCents)
                .multiply(BigDecimal.valueOf(rule.percentBps()))
                .divide(BigDecimal.valueOf(10_000), 0, RoundingMode.HALF_UP)
                .longValueExact();
        long fee = percentPart + rule.flatCents();
        if (rule.minCents() != null) fee = Math.max(fee, rule.minCents());
        if (rule.maxCents() != null) fee = Math.min(fee, rule.maxCents());
        return fee;
    }
}
