package com.premisave.wallet.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class DepositRequest {

    @NotNull
    @DecimalMin("1.00")
    private BigDecimal amount;

    /**
     * Payment provider: MPESA | MPESA_TILL | STRIPE | PAYPAL | FLUTTERWAVE
     * Defaults to MPESA if omitted.
     */
    private String provider;

    /**
     * Used for M-Pesa STK push (provider=MPESA) — the customer's Safaricom
     * number. Format: 07xxxxxxxx or 254xxxxxxxx. Optional if the wallet has
     * a saved M-Pesa phone number (see PUT /wallet/mpesa-phone) — that's
     * used automatically when this is omitted, so repeat deposits don't
     * require typing a number every time.
     */
    private String phoneNumber;

    /**
     * Required for provider=MPESA_TILL (B2B Express Checkout) — the paying
     * merchant's own till number. The USSD PIN prompt is sent to whichever
     * phone is registered as that till's nominated operator number, not to
     * a number you supply directly.
     */
    private String payerTillNumber;

    /**
     * ISO 4217 currency code (e.g. KES, USD, EUR).
     * Defaults to KES for M-Pesa, USD for PayPal and Flutterwave.
     */
    private String currency;

    /**
     * Optional, provider=FLUTTERWAVE only — restricts which channels
     * Flutterwave's hosted checkout page shows, e.g. "card",
     * "banktransfer", "ussd", "mobilemoneyghana,mobilemoneyuganda,mobilemoneyrwanda,mobilemoneyzambia,mobilemoneyfranco",
     * or any comma-separated combination. Left null/blank to let
     * Flutterwave show every channel enabled on the account.
     *
     * Kenyan mobile money is intentionally not offered through this path —
     * use provider=MPESA for that (direct Daraja STK Push integration).
     */
    private String flutterwavePaymentOptions;

    /**
     * Optional, provider=FLUTTERWAVE only — customer display name/phone
     * pre-filled on Flutterwave's checkout page. Both optional; the
     * customer's email is always taken from the authenticated user, never
     * from this request.
     */
    private String customerName;
    private String customerPhone;
}