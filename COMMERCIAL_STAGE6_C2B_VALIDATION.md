# Stage 6: paybill account references and validation callbacks

Stage 6 finishes account references for live M-Pesa. A merchant creates a reference (`POST /payments/account-references`), the customer pays it as the account number of a paybill, and the money is matched to the merchant and credited. If the merchant registered a `VALIDATION` callback (section 11.13 of the endpoint reference), it is asked to accept or reject the payment before the customer's money moves.

Nothing here has run against Safaricom, a real MongoDB or Maven. The code was compiled with javac against stubs and its pure logic is unit tested (379 tests pass). Try it with Safaricom's sandbox paybill first.

## How a payment flows

1. The merchant creates a reference. With `commercial.c2b.enabled` the live `PayToProvider` (`MpesaPaybillPayTo`) points it at `commercial.c2b.shortcode`; without it a live reference is still refused with `422 METHOD_UNAVAILABLE`. The reference is the merchant's prefix plus the upper cased `merchantReference`, for example `PS1234-INV001`.
2. The customer pays the paybill with that account number. Safaricom calls our validation address.
3. Validation (`C2bService.validate`), before any money moves. The payment is refused when the account number is unknown, belongs to a test reference, belongs to another paybill, is closed or expired (`C2B00012`), or the reference has an `expectedAmount` and the amount is wrong (`C2B00013`). For a known reference, a refusal is recorded as a failed payment with `result.code` `DECLINED`, so the merchant sees it and receives `payment.failed`.
4. Merchant validation. If the app has an active `VALIDATION` registration the payment details (11.13) are sent through the developer service and the merchant answers `ACCEPT` or `REJECT` within `commercial.c2b.validation-deadline-ms` (default 3000). No answer, an error or an unreadable reply applies the registration's `onTimeout` (default `REJECT`). A rejection is recorded like any other refusal and answered to Safaricom with `C2B00016`.
5. Confirmation (`C2bService.confirm`), after the customer has paid. The payment is first written down as a `C2bReceipt` (id is Safaricom's transaction id), then credited: a `PAYMENT` with id `pay_c2b_<TransID>` is created, priced in KES with the normal fee, and succeeded through the usual outcome path. The merchant's balance, ledger and `payment.succeeded` event follow as for any payment. A repeated confirmation lands on the same receipt and transaction, so it cannot credit twice.

The customer's money has already moved at step 5, so the payment is credited whenever its reference is real, and flagged when something is off. The flag is in `metadata.flag` on the payment: `AMOUNT_MISMATCH` (the amount differs from `expectedAmount`), `REFERENCE_EXPIRED` or `REFERENCE_CLOSED`.

## Payments that cannot be credited

A confirmed payment that cannot be matched is kept as an `UNMATCHED` receipt for an operator, with a reason: `UNKNOWN_REFERENCE`, `TEST_REFERENCE`, `WRONG_SHORTCODE`, `INVALID_AMOUNT`, `PAID_AFTER_REFUSAL` (we refused it at validation but Safaricom completed it anyway, which happens with response type `Completed`), `NOT_PRICEABLE` (too small to carry its fee) or `PROCESSING_FAILED`. List them with `GET /internal/commercial/v1/c2b/unmatched`. Each is also logged at error level. Returning the money, or crediting a merchant by hand, is a manual operator decision.

A receipt that was recorded but not credited (the service stopped, or a price could not be had) stays `RECEIVED`; `C2bReceiptSweeper` retries it every `sweep-interval-ms` after `reprocess-after-seconds`, up to 20 tries, then moves it to `UNMATCHED`.

## Addresses and registration

Safaricom is given two signed addresses (the same signing as the STK and B2C callbacks, `commercial.mpesa.callback-secret`):

```
{base}/payments/commercial/rail/c2b-validation/{shortcode}/{signature}
{base}/payments/commercial/rail/c2b-confirmation/{shortcode}/{signature}
```

They contain no word Safaricom rejects. Register them once with `POST /internal/commercial/v1/c2b/register-urls` (internal service key and role), or set `commercial.c2b.register-on-startup: true`. Safaricom keeps one pair per paybill and will not overwrite it: an already registered paybill answers `alreadyRegistered: true`, and its existing addresses must be deleted in the Daraja portal before registering again. For production the paybill needs external validation enabled by Safaricom, or validation is skipped and every payment is simply accepted.

`commercial.c2b.response-type` is what Safaricom does when our validation address cannot be reached. `Cancelled` (default) refuses the payment, which moves no money. `Completed` accepts it, in which case an unreachable validation can let an unmatched or wrong payment through to confirmation.

## A paybill shared with the wallet service

Safaricom allows one address pair per paybill. The wallet service's own C2B deposits use one, so the commercial paybill should be a different paybill. If you must share one, keep the wallet service's addresses registered, set `commercial.c2b.shortcode` to the shared paybill, leave `register-on-startup` off, and call `CommercialC2bRouter` first in the wallet service's validation and confirmation handlers (the two snippets are in its class comment). The router answers only for account numbers that are real commercial references and says nothing otherwise, so the wallet service carries on as before.

## Validation request to the developer service

The developer service owns delivery to merchants. This service sends it one new call:

```
POST {developer-service-url}/internal/validations
X-API-Key: <internal key>

{
  "registrationId": "cbr_30de",
  "url": "https://merchant.example.com/hooks/validate",
  "deadlineMs": 3000,
  "payload": { ...the 11.13 request... }
}
```

It must deliver `payload` to `url` (signed as it signs events), wait up to `deadlineMs`, and reply `200` with the merchant's answer: `{"result": "ACCEPT"}` or `{"result": "REJECT", "reason": "Invoice already paid"}`. Anything else is treated as no answer. The developer service endpoint does not exist yet; until it does, every validation falls back to the registration's `onTimeout`.

The payload follows 11.13: `validationId` (`val_<TransID>`), `type` `payment.validation`, `livemode` true, `accountReference`, `amount` and `currency` (USD), `nativeAmount` and `nativeCurrency` (KES), `payer` with `name` and a masked `phone`, and `deadlineMs`. If a price cannot be had, `amount` and `currency` are left out and the KES figures are still sent. When several validation registrations are active for an app, the first is used.

## Settings

`commercial.c2b.*` in `application.yml`: `enabled`, `shortcode`, `response-type`, `merchant-validation`, `validation-deadline-ms`, `register-on-startup`, `reprocess-after-seconds`, `sweep-interval-ms`. It also needs `commercial.mpesa.enabled` with its callback address and secret.

## Files

New, under `rail/mpesa/c2b/`: `C2bPayload`, `C2bRules`, `C2bReceipt` and repository, `C2bService`, `C2bReceiptSweeper`, `MpesaC2bController`, `C2bUrlRegistrar`, `C2bAdminController`, `CommercialC2bRouter`, `MpesaPaybillPayTo`; and `config/CommercialC2bProperties`, `config/CommercialStage6Config`.

Changed earlier files: `rail/mpesa/MpesaCallbackSigner` (two new kinds), `rail/mpesa/MpesaDarajaClient` (`registerC2bUrls`), `service/DeveloperServiceClient` (`validate`).

## Known limits

* In production Safaricom sends a hash instead of the payer's phone number. The payer's name is kept; the phone is not, so such a payment cannot be refunded automatically (a mobile money refund needs the phone on the payment).
* A reference accepts repeated payments. Each is credited as its own payment; the reference is not closed after the first.
* The amount check for a reference in a currency other than KES compares USD values within 1 percent or 2 cents, at the rate when the customer pays.
* Payments that Safaricom never confirmed to us are not recovered here. The wallet service's pull transactions API could be used for that and is not wired in.
* The merchant validation depends on the developer service endpoint described above, which has to be built there.
