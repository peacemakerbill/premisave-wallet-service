# Commercial API, Phase 2 stage 3: refunds and disputes

Everything here follows `COMMERCIAL_API_ENDPOINTS.md` sections 2.15, 2.16 and 9. Where the build differs from the draft, the "as built" notes in that file's Implementation status section say so.

## What it adds

| Index rows | Endpoint | What it does |
|:-|:-|:-|
| 44 | `POST /refunds` | Refunds a successful payment in full or in part |
| 45 | `GET /refunds` | Lists refunds (filters: status, paymentId, merchantReference, from, to) |
| 46 | `GET /refunds/{id}` | One refund, with `result` when final |
| 47 | `GET /disputes` | Lists disputes (filters: status, paymentId, from, to) |
| 48 | `GET /disputes/{id}` | One dispute with its evidence |
| 49 | `POST /disputes/{id}/evidence` | Saves a draft or submits evidence to the provider |
| 13.3 | `POST /disputes` (sandbox) | Opens a test dispute on a successful sandbox payment |
| extra | `POST /disputes/{id}/resolve` (sandbox) | Decides a test dispute: `WON` or `LOST` |
| extra | `POST /refunds/{id}/simulate` (sandbox) | Same helper the payments and payouts have |

`GET /payments/{id}` now shows `refundedAmount`, as the draft always had it.

## Refunds

A refund is a transaction of type `REFUND`. It reuses the hold, capture or release path that payouts, settlements and transfers use (`TxType.usesHold()` now includes `REFUND`), so it appears in `GET /transactions`, the statement, the reports and the reconciliation report without any change to them.

Order of work in `POST /refunds`:

1. The payment must exist for the merchant (`404`), be `SUCCESS` and not already fully refunded (`409 INVALID_STATE`), and have no dispute in progress (`409 INVALID_STATE`; refunding a payment the customer is also disputing would pay twice).
2. The method is chosen from the original rail and payment method (below). Methods with no way back return `422 METHOD_UNAVAILABLE`.
3. The amount is worked out. `amount` is optional and means everything still refundable. When it is given, `currency` is required and may be `USD` or the currency the customer paid in. A refund in the payment's own currency keeps that figure exactly, and the USD side follows from the payment's own proportion (the customer gets back at the rate they paid at, not today's). The last refund of a payment always takes exactly the USD that is left, so rounding never leaves a cent behind or asks for one more. More than is left is `422 LIMIT_EXCEEDED` with `data.refundable`.
4. Everything that can refuse the request is checked before money moves: the customer's phone number or wallet reference on the payment, the rail for the payback, the callback address.
5. The refundable balance is reserved on the payment.
6. The amount is held on the merchant balance (`422 INSUFFICIENT_BALANCE` if it is not there; the reservation is given back).
7. The refund is sent, and the response is the refund as it stands.

### Method

| Original payment | Rail can refund itself | Method |
|:-|:-|:-|
| any | yes (refund) | `PROVIDER_REFUND` |
| any | yes (reversal) | `PROVIDER_REVERSAL` |
| Mobile money | no | `PAYOUT_TO_CUSTOMER`: a payout to the phone number on the payment, through the payout rail for that currency |
| Premisave wallet | no | `WALLET_CREDIT`: the customer's wallet is credited (live), at once |
| Card, PayPal, bank transfer, crypto | no | none: `422 METHOD_UNAVAILABLE` |

`PaymentRail` gained three default methods: `refundMode(payment)` (NONE, REFUND or REVERSAL), `refund(command)` and `submitDisputeEvidence(command)`. A rail that does not override them behaves as before. The sandbox rail refunds cards and PayPal itself. The M-Pesa rail does not override them: M-Pesa payments are refunded by paying the customer's phone, which is the `PAYOUT_TO_CUSTOMER` method. The stage 5 card and PayPal rails will override them and then `PROVIDER_REFUND` goes live with no other change. `HeldDebit` gained `send(call, tx)` so a provider refund gets the same failure handling a payout does (a rail that is unreachable fails the refund, releases the money and returns `503 PROVIDER_UNAVAILABLE`).

### No fee

A refund carries no fee (`fee` is 0.00) and the fee charged on the original payment is not returned. This is the simplest rule and the one most providers follow; if refunds should return the fee, or a refund payout should carry a payout fee, it is a small change in `RefundService`.

### Over-refund protection

Two refunds arriving together must not both take the last of a payment. Each refund first reserves its amount on the payment with one guarded update (add this much, but only if the total stays within the payment), so the database decides, not the application. The reserved total is the field `refundedCents` on the payment and what `refundedAmount` shows (refunds that have not failed).

A refund that ends without paying the customer (failed, timed out, cancelled or reversed by the provider) gives its reservation back. That is done by `RefundReservationListener`, called when any transaction finishes, and made safe to repeat by a flag on the refund document that only one caller can flip. If the process stops between flipping the flag and returning the amount, the payment is refundable for slightly less than it should be, never for more. `RefundReservations.repair` fixes that from the refunds themselves, and only when the payment has seen no reservation for five minutes, so a refund that has reserved but is not yet recorded is never missed. It runs when a refund is refused for lack of refundable balance.

### Wallet credit and recovery

`WALLET_CREDIT` mirrors transfers: credit, then record the result, with the credit applied once even if repeated (`PremisaveWalletCredit`). A sweep every 30 seconds (`commercial.refunds.recovery-interval-ms`) finishes any such refund that was held but not completed. In the sandbox no wallet is touched and the refund succeeds at once.

### Approval

A refund above `commercial.refunds.approval-threshold-usd` (1000 by default) carries `requiresApproval: true`. The draft leaves the approval workflow open, so the flag is informational and the refund proceeds. The threshold is one setting for the whole service, not per merchant.

### Events

`refund.succeeded` and `refund.failed` (also for a refund the provider later reverses), raised through the normal outbox with the refund as `data`. `EventTypes.forTransaction` and `EventService` map the new type. The refund's own `callbackUrl` replaces the registered addresses, as on payments and payouts.

## Disputes

Providers raise and decide disputes, so there is no merchant call to open one. `DisputeService.open(payment, amount, reason, providerDisputeId, evidenceDueBy)` and `resolve(disputeId, won)` are the two methods a rail calls when its provider says so (stage 5 adds those callers). A provider's own dispute id makes `open` safe to repeat (unique index on rail and provider dispute id).

### Opening

1. The dispute is recorded (`NEEDS_EVIDENCE`).
2. The disputed amount is held on the merchant balance (`holdId`).
3. `dispute.opened` is raised, under an id derived from the dispute, so it exists once.

If the balance cannot cover the hold, the dispute still opens, without a hold, and a warning is logged.

### Deciding

`resolve` moves the dispute from `NEEDS_EVIDENCE` or `UNDER_REVIEW` to `WON` or `LOST` with a guarded update (repeating the same decision returns the dispute unchanged; the opposite decision is `409`). Then:

* `WON`: the hold is released and `dispute.won` is raised.
* `LOST`: the hold is taken and `dispute.lost` is raised. If there was no hold, the amount is taken now if the balance has it; otherwise `unrecovered` shows the amount and an error is logged for finance.

Both steps write their flag last, and `DisputeService.recover` (every 30 seconds, `commercial.disputes.recovery-interval-ms`) finishes any dispute whose opening or settling was interrupted. Every money step and every event is exactly once, so repeating them is safe. Dispute fees (chargeback fees charged by the provider) are not modelled.

### Evidence

`POST /disputes/{id}/evidence`:

* Only while the dispute is `NEEDS_EVIDENCE` and before `evidenceDueBy`; otherwise `409 INVALID_STATE`.
* `type`: upper case words joined by underscores, for example `PROOF_OF_DELIVERY` (any such value; the provider decides what it accepts). `text` up to 5000 characters. Up to 10 `attachments`, each with a `name`, an `https` `url` without credentials, and a media type. At least one of `text` or `attachments`.
* `submit: false` saves a draft and changes nothing else.
* `submit: true` sends this entry together with every earlier draft to the provider in one go, then marks them all submitted and moves the dispute to `UNDER_REVIEW`. If the provider is unreachable nothing is saved and the answer is `503 PROVIDER_UNAVAILABLE`, so the same request can simply be repeated. If the provider refuses the evidence the answer is `409 INVALID_STATE` with its reason.
* Saving is a guarded write against the version the request read, so two requests at once cannot lose each other's drafts (the second gets `409` and rereads).

### Sandbox

`POST /disputes` with `paymentId`, optional `amount` (USD, default the whole payment) and `reason` opens a dispute exactly as a provider would, with a deadline `commercial.disputes.evidence-window-days` (14) away. `POST /disputes/{id}/resolve` with `{"outcome": "WON"}` or `{"outcome": "LOST"}` decides it. Sandbox reset removes disputes. A merchant environment cannot be closed while disputes are open.

## Files

New: `refund/` (RefundMethod, RefundAmounts, RefundReservations, RefundReservationListener, RefundService, RefundViews), `dispute/` (Dispute, DisputeRepository, DisputeRules, DisputeService, DisputeViews), `rail/` (RailRefundMode, RefundCommand, DisputeEvidenceCommand), `config/` (CommercialRefundProperties, CommercialDisputeProperties, CommercialStage3Config), `dto/` (CreateRefundRequest, SubmitEvidenceRequest, CreateTestDisputeRequest, ResolveDisputeRequest), `controller/` (CommercialRefundController, CommercialDisputeController).

Changed: TxType, CommercialTransaction (`refundedCents`, `refundTouchedAt`, an index on `relations.paymentId`), PaymentRail and SandboxRail, HeldDebit, EventTypes, EventService, TransactionQueryService (`paymentId` filter, Refund label), MpesaStkReconciler (watches M-Pesa refunds like payouts), Views (`refundedAmount`; `result` made public), SandboxService, CommercialSandboxController, MerchantService.

## What was and was not verified

Compiled with `javac` against hand written stand-ins for Spring and MongoDB, and 215 unit tests pass (32 new, covering the amount rules, method choice, views, evidence rules, deadline arithmetic and settings defaults). Not run: a Maven build, a real MongoDB, a real Safaricom sandbox. In particular the guarded reservation, the flag that makes the release happen once, the version guarded evidence write, the sparse unique index on provider dispute ids, and the Spring wiring of the new beans (including that the outcome service takes `RefundReservationListener` without a circular dependency) have only been checked by reading, not by running.
