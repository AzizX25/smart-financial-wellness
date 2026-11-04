# Local credential hardening

This is a synthetic, local demonstration application, not a production banking
deployment. Public source code must not supply reusable installation credentials.

## New installations

Run `scripts/setup/initialize_local_environment.ps1` from PowerShell. It writes
only a new, ignored `.env`, using the operating system's cryptographic random
generator for four independent secrets. It prints no values and never overwrites
an existing file. Keep the file private, protect its filesystem permissions and
back it up securely. The public `.env.example` contains blank secret fields.

Use the private local-administrator/demo workflow in `docs/demo/local-demo.md`.
Do not use the historical seeded bank logins. Mock-bank V5 retires unchanged
known-password credentials while preserving accounts, balances and transactions.
No backend Flyway migration is required for this correction.

All Compose-published ports bind to `127.0.0.1`; the bank database and AI API
remain internal. This reduces network exposure but does not replace strong
credentials, authorization, host security or a production deployment review.

## Existing installations: explicit rotation, never a reset

The setup script deliberately refuses an existing `.env`. This change does not
automatically rotate a running installation or modify a private configuration.

1. Keep local services inaccessible from other machines and pause use while
   rotating. Back up existing data and private configuration securely.
2. Privately compare configured signing keys/passwords with historical published
   examples. Treat matching or reused values as compromised; never paste values
   into issues, chat, logs, screenshots or shell command arguments.
3. Generate independent random replacement signing keys. Store them privately,
   update the installation and restart the affected services. Revoke affected
   refresh sessions as part of incident handling; access-key rotation alone does
   not revoke refresh credentials. Users may need to sign in and reconnect banks.
4. Rotate database-role passwords in PostgreSQL through an authenticated,
   interactive password-change workflow, then update matching private settings.
   Editing `.env` alone does not change a password held in an existing volume.
5. Apply mock-bank V5 through normal Flyway startup. It targets only exact seeded
   row identifiers whose password-hash fingerprint still matches the published
   fixture. A password already independently changed is not overwritten. Both
   changed and unchanged accounts retain financial records. Never edit V1-V4,
   run Flyway repair, or delete volumes to apply this correction.
6. The old administrator is not a setup mechanism. Provision a separately named
   administrator through the existing opt-in local demo workflow. Disabled
   administrators cannot keep using an unexpired JWT after the corrected service
   starts. Reactivating a retired customer without setting a new private password
   does not restore its old password because V5 replaces that hash.
7. Verify normal login, isolation, synchronization and demo reconciliation without
   printing tokens or credentials. Confirm old credentials/tokens no longer work.

Do not overwrite previously chosen account passwords silently. If the configured
administrator was the retired legacy account, use a new unused local username.
After confirming `local.demo.admin` is unused and taking protected backups of
both databases and configuration, `scripts/setup/replace_legacy_demo_administrator.ps1`
can replace only a legacy `admin.demo` entry in `.env.demo`. Supply
`-BackupDirectory` pointing to the protected backup directory containing the
matching original `.env.demo`. The command generates a new password privately,
enables the existing local bootstrap, preserves other settings, and refuses
unmatched backups, other identities or repeated replacement. It does not itself
connect to a database or reset an existing account.

## Repository checks and historical releases

The secret-check workflow scans a clean current-source checkout using a pinned,
checksum-verified Gitleaks release, and verifies secret templates/setup behavior.
It does not claim to erase or sanitize Git history. Historical V3/V4 SQL stays
byte-for-byte unchanged to preserve applied Flyway checksums.
The scanner has one exact, path-and-line-scoped exception for the Structurizr
diagram identifier `C2-Application`; no credential paths are broadly ignored.

The historical `v1.0.0-demo` tag predates these protections. Do not use it for a
new installation; use `v1.0.1-demo` or maintained `main`. If actual
provider keys were ever exposed, revoke them at their provider first. A separate
coordinated history-cleanup decision may follow; no force push is part of this fix.

Enable GitHub secret-scanning alerts and push protection in repository settings.
Adding a workflow does not enable those account-level settings automatically.

## Local validation - 4 November 2026

- Mock bank: `mvn verify "-Dlogging.level.root=ERROR" "-Ddebug=false"`:
  89 tests, zero failures, errors or skipped. Includes fresh V5, V4-to-V5
  upgrade, preservation of independently changed/ordinary accounts, normal
  authentication, disabled-administrator JWT rejection and Hibernate validation.
- Backend: `mvnw.cmd "-Dtest=JwtSigningKeyTests,AuthenticationProfileIntegrationTests,AuthenticationActionIntegrationTests" test "-Dlogging.level.root=ERROR" "-Ddebug=false"`:
  24 tests passed. A second focused run for `BankConnectionIntegrationTests`,
  `BankSynchronizationIntegrationTests`, `CategoryIntegrationTests` and
  `MockBankHttpClientTests` passed all 52 tests after replacing retired fixtures.
- Bank administration UI: `npm test` passed seven tests; `npm run lint` passed.
  Only one test input changed; no UI production behavior changed.
