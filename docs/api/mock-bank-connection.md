# Mock-bank connection API

This slice connects an authenticated, active application user to one synthetic
mock-bank customer. It is deliberately simpler than a production Open Banking
integration: there is no OAuth, PKCE, consent portal, provider refresh token,
payment API, or transfer API.

## Request flow and trust boundary

1. The frontend authenticates only with the core backend and sends its normal
   application bearer JWT.
2. `POST /api/bank-connections/mock-bank` accepts the demo bank username and
   password. The backend forwards them once to the internal mock-bank login
   adapter and never persists the password.
3. The mock bank validates its BCrypt hash and returns a short-lived signed JWT
   whose subject is the synthetic customer identifier.
4. The backend links that provider customer to the current application user.
   The provider identifier and access JWT stay server-side.
5. Live provider reads and explicit synchronizations derive the application
   user from the application JWT and the provider customer from the current
   connection. The provider bearer header is added only by the infrastructure
   adapter.
6. A synchronization retrieves every provider page before opening its atomic
   persistence transaction. It then upserts accounts and transactions in the
   application database, keyed by provider identity and stable upstream IDs.

The backend never accepts a public `userId` or mock-bank `customerId`. The mock
bank independently verifies the JWT signature, expiry, issuer, audience, path
customer, and account ownership on every data request.

## Development credentials

The deterministic V2/V3 fixture provides two development-only identities:

| Username | Password |
| --- | --- |
| `amina.demo` | `DemoBank2026!` |
| `youssef.demo` | `DemoBank2026!` |

The database stores only BCrypt password hashes. These credentials must never
be reused outside this synthetic local provider.

## Core backend endpoints

All routes below require a valid application JWT and an `ACTIVE` application
user plus `ACTIVE` authentication identity.

| Method and path | Responsibility |
| --- | --- |
| `POST /api/bank-connections/mock-bank` | Authenticate demo credentials and create or replace the current user's connection |
| `GET /api/bank-connections/mock-bank` | Return non-sensitive connection status |
| `DELETE /api/bank-connections/mock-bank` | Disconnect, clear the provider identity/token, and disable discovered accounts |
| `GET /api/bank/accounts` | Refresh and return the current user's connected account metadata |
| `GET /api/bank/accounts/{accountId}` | Return one locally scoped account after upstream ownership validation |
| `GET /api/bank/accounts/{accountId}/balance` | Return the account's current and available balances |
| `GET /api/bank/accounts/{accountId}/transactions` | Return a filtered, paginated transaction page |
| `POST /api/bank/synchronizations` | Run one user-scoped accounts, balances, and transactions synchronization |
| `GET /api/bank/synchronizations/status` | Return the latest attempt, last success, safe failure code, and item counters |
| `GET /api/accounts` | Return the current user's persisted normalized accounts, including balances and synchronization timestamps |
| `GET /api/transactions` | Return the current user's persisted normalized transactions with optional account, date, and direction filters |
| `GET /api/dashboard/summary?month=YYYY-MM` | Return decimal-safe account totals and monthly income, expenses, and net cash flow grouped by currency |

The connection request is:

```json
{
  "username": "amina.demo",
  "password": "<demo password>"
}
```

Connection responses contain only `id`, `providerCode`, `status`,
`reconnectRequired`, `connectedAt`, `tokenExpiresAt`, and `disconnectedAt`.
They never serialize credentials, provider tokens, provider customer IDs, or
application user IDs.

Account metadata contains an application-owned UUID, display name, type,
currency, masked reference, status, and creation time. Balance responses add
only the requested application's account UUID, currency, current and available
balances, status, and masked reference. Transaction items retain the provider's
stable transaction ID, dates, `BigDecimal` amount, direction, currency,
description, counterparty name, and status; no persistence entity is exposed.

Transaction `from` and `to` filters are optional inclusive ISO-8601 dates.
`page` defaults to `0`; `size` defaults to `20` and must be between `1` and
`100`. A reversed date range is rejected.

`GET /api/transactions` also accepts a bounded, case-insensitive `search`
filter over the safe description and counterparty fields. The dashboard
summary excludes transactions already paired in `internal_transfer`; it never
converts or combines different currencies.

The live `/api/bank/**` reads require a usable provider authorization. The
local `/api/accounts` and `/api/transactions` reads use only the application
database, so synchronized history remains readable after disconnection or a
provider outage. Local responses expose application-owned UUIDs and normalized
financial fields, but never upstream account/transaction IDs, provider tokens,
authorization IDs, sync-job IDs, or persistence entities.

Only one synchronization may run for a connection. External HTTP calls are
made outside the database transaction; the complete validated batch is then
persisted atomically. A repeated synchronization updates existing provider
records instead of duplicating them. Failure records contain a bounded safe
code, preserve the last successful timestamp and previously synchronized data,
and never invalidate the user's application session.

## Lifecycle and safe errors

Only one current mock-bank connection is allowed per application user, and one
synthetic provider customer cannot be current for two application users.
Disconnect clears the stored provider token and external customer link. A
provider authentication rejection also clears the usable token and requires a
new connection. Network timeouts and provider `5xx` responses return a safe
availability error without invalidating an otherwise valid connection.

| Condition | Status | Safe message |
| --- | ---: | --- |
| Invalid demo bank credentials | 422 | `Invalid mock-bank credentials` |
| Missing connection | 409 | `A mock-bank connection is required` |
| Expired or rejected provider token | 409 | `Mock-bank reconnection is required` |
| Missing or cross-user account | 404 | `Bank account not found` |
| Provider customer already linked | 409 | `This mock-bank customer is already linked to another user` |
| Provider unavailable | 503 | `Mock-bank service is unavailable` |
| Invalid provider success payload | 502 | `Mock-bank returned an invalid response` |
| Synchronization already running | 409 | `BANK_SYNC_IN_PROGRESS` |
| Provider authorization rejected during synchronization | 409 | `BANK_AUTHORIZATION_EXPIRED` |
| Other synchronization failure | 502 | `BANK_SYNC_FAILED` |

Raw upstream bodies, passwords, bearer values, authorization headers, hashes,
and internal exception causes are not included in application errors or logs.

## Mock-bank authentication

`POST /api/auth/login` is public on the mock bank. It returns `accessToken`,
`tokenType`, `expiresAt`, and `customerId` to the trusted backend adapter. The
health/info endpoints are also public; every `/api/v1/customers/**` route
requires that token. A customer-subject mismatch is deliberately returned as a
safe `404`, and an account ownership mismatch is indistinguishable from a
missing account.

The signing secret is mandatory and Base64-encoded. Local Compose variables are
documented in `.env.example`: `MOCK_BANK_JWT_SECRET`,
`MOCK_BANK_JWT_ISSUER`, `MOCK_BANK_JWT_AUDIENCE`, and
`MOCK_BANK_ACCESS_TOKEN_TTL`. Production-style secret storage, provider refresh
tokens, and token rotation are consciously deferred for this simulated service.
