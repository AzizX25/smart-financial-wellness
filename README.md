# Smart Financial Wellness

Smart Financial Wellness is a planned platform for helping users understand transaction activity and receive informational financial-wellness insights. The repository currently provides the initial project foundation, local PostgreSQL infrastructure, and the first backend authentication/profile slice. It does not yet include financial business features.

## Architecture

The planned system uses:

- A React frontend built with Vite and JavaScript.
- A Java 21 Spring Boot core backend built with Maven.
- A separate Java 21 Spring Boot mock-bank application that simulates an external banking provider.
- A Python FastAPI service for AI inference.
- A separate Python ML pipeline for dataset preparation, training, validation, and model evaluation.
- PostgreSQL for persistent data.
- Docker Compose for local infrastructure orchestration.

The communication flow is:

```text
React frontend
      |
      v
Core backend -----> Mock-bank API
      |
      +-----------> AI inference API
```

The frontend communicates only with the core backend. It must never call the mock bank or AI inference service directly. Each service owns its data and must not directly access another service's database.

## Repository Structure

```text
smart-financial-wellness/
|-- ai-service/       # FastAPI inference service
|-- backend/          # Core Spring Boot application
|-- compose.yaml      # Local databases, Mailpit, and mock-bank service
|-- docs/             # Project documentation
|-- frontend/         # React and Vite application
|-- infrastructure/   # Docker Compose and local infrastructure
|-- ml-pipeline/      # Dataset, training, validation, and evaluation workflows
`-- mock-bank/        # External banking-provider simulator
```

## Service Responsibilities

- `frontend` presents the user experience and communicates exclusively with the core backend.
- `backend` owns financial-wellness business logic and coordinates calls to the mock-bank and AI inference APIs.
- `mock-bank` behaves like an external banking provider and contains no financial-wellness business logic.
- `ai-service` serves model inference only and remains separate from model training.
- `ml-pipeline` prepares and versions datasets, features, model artifacts, and evaluation results before validated models are promoted.
- `compose.yaml` defines the main PostgreSQL database, Mailpit, and the independent mock-bank application and database; `infrastructure` is reserved for broader local infrastructure as the project grows.

Transaction ingestion supports CSV import as an alternative that remains available alongside synchronization with the mock bank.

## Current Status

This repository remains an initial project foundation. The backend has PostgreSQL persistence with Flyway-managed schema migrations and provides local email/password registration, email verification, JWT authentication, refresh-token rotation, password reset, logout, and profile APIs. Newly registered accounts remain pending until their email address is verified. Financial features are not implemented, and only the React authentication onboarding flow is connected to these APIs.

The backend database foundation defines its final MVP schema through Flyway
migrations V1-V10. Only the JPA mappings needed by authentication, profile,
preferences, refresh sessions, and audit logging are implemented; the other
schema tables remain intentionally unmapped. See the canonical [data model](docs/data-model.md) and
[Flyway schema documentation](docs/database/flyway-schema.md).

The mock bank now has an independent Java 21 Spring Boot foundation, its own
PostgreSQL database and Flyway history, and synthetic fixtures only. Its V1
migration creates seven tables and V2 loads deterministic customers, accounts,
and transactions. It exposes health plus read-only Accounts and Transactions
APIs backed by public response records rather than persistence entities.
Consent, authorization-code, and token APIs are not implemented. A lightweight
simulated read-only authorization flow will be added later.

The current mock-bank read-only API is:

| Method and path | Responsibility |
| --- | --- |
| `GET /api/v1/customers/{customerId}/accounts` | List public account metadata in stable external-account-ID order |
| `GET /api/v1/customers/{customerId}/accounts/{accountId}` | Return public metadata for one customer-owned account |
| `GET /api/v1/customers/{customerId}/accounts/{accountId}/balance` | Return only the account balance contract |
| `GET /api/v1/customers/{customerId}/accounts/{accountId}/transactions` | Return an account's transactions with date filters and pagination |

The Transactions endpoint accepts optional `from` and `to` ISO-8601 dates and
filters the PostgreSQL `booking_date`. Both boundaries are inclusive. Results
are ordered by `bookingDate` descending, then `externalTransactionId`
descending as a stable tie-breaker. `page` is zero-based and defaults to `0`;
`size` defaults to `20` and must be between `1` and `100`. The response exposes
only `items`, `page`, `size`, `totalElements`, `totalPages`, `first`, and `last`.

Unknown customers or accounts return a consistent JSON `404`. An account owned
by another customer also returns `404` with `Account not found`, preventing the
response from confirming that the account exists.

The core backend now contains an internal `ReadOnlyBankDataPort` implemented by
a synchronous mock-bank HTTP adapter. It supports `listAccounts`, `getAccount`,
`getBalance`, and `listTransactions` and maps responses to immutable,
backend-owned records. The adapter keeps upstream HTTP DTOs private to its
infrastructure package, uses finite connection/read timeouts, encodes IDs as
path segments, and does not retry. It preserves only the safe upstream `404`
messages `Customer not found` and `Account not found`; rejected requests,
unavailability, and invalid successful responses use separate integration
exception categories without retaining raw response bodies.

This adapter is intentionally not exposed through a backend controller or
proxy. Authenticated user-to-bank-customer linking and read consent must be
implemented before application users can access bank data.

The React frontend's financial screens remain a demonstrable UI prototype
backed by centralized simulated data. The authentication onboarding screens
call only the core backend; the frontend does not call the mock bank. See the [prototype guide](docs/prototype-ui.md), the
[thematic ERD views](docs/database/entity-relationship-diagrams.md), and the
[system architecture view](docs/architecture/system-overview.md).

## Frontend Prototype

From the repository root:

```powershell
Set-Location frontend
npm install
npm run dev
```

Open `http://localhost:5173`. All balances, transactions, forecasts,
recommendations, bank accounts, and identities shown by this prototype are
fictitious demonstration data. Setup will evolve as the backend APIs are
implemented.

