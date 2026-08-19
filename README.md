# Smart Financial Wellness

Smart Financial Wellness is a planned platform for helping users understand transaction activity and receive informational financial-wellness insights. The repository currently provides the initial project foundation, local PostgreSQL infrastructure, authentication/profile APIs, and a secure development-only mock-bank connection slice. Broader financial business features are not yet implemented.

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
|-- mock-bank-ui/     # Standalone React console for synthetic mock-bank data
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

This repository remains an initial project foundation. The backend has PostgreSQL persistence with Flyway-managed schema migrations and provides local email/password registration, email verification, JWT authentication, refresh-token rotation, password reset, logout, and profile APIs. Newly registered accounts remain pending until their email address is verified. An active user can create one development mock-bank connection, synchronize it into normalized application-owned accounts and transactions, and continue reading that synchronized history after disconnection. The React Dashboard and Transactions pages now consume only those local backend-owned records; the remaining financial modules are still prototypes.

The backend database foundation defines its final MVP schema through Flyway
migrations V1-V10; V11 adds the minimal lifecycle and provider-token fields,
V12 adds persistent synchronization state and normalized provider data, and
V13 adds deterministic transaction categorization and correction history. Only the JPA mappings
needed by implemented authentication, profile, preferences, audit, connection,
and account-read features exist; the other schema tables remain intentionally
unmapped. See the canonical [data model](docs/data-model.md) and
[Flyway schema documentation](docs/database/flyway-schema.md).

The mock bank now has an independent Java 21 Spring Boot foundation, its own
PostgreSQL database and Flyway history, and synthetic fixtures only. Its V1
migration creates seven tables, V2 loads deterministic customers, accounts,
and transactions, V3 adds demo usernames with BCrypt password hashes, and V4
adds the development-administrator identity. It
exposes a simple JWT login plus protected read-only Accounts and Transactions
APIs backed by public response records rather than persistence entities. A
separate French-language React console manages only this synthetic data.
OAuth, consent pages, refresh tokens, payments, and transfers are intentionally
not implemented.

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

The core backend contains internal authentication and read-only bank ports
implemented by one synchronous mock-bank HTTP adapter. The public backend API
never accepts an application user ID or upstream customer ID. It derives both
from the validated application JWT and the stored connection, then supplies
the short-lived provider bearer token only inside the adapter. The adapter maps
responses to immutable backend-owned records, uses finite timeouts, encodes IDs
as path segments, and does not retry or propagate raw upstream bodies.

See the [mock-bank connection API guide](docs/api/mock-bank-connection.md) for
the backend contracts, demo credentials, lifecycle, and safe error behavior.

The core backend synchronization and local-history API is:

| Method and path | Responsibility |
| --- | --- |
| `POST /api/bank/synchronizations` | Fetch all provider pages, validate them, and atomically upsert the current user's accounts, balances, and transactions |
| `GET /api/bank/synchronizations/status` | Return the latest attempt and safe synchronization state |
| `GET /api/accounts` | List normalized persisted accounts owned by the authenticated user |
| `GET /api/transactions` | Page through normalized persisted transactions with optional account, inclusive date, and direction filters |
| `GET /api/dashboard/summary?month=YYYY-MM` | Return monthly cash-flow and account-balance totals grouped by currency |
| `GET /api/categories` | List the current user's visible system and personal categories |
| `POST /api/categories` | Create a personal category |
| `PATCH /api/categories/{categoryId}` | Update an owned personal category |
| `DELETE /api/categories/{categoryId}` | Delete an unused owned personal category |
| `PATCH /api/transactions/{transactionId}/category` | Assign, correct, or remove a transaction category |
| `POST /api/categorization-runs` | Apply deterministic rules only to uncategorized transactions |
| `GET /api/categories/summary?month=YYYY-MM` | Return category totals and uncategorized totals separately for each currency |

Synchronization is explicit and serialized per connection. Provider HTTP calls
finish before the atomic database write, repeated calls are idempotent, and a
failed attempt preserves the previous successful data. These endpoints never
accept application-user or upstream-customer identifiers.

The authenticated React Dashboard and Transactions pages now consume these
local contracts. Dashboard totals are calculated in PostgreSQL with decimal
arithmetic, grouped by currency, and exclude recognized internal transfers.
The Transactions page uses backend pagination and account, date, direction,
category, uncategorized, and text filters. The Categories page uses only real
authenticated API data and supports personal-category management, deterministic
classification, and per-currency monthly summaries. Both pages retain synchronized history after disconnection;
they never call the mock bank directly.

