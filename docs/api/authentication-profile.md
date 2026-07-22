# Authentication and Profile Backend API

The backend supports local email/password registration, short-lived JWT access tokens, rotating refresh tokens, logout, current-user lookup, and profile retrieval/update. The React prototype is not connected to these endpoints yet.

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
| `AUTH_REFRESH_COOKIE_SECURE` | No | Enables the cookie `Secure` flag; defaults to `false` for local HTTP and must be `true` with production HTTPS |
| `AUTH_BCRYPT_STRENGTH` | No | BCrypt work factor from 4 through 31; defaults to 12 |
| `CORS_ALLOWED_ORIGIN` | No | Single allowed browser origin; defaults to `http://localhost:5173` |

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
| `POST /api/auth/logout` | Bearer JWT | Revokes the presented refresh session and clears its cookie |
| `GET /api/auth/me` | Bearer JWT | Returns the authenticated user and current account/identity statuses |
| `GET /api/profile` | Bearer JWT | Returns identity, profile, interface, financial, and notification preferences |
| `PUT /api/profile` | Bearer JWT | Replaces editable profile and interface/financial preference values |

The profile update contract deliberately excludes IDs, email, account status, identity status, and verification fields. Unknown input fields are rejected.

## Token and Account Rules

The access JWT contains the user UUID as its subject plus standard issuer, audience, issued-at, not-before, expiry, and token-ID claims. It does not contain email, password data, roles, or account status. Each authenticated request reloads the user and identity from PostgreSQL, so suspension, disabling, deletion pending, or deletion takes effect before an unexpired JWT can reach an endpoint.

New registrations remain truthfully in `PENDING_VERIFICATION` with no fabricated verification timestamp. Pending users may access only refresh, logout, current-user retrieval, and profile retrieval/update. All current and future business endpoints require both an `ACTIVE` user and an `ACTIVE` authentication identity. Email verification and activation are deferred to a later authentication slice.

Refresh values are opaque 256-bit random tokens stored only as SHA-256 hashes. The browser cookie is `HttpOnly`, `SameSite=Strict`, scoped to `/api/auth`, and optionally `Secure`. Rotation is transactional. Reuse of a consumed token revokes every session in its token family.

Passwords are never returned or logged. BCrypt input is limited to 72 UTF-8 bytes, rather than 72 Java characters.
