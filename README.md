# Smart Financial Wellness

Smart Financial Wellness is a planned platform for helping users understand transaction activity and receive informational financial-wellness insights. The repository currently provides the initial project foundation and service boundaries; it does not yet imply that any service is implemented or runnable.

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
- `infrastructure` defines local PostgreSQL and service orchestration through Docker Compose.

Transaction ingestion supports CSV import as an alternative that remains available alongside synchronization with the mock bank.

## Current Status

This repository is an initial project foundation. Its directories and documentation establish the intended architecture and development boundaries; they do not indicate completed service implementations.

## Development Tools

Development will require:

- Git
- Node.js and npm for React and Vite
- JDK 21 and Maven for the Spring Boot applications
- Python and a Python virtual-environment tool for the AI and ML components
- Docker with Docker Compose
- PostgreSQL tooling when direct database inspection is needed

Detailed setup, configuration, and execution instructions will be added as the services are implemented.
