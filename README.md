# Smart Financial Wellness Advisor

Smart Financial Wellness Advisor helps users understand their spending, prepare a
monthly budget, manage savings goals and estimate their end-of-month balance.
The French-language interface also presents an explainable financial-wellness
score and practical, informational recommendations.

This is an **internship demonstration application**, not a production banking
platform or a source of guaranteed financial advice. Its mock bank and sample
dataset are synthetic. Forecast ML is trained on synthetic data; optional local
LLM and receipt-vision features remain experimental.

Configuration, installed models and available history determine which optional
features can run. See the [local-install validation record](docs/release/local-install-validation.md)
for the checks performed on 22 October 2026 and their remaining limits.

## Contents

- [Features and usage](#features-and-usage)
- [Architecture and services](#architecture-and-services)
- [Local setup with Docker Compose](#local-setup-with-docker-compose)
- [Sample data (optional)](#sample-data-optional)
- [AI and model configuration](#ai-and-model-configuration)
- [Troubleshooting](#troubleshooting)
- [Development and validation](#development-and-validation)
- [API overview](#api-overview)
- [Architecture diagrams and documentation](#architecture-diagrams-and-documentation)
- [Limitations and next steps](#limitations-and-next-steps)

## Features and usage

| Area | What works today |
| --- | --- |
| Authentication | Email/password registration, email verification, login, rotating refresh tokens, logout and password-reset APIs |
| Profile | Editable display/profile information and financial/interface preferences; email and account status are read-only |
| Mon Budget / Dashboard | Server-calculated account balances, monthly income/expenses and actual spending by the current transaction category |
| Bank connection | Connect to the synthetic mock bank and manually synchronize accounts, transactions and dated balance snapshots |
| Transactions | Real persisted history, filters, pagination, manual category corrections and a review workflow |
| Categories | System and private categories, private-category management and deterministic categorization |
| Merchant recognition | Local model suggestions, private user merchant memory and optional bounded web/LLM enrichment; human approval is required |
| CSV import | Account selection/creation, preview, editable mapping, validation and idempotent confirmation |
| Salary Planning | One plan per user, calendar month and currency; expected income, category allocations, saving capacity and previous plans |
| Goals | Goal lifecycle, manual contributions, progress, monthly capacity allocation and temporary contribution scenarios |
| Forecast | Current-month end-balance estimation, saved results and temporary income/expense adjustments |
| Financial-wellness score | Deterministic, immutable 0–100 assessments with factor explanations, history and insufficient-data states |
| Recommendations | Versioned deterministic priorities with supporting facts, workflow links and accepted/rejected/hidden feedback |
| Optional local assistance | French recommendation explanations and experimental receipt extraction, human review and CSV export |

### First-use journey

1. Open the frontend and register. New accounts remain **PENDING_VERIFICATION**.
2. Open Mailpit, follow the verification link and sign in to the activated account.
   Pending accounts cannot use financial APIs.
3. In **Ma banque**, connect an existing synthetic mock-bank customer, then use
   **Actualiser**. Alternatively, import a statement from **Transactions**.
4. Review transactions and correct categories. Actual monthly spending uses the
   current saved assignments; uncategorized spending stays explicit.
5. In **Salary Planning**, select a month/currency, enter expected income, review
   historical proposals, edit allocations and save.
6. In **Objectifs**, create goals, record actual contributions and distribute
   available monthly saving capacity. Try a scenario without changing real progress.
7. In **Prévisions**, request the current estimate and optionally test temporary
   income/expense changes.
8. On the Dashboard, inspect the score and **Mes prochaines étapes**. New accounts
   may legitimately have insufficient history rather than a fabricated score.

Disconnecting the bank preserves already synchronized local history. Synchronization
is an explicit action, not an automatic background process. Read-only provider
tokens can expire; reconnect when the application requests it.

### Financial rules

Currencies are never combined or converted. Authoritative calculations run in the
backend with decimal amounts at scale 4; user input with excessive precision is
rejected. Display rounding does not change stored amounts.

Salary Planning calculates:

```text
totalIncome = sum(expected income)
effectiveAllocation = confirmed amount, otherwise recommended amount
totalExpenses = sum(effective allocations)
savingCapacity = totalIncome - totalExpenses
deficit = max(-savingCapacity, 0)
```

Historical proposals examine up to three completed months. Saved recommendation
amounts and their metadata are persisted together; reading a saved plan does not
recalculate them from newer history.

Goal progress is the initial amount plus contributions. Allocations are plans,
**not contributions**, and cannot exceed `max(savingCapacity, 0)`. A missing Salary
Plan must be created explicitly; past allocation months are read-only. Goal
scenarios concern contribution amounts and achievement timing, not Forecast.

Forecast calculates a deterministic remaining-period baseline and optionally adds
the frozen TND Huber model's predicted residual. An unavailable or ineligible model
leaves the deterministic result intact. The chart connects an observed balance to
the month-end estimate; it does not represent separately predicted daily balances.

The score uses up to six completed months and renormalizes available factor
weights. Its savings/history sufficiency checks may produce `INSUFFICIENT_DATA`.
It is not a credit score. Recommendations use available authoritative factors and
never modify budgets, goals, categories or the score automatically.

## Architecture and services

```text
User browser -> React frontend -> Core Spring Boot backend -> Application PostgreSQL
                                      |            |
                                      |            +-> Mailpit (local SMTP)
                                      +-> Mock-bank API -> Mock-bank PostgreSQL
                                      +-> FastAPI inference -> Frozen model artifacts
                                                   |     |
                                                   |     +-> Host Ollama (optional)
                                                   +-> Ollama Web Search (merchant-only, optional)

Administrator browser -> Separate mock-bank-ui -> Mock-bank administration API

Offline Python ML pipeline -> Evaluated, versioned artifacts -> AI image build
```

The main frontend calls **only the core backend**. The separate administrator
console calls its own mock-bank API. Each Spring Boot application owns its database;
neither service accesses the other's database. FastAPI has no PostgreSQL access.
Training is separate from inference, with no training triggered by user requests.

| Compose service | Role | Default local access |
| --- | --- | --- |
| `frontend` | Main React/Vite JavaScript SPA, served by Nginx | [localhost:5173](http://localhost:5173) |
| `backend` | Java 21 / Spring Boot 4.1 REST API and financial rules | [localhost:8080/actuator/health](http://localhost:8080/actuator/health) |
| `postgres` | Application PostgreSQL 17; named volume `postgres-data` | Host port `5432` |
| `mock-bank` | Independent Spring Boot synthetic banking provider | [localhost:8081/actuator/health](http://localhost:8081/actuator/health) |
| `mock-bank-db` | Independent PostgreSQL 17; volume `mock-bank-data` | Internal port `5432` only |
| `mock-bank-ui` | Separate React administration console | [localhost:5174](http://localhost:5174) |
| `ai-service` | Python 3.12 / FastAPI, frozen inference and optional assistance | Internal `http://ai-service:8000`; no published host port |
| `mailpit` | Local email capture, not a real email-delivery service | [localhost:8025](http://localhost:8025); SMTP `1025` |

Ollama runs on the host at port `11434`, not as a Compose service. The ML pipeline
runs in a separate Python environment. Structurizr is a separate documentation
viewer, not an application dependency.

### Repository structure

```text
smart-financial-wellness/
├── frontend/          # French user interface and shared authenticated API client
├── backend/           # Core Spring Boot API, feature packages and Flyway migrations
├── mock-bank/         # Independent synthetic bank API, ledger and administration
├── mock-bank-ui/      # Separate bank-administrator frontend
├── ai-service/        # FastAPI inference, strict contracts and frozen models
├── ml-pipeline/       # Offline datasets, notebooks, training, evaluation and export
├── scripts/demo/      # Synthetic-data setup, verification and script checks
├── docs/              # Canonical API, database, architecture and demonstration guides
├── infrastructure/    # Infrastructure supporting material
├── compose.yaml       # Local application orchestration
├── .env.example       # Non-private Compose configuration template
└── .env.demo.example  # Synthetic identity and blank demo-password template
```

Flyway is the sole schema-management mechanism. The application currently has
**V1–V24** under `backend/src/main/resources/db/migration`; the mock bank has
**V1–V4** under its own migration directory. Hibernate uses `ddl-auto=validate`.
Do not edit applied migrations or use schema auto-update/Flyway repair to hide drift.
Some tables support future work; their existence alone does not prove an API exists.

## Local setup with Docker Compose

### Prerequisites and downloads

| Tool | Needed for | Official download or setup |
| --- | --- | --- |
| Git | Clone the repository and retrieve updates | [Git downloads](https://git-scm.com/downloads/) |
| Docker Desktop | Build and run the application services; select Linux containers and Compose v2 | [Docker Desktop installation](https://docs.docker.com/desktop/setup/install/desktop-install/) |
| Browser | Open the application | A current Chrome, Edge, Firefox or Safari |
| PowerShell | Commands below and optional sample-data scripts | Windows PowerShell 5.1 is sufficient on Windows; [PowerShell 7](https://learn.microsoft.com/en-us/powershell/scripting/install/installing-powershell) is available separately |
| Ollama (optional) | Local explanations, merchant web reasoning and receipt reading | [Ollama download](https://ollama.com/download) |

**Java, Maven, Node.js, Python and a host PostgreSQL installation are not required
for the Docker setup.** Their build/runtime environments are supplied by the
images. Host development prerequisites are listed separately below.

Start Docker Desktop before continuing. On Windows, enable its WSL 2 backend and
the required virtualization support following the official installation guide.
Linux users can use Docker Engine with the Compose plugin. On macOS/Linux, use
PowerShell 7 for the sample-data scripts; the clone/Compose commands also work in
a normal terminal. GPU compatibility differs by operating system.

Local AI needs additional RAM and several gigabytes of model storage, on top of
Docker images and database volumes. A 24 GB RAM computer is a starting point for
trying the configured models one at a time, **not a tested minimum or a latency
guarantee**. Available RAM, GPU/VRAM and other running applications matter. Begin
with AI disabled, then check local response times before enabling each feature.
Internet access is needed for initial image builds and model downloads. Merchant
web enrichment additionally needs internet whenever it is used.

All commands below start from the repository root unless otherwise stated.
Docker builds may download dependencies and require network access.

### 0. Get the project

```powershell
git clone --branch feat/backend-foundation https://github.com/AzizX25/smart-financial-wellness.git
Set-Location smart-financial-wellness
git --version
docker --version
docker compose version
docker info --format '{{.OSType}}'
```

The last command should return `linux`. A private repository requires permission
and GitHub authentication. A clone contains only published commits, not another
computer's uncommitted work, private environment files or database contents.
The command selects the application's development branch explicitly; the default
branch may not contain the same features. Use a documented release tag instead
when a release has been published.
You can also download a source ZIP from GitHub and extract it before opening a
terminal in the directory containing `compose.yaml`.

### 1. Prepare private configuration

Copy the template only if a private configuration does not already exist:

```powershell
if (-not (Test-Path .env)) { Copy-Item .env.example .env }
```

Edit `.env` locally:

- Set private `DB_PASSWORD` and `MOCK_BANK_DB_PASSWORD`.
- Replace the example `AUTH_JWT_SECRET` and `MOCK_BANK_JWT_SECRET` with **different**
  securely generated Base64 secrets, each decoding to at least 32 random bytes.
- Keep optional AI flags disabled for the first run.
- Do not commit `.env`, print its contents or share secret-bearing Compose output.

For a **new installation**, this optional PowerShell snippet generates signing
keys without displaying them:

```powershell
function New-LocalSigningSecret {
  $bytes = New-Object byte[] 32
  $rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
  try { $rng.GetBytes($bytes) } finally { $rng.Dispose() }
  return [Convert]::ToBase64String($bytes)
}
$env:AUTH_JWT_SECRET = New-LocalSigningSecret
$env:MOCK_BANK_JWT_SECRET = New-LocalSigningSecret
```

These assignments affect only this shell and its child processes; shell variables
override matching Compose `.env` entries. Use securely managed persistent values
for repeatable startup. Do not regenerate an existing environment's signing keys
casually: that invalidates its JWTs. Do not change passwords for already initialized
database volumes without a deliberate credential-change procedure.

### 2. Start the application

```powershell
docker compose --env-file .env config --quiet
docker compose --env-file .env up -d --build
docker compose ps
```

Open the main frontend, mock-bank administration console and Mailpit using the
service table above. Register a platform user normally, or prepare the sample
scenario below. Platform and bank credentials are separate; the bank administrator
does not log in to the main application.

The initial build can take several minutes and is not a test run: the Java
Dockerfiles skip tests while packaging. Wait for both PostgreSQL services to be
healthy and for the APIs to start. Flyway creates the schemas on fresh volumes;
Hibernate validates them. No existing account or financial history is copied from
the author's machine. A new account can start with manual transactions or CSV;
the optional sample scenario supplies a longer synthetic history.

Backend health and info are public, but financial routes require an active
authenticated user. Use the exact health path, with no prose or suffix appended:

```powershell
Invoke-RestMethod http://localhost:8080/actuator/health
Invoke-RestMethod http://localhost:8081/actuator/health
docker compose exec -T ai-service python -c "import urllib.request; print(urllib.request.urlopen('http://localhost:8000/health', timeout=5).read().decode())"
```

AI health describes its frozen-model readiness; it does not prove every optional
Ollama model or external search call works. Backend health does not establish a
successful end-to-end user journey.

### 3. Match custom ports and browser origins

Defaults are backend `8080`, frontend `5173`, bank API `8081` and admin UI `5174`.
If your local backend is published on **8082**, use:

```text
BACKEND_PORT=8082
FRONTEND_API_URL=http://localhost:8082
```

Then use `http://localhost:8082/actuator/health` and set
`DEMO_PLATFORM_URL=http://localhost:8082` for demo scripts. The backend's internal
Compose port remains `8080`.

Frontend API URLs are **build-time settings**. Rebuild `frontend` after changing
`FRONTEND_API_URL`, or `mock-bank-ui` after changing `MOCK_BANK_ADMIN_API_URL`.
Use consistent browser hostnames; mixing `localhost` and `127.0.0.1` can break
origin/cookie expectations.

Changing the frontend origin also requires matching backend `CORS_ALLOWED_ORIGIN`
and `FRONTEND_BASE_URL` process settings; changing the admin origin requires
`MOCK_BANK_ADMIN_UI_ORIGIN`. Compose passes only variables explicitly wired in
`compose.yaml`: adding arbitrary Spring variables to root `.env` does not inject
them into a container automatically. Keep default UI origins for the simple setup.

### 4. Stop or inspect without deleting data

```powershell
docker compose logs --tail 100 backend ai-service mock-bank
docker compose stop
docker compose start
```

Inspect logs locally and redact sensitive content before sharing them. Named
database volumes preserve data across normal restarts. **Do not use
`docker compose down -v` to troubleshoot** or remove a shared PostgreSQL volume.

## Sample data (optional)

The existing versioned scenario uses a fixed, clearly synthetic identity:

| Field | Synthetic value |
| --- | --- |
| Name | Amine Ben Salem |
| Platform login | `amine.bensalem@example.test` |
| Mock-bank login | `amine.bensalem.demo` |
| Currency / locale / timezone | TND / French (`fr-TN`) / `Africa/Tunis` |

Prepare `.env.demo` without overwriting an existing one:

```powershell
if (-not (Test-Path .env.demo)) { Copy-Item .env.demo.example .env.demo }
```

Privately fill `DEMO_PLATFORM_PASSWORD`, `DEMO_BANK_PASSWORD` and
`DEMO_MOCK_BANK_ADMIN_PASSWORD` for the existing local bank administrator.
No passwords are supplied here. Set the URLs to your actual ports. Keep
`DEMO_RUN_MERCHANT_AI=false` for the core scenario.

The platform and bank passwords must be 12–72 UTF-8 bytes. Choose them on first
creation and keep them for later runs: changing this file does not reset an
existing account. `DEMO_MOCK_BANK_ADMIN_PASSWORD` must match the **already
provisioned** `admin.demo` administrator. Obtain that development credential
privately from the project maintainer; entering an arbitrary new password does
not create or update the administrator. Without it, use normal registration and
manual/CSV entry instead. The sample scripts cannot bootstrap that credential.

```powershell
docker compose --env-file .env --env-file .env.demo up -d --build
powershell.exe -NoProfile -ExecutionPolicy Bypass -File ./scripts/demo/seed_demo.ps1
powershell.exe -NoProfile -ExecutionPolicy Bypass -File ./scripts/demo/verify_demo.ps1
```

The seed uses normal authenticated APIs and an administrator-protected fixture
enabled only by the mock bank's `demo` profile and explicit fixture opt-in. The
copied `.env.demo.example` supplies both switches; the default profile disables
the endpoint. Scripts load `.env.demo`; nonempty shell settings take precedence.
The neutral entry points delegate to the existing scripts without renaming
persisted scenario identifiers or changing ownership checks. With PowerShell 7,
replace `powershell.exe` with `pwsh`.

The dataset contains two synthetic TND accounts, at least eight completed months
plus elapsed current-month activity, recurring income/expenses, transfer pairs,
a pending operation and categorization-review examples. It prepares current/future
Salary Plans, two goals with legitimate progress/allocations and Forecast prerequisites.

The default as-of date is today; `-AsOfDate yyyy-MM-dd` accepts a date in the current
calendar month. Stable scenario references make reconciliation idempotent. Month
rollover extends the existing ledger from its original opening anchor rather than
resetting balances. Altered or conflicting fixtures are refused; a `409` is not
permission to delete ordinary data or bypass checks.

The verifier is non-destructive, prints PASS/FAIL checks and exits nonzero on
failure. It validates the fixed identity and owned resources. No Ollama or web
search is needed for core seeding.

Recommended walkthrough: login → Dashboard → bank/synchronization → transactions
and category review → Salary Planning → goals/scenarios → Forecast → score and
recommendations. Show optional explanation/receipt flows separately and repeat
important financial flows with those features disabled.

Do not use `-ResetDemoOnly` for routine reconciliation. Its optional explicit reset
removes only the marked mock-bank fixture, not the platform user or synchronized
local history. See the [sample-data guide](docs/demo/local-demo.md) for safety
checks and removal limitations.

## AI and model configuration

### What each AI feature does

| Feature | Implementation and fallback |
| --- | --- |
| Forecast regression | Frozen scikit-learn Huber model, synthetic TND training, residual correction to a deterministic forecast; deterministic fallback |
| Merchant recognition | Frozen model/catalog, private user memory and optional public web reasoning; invalid/unrecognized results abstain |
| Recommendation explanations | Optional local Ollama wording of controlled rule/factor codes; original deterministic explanation on failure |
| Receipt vision | Optional local image extraction; missing fields stay missing, human review required and no transaction created |

The score, recommendation selection, Salary Planning and goal calculations do not
use an LLM. No autonomous AI agents or automatic financial actions are implemented.

Merchant AI receives an opaque key, sanitized merchant text, direction, currency
and contract versions—not amounts, user IDs or database transaction IDs.
Explanations send controlled rule/factor codes, not financial amounts or raw history.
Forecast sends aggregate features. Only **merchant web enrichment** makes external
search calls; recommendation explanations and receipt vision stay local.

### Optional Ollama setup

Install Ollama from the official download above, then reopen your terminal if
the `ollama` command is not found. Ollama runs on the **host**, not inside the
application's current Compose stack. It is not needed for the frozen Forecast
regression or the frozen merchant model, both included under `ai-service/models/`.

Check the host installation and installed models first:

```powershell
ollama --version
ollama list
ollama ps
```

Start Ollama if needed (`ollama serve` when it is not already running). Model
downloads are explicit, not part of application startup.

The template uses `qwen3:4b` for reasoning and `qwen3-embedding:0.6b` for embeddings.
The previously used `qwen2.5:latest` reasoning model can be selected explicitly;
do not assume a model is installed because it appears in configuration.
Receipt vision uses the separate `qwen2.5vl:3b` model.

For the Qwen2.5 configuration shown below, explicitly download only the models
needed for your chosen features:

```powershell
ollama pull qwen2.5:latest
# Only for optional merchant web enrichment:
ollama pull qwen3-embedding:0.6b
# Only for receipt-image extraction:
ollama pull qwen2.5vl:3b
ollama list
Invoke-RestMethod http://localhost:11434/api/tags
```

If you keep the template's `qwen3:4b` reasoning setting instead, download that
model with `ollama pull qwen3:4b`. Downloading Qwen2.5 alone does not change the
template: set `OLLAMA_REASONING_MODEL` explicitly as below. Keep reasoning
consistent between the backend and AI service; Compose wires that automatically.

| Feature | Host Ollama required? | Without it |
| --- | --- | --- |
| Authentication, transactions, CSV, Salary Planning, Goals and score | No | Core workflows remain available |
| Forecast regression | No | Uses the packaged artifact when eligible; deterministic fallback otherwise |
| Deterministic recommendations | No | Standard explanations remain available |
| Frozen merchant model and private merchant memory | No | Eligible local suggestions still work; unknown merchants can remain for review |
| Optional French LLM explanation | Yes, reasoning model | Original deterministic wording |
| Receipt reading | Yes, vision model | Manual/CSV entry remains available |
| Merchant web enrichment | Yes, reasoning and embedding models, plus private search key | Safe abstention/manual review |

Configure only the features you intend to demonstrate in private `.env`:

```text
OLLAMA_BASE_URL=http://host.docker.internal:11434
OLLAMA_REASONING_MODEL=qwen2.5:latest
OLLAMA_EMBEDDING_MODEL=qwen3-embedding:0.6b
OLLAMA_VISION_MODEL=qwen2.5vl:3b

MERCHANT_ENRICHMENT_ENABLED=true
RECOMMENDATION_EXPLAINER_ENABLED=true
RECEIPT_VISION_ENABLED=true
```

All three feature flags default to **false**. Merchant web enrichment additionally
requires a private `OLLAMA_API_KEY`; local explanations and vision do not. Never
commit or print that key. Enabling flags is not a compatibility/accuracy check:
install the selected models explicitly and validate safe public/synthetic examples.

For local explanations and receipt reading **without web search**, leave
`MERCHANT_ENRICHMENT_ENABLED=false` and `OLLAMA_API_KEY` blank. Enable the other
two flags independently. No external key is required for these local features.
Do not expose Ollama port 11434 publicly. The AI container must reach the host
through `host.docker.internal`; if that fails, follow the
[Ollama networking guidance](https://docs.ollama.com/faq) and restrict any listener
or firewall changes to the local Docker network, not the public internet.

After configuration changes, recreate only the affected services:

```powershell
docker compose --env-file .env config --quiet
docker compose --env-file .env up -d --no-deps ai-service backend
```

Use `--build` only when source/dependencies/artifacts changed. The reasoning model
name is propagated to the backend's expected merchant-enrichment contract by Compose.

### Bounded execution and timeouts

| Setting | Template value / role |
| --- | --- |
| `AI_SERVICE_CONNECT_TIMEOUT` / `AI_SERVICE_READ_TIMEOUT` | Forecast: `2s` / `5s` |
| `AI_MERCHANT_CONNECT_TIMEOUT` / `AI_MERCHANT_READ_TIMEOUT` | Merchant backend client: `2s` / `90s` |
| `MERCHANT_BATCH_TIMEOUT_SECONDS` | `80`; bounded total merchant budget |
| `OLLAMA_READ_TIMEOUT_SECONDS` / `OLLAMA_SEARCH_TIMEOUT_SECONDS` | Template: `30` / `20`; Compose fallbacks if omitted: `12` / `8` |
| `AI_ASSISTANCE_CONNECT_TIMEOUT` / `AI_ASSISTANCE_READ_TIMEOUT` | Explanation/receipt backend client: `2s` / `35s` |
| `OLLAMA_ASSISTANCE_TIMEOUT_SECONDS` | `30`; local assistance budget |
| `OLLAMA_EMBEDDING_CPU_ONLY` | `false`; set `true` to leave limited GPU memory for reasoning/vision |
| `OLLAMA_EMBEDDING_CONTEXT_SIZE` | `1024`; bounded embedding context |

A process-local admission slot avoids simultaneous local-model contention. Busy,
invalid and unavailable inference retains safe fallback/abstention. CPU/GPU use
depends on Ollama, installed drivers and model fit; 24 GB system RAM alone does not
guarantee sufficient VRAM or fixed latency. Do not disable timeouts. Cold vision
may need a bounded larger assistance budget with a correspondingly larger backend
timeout; see [local assistance](docs/api/local-assistance.md).

Receipt uploads accept one bounded JPEG/PNG (2 MiB, four million pixels). Images
are validated and metadata-stripped in memory, not retained. Review merchant/date/
positive scale-4 amount/currency before CSV export; a missing currency is never
guessed. **Do not import a receipt already represented by bank transactions.**

### ML training and provenance

`ml-pipeline/` contains synthetic generation, dataset validation, temporal splits,
baseline experiments and controlled artifact export. Start with notebooks
`01_data_audit.ipynb`, `02_baseline_models.ipynb` and `03_export_frozen_model.ipynb`.
The deployed Forecast artifact is `forecast-huber-synthetic-tnd` version `1.0.0`,
with feature contract `forecast-residual-features-v1`.

Model training is an offline developer workflow, not a frontend feature.
Synthetic test metrics do not establish accuracy on real users. Do not silently
retrain or replace frozen artifacts; version datasets/features/results, preserve
an untouched temporal test set and validate before promotion. See the
[Forecast experiment and provenance](docs/architecture/forecast-ml.md).

## Troubleshooting

| Symptom | What to check |
| --- | --- |
| Docker cannot connect or reports Windows containers | Start Docker Desktop, enable Linux containers and check WSL/virtualization requirements in the installation guide. |
| Build/download failure | Check internet, proxy and registry access. Do not change package versions or remove volumes to fix a network failure. |
| Port already allocated | Change the conflicting host port in `.env`. For backend changes, also update `FRONTEND_API_URL`, rebuild the frontend and adjust the demo URL. Keep default UI origins unless you deliberately configure matching CORS/email origins. |
| Database password fails after editing `.env` | Existing PostgreSQL volumes retain their original credentials. Restore the correct configuration or use an explicit password-change procedure; do not delete the volume. |
| Login or a financial endpoint returns 401/403 | Verify the email through Mailpit, sign in again, and distinguish platform credentials from mock-bank credentials. Pending accounts cannot use financial APIs. |
| Health URL returns 401 | Use exactly `/actuator/health` on the configured API port, with no extra text appended. |
| A new account has no score, forecast or recommendations | Add/synchronize eligible history in the selected currency. Insufficient data is a real state, not a failed installation. |
| AI has no suggestions | Check eligibility, model readiness, flags and strict validation. Abstention is expected for unsupported/ambiguous merchants; do not automatically assign a category. |
| Local AI/receipt extraction is unavailable or slow | Check `ollama list`, host `/api/tags`, container-to-host connectivity, model names and flags. Inspect `ollama ps` for CPU/GPU use; free memory and try one request at a time. Keep human review and bounded timeouts. |
| Demo setup returns 401 or 409 | A 401 can mean the configured existing administrator/customer password is wrong. A 409 indicates a fixture conflict; stop and inspect the safe error, rather than resetting data or bypassing ownership checks. |

Inspect only the relevant service logs locally:

```powershell
docker compose ps
docker compose logs --tail 100 backend ai-service mock-bank
```

Do not publish raw logs, resolved Compose configuration, `.env` files or tokens.
The supplied Compose file is for a trusted local machine: published ports may
bind beyond loopback. Keep them behind a local firewall; do not expose the
databases, Mailpit, development bank or optional Ollama server to the internet.
For updates, first preserve local changes with an intentional commit or backup,
then retrieve the intended release and rerun Compose with the same environment
files. Rebuild changed images; never reset a dirty worktree or delete database
volumes as an update shortcut. Take a database backup before applying a release
with new migrations; application rollback is not a database rollback.

## Development and validation

This section is optional for users running the application through Docker.
Host development uses JDK 21, Maven/the included wrappers, Node.js 24 (matching
the frontend Dockerfiles) and Python 3.12 with separate virtual environments.

### Host frontend development

Run the API/infrastructure through Compose, then use a separate frontend terminal.
Avoid running both Nginx and Vite on the same host port:

```powershell
docker compose stop frontend
Set-Location frontend
npm ci
if (-not (Test-Path .env.local)) { Copy-Item .env.example .env.local }
# Set VITE_API_BASE_URL in .env.local to the actual backend URL.
npm run dev
```

For the admin UI, stop `mock-bank-ui`, enter that directory, set
`$env:VITE_MOCK_BANK_API_URL='http://localhost:8081'`, then run `npm ci` and
`npm run dev`. Keep its origin aligned with the mock-bank CORS setting.

### Host backend development

Start `postgres`, `mailpit`, `mock-bank-db` and `mock-bank` through Compose and stop
the containerized `backend` if it occupies the desired API port. Before Maven,
supply `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`, `AUTH_JWT_SECRET` and the integration
settings to the backend process in the same shell. The database password must match
the initialized database's configured password.

**Spring Boot does not automatically load the root `.env` when run with Maven.**
Compose service names resolve inside its network, not from host Java/Python.
For a host backend use `MOCK_BANK_BASE_URL=http://localhost:8081`, local Mailpit,
and an explicitly reachable AI URL if desired; internal AI port 8000 is not
published by the supplied Compose file.

```powershell
# In a shell with the required private settings already supplied:
$env:DB_URL = 'jdbc:postgresql://localhost:5432/smart_financial_wellness'
$env:DB_USERNAME = 'smart_financial_wellness'
$env:MOCK_BANK_BASE_URL = 'http://localhost:8081'
$env:SMTP_HOST = 'localhost'
$env:SMTP_PORT = '1025'
Set-Location backend
.\mvnw.cmd spring-boot:run
```

Adjust host ports/database names to your configuration. See the
[authentication guide](docs/api/authentication-profile.md) for the full process
variables. The mock-bank database is not exposed on the host; do not point a
host mock-bank process at the application's database.

### Host Python development

Create separate virtual environments for `ai-service` and `ml-pipeline`.
For example, in `ai-service`:

```powershell
py -3.12 -m venv .venv
.\.venv\Scripts\Activate.ps1
python -m pip install -e ".[test]"
uvicorn forecast_ai.main:app --host 127.0.0.1 --port 8000
```

For host Ollama calls use `OLLAMA_BASE_URL=http://localhost:11434`, not the
container-specific hostname. A host backend must use `AI_SERVICE_BASE_URL=http://localhost:8000`
to call that host FastAPI process. In `ml-pipeline`, install `".[test]"` in its
own environment and run `jupyter lab` to open the notebooks.

### Validation commands

These are commands to run, **not a claim that they were rerun for this README**.
Run each from the indicated directory:

| Directory | Command |
| --- | --- |
| `backend` | `.\mvnw.cmd verify` (or `mvn verify` if wrapper downloads are unavailable) |
| `mock-bank` | `mvn verify` (no Maven wrapper is included in this module) |
| `frontend` | `npm test`, `npm run lint`, `npm run build` |
| `mock-bank-ui` | `npm test`, `npm run lint`, `npm run build` |
| `ai-service` | `python -m pytest` in its test-enabled virtual environment |
| `ml-pipeline` | `python -m pytest` in its test-enabled virtual environment |
| Repository root | `powershell.exe -NoProfile -ExecutionPolicy Bypass -File ./scripts/demo/test_demo_scripts.ps1` |
| Repository root | `docker compose --env-file .env config --quiet`, `git diff --check` |

On Unix-like shells, Maven wrappers use `./mvnw`. Java integration tests require
Docker and use isolated PostgreSQL Testcontainers with Flyway; they do not use the
persistent Compose databases. Automated AI tests use controlled providers, not
real Ollama/web requests; authentication mail tests use a fake delivery mechanism.

Do not infer release acceptance from a historical test count. Validate the combined
worktree and the intended demo environment before delivery.

## Security and data handling

- Access JWTs are short-lived; the main frontend keeps them in memory.
- Refresh tokens and action tokens are hash-only in PostgreSQL. Refresh rotation
  is transactional; consumed-token reuse revokes its family.
- Refresh cookies are HttpOnly, SameSite=Strict and path-scoped. Enable Secure
  for HTTPS deployment; local HTTP configuration is not production hardening.
- Every authenticated request resolves current user/identity status. Financial
  endpoints require both to be ACTIVE; ownership comes from the principal.
- Passwords use BCrypt with a UTF-8 72-byte limit. Profile writes cannot change
  email, identifiers, verification or account status.
- Verification/reset email dispatch happens after commit. SMTP failure does not
  undo a pending account; cooldowns are enforced server-side.
- Bank customer identifiers remain inside the backend adapter. Cross-customer
  bank reads return `404 Account not found`.
- Bank access currently uses a synthetic login/JWT flow, **not implemented OAuth
  Authorization Code with PKCE**. Schema/design references are not executable OAuth.
- Provider fetches and AI calls do not run inside PostgreSQL write transactions.
  Invalid AI batches do not partially persist predictions.
- Sensitive operations are audited; credentials and model prompts/responses must
  not be added to logs. Scores and produced factors are immutable.
- Never commit private environment files, credentials, raw financial datasets,
  generated outputs or model artifacts outside their approved versioned locations.

## API overview

The core API derives ownership from the authenticated principal. Amounts, eligibility
and currency constraints are validated server-side; passing an arbitrary user ID
is not an authorization mechanism.

| Area | Main core-backend routes |
| --- | --- |
| Authentication | `POST /api/auth/register`, `login`, `refresh`, `logout`; `GET /api/auth/me` |
| Account actions | `POST /api/auth/email-verification/confirm`, `resend`; `POST /api/auth/password-reset/request`, `confirm` |
| Profile | `GET`, `PUT /api/profile` |
| Bank connection | `GET`, `POST`, `DELETE /api/bank-connections/mock-bank` |
| Synchronization | `POST /api/bank/synchronizations`; `GET /api/bank/synchronizations/status` |
| Local data | `GET /api/accounts`, `/api/transactions`, `/api/dashboard/summary?month=YYYY-MM` |
| Categories | `GET`, `POST /api/categories`; `PATCH`, `DELETE /api/categories/{id}`; `GET /api/categories/summary` |
| Correction/review | `PATCH /api/transactions/{id}/category`; `POST /api/categorization-runs`, `/api/categorization-runs/merchant-recognition`; `GET /api/categorization-reviews`, `/api/merchant-memories` |
| CSV | `POST /api/imports/accounts`, `/api/imports/csv/preview`, `/api/imports/csv/{jobId}/confirm` |
| Salary Planning | `GET`, `PUT /api/salary-plans/{month}?currency=TND`; `GET /api/salary-plans`, `/api/salary-plans/proposals` |
| Goals | `GET`, `POST /api/goals`; `GET`, `PUT`, `DELETE /api/goals/{id}`; `PATCH /api/goals/{id}/status` |
| Contributions/scenarios | `GET`, `POST /api/goals/{id}/contributions`; `DELETE /api/goals/{id}/contributions/{contributionId}`; `POST /api/goals/{id}/simulations` |
| Capacity allocation | `GET`, `PUT /api/goals/allocations/{month}?currency=TND` |
| Forecast | `POST /api/forecasts/current`; `GET /api/forecasts`, `/api/forecasts/{id}`; `POST /api/forecasts/{id}/simulations` |
| Score | `POST /api/financial-scores/current`; `GET /api/financial-scores`, `/api/financial-scores/{id}` |
| Recommendations | `POST /api/recommendations/current`; `GET /api/recommendations`; `PATCH /api/recommendations/{id}/status`; `POST /api/recommendations/{id}/explanation` |
| Receipt preview | `POST /api/receipts/extract` (multipart `file`) |

Use exact `currency`, `month`, timezone and pagination parameters where required;
see controllers and linked API guides for request/response schemas. Goal deletion
archives rather than erasing financial history. Transaction-linked contributions
cannot be deleted through the manual-contribution endpoint.

The mock bank separately exposes `POST /api/auth/login`, customer-scoped read
APIs under `/api/v1/customers/{customerId}/accounts` (metadata, balances and
transactions), and administrator-only management under `/api/admin`.
The main frontend never calls port 8081 directly.

FastAPI provides `/health`, Forecast and merchant model/prediction endpoints,
`POST /api/v1/recommendations/explain` and `POST /api/v1/receipts/extract`.
Its supplied deployment is internal and must not be treated as a public browser API.

## Architecture diagrams and documentation

Open **[Structurizr diagrams](http://localhost:8085/workspace/1/diagrams)** when
the separate local viewer is running. Its ten views cover context, containers,
backend/frontend/AI/mock-bank/ML components and deployment.

```powershell
docker start sfwa-structurizr
```

For first creation, native DSL validation and viewing instructions, see the
[C4 guide](docs/architecture/c4/README.md). The canonical model is
[workspace.dsl](docs/architecture/c4/workspace.dsl); the previous
[offline HTML viewer](docs/architecture/c4/index.html) remains an independent
historical export, not an automatically synchronized native workspace.

| Guide | Contents |
| --- | --- |
| [System overview](docs/architecture/system-overview.md) | Service boundaries and source evidence |
| [Authentication and profile](docs/api/authentication-profile.md) | Tokens, activation, password reset and configuration |
| [Mock-bank connection](docs/api/mock-bank-connection.md) | Connection lifecycle, adapter, sync and provider contracts |
| [Transaction categories](docs/api/transaction-categories.md) | Rules, manual correction, merchant review and private memory |
| [CSV import](docs/api/csv-import.md) | Supported formats, preview/mapping, idempotency and overlap warning |
| [Forecast API](docs/api/forecast.md) / [ML experiment](docs/architecture/forecast-ml.md) | Deterministic fallback, synthetic Huber evaluation and provenance |
| [Financial score](docs/api/financial-wellness-score.md) | Factors, eligibility, immutable snapshots and limitations |
| [Recommendations](docs/api/recommendations.md) | Rule thresholds, feedback and justification integrity |
| [Local assistance](docs/api/local-assistance.md) | Ollama setup, bounded inference, explanations and receipt vision |
| [Sample-data scenario](docs/demo/local-demo.md) | Seeding, verification, rollover and demo-only reset |
| [Data model](docs/data-model.md) / [Flyway schema](docs/database/flyway-schema.md) / [ER diagrams](docs/database/entity-relationship-diagrams.md) | Consolidated database design; verify implemented extensions against migrations |

A [synthetic TND CSV](docs/demo/csv-import-test-tnd.csv) is available for import
testing. Inspect its dates/account currency before importing; dated fixtures do
not automatically become current-month activity. Older prototype or release
reports describe their own snapshot, not the current runtime's acceptance state.

## Limitations and next steps

- **Synthetic provider only:** no real-bank integration, OAuth/PKCE, payments or
  autonomous money transfers.
- **Privacy workflows:** profile editing is real, but consent displays and
  authenticated export/account-deletion actions still include prototype behavior.
  No complete user-deletion workflow is implemented.
- **Import scope:** CSV only; no Excel, separate debit/credit columns or automatic
  reconciliation of overlapping files/bank data. Importing receipts can duplicate
  existing banking activity.
- **Score completeness:** `ESSENTIAL_EXPENSE_BURDEN` and `BALANCE_RESILIENCE` remain
  unavailable because a reliable essential-expense classification is absent.
  They are not fabricated or scored as zero.
- **AI limitations:** synthetic-model accuracy is not proven on real users;
  unsupported currencies retain deterministic Forecast; merchant reasoning may
  abstain. Receipt quality/latency depends on the image, model and hardware.
- **UI/preferences:** saved interface preferences do not imply every theme,
  notification or accessibility setting is applied throughout the UI. Responsive
  tests do not establish a formal WCAG compliance audit.
- **Operational hardening:** CI/security checks, backup/restore, production TLS,
  deployment-specific secret handling, monitoring and privacy/retention policy
  need dedicated release work. Mailpit and development bank identities are local
  demonstration infrastructure.

Next improvements should preserve deterministic financial facts, currency isolation,
human review and safe AI fallback. This application is an educational assistant:
its scores, estimates and suggestions are **informational, never guaranteed
financial advice**.
