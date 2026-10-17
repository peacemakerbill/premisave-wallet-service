# Developer service split

The commercial API is now two services.

* **premisave-developer-service** (new, port 8091) owns developers, apps in sandbox and production, API keys and secrets, and the public `/v1` entry that merchants call.
* **premisave-wallet-service** keeps merchants, onboarding, the ledger, the payment rails and everything that moves money. It no longer manages apps.

Status of this delivery: both services were compiled with `javac` against hand written stubs of Spring, Jackson and the Mongo types. The developer service has 315 tests and the wallet has 457, all passing in that setup. Maven was not run (Maven Central is not reachable from where this was built), and nothing ran against a real MongoDB, a real SMTP server, a real wallet instance or a real Google, Facebook or GitHub. Run `mvn clean test` in both projects before relying on it.

## 1. Who owns what

| Concern | Owner |
|:-|:-|
| Developer accounts, passwords, sign in, sign out, JWTs | Developer service |
| Email confirmation, password reset, account emails | Developer service |
| Sign in with Google, Facebook and GitHub | Developer service |
| Apps (sandbox and production), app suspension, callback URL | Developer service |
| API keys and secrets, rotation, revocation | Developer service |
| Per app method and limit narrowing | Developer service |
| Sandbox test merchant numbers | Developer service (`developer.apps.test-merchant-numbers`) |
| One live app per Premisave wallet | Developer service |
| Public `/v1/{live\|sandbox}/**` entry, key authentication, rate limiting | Developer service |
| Staff tooling to suspend an app or disable a developer | Developer service (`/internal/admin`) |
| Merchants and merchant numbers (`PRM-` and ten digits) | Wallet |
| Onboarding application, documents on Drive, review, approval | Wallet |
| Merchant status, enabled methods, merchant limits | Wallet |
| Payments, payouts, ledger, settlements, billing, refunds, disputes, subscriptions, rails | Wallet |
| Premisave wallet lookup | Wallet (`GET /internal/commercial/v1/wallets/resolve`) |

The wallet is never called by merchants. Only the developer service calls it, with the internal key.

## 2. Request flow

1. The client calls `https://<developer-service>/v1/live/payments` (or `/v1/sandbox/...`) with its key and secret, either as HTTP Basic or as `X-Client-Key` and `X-Client-Secret`.
2. The developer service checks the path. Dot segments, empty segments, encoded dots and slashes, backslashes, `;`, whitespace and control characters are refused. An empty remainder is `404 NOT_FOUND`.
3. It authenticates the key. The secret is hashed with HMAC-SHA256 and a server side pepper and compared in constant time. An unknown key, a wrong secret, a revoked key and an expired rotating key all return the same `401 INVALID_API_KEY`, and an unknown key still pays for one hash so timing does not reveal which keys exist.
4. It checks that the key's environment matches the path. A sandbox key on `/v1/live` is `403 ENVIRONMENT_MISMATCH`.
5. It applies a per key rate limit (fixed one minute window held in memory, default 600).
6. It loads the app and its developer. A disabled developer is `403 ACCOUNT_DISABLED`.
7. It forwards to `{wallet}/internal/commercial/v1/{live|sandbox}{rest}?query` with the internal `X-API-Key`, an optional request signature, the allowed request headers and the context headers below. The client's key and secret are never forwarded.
8. The wallet answers and the developer service passes the body, status and a small set of response headers back, adding `X-RateLimit-Remaining`.

### Context headers

The developer service tells the wallet who is calling and what that app may do. The names are the same literals in both code bases and a test in each asserts them.

| Header | Value |
|:-|:-|
| `X-Merchant-Id` | Merchant that owns the app |
| `X-App-Id` | App id |
| `X-App-Environment` | `production` or `sandbox` |
| `X-App-Status` | `ACTIVE` or `SUSPENDED` |
| `X-App-Version` | Change counter (a long) that grows with every app change |
| `X-App-Methods` | Optional. Sorted comma list of the methods the app may use, or `none`. Absent means the same methods as the merchant |
| `X-App-Limits` | Optional. For example `singleTransactionUsd=100.00,monthlyVolumeUsd=20000,dailyPayoutCount=5`. Only limits that are set appear. Absent means no extra app limits |