Synchronization remains an explicit user action through **Actualiser**. A later
increment may synchronize automatically only when locally persisted data is
older than a configurable freshness threshold; that stale-data policy is not
implemented yet.

See the [transaction categorization API guide](docs/api/transaction-categories.md)
for rule precedence, ownership, history, and error contracts.

The React frontend now uses the core backend for authentication, the current
user/profile, and the development mock-bank connection journey. Dashboard
metrics and the other financial-planning screens remain a demonstrable UI
prototype backed by centralized simulated data. The frontend calls only the
core backend and never calls the mock bank directly. See the [prototype guide](docs/prototype-ui.md), the
[thematic ERD views](docs/database/entity-relationship-diagrams.md), and the
[system architecture view](docs/architecture/system-overview.md).

## Frontend Prototype

From the repository root:

```powershell
Set-Location frontend
npm install
Copy-Item .env.example .env.local
npm run dev
```

`VITE_API_BASE_URL` defaults locally to `http://localhost:8080` and can be
configured in `frontend/.env.local`. Open `http://localhost:5173`. Login,
profile identity/preferences, and the Profile/Ma banque connection views use
authenticated backend data. Dashboard summaries, the standalone Transactions
screen, salary planning, goals, forecasts, recommendations, and the financial
score still use fictitious demonstration data.

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
the mock-bank health. Login is public; all customer data routes require a valid
mock-bank bearer JWT whose subject matches the customer path. Stop only these
services without deleting their named volume:

```powershell
docker compose stop mock-bank mock-bank-db
```

Docker Compose supplies the mock-bank process with its database and JWT
settings. The application has no fallback database password or signing secret.

The deterministic development-only bank credentials are:

| Username | Password |
| --- | --- |
| `amina.demo` | `DemoBank2026!` |
| `youssef.demo` | `DemoBank2026!` |

These credentials authenticate only against synthetic mock-bank data. Never
reuse them in a real environment.

The separate administrator console is available at `http://localhost:5174`
when its Compose service is running. Its clearly development-only credential is:

| Username | Password |
| --- | --- |
| `admin.demo` | `AdminBank2026!` |

The administrator JWT has an explicit `ADMIN` role and token type. Customer
tokens cannot call administrator endpoints, and administrator tokens cannot
use customer-owned banking routes. Passwords and hashes are never returned by
management DTOs.

Start only the mock-bank group and its management console with:

```powershell
docker compose up -d --build mock-bank-db mock-bank mock-bank-ui
docker compose ps mock-bank-db mock-bank mock-bank-ui
```

The management contracts are:

| Method and path | Responsibility |
| --- | --- |
| `POST /api/admin/auth/login` | Authenticate the development administrator |
| `GET /api/admin/dashboard` | Return small customer, account, and transaction summaries |
| `GET /api/admin/customers` | Search and paginate synthetic customers |
| `GET /api/admin/customers/{customerId}` | Load one synthetic customer |
| `POST /api/admin/customers` | Create a customer with a BCrypt password hash |
| `PATCH /api/admin/customers/{customerId}` | Update the supported customer metadata |
| `POST /api/admin/customers/{customerId}/reset-password` | Replace a customer password hash |
| `POST /api/admin/customers/{customerId}/activate` | Allow customer authentication and access |
| `POST /api/admin/customers/{customerId}/deactivate` | Block customer authentication and access |
| `GET /api/admin/accounts` | Filter and paginate accounts |
| `GET /api/admin/accounts/{accountId}` | Load account details and balances |
| `POST /api/admin/customers/{customerId}/accounts` | Create a zero-balance account for its owner |
| `PATCH /api/admin/accounts/{accountId}` | Update account name or type without reassignment |
| `POST /api/admin/accounts/{accountId}/activate` | Activate a non-closed account |
| `POST /api/admin/accounts/{accountId}/freeze` | Freeze an account |
| `POST /api/admin/accounts/{accountId}/close` | Close an account without deleting history |
| `GET /api/admin/accounts/{accountId}/transactions` | Paginate and optionally date-filter operations |
| `POST /api/admin/accounts/{accountId}/transactions` | Atomically post a manual credit or debit and update balances |

