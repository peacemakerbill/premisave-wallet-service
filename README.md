<p align="center">
  <img src="https://capsule-render.vercel.app/api?type=waving&color=gradient&customColorList=6,11,20&height=200&section=header&text=Premisave%20Wallet%20Service&fontSize=48&fontColor=ffffff&fontAlignY=35&desc=Multi-Provider%20Digital%20Wallet%20and%20Payment%20Orchestration&descAlignY=55&descSize=18" alt="Premisave Wallet Service" width="100%">
</p>

<p align="center">
  <img src="https://readme-typing-svg.demolab.com?font=Fira+Code&weight=500&size=22&pause=1000&color=6DB33F&center=true&vCenter=true&width=700&lines=Java+and+Spring+Boot+Microservice;M-Pesa,+Stripe,+PayPal,+Flutterwave,+NOWPayments;Unified+USD+Wallet,+Live+FX,+Full+Reconciliation" alt="Typing SVG">
</p>

**A production-grade Spring Boot microservice powering digital wallets, multi-currency balances, and payment orchestration across M-Pesa, Stripe, PayPal, Flutterwave, and NOWPayments — built in Java for the Premisave fintech platform.**

<p align="center">
  <a href="https://github.com/peacemakerbill/premisave-wallet-service/stargazers"><img src="https://img.shields.io/github/stars/peacemakerbill/premisave-wallet-service?style=for-the-badge&color=gold" alt="GitHub Stars"></a>
  <a href="https://github.com/peacemakerbill/premisave-wallet-service/network/members"><img src="https://img.shields.io/github/forks/peacemakerbill/premisave-wallet-service?style=for-the-badge&color=blue" alt="GitHub Forks"></a>
  <a href="https://github.com/peacemakerbill/premisave-wallet-service/issues"><img src="https://img.shields.io/github/issues/peacemakerbill/premisave-wallet-service?style=for-the-badge&color=orange" alt="GitHub Issues"></a>
  <a href="https://github.com/peacemakerbill/premisave-wallet-service/commits/main"><img src="https://img.shields.io/github/last-commit/peacemakerbill/premisave-wallet-service?style=for-the-badge&color=success" alt="Last Commit"></a>
</p>

<p align="center">
  <img src="https://img.shields.io/badge/Java-21-ED8B00?style=for-the-badge&logo=openjdk&logoColor=white" alt="Java 21">
  <img src="https://img.shields.io/badge/Spring%20Boot-4.1.1-6DB33F?style=for-the-badge&logo=springboot&logoColor=white" alt="Spring Boot 4.1.1">
  <img src="https://img.shields.io/badge/Spring%20Framework-7.0.9-6DB33F?style=for-the-badge&logo=spring&logoColor=white" alt="Spring Framework 7">
  <img src="https://img.shields.io/badge/MongoDB-4EA94B?style=for-the-badge&logo=mongodb&logoColor=white" alt="MongoDB">
  <img src="https://img.shields.io/badge/Redis-DC382D?style=for-the-badge&logo=redis&logoColor=white" alt="Redis">
  <img src="https://img.shields.io/badge/Maven-C71A36?style=for-the-badge&logo=apachemaven&logoColor=white" alt="Maven">
</p>

<p align="center">
  <img src="https://img.shields.io/badge/M--Pesa-00A651?style=flat-square" alt="M-Pesa">
  <img src="https://img.shields.io/badge/Stripe-635BFF?style=flat-square&logo=stripe&logoColor=white" alt="Stripe">
  <img src="https://img.shields.io/badge/PayPal-00457C?style=flat-square&logo=paypal&logoColor=white" alt="PayPal">
  <img src="https://img.shields.io/badge/Flutterwave-F5A623?style=flat-square" alt="Flutterwave">
  <img src="https://img.shields.io/badge/NOWPayments-00D4AA?style=flat-square" alt="NOWPayments">
</p>

<p align="center">
  <a href="https://github.com/peacemakerbill"><img src="https://img.shields.io/badge/Maintainer-@peacemakerbill-181717?style=flat-square&logo=github&logoColor=white" alt="Maintainer"></a>
  <img src="https://img.shields.io/badge/License-Proprietary-red?style=flat-square" alt="License">
  <img src="https://img.shields.io/badge/Status-Active-brightgreen?style=flat-square" alt="Status">
</p>

---

## Table of Contents