## Local PostgreSQL, Mailpit, and Mock Bank

Docker Desktop must be running before using Docker Compose or running backend tests.

From the repository root, create a local environment file in PowerShell:

```powershell
Copy-Item .env.example .env
```

Open `.env` and replace both local password placeholders with development-only
passwords. Never commit `.env`.

Start PostgreSQL and the local Mailpit email catcher:

```powershell
docker compose up -d postgres mailpit
```

Check its status and health:

```powershell
docker compose ps
```

Mailpit accepts local SMTP messages without authentication or TLS on `localhost:1025`.
Open `http://localhost:8025` to inspect verification and password-reset emails.

Stop PostgreSQL without deleting its persistent named volume:

```powershell
docker compose stop postgres
```

Stop Mailpit separately with `docker compose stop mailpit`.

Start the independent mock-bank database and application:

```powershell
docker compose up -d mock-bank-db mock-bank
docker compose ps mock-bank-db mock-bank
```

When both services are ready, `http://localhost:8081/actuator/health` reports
the mock-bank health. The four read-only API routes documented above are also
available; simulated authorization is intentionally deferred. Stop
only these services without deleting their named volume:

```powershell
docker compose stop mock-bank mock-bank-db
```

Docker Compose supplies the mock-bank process with `DB_URL`, `DB_USERNAME`, and
`DB_PASSWORD` from the `MOCK_BANK_DB_*` settings. The application does not have
a fallback database password.

To run both Spring Boot applications as containers, build only their images and
start their required services:

```powershell
docker compose build backend mock-bank
docker compose up -d postgres mock-bank-db mock-bank backend
docker compose ps postgres mock-bank-db mock-bank backend
```

The backend container always uses `http://mock-bank:8081`, the Compose service
hostname, rather than `localhost`. Stop only this group without deleting its
named database volumes:

```powershell
docker compose stop backend mock-bank mock-bank-db postgres
```

The root `.env` file configures Docker Compose through the main `DB_*`,
`MOCK_BANK_DB_*`, and port settings. Spring Boot does not automatically load
this file when either application is started with Maven.

Before starting the backend, make `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`, and a Base64-encoded JWT signing secret available to its process. The backend password must match `DB_PASSWORD` in `.env`. For the example database name, username, and port, run the following from the repository root in the same PowerShell session:

```powershell
$env:DB_URL = "jdbc:postgresql://localhost:5432/smart_financial_wellness"
$env:DB_USERNAME = "smart_financial_wellness"
$env:DB_PASSWORD = "<same local password as .env>"
$jwtSecretBytes = [byte[]]::new(32)
[System.Security.Cryptography.RandomNumberGenerator]::Fill($jwtSecretBytes)
$env:AUTH_JWT_SECRET = [Convert]::ToBase64String($jwtSecretBytes)
$env:SMTP_HOST = "localhost"
$env:SMTP_PORT = "1025"
$env:SMTP_AUTH = "false"
$env:SMTP_STARTTLS_ENABLE = "false"
$env:EMAIL_SENDER_ADDRESS = "no-reply@smart-financial-wellness.local"
$env:EMAIL_SENDER_NAME = "Mon Equilibre"
$env:FRONTEND_BASE_URL = "http://localhost:5173"
$env:MOCK_BANK_BASE_URL = "http://localhost:8081"
$env:MOCK_BANK_CONNECT_TIMEOUT = "2s"
$env:MOCK_BANK_READ_TIMEOUT = "5s"
Set-Location backend
mvn spring-boot:run
```

Adjust the database URL, username, and SMTP port if their Compose values were changed. The generated JWT secret is temporary for that PowerShell process; production must inject a securely managed secret. No dotenv dependency is required. See the [authentication and profile API guide](docs/api/authentication-profile.md) for all optional security and email settings and endpoint behavior.

The mock-bank client settings are:

| Spring property | Environment variable | Local default |
| --- | --- | --- |
| `app.integrations.mock-bank.base-url` | `MOCK_BANK_BASE_URL` | `http://localhost:8081` |
| `app.integrations.mock-bank.connect-timeout` | `MOCK_BANK_CONNECT_TIMEOUT` | `2s` |
| `app.integrations.mock-bank.read-timeout` | `MOCK_BANK_READ_TIMEOUT` | `5s` |

The base URL must be an absolute HTTP(S) URL without embedded credentials,
query, or fragment. Both timeouts must be positive; invalid configuration stops
backend startup.

Running `mvn test` from `backend` starts an isolated `postgres:17-alpine` Testcontainer automatically. Docker is the only external test prerequisite; the local Compose database is not used by the tests.

Running `mvn test` from `mock-bank` likewise uses its own isolated PostgreSQL
Testcontainer. It applies only the mock-bank V1 and V2 migrations and does not
require the main application database.

## Development Tools

Development will require:

- Git
- Node.js and npm for React and Vite
- JDK 21 and Maven for the Spring Boot applications
- Python and a Python virtual-environment tool for the AI and ML components
- Docker with Docker Compose
- PostgreSQL tooling when direct database inspection is needed

Additional setup, configuration, and execution instructions will be added as the services are implemented.
