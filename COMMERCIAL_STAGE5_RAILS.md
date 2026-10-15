# Stage 5: live provider rails

Stage 5 adds the live (production) rails for Stripe, PayPal, Flutterwave, NOWPayments and the Premisave wallet to the commercial API, and the credential setups that let card, PayPal and wallet methods be saved for subscriptions. M-Pesa was Phase 2. Everything lives in the `commercial` package; the wallet service's own payment code is not changed.

Nothing here has run against a real provider, a real MongoDB or Maven. The code was compiled with javac against hand-written stubs and its pure logic is unit tested (379 tests pass). Test every rail in the provider's sandbox before real money.

## What each rail does

| Rail | Collections | Payouts | Refunds | Saved method for subscriptions |
|:-|:-|:-|:-|:-|
| `STRIPE` | `CARD`. A PaymentIntent; next action `CLIENT_SECRET` with the client secret and publishable key | none | `PROVIDER_REFUND` against the payment intent | `CARD` through a SetupIntent |
| `PAYPAL` | `PAYPAL`. An Orders v2 order; next action `REDIRECT`. Captured by the rail once approved | `PAYPAL` email through the Payouts API | `PROVIDER_REFUND` against the capture | `PAYPAL` through the vault (setup token, then payment token) |
| `FLUTTERWAVE` | `MOBILE_MONEY` outside Kenya (GHS, UGX, TZS, RWF, ZMW by default); customer approves a prompt | `BANK_ACCOUNT` and `MOBILE_MONEY` in local currency | none | none |
| `NOWPAYMENTS` | `CRYPTO`; next action `CRYPTO_ADDRESS` | none | none | none |
| `PREMISAVE` | `PREMISAVE_WALLET`; the wallet owner approves in the Premisave app | `PREMISAVE_WALLET` credit, final at once | handled by the refund service as `WALLET_CREDIT` | `PREMISAVE_WALLET` through a standing approval |

Every rail serves the live environment only and is off until its `enabled` flag is true. The sandbox is unchanged.

## How results arrive

* Stripe has its own signed webhook: `POST /payments/commercial/rail/webhook/stripe`. It handles payment intents, setup intents, refunds and disputes. Create a separate Stripe Dashboard destination for this address and put its signing secret in `commercial.rails.stripe.webhook-secret`. Do not reuse the wallet service's destination or secret. Events for objects without commercial metadata are ignored, so wallet payments are unaffected.
* PayPal, Flutterwave and NOWPayments webhook addresses are shared with the wallet service, so the commercial rails do not use them. Their results are found by looking them up with the provider (`RailPoller`).
* `RailPoller` looks every open transaction up for any rail that says it is pollable, starting after `commercial.rails.polling.after-seconds` and backing off from 15 seconds to 5 minutes. It is also the safety net behind Stripe's webhook. Applying a result twice is harmless, so overlap with a webhook is fine.
* `PendingMethodPoller` finishes saved methods that are waiting for the customer (Stripe setup intents, PayPal approvals, wallet approvals) and fails them after `setup-timeout-hours`.
* `RecallTimedOutCollections` asks the rail to withdraw an expired unpaid collection, so a customer who finishes later is not charged for a payment already closed. Stripe cancels the intent and the wallet cancels the request. PayPal only takes money when an order is captured and the rail never captures a closed payment, so it needs no call. Flutterwave and NOWPayments cannot recall a payment once the customer has been prompted or given an address.

## Safety rules the rails follow

* A failed card attempt cancels its intent at Stripe at once.
* Requests that may have reached a provider but got no answer are never failed on a guess. They stay pending and the lookup decides. The only exceptions are refusals that happen before anything is sent.
* Provider calls carry stable idempotency keys built from the transaction id (`commercial-order-<id>`, `commercial-capture-<id>`, the transfer reference), so a repeat cannot double charge or double pay.
* A Flutterwave charge that reports success for a different amount or currency than was asked is logged and not applied automatically.
* A NOWPayments payment that arrives short (`partially_paid`) is not credited; it expires unpaid so a person can sort it out with the customer.

## Premisave wallet rail

Collecting from a wallet needs the owner's consent, so there is an owner side as well as the merchant side.

Merchant side, one off payment: `POST /payments` with method `PREMISAVE_WALLET` and `customer.accountRef` (the owner's email). The payment is `REQUIRES_ACTION` with next action `CUSTOMER_CONSENT` (`consentId`, `instructions`, `expiresAt`). It lasts `commercial.rails.premisave.consent-minutes` (never beyond the payment's own expiry).

