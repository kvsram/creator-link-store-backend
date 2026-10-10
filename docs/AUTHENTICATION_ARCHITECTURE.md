# Authentication vertical slice

This document describes the complete password-authentication boundary. It is intentionally a
single Spring Boot module today. Splitting it into a network microservice would add a second
database consistency boundary without improving the current one-region, low-scale deployment.

## Request path

```text
React page
  -> CSRF bootstrap (`GET /api/v1/auth/csrf`)
  -> `IdentityAuthController`
  -> `IdentityAuthenticationService`
  -> transactional registration/login/challenge service
  -> `IdentityRepository` / `SessionService` / `AuthDeliveryRepository`
  -> PostgreSQL

Every protected request
  -> `SecurityFilterChain`
  -> `SessionAuthenticationFilter`
  -> SHA-256(cookie value) lookup in `auth_sessions`
  -> Spring Security `CreatorPrincipal` with `ROLE_CREATOR`
  -> owner-scoped controller/service/repository
```

Spring Security is the HTTP authorization boundary. The session cookie contains a random opaque
secret; it is not a JWT and it contains no account data. PostgreSQL stores only its SHA-256 hash.
Business controllers receive the account ID established by the server-side session and never trust
a creator ID supplied by the browser.

## Public and authenticated API

| Method and path | Authentication | Purpose |
| --- | --- | --- |
| `GET /api/v1/auth/csrf` | Public | Issues the cookie/header CSRF pair used by browser mutations. |
| `POST /api/v1/auth/registrations` | Public + CSRF | Creates the pending account, profile, generated store handle, store defaults, verification challenge, and delivery outbox row atomically. |
| `POST /api/v1/auth/email-verifications` | Public + CSRF | Consumes the one-use verification token and creates the first session. |
| `POST /api/v1/auth/email-verifications/resend` | Public + CSRF | Replaces an eligible pending-account challenge without disclosing unknown accounts. |
| `POST /api/v1/auth/sessions` | Public + CSRF | Authenticates by normalized email or handle and creates a session. |
| `GET /api/v1/auth/me` | `ROLE_CREATOR` | Returns the authenticated account only. |
| `GET /api/v1/auth/sessions` | `ROLE_CREATOR` | Lists the account's active devices. |
| `DELETE /api/v1/auth/sessions/current` | `ROLE_CREATOR` + CSRF | Revokes the current session and expires the cookie. |
| `DELETE /api/v1/auth/sessions/{publicId}` | `ROLE_CREATOR` + CSRF | Revokes another session owned by the same account. |
| `POST /api/v1/auth/password-reset-requests` | Public + CSRF | Returns a generic accepted response and queues mail only for an eligible account. |
| `POST /api/v1/auth/password-resets` | Public + CSRF | Consumes the token once, changes the Argon2id verifier, increments `session_version`, and revokes all sessions. |

The compatibility `/api/auth/*` surface is temporary. New clients must use only `/api/v1/auth/*`.
It must be removed after the deployed frontend and any external clients have completed the cutover.

## Core relational model

The tables are normalized by responsibility instead of putting credentials, profile fields, and
session state into one `users` row:

```text
accounts (lifecycle, public ID, session version)
  |-- 1:1 creator_profiles (display data)
  |-- 1:N creator_handles (active/redirect/retired public names)
  |-- 1:N account_emails (normalized uniqueness, primary, verification state)
  |-- 1:N account_phones (primary and verification state)
  |-- 1:1 password_credentials (Argon2id verifier only)
  |-- 1:1 account_login_state (failure window and temporary lock)
  |-- 1:N auth_sessions (hashed opaque tokens, idle/absolute expiry, revocation)
  |-- 1:N password_reset_tokens (hashed, expiring, one-use)
  |-- 1:N auth_security_events (append-only audit metadata; identifiers are HMACed)
  `-- 1:N auth_delivery_outbox (transactional verification/reset delivery)

account_emails
  `-- 1:N email_verification_tokens (hashed, expiring, one-use)
```

The authentication tables remain in the same PostgreSQL database as the creator/store tables so
registration can commit the mandatory creator, default store, credential, challenge, and email
outbox record together. A separate physical login database is a later scaling/security option, not
a prerequisite for a correctly isolated module.

## Registration transaction

1. Bean Validation rejects missing or oversized fields before business logic.
2. The service normalizes email/phone and either validates the optional handle or derives a unique
   readable handle from the display name.
3. A bounded rate guard rejects abusive registration attempts before expensive password hashing.
4. `PasswordService` creates an Argon2id verifier. The raw password is never logged or persisted.
5. One database transaction writes the creator compatibility row, account, profile, handle, email,
   optional phone, password credential, login state, default store, default preferences, hashed
   verification challenge, and durable email outbox row.
