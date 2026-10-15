# Commercial API, Phase 2 stage 4: subscriptions

Plans, customers, saved payment methods, subscriptions and invoices (index rows 27 to 43 of `COMMERCIAL_API_ENDPOINTS.md`), plus the sandbox helpers 13.4. Everything is under `/internal/commercial/v1/{env:live|sandbox}` and uses the same envelope, headers, pagination and error codes as the earlier stages.

## What was built

| Area | Routes |
|:-|:-|
| Plans | `POST /plans`, `GET /plans`, `GET /plans/{planId}`, `PUT /plans/{planId}` |
| Customers | `POST /customers`, `GET /customers`, `GET /customers/{customerId}`, `POST /customers/{customerId}/payment-methods` |
| Subscriptions | `POST /subscriptions`, `GET /subscriptions`, `GET /subscriptions/{id}`, `PUT /subscriptions/{id}`, `POST /subscriptions/{id}/pause`, `/resume`, `/cancel` |
| Invoices | `GET /subscriptions/{id}/invoices`, `POST /invoices/{id}/retry` |
| Sandbox only | `POST /subscriptions/{id}/advance-clock`, `POST /customers/{customerId}/payment-methods/{paymentMethodId}/authorise` |

`POST /subscriptions` and `POST /invoices/{id}/retry` honour `Idempotency-Key`. List endpoints take `page`, `size` and `sort`. Filters: subscriptions `state`, `customerId`, `planId`, `from`, `to`; plans `status`; customers `email`, `phone`, `merchantReference`; invoices `status`.

## How billing works

* Billing is in advance. Period `k` starts at `anchor + k` intervals, in UTC, and a day of month that does not exist in a shorter month falls on that month's last day (a subscription anchored on the 31st bills on the 28th or 29th of February and returns to the 31st in March).
* A subscription copies the plan's price terms when it is created (`Terms`). Changing a plan later never changes what an existing subscription pays. `PUT /plans/{planId}` can rename, archive, and change allowed methods, retry schedule, exhaustion behaviour and metadata, but amount, currency, interval, interval count, trial days and setup fee are refused with `409 INVALID_STATE` once set (sending the current value is accepted).
* Amount charged = plan amount x `quantity`, in the plan currency (USD or KES, the currencies the commercial API supports). The setup fee is added to the first invoice only.
* A trial gives a first period with nothing to pay; the first invoice is raised when the trial ends. `trialDays` on the request overrides the plan (0 turns the trial off).
* No proration. `PUT /subscriptions/{id}` with a new `planId` is held as a pending change and takes effect at the next billing date, when the subscription is re-anchored on the new plan's terms. A quantity change applies from the next invoice.
* A customer can hold one live subscription (any state other than `CANCELLED` or `EXPIRED`) per plan; a second returns `409 DUPLICATE_REQUEST`.

### Subscription states

