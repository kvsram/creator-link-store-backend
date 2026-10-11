# HANDOFF — read this first

This file is written for whoever (human or LLM) continues this codebase. It states what exists,
what the rules are, and what to build next. Keep it updated when you add a module.

## 1. What this is

A Stan-Store-style creator commerce platform: an influencer signs up, connects Instagram, lists
products, and followers who comment a keyword get an auto-DM with a link to a personalised checkout page.

Built as a **modular monolith**: one Spring Boot application, one PostgreSQL database, one deployable,
with strict package boundaries so modules could be split out later if ever needed.

Stack: Java 21, Spring Boot 3.5, Spring Data JPA (Hibernate), Flyway, PostgreSQL 16, Spring Security
(resource server, HS256 JWT). No Lombok, no MapStruct, no third-party JWT library.

## 2. Status

| Module         | Package                        | Status        | Owns tables |
|----------------|--------------------------------|---------------|-------------|
| common         | `com.creatorstore.common`      | done          | — |
| config         | `com.creatorstore.config`      | done          | — |
| security       | `com.creatorstore.security`    | done          | — |
| creator        | `com.creatorstore.creator`     | done          | `creators` |
| auth           | `com.creatorstore.auth`        | done          | `refresh_tokens` |
| verification   | `com.creatorstore.verification`| done          | `phone_verification_tokens` |
| notification   | `com.creatorstore.notification`| done (log only) | — |
| admin          | `com.creatorstore.admin`       | login only    | `admin_users` |
| product        | `com.creatorstore.product`     | **not started** (package-info.java describes it) | `products`, `prices` |
| booking        | `com.creatorstore.booking`     | not started   | `booking_sessions`, `booking_slots` |
| storefront     | `com.creatorstore.storefront`  | not started   | `store_pages` |
| automation     | `com.creatorstore.automation`  | not started   | `social_accounts`, `automation_rules`, `dm_logs`, `tracking_links` |
| customer       | `com.creatorstore.customer`    | not started   | `customers` |
| order          | `com.creatorstore.order`       | not started   | `orders`, `fulfillments` |
| payment        | `com.creatorstore.payment`     | not started   | `payments`, `refunds`, `disputes`, `payout_batches`, `creator_payouts`, `creator_subscriptions` |
| support        | `com.creatorstore.support`     | not started   | `support_tickets`, `support_messages`, `system_errors`, `audit_logs` |

The planned tables for the unstarted modules are sketched in `docs/planned-schema.sql`.
They are a starting point, not a contract: adjust them when you implement the module.

### Verification state of the delivered code

- `src/main/resources/db/migration/V1__auth_schema.sql` was applied to a real PostgreSQL 16 and
  every constraint and index was exercised (duplicates, case, E.164, partial unique index, cascade,
  lockout SQL, EXPLAIN on hot queries). It is correct as written.
- `AuthFlowIntegrationTest` boots the application against PostgreSQL 16 (Testcontainers) and walks
  every built flow over HTTP; `ArchitectureTest` (ArchUnit) enforces the module rules below.
  Both run in `mvn verify`, locally and in the pull-request gate.
- The Java was written against Spring Boot 3.5 / Spring Security 6.5 / Hibernate 6.6 APIs. The first
  CI run on the pull request is its first compile; expect small fixes, not design changes. If Hibernate's
  schema validation (`ddl-auto: validate`) complains about `timestamptz` columns, set
  `spring.jpa.hibernate.ddl-auto: none` — Flyway owns the schema either way.

### History

This repository held a two-file prototype (JdbcTemplate, no authentication). Its schema sketch and README
are kept under `docs/legacy/` as reference input for the product, payment and automation modules; nothing
else from it is used.

## 3. How to run

```bash
# 1. PostgreSQL (any 14+); defaults match application.yml
createuser creatorstore -P        # password: creatorstore
createdb -O creatorstore creatorstore

# 2. Required environment
export JWT_SECRET='change-me-to-a-random-string-of-at-least-32-characters'
# optional: creates the first SUPER_ADMIN on first start, then remove them
export ADMIN_BOOTSTRAP_EMAIL=admin@example.com
export ADMIN_BOOTSTRAP_PASSWORD='at-least-12-characters'

# 3. Build and run
mvn -q verify
mvn spring-boot:run
```

