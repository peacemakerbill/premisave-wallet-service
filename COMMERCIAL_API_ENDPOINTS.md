# Premisave PSP API: Endpoint Reference (Wallet Service)

Draft contract for every endpoint the wallet service will expose to the developer service for the commercial (payment service provider) API. Companion to [COMMERCIAL_API_README.md](COMMERCIAL_API_README.md), which explains the design, rails, routing and rules. This file is the request and response reference.

**Status:** Phase 1 is implemented (see Implementation status below); everything else is still draft. All values in examples are illustrative. Names, paths, headers and status values here are the intended contract.

## Contents

1. [Conventions](#1-conventions)
2. [Object schemas](#2-object-schemas)
3. [Merchant and app provisioning](#3-merchant-and-app-provisioning)
4. [Capabilities and quotes](#4-capabilities-and-quotes)
5. [Payments (collections)](#5-payments-collections)
6. [Payouts](#6-payouts)
7. [Beneficiary verification](#7-beneficiary-verification)
8. [Subscriptions](#8-subscriptions)
9. [Refunds and disputes](#9-refunds-and-disputes)
10. [Balance, statements, settlements, transfers](#10-balance-statements-settlements-transfers)
11. [Events and callback registrations](#11-events-and-callback-registrations)
12. [Reports](#12-reports)
13. [Sandbox helpers](#13-sandbox-helpers)
14. [Endpoint index](#14-endpoint-index)

## Implementation status

Built in Phase 1 (rows of the index in section 14): 1 to 7 (provisioning), 8 and 9 (payment methods, quotes), 10 to 13 (payments), 19, 21 and 22 (payouts), 50 to 53 (balance, statement, transactions), 59 to 69 (events and callback registrations), and the sandbox helpers 13.1, 13.2, 13.5, 13.6 and 13.7. That is 7 provisioning calls, 24 operations under both `/live` and `/sandbox` (48 routes) and 5 sandbox-only helpers, 60 routes in total.

Phase 1 runs on a simulated sandbox rail only. Under `/live`, a payment or payout is refused with `422 METHOD_UNAVAILABLE` until a live rail (M-Pesa first) is added.

Not built yet: payment links and account references (14 to 18), bulk payouts (20, 23), beneficiary checks (24 to 26), subscriptions (27 to 43), refunds and disputes (44 to 49, and sandbox 13.3 and 13.4), settlements and transfers (54 to 58), reports (70 to 72), the live `PREMISAVE_WALLET` method (the sandbox simulates it) and validation callbacks (11.13). Sections describing these remain the intended contract.

Details that differ from the draft text below, as built:

* Customer phone, email and destination account details are always masked in responses, on single reads as well as lists.
* `POST /payments/{id}/cancel` does not use an `Idempotency-Key`; repeating it on a cancelled payment returns `409 INVALID_STATE`.
* Callback registration creation does not use an `Idempotency-Key`; a repeat of the same URL returns `409 DUPLICATE_REQUEST`. The registration limit counts registrations of every status.
* `GET /callbacks/registrations/{id}` returns the registration without a `health` block. Delivery health belongs to the developer service, which performs the delivery.
* The money model is one wallet per merchant and environment (collection `merchant_wallets`), separate from consumer wallets. All balances are USD cents.

## 1. Conventions

### 1.1 Base URLs

| Scope | Base URL | Postman variable |
|:-|:-|:-|
| Production | `{{base_url_wallet}}/internal/commercial/v1/live` | `{{base_live}}` |
| Sandbox | `{{base_url_wallet}}/internal/commercial/v1/sandbox` | `{{base_sandbox}}` |
| Provisioning | `{{base_url_wallet}}/internal/commercial/v1` | `{{base_provisioning}}` |

Every operation in sections 4 to 12 exists under both `/live` and `/sandbox` with identical request and response shapes. In this document paths are written relative to the environment base, for example `POST /payments` means `POST {{base_live}}/payments` or `POST {{base_sandbox}}/payments`. Sandbox helpers (section 13) exist under `/sandbox` only. Provisioning (section 3) uses the provisioning base.

### 1.2 Headers

These calls are made only by the developer service, never directly by merchants. Merchant credentials (client ids, secrets, API keys, tokens) are validated by the developer service and are never sent here.

| Header | Provisioning | Environment endpoints | Value |
|:-|:-|:-|:-|
| `Content-Type` | on bodies | on bodies | `application/json` |
| `Accept` | yes | yes | `application/json` |
| `X-API-Key` | required | required | Internal service key (`internal.api-key`) |
| `X-Merchant-Id` | not sent (merchant id is in the path, or created by `POST /merchants`) | required | `mer_4821` |
| `X-App-Id` | not sent | required | `app_93f0` |
| `X-App-Environment` | not sent | required | `production` for `/live`, `sandbox` for `/sandbox`. A mismatch returns `ENVIRONMENT_MISMATCH` |
| `X-Request-Id` | recommended | recommended | Correlation id, echoed back in the same header and in error bodies |
| `X-Timestamp` | required once signing is enabled | same | Unix time in seconds |
| `X-Signature` | required once signing is enabled | same | See 1.3 |
| `Idempotency-Key` | not used | required on money-moving `POST`s (marked in each endpoint) | Unique string per logical request, up to 64 characters |

In each endpoint below, **Headers: Standard** means the environment-endpoint column above. **Standard + Idempotency-Key** adds that header.

Response headers on every call: `X-Request-Id`, `Content-Type: application/json`. On an idempotent replay: `Idempotent-Replayed: true`.

### 1.3 Request signing

```
signingString = X-Timestamp + "\n" + HTTP_METHOD + "\n" + path_with_query + "\n" + lowercase_hex(SHA-256(raw_body))
X-Signature   = lowercase_hex(HMAC-SHA256(sharedSecret, signingString))
```

For requests with no body, the body hash is the SHA-256 of an empty string. Requests older than five minutes are rejected with `401`.

### 1.4 Response envelope

Success:

```json
{
  "success": true,
  "message": "Payment created",
  "data": { }
}
```

Error:

```json
{
  "success": false,
  "message": "Insufficient merchant balance",
  "data": {
    "code": "INSUFFICIENT_BALANCE",
    "requestId": "req_8f2c1a",
    "details": [ { "field": "amount", "issue": "exceeds available balance of 12.40 USD" } ]
  }
}
```

`details` is present on validation errors only.

### 1.5 Pagination and filtering

List endpoints accept `page` (zero based, default 0), `size` (default 20, maximum 100) and `sort` (for example `createdAt,desc`). Filters are listed per endpoint. Response:

```json
{
  "success": true,
  "message": "Payments retrieved",
  "data": {
    "content": [ { } ],
    "page": { "size": 20, "number": 0, "totalElements": 134, "totalPages": 7 }
  }
}
```

### 1.6 Idempotency

`Idempotency-Key` is unique per `(merchant, environment, key)` and remembered for 24 hours. Repeating a request with the same key and the same body returns the original response with `Idempotent-Replayed: true`. The same key with a different body returns `409 DUPLICATE_REQUEST`. A request still being processed under the same key returns `409 REQUEST_IN_PROGRESS`.

### 1.7 HTTP status and error codes

| HTTP | `data.code` | When |
|:-|:-|:-|
| 400 | `VALIDATION_FAILED` | Missing or invalid field |
| 400 | `INVALID_CALLBACK_URL` | Callback or registration URL fails validation |
| 400 | `ENVIRONMENT_MISMATCH` | `X-App-Environment` does not match the path |
| 401 | `UNAUTHENTICATED` | Bad internal key, bad or expired signature |
| 403 | `MERCHANT_SUSPENDED`, `APP_SUSPENDED` | Merchant or app not active |
| 403 | `LIVE_NOT_ENABLED` | Production access not yet approved |
| 403 | `METHOD_NOT_ENABLED` | Method not enabled for this merchant or app |
| 404 | `NOT_FOUND`, `MERCHANT_NOT_FOUND`, `APP_NOT_FOUND` | Unknown id, or id belongs to another merchant |
| 409 | `DUPLICATE_REQUEST`, `REQUEST_IN_PROGRESS` | Idempotency conflicts |
| 409 | `INVALID_STATE` | Operation not allowed in the current status |
| 422 | `INSUFFICIENT_BALANCE`, `LIMIT_EXCEEDED` | Money rules |
| 422 | `BENEFICIARY_NOT_VERIFIED` | A required beneficiary check failed |
| 422 | `METHOD_UNAVAILABLE` | No rail can serve this method, country and currency right now |
| 422 | `CALLBACK_REGISTRATION_LIMIT`, `CALLBACK_NOT_VERIFIED` | Callback registration rules |
| 429 | `RATE_LIMITED` | Defence in depth limit exceeded |
| 503 | `PROVIDER_UNAVAILABLE` | Provider or rail is down; nothing was debited |

### 1.8 Common enumerations

| Name | Values |
|:-|:-|
| Environment | `live`, `sandbox` |
| Method type | `MOBILE_MONEY`, `CARD`, `BANK_TRANSFER`, `PAYPAL`, `CRYPTO`, `PREMISAVE_WALLET` |
| Provider (rail) | `AUTO`, `MPESA`, `STRIPE`, `PAYPAL`, `FLUTTERWAVE`, `NOWPAYMENTS`, `PREMISAVE` |
| Status | `REQUIRES_ACTION`, `PENDING`, `SUCCESS`, `FAILED`, `TIMED_OUT`, `CANCELLED`, `REVERSED` |
| `nextAction.type` | `CUSTOMER_PROMPT`, `CLIENT_SECRET`, `REDIRECT`, `BANK_TRANSFER_DETAILS`, `CRYPTO_ADDRESS`, `CUSTOMER_CONSENT`, `NONE` |
| `result.code` | `SUCCESS`, `CUSTOMER_CANCELLED`, `CUSTOMER_TIMEOUT`, `INSUFFICIENT_FUNDS`, `DECLINED`, `INVALID_DESTINATION`, `BENEFICIARY_MISMATCH`, `LIMIT_EXCEEDED`, `PROVIDER_ERROR`, `TIMED_OUT`, `REVERSED` |
| Destination type | `MOBILE_MONEY`, `BUSINESS_ACCOUNT`, `BANK_ACCOUNT`, `PAYPAL`, `CRYPTO`, `PREMISAVE_WALLET` |
| Subscription state | `PENDING_AUTHORISATION`, `TRIALING`, `ACTIVE`, `PAST_DUE`, `PAUSED`, `CANCELLED`, `EXPIRED` |
| Callback registration status | `PENDING_VERIFICATION`, `ACTIVE`, `DISABLED`, `SUSPENDED` |
| Event forward status | `PENDING`, `DELIVERED_TO_DEVELOPER_SERVICE`, `FAILED`, `NOT_REQUIRED` (no delivery target) |

Amounts are decimal strings. `currency` is an ISO 4217 code (or a crypto ticker where relevant) and defaults to `USD` when omitted. Timestamps are ISO 8601 in UTC.

## 2. Object schemas

Objects are defined once here and referenced by name in the endpoint sections.

### 2.1 Merchant

```json
{
  "merchantId": "mer_4821",
  "businessName": "Acme Limited",
  "displayName": "ACME LIMITED",
  "defaultCurrency": "USD",
  "sandbox": { "status": "ACTIVE", "walletId": "wal_sb_77" },
  "live": { "status": "ACTIVE", "walletId": "wal_lv_31" },
  "enabledMethods": ["MOBILE_MONEY", "CARD", "BANK_TRANSFER", "PAYPAL", "CRYPTO", "PREMISAVE_WALLET"],
  "limits": {
    "singleTransactionUsd": "5000.00",
    "dailyVolumeUsd": "50000.00",
    "monthlyVolumeUsd": "500000.00",
    "dailyPayoutCount": 500
  },
  "feePlanId": "fee_standard",
  "settlementDestinations": [
    { "id": "dst_1", "type": "MOBILE_MONEY", "label": "Finance M-Pesa", "country": "KE", "phoneNumber": "2547****1111" }
  ],
  "createdAt": "2026-10-14T18:00:00Z",
  "updatedAt": "2026-10-14T18:00:00Z"
}
```

### 2.2 App

```json
{
  "appId": "app_93f0",
  "merchantId": "mer_4821",
  "environment": "production",
  "name": "Checkout backend",
  "status": "ACTIVE",
  "enabledMethods": ["MOBILE_MONEY", "CARD"],
  "limits": { "singleTransactionUsd": "1000.00", "dailyVolumeUsd": "20000.00" },
  "createdAt": "2026-10-14T18:05:00Z",
  "updatedAt": "2026-10-14T18:05:00Z"
}
```

`enabledMethods` and `limits` on an app can only narrow what the merchant already has. `null` means "same as merchant".

### 2.3 Quote

```json
{
  "quoteId": "qt_20a9",
  "direction": "COLLECTION",
  "amount": "25.00",
  "currency": "USD",
  "nativeAmount": "3225.00",
  "nativeCurrency": "KES",
  "fxRate": "129.00",
  "fee": "0.50",
  "netAmount": "24.50",
  "rail": "MPESA",
  "method": { "type": "MOBILE_MONEY", "provider": "MPESA" },
  "expiresAt": "2026-10-14T19:15:00Z"
}
```

### 2.4 Payment

```json
{
  "id": "pay_81ae",
  "merchantReference": "ORDER-1042",
  "status": "REQUIRES_ACTION",
  "amount": "25.00",
  "currency": "USD",
  "nativeAmount": "3225.00",
  "nativeCurrency": "KES",
  "fxRate": "129.00",
  "fee": "0.50",
  "netAmount": "24.50",
  "method": { "type": "MOBILE_MONEY", "provider": "MPESA" },
  "customer": { "name": "Jane Doe", "email": "jane@example.com", "phone": "2547****5678" },
  "nextAction": { "type": "CUSTOMER_PROMPT", "instructions": "Ask the customer to approve the prompt on 2547****5678." },
  "result": null,
  "providerReference": null,
  "description": "Order 1042",
  "callbackUrl": "https://merchant.example.com/hooks/premisave",
  "notifyRegistered": false,
  "metadata": { "orderId": "1042" },
  "appId": "app_93f0",
  "livemode": true,
  "version": 1,
  "createdAt": "2026-10-14T19:05:00Z",
  "updatedAt": "2026-10-14T19:05:00Z",
  "expiresAt": "2026-10-14T19:10:00Z"
}
```

`nextAction` variants:

```json
{ "type": "CUSTOMER_PROMPT", "instructions": "Approve the prompt on your phone." }
{ "type": "CLIENT_SECRET", "clientSecret": "pi_3Q_secret_9xk2", "publishableKey": "pk_live_51Hx" }
{ "type": "REDIRECT", "url": "https://www.paypal.com/checkoutnow?token=5O190127TN364715T" }
{ "type": "BANK_TRANSFER_DETAILS", "bankName": "Example Bank", "accountNumber": "0123456789", "accountName": "Premisave Collections", "reference": "PS1234-INV001" }
{ "type": "CRYPTO_ADDRESS", "network": "TRC20", "payCurrency": "USDT", "payAmount": "25.12", "address": "TXyz...example", "expiresAt": "2026-10-14T19:35:00Z" }
{ "type": "CUSTOMER_CONSENT", "instructions": "The wallet owner must approve this debit in the Premisave app." }
{ "type": "NONE" }
```

When final, `result` is set:

```json
{
  "code": "SUCCESS",
  "description": "Payment completed",
  "providerCode": "0",
  "providerDescription": "The service request is processed successfully."
}
```

### 2.5 PaymentLink

```json
{
  "id": "plink_5d21",
  "url": "https://pay.example.com/l/plink_5d21",
  "merchantReference": "INVOICE-77",
  "amountType": "FIXED",
  "amount": "100.00",
  "currency": "USD",
  "minAmount": null,
  "maxAmount": null,
  "description": "Consulting invoice 77",
  "allowedMethods": ["MOBILE_MONEY", "CARD"],
  "usage": "SINGLE",
  "usageCount": 0,
  "callbackUrl": "https://merchant.example.com/hooks/premisave",
  "status": "ACTIVE",
  "expiresAt": "2026-11-14T00:00:00Z",
  "createdAt": "2026-10-14T19:00:00Z"
}
```

`url` is built from the developer service's configured checkout host; the wallet service stores and returns the link id and rules, and the checkout page belongs to the developer service.

### 2.6 AccountReference

```json
{
  "reference": "PS1234-INV001",
  "merchantReference": "INVOICE-77",
  "expectedAmount": "100.00",
  "currency": "USD",
  "status": "OPEN",
  "payTo": { "type": "MOBILE_MONEY", "provider": "MPESA", "shortcode": "600992", "accountNumber": "PS1234-INV001" },
  "expiresAt": "2026-11-14T00:00:00Z",
  "createdAt": "2026-10-14T19:00:00Z"
}
```

### 2.7 Payout

```json
{
  "id": "po_3b77",
  "merchantReference": "SALARY-2026-10-001",
  "status": "PENDING",
  "amount": "20.00",
  "currency": "USD",
  "nativeAmount": "2580.00",
  "nativeCurrency": "KES",
  "fxRate": "129.00",
  "fee": "0.40",
  "totalDebit": "20.40",
  "destination": { "type": "MOBILE_MONEY", "provider": "MPESA", "country": "KE", "phoneNumber": "2547****5678" },
  "beneficiary": { "checkId": "bc_91ab", "registeredName": "JOHN DOE", "match": "EXACT" },
  "remarks": "October salary",
  "result": null,
  "providerReference": "AG_20261014_0000a1b2c3",
  "batchId": null,
  "callbackUrl": null,
  "notifyRegistered": true,
  "metadata": {},
  "appId": "app_93f0",
  "livemode": true,
  "version": 1,
  "createdAt": "2026-10-14T19:10:00Z",
  "updatedAt": "2026-10-14T19:10:00Z"
}
```

`totalDebit` is held at creation and becomes a real debit only on success.

### 2.8 PayoutBatch

```json
{
  "batchId": "bat_62f1",
  "merchantReference": "PAYROLL-2026-10",
  "status": "PROCESSING",
  "totals": { "count": 3, "amount": "60.00", "currency": "USD", "fee": "1.20" },
  "counters": { "pending": 1, "success": 2, "failed": 0, "timedOut": 0 },
  "callbackUrl": "https://merchant.example.com/hooks/payroll",
  "createdAt": "2026-10-14T19:12:00Z",
  "updatedAt": "2026-10-14T19:12:30Z"
}
```

Batch `status`: `PROCESSING`, `COMPLETED`, `COMPLETED_WITH_FAILURES`, `REJECTED`.

### 2.9 BeneficiaryCheck

```json
{
  "checkId": "bc_91ab",
  "destinationType": "BANK_ACCOUNT",
  "destination": { "country": "NG", "bankCode": "058", "accountNumber": "0123****89" },
  "verified": true,
  "registeredName": "ACME LIMITED",
  "expectedName": "ACME LIMITED",
  "match": "EXACT",
  "source": "PROVIDER_ACCOUNT_RESOLVE",
  "cached": false,
  "checkedAt": "2026-10-14T19:05:11Z",
  "expiresAt": "2026-10-15T19:05:11Z"
}
```

`match`: `EXACT`, `PARTIAL`, `MISMATCH`, `UNAVAILABLE`, `NOT_REQUESTED`. `source` examples: `PROVIDER_ORG_LOOKUP`, `PROVIDER_ACCOUNT_RESOLVE`, `PREMISAVE_DIRECTORY`, `PREVIOUS_PAYOUTS`, `ADDRESS_FORMAT_ONLY`, `MERCHANT_DISPLAY_NAME`.

### 2.10 Plan

```json
{
  "planId": "plan_pro_monthly",
  "name": "Pro monthly",
  "amount": "10.00",
  "currency": "USD",
  "interval": "MONTH",
  "intervalCount": 1,
  "trialDays": 7,
  "setupFee": "0.00",
  "allowedMethods": ["CARD", "PREMISAVE_WALLET", "MOBILE_MONEY"],
  "retryScheduleDays": [1, 3, 5],
  "onExhausted": "CANCEL",
  "status": "ACTIVE",
  "metadata": {},
  "createdAt": "2026-10-14T19:00:00Z"
}
```

`interval`: `DAY`, `WEEK`, `MONTH`, `YEAR`. `onExhausted`: `CANCEL` or `EXPIRE`.

### 2.11 Customer

```json
{
  "customerId": "cus_19ce",
  "name": "Jane Doe",
  "email": "jane@example.com",
  "phone": "2547****5678",
  "merchantReference": "USER-2210",
  "paymentMethods": [ { "paymentMethodId": "pm_a41f", "type": "CARD", "label": "Visa ending 4242", "status": "ACTIVE", "default": true } ],
  "metadata": {},
  "createdAt": "2026-10-14T19:00:00Z"
}
```

### 2.12 CustomerPaymentMethod

```json
{
  "paymentMethodId": "pm_a41f",
  "customerId": "cus_19ce",
  "type": "CARD",
  "provider": "STRIPE",
  "label": "Visa ending 4242",
  "status": "PENDING_AUTHORISATION",
  "default": false,
  "nextAction": { "type": "CLIENT_SECRET", "clientSecret": "seti_1Q_secret_7hd", "publishableKey": "pk_live_51Hx" },
  "createdAt": "2026-10-14T19:01:00Z"
}
```

`status`: `PENDING_AUTHORISATION`, `ACTIVE`, `FAILED`, `REMOVED`. Only provider tokens are kept, never card numbers.

### 2.13 Subscription

```json
{
  "id": "sub_77c0",
  "customerId": "cus_19ce",
  "planId": "plan_pro_monthly",
  "paymentMethodId": "pm_a41f",
  "state": "ACTIVE",
  "quantity": 1,
  "currentPeriod": { "start": "2026-10-14T00:00:00Z", "end": "2026-11-14T00:00:00Z" },
  "nextBillingAt": "2026-11-14T00:00:00Z",
  "trialEnd": null,
  "cancelAtPeriodEnd": false,
  "callbackUrl": null,
  "metadata": {},
  "appId": "app_93f0",
  "livemode": true,
  "version": 2,
  "createdAt": "2026-10-14T19:02:00Z",
  "updatedAt": "2026-10-14T19:03:00Z"
}
```

### 2.14 Invoice

```json
{
  "id": "inv_0c9d",
  "subscriptionId": "sub_77c0",
  "billingPeriod": "2026-10",
  "status": "PAID",
  "amount": "10.00",
  "currency": "USD",
  "fee": "0.45",
  "attempts": [
    { "attempt": 1, "at": "2026-10-14T19:03:00Z", "status": "SUCCESS", "paymentId": "pay_92bf" }
  ],
  "nextRetryAt": null,
  "paymentId": "pay_92bf",
  "createdAt": "2026-10-14T19:03:00Z"
}
```

Invoice `status`: `OPEN`, `AWAITING_CUSTOMER`, `PAID`, `PAYMENT_FAILED`, `VOID`.

### 2.15 Refund

```json
{
  "id": "rf_4a18",
  "paymentId": "pay_81ae",
  "status": "PENDING",
  "amount": "10.00",
  "currency": "USD",
  "reason": "Customer returned item",
  "method": "PAYOUT_TO_CUSTOMER",
  "requiresApproval": true,
  "merchantReference": "RETURN-1042",
  "result": null,
  "callbackUrl": null,
  "metadata": {},
  "version": 1,
  "createdAt": "2026-10-14T20:00:00Z",
  "updatedAt": "2026-10-14T20:00:00Z"
}
```

`method`: `PROVIDER_REFUND`, `PROVIDER_REVERSAL`, `PAYOUT_TO_CUSTOMER`, `WALLET_CREDIT`.

### 2.16 Dispute

```json
{
  "id": "dp_1e60",
  "paymentId": "pay_92bf",
  "status": "NEEDS_EVIDENCE",
  "amount": "10.00",
  "currency": "USD",
  "reason": "PRODUCT_NOT_RECEIVED",
  "evidenceDueBy": "2026-10-28T23:59:59Z",
  "evidence": [],
  "holdId": "hold_88a1",
  "createdAt": "2026-10-14T20:30:00Z",
  "updatedAt": "2026-10-14T20:30:00Z"
}
```

`status`: `NEEDS_EVIDENCE`, `UNDER_REVIEW`, `WON`, `LOST`.

### 2.17 Settlement

```json
{
  "id": "stl_7e02",
  "status": "PENDING",
  "amount": "500.00",
  "currency": "USD",
  "fee": "1.50",
  "destination": { "id": "dst_1", "type": "MOBILE_MONEY", "label": "Finance M-Pesa" },
  "trigger": "MERCHANT",
  "merchantReference": "SETTLE-2026-10-14",
  "result": null,
  "version": 1,
  "createdAt": "2026-10-14T21:00:00Z",
  "updatedAt": "2026-10-14T21:00:00Z"
}
```

### 2.18 Transfer

```json
{
  "id": "tr_2f94",
  "status": "SUCCESS",
  "amount": "15.00",
  "currency": "USD",
  "fee": "0.00",
  "to": { "type": "PREMISAVE_WALLET", "accountNumber": "seller@example.com", "name": "SELLER ONE" },
  "merchantReference": "ESCROW-RELEASE-9",
  "createdAt": "2026-10-14T21:10:00Z"
}
```

### 2.19 Balance

```json
{
  "currency": "USD",
  "available": "1240.60",
  "pending": "310.00",
  "reserved": "50.00",
  "total": "1600.60",
  "native": [ { "currency": "KES", "available": "160037.40", "rate": "129.00" } ],
  "asOf": "2026-10-14T21:15:00Z",
  "livemode": true
}
```

`available` = settled balance minus active holds. `pending` = funds not yet released by the provider (for example cards). `reserved` = holds for in-flight payouts and disputes.

### 2.20 Transaction (merchant ledger view)

```json
{
  "id": "txn_9d40",
  "type": "PAYMENT",
  "status": "SUCCESS",
  "merchantReference": "ORDER-1042",
  "amount": "25.00",
  "currency": "USD",
  "fee": "0.50",
  "net": "24.50",
  "method": { "type": "MOBILE_MONEY", "provider": "MPESA" },
  "objectId": "pay_81ae",
  "appId": "app_93f0",
  "timeline": [
    { "at": "2026-10-14T19:05:00Z", "status": "REQUIRES_ACTION", "note": "Prompt sent" },
    { "at": "2026-10-14T19:05:32Z", "status": "SUCCESS", "note": "Provider confirmed" }
  ],
  "livemode": true,
  "createdAt": "2026-10-14T19:05:00Z"
}
```

`type`: `PAYMENT`, `PAYOUT`, `REFUND`, `SUBSCRIPTION_CHARGE`, `SETTLEMENT`, `TRANSFER`, `ADJUSTMENT`, `FEE`.

### 2.21 Event

```json
{
  "eventId": "evt_7c1d9a",
  "type": "payment.succeeded",
  "createdAt": "2026-10-14T19:05:32Z",
  "livemode": true,
  "merchantId": "mer_4821",
  "appId": "app_93f0",
  "data": { },
  "targets": [
    { "url": "https://merchant.example.com/hooks/premisave", "source": "REGISTERED", "registrationId": "cbr_30de" }
  ],
  "forwardStatus": "DELIVERED_TO_DEVELOPER_SERVICE"
}
```

`data` is the full object (Payment, Payout, and so on) as it was at that moment. `forwardStatus`: `PENDING`, `DELIVERED_TO_DEVELOPER_SERVICE`, `FAILED`, `NOT_REQUIRED` (the event had no delivery target). `attempts` and `lastError` describe forwarding to the developer service.

### 2.22 CallbackRegistration

```json
{
  "id": "cbr_30de",
  "type": "EVENT",
  "url": "https://merchant.example.com/hooks/premisave",
  "eventTypes": ["payment.*", "payout.succeeded", "payout.failed"],
  "description": "Production order system",
  "status": "ACTIVE",
  "statusReason": null,
  "onTimeout": null,
  "verifiedAt": "2026-10-14T18:20:00Z",
  "appId": "app_93f0",
  "livemode": true,
  "createdAt": "2026-10-14T18:19:00Z",
  "updatedAt": "2026-10-14T18:20:00Z"
}
```

`onTimeout` (`ACCEPT` or `REJECT`) applies to `type: VALIDATION` only.

## 3. Merchant and app provisioning

Base: `{{base_provisioning}}` (not environment scoped). Called by the developer service when a developer signs up, completes business verification, creates an app, or is suspended. Headers for every call in this section:

| Header | Required | Value |
|:-|:-|:-|
| `X-API-Key` | yes | Internal service key |
| `Content-Type` | on bodies | `application/json` |
| `X-Request-Id` | recommended | Correlation id |
| `X-Timestamp`, `X-Signature` | once signing is enabled | See 1.3 |

### 3.1 Create merchant

`POST /merchants`

Creates the merchant and provisions its two wallets (sandbox active immediately, live in `PENDING_APPROVAL` until activated by 3.3). Natural key is `developerAccountId`, so a repeated call for the same account returns `409 DUPLICATE_REQUEST` with the existing `merchantId` in `data`.

Request:

```json
{
  "developerAccountId": "dev_5521",
  "businessName": "Acme Limited",
  "displayName": "ACME LIMITED",
  "country": "KE",
  "contactEmail": "finance@acme.example.com",
  "contactPhone": "254712345678",
  "defaultCurrency": "USD",
  "requestedMethods": ["MOBILE_MONEY", "CARD"]
}
```

| Field | Type | Required | Notes |
|:-|:-|:-|:-|
| `developerAccountId` | string | yes | Developer service account id |
| `businessName` | string | yes | Legal name |
| `displayName` | string | no | Name shown to customers and used for name-check matching. Defaults to uppercased `businessName` |
| `country` | string | yes | ISO 3166-1 alpha-2 |
| `contactEmail` | string | yes | |
| `contactPhone` | string | no | |
| `defaultCurrency` | string | no | Defaults to `USD` |
| `requestedMethods` | array | no | Methods to enable once live is approved. Sandbox always has all methods |

Response `201`:

```json
{
  "success": true,
  "message": "Merchant created",
  "data": {
    "merchantId": "mer_4821",
    "businessName": "Acme Limited",
    "displayName": "ACME LIMITED",
    "defaultCurrency": "USD",
    "sandbox": { "status": "ACTIVE", "walletId": "wal_sb_77" },
    "live": { "status": "PENDING_APPROVAL", "walletId": "wal_lv_31" },
    "enabledMethods": ["MOBILE_MONEY", "CARD"],
    "feePlanId": "fee_standard",
    "createdAt": "2026-10-14T18:00:00Z"
  }
}
```

Errors: `400 VALIDATION_FAILED`, `401 UNAUTHENTICATED`, `409 DUPLICATE_REQUEST`.

### 3.2 Get merchant

`GET /merchants/{merchantId}`

Headers: provisioning headers. No body.

Response `200`: `data` is a Merchant (2.1).

Errors: `404 MERCHANT_NOT_FOUND`.

### 3.3 Change merchant status

`PUT /merchants/{merchantId}/status`

Activates, suspends or closes one environment of a merchant. Live activation is the approval step after business verification in the developer service.

Request:

```json
{
  "environment": "live",
  "status": "ACTIVE",
  "reason": "KYC approved by compliance, ticket CMP-1182"
}
```

| Field | Type | Required | Values |
|:-|:-|:-|:-|
| `environment` | string | yes | `live`, `sandbox` |
| `status` | string | yes | `ACTIVE`, `SUSPENDED`, `CLOSED` |
| `reason` | string | yes for `SUSPENDED` and `CLOSED` | Stored on the audit record |

Behaviour: `SUSPENDED` rejects new payments, payouts, refunds and settlements with `MERCHANT_SUSPENDED` but keeps accepting provider callbacks for in-flight items and still serves reads. `CLOSED` additionally requires a zero balance and no open disputes, otherwise `409 INVALID_STATE`.

Response `200`: `data` is a Merchant (2.1).

Errors: `400 VALIDATION_FAILED`, `404 MERCHANT_NOT_FOUND`, `409 INVALID_STATE`.

### 3.4 Update merchant configuration

`PUT /merchants/{merchantId}/config`

Partial update; omitted fields are unchanged.

Request:

```json
{
  "displayName": "ACME LTD",
  "defaultCurrency": "USD",
  "enabledMethods": ["MOBILE_MONEY", "CARD", "BANK_TRANSFER"],
  "limits": {
    "singleTransactionUsd": "5000.00",
    "dailyVolumeUsd": "50000.00",
    "monthlyVolumeUsd": "500000.00",
    "dailyPayoutCount": 500
  },
  "feePlanId": "fee_standard",
  "settlementDestinations": [
    { "type": "MOBILE_MONEY", "label": "Finance M-Pesa", "country": "KE", "phoneNumber": "254711111111" }
  ],
  "settlementSchedule": { "mode": "MANUAL" },
  "environment": "live"
}
```

`environment` selects which wallet the `limits` and `settlementDestinations` apply to; `enabledMethods`, `displayName` and `feePlanId` are merchant wide. Changing `displayName` invalidates cached beneficiary checks that used the old name.

Response `200`: `data` is a Merchant (2.1).

Errors: `400 VALIDATION_FAILED`, `404 MERCHANT_NOT_FOUND`.

### 3.5 Create app

`POST /merchants/{merchantId}/apps`

The developer service creates its own app record and API keys; this call registers the app here so requests carrying `X-App-Id` can be validated and tracked.

Request:

```json
{
  "appId": "app_93f0",
  "environment": "production",
  "name": "Checkout backend",
  "enabledMethods": ["MOBILE_MONEY", "CARD"],
  "limits": { "singleTransactionUsd": "1000.00", "dailyVolumeUsd": "20000.00" }
}
```

`appId` is supplied by the developer service so both systems share the id. `environment` is `production` or `sandbox`. A `production` app requires the merchant live status to be `ACTIVE`, otherwise `403 LIVE_NOT_ENABLED`.

Response `201`: `data` is an App (2.2).

Errors: `400 VALIDATION_FAILED`, `403 LIVE_NOT_ENABLED`, `404 MERCHANT_NOT_FOUND`, `409 DUPLICATE_REQUEST` (appId already registered).

### 3.6 List apps

`GET /merchants/{merchantId}/apps`

Query parameters: `environment` (`production`, `sandbox`), `status` (`ACTIVE`, `SUSPENDED`), `page`, `size`.

Response `200`: paginated list (1.5) of App objects.

Errors: `404 MERCHANT_NOT_FOUND`.

### 3.7 Update app

`PUT /merchants/{merchantId}/apps/{appId}`

Request (all fields optional):

```json
{
  "name": "Checkout backend v2",
  "status": "SUSPENDED",
  "enabledMethods": ["CARD"],
  "limits": { "singleTransactionUsd": "500.00" }
}
```

`status`: `ACTIVE` or `SUSPENDED`. A suspended app gets `APP_SUSPENDED` on every call. App `enabledMethods` and `limits` may only narrow the merchant's; widening returns `400 VALIDATION_FAILED`.

Response `200`: `data` is an App (2.2).

Errors: `400 VALIDATION_FAILED`, `404 MERCHANT_NOT_FOUND`, `404 APP_NOT_FOUND`.

## 4. Capabilities and quotes

Headers: Standard (1.2). No `Idempotency-Key` needed.

### 4.1 List payment methods

`GET /payment-methods`

Returns what this merchant and app can use right now, after applying merchant and app enablement, country and currency support, rail health and circuit breakers.

Query parameters:

| Parameter | Required | Notes |
|:-|:-|:-|
| `country` | no | ISO country of the customer or recipient, for example `KE` |
| `currency` | no | Defaults to `USD` |
| `direction` | no | `COLLECTION` or `PAYOUT`. Both when omitted |

Response `200`:

```json
{
  "success": true,
  "message": "Payment methods retrieved",
  "data": {
    "methods": [
      {
        "type": "MOBILE_MONEY",
        "direction": "COLLECTION",
        "providers": ["MPESA"],
        "countries": ["KE"],
        "currencies": ["KES", "USD"],
        "available": true,
        "limits": { "minUsd": "0.50", "maxUsd": "1500.00" },
        "settlement": "INSTANT",
        "requiresAction": "CUSTOMER_PROMPT"
      },
      {
        "type": "CARD",
        "direction": "COLLECTION",
        "providers": ["STRIPE"],
        "countries": ["*"],
        "currencies": ["USD", "EUR", "GBP"],
        "available": true,
        "limits": { "minUsd": "1.00", "maxUsd": "5000.00" },
        "settlement": "DELAYED",
        "requiresAction": "CLIENT_SECRET"
      },
      {
        "type": "BANK_TRANSFER",
        "direction": "PAYOUT",
        "providers": ["FLUTTERWAVE"],
        "countries": ["NG", "GH", "KE"],
        "currencies": ["NGN", "GHS", "KES", "USD"],
        "available": false,
        "unavailableReason": "PROVIDER_UNAVAILABLE"
      }
    ]
  }
}
```

Errors: `400 VALIDATION_FAILED` (unknown country, currency or direction).

### 4.2 Create quote

`POST /quotes`

Previews fee, FX and the chosen rail without creating anything. A quote is informational; creating the payment or payout recomputes and locks the rate then. A `quoteId` may be passed to those endpoints to request the quoted rate, honoured while the quote has not expired.

Request:

```json
{
  "direction": "COLLECTION",
  "amount": "25.00",
  "currency": "USD",
  "method": { "type": "MOBILE_MONEY", "provider": "AUTO" },
  "country": "KE"
}
```

For payouts include `destination` (see 6.1) instead of `method`.

Response `200`: `data` is a Quote (2.3).

Errors: `400 VALIDATION_FAILED`, `403 METHOD_NOT_ENABLED`, `422 LIMIT_EXCEEDED`, `422 METHOD_UNAVAILABLE`.

## 5. Payments (collections)

### 5.1 Create payment

`POST /payments`

Headers: **Standard + Idempotency-Key**.

Starts a collection from a customer. The response always returns immediately; final status arrives by callback (section 11) or by polling 5.3.

Request, mobile money:

```json
{
  "amount": "25.00",
  "currency": "USD",
  "merchantReference": "ORDER-1042",
  "description": "Order 1042",
  "customer": { "name": "Jane Doe", "email": "jane@example.com", "phone": "254712345678" },
  "method": { "type": "MOBILE_MONEY", "provider": "AUTO" },
  "quoteId": "qt_20a9",
  "callbackUrl": "https://merchant.example.com/hooks/premisave",
  "notifyRegistered": false,
  "expiresInSeconds": 300,
  "metadata": { "orderId": "1042" }
}
```

| Field | Type | Required | Notes |
|:-|:-|:-|:-|
| `amount` | string | yes | Decimal, greater than zero |
| `currency` | string | no | Defaults to `USD`. A non-USD currency (for example `KES`) is converted at the locked rate; the response carries USD plus `nativeAmount` and `fxRate` |
| `merchantReference` | string | yes | Merchant's own reference, up to 64 characters. Searchable, returned on every event |
| `description` | string | no | Shown to the customer where the rail supports it |
| `customer` | object | depends | Fields required depend on method, see below |
| `method.type` | string | yes | See 1.8 |
| `method.provider` | string | no | Defaults to `AUTO` |
| `quoteId` | string | no | Honour a previous quote's rate while unexpired |
| `callbackUrl` | string | no | Per-request callback, HTTPS only (live). Replaces registered callbacks for this payment unless `notifyRegistered` is true |
| `notifyRegistered` | boolean | no | Also notify registered callbacks. Default false when `callbackUrl` is set, otherwise registered callbacks are used |
| `expiresInSeconds` | integer | no | 60 to 86400, default depends on method |
| `metadata` | object | no | Up to 20 string keys, returned unchanged |

Customer and method requirements:

| Method | Required customer fields | Extra `method` fields |
|:-|:-|:-|
| `MOBILE_MONEY` | `phone` | |
| `CARD` | `email` | `paymentMethodId` (saved card, optional), `returnUrl` (optional, for 3-D Secure redirects) |
| `PAYPAL` | `email` | `returnUrl`, `cancelUrl` (required) |
| `BANK_TRANSFER` | `name`, `email` | |
| `CRYPTO` | `email` | `payCurrency` (for example `USDT`), `network` (optional) |
| `PREMISAVE_WALLET` | `accountRef` (Premisave email or wallet account number) | |

Response `201` (mobile money, awaiting customer):

```json
{
  "success": true,
  "message": "Payment created",
  "data": {
    "id": "pay_81ae",
    "merchantReference": "ORDER-1042",
    "status": "REQUIRES_ACTION",
    "amount": "25.00",
    "currency": "USD",
    "nativeAmount": "3225.00",
    "nativeCurrency": "KES",
    "fxRate": "129.00",
    "fee": "0.50",
    "netAmount": "24.50",
    "method": { "type": "MOBILE_MONEY", "provider": "MPESA" },
    "customer": { "name": "Jane Doe", "email": "jane@example.com", "phone": "2547****5678" },
    "nextAction": { "type": "CUSTOMER_PROMPT", "instructions": "Ask the customer to approve the prompt on 2547****5678." },
    "result": null,
    "providerReference": null,
    "callbackUrl": "https://merchant.example.com/hooks/premisave",
    "notifyRegistered": false,
    "metadata": { "orderId": "1042" },
    "livemode": true,
    "version": 1,
    "createdAt": "2026-10-14T19:05:00Z",
    "updatedAt": "2026-10-14T19:05:00Z",
    "expiresAt": "2026-10-14T19:10:00Z"
  }
}
```

Other methods differ only in `nextAction` (see 2.4): card returns `CLIENT_SECRET`, PayPal `REDIRECT`, bank transfer `BANK_TRANSFER_DETAILS`, crypto `CRYPTO_ADDRESS`, wallet `CUSTOMER_CONSENT`. A saved card that needs no further step returns status `PENDING` with `nextAction.type` `NONE`.

Errors: `400 VALIDATION_FAILED`, `400 INVALID_CALLBACK_URL`, `403 MERCHANT_SUSPENDED`, `403 APP_SUSPENDED`, `403 METHOD_NOT_ENABLED`, `409 DUPLICATE_REQUEST`, `409 REQUEST_IN_PROGRESS`, `422 LIMIT_EXCEEDED`, `422 METHOD_UNAVAILABLE`, `503 PROVIDER_UNAVAILABLE`.

### 5.2 List payments

`GET /payments`

Headers: Standard.

Query parameters: `status`, `method` (type), `provider`, `merchantReference` (exact), `customerEmail`, `customerPhone`, `from`, `to` (ISO timestamps on `createdAt`), `minAmount`, `maxAmount`, `appId`, `page`, `size`, `sort`.

Response `200`: paginated list (1.5) of Payment objects (2.4). Customer contact fields are masked in lists.

Errors: `400 VALIDATION_FAILED`.

### 5.3 Get payment

`GET /payments/{id}`

Headers: Standard.

Response `200`:

```json
{
  "success": true,
  "message": "Payment retrieved",
  "data": {
    "id": "pay_81ae",
    "merchantReference": "ORDER-1042",
    "status": "SUCCESS",
    "amount": "25.00",
    "currency": "USD",
    "nativeAmount": "3225.00",
    "nativeCurrency": "KES",
    "fxRate": "129.00",
    "fee": "0.50",
    "netAmount": "24.50",
    "method": { "type": "MOBILE_MONEY", "provider": "MPESA" },
    "nextAction": { "type": "NONE" },
    "result": { "code": "SUCCESS", "description": "Payment completed", "providerCode": "0", "providerDescription": "The service request is processed successfully." },
    "providerReference": "RCA71X5MJ4",
    "refundedAmount": "0.00",
    "version": 3,
    "createdAt": "2026-10-14T19:05:00Z",
    "updatedAt": "2026-10-14T19:05:32Z"
  }
}
```

A failed payment has `status: FAILED` and for example `result: { "code": "CUSTOMER_CANCELLED", "description": "Customer cancelled the prompt" }`.

Errors: `404 NOT_FOUND`.

### 5.4 Cancel payment

`POST /payments/{id}/cancel`

Headers: **Standard**.

Cancels a payment that has not been paid. Allowed while status is `REQUIRES_ACTION` or `PENDING` and the rail supports cancellation (cards, PayPal, crypto, bank transfer details). Mobile money prompts cannot be recalled once sent; they expire on their own and return `409 INVALID_STATE`.

Request (optional):

```json
{ "reason": "Customer changed their mind" }
```

Response `200`: `data` is the Payment with `status: CANCELLED` and `result.code: CUSTOMER_CANCELLED`.

Errors: `404 NOT_FOUND`, `409 INVALID_STATE`.

### 5.5 Create payment link

`POST /payment-links`

Headers: **Standard + Idempotency-Key**.

Request:

```json
{
  "merchantReference": "INVOICE-77",
  "amountType": "FIXED",
  "amount": "100.00",
  "currency": "USD",
  "description": "Consulting invoice 77",
  "allowedMethods": ["MOBILE_MONEY", "CARD"],
  "usage": "SINGLE",
  "expiresAt": "2026-11-14T00:00:00Z",
  "callbackUrl": "https://merchant.example.com/hooks/premisave",
  "metadata": {}
}
```

| Field | Notes |
|:-|:-|
| `amountType` | `FIXED` (needs `amount`) or `OPEN` (customer enters, bounded by `minAmount` and `maxAmount`) |
| `usage` | `SINGLE` (one successful payment) or `MULTIPLE` |
| `allowedMethods` | Subset of the merchant's enabled methods. Defaults to all enabled |

Each customer attempt through the link creates a normal Payment (5.1) carrying `paymentLinkId` and the link's `merchantReference`.

Response `201`: `data` is a PaymentLink (2.5).

Errors: `400 VALIDATION_FAILED`, `400 INVALID_CALLBACK_URL`, `403 METHOD_NOT_ENABLED`, `409 DUPLICATE_REQUEST`.

### 5.6 List payment links

`GET /payment-links`

Query parameters: `status` (`ACTIVE`, `DISABLED`, `EXPIRED`, `COMPLETED`), `merchantReference`, `from`, `to`, `page`, `size`.

Response `200`: paginated list of PaymentLink objects.

### 5.7 Get payment link

`GET /payment-links/{id}`

Response `200`: `data` is a PaymentLink (2.5) with `usageCount` and `payments` (ids of attempts):

```json
{ "id": "plink_5d21", "usageCount": 1, "payments": ["pay_a1", "pay_a2"], "status": "COMPLETED" }
```

(abbreviated; the full PaymentLink fields are also present).

Errors: `404 NOT_FOUND`.

### 5.8 Update payment link

`PUT /payment-links/{id}`

Request (all optional): `description`, `status` (`ACTIVE` or `DISABLED`), `expiresAt`, `allowedMethods`, `callbackUrl`, `metadata`. `amount` and `currency` cannot change once any payment was attempted (`409 INVALID_STATE`).

Response `200`: `data` is a PaymentLink.

Errors: `400 VALIDATION_FAILED`, `404 NOT_FOUND`, `409 INVALID_STATE`.

### 5.9 Create account reference

`POST /payments/account-references`

Headers: **Standard + Idempotency-Key**.

Creates a reference the customer quotes as the account number when paying to the merchant's pay-in details (for example an M-Pesa paybill account number). Incoming transfers are matched to the merchant by reference, and if a validation callback is registered the merchant can accept or reject each one (11.13).

Request:

```json
{
  "merchantReference": "INVOICE-77",
  "expectedAmount": "100.00",
  "currency": "USD",
  "method": { "type": "MOBILE_MONEY", "provider": "MPESA" },
  "expiresAt": "2026-11-14T00:00:00Z"
}
```

`expectedAmount` is optional. When present, payments of a different amount are rejected at validation (if the rail supports validation) or flagged `AMOUNT_MISMATCH` for review.

Response `201`: `data` is an AccountReference (2.6).

Errors: `400 VALIDATION_FAILED`, `403 METHOD_NOT_ENABLED`, `409 DUPLICATE_REQUEST`, `422 METHOD_UNAVAILABLE`.

## 6. Payouts

### 6.1 Create payout

`POST /payouts`

Headers: **Standard + Idempotency-Key**.

Sends money from the merchant balance to a person, a business or a wallet. The amount plus fee is held immediately (`totalDebit`) and becomes a real debit only on success. A failed or timed-out payout releases the hold.

Request, mobile money to a person:

```json
{
  "amount": "20.00",
  "currency": "USD",
  "merchantReference": "SALARY-2026-10-001",
  "remarks": "October salary",
  "destination": {
    "type": "MOBILE_MONEY",
    "provider": "AUTO",
    "country": "KE",
    "phoneNumber": "254712345678"
  },
  "beneficiaryCheck": { "required": true, "expectedName": "John Doe" },
  "callbackUrl": null,
  "notifyRegistered": true,
  "metadata": {}
}
```

Destination variants:

```json
{ "type": "MOBILE_MONEY", "country": "KE", "phoneNumber": "254712345678" }
{ "type": "BUSINESS_ACCOUNT", "country": "KE", "subtype": "PAYBILL", "shortcode": "888880", "accountNumber": "ACC-1001" }
{ "type": "BUSINESS_ACCOUNT", "country": "KE", "subtype": "TILL", "shortcode": "123456" }
{ "type": "BANK_ACCOUNT", "country": "NG", "bankCode": "058", "accountNumber": "0123456789", "accountName": "ACME LIMITED" }
{ "type": "PAYPAL", "email": "payee@example.com" }
{ "type": "CRYPTO", "currency": "USDT", "network": "TRC20", "address": "TXyz...example" }
{ "type": "PREMISAVE_WALLET", "accountRef": "seller@example.com" }
```

| Field | Type | Required | Notes |
|:-|:-|:-|:-|
| `amount` | string | yes | Decimal, greater than zero |
| `currency` | string | no | Defaults to `USD`; non-USD is converted at the locked rate |
| `merchantReference` | string | yes | Up to 64 characters |
| `remarks` | string | no | Up to 100 characters, passed to the provider where supported |
| `destination` | object | yes | One of the variants above |
| `beneficiaryCheck.required` | boolean | no | Default follows merchant config. When true and the check is not `EXACT` or `PARTIAL` the payout is refused before any money moves (`BENEFICIARY_NOT_VERIFIED`) |
| `beneficiaryCheck.expectedName` | string | no | Name to compare against the registered name |
| `beneficiaryCheck.checkId` | string | no | Reuse a prior check (7.3) instead of resolving again |
| `callbackUrl`, `notifyRegistered`, `metadata` | | no | As in 5.1 |

Response `201`:

```json
{
  "success": true,
  "message": "Payout created",
  "data": {
    "id": "po_3b77",
    "merchantReference": "SALARY-2026-10-001",
    "status": "PENDING",
    "amount": "20.00",
    "currency": "USD",
    "nativeAmount": "2580.00",
    "nativeCurrency": "KES",
    "fxRate": "129.00",
    "fee": "0.40",
    "totalDebit": "20.40",
    "destination": { "type": "MOBILE_MONEY", "provider": "MPESA", "country": "KE", "phoneNumber": "2547****5678" },
    "beneficiary": { "checkId": "bc_91ab", "registeredName": "JOHN DOE", "match": "EXACT" },
    "result": null,
    "providerReference": "AG_20261014_0000a1b2c3",
    "livemode": true,
    "version": 1,
    "createdAt": "2026-10-14T19:10:00Z",
    "updatedAt": "2026-10-14T19:10:00Z"
  }
}
```

Errors: `400 VALIDATION_FAILED`, `400 INVALID_CALLBACK_URL`, `403 MERCHANT_SUSPENDED`, `403 APP_SUSPENDED`, `403 METHOD_NOT_ENABLED`, `409 DUPLICATE_REQUEST`, `409 REQUEST_IN_PROGRESS`, `422 INSUFFICIENT_BALANCE`, `422 LIMIT_EXCEEDED`, `422 BENEFICIARY_NOT_VERIFIED`, `422 METHOD_UNAVAILABLE`, `503 PROVIDER_UNAVAILABLE`.

`BENEFICIARY_NOT_VERIFIED` includes the check in `data.check` so the merchant can show the mismatch:

```json
{
  "success": false,
  "message": "Recipient name does not match",
  "data": {
    "code": "BENEFICIARY_NOT_VERIFIED",
    "requestId": "req_8f2c1a",
    "check": { "checkId": "bc_91ac", "registeredName": "MARY W", "expectedName": "John Doe", "match": "MISMATCH" }
  }
}
```

### 6.2 Create bulk payouts

`POST /payouts/bulk`

Headers: **Standard + Idempotency-Key**.

Request:

```json
{
  "merchantReference": "PAYROLL-2026-10",
  "mode": "BEST_EFFORT",
  "callbackUrl": "https://merchant.example.com/hooks/payroll",
  "items": [
    {
      "merchantReference": "SAL-001",
      "amount": "20.00",
      "currency": "USD",
      "destination": { "type": "MOBILE_MONEY", "country": "KE", "phoneNumber": "254712345678" },
      "beneficiaryCheck": { "required": true, "expectedName": "John Doe" }
    },
    {
      "merchantReference": "SAL-002",
      "amount": "40.00",
      "currency": "USD",
      "destination": { "type": "BANK_ACCOUNT", "country": "NG", "bankCode": "058", "accountNumber": "0123456789" }
    }
  ]
}
```

| Field | Notes |
|:-|:-|
| `mode` | `ALL_OR_NOTHING`: any invalid item rejects the whole batch. `BEST_EFFORT`: valid items proceed, invalid ones are reported |
| `items` | 1 to 500 items, each shaped like 6.1 without `callbackUrl` and `notifyRegistered` |

The total (amounts plus fees) is checked against available balance up front. Items are held and dispatched asynchronously at a controlled rate.

Response `202`:

```json
{
  "success": true,
  "message": "Batch accepted",
  "data": {
    "batchId": "bat_62f1",
    "status": "PROCESSING",
    "totals": { "count": 2, "amount": "60.00", "currency": "USD", "fee": "1.20" },
    "accepted": [ { "merchantReference": "SAL-001", "payoutId": "po_3b77" }, { "merchantReference": "SAL-002", "payoutId": "po_3b78" } ],
    "rejected": []
  }
}
```

A rejected item looks like `{ "merchantReference": "SAL-003", "code": "VALIDATION_FAILED", "message": "phoneNumber is invalid" }`. Completion is announced by `payout.batch.completed`.

Errors: `400 VALIDATION_FAILED` (including more than 500 items), `409 DUPLICATE_REQUEST`, `422 INSUFFICIENT_BALANCE`, `422 LIMIT_EXCEEDED`, plus the item-level codes above in `rejected`.

### 6.3 List payouts

`GET /payouts`

Query parameters: `status`, `destinationType`, `provider`, `merchantReference`, `batchId`, `from`, `to`, `minAmount`, `maxAmount`, `appId`, `page`, `size`, `sort`.

Response `200`: paginated list of Payout objects (2.7) with masked destinations.

### 6.4 Get payout

`GET /payouts/{id}`

Response `200`: `data` is a Payout (2.7). A finished payout includes `result` and `completedAt`:

```json
{
  "id": "po_3b77",
  "status": "SUCCESS",
  "result": { "code": "SUCCESS", "description": "Payout completed", "providerCode": "0" },
  "beneficiary": { "registeredName": "JOHN DOE", "match": "EXACT" },
  "completedAt": "2026-10-14T19:10:41Z"
}
```

(abbreviated; all Payout fields are returned). Failure example: `status: FAILED`, `result: { "code": "INVALID_DESTINATION", "description": "Recipient number is not registered" }`, hold released.

Errors: `404 NOT_FOUND`.

### 6.5 Get payout batch

`GET /payouts/batches/{batchId}`

Query parameters: `includeItems` (default false), `itemStatus`, `page`, `size`.

Response `200`:

```json
{
  "success": true,
  "message": "Batch retrieved",
  "data": {
    "batchId": "bat_62f1",
    "status": "COMPLETED_WITH_FAILURES",
    "totals": { "count": 3, "amount": "60.00", "currency": "USD", "fee": "1.20" },
    "counters": { "pending": 0, "success": 2, "failed": 1, "timedOut": 0 },
    "items": {
      "content": [
        { "payoutId": "po_3b77", "merchantReference": "SAL-001", "status": "SUCCESS" },
        { "payoutId": "po_3b79", "merchantReference": "SAL-003", "status": "FAILED", "result": { "code": "INVALID_DESTINATION" } }
      ],
      "page": { "size": 20, "number": 0, "totalElements": 3, "totalPages": 1 }
    }
  }
}
```

`items` appears only when `includeItems=true`.

Errors: `404 NOT_FOUND`.

## 7. Beneficiary verification

Headers: Standard. Name checks fail closed: when a rail cannot resolve a name the result is `UNAVAILABLE`, never a guess.

### 7.1 Verify beneficiary

`POST /beneficiaries/verify`

Resolves the registered name for a destination and compares it with an expected name. Works for mobile money numbers, till and paybill numbers, bank accounts, PayPal addresses, Premisave wallets and crypto addresses (format only).

Request:

```json
{
  "destination": { "type": "BANK_ACCOUNT", "country": "NG", "bankCode": "058", "accountNumber": "0123456789" },
  "expectedName": "Acme Limited",
  "direction": "PAYOUT"
}
```

`direction` is `PAYOUT` or `COLLECTION` (checking a customer before charging). `expectedName` is optional; without it the registered name is returned with `match: NOT_REQUESTED`. Results are cached per destination for 24 hours (`cached: true`).

Response `200`:

```json
{
  "success": true,
  "message": "Beneficiary verified",
  "data": {
    "checkId": "bc_91ab",
    "destinationType": "BANK_ACCOUNT",
    "destination": { "country": "NG", "bankCode": "058", "accountNumber": "0123****89" },
    "verified": true,
    "registeredName": "ACME LIMITED",
    "expectedName": "Acme Limited",
    "match": "EXACT",
    "source": "PROVIDER_ACCOUNT_RESOLVE",
    "cached": false,
    "checkedAt": "2026-10-14T19:05:11Z",
    "expiresAt": "2026-10-15T19:05:11Z"
  }
}
```

Errors: `400 VALIDATION_FAILED`, `403 METHOD_NOT_ENABLED`, `503 PROVIDER_UNAVAILABLE`. A rail that cannot answer returns `200` with `verified: false` and `match: UNAVAILABLE`, not an error.

### 7.2 Look up account

`POST /beneficiaries/lookup-account`

Returns the registered name only (no comparison). Intended for "confirm recipient" screens.

Request:

```json
{ "destination": { "type": "MOBILE_MONEY", "country": "KE", "phoneNumber": "254712345678" } }
```

Response `200`:

```json
{
  "success": true,
  "message": "Account resolved",
  "data": { "checkId": "bc_91ad", "registeredName": "JOHN DOE", "source": "PROVIDER_ORG_LOOKUP", "cached": true, "expiresAt": "2026-10-15T19:05:11Z" }
}
```

Errors: `400 VALIDATION_FAILED`, `422 METHOD_UNAVAILABLE` (rail has no lookup for this destination), `503 PROVIDER_UNAVAILABLE`.

### 7.3 Get beneficiary check

`GET /beneficiaries/checks/{checkId}`

Response `200`: `data` is a BeneficiaryCheck (2.9). Errors: `404 NOT_FOUND`.

## 8. Subscriptions

Headers: Standard; the `POST`s that create money obligations also send **Idempotency-Key** (marked below).

### 8.1 Create plan

`POST /plans`

Headers: **Standard + Idempotency-Key**.

Request:

```json
{
  "planId": "plan_pro_monthly",
  "name": "Pro monthly",
  "amount": "10.00",
  "currency": "USD",
  "interval": "MONTH",
  "intervalCount": 1,
  "trialDays": 7,
  "setupFee": "0.00",
  "allowedMethods": ["CARD", "PREMISAVE_WALLET", "MOBILE_MONEY"],
  "retryScheduleDays": [1, 3, 5],
  "onExhausted": "CANCEL",
  "metadata": {}
}
```

`planId` is optional (generated when omitted) and unique per merchant and environment.

Response `201`: `data` is a Plan (2.10).

Errors: `400 VALIDATION_FAILED`, `409 DUPLICATE_REQUEST`.

### 8.2 List plans

`GET /plans`. Query parameters: `status` (`ACTIVE`, `ARCHIVED`), `page`, `size`. Response `200`: paginated Plan list.

### 8.3 Get plan

`GET /plans/{planId}`. Response `200`: Plan (2.10). Errors: `404 NOT_FOUND`.

### 8.4 Update plan

`PUT /plans/{planId}`

Request (optional fields): `name`, `status` (`ACTIVE` or `ARCHIVED`), `allowedMethods`, `retryScheduleDays`, `onExhausted`, `metadata`. Price, currency and interval are immutable; create a new plan instead. Existing subscriptions keep the terms they started with.

Response `200`: Plan. Errors: `400 VALIDATION_FAILED`, `404 NOT_FOUND`, `409 INVALID_STATE` (attempt to change price or interval).

### 8.5 Create customer

`POST /customers`

Headers: Standard.

Request:

```json
{
  "name": "Jane Doe",
  "email": "jane@example.com",
  "phone": "254712345678",
  "merchantReference": "USER-2210",
  "metadata": {}
}
```

Response `201`: `data` is a Customer (2.11). Errors: `400 VALIDATION_FAILED`, `409 DUPLICATE_REQUEST` (same `merchantReference` already exists; `data.customerId` returned).

### 8.6 List customers

`GET /customers`. Query parameters: `email`, `phone`, `merchantReference`, `page`, `size`. Response `200`: paginated Customer list.

### 8.7 Get customer

`GET /customers/{customerId}`. Response `200`: Customer (2.11) including saved payment methods. Errors: `404 NOT_FOUND`.

### 8.8 Add customer payment method

`POST /customers/{customerId}/payment-methods`

Headers: **Standard + Idempotency-Key**.

Saves a way to bill the customer repeatedly. No card numbers are sent here; the customer completes the provider step using the returned `nextAction`.

Request:

```json
{
  "type": "CARD",
  "provider": "STRIPE",
  "returnUrl": "https://merchant.example.com/billing/return",
  "makeDefault": true
}
```

Supported `type` values: `CARD` (setup intent, returns `CLIENT_SECRET`), `PAYPAL` (billing agreement, returns `REDIRECT`), `PREMISAVE_WALLET` (consent in the Premisave app, returns `CUSTOMER_CONSENT`; needs `accountRef`), `MOBILE_MONEY` (per-cycle prompt, no stored credential; needs `phoneNumber`; status `ACTIVE` immediately and every cycle sends a new prompt).

Response `201`: `data` is a CustomerPaymentMethod (2.12). Status becomes `ACTIVE` through the event `customer.payment_method.activated` once the customer completes the step.

Errors: `400 VALIDATION_FAILED`, `403 METHOD_NOT_ENABLED`, `404 NOT_FOUND`, `422 METHOD_UNAVAILABLE`.

### 8.9 Create subscription

`POST /subscriptions`

Headers: **Standard + Idempotency-Key**.

Request:

```json
{
  "customerId": "cus_19ce",
  "planId": "plan_pro_monthly",
  "paymentMethodId": "pm_a41f",
  "quantity": 1,
  "startAt": null,
  "trialDays": null,
  "callbackUrl": null,
  "metadata": { "tier": "pro" }
}
```

`startAt` defaults to now. `trialDays` overrides the plan's trial. The first charge happens at trial end (or immediately). If the payment method is still `PENDING_AUTHORISATION` the subscription starts in that state.

Response `201`: `data` is a Subscription (2.13) with `state` `TRIALING`, `ACTIVE` or `PENDING_AUTHORISATION`.

Errors: `400 VALIDATION_FAILED`, `403 METHOD_NOT_ENABLED`, `404 NOT_FOUND` (customer, plan or payment method), `409 DUPLICATE_REQUEST`, `422 METHOD_UNAVAILABLE`.

### 8.10 List subscriptions

`GET /subscriptions`. Query parameters: `state`, `customerId`, `planId`, `from`, `to`, `page`, `size`. Response `200`: paginated Subscription list.

### 8.11 Get subscription

`GET /subscriptions/{id}`. Response `200`: Subscription (2.13). Errors: `404 NOT_FOUND`.

### 8.12 Pause subscription

`POST /subscriptions/{id}/pause`

Request: `{ "resumeAt": "2026-12-01T00:00:00Z", "reason": "Customer request" }` (`resumeAt` optional; without it the subscription stays paused until resumed).

Response `200`: Subscription with `state: PAUSED`. Errors: `404 NOT_FOUND`, `409 INVALID_STATE` (only `ACTIVE`, `TRIALING` or `PAST_DUE` can be paused).

### 8.13 Resume subscription

`POST /subscriptions/{id}/resume`

Request: `{ "billNow": false }`. Response `200`: Subscription back to `ACTIVE`; `billNow: true` charges the prorated amount immediately. Errors: `404 NOT_FOUND`, `409 INVALID_STATE`.

### 8.14 Cancel subscription

`POST /subscriptions/{id}/cancel`

Request: `{ "atPeriodEnd": true, "reason": "Too expensive" }`. With `atPeriodEnd: true` it stays `ACTIVE` with `cancelAtPeriodEnd: true`; otherwise it moves to `CANCELLED` immediately and open invoices are voided.

Response `200`: Subscription. Errors: `404 NOT_FOUND`, `409 INVALID_STATE`.

### 8.15 Update subscription

`PUT /subscriptions/{id}`

Request (optional): `planId` (switch plan, prorated), `quantity`, `paymentMethodId`, `callbackUrl`, `metadata`. Response `200`: Subscription. Errors: `400 VALIDATION_FAILED`, `404 NOT_FOUND`, `409 INVALID_STATE`.

### 8.16 List subscription invoices

`GET /subscriptions/{id}/invoices`. Query parameters: `status`, `page`, `size`. Response `200`: paginated Invoice list (2.14). Errors: `404 NOT_FOUND`.

There is one invoice per `(subscriptionId, billingPeriod)`; the scheduler cannot create a duplicate.

### 8.17 Retry invoice

`POST /invoices/{id}/retry`

Headers: **Standard + Idempotency-Key**.

Request (optional): `{ "paymentMethodId": "pm_b772" }` to retry on a different method.

Response `200`: Invoice (2.14) with a new attempt (`status` `OPEN` or `AWAITING_CUSTOMER`). Errors: `404 NOT_FOUND`, `409 INVALID_STATE` (already `PAID` or `VOID`), `422 METHOD_UNAVAILABLE`, `503 PROVIDER_UNAVAILABLE`.

## 9. Refunds and disputes

### 9.1 Create refund

`POST /refunds`

Headers: **Standard + Idempotency-Key**.

Refunds a successful payment in full or in part. The method is chosen from what the original rail supports: provider refund (cards, PayPal), provider reversal (where offered), payout back to the customer (mobile money), or wallet credit (Premisave wallet payments).

Request:

```json
{
  "paymentId": "pay_81ae",
  "amount": "10.00",
  "currency": "USD",
  "reason": "Customer returned item",
  "merchantReference": "RETURN-1042",
  "callbackUrl": null,
  "metadata": {}
}
```

`amount` is optional and defaults to the remaining refundable amount. Refunds above the merchant's approval threshold return with `requiresApproval: true`. How approval is collected (Developer Portal, merchant staff roles) is still an open decision; until it is settled this flag is informational and the refund proceeds.

Response `201`: `data` is a Refund (2.15).

Errors: `400 VALIDATION_FAILED`, `404 NOT_FOUND` (payment), `409 DUPLICATE_REQUEST`, `409 INVALID_STATE` (payment not `SUCCESS`, or fully refunded), `422 INSUFFICIENT_BALANCE`, `422 LIMIT_EXCEEDED` (amount exceeds refundable balance), `503 PROVIDER_UNAVAILABLE`.

### 9.2 List refunds

`GET /refunds`. Query parameters: `status`, `paymentId`, `merchantReference`, `from`, `to`, `page`, `size`. Response `200`: paginated Refund list.

### 9.3 Get refund

`GET /refunds/{id}`. Response `200`: Refund (2.15), with `result` when final. Errors: `404 NOT_FOUND`.

### 9.4 List disputes

`GET /disputes`. Query parameters: `status`, `paymentId`, `from`, `to`, `page`, `size`. Response `200`: paginated Dispute list (2.16).

### 9.5 Get dispute

`GET /disputes/{id}`. Response `200`: Dispute (2.16) including submitted evidence. Errors: `404 NOT_FOUND`.

### 9.6 Submit dispute evidence

`POST /disputes/{id}/evidence`

Headers: **Standard + Idempotency-Key**.

Request:

```json
{
  "type": "PROOF_OF_DELIVERY",
  "text": "Parcel delivered 2026-10-10, signed by recipient.",
  "attachments": [
    { "name": "delivery-receipt.pdf", "url": "https://files.example.com/dr-77.pdf", "mimeType": "application/pdf" }
  ],
  "submit": true
}
```

`submit: false` saves a draft; `true` sends it to the provider and moves the dispute to `UNDER_REVIEW`. Evidence after `evidenceDueBy` returns `409 INVALID_STATE`.

Response `200`: Dispute with the evidence entry added.

Errors: `400 VALIDATION_FAILED`, `404 NOT_FOUND`, `409 INVALID_STATE`, `503 PROVIDER_UNAVAILABLE`.

## 10. Balance, statements, settlements, transfers

Headers: Standard; `Idempotency-Key` where marked.

### 10.1 Get balance

`GET /balance`

Query parameters: `currency` (optional, adds a `native` conversion).

Response `200`: `data` is a Balance (2.19).

### 10.2 Statement

`GET /statement`

Query parameters: `from`, `to` (required), `format` (`JSON` default, or `CSV`), `page`, `size`.

Response `200`:

```json
{
  "success": true,
  "message": "Statement retrieved",
  "data": {
    "currency": "USD",
    "from": "2026-10-01T00:00:00Z",
    "to": "2026-10-14T23:59:59Z",
    "openingBalance": "900.00",
    "closingBalance": "1240.60",
    "totals": { "credits": "1800.00", "debits": "1459.40", "fees": "32.10" },
    "lines": {
      "content": [
        { "at": "2026-10-14T19:05:32Z", "type": "PAYMENT", "objectId": "pay_81ae", "description": "ORDER-1042", "credit": "25.00", "debit": null, "fee": "0.50", "balanceAfter": "1240.60" }
      ],
      "page": { "size": 20, "number": 0, "totalElements": 213, "totalPages": 11 }
    }
  }
}
```

`format=CSV` returns `text/csv` (no envelope) with the same lines. Errors: `400 VALIDATION_FAILED` (range over 92 days).

### 10.3 List transactions

`GET /transactions`

Query parameters: `type`, `status`, `merchantReference`, `objectId`, `from`, `to`, `appId`, `page`, `size`, `sort`. Response `200`: paginated Transaction list (2.20).

### 10.4 Get transaction

`GET /transactions/{id}`. Response `200`: Transaction (2.20) with `timeline`. Errors: `404 NOT_FOUND`.

### 10.5 Create settlement

`POST /settlements`

Headers: **Standard + Idempotency-Key**.

Moves funds from the merchant balance to a registered settlement destination (3.4). Amount plus fee is held first.

Request:

```json
{
  "amount": "500.00",
  "currency": "USD",
  "destinationId": "dst_1",
  "merchantReference": "SETTLE-2026-10-14"
}
```

Response `201`: `data` is a Settlement (2.17) in `PENDING`.

Errors: `400 VALIDATION_FAILED`, `403 MERCHANT_SUSPENDED`, `404 NOT_FOUND` (destination), `409 DUPLICATE_REQUEST`, `422 INSUFFICIENT_BALANCE`, `422 LIMIT_EXCEEDED`, `503 PROVIDER_UNAVAILABLE`.

### 10.6 List settlements

`GET /settlements`. Query parameters: `status`, `trigger` (`MERCHANT`, `SCHEDULE`), `from`, `to`, `page`, `size`. Response `200`: paginated Settlement list.

### 10.7 Get settlement

`GET /settlements/{id}`. Response `200`: Settlement (2.17). Errors: `404 NOT_FOUND`.

### 10.8 Update settlement schedule

`PUT /settlements/schedule`

Request:

```json
{
  "mode": "DAILY",
  "destinationId": "dst_1",
  "minimumAmount": "50.00",
  "keepReserve": "100.00",
  "runAtUtc": "06:00"
}
```

`mode`: `MANUAL`, `DAILY`, `WEEKLY` (add `dayOfWeek`), `THRESHOLD` (add `thresholdAmount`). `keepReserve` is left in the balance. Scheduled runs create Settlements with `trigger: SCHEDULE`.

Response `200`: `data` is the saved schedule object (the request plus `updatedAt`).

Errors: `400 VALIDATION_FAILED`, `404 NOT_FOUND` (destination).

### 10.9 Create transfer

`POST /transfers`

Headers: **Standard + Idempotency-Key**.

Instant transfer from the merchant balance to a Premisave wallet (marketplace splits, escrow release, internal payouts). Charged at the internal transfer rate.

Request:

```json
{
  "amount": "15.00",
  "currency": "USD",
  "to": { "type": "PREMISAVE_WALLET", "accountRef": "seller@example.com" },
  "merchantReference": "ESCROW-RELEASE-9",
  "note": "Order 9 payout to seller"
}
```

Response `201`: `data` is a Transfer (2.18).

Errors: `400 VALIDATION_FAILED`, `404 NOT_FOUND` (recipient wallet), `409 DUPLICATE_REQUEST`, `422 INSUFFICIENT_BALANCE`, `422 LIMIT_EXCEEDED`.

## 11. Events and callback registrations

The wallet service writes every status change as an Event to an outbox and forwards it to the developer service, which delivers it to the merchant's URLs. Events are scoped to the environment in the path.

### 11.1 List events

`GET /events`

Query parameters: `type` (supports `payment.*` patterns), `objectId`, `forwardStatus`, `from`, `to`, `page`, `size`.

Response `200`: paginated Event list (2.21).

### 11.2 Get event

`GET /events/{eventId}`. Response `200`: Event (2.21) with `data` and delivery `targets`. Errors: `404 NOT_FOUND`.

### 11.3 Resend event

`POST /events/{eventId}/resend`

Re-delivers an event with the same `eventId` and payload (receivers must deduplicate on `eventId`).

Request (optional): `{ "registrationId": "cbr_30de" }` or `{ "callbackUrl": "https://merchant.example.com/hooks/premisave" }` to target one destination. Without a body it goes to the original targets.

Response `202`: `{ "success": true, "message": "Event queued for resend", "data": { "eventId": "evt_7c1d9a", "queuedAt": "2026-10-14T20:00:00Z" } }`.

Errors: `400 INVALID_CALLBACK_URL`, `404 NOT_FOUND`.

### 11.4 Register callback

`POST /callbacks/registrations`

Headers: **Standard**.

Request, event callback:

```json
{
  "type": "EVENT",
  "url": "https://merchant.example.com/hooks/premisave",
  "eventTypes": ["payment.*", "payout.succeeded", "payout.failed"],
  "description": "Production order system"
}
```

Request, validation callback (synchronous accept or reject of incoming paybill style payments):

```json
{
  "type": "VALIDATION",
  "url": "https://merchant.example.com/hooks/validate",
  "eventTypes": ["payment.validation"],
  "onTimeout": "REJECT",
  "description": "Invoice number check"
}
```

| Field | Notes |
|:-|:-|
| `type` | `EVENT` or `VALIDATION` |
| `url` | Up to 2048 characters, no credentials in the URL. Live requires HTTPS and a public host. Sandbox also allows `http` and localhost-style hosts for local development |
| `eventTypes` | Exact types or patterns such as `payment.*`. `*` subscribes to everything |
| `onTimeout` | `VALIDATION` only: `ACCEPT` or `REJECT` (default `REJECT`) |

Response `201`: `data` is a CallbackRegistration (2.22). In `live` it starts as `PENDING_VERIFICATION` and must pass 11.9 before it receives events; in `sandbox` it starts `ACTIVE`.

Errors: `400 VALIDATION_FAILED`, `400 INVALID_CALLBACK_URL`, `409 DUPLICATE_REQUEST`, `422 CALLBACK_REGISTRATION_LIMIT` (maximum registrations per app and environment, default 10).

### 11.5 List registrations

`GET /callbacks/registrations`. Query parameters: `status`, `type`, `appId`, `page`, `size`. Response `200`: paginated CallbackRegistration list.

### 11.6 Get registration

`GET /callbacks/registrations/{id}`

Response `200`: CallbackRegistration (2.22). Errors: `404 NOT_FOUND`.

Delivery health (last delivery, last status code, failure counts) is owned by the developer service, which performs the delivery to the merchant.

### 11.7 Update registration

`PUT /callbacks/registrations/{id}`

Request (optional): `url`, `eventTypes`, `description`, `onTimeout`. Changing `url` in live returns the registration to `PENDING_VERIFICATION`.

Response `200`: CallbackRegistration. Errors: `400 VALIDATION_FAILED`, `400 INVALID_CALLBACK_URL`, `404 NOT_FOUND`.

### 11.8 Delete registration

`DELETE /callbacks/registrations/{id}`

Response `200`: `{ "success": true, "message": "Registration deleted", "data": { "id": "cbr_30de", "deleted": true } }`. Events already queued for it are dropped. Errors: `404 NOT_FOUND`.

### 11.9 Verify registration

`POST /callbacks/registrations/{id}/verify`

Proves the merchant controls the URL (live only). The wallet asks the developer service to run the check:

```
POST {developer_service}/internal/callbacks/verify
X-API-Key: <internal key>
```

```json
{ "url": "https://merchant.example.com/hooks/premisave", "challenge": "ch_5a91f3", "registrationId": "cbr_30de", "livemode": true }
```

The developer service delivers a `callback.verification` event containing the challenge to that URL, expects a `2xx` reply echoing it (`{ "challenge": "ch_5a91f3" }`), and answers the wallet with `2xx` and `{ "verified": true }`. Any other answer, or a deployment with no developer service configured, leaves the registration pending.

Response `200`:

```json
{ "success": true, "message": "Callback verified", "data": { "id": "cbr_30de", "status": "ACTIVE", "verifiedAt": "2026-10-14T18:20:00Z" } }
```

Errors: `404 NOT_FOUND`, `409 INVALID_STATE` (already active or suspended), `422 CALLBACK_NOT_VERIFIED` (no reply, wrong challenge or non-2xx; `data.detail` carries the reason). Sandbox registrations return `200` immediately.

### 11.10 Test registration

`POST /callbacks/registrations/{id}/test`

Sends a `callback.test` event to the registered URL.

Request (optional): `{ "eventType": "payment.succeeded" }` to send a sample of a real type (sample data marked `"sample": true`).

Response `202`:

```json
{ "success": true, "message": "Test event queued", "data": { "eventId": "evt_test_01", "registrationId": "cbr_30de" } }
```

The delivery outcome is visible on `GET /events/{eventId}`. Errors: `404 NOT_FOUND`, `409 INVALID_STATE` (registration not `ACTIVE`).

### 11.11 Change registration status

`PUT /callbacks/registrations/{id}/status`

Developer service only (not exposed to merchants). Used to disable a failing endpoint or suspend an abusive one.

Request:

```json
{ "status": "SUSPENDED", "reason": "Receiver returned 5xx for 72 hours" }
```

`status`: `ACTIVE`, `DISABLED`, `SUSPENDED`. Response `200`: CallbackRegistration with `statusReason`.

Errors: `400 VALIDATION_FAILED`, `404 NOT_FOUND`.

### 11.12 What the merchant receives (event delivery)

Delivery is at-least-once. Receivers return `2xx` within 10 seconds; anything else is retried with backoff and each attempt reuses the same `eventId`.

Wallet to developer service (outbox forward):

```
POST {developer_service}/internal/events
X-API-Key: <internal key>
Content-Type: application/json
X-Premisave-Event-Id: evt_7c1d9a
X-Premisave-Timestamp: 1760468732
X-Premisave-Signature: sha256=<hex>
```

```json
{
  "eventId": "evt_7c1d9a",
  "type": "payment.succeeded",
  "createdAt": "2026-10-14T19:05:32Z",
  "livemode": true,
  "merchantId": "mer_4821",
  "appId": "app_93f0",
  "targets": [ { "url": "https://merchant.example.com/hooks/premisave", "source": "REGISTERED", "registrationId": "cbr_30de" } ],
  "data": {
    "id": "pay_81ae",
    "merchantReference": "ORDER-1042",
    "status": "SUCCESS",
    "amount": "25.00",
    "currency": "USD",
    "nativeAmount": "3225.00",
    "nativeCurrency": "KES",
    "fxRate": "129.00",
    "fee": "0.50",
    "result": { "code": "SUCCESS", "description": "Payment completed" },
    "providerReference": "RCA71X5MJ4",
    "version": 3,
    "updatedAt": "2026-10-14T19:05:32Z"
  }
}
```

Developer service to merchant URL (what the merchant's server sees):

```
POST https://merchant.example.com/hooks/premisave
Content-Type: application/json
X-Premisave-Event-Id: evt_7c1d9a
X-Premisave-Timestamp: 1760468733
X-Premisave-Signature: sha256=<hex of HMAC-SHA256(appSigningSecret, timestamp + "." + rawBody)>
```

Body: the same object without `targets`. Receivers should verify the signature, reject timestamps older than five minutes, deduplicate by `eventId`, and ignore an event whose `data.version` is lower than one already processed for that object.

Event types:

| Group | Types |
|:-|:-|
| Payments | `payment.requires_action`, `payment.pending`, `payment.succeeded`, `payment.failed`, `payment.timed_out`, `payment.cancelled` |
| Payouts | `payout.pending`, `payout.requires_action`, `payout.succeeded`, `payout.failed`, `payout.timed_out`, `payout.batch.completed` |
| Refunds | `refund.succeeded`, `refund.failed` |
| Disputes | `dispute.opened`, `dispute.won`, `dispute.lost` |
| Subscriptions | `subscription.created`, `subscription.activated`, `subscription.past_due`, `subscription.paused`, `subscription.cancelled`, `customer.payment_method.activated` |
| Invoices | `invoice.upcoming`, `invoice.paid`, `invoice.payment_failed` |
| Other | `beneficiary_check.completed`, `settlement.succeeded`, `settlement.failed`, `balance.low`, `callback.test`, `callback.verification` |

### 11.13 Validation callback (synchronous)

For registrations of type `VALIDATION`. Sent while a customer payment is still in flight so the merchant can accept or reject it, for example by checking an invoice number.

Request to the merchant:

```json
{
  "validationId": "val_2c41",
  "type": "payment.validation",
  "livemode": true,
  "accountReference": "PS1234-INV001",
  "amount": "100.00",
  "currency": "USD",
  "nativeAmount": "12900.00",
  "nativeCurrency": "KES",
  "payer": { "name": "JANE DOE", "phone": "2547****5678" },
  "deadlineMs": 3000
}
```

Merchant answers within `deadlineMs` with `200`:

```json
{ "result": "ACCEPT" }
```

or

```json
{ "result": "REJECT", "reason": "Invoice already paid" }
```

Anything else, a timeout, or an error is treated as the registration's `onTimeout` setting (default `REJECT`). The outcome is recorded on the payment (`result.code: DECLINED` when rejected).

## 12. Reports

Headers: Standard. All reports are scoped to the environment and merchant (and to the app when `appId` is given).

### 12.1 Daily report

`GET /reports/daily`

Query parameters: `date` (default today, UTC), `appId`, `format` (`JSON` or `CSV`).

Response `200`:

```json
{
  "success": true,
  "message": "Daily report",
  "data": {
    "date": "2026-10-14",
    "currency": "USD",
    "collections": { "count": 58, "volume": "1480.00", "fees": "27.40", "failed": 6, "byMethod": { "MOBILE_MONEY": "900.00", "CARD": "580.00" } },
    "payouts": { "count": 21, "volume": "630.00", "fees": "8.20", "failed": 1 },
    "refunds": { "count": 2, "volume": "35.00" },
    "settlements": { "count": 1, "volume": "500.00" },
    "net": "315.40"
  }
}
```

### 12.2 Summary report

`GET /reports/summary`

Query parameters: `from`, `to` (required), `groupBy` (`DAY`, `WEEK`, `MONTH`, `METHOD`, `APP`), `appId`.

Response `200`: `data.rows` is an array of the same blocks as 12.1, one per group, plus `data.totals`. Errors: `400 VALIDATION_FAILED`.

### 12.3 Reconciliation report

`GET /reports/reconciliation`

Compares the merchant ledger against provider records for a period and lists differences.

Query parameters: `from`, `to` (required, maximum 31 days), `provider`, `format`.

Response `200`:

```json
{
  "success": true,
  "message": "Reconciliation report",
  "data": {
    "from": "2026-10-01T00:00:00Z",
    "to": "2026-10-14T23:59:59Z",
    "matched": 412,
    "exceptions": [
      { "type": "MISSING_AT_PROVIDER", "objectId": "po_3b70", "amount": "20.00", "note": "Marked SUCCESS here, not found in provider statement" },
      { "type": "AMOUNT_MISMATCH", "objectId": "pay_80f1", "amount": "25.00", "providerAmount": "24.00" }
    ],
    "totals": { "ledger": "12480.00", "provider": "12479.00", "difference": "1.00", "currency": "USD" }
  }
}
```

Errors: `400 VALIDATION_FAILED`.

## 13. Sandbox helpers

Exist under `{{base_sandbox}}` only. Calling them under `/live` returns `404 NOT_FOUND`. Headers: Standard (`X-App-Environment: sandbox`); no real money or provider is touched.

Sandbox behaviour in general: payments and payouts stay `PENDING` or `REQUIRES_ACTION` until simulated (13.1, 13.2), unless the amount follows the magic-value table below, in which case they resolve automatically a few seconds after creation.

| Amount (USD, last digits) | Outcome |
|:-|:-|
| `x.00` | `SUCCESS` |
| `x.01` | `FAILED`, `INSUFFICIENT_FUNDS` |
| `x.02` | `FAILED`, `CUSTOMER_CANCELLED` |
| `x.03` | `TIMED_OUT`, `CUSTOMER_TIMEOUT` |
| `x.04` | `FAILED`, `DECLINED` |
| `x.05` | stays `PENDING` until simulated |
| any other ending | `SUCCESS` |

For payouts, `x.02` fails with `INVALID_DESTINATION` instead of `CUSTOMER_CANCELLED`, and `x.03` times out with `TIMED_OUT`. The ending is the last two digits of the USD amount.

### 13.1 Simulate payment outcome

`POST /payments/{id}/simulate`

Request:

```json
{ "outcome": "FAILED", "resultCode": "INSUFFICIENT_FUNDS", "delaySeconds": 0 }
```

`outcome`: `SUCCESS`, `FAILED`, `TIMED_OUT`, `CANCELLED`. `resultCode` is any normalised code from 1.8. Fires the same events and callbacks a real provider result would.

Response `200`: Payment in the new status. Errors: `404 NOT_FOUND`, `409 INVALID_STATE` (already final).

### 13.2 Simulate payout outcome

`POST /payouts/{id}/simulate`

Request and response as 13.1 for payouts; `outcome`: `SUCCESS`, `FAILED`, `TIMED_OUT`, `REVERSED`. Releases or finalises the hold exactly as live would.

### 13.3 Create test dispute

`POST /disputes`

Request:

```json
{ "paymentId": "pay_92bf", "amount": "10.00", "reason": "PRODUCT_NOT_RECEIVED" }
```

Response `201`: Dispute (2.16) and a `dispute.opened` event. Errors: `404 NOT_FOUND`, `409 INVALID_STATE` (payment not `SUCCESS`).

### 13.4 Advance subscription clock

`POST /subscriptions/{id}/advance-clock`

Request: `{ "to": "2026-11-14T00:00:00Z" }` (must be in the future relative to the subscription's clock, maximum one year ahead). Runs billing for every period boundary crossed, so invoices, retries and events can be exercised without waiting.

Response `200`: Subscription with the advanced `currentPeriod`. Errors: `400 VALIDATION_FAILED`, `404 NOT_FOUND`.

### 13.5 Credit sandbox balance

`POST /balance/credit`

Request: `{ "amount": "1000.00", "currency": "USD" }` (maximum 100000 USD per call).

Response `200`: Balance (2.19). Errors: `400 VALIDATION_FAILED`.

### 13.6 Send test event

`POST /events/test`

Request: `{ "eventType": "payout.succeeded", "registrationId": "cbr_30de" }` (`registrationId` optional; defaults to all matching registrations). Sends a sample event.

Response `202`: `{ "success": true, "message": "Test event queued", "data": { "eventId": "evt_test_02" } }`.

### 13.7 Reset sandbox

`POST /reset`

Request: `{ "confirm": "RESET", "keep": ["callbacks", "plans"] }`. Deletes sandbox payments, payouts, refunds, subscriptions, customers, events and resets the balance to zero. `keep` may list `callbacks`, `plans`, `customers`.

Response `200`: `{ "success": true, "message": "Sandbox reset", "data": { "deleted": { "payments": 120, "payouts": 40, "events": 640 } } }`. Errors: `400 VALIDATION_FAILED` (missing confirmation).

## 14. Endpoint index

| # | Method | Path (relative) | Section | Idempotency-Key |
|:-|:-|:-|:-|:-|
| 1 | POST | `/merchants` (provisioning) | 3.1 | no |
| 2 | GET | `/merchants/{merchantId}` (provisioning) | 3.2 | no |
| 3 | PUT | `/merchants/{merchantId}/status` (provisioning) | 3.3 | no |
| 4 | PUT | `/merchants/{merchantId}/config` (provisioning) | 3.4 | no |
| 5 | POST | `/merchants/{merchantId}/apps` (provisioning) | 3.5 | no |
| 6 | GET | `/merchants/{merchantId}/apps` (provisioning) | 3.6 | no |
| 7 | PUT | `/merchants/{merchantId}/apps/{appId}` (provisioning) | 3.7 | no |
| 8 | GET | `/payment-methods` | 4.1 | no |
| 9 | POST | `/quotes` | 4.2 | no |
| 10 | POST | `/payments` | 5.1 | yes |
| 11 | GET | `/payments` | 5.2 | no |
| 12 | GET | `/payments/{id}` | 5.3 | no |
| 13 | POST | `/payments/{id}/cancel` | 5.4 | no |
| 14 | POST | `/payment-links` | 5.5 | yes |
| 15 | GET | `/payment-links` | 5.6 | no |
| 16 | GET | `/payment-links/{id}` | 5.7 | no |
| 17 | PUT | `/payment-links/{id}` | 5.8 | no |
| 18 | POST | `/payments/account-references` | 5.9 | yes |
| 19 | POST | `/payouts` | 6.1 | yes |
| 20 | POST | `/payouts/bulk` | 6.2 | yes |
| 21 | GET | `/payouts` | 6.3 | no |
| 22 | GET | `/payouts/{id}` | 6.4 | no |
| 23 | GET | `/payouts/batches/{batchId}` | 6.5 | no |
| 24 | POST | `/beneficiaries/verify` | 7.1 | no |
| 25 | POST | `/beneficiaries/lookup-account` | 7.2 | no |
| 26 | GET | `/beneficiaries/checks/{checkId}` | 7.3 | no |
| 27 | POST | `/plans` | 8.1 | yes |
| 28 | GET | `/plans` | 8.2 | no |
| 29 | GET | `/plans/{planId}` | 8.3 | no |
| 30 | PUT | `/plans/{planId}` | 8.4 | no |
| 31 | POST | `/customers` | 8.5 | no |
| 32 | GET | `/customers` | 8.6 | no |
| 33 | GET | `/customers/{customerId}` | 8.7 | no |
| 34 | POST | `/customers/{customerId}/payment-methods` | 8.8 | yes |
| 35 | POST | `/subscriptions` | 8.9 | yes |
| 36 | GET | `/subscriptions` | 8.10 | no |
| 37 | GET | `/subscriptions/{id}` | 8.11 | no |
| 38 | POST | `/subscriptions/{id}/pause` | 8.12 | no |
| 39 | POST | `/subscriptions/{id}/resume` | 8.13 | no |
| 40 | POST | `/subscriptions/{id}/cancel` | 8.14 | no |
| 41 | PUT | `/subscriptions/{id}` | 8.15 | no |
| 42 | GET | `/subscriptions/{id}/invoices` | 8.16 | no |
| 43 | POST | `/invoices/{id}/retry` | 8.17 | yes |
| 44 | POST | `/refunds` | 9.1 | yes |
| 45 | GET | `/refunds` | 9.2 | no |
| 46 | GET | `/refunds/{id}` | 9.3 | no |
| 47 | GET | `/disputes` | 9.4 | no |
| 48 | GET | `/disputes/{id}` | 9.5 | no |
| 49 | POST | `/disputes/{id}/evidence` | 9.6 | yes |
| 50 | GET | `/balance` | 10.1 | no |
| 51 | GET | `/statement` | 10.2 | no |
| 52 | GET | `/transactions` | 10.3 | no |
| 53 | GET | `/transactions/{id}` | 10.4 | no |
| 54 | POST | `/settlements` | 10.5 | yes |
| 55 | GET | `/settlements` | 10.6 | no |
| 56 | GET | `/settlements/{id}` | 10.7 | no |
| 57 | PUT | `/settlements/schedule` | 10.8 | no |
| 58 | POST | `/transfers` | 10.9 | yes |
| 59 | GET | `/events` | 11.1 | no |
| 60 | GET | `/events/{eventId}` | 11.2 | no |
| 61 | POST | `/events/{eventId}/resend` | 11.3 | no |
| 62 | POST | `/callbacks/registrations` | 11.4 | no |
| 63 | GET | `/callbacks/registrations` | 11.5 | no |
| 64 | GET | `/callbacks/registrations/{id}` | 11.6 | no |
| 65 | PUT | `/callbacks/registrations/{id}` | 11.7 | no |
| 66 | DELETE | `/callbacks/registrations/{id}` | 11.8 | no |
| 67 | POST | `/callbacks/registrations/{id}/verify` | 11.9 | no |
| 68 | POST | `/callbacks/registrations/{id}/test` | 11.10 | no |
| 69 | PUT | `/callbacks/registrations/{id}/status` | 11.11 | no |
| 70 | GET | `/reports/daily` | 12.1 | no |
| 71 | GET | `/reports/summary` | 12.2 | no |
| 72 | GET | `/reports/reconciliation` | 12.3 | no |
| 73 to 79 | POST | Sandbox helpers (sandbox only) | 13.1 to 13.7 | no |

Counting: 7 provisioning calls, 65 operations (rows 8 to 72) available under both `/live` and `/sandbox` (130 routes), and 7 sandbox-only helpers, for 144 routes in total.