- Both PowerShell setup and existing demo script checks passed. Compose validated
  using a newly generated temporary environment, not a private installation file.
  A blank template was refused and all seven published ports were loopback-only.
- Gitleaks 8.30.1 reported zero remaining current-source matches using the narrow
  C4 identifier exception. This is not a guarantee that credentials are absent:
  known historical passwords were identified separately through code inspection.
- `git diff --check` passed. Existing backend V1-V24 and mock-bank V1-V4 bytes
  remained unchanged. No dependency versions changed.

These initial tests used disposable databases. During that initial phase, no
running application was rebuilt, no existing database was migrated, and no
private configuration was read or rotated. The separately approved local upgrade
uses protected backups, an unused local administrator and the same normal Flyway
and authentication mechanisms; it does not change ordinary account passwords.

### Approved existing-installation follow-up

On 4 November 2026, custom-format backups of both existing databases were taken
and their archive indexes validated. Private configuration copies were saved in
a Git-ignored directory restricted to the local Windows account. No volume was
deleted. Only the exposed legacy administrator configuration was replaced with
a separate, unused local administrator and a cryptographically generated password.

The rebuilt Java services started successfully. PostgreSQL was healthy, bank
Flyway reached V5, backend Flyway remained V24, Hibernate validation initialized,
and both health endpoints returned UP. All 14 pre-upgrade financial-table
fingerprints matched before the normal API smoke checks. Legacy administrator
and seeded customer logins returned 401; the new administrator logged in normally.

An existing synthetic bank-password/configuration mismatch was discovered. Its
stored hash matched the pre-upgrade backup, confirming the upgrade had not changed
it. With separate explicit approval, only the fixed synthetic customer was
verified by customer identifier, username, display name, country and its two
marked TND accounts, then reset through the normal authenticated administrator
API. Its generated password was saved only in private configuration. No platform,
database or signing-key credential was changed.

Normal platform login, principal-scoped profile access, owned bank reconnection,
account/history retrieval and manual synchronization then passed. Synchronization
created zero transactions and preserved the existing history count. Anonymous
financial access returned 401. Both UI entry pages returned 200. No Ollama/web
request was needed for these checks. Historical/demo data was not regenerated.

GitHub secret scanning and push protection were enabled. GitHub did not activate
the requested non-provider-pattern setting; generic current-source detection is
also covered by the pinned Gitleaks workflow. Scanner results are not a substitute
for the credential audit or a guarantee of production security.

### Changed-file inventory

Configuration and automation:

- `.env.example`
- `compose.yaml`
- `.gitleaks.toml`
- `.github/workflows/secret-scan.yml`
- `scripts/setup/initialize_local_environment.ps1`
- `scripts/setup/test_local_environment.ps1`
- `scripts/setup/replace_legacy_demo_administrator.ps1`
- `scripts/setup/test_legacy_demo_administrator.ps1`

Backend (paths relative to `backend/src`):

- `main/java/com/azizx25/smartfinancialwellness/config/SecurityConfiguration.java`
- `main/java/com/azizx25/smartfinancialwellness/security/JwtSigningKey.java`
- `test/java/com/azizx25/smartfinancialwellness/PostgresTestConfiguration.java`
- `test/java/com/azizx25/smartfinancialwellness/security/JwtSigningKeyTests.java`
- `test/java/com/azizx25/smartfinancialwellness/BankConnectionIntegrationTests.java`
- `test/java/com/azizx25/smartfinancialwellness/BankSynchronizationIntegrationTests.java`
- `test/java/com/azizx25/smartfinancialwellness/CategoryIntegrationTests.java`
- `test/java/com/azizx25/smartfinancialwellness/bank/infrastructure/mockbank/MockBankHttpClientTests.java`

Mock bank (Java paths relative to `mock-bank/src`):

- `main/java/com/azizx25/mockbank/config/MockBankSecurityConfiguration.java`
- `main/java/com/azizx25/mockbank/security/JwtSigningKey.java`
- `main/java/com/azizx25/mockbank/security/BankCustomerAuthorizationFilter.java`
- `main/resources/db/migration/V5__disable_published_fixture_credentials.sql`
- `test/java/com/azizx25/mockbank/MockBankPostgresTestConfiguration.java`
- `test/java/com/azizx25/mockbank/TestBankCredentials.java`
- `test/java/com/azizx25/mockbank/PublishedCredentialMigrationTests.java`
- `test/java/com/azizx25/mockbank/security/JwtSigningKeyTests.java`
- `test/java/com/azizx25/mockbank/AdminManagementIntegrationTests.java`
- `test/java/com/azizx25/mockbank/MockBankAuthenticationIntegrationTests.java`
- `test/java/com/azizx25/mockbank/MockBankApplicationTests.java`

Bank UI test and documentation:

- `mock-bank-ui/src/App.test.jsx`
- `README.md`
- `docs/api/mock-bank-connection.md`
- `docs/demo/local-demo.md`
- `docs/security/local-credentials.md`