Flyway creates the schema on first start. SMS messages are printed to the log
(`app.sms.provider=log`); the verification link is in that log line.

## 4. The rules every module follows

These are not suggestions. They are what makes the codebase stay understandable as it grows.

### Layout: one package per module, same files in each

```
com.creatorstore.<module>/
    <Entity>.java               JPA entity, maps 1:1 to a table; no setters, intention-revealing methods
    <Entity>Repository.java     Spring Data interface; used ONLY by this module's service
    <Module>Service.java        the module's API; all business rules and transactions live here
    <Module>Controller.java     thin: validate input, call service, return DTO
    <Module>Dtos.java           request/response records (or one record per file if many)
    package-info.java           what the module owns and depends on
```

### Dependency rules

- A module may call another module's **Service**. Never its Repository, never its Entity's mutators.
- `common`, `config`, `security` are shared by everyone and depend on no module.
- No cycles between modules. If you need one, you have the boundary wrong (usually the shared
  concept belongs in a third module, or an event is needed).
- Controllers never touch repositories.

### Database rules

- Flyway owns the schema. Hibernate only validates. Never edit a migration that has run outside
  your laptop; add `V<n+1>__<name>.sql`.
- Enum-like columns store the Java enum **name** (`'ACTIVE'`), with a `CHECK` constraint listing the names.
  Entity field: `@Enumerated(EnumType.STRING)`.
- All timestamps `TIMESTAMPTZ` ↔ `java.time.Instant`.
- Money: `BIGINT` minor units (paise/cents) + `CHAR(3)` ISO currency. Never floating point.
- Secrets (passwords, tokens) are stored only as hashes. Raw tokens are shown to the user once.
- Uniqueness constraints only for real identifiers (email, username, provider ids). Names, phones,
  and similar are not identities and are never unique.
- Every table: `id BIGSERIAL PRIMARY KEY`, `created_at`, and `updated_at` where rows change.
- Every foreign key gets an index if you will ever query by it.
- Normalize input in Java (`Normalizer`) and add a `CHECK` that the stored value is normalized,
  so a code bug cannot store a bad value.

### Error handling

- Throw `ApiException` with a stable `code` (SCREAMING_SNAKE) and a human message. Nothing else.
- `GlobalExceptionHandler` turns everything into `{"code": "...", "message": "..."}`.
- Never leak whether an account exists: login, refresh, and verification use generic messages.

### Transactions

- `@Transactional` on service methods, never on controllers.
- Read-only queries: `@Transactional(readOnly = true)`.
- If a method must **record something and then throw** (failed-login counter, token-reuse revocation),
  either run the write in its own transaction (see `CreatorService.recordFailedLogin`) or use
  `@Transactional(noRollbackFor = ApiException.class)` (see `RefreshTokenService.rotate`).
- Concurrency: use a single atomic SQL statement (`CreatorRepository.recordFailedLogin`) or a row lock
  (`@Lock(PESSIMISTIC_WRITE)` on `findByTokenHash`) instead of read-modify-write in Java.

### Security

- Public endpoints are listed once in `SecurityConfig.PUBLIC_*_PATHS`. Add there and nowhere else.
- `/api/admin/**` requires `ROLE_ADMIN`; `/api/**` requires `ROLE_CREATOR`; rest is denied.
- Finer admin permissions: `@PreAuthorize("hasRole('FINANCE')")` (from the `admin_role` claim).
- Get the caller with `AuthenticatedUser.from(jwt)` where `jwt` is `@AuthenticationPrincipal Jwt`.
- Owner checks are the service's job: a creator may only read/modify rows where `creator_id = caller id`.

## 5. Authentication design (implemented)

### Creator signup
`POST /api/auth/signup` → `CreatorService.register` normalizes (trim, lowercase email/username,
E.164 phone), checks reserved usernames, checks `existsBy...` for friendly 409s, inserts; the DB
unique constraints are the final referee under races, and their violation is mapped to the same 409.
If a phone was given, `PhoneVerificationService.issueLink` runs in the same transaction.

### Phone verification
One row per link in `phone_verification_tokens`; only the SHA-256 of the token is stored; at most one
open link per creator (partial unique index); 30-minute TTL; max 3 links per phone per hour.
`POST /api/verification/phone/confirm {token}` is public (the token is the proof). Confirm locks the
row, rejects used/expired/phone-changed, marks used and sets `phone_verified` in one transaction.

