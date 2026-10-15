# Premisave Wallet Service: Commercial API, Phase 2

Phase 2 turns the commercial API from a sandbox-only skeleton into a working payment service provider. Phase 1 gave the foundation (merchants, apps, quotes, payments, payouts, events, a simulated sandbox rail). Phase 2 adds the live rails, the money movement around them, and the billing, refund and dispute features merchants expect.

Phase 2 is delivered in a first step (the live M-Pesa rail) and six stages. This file is the single entry point. Each stage also has its own document with the full detail, listed at the end.

The commercial API is called only by the future Developer Service, never by merchants directly. All routes live under `/internal/commercial/v1/{env}`, where `env` is `live` or `sandbox`. Money is held in USD cents. Everything is new code in the `com.premisave.wallet.commercial` package; the wallet service's own payment code is not rewritten.

## Status at a glance

| Part | What it delivers | Live today |
|:-|:-|:-|
| Step 1: M-Pesa rail | STK push collections, B2C payouts, signed callbacks, reconciler | M-Pesa, Kenya, KES |
| Stage 1 | Settlements, schedules, transfers to Premisave wallets, daily and summary reports, reconciliation | Where a live rail exists |
| Stage 2 | Payment links, account references, bulk payouts, beneficiary name checks | Links, bulk and checks yes; live account references need stage 6 |
| Stage 3 | Refunds and disputes | Refunds by payout to phone or wallet credit; provider refunds need stage 5 rails |
| Stage 4 | Plans, customers, saved payment methods, subscriptions, invoices, dunning | M-Pesa billing; other methods need stage 5 |
| Stage 5 | Live rails for Stripe, PayPal, Flutterwave, NOWPayments and the Premisave wallet | Each rail, once enabled |
| Stage 6 | Paybill account references and validation callbacks (section 11.13) | Once a dedicated paybill is registered |

## Important: what has and has not been verified

Everything was compiled with `javac` against hand-written stand-ins for Spring, MongoDB and the wallet classes, because Maven Central is not reachable from the build environment. The pure logic is covered by 379 unit tests, all passing. There was no Maven build, no real MongoDB and no real provider run. In particular the following are written but unexercised: the Mongo queries and guarded updates, the Spring wiring of the new beans, the schedulers and sweeps, and every call to Safaricom, Stripe, PayPal, Flutterwave and NOWPayments.

Before using real money: run `mvn clean test`, start the service against a development database, exercise the sandbox, then test each rail in its provider's own sandbox.

## Installing

1. Unzip `premisave-wallet-commercial-phase1.zip`, then unzip the Phase 2 archive over it, keeping paths. The Phase 2 archive holds every file Phase 2 added or changed; Phase 1 files that nothing changed are only in the Phase 1 archive.
2. Use the `application.yml` and `additional-spring-configuration-metadata.json` from this delivery.
3. In `SecurityConfig` the public callback list and in `WebConfig` the rate limiter exclusion list each need the line `"/payments/commercial/rail/**",` (step 1 of Phase 2). Nothing else in the security or web configuration changes.
4. Run `mvn clean test`.
5. Turn rails on one at a time (see Configuration). Every live rail is off by default.

The per-stage install notes (`STAGE1_INSTALL.txt` to `STAGE6_INSTALL.txt`, `PHASE2_INSTALL.txt`) list the files each stage changed.

## How the pieces fit together

* **Rails.** A `PaymentRail` collects money, pays money out, and optionally refunds, recalls an unpaid collection, submits dispute evidence and looks a transaction up. `RailRegistry` picks the rail for an environment, direction, method, country and currency. A rail reports results to `TransactionOutcomeService`, the single place that finishes a transaction. The first outcome wins and repeating one is harmless.
* **Holds.** Payouts, settlements, transfers and refunds first hold the amount plus fee on the merchant balance. Success captures the hold; any failure releases it.
* **Never guess.** A request that may have reached a provider but got no answer is left pending and settled by a callback or lookup, never failed on a guess. Only refusals that happen before anything is sent fail at once.
* **Exactly once.** Money steps and events use guarded updates, unique indexes and ids derived from the object, so a restart, a second instance or a repeated callback cannot move money or send an event twice.
* **Sandbox.** Sandbox requests always use the simulated rail and never touch a provider or a real wallet.

## Step 1: live M-Pesa rail

