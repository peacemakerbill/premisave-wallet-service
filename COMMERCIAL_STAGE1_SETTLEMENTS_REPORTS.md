# Commercial API, Phase 2 stage 1: settlements, transfers and reports

This stage adds the endpoints that move a merchant's own balance out (settlements and transfers) and the reports that explain it. Everything is new code in new packages; the small changes to existing Phase 1 files are listed at the end.

## What was added

| Endpoint | What it does |
| --- | --- |
| `POST /settlements` | Sends money from the merchant balance to one of the merchant's registered settlement destinations. Amount plus fee is held first. |
| `GET /settlements`, `GET /settlements/{id}` | List and read settlements. |
| `PUT /settlements/schedule` | Saves how the balance should be settled automatically: `MANUAL`, `DAILY`, `WEEKLY` or `THRESHOLD`. |
| `POST /transfers` | Instant transfer from the merchant balance into a Premisave wallet. |
| `GET /reports/daily` | One day of activity, as JSON or CSV. |
| `GET /reports/summary` | A date range grouped by `DAY`, `WEEK`, `MONTH`, `METHOD` or `APP`, with totals. |
| `GET /reports/reconciliation` | Checks the merchant's transactions against the merchant's ledger and lists every disagreement. |

All of them exist under both `/live` and `/sandbox`.

## Settlements

A settlement is a payout whose destination is fixed in advance. The request names a destination by id; it can never carry account details of its own, so a stolen API key cannot redirect money to a new account. The destination's details are turned into the same shape a payout uses and sent over the same rails, so every payout rule (limits, rail choice, hold, capture or release) applies unchanged.

* Amount plus fee is held first. Success captures the hold, any failure releases it.
* Events: `settlement.succeeded` and `settlement.failed`.
* Destinations of type card or crypto are refused. Mobile money, bank, PayPal and Premisave wallet destinations are accepted, and a live settlement succeeds only where a live rail exists for that method (today M-Pesa for Kenyan mobile money).
* A settlement to a Kenyan mobile money destination may be requested in USD. The amount is converted to whole KES, rounded down, and the settlement shows `nativeAmount`, `nativeCurrency` and `fxRate`.

### Schedules

One schedule per merchant and environment. The scheduler checks every minute (`commercial.settlements.scheduler-interval-ms`).

| Mode | When it runs |
| --- | --- |
| `MANUAL` | Never. |
| `DAILY` | Once a day at `runAtUtc` (default 06:00). |
| `WEEKLY` | Once a week on `dayOfWeek` at `runAtUtc`. |
| `THRESHOLD` | Whenever the balance left after `keepReserve` reaches `thresholdAmount`, unless a scheduled settlement is still open. |

The amount is the largest figure for which amount plus fee fits in the balance after `keepReserve`. If it is below `minimumAmount`, that period is skipped.

Each period is claimed with a guarded update before anything is created, so two instances or a restart cannot settle the same period twice. A run that fails (for example the balance moved) is logged and waits for the next period. Scheduled settlements raise events for the app that saved the schedule, and are skipped while the merchant or that app is not active.

## Transfers

A transfer holds amount plus fee, credits the recipient's wallet, then captures the hold. If the recipient does not exist the request fails with `404 NOT_FOUND` before anything is held. If the wallet turns out to be frozen at the moment of the credit, the transfer is created as `FAILED` and the hold is released.

The credit is applied exactly once. Instead of read, add and save, it replaces the balance only if it is still the value that was read, retries if someone changed it first, and records the transaction id on the wallet in the same update. A marker document (`commercial_wallet_credits`) is written as well. A sweep (`commercial.transfers.recovery-interval-ms`) finishes any transfer that stopped between the hold and the credit; repeating the credit is safe.

The recipient sees a normal `TRANSFER` row in their wallet history. The merchant sees the recipient masked: the account email is masked and the name shows only the first letter of each word, so a merchant cannot learn a person's name from their email.

Sandbox transfers never touch a real wallet. Any well formed email is accepted and the transfer succeeds immediately.

## Reports

The database does the adding up: one grouped query returns a few buckets per day however many transactions there were. A transaction counts on the day it reached its final status.

* Volume counts successful transactions only. Failed, timed out and cancelled ones appear in `failed`.
* A payout later reversed by the provider stops counting as volume but keeps its fee (`payouts.reversed` counts them).
* `net` is the change in available balance: collections less fees, minus payouts, refunds, settlements and transfers with their fees.
* Beyond the draft examples: a `transfers` block, `fees` on refunds and settlements, `payouts.reversed`.
* Summary ranges are limited to 366 days.

### Reconciliation

Provider statements are not available to this service, so the report checks the merchant's books against themselves and says so (`providerComparison: NOT_AVAILABLE`). It reports:

| Exception | Meaning |
| --- | --- |
| `LEDGER_MISSING` | A transaction succeeded but the credit or debit never reached the ledger. |
| `AMOUNT_MISMATCH` | A ledger line differs from the transaction's amount or fee. |
| `HOLD_MISSING`, `HOLD_NOT_RELEASED` | An outflow has no hold, or a failed one was not given back. |
| `POSTING_WITHOUT_SUCCESS` | Money was posted for a transaction that did not succeed. |
| `NOT_APPLIED` | A transaction finished but its money step is not recorded as done after 5 minutes. |
| `STUCK_PENDING` | Still open well past its deadline (payments: expiry plus 5 minutes; outflows: 30 minutes). |

Up to 20,000 transactions are examined per request; `truncated` is true beyond that. The range is limited to 31 days.

## Settings

```yaml
commercial:
  indexes:
    ensure: true
  settlements:
    scheduler-enabled: true
    scheduler-interval-ms: 60000
  transfers:
    recovery-interval-ms: 30000
```

Every key has the default shown, so adding them is optional.

## Trying it in the sandbox

1. Register a destination for the sandbox environment with the merchant configuration call (section 3.4), for example a `MOBILE_MONEY` destination with `country` `KE` and a `phoneNumber`.
2. Add test money with the sandbox credit helper (13.5).
3. `POST /sandbox/settlements` with `destinationId`, an amount and an `Idempotency-Key`. The sandbox rail resolves it after about 3 seconds.
4. `GET /sandbox/balance` and `GET /sandbox/reports/daily` should now agree with each other.
5. `GET /sandbox/reports/reconciliation` over the last day should list no exceptions.

## Index creation (affects Phase 1 too)

While checking the indexes for the new collections I found that nothing in the project turns on index creation. Spring Data creates indexes from annotations only when `spring.data.mongodb.auto-index-creation` is true, and the project does not set it. The commercial code relies on unique indexes: the idempotency key, one ledger line per operation, and the callback registration rules. Without them a repeated request is not rejected by the database.

`config/CommercialIndexInitializer.java` creates every index declared on the documents in the commercial package when the service starts. It leaves the rest of the wallet service alone, logs a failure for one document without stopping the others, and does nothing for indexes that already exist. Turn it off with `commercial.indexes.ensure: false` if you create indexes another way. If you already create them yourself or have auto-index-creation on, it changes nothing.

## Changes to existing Phase 1 files

| File | Change |
| --- | --- |
| `enums/TxType.java` | Adds `isDebit()`, `usesHold()` and `label()`. |
| `entity/CommercialTransaction.java` | Adds the `relations` map and an index on completion time for reports. |
| `service/TransactionOutcomeService.java` | The hold, capture, release and reversal steps now apply to settlements and transfers as well as payouts. |
| `service/EventTypes.java` | Maps settlements to `settlement.succeeded` and `settlement.failed`. |
| `service/EventService.java` | Uses the settlement view for settlement events. |
| `service/FeeRevenuePoster.java` | Posts a fee to the company ledger only when the money moved. Before this change a failed or timed out transaction with a fee posted revenue it never earned. Fee entries are named after the transaction type. |
| `service/LimitService.java` | Settlements are checked against the single transaction ceiling only; transfers count toward daily and monthly volume. |
| `service/TransactionQueryService.java` | Not found messages say Settlement or Transfer. |
| `service/SandboxService.java` | Sandbox reset also removes the settlement schedule. |
| `service/CommercialPaymentService.java` | `applyPricing` is now public. |
| `dto/Views.java` | The ledger view shows every debit type with a negative net. |
| `controller/CommercialSettlementController.java` | Comment only. |

## What was and was not checked

* All new code and the changed files compile against stand-ins for Spring and Mongo, and 136 unit tests pass (the arithmetic, schedule rules, destination mapping, report maths, reconciliation rules, masking and CSV). The index initializer follows the documented Spring Data approach but has no test; check the startup log line "Commercial indexes checked for N document types" and look at the indexes in MongoDB.
* There was no Maven build, no real MongoDB and no real Premisave wallet in this environment. The Mongo queries, the grouped report query and the wallet credit have not run against a database. Run the sandbox steps above, and credit a test wallet by transfer in a development database, before using live money.
* The wallet credit writes a field named `commercialCredits` on wallet documents. The existing wallet code saves whole wallet documents and does not know about that field, so a later save drops it; the marker document is the second guard for that case. Both guards only matter if the same transfer is retried after a crash, which is rare.
* The existing wallet code still updates balances by read and save. A save based on an older read can overwrite a balance written by this credit, exactly as it can overwrite any other concurrent change today. That is outside this stage.
