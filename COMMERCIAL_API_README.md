# Premisave Payment Service Provider API (Wallet Service Side)

Design draft for turning `premisave-wallet-service` into a payment service provider (PSP): a platform that lets third-party businesses accept payments and send payouts across many payment methods and providers through one API. Two things are built later and sit in front of it: a **developer service** (a separate microservice that owns developer accounts, apps, API keys and client secrets, and authenticates every public request) and a **developer portal** (the web app where users sign up, create sandbox or production apps and get their keys). This document covers the wallet-service half only.

**Status:** Phase 1 is built (merchant and app provisioning, environment routing, idempotency, the merchant ledger, balance and statement, the event outbox with callback registrations, and a fully simulated sandbox rail). Live rails and every later phase are not built yet; Section 15 marks what is done. Section 16 lists the decisions with a proposed answer for each; items marked confirm need checking outside this repository. The request and response reference is in [COMMERCIAL_API_ENDPOINTS.md](COMMERCIAL_API_ENDPOINTS.md), whose Implementation status section lists the exact endpoints that exist.

## Contents

1. [Summary](#1-summary)
2. [Goals and non-goals](#2-goals-and-non-goals)
3. [Where it fits](#3-where-it-fits)
4. [Core concepts](#4-core-concepts)
5. [Rails and capabilities](#5-rails-and-capabilities)
6. [Request contract](#6-request-contract)
7. [Product areas and endpoints](#7-product-areas-and-endpoints)
8. [Routing and failover](#8-routing-and-failover)
9. [Data model](#9-data-model)
10. [Money rules](#10-money-rules)
11. [Security](#11-security)
12. [Reliability](#12-reliability)
13. [Compliance notes](#13-compliance-notes)
14. [Proposed package layout](#14-proposed-package-layout)
15. [Phased roadmap](#15-phased-roadmap)
16. [Decisions and proposed answers](#16-decisions-and-proposed-answers)

## 1. Summary

The wallet service already integrates five providers and an internal wallet ledger. Today they serve Premisave's own users. The PSP API lets outside businesses ("merchants") use the same capabilities, without each merchant integrating every provider separately.

Terms used throughout, independent of any one provider:

| Term | Meaning | Common abbreviation |
|:-|:-|:-|
| Collection (pay-in) | A customer pays a merchant | C2B |
| Payout to a person | A merchant pays an individual | B2C |
| Payout to a business | A merchant pays another business | B2B |

What merchants get:

- **Accept payments** by mobile money, card, PayPal, bank transfer and crypto, through one unified payment object and one set of status values.
- **Send payouts** to mobile money numbers, bank accounts, PayPal, crypto addresses and other Premisave wallets, singly or in bulk.
- **Verify beneficiaries** (name checks) before money moves, for every payout type and for collection accounts.
- **Recurring subscriptions** that collect over whichever rail the customer can authorise once.
- **Refunds, disputes, settlements, statements, reconciliation, events and a sandbox.**
- **Smart routing:** the platform picks the best available provider for a method, country and currency, with failover.

The wallet service stays the only place that holds provider credentials and moves money. The future developer service owns developer accounts, apps, API keys, client secrets, token issuance, rate limits, hosted checkout pages and outbound webhooks. The wallet service never sees a client secret or API key.

## 2. Goals and non-goals

**Goals**

- Provider-agnostic public contract. A merchant asks for "collect 50 by card" or "pay out 20 to this mobile number" and never needs to know which provider or service class handles it. Amounts are in USD unless the request names another currency.
- Additive only. Nothing in `/wallet`, `/payments`, `/disbursements`, `/admin` or the existing `/internal` endpoints changes behaviour for existing users.
- Reuse existing provider services behind a thin adapter layer instead of duplicating integration logic.
- Every merchant request is idempotent, auditable, limit-checked and attributable to one merchant.
- One stable merchant-facing state model even though providers differ widely in how and when they report results.

**Non-goals for this document**

- The developer service and developer portal (sign-up, apps, API keys, client secrets, token issuance, merchant dashboard, hosted checkout pages, outbound webhook delivery).
- Changing how Premisave's own consumer wallets work.
- Becoming a card issuer or acquirer. Card acceptance goes through Stripe; card data never touches Premisave servers.

## 3. Where it fits

```
  Developers and their customers
        │                                    │
        │ sign up, create apps,              │ HTTPS, client credentials
        │ get keys                           │ or API key
        v                                    v
  ┌──────────────────┐        ┌──────────────────────────────────┐
  │ Developer Portal │ calls  │ Developer Service (future)       │
  │ (future web app) │────────│ developers, apps, API keys,      │
  └──────────────────┘        │ client secrets, tokens, KYC,     │
                              │ rate limits, hosted checkout,    │
                              │ signs and delivers webhooks      │
                              └───────────────┬──────────────────┘
                                              │ HTTPS, internal X-API-Key,
                                              │ X-Merchant-Id, X-App-Id,
                                              │ X-App-Environment, signature
                                              v
  ┌───────────────────────────────────────────────────┐
  │  Wallet Service (this repo)                       │
  │  /internal/commercial/v1/{live|sandbox}/**        │
  │                                                   │
  │  Controllers: payments, payouts, beneficiaries,   │
  │  subscriptions, refunds, settlements, reports     │
  │                                                   │
  │  Orchestration: routing, fees, FX, holds, ledger, │
  │  idempotency, event outbox                        │
  │                                                   │
  │  Rail adapters (thin wrappers over existing code) │
  │  M-Pesa | Stripe | PayPal | Flutterwave |         │
  │  NOWPayments | Premisave wallet                   │
  └──────┬────────────────────────────┬───────────────┘
         │ provider APIs and webhooks │ events (outbox)
         v                            v
      Providers              Developer Service events endpoint
```

**Responsibility split**

| Concern | Developer portal and service (future) | Wallet service (this work) |
|:-|:-|:-|
| Developer sign-up, login, KYC documents, dashboard | owns | not involved |
| Apps (sandbox or production), app settings, webhook URLs | owns | keeps a minimal app registry for attribution and kill switches |
| API keys, client ids, client secrets, rotation, token issuance | **owns** | **never sees them** |
| Authenticating every public request, IP allowlists | **owns** | trusts only the signed internal call |
| Hosted checkout and payment link pages | owns | provides sessions and available methods |
| Public rate limiting | owns | defence in depth only |
| Which methods a merchant may use, limits, fee plan | stores a copy | **source of truth for money rules** |
| Provider credentials, provider calls, provider webhooks | never | **owns** |
| Merchant balance, ledger, fees | read only via API | **owns** |
| Delivering callbacks, signing, retries, delivery logs, ownership checks, webhook secrets | **owns** | emits events with their resolved destination URLs |
| Registry of registered callback URLs and event filters | portal and API front it | **owns the records** |

The wallet service authenticates only the calling service, never the developer. `InternalApiKeyFilter` already guards `/internal/**` with `X-API-Key` and grants `ROLE_INTERNAL_SERVICE`, so the new controllers live under `/internal/commercial/v1`. Section 11 adds request signing on top.

**End-to-end authentication**

1. A developer signs up in the developer portal. The developer service creates the developer account.
2. The developer creates an app in the portal and chooses sandbox or production. The developer service generates the client id and client secret (or API key) for that app and shows the secret once. Production apps are only allowed after the developer passes KYC and go-live approval.
3. The developer's server calls the public API, which is the developer service, with those credentials, for example a client-credentials token request followed by bearer calls, or an API key header. The exact public scheme is the developer service's decision.
4. The developer service validates the credentials, applies rate limits, and resolves the app to its merchant and environment.
5. The developer service calls this service under `/live` or `/sandbox` according to the app's environment, sending the internal API key, `X-Merchant-Id`, `X-App-Id`, `X-App-Environment` and a signature.
6. The wallet service verifies the internal key and signature, checks that `X-App-Environment` matches the path, checks that the merchant and app are active in that environment, and only then executes.

Client secrets and API keys stop at step 4. They never reach this service, its logs or its database. The public URL layout is the developer service's choice, but mirroring this document's paths (for example `https://api.example.com/v1/payments` for production and `https://sandbox.api.example.com/v1/payments` for sandbox) keeps the mapping trivial.

## 4. Core concepts

**Merchant.** The business account behind a developer. The developer service creates it; the wallet service keeps a `MerchantProfile` with status, enabled methods, limits, fee plan and the wallets that hold its money.

**App.** What a developer creates in the portal: a named integration that is either `sandbox` or `production` and owns one set of credentials (held by the developer service). A merchant can have many apps. The wallet service stores only a small registration per app (id, environment, status, optional limits and method restrictions) so it can attribute every transaction to an app and suspend one app without touching the others.

**Merchant wallet.** Every merchant has a wallet that acts as its ledger: collections credit it, payouts and fees debit it. It is the same `Wallet` entity used today, so statements, freezing, manual adjustments and reports keep working. USD remains the ground truth. Native amounts and the FX rate are stored on every transaction.

**Payment method.** What the customer pays with or what the merchant pays out to. Types: `MOBILE_MONEY`, `CARD`, `BANK_TRANSFER`, `PAYPAL`, `CRYPTO`, `PREMISAVE_WALLET`.

**Rail.** A provider integration that can carry one or more method types: M-Pesa, Stripe, PayPal, Flutterwave, NOWPayments, and the internal wallet ledger. Each rail is wrapped by an adapter implementing one common interface (Section 14).

**Payment.** One merchant-facing collection. Creating it returns a `nextAction` telling the caller what must happen next (approve a prompt, open a URL, confirm a card client-side, send crypto to an address). The payment later reaches a final status.

**Payout.** One merchant-facing outbound transfer to a destination of any supported type.

**Commercial transaction.** The merchant-facing record behind payments, payouts, refunds, subscription charges and settlements. It links to the underlying `Deposit`, `Disbursement` or provider operation and carries the merchant's own `merchantReference`.

**Environment.** `live` (production) or `sandbox`. Every endpoint exists in both environments and the environment is part of the URL (Section 6), so a sandbox call can never be mistaken for a live one. Each document carries a `livemode` flag. A merchant has two completely separate ledgers, a live wallet and a sandbox wallet, so test activity can never touch real money. Sandbox uses each provider's test credentials.

**Status model.** One vocabulary for every rail:

| Status | Meaning |
|:-|:-|
| `REQUIRES_ACTION` | Waiting for the customer (approve prompt, 3-D Secure, send crypto) |
| `PENDING` | Accepted by the provider, waiting for the result |
| `SUCCESS` | Money moved and ledger posted |
| `FAILED` | Provider or validation rejected it, no money moved |
| `TIMED_OUT` | No result before the deadline; reconciliation resolves it |
| `CANCELLED` | Cancelled before completion |
| `REVERSED` | Was successful, later refunded, reversed or charged back |

## 5. Rails and capabilities

This table reflects what the codebase already supports (left side of each cell) and what is new work. It is the basis for the adapter layer and the roadmap.

| Rail | Collections | Payouts | Recurring | Refunds | Beneficiary check |
|:-|:-|:-|:-|:-|:-|
| **M-Pesa** (mobile money, KES) | Exists: STK push, Pay Bill C2B. New: merchant routing, QR | Exists: B2C, B2B, Pochi. New: merchant routing, bulk | New: standing orders if available, otherwise STK per cycle | Exists: reversal flow with admin approval | Exists: B2B organisation lookup. B2C: see Section 7.5 |
| **Stripe** (cards, bank payouts) | Exists: charge of a saved card. New: payment intent for anonymous customers, client-side confirmation | Exists: Connect transfer and bank payout | Exists: saved cards. New: off-session billing for merchant customers | New: refunds | Card: new, via card checks. Bank: Connect account details |
| **PayPal** | Exists: orders, capture, vault | Exists: payouts by email | Exists: vault tokens. New: merchant customer vaulting | New: refunds | Email only, no name lookup |
| **Flutterwave** (mobile money and bank, multiple African markets) | Exists: mobile money charge, bank transfer deposits (market-limited) | Exists: mobile money and bank transfers | Exists: saved payment method. New: recurring charge | New: refunds | Beneficiary name is already carried on bank transfers. Account name resolution: to confirm |
| **NOWPayments** (crypto) | Exists: payment creation with fiat pricing, estimates, partial-payment handling | Exists: payouts with two-factor verification | Invoice per cycle, customer pays each time | Manual only | Address format and network validation, no name |
| **Premisave wallet** (internal) | Exists: payment from a wallet balance | Exists: wallet-to-wallet transfer | Direct debit with customer consent | Internal credit back | Exists: name from `UserNameResolver` |

Currency and country coverage is whatever each provider account is enabled for. The adapter exposes this at runtime through a capabilities call (Section 7.2), so merchants and checkout pages only see methods that can actually work for a given country and currency.

## 6. Request contract

**Base paths**

| Scope | Base path | Used for |
|:-|:-|:-|
| Production | `{{base_url_wallet}}/internal/commercial/v1/live` | Real money, real providers |
| Sandbox | `{{base_url_wallet}}/internal/commercial/v1/sandbox` | Test money, provider test credentials, simulation helpers |
| Provisioning | `{{base_url_wallet}}/internal/commercial/v1` | Merchant profiles, which manage both environments (Section 7.1) |

Every operation in Sections 7.2 to 7.10 is available under both `/live` and `/sandbox` with identical request and response shapes, so a merchant switches environments by changing one URL segment and their API key. The sandbox-only helpers in Section 7.11 exist under `/sandbox` only and return `NOT_FOUND` under `/live`.

The path is what selects the environment. The signed `X-App-Environment` header is a cross-check, not a second source: if the two disagree, the request is rejected, so a sandbox app can never reach production data even through a routing mistake.

**Headers on every call**

| Header | Required | Purpose |
|:-|:-|:-|
| `X-API-Key` | yes | Internal service key, validated by `InternalApiKeyFilter` |
| `X-Merchant-Id` | yes | The merchant the call is made on behalf of |
| `X-App-Id` | yes | The app the call came from, used for attribution, app-level limits and app suspension. An identifier only, never a credential |
| `X-App-Environment` | yes | `sandbox` or `production` as asserted by the developer service. Must match the path (`sandbox` with `/sandbox`, `production` with `/live`) or the call is rejected with `ENVIRONMENT_MISMATCH` |
| `Idempotency-Key` | on every money-moving POST | Same key returns the same result; never moves money twice |
| `X-Request-Id` | recommended | Correlation id, echoed in logs and responses |
| `X-Timestamp`, `X-Signature` | yes once signing is enabled | HMAC over method, path, body and timestamp (Section 11) |

**Response envelope.** The shape the service uses everywhere (`ApiResponse`):

```json
{
  "success": true,
  "message": "Payment created",
  "data": { }
}
```

**Errors.** A stable `code` inside `data` so the developer service can map errors without parsing text:

```json
{
  "success": false,
  "message": "Insufficient merchant balance",
  "data": { "code": "INSUFFICIENT_BALANCE", "requestId": "req_8f2c" }
}
```

Planned codes: `VALIDATION_FAILED`, `MERCHANT_NOT_FOUND`, `MERCHANT_SUSPENDED`, `APP_NOT_FOUND`, `APP_SUSPENDED`, `METHOD_NOT_ENABLED`, `METHOD_UNAVAILABLE`, `LIMIT_EXCEEDED`, `INSUFFICIENT_BALANCE`, `DUPLICATE_REQUEST`, `BENEFICIARY_NOT_VERIFIED`, `PROVIDER_UNAVAILABLE`, `INVALID_CALLBACK_URL`, `CALLBACK_REGISTRATION_LIMIT`, `CALLBACK_NOT_VERIFIED`, `LIVE_NOT_ENABLED` (merchant not yet approved for production), `ENVIRONMENT_MISMATCH` (request reached a deployment that does not serve that environment), `NOT_FOUND`.

**Amounts and currency.** Decimal strings, never floating point. **USD is the default currency everywhere:** if `currency` is omitted on a payment, payout, quote, refund, plan or settlement, it is USD, and balances, statements, reports and fee calculations are reported in USD. A request may name another ISO 4217 `currency` (or a crypto ticker where relevant) when the customer or beneficiary needs it, for example a KES mobile money payment; the platform converts at a locked rate and always returns the USD amount alongside (Section 10). A merchant profile carries a `defaultCurrency` that starts as USD, so a different default can be set per merchant later without changing the contract.

**Pagination and filtering.** Standard `page`, `size`, `sort`, plus `status`, `type`, `method`, `from`, `to`, `merchantReference`, using the same MongoTemplate criteria approach already used for the history endpoints.

## 7. Product areas and endpoints

Paths in 7.2 to 7.10 are relative to an environment base path from Section 6 and exist under both `/live` and `/sandbox`. Paths in 7.1 are relative to the provisioning base path. Paths in 7.11 are relative to the sandbox base path only.

Headers, request bodies, response bodies and error codes for every endpoint are in [COMMERCIAL_API_ENDPOINTS.md](COMMERCIAL_API_ENDPOINTS.md). The tables in this section describe what each endpoint is for; that file is the contract.

### 7.1 Merchant and app provisioning (called by the developer service)

The developer service calls these when something changes in the portal: a developer is verified, an app is created or suspended, production access is approved.

| Method | Path | Purpose |
|:-|:-|:-|
| POST | `/merchants` | Create or link a merchant profile. Creates the sandbox wallet immediately; the live wallet is created when production access is approved |
| GET | `/merchants/{merchantId}` | Profile, status per environment, enabled methods, limits |
| PUT | `/merchants/{merchantId}/status` | Per environment (`environment` in the body): activate, suspend, close (kill switch). Production access is switched on here after KYC approval |
| PUT | `/merchants/{merchantId}/config` | Methods, limits, fee plan, display name, settlement destinations |
| POST | `/merchants/{merchantId}/apps` | Register an app: `appId`, `environment`, name, optional method restrictions and limits. A production app is refused until production access is active |
| GET | `/merchants/{merchantId}/apps` | List registered apps with status |
| PUT | `/merchants/{merchantId}/apps/{appId}` | Update restrictions or limits, suspend, reactivate or close an app |

A suspended or closed merchant or app is rejected on every other endpoint in the affected environment. Sandbox is available as soon as the merchant exists; `/live` calls return `LIVE_NOT_ENABLED` until production access is activated.

### 7.2 Capabilities and quotes

| Method | Path | Purpose |
|:-|:-|:-|
| GET | `/payment-methods?country=&currency=&direction=` | Methods currently available to this merchant for collection or payout, with limits and expected settlement time |
| POST | `/quotes` | Price a payment or payout before creating it: fee, FX rate, net amount, chosen rail. Amounts default to USD |

Quotes let a checkout page show the final amount and let merchants display fees to their customers. A quote is valid for a short time and can be referenced when creating the payment so the FX rate is honoured.

### 7.3 Collections (pay-ins)

| Method | Path | Purpose |
|:-|:-|:-|
| POST | `/payments` | Create a payment with any supported method |
| GET | `/payments`, `/payments/{id}` | List and detail, with timeline |
| POST | `/payments/{id}/cancel` | Cancel while still `REQUIRES_ACTION` or `PENDING`, where the rail allows it |
| POST | `/payment-links` | Fixed or open amount, expiry, single or multi use |
| GET / PUT | `/payment-links`, `/payment-links/{id}` | List, edit, deactivate |
| POST | `/payments/account-references` | Reserve a reference customers can pay to from their own mobile money or bank app (pay bill style) |

One endpoint, many methods. The request names the method type; `provider` may be `AUTO` to let routing decide. `currency` is omitted in the example below, so the amount is in USD. For a mobile money method the customer is still charged in their local currency: the platform converts the USD amount at the quoted rate and returns both figures.

```json
{
  "amount": "25.00",
  "merchantReference": "ORDER-1042",
  "description": "Order 1042",
  "customer": { "name": "Jane Doe", "email": "jane@example.com", "phone": "254712345678" },
  "method": { "type": "MOBILE_MONEY", "provider": "AUTO", "phoneNumber": "254712345678" },
  "returnUrl": "https://merchant.example.com/orders/1042",
  "metadata": { "orderId": "1042" }
}
```

The response carries a `nextAction` that depends on the method:

| Method | `nextAction.type` | What the caller does |
|:-|:-|:-|
| Mobile money prompt | `CUSTOMER_PROMPT` | Tell the customer to approve the prompt on their phone |
| Card | `CLIENT_SECRET` | Confirm client-side with the provider's hosted element; card data never reaches Premisave |
| PayPal | `REDIRECT` | Send the customer to the approval URL |
| Bank transfer | `BANK_TRANSFER_DETAILS` | Show account details and the reference to pay with |
| Crypto | `CRYPTO_ADDRESS` | Show address, network and exact amount to send |
| Premisave wallet | `NONE` or `CUSTOMER_CONSENT` | Completes immediately, or asks the wallet owner to approve |

Final status always arrives through an event (Section 7.9); the merchant never has to poll, though `GET /payments/{id}` is always available.

Payment links store the rules only. The hosted page that renders a link belongs to the developer service and creates a normal payment when a customer pays.

### 7.4 Payouts

| Method | Path | Purpose |
|:-|:-|:-|
| POST | `/payouts` | Pay out to any supported destination |
| POST | `/payouts/bulk` | Up to N payouts in one batch, validated up front |
| GET | `/payouts`, `/payouts/{id}` | List and detail |
| GET | `/payouts/batches/{batchId}` | Batch progress and per-item results |

Destination types:

| `destination.type` | Fields | Typical rail |
|:-|:-|:-|
| `MOBILE_MONEY` | `country`, `network`, `phoneNumber` | M-Pesa, Flutterwave |
| `BUSINESS_ACCOUNT` | `shortcode` or `tillNumber`, `accountReference` | M-Pesa business payments |
| `BANK_ACCOUNT` | `country`, `bankCode`, `accountNumber`, `accountName` | Flutterwave, Stripe Connect |
| `PAYPAL` | `email` | PayPal |
| `CRYPTO` | `network`, `address` | NOWPayments |
| `PREMISAVE_WALLET` | `accountNumber` or phone | Internal ledger |

```json
{
  "amount": "20.00",
  "merchantReference": "SALARY-2026-10-001",
  "remarks": "October salary",
  "destination": {
    "type": "MOBILE_MONEY",
    "provider": "AUTO",
    "country": "KE",
    "phoneNumber": "254712345678"
  },
  "beneficiaryCheck": { "required": true, "expectedName": "John Doe" }
}
```

Amounts default to USD. For a destination that only pays out in a local currency (for example mobile money in KES), the platform converts the USD amount at the quoted rate before sending, debits the merchant the USD equivalent plus fee, and returns the native amount sent. A request can instead set `"currency": "KES"` to fix the amount the beneficiary receives, in which case the USD debit is derived from the rate.

Behaviour:

- The commercial layer resolves the merchant wallet, enforces limits, creates a hold, runs the beneficiary check if requested, routes to a rail and delegates to the existing disbursement service for that rail.
- The merchant wallet is debited only when the provider confirms success, matching the current async-debit rule. The hold (Section 10) protects against overspending while results are pending.
- Bulk payouts are validated as a whole before anything is sent (balance for the full batch, per-item format, duplicate references), then processed item by item with a concurrency cap so provider throttling is respected. One failing item never fails the batch.
- Crypto payouts that need a verification code from the account owner stay in `REQUIRES_ACTION` until an operator or the merchant's configured flow supplies it, as the existing NOWPayments flow does today.

### 7.5 Beneficiary verification (name checks)

Verify who will receive money before it is sent, and who a collection account belongs to.

| Method | Path | Purpose |
|:-|:-|:-|
| POST | `/beneficiaries/verify` | Verify a payout destination of any type |
| POST | `/beneficiaries/lookup-account` | Resolve the display name a customer will see for a merchant collection account reference |
| GET | `/beneficiaries/checks/{checkId}` | Retrieve an earlier check |

```json
{
  "destination": { "type": "BANK_ACCOUNT", "country": "NG", "bankCode": "058", "accountNumber": "0123456789" },
  "expectedName": "ACME LIMITED"
}
```

```json
{
  "success": true,
  "message": "Beneficiary check completed",
  "data": {
    "checkId": "bc_91ab",
    "destinationType": "BANK_ACCOUNT",
    "verified": true,
    "registeredName": "ACME LIMITED",
    "match": "EXACT",
    "source": "PROVIDER_ACCOUNT_RESOLVE",
    "cached": false,
    "checkedAt": "2026-10-14T19:05:11Z"
  }
}
```

`match` is `EXACT`, `PARTIAL`, `MISMATCH`, `UNAVAILABLE` or `NOT_REQUESTED`. Name comparison is case-insensitive, strips punctuation and common company suffixes (LTD, LIMITED, CO, INC) and uses a configurable similarity threshold.

**Sources by destination type**

| Destination | Source, in order | Notes |
|:-|:-|:-|
| Mobile money, business account | The mobile money provider's organisation lookup where one exists (already built for M-Pesa business payments) | Cached briefly to avoid repeated provider calls for the same shortcode |
| Mobile money, individual | 1. Premisave wallet directory by phone number. 2. Names learned from earlier successful payouts, since provider results often return the receiver's public name. 3. A provider pre-payment lookup where the provider offers one | Not every provider exposes a pre-payment lookup for individuals; the response says which source answered and how confident it is |
| Bank account | Provider account-resolution API where available for that country | Coverage varies by country and provider; to confirm per market |
| PayPal | Email format and domain checks only | PayPal does not reveal the account holder name before payment |
| Crypto | Address format and network checksum validation | Validates the address, not the owner; the response says so |
| Premisave wallet | `UserNameResolver` | Exact |
| Collection account reference | The merchant's configured display name | Also feeds the existing Hakikisha-style name lookup service through the accounts directory; suspended merchants return "invalid account" |

**Rules**

- Fail closed, like the existing account validation does: if no source can answer, return `UNAVAILABLE`, never `verified: true`.
- Payer names captured on incoming payments are stored as evidence on the payment. They are not treated as a name check, because providers may mask or omit them.
- Every check is stored (who asked, what, result, source) for audit and dispute handling.
- Checks can be billed; the fee plan has a `beneficiaryCheckFee` field.

### 7.6 Recurring subscriptions

A provider-agnostic billing engine on top of the payment primitives.

| Method | Path | Purpose |
|:-|:-|:-|
| POST / GET / PUT | `/plans`, `/plans/{planId}` | Price, interval, trial, retry policy, allowed methods |
| POST / GET | `/customers`, `/customers/{customerId}` | Merchant's customers and their saved payment methods |
| POST | `/customers/{customerId}/payment-methods` | Start saving a method; returns the `nextAction` needed to authorise it once |
| POST | `/subscriptions` | Start a subscription for a customer on a plan |
| GET | `/subscriptions`, `/subscriptions/{id}` | List and detail |
| POST | `/subscriptions/{id}/pause`, `/resume`, `/cancel` | Lifecycle |
| PUT | `/subscriptions/{id}` | Change plan, quantity or payment method |
| GET | `/subscriptions/{id}/invoices` | Billing history |
| POST | `/invoices/{id}/retry` | Manual retry of a failed charge |

**How each cycle collects money.** The customer authorises once; the engine then charges using whichever saved credential the subscription holds:

| Saved credential | Customer action | Notes |
|:-|:-|:-|
| Card | Saves a card once (strong customer authentication when required) | Off-session charge each cycle |
| PayPal vault token | Approves vaulting once | Charged without the customer present |
| Provider saved payment method (mobile money or bank) | Authorises once | Depends on the provider supporting recurring charges in that market |
| Mobile money standing order | Approves a standing order once | Only where the provider offers it; to confirm |
| Premisave wallet | Consents once to debits from their wallet | Internal ledger move, no provider fee |
| Per-cycle prompt or invoice | Approves or pays every cycle | Fallback for rails with no stored credential (mobile money prompt, crypto invoice). The invoice waits in `AWAITING_CUSTOMER` until paid |

**Engine behaviour**

- A scheduler (same `@Scheduled` approach as the exchange-rate refresh) selects subscriptions whose `nextBillingAt` has passed and creates exactly one invoice per `(subscriptionId, billingPeriod)`. A unique index enforces this, so overlapping runs or restarts cannot double-bill.
- Failed charges follow the plan's retry schedule (for example day 1, 3 and 5). The engine can fall back to another saved credential on the same subscription before marking it `PAST_DUE`, and finally `CANCELLED` or `EXPIRED` per plan setting.
- States: `PENDING_AUTHORISATION`, `TRIALING`, `ACTIVE`, `PAST_DUE`, `PAUSED`, `CANCELLED`, `EXPIRED`.
- Every change emits an event, so merchants never poll.
- Proration, coupons, usage-based billing and multi-item subscriptions come after the first cut.

### 7.7 Refunds and disputes

| Method | Path | Purpose |
|:-|:-|:-|
| POST | `/refunds` | Refund all or part of a successful payment |
| GET | `/refunds`, `/refunds/{id}` | Status |
| GET | `/disputes`, `/disputes/{id}` | Chargebacks and disputes raised against the merchant |
| POST | `/disputes/{id}/evidence` | Submit evidence where the provider supports it |

Refund strategy by original rail: use the provider's native refund or reversal where one exists and is still available; otherwise pay the customer back with a payout to the same instrument or a fresh destination, from the merchant wallet. The existing reversal flow needs admin approval; commercial refunds start with the same rule and can become automatic below a per-merchant threshold later.

Disputes arrive from provider webhooks (card chargebacks first). A dispute places a hold on the disputed amount and emits `dispute.opened`.

### 7.8 Balances, settlements and treasury

| Method | Path | Purpose |
|:-|:-|:-|
| GET | `/balance` | Available, pending (holds), total, in USD and native currencies |
| GET | `/statement` | Paged ledger lines with running balance |
| GET | `/transactions`, `/transactions/{id}` | Every commercial transaction with filters and timeline |
| POST | `/settlements` | Move funds from the merchant wallet to the merchant's own registered destination |
| GET | `/settlements`, `/settlements/{id}` | History |
| PUT | `/settlements/schedule` | Automatic daily, weekly or threshold-based settlement |
| POST | `/transfers` | Merchant wallet to another Premisave wallet (marketplace payouts, internal movements) |

Settlements are payouts to pre-registered settlement destinations only, so a leaked API key cannot redirect funds to a new account. Changing a destination goes through the developer portal's verified flow, not this API.

### 7.9 Events and callback URLs

Merchants learn the outcome of a transaction through callbacks: an HTTPS POST to a URL they control. There are two separate callback layers and they must not be confused:

| Layer | Direction | Owner | Changes? |
|:-|:-|:-|:-|
| Provider callbacks | Provider (M-Pesa, Stripe, PayPal, Flutterwave, NOWPayments) to Premisave | Existing endpoints under `/payments/**` | No. They keep working exactly as they do now |
| Merchant callbacks | Premisave to the merchant's server | Wallet service creates the event, developer service delivers it | New, described here |

**Two ways to give Premisave a callback URL.** Merchants choose either or both:

| Way | How | Best for |
|:-|:-|:-|
| **Per request** | Send `callbackUrl` on the operation | A different destination per order, per customer or per environment, and merchants that prefer not to register anything |
| **Registered** | Register one or more URLs once against the app, each with the event types it wants | A standing webhook endpoint that receives every payment, payout or subscription event without repeating the URL on each call |

Every operation that can finish later accepts an optional `callbackUrl` (and optional `notifyRegistered`): `POST /payments`, `/payouts`, `/payouts/bulk` (batch level, with an optional override per item), `/refunds`, `/settlements`, `/subscriptions`, `/payment-links` and `/beneficiaries/verify`.

**Which URLs receive an event.** The wallet service resolves the destinations when it creates the event and stores them with it, so the developer service only has to deliver:

1. If the transaction has a `callbackUrl`, the event goes to that URL. This replaces registered URLs for that transaction, so "send this result here" always means exactly here. Set `"notifyRegistered": true` to also notify registered URLs.
2. Otherwise the event goes to every **active** registration on the app whose event types match, in the same environment.
3. If there is neither, no callback is sent. The result is still available through `GET` and the event list below.

A payment created from a payment link uses the link's `callbackUrl`. Subscription lifecycle and invoice events use the subscription's `callbackUrl`. The same URL appearing twice (per request and registered) is delivered once.

```json
{
  "amount": "25.00",
  "merchantReference": "ORDER-1042",
  "method": { "type": "MOBILE_MONEY", "provider": "AUTO", "phoneNumber": "254712345678" },
  "callbackUrl": "https://merchant.example.com/hooks/premisave",
  "notifyRegistered": false,
  "metadata": { "orderId": "1042" }
}
```

**Registering callback URLs.** Registrations belong to an app and an environment: a sandbox registration only ever receives sandbox events. Both the developer portal and the public API can manage them; either way they end up as the endpoints below.

| Method | Path | Purpose |
|:-|:-|:-|
| POST | `/callbacks/registrations` | Register a URL |
| GET | `/callbacks/registrations` | List the app's registrations with status |
| GET | `/callbacks/registrations/{id}` | One registration |
| PUT | `/callbacks/registrations/{id}` | Change the URL, event types or description, or disable and re-enable it |
| DELETE | `/callbacks/registrations/{id}` | Remove it |
| POST | `/callbacks/registrations/{id}/verify` | Start (or repeat) the ownership check |
| POST | `/callbacks/registrations/{id}/test` | Send a `callback.test` event to this registration only |
| PUT | `/callbacks/registrations/{id}/status` | Called by the developer service to report verification passed, or that a failing URL was suspended |

```json
{
  "type": "EVENT",
  "url": "https://merchant.example.com/hooks/premisave",
  "eventTypes": ["payment.*", "payout.succeeded", "payout.failed"],
  "description": "Production order system"
}
```

`eventTypes` accepts exact names, prefix wildcards such as `payment.*`, or `*` for everything. The same URL rules apply as for `callbackUrl` (HTTPS, no embedded credentials, length limit, hostname in live). An app can hold a limited number of registrations (Section 16, item 17), and the same URL cannot be registered twice on one app.

**Registration lifecycle**

| Status | Meaning |
|:-|:-|
| `PENDING_VERIFICATION` | Live only. Waiting for the ownership check; receives no events yet |
| `ACTIVE` | Receives matching events. Sandbox registrations start here |
| `DISABLED` | Switched off by the merchant |
| `SUSPENDED` | Switched off automatically after sustained delivery failure. The merchant fixes the endpoint and re-enables it; nothing is lost because missed events stay available through `GET /events` and can be resent |

**Ownership check (live).** When a live URL is registered or its URL changes, the developer service sends it a `callback.verification` event containing a one-time challenge. The merchant's endpoint must answer 2xx and echo the challenge in the response body. On success the developer service reports `ACTIVE` through the status endpoint above. This stops anyone from pointing events, which contain customer details, at a server they do not control.

**Validation callbacks (synchronous, optional).** A second registration type, `VALIDATION`, is for pay-in methods where the provider asks the receiving business to accept or reject a payment before it completes, such as mobile money pay bill style payments. The provider only waits a few seconds, so the exchange is a single request and answer:

```json
{
  "type": "payment.validation",
  "validationId": "val_55c2",
  "accountReference": "PS1234-INV001",
  "amount": "25.00",
  "currency": "USD",
  "nativeAmount": "3225.00",
  "nativeCurrency": "KES",
  "customer": { "phone": "2547****5678" },
  "method": { "type": "MOBILE_MONEY", "provider": "MPESA" },
  "deadlineMs": 3000
}
```

The merchant answers `{"result": "ACCEPT"}` or `{"result": "REJECT", "reason": "Invoice already paid"}` within the deadline. Each validation registration carries an `onTimeout` setting, `REJECT` by default (safe: the customer's payment simply does not go through) or `ACCEPT`. Validation is only offered on rails that have a pre-completion hook; on others the registration is accepted but never called, and `GET /payment-methods` says which methods support it. Because it is synchronous it is not retried, is not signed differently from other callbacks, and each outcome is also written as an event so it can be reviewed.

**What a callback contains.** One stable shape for every rail, so merchants write one handler:

```json
{
  "eventId": "evt_7c1d9a",
  "type": "payment.succeeded",
  "createdAt": "2026-10-14T19:22:41Z",
  "livemode": true,
  "merchantId": "mer_4821",
  "appId": "app_93f0",
  "data": {
    "id": "pay_81ae",
    "merchantReference": "ORDER-1042",
    "status": "SUCCESS",
    "result": {
      "code": "SUCCESS",
      "description": "Payment completed",
      "providerCode": "0",
      "providerDescription": "The service request is processed successfully."
    },
    "amount": "25.00",
    "currency": "USD",
    "nativeAmount": "3225.00",
    "nativeCurrency": "KES",
    "fxRate": "129.00",
    "fee": "0.50",
    "method": { "type": "MOBILE_MONEY", "provider": "MPESA" },
    "customer": { "name": "Jane Doe", "phone": "2547****5678" },
    "providerReference": "RCA71X5MJ4",
    "metadata": { "orderId": "1042" },
    "version": 3,
    "updatedAt": "2026-10-14T19:22:41Z"
  }
}
```

`result.code` is Premisave's normalised outcome, the same for every rail. `providerCode` and `providerDescription` carry the provider's raw answer for debugging. `version` increases on every state change of the transaction so a merchant can discard a late, out-of-order callback.

**Normalised result codes**

| `result.code` | Meaning |
|:-|:-|
| `SUCCESS` | Completed |
| `CUSTOMER_CANCELLED` | The customer cancelled or dismissed the prompt |
| `CUSTOMER_TIMEOUT` | The customer did not act in time |
| `INSUFFICIENT_FUNDS` | The payer's balance was too low |
| `DECLINED` | Declined by the payer's bank, card issuer or wallet |
| `INVALID_DESTINATION` | The payout account, number or address is not valid |
| `BENEFICIARY_MISMATCH` | The beneficiary check failed or the name did not match |
| `LIMIT_EXCEEDED` | A merchant, customer or provider limit was hit |
| `PROVIDER_ERROR` | The provider failed or rejected the request |
| `TIMED_OUT` | No final answer before the deadline; reconciliation will resolve it |
| `REVERSED` | Later refunded, reversed or charged back |

**Events**

| Event | Fires when |
|:-|:-|
| `payment.requires_action`, `payment.pending`, `payment.succeeded`, `payment.failed`, `payment.timed_out`, `payment.cancelled` | Collection lifecycle |
| `payout.pending`, `payout.succeeded`, `payout.failed`, `payout.timed_out`, `payout.requires_action` | Payout lifecycle |
| `payout.batch.completed` | Last item of a bulk batch settles |
| `refund.succeeded`, `refund.failed` | Refund lifecycle |
| `dispute.opened`, `dispute.won`, `dispute.lost` | Chargebacks |
| `subscription.created`, `.activated`, `.past_due`, `.paused`, `.cancelled` | Subscription lifecycle |
| `invoice.upcoming`, `invoice.paid`, `invoice.payment_failed` | Billing cycle |
| `beneficiary_check.completed` | Asynchronous checks, if any |
| `settlement.succeeded`, `settlement.failed` | Settlements |
| `balance.low` | Balance below the merchant's configured threshold |

**How an event travels.** The wallet service writes the event to an outbox collection in the same transaction as the state change, together with its resolved destinations (the `targets` list, empty if there are none). A scheduled forwarder pushes pending events to the developer service (`POST /internal/events` on the developer service) with retries. The developer service delivers to each target, signing the payload with the app's webhook secret. Webhook secrets live only in the developer service.

**Delivery rules (developer service)**

- **At-least-once.** A merchant may receive the same event more than once and must dedupe on `eventId`.
- **Success means HTTP 2xx within 10 seconds.** Anything else, including a timeout, is retried with exponential backoff and jitter over a bounded window (Section 16, item 16), then marked failed. A failed delivery never changes the transaction.
- **Order is not guaranteed.** Use `version` and `updatedAt` to ignore stale callbacks, or fetch the object by id.
- **Signature headers** on every callback: `X-Premisave-Event-Id`, `X-Premisave-Timestamp` and `X-Premisave-Signature` (HMAC of timestamp and raw body). Merchants verify the signature and reject old timestamps.
- **Network safety.** The developer service resolves the hostname at delivery time and refuses private, loopback, link-local and cloud-metadata addresses, does not follow redirects, caps request and response size, and re-checks DNS on every attempt so a hostname cannot be swapped for an internal address after validation. An optional per-app domain allowlist can restrict destinations further.
- **No secrets in the payload.** Phone numbers are masked, and no card data, account secrets or internal ids beyond those shown are included.
- **Delivery logs live in the developer service**, which can show merchants each attempt, status code and response time in the portal. The wallet service does not track individual delivery attempts. It only learns two things from the developer service, through the registration status endpoint: that a URL passed verification, and that a URL was suspended for sustained failure.

**Event endpoints on this service** (exist under both `/live` and `/sandbox`)

| Method | Path | Purpose |
|:-|:-|:-|
| GET | `/events` | List events for the merchant, filterable by `type`, transaction id, `merchantReference` and date. Lets merchants and the developer service recover anything missed |
| GET | `/events/{eventId}` | One event with the exact payload that was sent |
| POST | `/events/{eventId}/resend` | Re-queue an event to the developer service for delivery again, for example after a merchant outage. The `eventId` stays the same so merchant dedupe still works |

### 7.10 Reports and reconciliation

| Method | Path | Purpose |
|:-|:-|:-|
| GET | `/reports/daily` | Collections, payouts, fees, refunds, net, by day and method |
| GET | `/reports/summary` | Totals for a date range |
| GET | `/reports/reconciliation` | Transactions that disagree with the provider or are still pending past their deadline |

The existing reconciliation sweeps extend naturally: stuck commercial transactions are flagged, queried against the provider's status API, and resolved or escalated to the admin reconciliation screens that already exist. The existing gateway-balance snapshots can also compare total merchant balances to real provider balances.

### 7.11 Sandbox

Sandbox is a full parallel environment, not a flag. Every operation in 7.2 to 7.10 works under `/sandbox` exactly as under `/live`, against a separate sandbox wallet, separate sandbox data (`livemode: false`) and each provider's test credentials. Nothing in sandbox can reach a live balance or a real provider account.

Sandbox-only helpers let merchants exercise every branch without real money or real phones:

| Method | Path | Purpose |
|:-|:-|:-|
| POST | `/payments/{id}/simulate` | Force a payment to `approve`, `decline`, `timeout` or `fail` as if the customer or provider had acted |
| POST | `/payouts/{id}/simulate` | Force a payout to `succeed`, `fail` or `timeout` |
| POST | `/disputes` | Open a test chargeback against a sandbox payment |
| POST | `/subscriptions/{id}/advance-clock` | Move the billing clock forward to trigger renewal, retry and dunning steps now |
| POST | `/balance/credit` | Add test funds to the sandbox wallet |
| POST | `/events/test` | Send a sample event of a chosen type through the real callback path, so merchants can test their handler and signature check |
| POST | `/reset` | Clear all sandbox data for the merchant |

Simulated results flow through the same code as real provider callbacks, including fees, holds, ledger lines and events, so what a merchant tests is what production does. Sandbox events carry `livemode: false`.

### 7.12 Endpoint summary

| Group | Count |
|:-|:-|
| Provisioning: merchants 4, apps 3 (not environment-scoped) | 7 |
| Environment-scoped operations (7.2 to 7.10) | 65 |
| Those operations exposed under `/live` and `/sandbox` | 65 x 2 = 130 |
| Sandbox-only helpers | 7 |
| **Total routes** | **144** |

The 65 operations are 65 methods in the controllers, each mapped to both environment prefixes, so the code is written once.

The full numbered list with section references is the endpoint index (Section 14) in [COMMERCIAL_API_ENDPOINTS.md](COMMERCIAL_API_ENDPOINTS.md).

## 8. Routing and failover

When `provider` is `AUTO`, a routing step chooses the rail.

**Inputs:** method type, country, currency, amount, merchant preference list, merchant fee plan, rail capability and limits, and recent rail health.

**Rules, in order:**

1. Drop rails that do not support the method, country, currency or amount.
2. Drop rails the merchant has not enabled, and rails currently tripped by the health check.
3. Prefer the merchant's configured priority, then lowest total cost, then fastest expected settlement.
4. Pick the first remaining rail.

**Health check:** a simple circuit breaker per rail and method, based on recent failure rate and timeouts. An open breaker removes the rail from step 2 until a probe succeeds.

**Failover boundaries:**

- Before a request has been accepted by any provider, routing may choose an alternative rail.
- After any provider has accepted a payout, it is never re-sent on another rail automatically. An ambiguous outcome goes to reconciliation, not to a retry elsewhere, so a payout is never paid twice.
- For collections, an alternative is offered to the customer as another method; the platform does not silently charge a second rail.

## 9. Data model

All new collections are additive. Existing entities gain only optional fields.

**New collections**

| Collection | Key fields |
|:-|:-|
| `merchant_apps` | `appId` (unique), `merchantId`, `environment`, `name`, `status`, optional `enabledMethods` and `limits`, `createdAt`. No credentials of any kind |
| `merchant_profiles` | `merchantId` (unique), `liveWalletId`, `sandboxWalletId`, `liveStatus`, `sandboxStatus`, `defaultCurrency` (USD), `businessName`, `displayName`, `status`, `enabledMethods`, `limits`, `feePlanId`, `settlementDestinations`, `routingPreferences` |
| `merchant_fee_plans` | `feePlanId`, per method and rail percentage and flat fees, tiers, `beneficiaryCheckFee`, minimum and maximum fee |
| `commercial_transactions` | `id`, `merchantId`, `appId`, `type`, `status`, `merchantReference`, `callbackUrl`, `notifyRegistered`, `metadata`, `version`, `method`, `rail`, `amount`, `currency`, `usdAmount`, `fee`, `fxRate`, `linkedDepositId` or `linkedDisbursementId` or `linkedOperationId`, `nextAction`, `livemode`, `timeline[]`, `createdAt`, `updatedAt` |
| `payment_links` | `linkId`, `merchantId`, amount rules, `callbackUrl`, expiry, usage count, status |
| `account_references` | `reference` (unique), `merchantId`, `expectedAmount`, `expiresAt`, `status` |
| `beneficiary_checks` | `checkId`, `merchantId`, `destinationType`, destination hash, `registeredName`, `expectedName`, `match`, `source`, `checkedAt`, `expiresAt` |
| `plans`, `commercial_customers`, `customer_payment_methods`, `subscriptions`, `subscription_invoices` | Billing engine (Section 7.6). Unique index on `(subscriptionId, billingPeriod)` for invoices. Provider tokens only, never raw card data |
| `payout_batches` | `batchId`, `merchantId`, `callbackUrl`, totals, per-item status counters |
| `refunds`, `disputes` | Linked to the original payment, with provider references |
| `idempotency_records` | `(merchantId, livemode, idempotencyKey)` unique, request hash, stored response, expiry |
| `callback_registrations` | `registrationId`, `merchantId`, `appId`, `livemode`, `type` (`EVENT` or `VALIDATION`), `url`, `eventTypes[]`, `onTimeout` (validation only), `description`, `status`, `statusReason`, `verifiedAt`, `createdAt`, `updatedAt`. Unique on `(appId, livemode, url)`. No secrets |
| `event_outbox` | `eventId`, `merchantId`, `appId`, `type`, payload, `targets[]` (each with `url`, `source` of `REQUEST` or `REGISTERED`, and `registrationId`), `forwardStatus` (to the developer service), `attempts`, `nextAttemptAt` |
| `merchant_holds` | `merchantId`, `transactionId`, `amountUsd`, `reason`, `createdAt`, `releasedAt` |
| `merchant_wallets`, `commercial_ledger` | Built. One wallet per merchant and environment with `availableCents`, `heldCents`, `pendingCents` and a window of applied operation keys; one ledger line per balance change, unique on `(walletId, objectId, entryType)`. Replaces the `Wallet` reuse in the proposed answer to decision 1 |
| `commercial_quotes` | Built. A priced quote with rate, fee and expiry that a payment or payout can reference |
| `rail_health` | `rail`, `method`, rolling counters, `breakerState`, `lastProbeAt` |

**Additive fields on existing entities**

- `Deposit`, `Disbursement`, `Transaction`: optional `merchantId`, `merchantReference`, `commercialTransactionId`. Null for every existing consumer record.
- `CompanyLedgerEntry`: new entry types for merchant fees, so PSP revenue appears alongside the existing commission entries in finance reports.

## 10. Money rules

**Currency.** USD is the default currency of the whole API and the ground truth of the ledger. Merchant wallets are USD ledgers like every wallet today, and every balance, statement line, fee and report figure is in USD. A payment or payout in another currency credits or debits `amount x rate to USD`, with the rate read from `ExchangeRateService` and stored on the transaction at initiation (or taken from a quote). The merchant sees the native amount, the USD amount and the rate used. When `currency` is omitted nothing is converted at all. Whether merchants should instead hold separate native-currency balances is an open decision (Section 16).

**Fees.** Computed per merchant from `merchant_fee_plans`, not from the global `CommissionConfig` rates (those stay for consumer flows). Collection fees are deducted from the credit; payout fees are added to the debit. Pass-through of provider costs is configurable per plan. Every fee posts a `CompanyLedgerEntry`, so it shows in the existing finance reports. The numbers themselves are a business decision, see Section 16.

**Holds.** Debiting only on confirmed provider result is safe for one consumer but not for a merchant sending many payouts at once, because each balance check would pass before any debit lands. At initiation the engine writes a hold for payout amount plus fee, and balance checks use `balance minus active holds`. The hold converts into a real debit on success, or is released with no debit on failure or timeout. Disputes also create holds. Funds from rails that pay out later, such as cards, show as pending and cannot be paid out until the provider releases them.

**Limits.** Per merchant: maximum single transaction, daily and monthly volume, daily payout count, per-method caps, and each provider's own minimum and maximum. Enforced in the wallet service even though the developer service checks too.

**Ledger integrity.** Every balance change goes through one method that writes a ledger line with `balanceBefore` and `balanceAfter`, mirroring the `ManualAdjustment` audit trail, so statements can always be reconstructed.

## 11. Security

- **Service authentication:** existing `X-API-Key` and `ROLE_INTERNAL_SERVICE`. Because these endpoints move money for third parties, the shared key alone is not enough for production.
- **Request signing:** add an HMAC check (`X-Timestamp`, `X-Signature` over method, path, body and timestamp) with a short replay window, so a leaked static key cannot be replayed. Mutual TLS between the developer service and the wallet service is the stronger alternative if the network allows.
- **Network:** `/internal/commercial/**` reachable only from the developer service (private network, security group or service mesh), never from the public internet.
- **Environment isolation:** the environment is resolved once, from the URL path, into a request-scoped context that every service and repository reads. Queries always filter on `livemode`, sandbox and live wallets are different documents, and the developer service issues separate credentials per app and per environment and routes by the app's environment, so a sandbox app's credentials can never be used against `/live`. The wallet service cross-checks the signed `X-App-Environment` against the path as a second barrier.
- **Credentials stay out of this service:** client ids are not even needed here, only the app id. Client secrets, API keys and tokens are validated and stored solely by the developer service, so a compromise of the wallet service does not leak developer credentials, and a compromise of the developer service's credential store does not by itself give access to provider secrets.
- **Merchant isolation:** every repository query includes `merchantId`. An id belonging to another merchant returns `NOT_FOUND`, never `FORBIDDEN`, to avoid leaking existence.
- **Card data:** card details are collected only by the provider's hosted elements. Premisave stores provider tokens and last four digits, never card numbers, which keeps PCI scope to the smallest self-assessment level.
- **Settlement destinations** are fixed per merchant and cannot be set through payout calls.
- **Secrets:** all provider credentials, certificates and webhook secrets stay in this service. Merchants and the developer service never see them.
- **PII:** phone numbers, emails, names and bank details are stored only where needed, masked in logs, and covered by retention rules (Section 13).
- **Fraud and risk:** velocity limits per merchant, per customer and per destination; blocklists for destinations and phone numbers; automatic hold or review above configurable thresholds; payout destination change cooling-off.
- **Kill switches:** merchant-level suspend, method-level disable, rail-level disable and a global PSP API off switch in configuration.
- **Audit:** every call logs `merchantId`, `requestId`, endpoint, outcome and amount, using the single-summary-line style adopted for the exchange-rate logs.

## 12. Reliability

- **Idempotency:** `(merchantId, Idempotency-Key)` is unique. A replay with the same body returns the stored response; the same key with a different body returns `DUPLICATE_REQUEST`. This sits in front of the existing `IdempotencyService` checks on `reference`.
- **Provider callbacks stay where they are.** Each provider keeps calling its existing webhook or callback endpoint. After the existing handler finishes, a router checks whether the underlying record has a `merchantId` and, if so, updates the commercial transaction, applies fees, releases holds and writes the outbox event. Consumer flows are untouched when `merchantId` is null.
- **Credit step is the one change to existing code paths.** Today each provider's success handler credits the consumer wallet directly and sends a consumer email. The merchant branch needs those handlers to call a small strategy instead (consumer credit versus merchant credit with fee). It is additive and defaults to current behaviour, but it is the part to test hardest.
- **Timeouts:** every commercial transaction has a deadline. Past it, status becomes `TIMED_OUT`, a status query goes to the provider, and reconciliation resolves it either way.
- **Outbox delivery:** at-least-once with retry and backoff; merchants dedupe on `eventId`.
- **Back-pressure:** bulk payouts use a concurrency cap and honour provider throttling responses.
- **Degraded mode:** if a provider is down, calls routed to it fail fast with `PROVIDER_UNAVAILABLE` or move to another rail before acceptance (Section 8), without creating debits or holds.
- **Observability:** metrics per merchant, method and rail (initiated, succeeded, failed, timed out, latency, hold total, breaker state) and a correlation id in every log line.

## 13. Compliance notes

These are flags for the business to confirm with counsel and the providers, not legal advice.

- Accepting and paying out money for third parties is regulated payment-service activity. Check which authorisations are needed in each country where merchants or their customers operate, starting with the Central Bank of Kenya under the National Payment System framework, and whether operating through Premisave's own provider accounts is permitted or each merchant needs its own.
- Each provider has its own terms for platforms and aggregators (Stripe Connect platform terms, PayPal partner terms, Flutterwave and NOWPayments business terms, mobile money aggregator onboarding). Confirm them before go-live.
- KYC and anti-money-laundering obligations apply to merchants: identity, business registration, source of funds, sanctions screening, suspicious activity reporting. The developer service collects documents; the wallet service must refuse to activate a merchant that is not marked verified.
- Crypto acceptance and payouts may fall under virtual asset service provider rules in some jurisdictions.
- The Data Protection Act 2019 and equivalent laws elsewhere apply to customer names, phone numbers and bank details: lawful basis, retention limits, deletion on request, a data-processing agreement with each merchant, and rules for cross-border transfer.
- Card handling should stay within the provider's hosted elements so PCI scope remains minimal.
- Merchant funds must stay identifiable and reconcilable against provider balances.

## 14. Proposed package layout

```
com.premisave.wallet.commercial
  controller
    CommercialMerchantController
    CommercialCapabilityController      (payment-methods, quotes)
    CommercialPaymentController         (payments, payment-links, account-references)
    CommercialPayoutController
    CommercialBeneficiaryController
    CommercialSubscriptionController
    CommercialRefundController          (refunds, disputes)
    CommercialSettlementController      (balance, statement, settlements, transfers)
    CommercialReportController
    CommercialSandboxController         (sandbox-only helpers)
  service
    MerchantService     AppRegistryService
    PaymentService (commercial)    PayoutService (commercial)
    BeneficiaryVerificationService
    SubscriptionBillingService
    RoutingService      RailHealthService
    QuoteService        FeeService
    HoldService         LedgerService
    EventOutboxService  CommercialCallbackRouter
    EnvironmentInterceptor              (reads /live or /sandbox from the path into a request-scoped context)
  rail
    PaymentRail                         (common interface)
    MpesaRail  StripeRail  PaypalRail  FlutterwaveRail  NowPaymentsRail  InternalWalletRail
  entity, repository, dto, config
```

**`PaymentRail` interface (sketch)**

```
capabilities()                         methods, countries, currencies, limits, direction
collect(CollectCommand)                returns provider reference and nextAction
payout(PayoutCommand)                  returns provider reference and initial status
verifyBeneficiary(VerifyCommand)       returns name, match inputs, source
refund(RefundCommand)                  native refund or reversal if supported
status(providerReference)              for reconciliation
```

Each adapter wraps the existing services (`MpesaDepositService`, `StripeDepositService`, `PaypalDisbursementService`, `FlutterwaveService`, `NowPaymentsDepositService` and so on) rather than reimplementing them. Controllers stay thin: validate, resolve the merchant, enforce idempotency, call a service. Provider-specific logic never appears outside `rail`.

## 15. Phased roadmap

| Phase | Scope | Result |
|:-|:-|:-|
| 0 | Settle the decisions in Section 16 | Agreed fee model, ledger shape, hold rule, launch rails, callback retry policy |
| 1 | Foundations: merchant profile, app registry, headers, idempotency, ledger method, balance and statement, event outbox, event endpoints and callback registrations, error codes, environment routing for `/live` and `/sandbox`, sandbox wallet and test funding, `PaymentRail` interface and the internal wallet rail | A merchant and its apps can exist in both environments, see a balance and receive events. **Built, except the internal wallet rail**, which is deferred because it touches consumer wallets. Built in addition: a simulated sandbox rail for payments and payouts with quotes, holds, fees and the simulate, credit, test event and reset helpers |
| 2 | First external rails for collection and payout: mobile money (M-Pesa) and one of Flutterwave or Stripe, callback router, per-request `callbackUrl` with normalised result codes, holds, capabilities and quotes, and the payment, payout and event simulation helpers | Merchants can get paid and pay out on the first real rails, and test them end to end in sandbox |
| 3 | Remaining rails (cards, PayPal, crypto, bank payouts), routing and health, bulk payouts | Full method coverage |
| 4 | Beneficiary verification for all destination types, payment links, account references, validation callbacks | Safer payouts, easier collection |
| 5 | Subscriptions: plans, customers, saved credentials, billing scheduler, dunning | Recurring revenue across rails |
| 6 | Refunds, disputes, settlements, reports, reconciliation, remaining sandbox helpers (disputes, clock, reset) | Operationally complete |
| 7 | Marketplace splits and sub-merchants, multi-currency balances, virtual accounts, advanced risk rules | Market parity |

Each phase ships on its own and adds no breaking change to existing endpoints.

## 16. Decisions and proposed answers

Each decision below has a proposed answer so implementation can start at Phase 1. Items marked **confirm** depend on facts about providers, regulators or pricing that must be checked outside this repository; the design works whichever way they turn out.

1. **Merchant ledger shape.** *Built differently from the proposal:* one USD `MerchantWallet` per merchant and environment, in its own `merchant_wallets` collection with a `commercial_ledger` of lines, instead of reusing the consumer `Wallet`. Balances are whole USD cents changed with guarded atomic updates, which the consumer wallet's read-modify-write code does not offer, and keeping them apart means a merchant operation can never credit a consumer wallet or trigger a consumer email. *Original proposal:* one USD `Wallet` per merchant and environment. Every transaction stores its native amount, currency and FX rate. Separate per-currency balances are a Phase 7 item, added only when merchants ask to hold foreign currency.

2. **Launch rails.** *Proposed:* the internal Premisave wallet in Phase 1, then M-Pesa (mobile money, already the most complete integration) and Stripe (cards) in Phase 2. Flutterwave comes third for mobile money and bank payouts across other African markets, then PayPal and NOWPayments.

3. **Provider account structure.** *Proposed:* collect on Premisave's own provider accounts, one pooled reconciliation per provider, for launch. Move high-volume merchants to provider sub-accounts or connected accounts later. **Confirm** that each provider's platform terms allow pooled collection for third parties (Section 13).

4. **Fee model.** *Proposed structure:*
   - Collections: percentage per method with a per-transaction minimum.
   - Payouts: flat fee plus a small percentage, since the provider cost is largely flat.
   - Provider costs built into the price rather than itemised, so merchants see one fee.
   - Beneficiary checks: a small flat fee per live check, free when the cached result is used.
   - Refunds: no Premisave fee, but the original collection fee is not returned.
   - Custom fee plans per merchant for large accounts.

   The actual numbers are a business decision. **Confirm** by taking each provider's current cost, adding a margin, and checking the result against what competitors charge in each market.

5. **Holds and reserves.** *Proposed:* yes to payout holds as described in Section 10. A rolling reserve on collections is off by default and switchable per merchant (percentage and number of days), meant for new, high-risk or high-dispute merchants. Disputes always create a hold for the disputed amount.

6. **Beneficiary checks for individuals on mobile money.** *Proposed:* accept the layered approach in Section 7.5, and always return `source` and `match` so merchants can see how strong a result is. A name learned from earlier payouts is shown as "previously seen", never as verified. **Confirm** per provider and country whether a true pre-payment lookup exists, using the provider's current documentation, and add it as the first source when it does.

7. **Bank account name resolution.** *Proposed:* at launch, where a provider offers account resolution for a country, use it; where none does, require `accountName` from the merchant and return `match: UNAVAILABLE`, not a guess. **Confirm** coverage per provider and country before promising it in the public API.

8. **Recurring on mobile money.** *Proposed:* do not depend on mobile money standing orders for the first release. Launch subscriptions on saved cards, vaulted PayPal, Premisave wallet debit and per-cycle prompts. Add standing orders as a new credential type once **confirmed** available on your shortcode and markets.

9. **Refund and dispute handling.** *Proposed:* admin approval for every commercial refund at launch, using the screens that already exist. Revisit an automatic path under a per-merchant threshold once a few months of data show the approval queue is mostly rubber-stamping. Chargeback evidence: the merchant supplies it through the developer portal, which passes it to `/disputes/{id}/evidence`; Premisave operations reviews it before it is submitted to the provider.

10. **Settlement.** *Proposed:* merchant-triggered by default, with optional scheduled settlement (daily or weekly, fixed cut-off in East Africa Time). Minimum settlement equals the destination provider's own minimum. Funds from rails that pay out later, such as cards, show as pending and cannot be settled until the provider releases them.

11. **Request signing.** *Proposed:* HMAC with a per-service shared secret, a five-minute replay window and two active secrets during rotation. It fits the existing internal-key setup without new infrastructure. Move to mutual TLS later if the network layer supports it.

12. **How both environments are served.** *Proposed:* option (b), one deployment per environment from the same code, each with its own provider credentials and database. It changes none of the existing provider configuration and gives the strongest isolation between test and real money.

13. **Other currencies and countries at launch.** *Proposed:* USD (default) and KES at launch. Add further currencies one at a time, per rail, after the FX path for each has been tested end to end. Countries: Kenya first for mobile money and payouts; card collection wherever the card provider allows; crypto wherever the crypto provider allows. **Confirm** the legal position for each new country before enabling it (Section 13).

14. **Ledger scope.** *Proposed:* one wallet per merchant and environment, shared by all its apps. Per-app limits and method restrictions are stored at app registration and enforced in the wallet service, because it is the source of truth for money rules. The developer service can enforce the same limits too as a first line.

15. **Names and repositories.** *Proposed:* `premisave-developer-service` and `premisave-developer-portal`, following the naming of `premisave-wallet-service`. Headers stay `X-Merchant-Id`, `X-App-Id` and `X-App-Environment`; the events endpoint is `POST /internal/events` on the developer service.

16. **Callback retry window and secret rotation.** *Proposed:* up to 12 attempts with exponential backoff and jitter over 24 hours, then mark the delivery failed and rely on `GET /events` and `POST /events/{eventId}/resend`. Webhook secrets rotate with two valid secrets for a short overlap, so merchants can switch without missing events. **Confirm** the window against how long typical merchant servers take to recover.

17. **Registration limits, verification and suspension.** *Proposed:* at most 10 registrations per app and environment. Live URLs must pass the challenge check before they receive events. A registration is suspended automatically after 3 days of continuous failure, with a notification to the merchant, and can be re-enabled after a successful test. Validation callbacks default to `REJECT` on timeout. **Confirm** the validation deadline against the slowest provider hook you plan to support.

With these answers, work starts at Phase 1 and the items marked **confirm** are tracked as prerequisites for the phase that uses them: provider terms and fees before Phase 2, name lookup coverage before Phase 4, mobile money standing orders before Phase 5.
