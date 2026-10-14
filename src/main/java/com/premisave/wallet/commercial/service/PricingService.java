package com.premisave.wallet.commercial.service;

import com.premisave.wallet.commercial.config.CommercialProperties;
import com.premisave.wallet.commercial.entity.Quote;
import com.premisave.wallet.commercial.enums.Direction;
import com.premisave.wallet.commercial.enums.ErrorCode;
import com.premisave.wallet.commercial.enums.MethodType;
import com.premisave.wallet.commercial.exception.CommercialException;
import com.premisave.wallet.commercial.util.Money;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.Locale;

/**
 * Turns a requested amount into the numbers the ledger needs. USD is the
 * default currency; any other supported currency is converted at the current
 * rate, or at the rate fixed by a quote the caller names.
 */
@Service
public class PricingService {

    private final CommercialProperties props;
    private final FxConverter fx;
    private final FeeService fees;

    public PricingService(CommercialProperties props, FxConverter fx, FeeService fees) {
        this.props = props;
        this.fx = fx;
        this.fees = fees;
    }

    public String normaliseCurrency(String currency) {
        String c = currency == null || currency.isBlank() ? Money.BASE_CURRENCY : currency.trim().toUpperCase(Locale.ROOT);
        if (!props.supportsCurrency(c)) {
            throw CommercialException.validation("currency", c + " is not supported");
        }
        return c;
    }

    /**
     * Prices an amount. When {@code quote} is given its rate, USD amount and fee are used
     * as they were quoted; the caller must already have checked the quote matches the request.
     */
    public Priced price(Direction direction, MethodType method, BigDecimal amount, String currencyInput,
                                 Quote quote) {
        String currency = normaliseCurrency(currencyInput);
        if (amount == null || amount.signum() <= 0) {
            throw CommercialException.validation("amount", "must be greater than zero");
        }
        if (Money.hasMoreThanCents(amount)) {
            throw CommercialException.validation("amount", "must have at most 2 decimal places");
        }

        long usdCents;
        BigDecimal nativeAmount = null;
        String nativeCurrency = null;
        BigDecimal rate = null;

        if (currency.equals(Money.BASE_CURRENCY)) {
            usdCents = Money.toCents(amount);
        } else {
            rate = quote != null ? quote.getFxRate() : lookupRate(currency);
            usdCents = quote != null ? quote.getUsdCents() : Money.nativeToUsdCents(amount, rate);
            nativeAmount = amount.setScale(2, java.math.RoundingMode.HALF_UP);
            nativeCurrency = currency;
        }
        if (usdCents <= 0) {
            throw CommercialException.validation("amount", "is too small once converted to USD");
        }

        long fee = quote != null ? quote.getFeeCents() : fees.fee(direction, method, usdCents);
        long settle;
        if (direction == Direction.COLLECTION) {
            if (fee >= usdCents) {
                throw CommercialException.validation("amount", "is below the minimum for this method once the fee is applied");
            }
            settle = usdCents - fee;
        } else {
            settle = usdCents + fee;
        }
        return new Priced(usdCents, currency, nativeAmount, nativeCurrency, rate, fee, settle);
    }

    private BigDecimal lookupRate(String currency) {
        try {
            BigDecimal rate = fx.nativePerUsd(currency);
            if (rate == null || rate.signum() <= 0) {
                throw new IllegalStateException("non-positive rate");
            }
            return rate;
        } catch (IllegalArgumentException e) {
            throw CommercialException.validation("currency", currency + " cannot be priced");
        } catch (RuntimeException e) {
            throw new CommercialException(ErrorCode.PROVIDER_UNAVAILABLE,
                    "The exchange rate for " + currency + " is not available right now");
        }
    }
}
