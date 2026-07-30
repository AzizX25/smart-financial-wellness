# Authentication and Profile Backend API

The backend supports local email/password registration, email verification, short-lived JWT access tokens, rotating refresh tokens, password reset, logout, current-user lookup, and profile retrieval/update. The React authentication onboarding flow uses the registration and verification endpoints.

## Configuration

The backend requires PostgreSQL connection variables and a JWT signing secret:

| Variable | Required | Purpose |
| --- | --- | --- |
| `DB_URL` | Yes | PostgreSQL JDBC URL |
| `DB_USERNAME` | Yes | PostgreSQL application username |
| `DB_PASSWORD` | Yes | PostgreSQL password; it must match the local Compose password |
| `AUTH_JWT_SECRET` | Yes | Base64-encoded secret that decodes to at least 32 random bytes |
| `AUTH_JWT_ISSUER` | No | JWT issuer; defaults to `smart-financial-wellness-backend` |
| `AUTH_JWT_AUDIENCE` | No | Required JWT audience; defaults to `smart-financial-wellness-frontend` |
| `AUTH_ACCESS_TOKEN_TTL` | No | Access-token duration; defaults to `15m` |
| `AUTH_REFRESH_TOKEN_TTL` | No | Refresh-token duration; defaults to `30d` |
| `AUTH_EMAIL_VERIFICATION_TOKEN_TTL` | No | Email-verification-token duration; defaults to `24h` |
| `AUTH_EMAIL_VERIFICATION_RESEND_COOLDOWN` | No | Server-side verification resend cooldown; defaults to `30s` |
| `AUTH_PASSWORD_RESET_TOKEN_TTL` | No | Password-reset-token duration; defaults to `30m` |
| `AUTH_PASSWORD_RESET_REQUEST_COOLDOWN` | No | Server-side password-reset request cooldown; defaults to `30s` |
| `AUTH_REFRESH_COOKIE_SECURE` | No | Enables the cookie `Secure` flag; defaults to `false` for local HTTP and must be `true` with production HTTPS |
| `AUTH_BCRYPT_STRENGTH` | No | BCrypt work factor from 4 through 31; defaults to 12 |
| `CORS_ALLOWED_ORIGIN` | No | Single allowed browser origin; defaults to `http://localhost:5173` |
| `SMTP_HOST` | No | SMTP hostname; defaults to `localhost` |
| `SMTP_PORT` | No | SMTP port; defaults to `1025` |
| `SMTP_USERNAME` | No | Optional SMTP username; empty for local Mailpit |
| `SMTP_PASSWORD` | No | Optional SMTP password; empty for local Mailpit |
| `SMTP_AUTH` | No | Enables SMTP authentication; defaults to `false` |
| `SMTP_STARTTLS_ENABLE` | No | Enables STARTTLS; defaults to `false` |
| `SMTP_STARTTLS_REQUIRED` | No | Requires STARTTLS; defaults to `false` |
| `EMAIL_SENDER_ADDRESS` | No | Sender address; defaults to a non-routable local address |
| `EMAIL_SENDER_NAME` | No | Sender display name; defaults to `Mon Equilibre` |
| `FRONTEND_BASE_URL` | No | Base URL used for action links; defaults to `http://localhost:5173` |

The root `.env` configures Docker Compose only. Spring Boot does not load it automatically when Maven starts the backend. Export the variables in the same shell that runs Maven; never commit their real values.

```powershell
$env:DB_URL = "jdbc:postgresql://localhost:5432/smart_financial_wellness"
$env:DB_USERNAME = "smart_financial_wellness"
$env:DB_PASSWORD = "<same local password as .env>"
$jwtSecretBytes = [byte[]]::new(32)
[System.Security.Cryptography.RandomNumberGenerator]::Fill($jwtSecretBytes)
$env:AUTH_JWT_SECRET = [Convert]::ToBase64String($jwtSecretBytes)
$env:AUTH_REFRESH_COOKIE_SECURE = "false"
$env:CORS_ALLOWED_ORIGIN = "http://localhost:5173"
$env:SMTP_HOST = "localhost"
$env:SMTP_PORT = "1025"
$env:SMTP_AUTH = "false"
$env:SMTP_STARTTLS_ENABLE = "false"
$env:EMAIL_SENDER_ADDRESS = "no-reply@smart-financial-wellness.local"
$env:EMAIL_SENDER_NAME = "Mon Equilibre"
$env:FRONTEND_BASE_URL = "http://localhost:5173"

Set-Location backend
mvn spring-boot:run
```