The context headers are not covered by the request signature. Their protection is the internal key and network isolation: the wallet must not be reachable from the internet, only from the developer service.

On the wallet, a suspended app can still read but every non read call is refused with `403 APP_SUSPENDED`. The app's methods and limits narrow what the merchant already has and can never widen it.

## 3. Background jobs and the snapshot

The settlement scheduler and the billing engine run with no request behind them, so they cannot read headers. The wallet therefore keeps one `commercial_app_snapshots` document per app.

* Every request carries the latest app context, and the wallet records it best effort (a failure to record never fails the request).
* The developer service also pushes each change with `PUT /internal/commercial/v1/apps/{appId}/context`. Every app change marks the app `contextSyncPending` in the developer database, and a job that runs every 30 seconds (`developer.apps.sync-interval-ms`) retries any app still pending until the wallet confirms.
* The snapshot with the highest `X-App-Version` wins, an app cannot move to another merchant, and a live request is always authoritative over the snapshot.

## 4. App rules that moved to the developer service

* A production app needs a `premisaveWalletAccount`, which may be left out when the caller applied for the business and its application names a wallet; the business's own `PRM-` number is used, and `merchantNumber` may be left out. The developer service resolves it through the wallet. The wallet must exist (`404 WALLET_NOT_FOUND`) and not be frozen (`422 WALLET_NOT_USABLE`).
* One live app per Premisave wallet (`409 WALLET_ALREADY_LINKED`), enforced by a pre-check and a unique sparse index.
* A sandbox app uses one of the configured test merchant numbers (default `PRM-TEST-0001` to `PRM-TEST-0005`, never anything shaped like `PRM-` and ten digits) and refuses a wallet.
* A production app is refused with `403 LIVE_NOT_ENABLED` while the merchant's production status is `PENDING_APPROVAL` (approval of the onboarding application switches it on). Any other status that is not `ACTIVE` in that environment is `403 MERCHANT_SUSPENDED`.
* App methods must be a subset of the merchant's methods (production only), and app limits must fit within the merchant's limits.
* The callback URL must be https. http is allowed for sandbox apps only. No user info and no fragment.
* At most 10 apps per environment for each business and 5 active keys per app (`422 LIMIT_REACHED`).
* A developer can suspend and reactivate their own app. A suspension placed by staff (`suspendedBy: ADMIN`) cannot be lifted by the developer.
* Rotating a key keeps the old key working as `ROTATING` for a grace period (default 24 hours, `0` revokes at once).

## 5. Businesses from the developer side

A developer account is one person, and one account can apply for many businesses. Each business is a wallet merchant with its own merchant number, its own onboarding application and its own apps:

1. `POST /api/v1/businesses` on the developer service creates the merchant in the wallet (`developerAccountId` carries the developer id) and adds the merchant id to the developer's `merchantIds`. The wallet refuses the same business twice for one account: the natural key is the developer id plus the business name and country, normalised to lower case letters and digits, so `Acme Ltd.` and `acme ltd` in `KE` are one business. A repeat answers `409 DUPLICATE_REQUEST` with the existing `merchantId`. If the call times out after the wallet created the merchant, the next attempt gets that answer and the developer service links the merchant it already has instead of making a second one.
2. The wallet is the source of truth for which businesses an account has: `GET /internal/commercial/v1/merchants?developerAccountId=` lists them. `GET /api/v1/businesses` asks for that list and adds any business the developer document does not know yet, so a lost write repairs itself. The developer service caps an account at `developer.businesses.max-per-developer` (`DEVELOPER_MAX_BUSINESSES`, default 10).
3. Onboarding is driven through `/api/v1/businesses/{businessId}/application/**` on the developer service, which forwards to the wallet's `/internal/commercial/v1/merchants/{merchantId}/application...` with `X-Actor-Id` set to the developer id. The business must be on the caller's account, otherwise `404 BUSINESS_NOT_FOUND`, the same answer as for a business that does not exist. A document is sent to the developer service as form data (`file` and `type`, up to 5 MB) and converted to the wallet's JSON upload.
4. An app names its business with `businessId`. It can be left out while the account has exactly one.
5. Staff review stays on the wallet's staff endpoints, unchanged.
6. The application form carries the wallet account to link (`walletAccountNumber`), and whether the developer applies on behalf of the business (`onBehalf`, `applicantRole`, and an `AUTHORISATION_LETTER` document). The wallet stores the link as `linkedWalletId` and `linkedWalletAccount` on the application and checks that the wallet exists and is not frozen. The application summary in the merchant view carries `walletAccount` and `contactEmail`; the developer service uses them to address the transfer code and removes them before anything goes to a developer.
7. The wallet owner follows the application from the wallet: `GET /wallet/merchant-applications`, `GET /wallet/merchant-applications/{applicationId}` and `GET /wallet/merchant-numbers`, with the owner's login token. The merchant number is hidden until the application is approved, the tax pin is masked and director ID numbers are left out.
8. **One business, more than one account.** A business is applied for once, by one account (`merchantIds`). Another account can go live with the same merchant number when the owner agrees, in two ways, both confirmed with a six digit code emailed to the address linked to the business (the wallet account on the application, else the business contact email): moving a production app (`POST /api/v1/apps/{appId}/transfer`), or claiming the approved business by its number (`POST /api/v1/transfers/claim`). Either gives the other account use of the business in `sharedMerchantIds`: it can create production apps for it, but cannot open its application. A moved app keeps its merchant number and wallet link and all its API keys are revoked. The original account keeps the business and its other apps. The code lasts 15 minutes, works once, five wrong tries close the request, and requests are limited to one code a minute and ten an hour. Requests live in `transfer_requests`, with a TTL on `purgeAt`. The code is stored as an HMAC under `API_KEY_PEPPER`, so changing the pepper also ends any open transfer. See `DEVELOPER_API.md` sections 2.4 and 2.5.

## 6. Failure behaviour

| Situation | Result |
|:-|:-|
| Wallet unreachable | `503 UPSTREAM_UNAVAILABLE` |
| Wallet does not answer in time | `504 UPSTREAM_TIMEOUT`. The payment may still have been created. Repeat the call with the same `Idempotency-Key` to learn the outcome |
| Wallet error envelope | Translated to the developer service's own error code of the same name, otherwise chosen by HTTP status |
| Developer service down | No merchant traffic at all. Run it with at least two instances behind a load balancer. The rate limiter is per instance, so the effective limit is the configured value times the instance count |
| Wallet down while an app changes | The change is saved, the app stays `contextSyncPending`, and the 30 second job pushes it when the wallet is back |

## 7. Configuration

Developer service (`developer.*`):