* `POST /payments` with a mobile money method and `customer.phone` sends an STK push. The payment becomes `REQUIRES_ACTION` with a `CUSTOMER_PROMPT` next action and settles when Safaricom calls back.
* `POST /payouts` to a `MOBILE_MONEY` destination sends a B2C payment and stays `PENDING` until Safaricom posts the result.
* Callbacks use HMAC-signed addresses under `/payments/commercial/rail/`, kept separate from the wallet service's own callbacks. A wrong signature gets 403. The paths avoid the word "mpesa" because Safaricom's sandbox rejects it.
* Result codes are mapped (`1032` cancelled, `1037` timed out, `1` insufficient funds, `2001` wrong PIN); unknown codes surface as `PROVIDER_ERROR` with Safaricom's own wording.
* `MpesaStkReconciler` queries payments whose callback never arrived. A payout with no result after 30 minutes is logged for an operator.
* Limits: no B2B payouts to paybill or till, no cancellation of a sent prompt, whole shillings only.

## Stage 1: settlements, transfers and reports

* **Settlements** (`POST /settlements`, list, read). Pay the merchant's balance out to a pre-registered destination (mobile money, bank, PayPal or Premisave wallet). The request names a destination id and can never carry new account details, so a stolen key cannot redirect money. A Kenyan mobile money settlement may be requested in USD and is converted to whole KES.
* **Schedules** (`PUT /settlements/schedule`). `MANUAL`, `DAILY`, `WEEKLY` or `THRESHOLD`, with `keepReserve` and `minimumAmount`. Each period is claimed with a guarded update so it can never run twice.
* **Transfers** (`POST /transfers`). Instant, from the merchant balance into a Premisave wallet. The credit is applied exactly once; a recovery sweep finishes any transfer interrupted between the hold and the credit. The merchant sees the recipient masked.
* **Reports.** `GET /reports/daily` (JSON or CSV), `GET /reports/summary` (group by day, week, month, method or app, up to 366 days), and `GET /reports/reconciliation`, which checks the merchant's transactions against their own ledger and lists every disagreement. Provider statements are not available, so reconciliation is internal only.
* `CommercialIndexInitializer` creates the declared indexes at startup, because the project did not enable Spring Data's index creation and the commercial code relies on unique indexes.
* A fee is now posted to company revenue only when the money actually moved.

## Stage 2: payment links, account references, bulk payouts, beneficiary checks

* **Payment links** (`POST/GET/PUT /payment-links`). A link holds rules (fixed or open amount, allowed methods, single or multiple use, expiry). Each customer attempt is an ordinary payment created with `paymentLinkId`. A single-use link takes one successful payment, with a double check so two customers cannot both take the last use. Status is computed on read.
* **Account references** (`POST /payments/account-references`). A reference is the merchant's prefix (`PS` plus 4 or more digits) plus the upper-cased `merchantReference`, for example `PS1234-INV001`. The sandbox issues references against the Daraja test paybill. Live issuing works once stage 6 is enabled.
* **Bulk payouts** (`POST /payouts/bulk`, `GET /payouts/batches/{batchId}`). Up to 500 items. The whole request is validated and the total checked against the balance before money moves. `ALL_OR_NOTHING` or best effort. A dispatcher sends about five payouts a second, closes payouts stuck without an answer, rolls back interrupted intakes, and raises `payout.batch.completed` once.
* **Beneficiary checks** (`POST /beneficiaries/verify`, `/lookup-account`, `GET /beneficiaries/checks/{checkId}`). Verification fails closed: only an `EXACT` or `PARTIAL` name match lets a required payout proceed. Name matching ignores case, order, punctuation and accents and understands masked names. Live lookups exist today only for Premisave wallets (name always masked); other live types return `UNAVAILABLE` until a resolver exists. A payout can require a check with `beneficiaryCheck`, and a merchant can require it by default through `requireBeneficiaryCheck`.

## Stage 3: refunds and disputes

* **Refunds** (`POST/GET /refunds`). A refund is a transaction of type `REFUND` and reuses the hold path, so it appears in transactions, statements and reports. The method follows the original payment: `PROVIDER_REFUND` or `PROVIDER_REVERSAL` where the rail can refund itself, `PAYOUT_TO_CUSTOMER` for mobile money, `WALLET_CREDIT` for Premisave wallet, and `422 METHOD_UNAVAILABLE` otherwise.
* Partial refunds are supported in USD or the customer's currency, at the rate the customer paid. The last refund takes exactly what is left. A refund carries no fee and the original fee is not returned.
* **Over-refund protection.** Each refund first reserves its amount on the payment with one guarded update, so two refunds arriving together cannot both take the last of a payment. A failed refund gives its reservation back, and a repair routine fixes any reservation left by a crash.
* Refunds above `commercial.refunds.approval-threshold-usd` (default 1000) are flagged `requiresApproval`; the flag is informational.
* **Disputes** (`GET /disputes`, `GET /disputes/{id}`, `POST /disputes/{id}/evidence`). Providers open and decide disputes through `DisputeService.open` and `resolve`. Opening holds the disputed amount and raises `dispute.opened`; winning releases the hold, losing takes it. Evidence can be saved as drafts and submitted to the provider in one go. The sandbox can open and resolve test disputes.
* Events: `refund.succeeded`, `refund.failed`, `dispute.opened`, `dispute.won`, `dispute.lost`.

