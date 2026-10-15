# Commercial API, Phase 2 stage 2: payment links, account references, bulk payouts, beneficiary checks

Everything here follows `COMMERCIAL_API_ENDPOINTS.md`. Where the build differs from the draft, the "as built" notes in that file's Implementation status section say so.

## What it adds

| Index rows | Endpoint | What it does |
|:-|:-|:-|
| 14 | `POST /payment-links` | Creates a reusable pay page definition |
| 15 | `GET /payment-links` | Lists links (filters: status, merchantReference, from, to) |
| 16 | `GET /payment-links/{id}` | One link with its attempt ids and live usage count |
| 17 | `PUT /payment-links/{id}` | Changes description, status, expiry, methods, callback, metadata |
| 18 | `POST /payments/account-references` | Issues an account reference (sandbox only for now) |
| 20 | `POST /payouts/bulk` | Accepts up to 500 payouts in one request |
| 23 | `GET /payouts/batches/{batchId}` | Batch status, counters and (optionally) items |
| 24 | `POST /beneficiaries/verify` | Checks who owns a destination, optionally against an expected name |
| 25 | `POST /beneficiaries/lookup-account` | Returns the registered name of a destination |
| 26 | `GET /beneficiaries/checks/{checkId}` | Reads an earlier check |
| 19 | `POST /payouts` | New optional `beneficiaryCheck {required, expectedName, checkId}` |

All of them exist under both `/live` and `/sandbox`.

## Payment links

A link holds rules only. Each customer attempt is an ordinary payment: the developer service calls `POST /payments` with the new field `paymentLinkId`. The payment then takes the link's currency, `merchantReference`, callback address and metadata (request metadata wins on a clash), and its `relations` carry the link id. A `paymentLinkId` the merchant does not own is `404 NOT_FOUND`.

Rules checked on every attempt, before any money moves:

* The link must be `ACTIVE`. `DISABLED`, `COMPLETED` and `EXPIRED` links refuse with `409 INVALID_STATE`.
* The method must be one of the link's `allowedMethods` (`403 METHOD_NOT_ENABLED`). The default is every method enabled for the app.
* A `FIXED` link takes exactly its amount; an `OPEN` link takes any amount inside its optional `minAmount` and `maxAmount`. A different amount or currency is `400 VALIDATION_FAILED`.
* A `SINGLE` link takes one successful payment. While an attempt is open (customer has not finished) or paid, new attempts are refused. The check is made before and again after the attempt is recorded, so two customers racing for the last use cannot both go through. Worst case both are turned away and one retries; an unsent attempt that loses the race is deleted without any event.

The link `status` you see is worked out on each read: stored `DISABLED` or `COMPLETED`, otherwise `EXPIRED` once `expiresAt` has passed, otherwise `ACTIVE`. A `SINGLE` link becomes `COMPLETED` when its payment succeeds, through a new `TransactionFinishListener` that the outcome service calls after a transaction is finished. `usageCount` is recounted from the payments each time (never incremented), so repeating the step is harmless, and `GET /payment-links/{id}` always recounts live.

`PUT` refuses to change `amount`, `minAmount`, `maxAmount` or `currency` once any payment was attempted on the link (`409 INVALID_STATE`). A completed link cannot be reactivated.

The link `url` is `commercial.links.checkout-base-url` plus `/pay/{id}` (the developer service serves that page). With no address configured the url is the relative path `/pay/{id}`.

## Account references

`PS1234-INV001` is a merchant prefix (`PS` and 4 or more digits, allocated once per merchant and environment, kept unique by an index) plus the upper cased `merchantReference`. The reference is the document id, so it is unique across all merchants and environments. A second reference for the same `merchantReference` returns `409 DUPLICATE_REQUEST`.

Issuing a reference needs a rail that can match an incoming paybill payment to it. That matching is validation callbacks (11.13), planned for stage 6, so live is deliberately refused with `422 METHOD_UNAVAILABLE` rather than handing customers a reference that would not be credited. The sandbox issues references that point at the Daraja test paybill 174379 and are never paid. `PayToProvider` is the seam: stage 6 adds an M-Pesa implementation and live starts working.

## Bulk payouts

Intake runs in this order and never moves money until the whole request has been checked:

1. Each item goes through the same preparation as a single payout (destination, method policy, quote, price, rail, beneficiary check). Failures are collected per item. A repeated `merchantReference` inside the batch is rejected as `DUPLICATE_REQUEST`.
2. `ALL_OR_NOTHING` with any failure: the batch is saved as `REJECTED`, nothing is held, and every item is listed in `rejected` (the failing ones with their own code, the others with `BATCH_REJECTED`).
3. The total of amounts plus fees is compared with the available balance. If it does not fit: `422 INSUFFICIENT_BALANCE` with `data.required` and `data.available`, in either mode, and nothing is saved.
4. Items are held and recorded one at a time (so daily limits see earlier items). They are recorded in state `INTAKE`, which the dispatcher ignores. An item that now fails (limit, or balance moved) is rejected in `BEST_EFFORT`; in `ALL_OR_NOTHING` all recorded items are cancelled silently (no events, money released) and the batch is `REJECTED`.
5. The batch is saved, then its items are released to `QUEUED` in one update.

`BatchDispatcher` (one run every 2 seconds by default) then:

1. Sends up to 10 queued payouts, each claimed with a guarded update (`QUEUED` to `SENDING`) so two instances never send the same one, then marks it `SENT`. That is about five payouts a second.
2. Closes payouts left `SENDING` with no answer for `stuck-after-minutes` (60) as `TIMED_OUT`, releasing the money. If the provider had received one, its result would have arrived by then. The result text tells the merchant to check the provider statement before sending again.
3. Rolls back batches whose intake was interrupted (items still `INTAKE` after 10 minutes): items cancelled silently and the batch marked `REJECTED` with `INTAKE_INTERRUPTED`.
4. Finishes batches with nothing pending: `COMPLETED` (everything paid) or `COMPLETED_WITH_FAILURES` (any item failed, timed out, or was turned away at intake), then raises `payout.batch.completed` once. The event id is derived from the batch id, and a batch whose event was not raised (crash in between) is picked up on the next run.

Items are ordinary payouts: they raise the usual `payout.*` events to the registered callbacks. The batch `callbackUrl` receives `payout.batch.completed` only. `GET /payouts/{id}` and `GET /payouts?batchId=...` work on items, and a payout shows its `batchId`.

A batch `merchantReference` is unique per merchant and environment while the batch is not rejected (a sparse unique index), so a rejected batch's reference can be reused and a second live batch with the same reference is `409 DUPLICATE_REQUEST`.

## Beneficiary checks

Verification fails closed. A match of `EXACT` or `PARTIAL` is the only thing that lets a required payout proceed.

Lookups come from `BeneficiaryResolver` beans:

| Resolver | Serves | Notes |
|:-|:-|:-|
| `PremisaveWalletBeneficiaryResolver` | live, `PREMISAVE_WALLET` | Compares against the real wallet owner name; shows only a masked name (`S***** O**`). A frozen wallet counts as not found |
| `SandboxBeneficiaryResolver` | sandbox, every type except crypto | Everything is registered to `JOHN DOE` (`ACME TRADERS LTD` for paybill and till). Account numbers ending `0001` do not exist; ending `0002` simulate a provider outage |
| none | live mobile money, paybill, till, bank, PayPal | No lookup available yet. `verify` returns 200 with `verified` false and `match` `UNAVAILABLE`; `lookup-account` returns `422 METHOD_UNAVAILABLE` |

Daraja has no public name lookup, so live M-Pesa names need a separate service; a new `BeneficiaryResolver` bean is all it takes to plug one in. Bank resolve (Flutterwave) and PayPal arrive with their rails in stage 5. Crypto addresses are checked for format only (source `FORMAT_CHECK`, `verified` true, `match` `NOT_REQUESTED`, or `UNAVAILABLE` when a name was expected).

The provider answer, not the comparison, is cached per merchant, environment and destination for `commercial.beneficiary.cache-hours` (24). Each call still creates its own check, so a different `expectedName` is judged afresh; `cached` says whether the provider was asked. Answers that could not be obtained are never cached. Checks are deleted after 30 days.