| Property | Environment variable | Default | Notes |
|:-|:-|:-|:-|
| `jwt.secret` | `DEVELOPER_JWT_SECRET` | none | Required, at least 32 characters |
| `keys.pepper` | `API_KEY_PEPPER` | none | Required, at least 16 characters. Losing or changing it invalidates every key |
| `wallet.base-url` | `WALLET_SERVICE_URL` | `http://localhost:8084` | |
| `wallet.live-url`, `wallet.sandbox-url` | none | falls back to base-url | Only if live and sandbox are separate wallet deployments |
| `wallet.api-key` | `INTERNAL_API_KEY` | none | Required. Same value as the wallet's internal key |
| `wallet.signing-secret` | `WALLET_SIGNING_SECRET` | empty | Set when the wallet has `commercial.signing.enabled=true`; it must be one of the wallet's `commercial.signing.secrets` |
| `wallet.timeout-seconds` | none | 30 | |
| `proxy.rate-limit-per-minute` | none | 600 | Per key |
| `proxy.max-body-bytes` | none | 1048576 | Public API bodies |
| `proxy.max-upload-bytes` | none | 12582912 | Onboarding document upload |
| `apps.max-per-environment` | none | 10 | |
| `apps.test-merchant-numbers` | none | five defaults | |
| `businesses.max-per-developer` | `DEVELOPER_MAX_BUSINESSES` | 10 | Businesses one account may apply for |
| `apps.sync-interval-ms` | none | 30000 | |
| `security.max-failed-logins`, `lock-minutes` | none | 5, 15 | |
| `security.password-min-length` | none | 10 | |
| `security.pbkdf2-iterations` | none | 210000 | |
| `internal.api-key` | `DEVELOPER_INTERNAL_API_KEY` | empty | Guards `/internal/admin`. Empty means those endpoints refuse every call |
| `cors.allowed-origins` | `DEVELOPER_PORTAL_ORIGINS` | empty | Portal API only. CORS is never opened on `/v1` |
| `mail.portal-base-url` | `DEVELOPER_PORTAL_URL` | `http://localhost:3000` | Emails link to `{this}/verify-email?token=` and `/reset-password?token=` |
| `mail.from` | `DEVELOPER_MAIL_FROM` | empty | Sender address |
| `mail.verification-ttl-hours`, `reset-ttl-minutes` | none | 24, 60 | |
| `mail.resend-cooldown-seconds`, `max-per-hour` | none | 60, 5 | Per account and link kind |
| `mail.require-verified-for-business` | none | true | Confirmed email needed to create a business |
| `mail.log-links` | `DEVELOPER_MAIL_LOG_LINKS` | false | Development only, links are credentials |
| `mail.notify-sign-in` | `DEVELOPER_MAIL_NOTIFY_SIGN_IN` | true | Email the owner on every successful sign in |
| `spring.mail.*` | `MAIL_HOST`, `MAIL_PORT`, `MAIL_USERNAME`, `MAIL_PASSWORD` | host `smtp.gmail.com`, port `587`, username and password empty | An empty username means no email is sent. Same capital-letter style as the wallet. `DEVELOPER_MAIL_STARTUP_CHECK=true` logs whether mail works at every start |
| `oauth.google.client-id` | `GOOGLE_CLIENT_ID` | empty | Google is on with the client ID alone, because ID tokens are checked against Google and this ID |
| `oauth.facebook`, `oauth.github` `.client-id`, `.client-secret` | `FACEBOOK_CLIENT_ID`, `FACEBOOK_CLIENT_SECRET`, `GITHUB_CLIENT_ID`, `GITHUB_CLIENT_SECRET` | empty | A provider is on with both set. `verify-url`, `token-url`, `user-info-url` (and `emails-url` for GitHub) can be overridden |
| server port | `SERVER_PORT` | 8091 | |
| database | `MONGODB_URI` | `mongodb://localhost:27017/premisave_developer` | A separate database from the wallet |

Wallet: the `commercial.merchants` block is removed. Nothing is added. Keep `commercial.events.developer-service-url` (`DEVELOPER_SERVICE_URL`) empty for now (section 9).

## 8. Data migration

The old `merchant_apps` collection in the wallet database is no longer read or written. No real apps or keys existed yet, so there is nothing to migrate: recreate any test apps through the developer service and drop `merchant_apps` from the wallet database when convenient. The new wallet collection `commercial_app_snapshots` fills itself.

**Transfers.** The developer database gets a `transfer_requests` collection and a `sharedMerchantIds` field on `developers` at first use. No migration is needed, and the wallet needs no new index: `linkedWalletAccount` on `merchant_applications` is indexed by the new field, and applications saved before the change simply have no link until the form is saved again with a `walletAccountNumber`, which the applicant can do while the application is a draft or returned. An application that is already in review or decided without a link cannot be edited, so its wallet owner cannot see it. For those, a transfer code goes to the business contact email.

**One business per account to many.** The wallet's `merchant_profiles` collection had a unique index on `developerAccountId`. Drop it in the wallet database, or a second business for the same account fails with a duplicate key error: `db.merchant_profiles.dropIndex("developerAccountId")`. The wallet creates the new unique, sparse index `developer_business_key` (`developerAccountId` and `applicationKey`) at startup. Merchants created before the change have no `applicationKey`, so they are not covered by it until they are saved again; the duplicate check by name and country still sees them because the key is computed on the fly. Developer accounts that stored a single `merchantId` keep it, and it counts as the first business. The old `/api/v1/merchant` and `/api/v1/merchant/application/**` routes are gone, and the error codes `MERCHANT_EXISTS` and `MERCHANT_REQUIRED` are now `BUSINESS_EXISTS` and `BUSINESS_REQUIRED`.