## Stage 4: subscriptions

* **Plans, customers, saved payment methods, subscriptions and invoices**, plus the sandbox helpers `advance-clock` and `authorise`.
* Billing is in advance and in UTC, with month-end handling (a subscription anchored on the 31st bills on the last day of shorter months). A subscription copies the plan's terms when created, so changing a plan never changes what an existing subscriber pays. There is no proration.
* A billing cycle creates an invoice (unique per subscription and period), advances the subscription, then collects through a normal payment with a stable idempotency key. A listener settles the invoice when the payment finishes, and a recovery sweep re-delivers any result missed while the service was down.
* **Dunning.** A provider outage is retried after 15 minutes up to 4 times without counting against the customer. Counted failures follow the plan's `retryScheduleDays` (default 1, 3 and 5 days); the subscription is `PAST_DUE` while retrying, and ends `CANCELLED` or `EXPIRED` per the plan.
* Pause, resume (optionally billing now) and cancel (immediately or at period end) are supported.
* Events: `subscription.created`, `.activated`, `.past_due`, `.paused`, `.cancelled`, `customer.payment_method.activated`, `invoice.upcoming`, `invoice.paid`, `invoice.payment_failed`.
* Saved methods: mobile money works at once (each cycle sends a prompt); card, PayPal and Premisave wallet work in live once stage 5 is enabled.

## Stage 5: live rails

| Rail | Collects | Pays out | Refunds | Saved method |
|:-|:-|:-|:-|:-|
| `STRIPE` | `CARD` through a PaymentIntent | no | provider refund | `CARD` through a SetupIntent |
| `PAYPAL` | `PAYPAL` through Orders v2, captured by the rail once approved | `PAYPAL` email through Payouts | provider refund | `PAYPAL` through the vault |
| `FLUTTERWAVE` | `MOBILE_MONEY` outside Kenya (GHS, UGX, TZS, RWF, ZMW by default) | `BANK_ACCOUNT` and `MOBILE_MONEY` | none | none |
| `NOWPAYMENTS` | `CRYPTO` with a `CRYPTO_ADDRESS` next action | no | none | none |
| `PREMISAVE` | `PREMISAVE_WALLET` with owner consent | wallet credit | wallet credit | `PREMISAVE_WALLET` through a standing approval |

* **How results arrive.** Stripe has its own signed webhook at `/payments/commercial/rail/webhook/stripe`. The other providers' webhook addresses are shared with the wallet service, so their results come from lookups by `RailPoller` (backoff from 15 seconds to 5 minutes), which is also the safety net behind Stripe. `PendingMethodPoller` finishes saved methods waiting for the customer. `RecallTimedOutCollections` withdraws an expired unpaid collection where the provider allows it.
* **Premisave wallet consent.** Collecting from a wallet needs the owner's approval. The owner answers through `GET /wallet/consents` and `POST /wallet/consents/{id}/approve|decline|revoke`, authenticated as the wallet owner. The debit is atomic and idempotent, cannot take a balance below zero, and is checked before and after: if the payment closed in the meantime the money is credited straight back. A standing approval lets subscription cycles charge without a prompt, up to `mandate-max-usd-cents`.
* A Flutterwave charge reporting a different amount than asked is logged and not applied. A short NOWPayments payment is not credited.

## Stage 6: paybill account references and validation callbacks

* A customer pays the paybill using the merchant's account reference. Safaricom calls two signed addresses: `/payments/commercial/rail/c2b-validation/{shortcode}/{signature}` before the money moves, and `.../c2b-confirmation/...` after.
* **Validation** refuses unknown, test, closed or expired references, wrong amounts for references with an `expectedAmount`, and anything the merchant rejects. If the app has an active `VALIDATION` registration, the payment is sent through the developer service and the merchant must answer `ACCEPT` or `REJECT` within `validation-deadline-ms` (default 3000). No answer applies the registration's `onTimeout`, default `REJECT`.
* **Confirmation** writes a `C2bReceipt` first, then credits a `PAYMENT` (id `pay_c2b_<TransID>`) with the normal fee. A repeated confirmation cannot credit twice. A sweeper retries receipts that were recorded but not credited.
* Payments that cannot be credited are kept as `UNMATCHED` receipts with a reason, listed at `GET /internal/commercial/v1/c2b/unmatched`.
* Register the addresses once with `POST /internal/commercial/v1/c2b/register-urls`. Safaricom allows one pair per paybill, so use a paybill different from the wallet service's C2B deposits, or route through `CommercialC2bRouter`.

## Configuration

