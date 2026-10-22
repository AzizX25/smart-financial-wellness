# Local installation validation - 22 October 2026

This record describes local validation of the combined application source and a
subsequent first-run administrator setup check.
It is not a security certification, production-readiness claim or a guarantee of
AI accuracy. The root README is the installation and usage guide.

## Automated validation

The initial combined-worktree run, before the administrator-setup follow-up:

| Module | Command (from that module) | Result |
| --- | --- | --- |
| Backend | `.\mvnw.cmd verify "-Dlogging.level.root=ERROR" "-Ddebug=false"` | 234 tests; 0 failures, errors or skipped; BUILD SUCCESS |
| Mock bank | `mvn verify "-Dlogging.level.root=ERROR" "-Ddebug=false"` | 70 tests; 0 failures, errors or skipped; BUILD SUCCESS |
| Frontend | `npm test`; `npm run lint`; `npm run build` | 154 tests in 24 files; lint and production build passed |
| Bank administration UI | Same npm commands | 7 tests in 2 files; lint and production build passed |
| AI service | `.\.venv\Scripts\python.exe -m pytest` | 86 tests passed |
| Offline ML pipeline | Same Python command in its own environment | 78 tests passed |
| Demo scripts | `powershell.exe -NoProfile -ExecutionPolicy Bypass -File ./scripts/demo/test_demo_scripts.ps1` | Passed |
| Compose template | `docker compose --env-file .env.example config --quiet` | Passed |
| Git whitespace | `git diff --check` | Passed |

Java integration tests used isolated PostgreSQL Testcontainers, not an existing
application database. Automated inference tests did not call real web/Ollama
providers. Existing Java-agent, Python dependency-deprecation and physical-core
detection warnings remain; they did not fail the commands.

Both UI installs reported development-dependency audit findings, including
critical findings. Separate `npm audit --omit=dev --json` checks reported zero
known production-dependency findings at the time of validation. This distinction
does not establish that development tools or deployed images are vulnerability
free. Dependency remediation is separate work; no automatic upgrades were made.

## Isolated first-start check

A separate source copy was assembled from the tracked files and pending release
source, excluding private environment files, Git metadata, generated output and
academic documents. All five custom images were built through the existing
Compose configuration, with an isolation-only override:

- Separate Compose project and fresh application/bank PostgreSQL volumes.
- Loopback-only test ports, not the usual application ports.
- Fresh random signing/database credentials held only in the launch process.
- Local AI explanation, receipt vision, web enrichment and demo mutations disabled.
- No existing user's records, credentials or database volumes reused.

The application applied 24 Flyway migrations successfully in
`smart_financial_wellness`, ending at V24. Hibernate initialized with
`ddl-auto=validate`; Tomcat started on internal port 8080. Both API health
endpoints returned UP. FastAPI reported UP with both packaged models available
and merchant enrichment disabled. Both browser application entry pages returned
HTTP 200.

Normal HTTP APIs verified an anonymous request is rejected; registration creates
a pending account; the captured verification email can activate it; login and
principal-scoped profile access succeed. One clearly synthetic user then created
a TND account and categorized manual debit, saved a Salary Plan with persisted
recommendation metadata, created a goal, recorded a contribution, allocated
capacity and requested a temporary contribution simulation. The allocation did
not increase actual saved progress. No password, token or authorization header
was printed.

## Sharing safeguards and limits

Local report drafts, report archives and rendered output are excluded from Git.
Private environment files remain ignored. Frontend, bank-UI and AI Docker
contexts exclude private environment files. Candidate text files were checked
for high-confidence key/private-key patterns without printing matching values;
this heuristic is not a complete secret audit.

The initial sample-data workflow needed an existing administrator credential.
The follow-up below removes that handover dependency through explicit local
first-run provisioning, without providing a password-reset facility. Ordinary
users can also register and use manual/CSV entry without sample preparation.

Real Ollama models, GPU performance, web-search credentials, receipt quality and
a complete browser walkthrough were not revalidated in this check. They remain
optional and hardware/configuration-dependent. The first core runtime check used
a separate synthetic user; the subsequent sample check is described below.

The local setup is intended for evaluation, not public banking deployment. Keep
synthetic development identities, local email capture and database ports private.

## First-run local administrator follow-up

The new startup provisioner runs only with the `demo` profile and explicit
`MOCK_BANK_ADMIN_BOOTSTRAP_ENABLED=true`. Default, `prod` and `production`
profiles cannot register it. An installer supplies a private password for a
separate local administrator; only its BCrypt hash is stored. Existing accounts
are never overwritten or reactivated, and no HTTP bootstrap endpoint is added.

Focused tests passed: 12 tests, zero failures/errors/skipped. Full mock-bank
verification then passed 82 tests, zero failures/errors/skipped. Coverage includes
normal admin login, hash-only storage, unchanged repeated starts, incorrect and
inactive existing credentials, UTF-8 byte limits, concurrent provisioning and
profile/flag restrictions. Demo script checks, default/demo Compose mappings and
`git diff --check` also passed. No dependencies or Flyway migrations changed.

Only the mock-bank image was rebuilt in the isolated installation. Fresh random
test passwords were supplied through the launch process, not private files or
source code. A separate `local.demo.admin` was provisioned beside the unchanged
legacy administrator. Amine's synthetic sample scenario was prepared twice and
then verified through the normal application APIs, without an existing shared
administrator password, real Ollama or web calls.

All 15 verifier checks passed: two TND accounts, 166 stable transaction references,
eight completed months, reconciled ledger balances, internal-transfer pairing,
synchronization, locale, Salary Plans, goals/contributions/allocations, Forecast
prerequisites, categorization cases and ownership. Counts were unchanged by the
second preparation. These counts describe the 22 October 2026 as-of date, not a
permanent fixture size. Existing application volumes and private environment
files were not modified by this isolated check.
