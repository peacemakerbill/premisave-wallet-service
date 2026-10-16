# Stage 7: Merchant numbers, merchant onboarding and the production wallet link

Stage 7 adds three things to the commercial API:

1. Every merchant gets a merchant number (`PRM-0000000001`, `PRM-0000000002`, and so on). Production apps use it. Sandbox apps use configured test numbers.
2. A production app is created with the Premisave wallet it goes live with, and a wallet can go live with one production app only.
3. Merchant onboarding: an application form, supporting documents kept on Google Drive, review by the finance, operations and admin teams, return for correction with messages, approval and rejection, emails, and a timeline of every step.

Request and response detail is in `COMMERCIAL_API_ENDPOINTS.md` (section 15). This file explains how it works, what to configure and what it does not do.

**Changed after this stage.** Apps, test merchant numbers and the wallet link are no longer managed by the wallet service. They moved to the developer service (see `DEVELOPER_SERVICE_SPLIT.md`), which applies the rules described under "Test merchant numbers" and "Production apps and the Premisave wallet" below. The wallet keeps merchant numbers, onboarding and the wallet lookup the developer service uses. Where this file says "the call" for app creation, read it as the developer service's `POST /api/v1/apps`. The wallet's `POST/GET/PUT /merchants/{merchantId}/apps` and `GET /merchants/test-numbers` no longer exist, and the `commercial.merchants.test-numbers` setting is replaced by `developer.apps.test-merchant-numbers` in the developer service.

## Verification status

The code was compiled with `javac` against hand written stubs of Spring, Mongo and Jakarta, and 448 unit tests passed in a small stand-in runner (69 of them for this stage). It was never built with Maven, never started against a real MongoDB, and never talked to Google Drive, a mail server or Safaricom. Run `mvn clean test`, then walk one application through by hand (steps in "Before using it") before relying on it.

## Merchant numbers