All keys have defaults and are listed in `application.yml` and the metadata file. The essentials:

```
commercial.mpesa.enabled, callback-base-url (https), callback-secret (16+ characters)
commercial.rails.stripe.enabled, webhook-secret          (reuses stripe.* credentials)
commercial.rails.paypal.enabled                          (reuses paypal.* credentials)
commercial.rails.flutterwave.enabled                     (reuses flutterwave.* credentials)
commercial.rails.nowpayments.enabled                     (reuses nowpayments.* credentials)
commercial.rails.premisave.enabled, consent-minutes, mandate-max-usd-cents
commercial.c2b.enabled, shortcode, response-type (Cancelled or Completed), validation-deadline-ms
commercial.currencies    must include every currency a rail serves, for example USD,KES,GHS,UGX
commercial.links.*, commercial.batches.*, commercial.billing.*, commercial.settlements.*
commercial.refunds.*, commercial.disputes.*, commercial.beneficiary.cache-hours
```

The service refuses to start if the M-Pesa rail or the C2B paybill settings are enabled but invalid (for example a callback address that is not https, a secret under 16 characters, or a shortcode that is not 5 to 7 digits). The other rails do not check their settings at startup; a missing provider key shows up as a failed call.

## Collections added

`commercial_payment_links`, `commercial_account_references`, `commercial_account_prefixes`, `commercial_payout_batches`, `commercial_beneficiary_checks`, `commercial_wallet_credits`, `billing_plans`, `billing_customers`, `customer_payment_methods`, `billing_subscriptions`, `billing_invoices`, the dispute and settlement collections, wallet consent and debit marker collections for the Premisave rail, and the C2B receipts collection. Indexes are created at startup.

## Manual steps before going live

1. Run `mvn clean test` and fix anything the stand-in build could not catch.
2. Enable one rail at a time and test it in the provider's sandbox: Safaricom sandbox for M-Pesa and the paybill, Stripe test mode, PayPal sandbox, Flutterwave test keys.
3. Create a separate Stripe Dashboard destination for `/payments/commercial/rail/webhook/stripe` with its own signing secret. Do not reuse the wallet service's.
4. Add the local currencies for any Flutterwave market to `commercial.currencies`.
5. Choose a dedicated paybill, ask Safaricom to enable external validation, and register the C2B addresses.
6. Build the Developer Service endpoint `POST /internal/validations` (request and reply shape are in the stage 6 document). Until it exists, merchants with a `VALIDATION` registration have their paybill payments refused by the `onTimeout` default.
7. Build the Premisave app screens for `/wallet/consents`.
8. Decide who watches the logs and the unmatched C2B list; several conditions are logged at error level for an operator.

## Known limits

* Flutterwave: an ambiguous network failure may hide a charge or transfer that went through, so payouts stay pending in that case; no refunds are offered; mobile money refunds outside Kenya fail because the network is not carried on the payment.
* PayPal: no webhooks and no PayPal disputes; results come from polling only.
* NOWPayments: no payouts. Stripe: no payouts. Crypto and bank transfer payments cannot be refunded.
* Paybill payments in production carry a hashed phone number, so they cannot be refunded automatically.
* Beneficiary name lookups exist live only for Premisave wallets.
* The pollers and sweepers have no leader election. With several instances the work is repeated but harmless.
* Wallet approval has no second factor beyond the owner's login token, the same as a wallet to wallet transfer today.
* An undo credit after a wallet debit can be refused if the wallet was frozen meanwhile; it is logged for an operator.
* No proration, coupons, taxes or usage-based billing. Refunds carry no fee. The refund approval flag is informational.
* The existing wallet code still updates balances by read and save, so a save based on an older read can overwrite a commercial credit or debit made in between. That predates this work.

## Where the detail is

| File | Covers |
|:-|:-|
| `COMMERCIAL_API_ENDPOINTS.md` | The full API reference, including an Implementation status section with every "as built" difference from the draft |
| `COMMERCIAL_MPESA_RAIL.md` | Step 1 |
| `COMMERCIAL_STAGE1_SETTLEMENTS_REPORTS.md` | Stage 1 |
| `COMMERCIAL_STAGE2_LINKS_BULK_BENEFICIARY.md` | Stage 2 |
| `COMMERCIAL_STAGE3_REFUNDS_DISPUTES.md` | Stage 3 |
| `COMMERCIAL_STAGE4_SUBSCRIPTIONS.md` | Stage 4 |
| `COMMERCIAL_STAGE5_RAILS.md` | Stage 5 |
| `COMMERCIAL_STAGE6_C2B_VALIDATION.md` | Stage 6 |
| `PHASE2_INSTALL.txt`, `STAGE1_INSTALL.txt` to `STAGE6_INSTALL.txt` | Files changed and install steps per part |
