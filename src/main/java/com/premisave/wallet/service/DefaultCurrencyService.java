package com.premisave.wallet.service;

import com.premisave.wallet.entity.Wallet;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * The currency a person chooses to deal in. The system default is USD, and a wallet that never chose uses it. The balance
 * is always kept in USD. The default currency is what an amount is typed in when a deposit or withdrawal does not name
 * one, and what the app shows amounts in.
 */
@Service
@RequiredArgsConstructor
public class DefaultCurrencyService {

    public static final String SYSTEM_DEFAULT = "USD";

    private final ExchangeRateService exchangeRateService;

    /** The wallet's default currency, USD when it never chose one. */
    public static String of(Wallet wallet) {
        String c = wallet.getDefaultCurrency();
        return c == null || c.isBlank() ? SYSTEM_DEFAULT : c.trim().toUpperCase();
    }

    /** Returns the code in upper case, or throws when no exchange rate exists for it. */
    public String validate(String code) {
        String c = code == null ? "" : code.trim().toUpperCase();
        if (!c.matches("[A-Z]{3}")) {
            throw new IllegalArgumentException("Use a three letter currency code, for example USD.");
        }
        if (!SYSTEM_DEFAULT.equals(c)) {
            try {
                exchangeRateService.getRate(SYSTEM_DEFAULT, c);
            } catch (RuntimeException e) {
                throw new IllegalArgumentException("The currency " + c + " is not supported.");
            }
        }
        return c;
    }

    /** [amount] in [from] as the same value in [to], to two decimals. */
    public BigDecimal convert(BigDecimal amount, String from, String to) {
        if (from.equalsIgnoreCase(to)) return amount;
        BigDecimal rate = exchangeRateService.getRate(from.toUpperCase(), to.toUpperCase());
        return amount.multiply(rate).setScale(2, RoundingMode.HALF_UP);
    }
}