6. The API returns `202 Accepted`, no session, and no verification token.
7. The outbox worker claims work with a unique owner token, sends through provider-neutral SMTP,
   retries with exponential backoff, and clears the raw delivery token after success, expiry,
   terminal failure, replacement by a newer challenge, or challenge consumption. A stale worker
   cannot update a row after another worker acquires its expired lease.

Local development uses Mailpit. AWS can use the identical SMTP adapter with SES SMTP credentials;
the application layer does not depend on SES classes. Provider credentials belong in a secret
manager or an untracked local environment file, never in Git.

## Login and session rules

- Unknown identifiers and wrong passwords receive the same public `401` response. Unknown-account
  attempts still perform comparable password work.
- Pending accounts cannot sign in until the email challenge is consumed.
- Suspended, deleted, locked, revoked, idle-expired, absolute-expired, or wrong-session-version
  sessions are rejected.
- Login creates a cryptographically random 256-bit token. The browser receives the cookie and the
  database receives only the token hash.
- Sessions expire after 12 hours idle and never live longer than 30 days.
- Password reset increments the account session version and revokes all existing sessions.
- A successful legacy BCrypt login is upgraded to Argon2id. New accounts never dual-write their
  real password verifier into the deprecated compatibility column.

## Browser security boundary

- Servlet sessions, form login, and HTTP Basic are disabled.
- Authentication is stateless at the Spring layer and backed by the explicit `auth_sessions` table.
- Every `/api/v1/**` and `/api/auth/**` route is authenticated unless it appears in the reviewed
  public allowlist.
- Cookie-authenticated mutations require the `X-XSRF-TOKEN` header matching the CSRF cookie.
- CORS allows credentials only for configured exact origins.
- Auth responses use `Cache-Control: no-store` and `X-Content-Type-Options: nosniff`.
- Production startup fails if cookies are not Secure, an allowed origin is not HTTPS, the public
  authentication-link origin is not allowed, authenticated STARTTLS SMTP credentials are missing,
  SMTP timeouts are not positive and shorter than the outbox lease, the test mailbox is enabled, or
  the stable fingerprint secret is missing. SMTP provider reachability is a runtime health concern:
  queued delivery is retried and must be monitored separately; startup does not prove that an
  external provider is reachable.
- Forwarded client addresses are interpreted by Spring's trusted-proxy handling; application code
  does not parse arbitrary user-supplied `X-Forwarded-For` values.

## Database change policy

Flyway owns the schema. `V1__baseline.sql` creates a fresh environment. For an existing database,
Flyway baselines the already-deployed schema at version 1 and then applies the additive
`V2__authentication_delivery_outbox.sql` and
`V3__authentication_delivery_lease_ownership.sql` migrations. V3 gives every worker claim a unique
owner token and returns leases held by the old protocol to pending. Application startup SQL is
disabled.

Before a production release, validate both paths against PostgreSQL: a blank database and a copy of
the deployed database. Take a database snapshot before migration. Application rollback is allowed
only while the additive schema remains backward compatible; schema rollback is a restore/forward-
fix operation, not an automatic `DROP`.

## Environments

| Environment | Email | Cookies/origin | Intended use |
| --- | --- | --- | --- |
| Automated tests | Durable outbox + gated test retrieval | CSRF may be disabled only in narrow legacy tests | Deterministic API tests. |
| Local Docker | Mailpit SMTP | HTTP localhost, non-Secure cookie | Full signup, verification, login, logout, and reset testing without external email. |
| Shared test | SES sandbox/SMTP and a verified recipient | HTTPS, Secure cookie, exact origin | Remote browser/device acceptance. |
| Production | SES production/SMTP (or equivalent) | HTTPS redirect, Secure cookie, exact origin | Real customer traffic after every release gate passes. |

## Definition of done

Authentication is not considered deployed merely because Java compiles. A release needs all of the
following evidence:

1. Maven unit/integration tests pass.
2. Flyway succeeds on blank and upgrade-path PostgreSQL databases.
3. Frontend tests/build pass and use only `/api/v1/auth/*`.
4. Local Docker proves signup -> delivered mail -> verification -> authenticated `/me` -> logout,
   plus forgot-password -> delivered mail -> reset -> old password rejected -> new password works.
5. A shared HTTPS environment repeats those journeys through the public ingress and real email
   provider.
6. Negative checks cover missing/invalid CSRF, unknown/wrong login equivalence, expired/replayed
   challenge, cross-account session revocation, revoked cookie, and unauthorized protected APIs.
7. Metrics/logs show mail retry/dead-letter behavior and auth failure rates without passwords,
   tokens, session cookies, or raw email addresses.

Distributed rate limiting, breached-password screening, MFA/passkeys, recovery codes, email-change
confirmation, application-level encryption of pending outbox tokens, and PostgreSQL
concurrency/Testcontainers coverage are the next hardening increments. They are not represented as
already deployed features.