Use a secret manager and HTTPS in deployed environments.

## Endpoints

| Method and path | Authentication | Behavior |
| --- | --- | --- |
| `POST /api/auth/register` | Public | Creates pending user and email identity records, hashes the password with BCrypt, and issues access/refresh tokens |
| `POST /api/auth/login` | Public | Validates normalized email and password and issues a new token pair |
| `POST /api/auth/refresh` | Refresh cookie | Rotates the refresh token and returns a new access token |
| `POST /api/auth/email-verification/confirm` | Public action token | Consumes a valid verification token and atomically activates the identity and user |
| `POST /api/auth/email-verification/resend` | Public | Safely requests a replacement verification email without revealing account existence |
| `POST /api/auth/password-reset/request` | Public | Returns a generic response and emails only eligible verified accounts |
| `POST /api/auth/password-reset/confirm` | Public action token | Changes the BCrypt password and revokes existing refresh sessions |
| `POST /api/auth/logout` | Bearer JWT | Revokes the presented refresh session and clears its cookie |
| `GET /api/auth/me` | Bearer JWT | Returns the authenticated user and current account/identity statuses |
| `GET /api/profile` | Bearer JWT | Returns identity, profile, interface, financial, and notification preferences |
| `PUT /api/profile` | Bearer JWT | Replaces editable profile and interface/financial preference values |

The profile update contract deliberately excludes IDs, email, account status, identity status, and verification fields. Unknown input fields are rejected.

## Token and Account Rules

The access JWT contains the user UUID as its subject plus standard issuer, audience, issued-at, not-before, expiry, and token-ID claims. It does not contain email, password data, roles, or account status. Each authenticated request reloads the user and identity from PostgreSQL, so suspension, disabling, deletion pending, or deletion takes effect before an unexpired JWT can reach an endpoint.

New registrations remain truthfully in `PENDING_VERIFICATION` with no fabricated verification timestamp. Pending users may access only refresh, logout, current-user retrieval, and profile retrieval/update. All current and future business endpoints require both an `ACTIVE` user and an `ACTIVE` authentication identity. A valid, single-use email-verification token atomically promotes both records to `ACTIVE`.

Refresh values are opaque 256-bit random tokens stored only as SHA-256 hashes. The browser cookie is `HttpOnly`, `SameSite=Strict`, scoped to `/api/auth`, and optionally `Secure`. Rotation is transactional. Reuse of a consumed token revokes every session in its token family.

Passwords are never returned or logged. BCrypt input is limited to 72 UTF-8 bytes, rather than 72 Java characters.

Email-verification and password-reset values are opaque 256-bit random tokens stored only as SHA-256 hashes. They are expiring, purpose-specific and single-use. Resending invalidates previous unused tokens of the same purpose. Password reset revokes every active refresh session and never authenticates the user automatically.

Verification and password-reset emails are registered for delivery only after the
transaction that creates their token commits. A rollback therefore dispatches no
email. SMTP failures are handled after commit, do not roll back account or token
records, and are audited without storing message content or raw tokens.

Verification resend and password-reset requests have independent, configurable
server-side cooldowns based on the latest action-token creation timestamp. The React
countdown is only a user-interface aid; direct API callers are subject to the same
backend enforcement. Unknown, ineligible, active, throttled, and delivery-failure
cases keep the same generic external response.

## Local email delivery

Start Mailpit from the repository root:

```powershell
docker compose up -d mailpit
```

The backend defaults to SMTP at `localhost:1025`, without authentication or TLS. Open
`http://localhost:8025` to inspect captured messages. The root `.env` configures the
published Compose ports only; Maven does not load it into the backend process.

Automated tests replace SMTP delivery with a deterministic in-memory capturing fake.
They do not connect to Mailpit or any external email provider.
