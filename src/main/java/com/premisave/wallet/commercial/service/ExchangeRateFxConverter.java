package com.premisave.wallet.commercial.service;

import com.premisave.wallet.service.ExchangeRateService;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/** Reads rates from the existing ExchangeRateService, so the commercial API and consumer flows always agree. */
@Component
public class ExchangeRateFxConverter implements FxConverter {

    private final ExchangeRateService exchangeRates;

    public ExchangeRateFxConverter(ExchangeRateService exchangeRates) {
        this.exchangeRates = exchangeRates;
    }

    @Override
    public BigDecimal nativePerUsd(String currency) {
        if (currency.equals("USD")) return BigDecimal.ONE;
        return exchangeRates.getRate("USD", currency);
    }
}