* Format: `PRM-` followed by ten digits, counted up from 1. The counter is one atomic increment in the `commercial_counters` collection (`_id` `merchant_number`), so two merchants created at the same moment never share a number, and a number is never reused. A number can only be skipped if the service stops between taking a number and saving the merchant, or if two requests create the same developer account at once (the loser's number is not reused).
* A merchant created before this stage receives its number the first time it is read or used by the service, so the order follows first use rather than creation date. If you want them numbered in creation order, read each existing merchant once in that order after installing.
* The number is stored on the merchant (`merchantNumber`, unique index) and copied onto every app. `GET /merchants/by-number/{merchantNumber}` finds a merchant by it.
* Sandbox apps use test merchant numbers, held by the developer service (`developer.apps.test-merchant-numbers`), five by default:

```yaml
developer:
  apps:
    test-merchant-numbers:
      - PRM-TEST-0001
      - PRM-TEST-0002
      - PRM-TEST-0003
      - PRM-TEST-0004
      - PRM-TEST-0005
```

  Edit the list and restart the developer service to change them; they are not editable at run time. A test number may not be `PRM-` followed by ten digits, so it can never equal a real number; a bad entry stops the developer service at startup. `GET /api/v1/meta/test-merchant-numbers` on the developer service lists the current set. A sandbox app created without a `merchantNumber` gets the first one; one with a number outside the list is refused.

## Production apps and the Premisave wallet

`POST /api/v1/apps` on the developer service for a production app needs two more fields:

| Field | Rule |
|:-|:-|
| `merchantNumber` | Required. Must be the merchant's own number. |
| `premisaveWalletAccount` | Required. The Premisave wallet account number (the wallet owner's email, compared as given and in lower case) or the wallet id. |

* The developer service looks the wallet up through the wallet service's `GET /internal/commercial/v1/wallets/resolve?reference=`. The wallet must exist (`404 WALLET_NOT_FOUND`) and not be frozen (`422 WALLET_NOT_USABLE`).
* If a production app already carries the wallet, the call returns `409 WALLET_ALREADY_LINKED`. This is checked against every production app, whether active or suspended and whichever merchant owns it. The check runs before the insert and is backed by a unique, sparse index on `apps.premisaveWalletId` in the developer service's database, so two simultaneous calls cannot both win.
* The link is a reference only, as chosen (link only). Nothing moves between the wallet and the merchant because of it, and it is not used as a settlement destination.
* Sandbox apps cannot carry a wallet: sending `premisaveWalletAccount` returns `400 VALIDATION_FAILED`.
* The existing rule still applies first: the merchant's live environment must be `ACTIVE`, which now happens when an onboarding application is approved.

Neither service proves that the person asking owns the wallet. The developer service must pass the wallet of the signed in user, and must not accept a wallet account typed by someone else. If you need ownership proven here, the next step would be a consent from the wallet owner (as the Premisave wallet payment method does).

## Onboarding

### When it starts

When a merchant is created (`POST /merchants`), an application is created with it, in `DRAFT`, prefilled with the business name, country, email and phone from the request. `onboarding: { applicationId, status }` is returned with the merchant. Switch the whole feature off with `commercial.onboarding.enabled: false`; merchants created while it is off have no application and can still be activated with `PUT /merchants/{merchantId}/status`. A merchant that has no application (created before this stage, or while onboarding was off) gets one from `POST /merchants/{merchantId}/application`, which returns the existing application if there is one.

While an application exists and is not `APPROVED`, `PUT /merchants/{merchantId}/status` refuses to move the live environment from `PENDING_APPROVAL` to `ACTIVE` (`409 INVALID_STATE`). Approval is the one way in. Suspending, closing and reactivating a suspended merchant work as before.

### Statuses

| Status | Meaning | Applicant can edit | Next |
|:-|:-|:-|:-|
| `DRAFT` | Being filled in | yes | `SUBMITTED` by submit |
| `SUBMITTED` | Waiting for a reviewer | no | `IN_REVIEW` by start review, or `REJECTED` |
| `IN_REVIEW` | A reviewer has it | no | `RETURNED`, `APPROVED` or `REJECTED` |
| `RETURNED` | Sent back with things to correct | yes | `SUBMITTED` by submit |
| `APPROVED` | Accepted, production on | no | final |
| `REJECTED` | Declined | no | `IN_REVIEW` by an admin reopening |

### The form

Business type, registered and trading name, registration number, tax PIN, country, city, address, website, industry, description, expected monthly volume in USD, contact name, email and phone, and one or more directors or owners (name, ID number, role, ownership percent; ownership may not total more than 100). Everything is optional while drafting and checked on submit.

### Documents

* Types: `BUSINESS_REGISTRATION`, `CERTIFICATE_OF_INCORPORATION`, `TAX_COMPLIANCE`, `DIRECTOR_ID`, `PROOF_OF_ADDRESS`, `BANK_STATEMENT`, `OTHER`. The ones that must be present are `commercial.onboarding.required-documents` (default registration, tax compliance and director ID).
* PDF, JPEG or PNG only. The first bytes of the file must match the claimed type, so a renamed web page or executable is refused. Default limit 5 MB per file (hard ceiling 8 MB) and 20 files per application.
* The file is sent as base64 inside the JSON body of `POST .../application/documents`. This keeps the existing request signing working (the signature covers the body) and avoids multipart handling. The request filter allows 12 MB for that one route and 1 MB for every other, so no `spring.servlet.multipart` setting is needed.
* The bytes are stored in Google Drive; the application keeps the Drive file id, a view link, the SHA-256, the size and the review verdict. File names are cleaned (no paths, only letters, digits, dot, dash and underscore) and the Drive name is `PRM-number_TYPE_documentId_filename`.
* Staff open a document through the wallet service (`.../documents/{documentId}/content`), or in Drive through `driveViewLink` if they have access to the folder.
* Reviewers mark each document `ACCEPTED` or `REJECTED` (a rejection needs a note, which the applicant sees). A rejected document stops counting toward the required types. Approval needs an accepted document of every required type.

### Google Drive setup

The code talks to Drive directly over HTTPS with a service account, using only the JDK (it signs a JWT with RS256, exchanges it for a token, and uses the Drive v3 REST API), so there is no new Maven dependency. The scope is `drive.file`, meaning the service account can only reach files it created itself.

1. In Google Cloud, create a project, enable the Google Drive API, create a service account and download its JSON key.
2. Create a Drive folder for onboarding documents (a folder in a shared drive is better, because files created by a service account use its own small quota otherwise). Share it with the service account's email as Editor. Share it with staff who should browse it.
3. Configure:

```yaml
commercial:
  onboarding:
    drive:
      enabled: true
      service-account-json-path: /secrets/drive-sa.json     # or service-account-json with the JSON text
      folder-id: <the folder id from its Drive URL>
```

Until this is configured, uploads fail with `503 PROVIDER_UNAVAILABLE` and the log says why at startup; nothing else in onboarding is affected. Uploading first stores the file and then records it; if recording fails the file is deleted again. Removing a document deletes its Drive file.

### Who may do what

The developer service signs staff in and passes `X-Actor-Id`, `X-Actor-Role` and optionally `X-Actor-Name` on each review step. This service trusts them because the call is authenticated with the internal key (and request signing, when enabled). Roles are compared without case and mapped to steps in the configuration:

```yaml
commercial:
  onboarding:
    roles:
      review: [FINANCE, OPERATIONS, ADMIN]               # start review, notes, document verdicts
      return-for-correction: [FINANCE, OPERATIONS, ADMIN]
      approve: [FINANCE, ADMIN]
      reject: [FINANCE, OPERATIONS, ADMIN]
      reopen: [ADMIN]
```

These defaults are a suggestion, not a rule of yours: finance verifies the numbers and approves, operations checks the business and can return or reject but not approve, admins can do everything and alone reopen. Change them to match how the teams work. A role that may not do a step gets `403 ROLE_NOT_PERMITTED`; missing headers get `401 UNAUTHENTICATED`. The reviewer who starts a review is recorded on the application. Starting a review that someone else already holds returns `409 INVALID_STATE`, but this is not a lock: any other staff member whose role allows the step can still add notes, mark documents, return, approve or reject it.

### Return for correction

A reviewer returns an application with a message and one or more items, each pointing at a form field (`taxPin`, `directors[0].idNumber`) or a document (`document:TAX_COMPLIANCE`) with text saying what to fix. The applicant sees the items in `returnItems`, receives them by email, edits the form and documents, and submits again, optionally answering each item. Resubmitting marks every open item addressed and puts the application back in `SUBMITTED` for review (the review is not remembered, a reviewer has to start it again). Approval is refused while items are open.

### Emails

| When | To | Subject |
|:-|:-|:-|
| Submitted or resubmitted | Applicant | We received your merchant application |
| Submitted or resubmitted | `staff-notify-emails` | Merchant application waiting for review |
| Review started | Applicant | Your merchant application is being reviewed |
| Returned | Applicant, with the message and each item | Action needed on your merchant application |
| Approved | Applicant | Your merchant application is approved |
| Rejected | Applicant, with the reason | Update on your merchant application |
| Reopened | Applicant | Your merchant application is back in review |

Emails are sent through the existing `JavaMailSender` (the `spring.mail.*` settings the wallet service already uses) on a separate thread, so a slow mail server never slows a request and a failed email never fails a step. Text typed by people is escaped before it goes into the page. The outcome of every email is written to the timeline as `EMAIL_SENT` or `EMAIL_FAILED`. The applicant address is the form's contact email, falling back to the merchant's. Set `mail-from` to send from another address.

### The timeline

Every step is written to the `onboarding_events` collection, append only: who (applicant, staff with id, name and role, or system), what, the status before and after, a message, and when. Staff read all of it. The applicant reads the steps marked visible, with staff shown only as "Premisave review team". Internal notes, document verdicts and staff email outcomes are staff only.

## Collections and indexes

| Collection | Contents | Indexes |
|:-|:-|:-|
| `merchant_applications` | One application per merchant | `merchantId` unique, `status` |
| `onboarding_events` | The timeline | `applicationId`, `at` |
| `commercial_counters` | The merchant number counter | `_id` |
| `merchant_profiles` (changed) | New `merchantNumber` | unique, sparse |
| `merchant_apps` (removed from use) | Replaced by `apps` in the developer service's database, with `merchantNumber`, `premisaveWalletId`, `premisaveWalletAccount` | `premisaveWalletId` unique, sparse (developer service) |
| `commercial_app_snapshots` (new) | The wallet's copy of each app's status, methods and limits, for background jobs | `_id` is the app id |

`CommercialIndexInitializer` creates the new indexes at startup when `commercial.indexes.ensure` is on. If your deployment keeps that off, create them yourself before use: without the unique indexes the checks still run, but two simultaneous requests could both pass.

## Before using it

1. Copy the files under `src/` over your project, merge the `commercial:` block of `application.yml` (only the `onboarding` block; the `merchants` block no longer exists), and use the metadata file. See `STAGE7_INSTALL.txt`.
2. Run `mvn clean test`.
3. Set up Drive (above) and the mail settings (`MAIL_USERNAME`, `MAIL_PASSWORD`). Optionally set `COMMERCIAL_ONBOARDING_STAFF_EMAILS`.
4. Make the developer service pass the actor headers on review steps, map its roles to `FINANCE`, `OPERATIONS` and `ADMIN` (or change the role lists), and pass the signed in user's own wallet on production app creation.
5. Create a test merchant and walk one application through: save the form, upload three documents, submit, start review, mark documents, return with an item, fix and resubmit, approve, then create a production app with the merchant number and a wallet account. Check the emails and the timeline.

## Known limits

* Documents are checked by type and first bytes only. There is no virus scan and no content reading (no OCR). A reviewer still has to look at them.
* Step changes read the application and save it back without a version check, so two staff members acting on the same application in the same instant could overwrite each other. The role rules make this unlikely; add optimistic locking if it matters.
* Merchant numbers of merchants created before this stage follow first use, not creation order.
* Test merchant numbers change only by editing the developer service's configuration and restarting it.
* The wallet link proves the wallet exists and is free, not that the caller owns it (see above).
* A wallet stays linked for as long as the production app record exists. Suspending the app does not free it. There is no call to unlink or delete a production app.
* Approval trusts the document verdicts recorded by staff; it does not check the tax PIN or registration number against any registry.
* A downloaded document is read from Drive into memory (at most 8 MB).
* Email delivery is single attempt. A failed email is recorded in the timeline but not retried.