- [Overview](#overview)
- [Key Features](#key-features)
- [Supported Payment Providers](#supported-payment-providers)
- [Tech Stack](#tech-stack)
- [Architecture](#architecture)
  - [Example Integration: C2B Hakikisha Name Lookup](#example-integration-c2b-hakikisha-name-lookup)
- [Getting Started](#getting-started)
  - [Prerequisites](#prerequisites)
  - [Installation](#installation)
  - [Configuration](#configuration)
  - [Running the Service](#running-the-service)
- [API Overview](#api-overview)
- [Security](#security)
- [Project Structure](#project-structure)
- [Contributing](#contributing)
- [Author](#author)
- [License](#license)

---

## Overview

**Premisave Wallet Service** is a Java Spring Boot microservice that provides digital wallet, multi-currency balance management, and payment gateway orchestration for the Premisave platform. It's the financial backbone connecting end users to five independent payment rails — **M-Pesa** (Safaricom Daraja), **Stripe**, **PayPal**, **Flutterwave**, and **NOWPayments** — behind a single, consistent, USD-denominated wallet API.

Every deposit, disbursement, transfer, and payment flows through one normalized ledger, with automatic real-time currency conversion, commission accounting, idempotent transaction processing, and full webhook-driven reconciliation — so the rest of the Premisave ecosystem never has to think about the differences between M-Pesa's STK Push, Stripe's PaymentIntents, or PayPal's Orders API.

If you're exploring **Spring Boot microservice architecture**, **fintech payment integration in Java**, **M-Pesa Daraja API integration**, **multi-currency wallet systems**, or **payment gateway orchestration patterns**, this repository is a real-world reference implementation of all of the above.

---

## Key Features

- **Unified USD Wallet** — every wallet balance is held in USD as ground truth, regardless of which currency a provider settles in, with automatic FX conversion applied consistently across deposits, disbursements, and reporting.
- **Live Exchange Rates** — scheduled background refresh from the Frankfurter API, cached in Redis and MongoDB, covering 160+ currency pairs.
- **Five Payment Providers, One API** — M-Pesa (STK Push, C2B, B2C, B2B, B2Pochi, Reversal, Account Balance, Transaction Status, Pull Transactions), Stripe (deposits, saved cards, Stripe Connect international payouts), PayPal (deposits, vaulting, Payouts API), Flutterwave v4, and NOWPayments crypto rails.
- **Wallet-to-Wallet Transfers** — instant internal transfers between Premisave users, fully commission-tracked.
- **Admin Financial Reporting** — daily finance reports, system-wide balance overviews, gateway reconciliation, and company-ledger revenue accounting (commissions, direct payment revenue, and company-side losses all normalized to USD).
- **Webhook-Driven Reconciliation** — signature-verified callback handling for every provider (HMAC, Daraja ResultURL, PayPal Verify Webhook Signature), with idempotent processing and raw-payload audit logging.
- **Automated Email Notifications** — deposit, disbursement, transfer, and payment confirmations with resolved sender/recipient names.
- **Dual Authentication Model** — JWT for end-user requests, a shared internal API key for service-to-service calls from the rest of the Premisave microservice ecosystem.
- **Rate Limiting** — token-bucket request throttling via Bucket4j.
- **Idempotency Everywhere** — every money-moving operation is protected against duplicate processing by design.
- **OpenAPI 3 Documentation** — full API spec generation via springdoc-openapi.

---

## Supported Payment Providers

| Provider | Deposits | Disbursements | Notes |
|---|:---:|:---:|---|
| **M-Pesa (Safaricom Daraja)** | Yes | Yes | STK Push, C2B, B2C, B2B, B2Pochi, Reversal, Account Balance, Transaction Status, Pull Transactions |
| **Stripe** | Yes | Yes | PaymentIntents, saved cards, Stripe Connect for cross-border payouts |
| **PayPal** | Yes | Yes | Orders API, vaulted payment methods, Payouts API |
| **Flutterwave (v4)** | Yes | Yes | Charges and Transfers |
| **NOWPayments** | Yes | Yes | Cryptocurrency deposits and payouts |

---

## Tech Stack

| Layer | Technology |
|---|---|
| **Language** | Java 21 |
| **Framework** | Spring Boot 4.1.1 · Spring Framework 7 |
| **Security** | Spring Security 7 · JWT (jjwt) · JSpecify null-safety |
| **Database** | MongoDB (Spring Data MongoDB) |
| **Cache / Rate Limiting** | Redis · Bucket4j |
| **Service Communication** | Spring Cloud OpenFeign |
| **JSON Processing** | Jackson 3 |
| **API Documentation** | springdoc-openapi (OpenAPI 3) |
| **Object Mapping** | ModelMapper |
| **Build Tool** | Maven |
| **Payment SDKs** | Stripe Java SDK · OkHttp (M-Pesa Daraja) |

---

## Architecture

Premisave Wallet Service is one microservice within the larger **Premisave** platform, communicating with sibling services (auth-service, property-service, and others) via a shared internal API key over REST, and with end users via JWT-authenticated endpoints forwarded through the platform's API gateway.

```
┌──────────────┐      JWT       ┌────────────────────────┐
│   End User   │ ─────────────► │                        │
└──────────────┘                │                        │      ┌─────────────┐
                                 │   Premisave Wallet     │ ───► │  M-Pesa     │
┌──────────────┐  X-API-Key     │      Service            │ ───► │  Stripe     │
│ auth-service │ ◄────────────► │  (this repository)     │ ───► │  PayPal     │
├──────────────┤                │                        │ ───► │ Flutterwave │
│property-serv.│ ◄────────────► │  MongoDB <-> Redis     │ ───► │ NOWPayments │
└──────────────┘                └────────────────────────┘      └─────────────┘
```

### Example Integration: C2B Hakikisha Name Lookup

A concrete example of the `/internal/**` API in action: the sibling **C2B Hakikisha** service (Safaricom's M-Pesa name-lookup API) polls this service's `GET /internal/accounts` endpoint to keep its own copy of M-Pesa/Pochi phone numbers in sync, then answers Safaricom's name-lookup requests entirely from its own local database — this service is never called synchronously in that hot path.

```mermaid
sequenceDiagram
    autonumber
    participant W as Wallet Service
    participant S as C2B Hakikisha Service
    participant DB as MongoDB
    participant SAF as Safaricom

    loop On startup and every N minutes
        S->>W: GET /internal/accounts (paged, X-API-Key)
        W-->>S: Accounts with M-Pesa numbers
        S->>DB: Upsert accounts, remove stale ones
    end

    SAF->>S: POST /oauth2/v1/generate (Basic Auth)
    S-->>SAF: access_token and expires_in
    SAF->>S: POST /api/v1/c2b/hakikisha/name-lookup (Bearer)
    S->>DB: Find by normalised M-Pesa number
    S-->>SAF: accountName
```

---

## Getting Started

### Prerequisites

- Java 21 (JDK)
- Maven 3.9+
- MongoDB (local or Atlas)
- Redis
- Active developer/sandbox credentials for whichever payment providers you intend to test (M-Pesa Daraja, Stripe, PayPal, Flutterwave, NOWPayments)

### Installation

```bash
git clone https://github.com/peacemakerbill/premisave-wallet-service.git
cd premisave-wallet-service
mvn clean install
```

### Configuration

All configuration is externalized via environment variables, referenced from `src/main/resources/application.yml`. Create a `.env` file (or export variables directly) covering, at minimum:

```bash
# Core
MONGODB_URI=mongodb://localhost:27017/premisave-wallet
REDIS_HOST=localhost
REDIS_PORT=6379
JWT_SECRET=your-jwt-secret
INTERNAL_API_KEY=your-shared-internal-key

# Auth Service Integration
AUTH_SERVICE_URL=http://localhost:8080

# M-Pesa Daraja
MPESA_CONSUMER_KEY=...
MPESA_CONSUMER_SECRET=...
MPESA_SHORTCODE=...

# Stripe / PayPal / Flutterwave / NOWPayments
STRIPE_SECRET_KEY=...
PAYPAL_CLIENT_ID=...
FLUTTERWAVE_CLIENT_ID=...
NOWPAYMENTS_API_KEY=...
```

> See `application.yml` for the complete, documented list of environment variables and their defaults.

### Running the Service

```bash
mvn spring-boot:run
```

The service starts on **port 8084** by default.

---

## API Overview

The service exposes REST endpoints under four broad namespaces:

| Namespace | Auth | Purpose |
|---|---|---|
| `/wallet/**` | JWT | Wallet operations, transfers, linked payment methods |
| `/payments/**` | JWT + Public webhooks | Payment initiation and provider webhook callbacks |
| `/disbursements/**` | JWT | Withdrawal/payout initiation |
| `/admin/**` | JWT (Admin/Finance/Operations roles) | Financial reporting, reconciliation, manual adjustments |
| `/internal/**` | Internal API Key | Service-to-service calls from other Premisave microservices |

Full interactive API documentation is available via OpenAPI 3 / springdoc-openapi (enable `springdoc.api-docs.enabled` / `springdoc.swagger-ui.enabled` in your own environment configuration if you need Swagger UI locally — disabled by default in this repo's shipped configuration).

---

## Security

- **JWT authentication** for all end-user-facing endpoints, validated against the platform's shared secret.
- **Internal API key authentication** (`X-API-Key` header) for service-to-service calls, kept entirely separate from user JWTs so the two trust boundaries never mix.
- **Webhook signature verification** on every payment provider callback — HMAC-SHA256 (Flutterwave), HMAC-SHA512 (NOWPayments), PayPal's Verify Webhook Signature API, and Safaricom's IP-allowlisted ResultURL model — before any transaction is trusted.
- **Role-based access control** on admin/finance endpoints (`ADMIN`, `FINANCE`, `OPERATIONS`).
- **Rate limiting** via a token-bucket algorithm to guard against abuse.
- **Stateless sessions** — no server-side session state, fully horizontally scalable.

---

## Project Structure

```
premisave-wallet-service/
├── src/main/java/com/premisave/wallet/
│   ├── config/          # Spring configuration (Security, Redis, Mongo, Feign, Rate Limiting)
│   ├── client/           # Feign clients for auth-service integration
│   ├── controller/       # REST controllers
│   ├── service/          # Business logic — one service per payment provider + core wallet logic
│   ├── dto/              # Request/response DTOs
│   ├── security/         # JWT & internal API key filters
│   ├── filter/           # Servlet filters (callback request logging, etc.)
│   └── PremisaveWalletApplication.java
├── src/main/resources/
│   └── application.yml
└── pom.xml
```

---

## Contributing

This is a proprietary service for the Premisave platform. If you've been granted access to contribute:

1. Fork the repository
2. Create a feature branch (`git checkout -b feature/your-feature`)
3. Commit your changes with clear, descriptive messages
4. Open a pull request against `main`

---

## Author

<p align="center">
  <img src="https://github.com/peacemakerbill.png" width="120" style="border-radius: 50%;" alt="Bill Graham Peacemaker">
</p>

<h3 align="center">Bill Graham Peacemaker</h3>

<p align="center">
Backend Developer &amp; API Support Engineer at Safaricom PLC · Nairobi, Kenya
</p>

<p align="center">
Enterprise systems developer and API integration specialist, working across backend microservices (Java/Spring Boot), Flutter frontends, and DevOps — with deep, hands-on production and personal-project experience in Safaricom's M-Pesa Daraja APIs, the same integration this repository is built around.
</p>

<p align="center">
  <a href="https://github.com/peacemakerbill"><img src="https://img.shields.io/badge/GitHub-@peacemakerbill-181717?style=for-the-badge&logo=github&logoColor=white" alt="GitHub"></a>
  <a href="https://github.com/peacemakerbill/premisave-wallet-service"><img src="https://img.shields.io/badge/Project-premisave--wallet--service-6DB33F?style=for-the-badge&logo=springboot&logoColor=white" alt="Project"></a>
</p>

<p align="center">
  <img src="https://github-readme-stats.vercel.app/api?username=peacemakerbill&show_icons=true&theme=default&hide_title=true&count_private=true" alt="peacemakerbill's GitHub stats" width="48%">
  <img src="https://github-readme-streak-stats.demolab.com/?user=peacemakerbill&theme=default" alt="peacemakerbill's GitHub streak" width="48%">
</p>

---

## License

This project is **proprietary** software developed for the Premisave platform. All rights reserved.

> *(Update this section if the project is intended to be released under an open-source license.)*

---

<p align="center">
  <img src="https://capsule-render.vercel.app/api?type=waving&color=gradient&customColorList=6,11,20&height=120&section=footer" alt="footer" width="100%">
</p>

<p align="center">
  <sub>Built with Spring Boot · A Premisave Platform Microservice</sub>
</p>

<!--
SEO Keywords: Spring Boot microservice, Java fintech, digital wallet API, M-Pesa Daraja integration, 
Stripe Java integration, PayPal API Java, Flutterwave API, NOWPayments crypto payments, multi-currency 
wallet system, payment gateway orchestration, MongoDB Spring Boot, Redis rate limiting, JWT authentication 
Spring Security, microservice architecture Java, fintech backend Java, mobile money API, payment reconciliation 
system, wallet-as-a-service, Premisave, Kenya fintech, East Africa payments
-->