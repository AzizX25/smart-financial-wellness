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
|-- compose.yaml      # Local PostgreSQL service
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
- `compose.yaml` defines local PostgreSQL; `infrastructure` is reserved for broader local infrastructure as the project grows.

Transaction ingestion supports CSV import as an alternative that remains available alongside synchronization with the mock bank.

## Current Status

This repository remains an initial project foundation. The backend has PostgreSQL persistence with Flyway-managed schema migrations and now provides local email/password registration, JWT authentication, refresh-token rotation, logout, and profile APIs. Newly registered accounts remain pending until the future email-verification slice promotes them. Financial features are not implemented, and the React prototype is not connected to these APIs yet.

The backend database foundation defines its final MVP schema through Flyway
migrations V1-V10. Only the JPA mappings needed by authentication, profile,
preferences, refresh sessions, and audit logging are implemented; the other
schema tables remain intentionally unmapped. See the canonical [data model](docs/data-model.md) and
[Flyway schema documentation](docs/database/flyway-schema.md).

The React frontend now contains a demonstrable UI prototype backed only by
centralized simulated data. It does not call a mock API or bypass the future
Spring Boot backend. See the [prototype guide](docs/prototype-ui.md), the
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

## Local PostgreSQL

Docker Desktop must be running before using Docker Compose or running backend tests.

From the repository root, create a local environment file in PowerShell:

```powershell
Copy-Item .env.example .env
```

Open `.env` and replace `replace_with_a_local_password` with a local development password. Never commit `.env`.

Start PostgreSQL:

```powershell
docker compose up -d postgres
```

Check its status and health:

```powershell
docker compose ps
```

Stop PostgreSQL without deleting its persistent named volume:

```powershell
docker compose stop postgres
```

The root `.env` file configures Docker Compose through `DB_NAME`, `DB_USERNAME`, `DB_PASSWORD`, and `DB_PORT`. Spring Boot does not automatically load this file when the backend is started with Maven.

Before starting the backend, make `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`, and a Base64-encoded JWT signing secret available to its process. The backend password must match `DB_PASSWORD` in `.env`. For the example database name, username, and port, run the following from the repository root in the same PowerShell session:

```powershell
$env:DB_URL = "jdbc:postgresql://localhost:5432/smart_financial_wellness"
$env:DB_USERNAME = "smart_financial_wellness"
$env:DB_PASSWORD = "<same local password as .env>"
$jwtSecretBytes = [byte[]]::new(32)
[System.Security.Cryptography.RandomNumberGenerator]::Fill($jwtSecretBytes)
$env:AUTH_JWT_SECRET = [Convert]::ToBase64String($jwtSecretBytes)
Set-Location backend
mvn spring-boot:run
```

Adjust the URL and username if their Compose values were changed. The generated JWT secret is temporary for that PowerShell process; production must inject a securely managed secret. No dotenv dependency is required. See the [authentication and profile API guide](docs/api/authentication-profile.md) for all optional security settings and endpoint behavior.

Running `mvn test` from `backend` starts an isolated `postgres:17-alpine` Testcontainer automatically. Docker is the only external test prerequisite; the local Compose database is not used by the tests.

## Development Tools

Development will require:

- Git
- Node.js and npm for React and Vite
- JDK 21 and Maven for the Spring Boot applications
- Python and a Python virtual-environment tool for the AI and ML components
- Docker with Docker Compose
- PostgreSQL tooling when direct database inspection is needed

Additional setup, configuration, and execution instructions will be added as the services are implemented.
