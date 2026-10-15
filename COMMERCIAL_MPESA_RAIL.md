# Commercial API, Phase 2 step 1: live M-Pesa rail

This adds live M-Pesa to the commercial API. A merchant can now take a payment from a customer's phone and pay money out to a phone number, using the same endpoints as Phase 1. Nothing in Phase 1 was rewritten: the rail is a new class that plugs into `RailRegistry`, which already collects every `PaymentRail`.

## What it does

| Request | What happens |
| --- | --- |
| `POST /internal/commercial/v1/live/payments` with a mobile money method | Sends an STK push to `customer.phone`. The payment becomes `REQUIRES_ACTION` with a `CUSTOMER_PROMPT` next action. The customer enters their PIN and Safaricom calls back with the result. |
| `POST /internal/commercial/v1/live/payouts` to a `MOBILE_MONEY` destination | Sends a B2C payment to `destination.phoneNumber`. The payout stays `PENDING` until Safaricom posts the result. |
| `GET /internal/commercial/v1/live/capabilities` | Now lists M-Pesa for Kenya in KES, for both collection and payout. |

Only the live environment is served. Sandbox requests keep using the simulated sandbox rail.

## How results come back

Safaricom is given three callback addresses per transaction:

```
{base}/payments/commercial/rail/stk/{transactionId}/{signature}
{base}/payments/commercial/rail/b2c-result/{transactionId}/{signature}
{base}/payments/commercial/rail/b2c-timeout/{transactionId}/{signature}
```

The signature is an HMAC of the kind of callback and the transaction id, made with `commercial.mpesa.callback-secret`. A callback with a wrong signature gets 403 and nothing is parsed. The paths deliberately avoid the word "mpesa" because Safaricom's sandbox rejects callback URLs that contain it.

These addresses are separate from your existing wallet callbacks, which credit user wallets. Commercial transactions never touch the wallet callback code.

## Result mapping

| Safaricom code | Commercial result |
| --- | --- |
| STK `0` | `SUCCESS`, M-Pesa receipt stored as the provider reference |
| STK `1032` | `FAILED`, `CUSTOMER_CANCELLED` |
| STK `1037` | `TIMED_OUT`, `CUSTOMER_TIMEOUT` |
| STK `1` | `FAILED`, `INSUFFICIENT_FUNDS` |
| STK `2001` | `FAILED`, `DECLINED` (wrong PIN) |
| B2C `0` | `SUCCESS`, M-Pesa transaction id stored as the provider reference |
| B2C queue timeout | `TIMED_OUT`: Safaricom did not process it, so the hold is released |
| Any other code | `FAILED`, `PROVIDER_ERROR`, with Safaricom's own code and text kept in `result.providerCode` and `result.providerDescription` |

Codes whose meaning is not certain are not guessed at; they surface as `PROVIDER_ERROR` with Safaricom's wording.

## Safety behaviour

- A payment success whose amount differs from the amount asked for is logged as an error and not applied.
- If the STK push request is sent but no answer comes back, the payment stays open and waits for the callback instead of being failed. If it were failed while the customer paid, the money would arrive with nothing to credit it to.
- The same rule applies to payouts. If the B2C request may have been received, the payout stays `PENDING` and the held money stays held until the result arrives. Only a request that never left the server (no token, connection refused) fails at once, with `PROVIDER_UNAVAILABLE`.
- `MpesaStkReconciler` asks Safaricom about payments whose callback has not arrived, at growing intervals starting 60 seconds after the payment was created. It settles them from the answer.
- A payout that has had no result for 30 minutes is logged as an error so someone can check it in the M-Pesa portal. There is no automatic lookup for payouts in this step.
- The B2C `OriginatorConversationID` is the transaction id, so a payout can never be sent twice for one transaction.

## Settings

Add to `application.yml` (already in the supplied file):

```yaml
commercial:
  mpesa:
    enabled: ${COMMERCIAL_MPESA_ENABLED:false}
    callback-base-url: ${COMMERCIAL_MPESA_CALLBACK_BASE_URL:}
    callback-secret: ${COMMERCIAL_MPESA_CALLBACK_SECRET:}
    collection-max-kes: 250000
    poll-after-seconds: 60
    poll-interval-ms: 30000
    http-timeout-seconds: 15
```

The rail is off by default. When it is turned on, the service refuses to start unless the base URL is an https address and the secret is at least 16 characters. It also needs the existing `mpesa.daraja.*` settings: shortcode, passkey, certificate path and the `b2c` initiator details.

## Two edits to existing files

`SecurityConfig` must let Safaricom reach the new callbacks without a token, and `WebConfig` must keep them out of the rate limiter. In the list of public callback paths in `SecurityConfig`, and in the `excludePathPatterns` list in `WebConfig`, add one line:

```java
"/payments/commercial/rail/**",
```

Nothing else in your project changes. The raw body logging filter (`CallbackRequestLoggingFilter`) is left alone: it matches exact paths, and these callbacks read their body as plain text, so they cannot fail on a payload shape change.

## Trying it with Safaricom's sandbox

1. Set `mpesa.daraja.environment` to `sandbox` and use your sandbox shortcode, passkey and B2C initiator.
2. Expose the service over https (for example with ngrok) and set `COMMERCIAL_MPESA_CALLBACK_BASE_URL` to that address.
3. Set `COMMERCIAL_MPESA_ENABLED=true` and a `COMMERCIAL_MPESA_CALLBACK_SECRET`.
4. Create a live payment for a merchant app in the `production` environment, with `customer.phone` set to a Safaricom sandbox test number.

## Limits of this step

- Payouts to paybills and tills (B2B) are refused with a clear message. Only phone number payouts work.
- A prompt already sent to a customer cannot be recalled, so cancelling an M-Pesa payment is refused and it expires on its own.
- Refunds and reversals are not available yet.
- Amounts must be whole shillings in KES.
- The callback handling and Daraja calls were compiled and their mapping logic unit tested, but they have not been run against Safaricom or a real database. Run the sandbox steps above before using real money.
