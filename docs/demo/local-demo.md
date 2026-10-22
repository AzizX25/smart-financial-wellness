# Optional local sample data

Use this scenario to explore the application with a synthetic financial history.
All names, accounts, employers, transactions and amounts are fictional. It does
not connect to a real bank, send payments or provide guaranteed financial advice.

## Requirements

Complete the [main setup guide](../../README.md#local-setup-with-docker-compose)
first. The application, mock bank, databases and Mailpit must be running. Ollama
and web search are not required for sample preparation.

The scenario uses the fixed identity `amine.bensalem@example.test`, bank username
`amine.bensalem.demo`, currency TND, French locale and `Africa/Tunis` timezone.
This account is not created by cloning the repository or by signing into GitHub.

## Private configuration

From the repository root, in PowerShell:

```powershell
if (-not (Test-Path .env.demo)) { Copy-Item .env.demo.example .env.demo }
```

Edit `.env.demo` privately. Set `DEMO_PLATFORM_PASSWORD` and `DEMO_BANK_PASSWORD`
to locally chosen values of 12–72 UTF-8 bytes. Set
`DEMO_MOCK_BANK_ADMIN_PASSWORD` to the **existing** `admin.demo` administrator's
password, obtained privately from the project maintainer. This file cannot set or
reset the provisioned administrator password. Do not commit or print its contents.
Use the same passwords for an existing scenario; failed login is not permission
to overwrite an account.

Keep `DEMO_RUN_MERCHANT_AI=false`. The template's profile and fixture opt-in are
required together; do not enable demo mutations in a public deployment. Check
the three `DEMO_*_URL` values if you changed the API or Mailpit ports.

## Prepare and verify

```powershell
docker compose --env-file .env --env-file .env.demo config --quiet
docker compose --env-file .env --env-file .env.demo up -d --build
powershell.exe -NoProfile -ExecutionPolicy Bypass -File ./scripts/demo/seed_demo.ps1
powershell.exe -NoProfile -ExecutionPolicy Bypass -File ./scripts/demo/verify_demo.ps1
```

Use `pwsh` instead of `powershell.exe` with PowerShell 7. Keep both environment
files in subsequent Compose commands that recreate this demo setup. Shell
environment variables override file values; the scripts load `.env.demo`, not
the private root `.env`.

The neutral command names are compatibility entry points: they preserve the
original scenario version and call the existing authenticated workflow. They do
not bypass authorization, change stored identifiers or modify ordinary accounts.

Preparation reconciles missing resources without resetting an existing fixture.
It creates two TND accounts with at least eight completed months and elapsed
current-month activity, transaction-review examples, Salary Plans and two goals
with legitimate contributions and allocations. Forecast uses its normal API and
can retain deterministic fallback. Verification prints a PASS/FAIL table and
returns a nonzero status on failure.

The default date is today. To reproduce an earlier date within the current month:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File ./scripts/demo/seed_demo.ps1 -AsOfDate yyyy-MM-dd
powershell.exe -NoProfile -ExecutionPolicy Bypass -File ./scripts/demo/verify_demo.ps1 -AsOfDate yyyy-MM-dd
```

Replace `yyyy-MM-dd` with an actual date. Never choose a date before already
present operations. Month rollover extends the original ledger rather than
rewriting it. A 409 conflict must be investigated; it is not a reason to reset
or manually delete database rows.

## Explore the application

Open [the application](http://localhost:5173) and sign in with the platform email
and locally configured platform password. Bank and administrator credentials are
separate. Suggested order:

1. Dashboard: balances, actual spending, score and justified priorities.
2. Ma banque: connection status, manual refresh and preserved history.
3. Transactions: filters, manual editing, category approval and CSV preview.
4. Categories: system categories and private user choices.
5. Salary Planning: expected income, planned expenses and saving capacity.
6. Goals: contributions, capacity allocation and temporary contribution scenarios.
7. Forecast: month-end estimate and temporary income/expense changes.
8. Optional local AI: review an explanation or a synthetic receipt separately.

Allocation is not a contribution; a forecast is not a bank balance. AI suggestions
remain pending until approval. Never import a receipt already represented by a
bank transaction: overlapping sources are not automatically reconciled.

## Stop and removal limits

`docker compose stop` preserves the databases. Routine seeding must not use a
reset switch. The optional explicit `-ResetDemoOnly` removes only the marked
mock-bank fixture after identity checks; it does **not** erase the platform user
or synchronized application history. No complete platform-account deletion
workflow is provided. Use a dedicated disposable installation for destructive
experiments; do not remove a shared database volume or change an ordinary user.

No local password or API key is included in this guide. Without the existing bank
administrator credential, use normal registration and manual/CSV entry instead.