Manual operations require an active account and a strictly positive decimal
amount. Debits cannot exceed current or available funds. Transactions are
immutable; correcting entries are represented by new reversing operations.

With an `ACTIVE` application user's access JWT in `$appAccessToken`, connect
through the core backend and read the linked data as follows:

```powershell
$headers = @{ Authorization = "Bearer $appAccessToken" }
$credentials = @{ username = "amina.demo"; password = "DemoBank2026!" } | ConvertTo-Json
Invoke-RestMethod -Method Post -Uri "http://localhost:8080/api/bank-connections/mock-bank" -Headers $headers -ContentType "application/json" -Body $credentials
$accounts = Invoke-RestMethod -Uri "http://localhost:8080/api/bank/accounts" -Headers $headers
$accountId = $accounts[0].id
Invoke-RestMethod -Uri "http://localhost:8080/api/bank/accounts/$accountId/balance" -Headers $headers
Invoke-RestMethod -Uri "http://localhost:8080/api/bank/accounts/$accountId/transactions?page=0&size=20" -Headers $headers
Invoke-RestMethod -Method Delete -Uri "http://localhost:8080/api/bank-connections/mock-bank" -Headers $headers
```

The commands intentionally use the core backend rather than exposing a mock
customer identifier to the frontend.

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
$rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
$jwtSecretBytes = New-Object byte[] 32
$rng.GetBytes($jwtSecretBytes)
$rng.Dispose()
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

The mock-bank JWT settings are:

| Spring property | Environment variable | Local default |
| --- | --- | --- |
| `app.security.jwt.secret` | `MOCK_BANK_JWT_SECRET` | None; required Base64 secret of at least 32 decoded bytes |
| `app.security.jwt.issuer` | `MOCK_BANK_JWT_ISSUER` | `smart-financial-wellness-mock-bank` |
| `app.security.jwt.audience` | `MOCK_BANK_JWT_AUDIENCE` | `smart-financial-wellness-backend` |
| `app.security.jwt.access-token-ttl` | `MOCK_BANK_ACCESS_TOKEN_TTL` | `10m` |
| `app.security.jwt.admin-access-token-ttl` | `MOCK_BANK_ADMIN_ACCESS_TOKEN_TTL` | `15m` |
| `app.security.admin-ui-origin` | `MOCK_BANK_ADMIN_UI_ORIGIN` | `http://localhost:5174` |

When the mock bank is started with Maven instead of Compose, its process must
receive the database variables and signing secret explicitly because Spring
Boot does not load the root `.env` file:

```powershell
$env:DB_URL = "jdbc:postgresql://localhost:5432/mock_bank"
$env:DB_USERNAME = "mock_bank"
$env:DB_PASSWORD = "<same mock-bank password as .env>"
$rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
$mockJwtSecretBytes = New-Object byte[] 32
$rng.GetBytes($mockJwtSecretBytes)
$rng.Dispose()
$env:MOCK_BANK_JWT_SECRET = [Convert]::ToBase64String($mockJwtSecretBytes)
$env:MOCK_BANK_ADMIN_UI_ORIGIN = "http://localhost:5174"
Set-Location mock-bank
mvn spring-boot:run
```

Running `mvn test` from `backend` starts an isolated `postgres:17-alpine` Testcontainer automatically. Docker is the only external test prerequisite; the local Compose database is not used by the tests.

Running `mvn test` from `mock-bank` likewise uses its own isolated PostgreSQL
Testcontainer. It applies only the mock-bank V1 through V4 migrations and does not
require the main application database.

For host-based frontend development, set `VITE_MOCK_BANK_API_URL` before
starting Vite when the mock bank is not available at `http://localhost:8081`:

```powershell
Set-Location mock-bank-ui
$env:VITE_MOCK_BANK_API_URL = "http://localhost:8081"
npm install
npm run dev
```

The API URL is compiled into this development frontend, but administrator
credentials are not. The JWT is stored only in `sessionStorage` and cleared on
an administrator `401` response.

## Development Tools

Development will require:

- Git
- Node.js and npm for React and Vite
- JDK 21 and Maven for the Spring Boot applications
- Python and a Python virtual-environment tool for the AI and ML components
- Docker with Docker Compose
- PostgreSQL tooling when direct database inspection is needed

Additional setup, configuration, and execution instructions will be added as the services are implemented.