### Login
`POST /api/auth/login {identifier, password}`; identifier with `@` is an email, else a username.
Order: lookup → (unknown: run a dummy BCrypt so timing matches) → status ACTIVE? → locked? →
password → on failure `recordFailedLogin` (atomic SQL; 5th failure locks 15 min and resets the
counter) → on success reset counters, create a refresh-token family, issue a 15-minute access JWT.

### Tokens
- Access token: HS256 JWT, claims `sub` (creator/admin id), `role` (`CREATOR`|`ADMIN`),
  `username` (creators) or `admin_role` (admins), `iss`, `iat`, `exp`. Verified by Spring's
  resource-server filter. Not stored anywhere.
- Refresh token: opaque 256-bit random, 30 days, SHA-256 stored in `refresh_tokens`.
  `POST /api/auth/refresh` **rotates**: old row revoked and linked to the new row (`replaced_by_id`),
  same `family_id`. Presenting an already-revoked token = reuse = whole family revoked (committed,
  then 401). `POST /api/auth/logout` revokes one; `POST /api/auth/logout-all` revokes all.
- The bearer-token resolver ignores the `Authorization` header on public endpoints, so a stale
  access token attached to `/refresh` or `/login` does not cause a 401 before the controller runs.
- Admins: 8-hour access token, no refresh token (few staff, known devices). Revisit if that changes.

### Endpoints

| Method | Path | Auth | Purpose |
|---|---|---|---|
| GET  | `/api/auth/username-availability?username=` | public | live check while typing |
| POST | `/api/auth/signup` | public | create account (+ send SMS link if phone given) |
| POST | `/api/auth/login` | public | access + refresh token |
| POST | `/api/auth/refresh` | public | rotate refresh token, new access token |
| POST | `/api/auth/logout` | public | revoke one refresh token |
| POST | `/api/auth/logout-all` | CREATOR | revoke every session |
| POST | `/api/verification/phone/confirm` | public | consume the SMS link token |
| POST | `/api/verification/phone/resend` | CREATOR | new link to current number |
| PUT  | `/api/verification/phone` | CREATOR | set/change number, resets verification, sends link |
| GET  | `/api/creators/me` | CREATOR | own profile |
| POST | `/api/admin/auth/login` | public | admin access token |
| GET  | `/api/admin/me` | ADMIN | own admin profile |

## 6. Known gaps (deliberate, in priority order)

1. **Rate limiting by IP** on signup/login/refresh/confirm. Put it at the gateway/WAF, or add
   Bucket4j with a filter. Per-phone SMS limiting exists; per-IP does not.
2. **SMS outbox.** The SMS is sent inside the signup transaction. A slow provider slows signup, a
   failed send rolls signup back. For volume: write to an `outbox` table, send from a scheduled job.
3. **Real SMS provider.** Implement `SmsSender`, annotate `@ConditionalOnProperty(name="app.sms.provider", havingValue="twilio")`.
4. **Email verification** (same pattern as phone tokens) and **password reset** (same pattern, plus
   `logout-all` on success).
5. **Unverified-account cleanup job** and the policy for it.
6. **Admin management endpoints** (create admin, deactivate, list creators, suspend) — the schema
   columns exist (`account_status`, `is_active`); the audit log table does not yet (support module).
7. **Gating:** storefront publish and payouts must require `phone_verified = true`. Enforce in those
   modules' services, not in auth.
8. Behind a reverse proxy set `server.forward-headers-strategy: native` so `ClientInfo` gets real IPs.

## 7. What to build next, in order

1. `product` (V2) — needed by everything else.
2. `storefront` (V4) — first public page; gate on ACTIVE + published + phone verified.
3. `customer` + `order` + `payment` (V7, V8, V9) — Stripe/Razorpay checkout, webhook, fulfilment.
4. `automation` (V5, V6) — Meta OAuth, webhook, keyword rules, DM queue, tracking links.
5. `booking` (V3), `support` (V12), payouts and creator subscriptions (V10, V11).

For each: write the migration first, run it, then entity → repository → service → controller → tests,
and update the table in section 2 of this file.