Owner side, under the existing wallet area and authenticated as the wallet owner (token email), not part of the merchant API:

| Call | Effect |
|:-|:-|
| `GET /wallet/consents` | Requests waiting for an answer and standing approvals already given |
| `POST /wallet/consents/{id}/approve` | Debits the wallet and completes the payment. A short balance returns `422` and leaves the request open; a frozen wallet returns `403`; an expired request `410`; one already answered `409` |
| `POST /wallet/consents/{id}/decline` | Fails the payment with `CUSTOMER_CANCELLED` |
| `POST /wallet/consents/{id}/revoke` | Withdraws a standing approval; later charges under it fail with `DECLINED` |

Subscriptions: adding a `PREMISAVE_WALLET` method to a customer in live creates a standing approval request (`CUSTOMER_CONSENT` next action). When the owner approves it the method becomes `ACTIVE` and its token is the approval id. Each cycle is then charged at once, without a prompt, as long as the approval is active, belongs to the same wallet and merchant, and the charge is at most `mandate-max-usd-cents`. A charge under a standing approval that finds the wallet short fails the invoice attempt like any other failed payment.

Money safety:

* `PremisaveWalletDebit` mirrors the existing `PremisaveWalletCredit`: the balance is replaced only if it is unchanged since it was read, and only if this payment has not already been recorded on the wallet, so a repeat after a crash never debits twice and a balance never goes below zero. It writes a wallet history row for the owner.
* Before debiting, the service checks the payment is still open. After reporting the payment as paid, it checks the payment really ended as paid, and if it did not (it closed in between) credits the money straight back with the id `<paymentId>-undo`. If that undo credit is refused (for example the wallet was frozen meanwhile) it is logged as an error for an operator.
* Every consent state change is a guarded update, so an approval and a timeout racing cannot both win.
* Payouts to a wallet use the existing idempotent credit and finish immediately; an unexpected error leaves the payout pending and the poller repeats the (safe) credit.

The approval has no second factor beyond the owner's login token, the same as a wallet to wallet transfer today.

## Settings

All under `commercial.rails.*` (see `application.yml`). Each rail also reuses the wallet service's own credentials (`stripe.*`, `paypal.*`, `flutterwave.*`, `nowpayments.*`). Currencies a rail serves must also be in `commercial.currencies`, or pricing refuses them. For Flutterwave that means adding the local currencies you enable, for example `COMMERCIAL_CURRENCIES=USD,KES,GHS,UGX`.

## Files

New: `config/CommercialRailsProperties`, `config/CommercialStage5Config`, `rail/http/ProviderHttp`, `rail/RailPoller`, `rail/RecallTimedOutCollections`, `billing/PendingMethodPoller`, `rail/stripe/*` (Money, Mapper, Signature, Api, Rail, CardSetup, WebhookController), `rail/paypal/*` (Mapper, Api, Rail, VaultSetup), `rail/flutterwave/*` (Mapper, Rail), `rail/nowpayments/*` (Mapper, Rail), `rail/premisave/*` (Rail, WalletConsent and repository, WalletConsentService, WalletConsentController, PremisaveWalletDebit with its marker, WalletMandateSetup, ConsentRules).

Changed earlier files: `rail/PaymentRail` (adds `pollable` and `poll`), `billing/CredentialSetup` (adds `Progress` and `poll`), `util/RequestValidators` (a mobile money destination keeps an optional `network`, needed for Flutterwave mobile money payouts).

## Known limits

* Flutterwave: the wallet service's methods fold network faults into a failure message, so a charge or transfer that errored may still have gone through. Payouts stay pending in that case. Charges that report "Charge initiation failed" are treated as unavailable; if one was actually created it will not be linked to the payment. Refunds of Flutterwave payments are not offered.
* Mobile money refunds outside Kenya are not supported. A mobile money refund is sent as a payout to the customer's phone, and a Flutterwave payout needs the mobile network, which a refund does not carry, so such a refund fails (`INVALID_DESTINATION`) and its hold is released. Send the money back with a payout that names the network.
* PayPal: webhooks and PayPal disputes are not handled; results come from polling only.
* NOWPayments: no payouts (NOWPayments requires a person to enter a 2FA code to release a payout).
* Stripe payouts are not offered; bank payouts go through Flutterwave.
* The poller is a single node safety net. With several instances running, overlapping lookups are harmless but repeated; there is no leader election.