`PENDING_AUTHORISATION` (waiting for the customer to authorise the saved method), `TRIALING`, `ACTIVE`, `PAST_DUE` (latest invoice unpaid, still being retried), `PAUSED`, `CANCELLED`, `EXPIRED` (retries exhausted and the plan's `onExhausted` is `EXPIRE`).

### A billing cycle

1. The scheduler finds subscriptions whose period has ended and creates the invoice. The invoice is unique on subscription and billing period, so running the sweep twice cannot create two.
2. The subscription is advanced to the next period (guarded on its version).
3. A collection attempt creates a normal payment with `Idempotency-Key` `{invoiceId}_a{attempt}` and relations `invoiceId`, `attempt` and `subscriptionId`. It goes through the same rail selection as `POST /payments`, with the subscription's `callbackUrl` and registered callbacks both notified.
4. When the payment reaches a final status, `InvoicePaymentListener` settles the invoice. Settlement is idempotent, and the recovery sweep re-delivers it for any payment that finished while the service was down.

### Retries and dunning

* A provider outage (`PROVIDER_ERROR`) is not counted against the customer. It is retried after 15 minutes, up to 4 times in a row; after that it counts as a failure.
* Counted failures follow the plan's `retryScheduleDays` (default `[1, 3, 5]`, days after the due date), with at least one hour between attempts. The subscription becomes `PAST_DUE` on the first counted failure and returns to `ACTIVE` when an invoice is paid.
* When the schedule is used up the invoice becomes `PAYMENT_FAILED` and the subscription is `CANCELLED` or `EXPIRED` according to `onExhausted`.
* `POST /invoices/{id}/retry` collects now, optionally on another `paymentMethodId`. It is refused unless the subscription is `TRIALING`, `ACTIVE` or `PAST_DUE`, and while a payment for the invoice is still in flight.
* An invoice that was voided (cancel before collection) and then paid by a payment that was already in flight ends as `PAID`; money received is never hidden.

### Invoice statuses

`OPEN`, `AWAITING_CUSTOMER` (the customer must act, for example approve an M-Pesa prompt), `PAID`, `PAYMENT_FAILED`, `VOID` (only an `OPEN` invoice with no attempt in flight is voided).

### Pause, resume, cancel

* `pause` works on `ACTIVE`, `TRIALING` and `PAST_DUE`. `resumeAt` schedules the resume; the scheduler resumes it. Billing dates that fall during the pause are skipped, not back-billed.
* `resume` carries on from the next billing boundary; boundaries missed while paused are skipped and the customer is not billed for the paused time. With `billNow: true` a new period starts now and is charged in full. A subscription that still has an unpaid invoice must retry that invoice instead.
* `cancel` is immediate by default. With `atPeriodEnd: true` the subscription stays billable until the paid period ends and then becomes `CANCELLED` without raising another invoice.
* A merchant environment cannot be closed while it has live subscriptions.

## Payment methods

`POST /customers/{customerId}/payment-methods` saves a credential for repeated billing.

| Type | Needs | Live | Sandbox |
|:-|:-|:-|:-|
| `MOBILE_MONEY` | `phoneNumber` | Saved at once. Each cycle sends an STK prompt to the customer (`AWAITING_CUSTOMER` until approved). | Same |
| `CARD`, `PAYPAL` | customer email, `returnUrl` | `422 METHOD_UNAVAILABLE` until Stage 5 | Returns a `nextAction`; call the sandbox `authorise` helper to finish |
| `PREMISAVE_WALLET` | `accountRef` | `422 METHOD_UNAVAILABLE` until Stage 5 | Saved after `authorise` |

A method is `PENDING_AUTHORISATION`, `ACTIVE`, `FAILED` or `REMOVED`, and `customer.payment_method.activated` is raised when it becomes active. A subscription created on a method that is still `PENDING_AUTHORISATION` waits in `PENDING_AUTHORISATION` and starts billing (`subscription.activated`) when the method activates.

## Events

`subscription.created`, `subscription.activated` (billing started, resumed or recovered), `subscription.past_due`, `subscription.paused`, `subscription.cancelled` (also used for `EXPIRED`; `data.state` tells them apart), `customer.payment_method.activated`, `invoice.upcoming` (sent `commercial.billing.upcoming-days` before a billing date), `invoice.paid`, `invoice.payment_failed`. Event ids are derived from the object and its state, so a re-run of a sweep never sends a second copy.

## Sandbox helpers (13.4)

* `POST /sandbox/subscriptions/{id}/advance-clock` with `{"to": "2026-12-01T00:00:00Z"}` moves the subscription's own clock forward and runs the real billing code for every period boundary passed. At most 24 steps and one year ahead per call. It waits up to `commercial.billing.clock-wait-ms` for each payment to settle and, if one is still pending, stops early and says so in `note`; call it again to continue. The clock is stored on the subscription, so other subscriptions and the scheduler are unaffected.
* `POST /sandbox/customers/{customerId}/payment-methods/{paymentMethodId}/authorise` with `{"outcome": "SUCCESS"}` or `{"outcome": "FAILED"}`.
* Sandbox collections resolve by the last two digits of the invoice amount: `.01` insufficient funds, `.02` cancelled, `.03` timeout, `.04` declined, `.05` stays pending, anything else succeeds.
* `POST /sandbox/reset` now also removes subscriptions and invoices, and plans and customers (with their saved methods) unless `keep` lists `plans` or `customers`.

## Configuration

```
commercial:
  billing:
    scheduler-enabled: true     # false to run no billing on this instance
    scheduler-interval-ms: 60000
    upcoming-days: 3            # 1 to 30
    stuck-minutes: 10           # at least 6; must exceed the 5 minute idempotency hold
    batch-size: 100             # 1 to 1000 per step per run
    clock-wait-ms: 3000         # 0 to 30000, sandbox advance-clock only
```

Run the scheduler on one instance, or accept that several will run it: every step is guarded (unique invoice per period, version checks, idempotent payment keys), so a second instance repeats no charge, it only duplicates work.

## Persistence

New collections: `billing_plans`, `billing_customers`, `customer_payment_methods`, `billing_subscriptions`, `billing_invoices`. Indexes are created at startup by `CommercialIndexInitializer`, including the unique and sparse ones (`activeKey` on subscriptions, `subscriptionId + billingPeriod` on invoices). The payments collection gets one new sparse index on `relations.invoiceId` and `relations.attempt`.

## Changes to earlier files

* `CommercialPaymentService`: `create` delegates to the new `createWithRelations`, which lets billing attach invoice relations before the payment is stored. Behaviour of `POST /payments` is unchanged.
* `SandboxRail`: a collection on a saved credential no longer asks the customer to act (except mobile money).
* `CommercialTransaction`: the new index.
* `EventTypes`: nine new constants.
* `SandboxService.reset`, `MerchantService.close`, `CommercialSandboxController`: as described above.

## Limits you should know about

* Not built or run here: there is no Maven and no MongoDB in this environment. The code compiles with javac against stubs and the 298 pure-logic unit tests pass. The orchestration (`BillingEngine`, `SubscriptionService`, `SubscriptionTransitions`) has no automated tests because it needs a database; exercise it in the sandbox with `advance-clock` before relying on it.
* Live cards, PayPal and Premisave wallet billing need the rails of Stage 5. Live recurring billing today is M-Pesa only, with a prompt to the customer each cycle.
* Cancelling while an invoice is mid-collection can still let that one payment succeed (it is recorded as `PAID`, not refunded automatically).
* A plan switch that is pending when a paused subscription resumes continues on the old plan for one interval before the new plan applies.
* No proration, coupons, taxes or usage-based pricing.