Name comparison ignores case, order, punctuation and accents, and understands the forms providers return: `JOHN D***` and `J DOE` match `JOHN DOE` as `PARTIAL`. All words matching exactly is `EXACT`. Extra words on one side, masked forms, or a one letter slip in a word of five or more letters give `PARTIAL`. A shared first name alone, or any word that differs, is `MISMATCH`. Anyone who can guess a name can ask whether it matches (the same is true of any verification service); the real name of a Premisave wallet is never returned.

On a payout (and on each bulk item), `beneficiaryCheck.required` defaults to the new merchant setting `requireBeneficiaryCheck` (change it with `PUT /merchants/{merchantId}/config`, field `requireBeneficiaryCheck`). A required check needs `expectedName` (a fresh or cached check) or a `checkId` from `verify` for the same destination. It is judged last in preparation and before anything is held, so a refused payout (`422 BENEFICIARY_NOT_VERIFIED`, with `data.check`) moves no money. A provider outage during a payout counts as `UNAVAILABLE`, not as a 503. An optional check (not required) never blocks; its result is still recorded on the payout as `beneficiary`.

## Changed Phase 1 and stage 1 files

| File | Change |
|:-|:-|
| `dto/CreatePaymentRequest` | new optional `paymentLinkId` (old constructor kept) |
| `dto/CreatePayoutRequest` | new optional `beneficiaryCheck` (old constructor kept) |
| `dto/UpdateConfigRequest` | new optional `requireBeneficiaryCheck` |
| `dto/Views` | payments show `paymentLinkId`; payouts show `beneficiary` and `batchId`; merchant shows `requireBeneficiaryCheck`; `maskedDestination` is public |
| `entity/MerchantProfile` | field `requireBeneficiaryCheck` (false for existing merchants) |
| `entity/CommercialTransaction` | two sparse indexes on `relations.paymentLinkId` and `relations.batchId` |
| `service/CommercialPaymentService` | applies payment link rules; `checkCallbackUrl` is public |
| `service/CommercialPayoutService` | split into `prepare`, `queue`, `dispatchQueued`; applies the beneficiary check; constructor takes `BeneficiaryService` and `HeldDebit` |
| `service/TransactionOutcomeService` | calls `TransactionFinishListener` beans after a transaction is finished |
| `service/TransactionFinishListener` | new interface |
| `service/CommercialTransactionStore` | `discardUnsent` |
| `service/EventService` | `emitWithId`; no event for items marked silent |
| `service/EventTypes` | two new constants |
| `service/MerchantService` | saves `requireBeneficiaryCheck` |
| `service/SandboxService` | reset also clears links, account references, batches and beneficiary checks |
| `service/TransactionQueryService` | filters `paymentLinkId` and `batchId` |
| `transfer/CommercialTransferService` | `maskName` is public |

## Collections added

`commercial_payment_links`, `commercial_account_references`, `commercial_account_prefixes`, `commercial_payout_batches`, `commercial_beneficiary_checks`. `CommercialIndexInitializer` creates their indexes at startup (including the TTL on checks and the sparse unique key on batches).

## Configuration

`commercial.links.checkout-base-url`, `commercial.links.sandbox-checkout-base-url`, `commercial.batches.*` (`items-per-run`, `dispatch-interval-ms`, `dispatcher-enabled`, `stuck-after-minutes`, `max-items`) and `commercial.beneficiary.cache-hours`. All have defaults; `application.yml` and the metadata file list them.

## Verification and limits

Compiled with `javac` against hand written stubs of Spring, Mongo and your own wallet classes (Maven Central is not reachable from the build environment), and 47 new unit tests pass alongside the earlier 136 (183 in all) in a small test runner. The tests cover the pure logic: link rules and views, name matching, destination keys and match results, sandbox lookups, batch rules and views, configuration defaults. Not run: a real Maven build, a real MongoDB, or Safaricom. In particular the following are written but unexercised against a database: the grouped counts and paged item query, the sparse unique batch key, the guarded claim and release updates of the dispatcher, and the Spring wiring of the new beans. Run the application once against a development database and try the sandbox before relying on it.

## Not in this stage

Live account references (needs stage 6), live name lookups other than Premisave wallets (need a provider lookup service or the stage 5 rails), and a `GET` for account references (the draft has none).
