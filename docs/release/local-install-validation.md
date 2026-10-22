# Local installation validation - 22 October 2026

This record describes one local validation of the combined application source.
It is not a security certification, production-readiness claim or a guarantee of
AI accuracy. The root README is the installation and usage guide.

## Automated validation

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

The sample-data workflow still requires the existing mock-bank administrator
credential, supplied privately. Editing its password in `.env.demo` does not
provision or reset the administrator. Ordinary users can register and use
manual/CSV entry without that optional sample scenario.

Real Ollama models, GPU performance, web-search credentials, receipt quality and
a complete browser walkthrough were not revalidated in this check. They remain
optional and hardware/configuration-dependent. The entire existing Amine sample
scenario was not seeded again: the runtime check used a separate synthetic user.

The local setup is intended for evaluation, not public banking deployment. Keep
synthetic development identities, local email capture and database ports private.
