# Smart Financial Wellness

Smart Financial Wellness is a planned platform for helping users understand transaction activity and receive informational financial-wellness insights. The repository currently provides the initial project foundation, including local PostgreSQL infrastructure and backend persistence configuration. It does not yet include financial features or authentication.

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

This repository is an initial project foundation. The backend has PostgreSQL persistence plumbing with Flyway-managed schema migrations, but the application services are not feature-complete. Financial features, user management, and authentication are not implemented.

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

Before starting the backend, make `DB_URL`, `DB_USERNAME`, and `DB_PASSWORD` available to its process. The backend password must match `DB_PASSWORD` in `.env`. For the example database name, username, and port, run the following from the repository root in the same PowerShell session:

```powershell
$env:DB_URL = "jdbc:postgresql://localhost:5432/smart_financial_wellness"
$env:DB_USERNAME = "smart_financial_wellness"
$env:DB_PASSWORD = "<same local password as .env>"
Set-Location backend
mvn spring-boot:run
```

Adjust the URL and username if their Compose values were changed. No dotenv dependency is required.

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