Existing merchants in the wallet have no developer account. A developer who signs up and wants to use an existing merchant needs the merchant id stored on their developer document; there is no endpoint for that in this slice.

## 9. Not in this slice

* Event and webhook delivery to merchant URLs. The wallet's outbox dispatcher posts to `{developer-service-url}/internal/events`, which the developer service does not have yet. Leave `DEVELOPER_SERVICE_URL` empty so events wait in the outbox. With a value set, the dispatcher would fail and retry up to 12 times per event.
* `POST /internal/validations` (C2B validation callback) and `POST /internal/callbacks/verify` (live callback registration check) on the developer service.
* A staff portal. Staff use `/internal/admin` directly.
* A deployment where the wallet runs as separate live and sandbox services needs the merchant to exist in both. Nothing here creates it twice.
* Request signing does not cover the context headers (section 2).

## 10. Account emails and social sign in

Added after the first cut of the split. Endpoint details are in `DEVELOPER_API.md` sections 1.6 to 1.10.

New collections in the developer database: `email_tokens` (confirmation and reset links, hashed), `revoked_tokens` (signed out tokens, removed by a TTL index when the token would have expired anyway) and `oauth_identities` (one row per provider account, `_id` is `provider:subject`). The `developers` document gains `emailVerified`, `emailVerifiedAt`, `authProviders`, the names `firstName`, `middleName` and `lastName`, a unique sparse `username` (with `usernameChangedAt`), the sign up details `country`, `phoneNumber`, `companyName`, `termsAcceptedAt` and `marketingOptIn`, and the address fields `addressLine1`, `addressLine2`, `city`, `stateOrRegion`, `postalCode` and `timezone` (accounts made before this have none of them: names and username are filled in the next time the developer signs in, and `profileComplete` reads false until names and a country exist; `developers.username` needs its unique index, created at startup by auto-index-creation); `passwordHash` may now be empty for an account created through a provider. Documents written before this change have no `emailVerified`, which reads as false: existing developers must confirm their address (or the gate can be switched off) before they can create a business.

How it works:

* **Sign out.** Every token has a random id. Signing out stores the id until the token expires; signing out everywhere raises the account's token version. Both are checked on every portal call.
* **Links.** A confirmation or reset link carries 32 random bytes. Only a keyed hash (HMAC-SHA256 with the API key pepper) is stored, so a copy of the database holds no usable link. Redeeming deletes the row in one atomic step, so two requests cannot both succeed with one link. Confirmation links last 24 hours and reset links 60 minutes. Per account and kind: one link per 60 seconds and 5 per hour.
* **No account enumeration by answer.** Asking for a reset or a new confirmation link for an unknown address returns the same answer as for a known one. Sending happens on a background pool, so the request does not wait for the mail server. Mail failures are logged and never reach the caller.
* **Reset.** The password policy is checked before the link is spent. A successful reset ends every session, clears a lockout, counts the address as confirmed and sends a "password changed" notice. A password change from the portal sends the same notice.
* **Names and usernames.** Sign up takes a first, optional middle and last name (letters, spaces, hyphens, apostrophes and full stops only) and generates a username from them. A username is lower case ASCII, 3 to 30 characters, unique through a sparse unique index, may not be a reserved word or contain a brand name (separators do not hide them), and can be changed once every 7 days. Two people taking the same name at once are settled by the index, and a sign up that loses the race retries with another name. Older accounts get names and a username when they next sign in.
* **Sign up issues no token, and a pending account cannot sign in.** `POST /api/v1/auth/signup` answers `201` with a `message`, `next: CONFIRM_EMAIL`, `status`, and `confirmationEmailSent`, and no `accessToken` and no account data. The portal tells the developer to open the confirmation link. Until the email is confirmed the account is `PENDING`: sign in with a password, sign in through a provider, and any portal token answer `403 ACCOUNT_PENDING`. The link, a password reset link, or a provider that vouches for the address makes it `ACTIVE`.
* **Mail login as pasted.** Quotes round `spring.mail.username` or `spring.mail.password`, and the spaces of a Google App Password (16 lower case letters in groups of four), are removed before the SMTP login (`MailCredentials`, applied by a bean post processor on the mail sender). Other passwords are used exactly as written.
* **Mail check at startup.** With `developer.mail.startup-check.enabled=true` (env `DEVELOPER_MAIL_STARTUP_CHECK`) a background thread runs after startup, logs whether mail is on, logs in to the mail server without sending, and when `send-test-to` is an address sends one test email. It never stops startup and never logs the password. SMTP connect, read and write timeouts are 10 seconds.
* **Business gate.** Creating a business needs a confirmed email (`developer.mail.require-verified-for-business`, default true), because the business is a legal entity that will receive payments and staff will contact the address.
* **Social sign in.** One endpoint per provider (`/api/v1/auth/social/google`, `/facebook`, `/github`), each taking a JSON `{ token }`, on the JDK HTTP client with no extra libraries. The browser makes the provider token: a Google ID token, a Facebook user access token, or a GitHub one-time code (a browser cannot get a GitHub access token without the client secret, so this service trades the code). Nothing is trusted without a provider check: Google's token info must show this service's client ID as the audience, Facebook's `debug_token` must show the token is valid for this app and belongs to the profile read with it, and GitHub's trade uses the secret held here. No state is kept on the server, so there is no redirect allow list and it works across instances. Account matching follows the table in `DEVELOPER_API.md` 1.9; the central rule is that an existing account is only joined when the provider vouches for the address, and an unconfirmed local account loses its password when it is joined, because whoever typed that password never proved they own the mailbox.

Known gaps:

* `GET /api/v1/me/username-available` needs a sign in but has no rate limit of its own, so a signed in developer could probe many usernames. Limit it at the gateway.
* No per IP rate limit on the sign in, sign up, forgot password and social sign in endpoints. Put a limit at the gateway. Accounts are protected by the lockout and the per account link limits, not by source address.
* A reset request for a known address does a database insert that an unknown address does not, so a very precise timer could tell them apart. The answer, status and body are identical.
* Links carry the token in the query string. The portal must remove it from the address bar and send `Referrer-Policy: no-referrer` (`DEVELOPER_API.md` 1.10).
* A provider cannot be linked from inside a signed in account. A Facebook address that matches an existing account is refused instead of linked, and the developer signs in the old way. Linking from account settings would fix that.
* The GitHub login runs in the portal, so the portal must make and compare its own `state` value. This service never sees it.
* Emails are HTML files under `src/main/resources/email/` filled by a small placeholder replacer (escaped values, one pass). There is no template engine, no localisation and no plain text alternative part.
* Facebook's Graph API version is pinned in the default endpoint addresses (`v21.0`). I could not confirm that this is Facebook's current version, so check it and override the three addresses if needed.

## 11. Security notes

* Passwords use PBKDF2WithHmacSHA256 from the JDK with a per password salt, and an unknown email still pays for one hash. Five failed sign-ins lock the account for 15 minutes (`423 ACCOUNT_LOCKED`).
* Portal tokens are HS256 JWTs signed with `developer.jwt.secret`. Changing or resetting a password and signing out everywhere raise the account's token version, which invalidates earlier tokens. Signing out one device revokes that token's id.
* API secrets are shown once, at creation or rotation. Only the pepper keyed hash is stored, plus the last characters for display.
* Both the internal key comparison and the secret comparison are constant time.
* The developer service has no Spring Security. Its authentication is two interceptors: developer JWT on `/api/v1/**` (except `/api/v1/auth/**`, which includes the email links and social sign in) and the internal key on `/internal/**`.
* Provider client secrets are read from the environment and used only in server to server calls. Provider replies are capped at 256 KB, redirects are not followed and calls time out after 10 seconds.
