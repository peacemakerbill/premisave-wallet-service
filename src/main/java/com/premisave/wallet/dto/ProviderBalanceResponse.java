package com.premisave.wallet.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Premisave's OWN balance held with a payment gateway — not any
 * customer's wallet balance. Lets an admin check how much money the
 * platform has sitting with each provider without logging into five
 * separate dashboards.
 *
 * Deliberately does NOT force every provider into the same "available +
 * pending" shape — each provider genuinely distinguishes different
 * things (Stripe: available/pending; PayPal: total/available/withheld;
 * Flutterwave: whatever "Wallets -> Balances" actually returns). Forcing
 * a false uniformity here would misrepresent what each number actually
 * means. CurrencyBalance.amounts is keyed by whatever that provider
 * calls its own figures.
 *
 * status distinguishes a genuine, unavoidable API difference: M-Pesa has
 * no synchronous "get balance now" call at all — Safaricom's Account
 * Balance API is asynchronous (submit a query, the real number arrives
 * later via webhook). status=PENDING_ASYNC represents that honestly
 * rather than pretending M-Pesa returns a number synchronously the way
 * the other four providers do.
 */
@Data
public class ProviderBalanceResponse {
    private String provider;

    /** "AVAILABLE" — balances populated below, fetched synchronously. "PENDING_ASYNC" — M-Pesa only; see pollNote. "ERROR" — the provider call itself failed; see message. */
    private String status;

    private List<CurrencyBalance> balances;
    private String message;

    /** Set only for status=PENDING_ASYNC (M-Pesa) — how to actually retrieve the result once it arrives. */
    private String pollNote;

    private LocalDateTime fetchedAt;

    @Data
    public static class CurrencyBalance {
        private String currency;
        private Map<String, BigDecimal> amounts;
    }
}