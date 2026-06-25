# Premisave Wallet Service

Digital Wallet and Payment Processing Microservice for the Premisave Platform.

## Features
- User wallet management (account number = email)
- M-Pesa STK Push, C2B, B2C
- Wallet-to-wallet transfers
- Platform internal payments (via Feign)
- Full transaction ledger
- Multi-currency support (KES primary)
- Idempotency & security

## Tech Stack
- Spring Boot 3.4
- Java 21
- MongoDB + Redis
- JWT Authentication
- Feign Client (Auth Service)

## Quick Start
```bash
cp .env.example .env
# Fill in your credentials
mvn spring-boot:run
