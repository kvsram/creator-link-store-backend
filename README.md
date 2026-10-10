# Creator Link Store API

Spring Boot API for the sectioned creator admin, public storefront, products, customers, orders, analytics, integrations, and automation metadata.

The code is a traditional layered Spring Boot modular monolith:

```text
controller -> service -> repository -> PostgreSQL
                     -> provider strategy/client -> Razorpay, Stripe, Instagram
```

`CreatorStoreApplication` is only the entry point. Domain-specific REST controllers contain routing, services contain validation/orchestration, repositories contain parameterized SQL, DTO records define request contracts, and integration adapters isolate provider HTTP calls. The infrastructure repository's `docs/backend-architecture.md` contains the full package/controller map.

```bash
docker compose up -d db
DB_USER=creator DB_PASSWORD=creator mvn spring-boot:run
```

It listens on port 8080 and its inferred PostgreSQL schema is initialized from `src/main/resources/schema.sql`. A clean database seeds the deterministic `alex` demo (its password is a random, intentionally-unusable value — register a real account, verify its email, and then log in to get a usable session).

India launch defaults are `INR`, Razorpay as the preferred strategy, and both payments and Instagram disabled. `GET /api/v1/payments/config` and `GET /api/v1/integrations/instagram/config` report safe readiness without secrets. Provider modes are `disabled`, `test`, and `live`; use test credentials locally and store deployed secrets in the selected cloud secret manager. Checkout amounts are always loaded server-side and expressed as integer paise in `*_subunits` fields.

## Authentication

There are two deliberately distinct contracts during the identity migration:

- The compatibility endpoints `POST /api/auth/register`, `POST /api/auth/login`,
  `POST /api/auth/logout`, and `GET /api/auth/me` remain available to older clients. Compatibility
  registration now enforces the same verification-first invariant as v1: it creates a pending
  account, returns HTTP `202`, and issues no session before email verification.
- The production-oriented v1 flow starts at `POST /api/v1/auth/registrations`. It creates a
  `pending_verification` account, returns HTTP `202`, and issues no session until
  `POST /api/v1/auth/email-verifications` consumes a valid one-time token. Resend is
  `POST /api/v1/auth/email-verifications/resend`. A correct password for a pending account returns
  HTTP `403` with `code: "EMAIL_NOT_VERIFIED"`; unknown accounts, wrong passwords, locked accounts,
  and suspended/deleted accounts all retain the same generic HTTP `401` response.

Both registration paths atomically create the creator, normalized identity rows, default store,
notification preferences, and the mandatory verification challenge. A failed insert leaves no partial
account. Passwords must contain 12–128 Unicode code points. New normalized credentials use
Argon2id; existing BCrypt credentials remain valid and are upgraded after a successful login.
Only password verifiers are stored—never a raw password. Twelve characters is an explicit
test-stage usability choice; before password-only production use, add MFA/passkeys and breached-
password screening or restore the current NIST 15-character single-factor minimum.

The cookie carries a random 30-day opaque token while the database stores only its SHA-256 hash.
Sessions also have a rolling 12-hour idle expiry and a database `session_version`. The v1 session
endpoints are:

- `POST /api/v1/auth/sessions` — log in with `handleOrEmail` and `password`.
- `GET /api/v1/auth/sessions` — list active devices with `publicId`, `createdAt`, `lastSeenAt`,
  `idleExpiresAt`, `expiresAt`, `authMethod`, `device`, `location`, and `current`.
- `DELETE /api/v1/auth/sessions/current` or
  `DELETE /api/v1/auth/sessions/{publicId}` — revoke one session.
- `POST /api/v1/auth/password-reset-requests` with `email` — always returns a generic accepted
  response; only an active account with a verified primary email receives a usable challenge.
- `POST /api/v1/auth/password-resets` with `token` and `newPassword` — consumes the challenge once,
  updates both transition-era password columns, bumps `session_version`, and revokes every legacy
  and v1 session.

Every `/api/v1/**` route requires a valid session except the
public/webhook/checkout paths a buyer or payment provider needs to hit anonymously — see
`identity/AuthInterceptor.java` for the exact allowlist. Authenticated creator APIs resolve
`creatorId` only from the session. Public checkout accepts the storefront creator ID but verifies
that the published product, selected plan, meeting slot, and checkout fields all belong to that
product before contacting a payment provider.

Registration requires `displayName`, `email`, and `password`; `phone` and `handle` are optional.
When `handle` is omitted, the server creates a readable unique store link from the display name.
Creators can rename that link later in Settings. Older clients may still provide an explicit
handle; the API normalizes it and rejects application routes such as `login`, `signup`, `dashboard`,
and `api`. Registration creates the free store plus notification defaults in one transaction. It
does **not** accept or persist card numbers, CVVs, bank details, or a client-asserted
premium entitlement. Premium signup must be implemented as a separate, authenticated
provider-hosted/tokenized checkout: the provider returns an opaque payment-method/customer ID,
and only a verified payment webhook may activate the paid subscription. Until that creator-billing
model exists, the registration response honestly reports `plan: "free"`.

### Verification delivery and authentication configuration

There is no real email provider in this test release. Verification and reset tokens are never
returned by normal production endpoints. For automated development testing only, an in-memory test
mailbox can be enabled with both `APP_AUTH_TEST_DELIVERY_ENABLED=true` and a strong
`APP_AUTH_TEST_DELIVERY_KEY`. The response then includes a one-time `testDeliveryId`; an authorized
test operator may retrieve it from `GET /api/v1/auth/test-deliveries/{deliveryId}` using the
`X-Test-Auth-Key` header. This feature is disabled by default, stores nothing durably, must remain
disabled in production, and the key must not be committed or exposed to browser users.

Relevant environment settings are:

| Setting | Default | Purpose |
| --- | --- | --- |
| `APP_AUTH_TEST_DELIVERY_ENABLED` | `false` | Enables the development-only mailbox. |
| `APP_AUTH_TEST_DELIVERY_KEY` | empty | Operator key required to read a test delivery. |
| `APP_AUTH_FINGERPRINT_SECRET` | empty | HMAC secret for non-plaintext security fingerprints; set outside local development. |
| `APP_AUTH_LOGIN_IP_ATTEMPTS` / `APP_AUTH_LOGIN_IP_WINDOW` | `30` / `PT5M` | Per-instance login guard. |
| `APP_AUTH_REGISTRATION_IP_ATTEMPTS` / `APP_AUTH_REGISTRATION_IP_WINDOW` | `10` / `PT1H` | Protects password hashing and account creation. |
| `APP_AUTH_CHALLENGE_IP_ATTEMPTS` | `20` | Per-IP verification and password-challenge guard. |
| `APP_AUTH_CHALLENGE_IDENTIFIER_ATTEMPTS` | `5` | Per-normalized-email challenge guard without disclosing account existence. |
| `APP_AUTH_CHALLENGE_WINDOW` | `PT15M` | Window used by both challenge guards. |

These bounded in-memory guards are suitable only for the current single-instance test deployment;
move the counters to Redis or an edge rate-limiter before scaling to multiple application replicas.

The schema migration is additive: legacy `creators` and `sessions` remain the compatibility
surface while normalized accounts, profiles, handles, credentials, challenges, audit events, and
sessions are backfilled. Existing active users are grandfathered with a verified primary email.
Pending accounts keep an unusable random BCrypt value in the legacy creator row until verification,
preventing an old-image rollback from bypassing verification. Logout/reset revoke legacy and v1
sessions, and the new resolver honors a legacy revocation. Deploy this version with a Recreate
strategy rather than mixed old/new application pods; rolling mixed-version issuance is not
supported, and rolling back to the old image intentionally logs out sessions created only by v1.

`POST /api/v1/authentication/check-unique-taken` checks only the public store handle. It returns
`handle_taken`, the compatibility alias `username_taken`, and `available`; it deliberately ignores
email and never discloses whether an email address is registered. Duplicate registration still
returns one generic handle-or-email conflict, and the public edge should rate-limit both endpoints.

## Product types

The schema and layered product service cover all 8 types (`digital-download`, `lead-magnet`, `fulfillment`, `meeting`, `webinar`, `community`, `membership`, `course`). Paid checkout requires `buyerEmail`, accepts optional `buyerName`, `fieldResponses`, `slotId`, and `planId`, persists those selections, and uses a validated payment-plan amount. Creator-side type-specific authoring is implemented; most separate buyer delivery/access workflows remain partial, as documented below and in the infrastructure repository's feature matrix. Compiling the service is not endpoint proof.

Creator products support edit, draft/publish, protected deletion, and persistent pin/unpin. Promotions support the same persistent pin state. The phone preview is the creator-facing pinning surface, and the public storefront combines products and promotions into one pinned-first feed. Products referenced by customer or order history return HTTP `409` on deletion and should be unpublished instead, preserving commerce records.

Type-specific authoring is persisted through `/api/v1/products/{id}/configuration`. The service allowlists thumbnail styles and validates download redirects, coaching duration/capacity/timezone, webinar time/capacity, course module structure, membership intervals, fulfillment turnaround, and community benefits. Authenticated multipart upload stores opaque object keys beneath `APP_STORAGE_DIR` and records file name, kind, content type, and byte size without exposing the server path. Production deployments must replace local disk with the configured cloud object-storage adapter plus malware scanning and signed delivery.

Published lead magnets accept public submissions at
`POST /api/public/products/{productId}/leads`. The endpoint requires an `Idempotency-Key`, derives
the creator from the published product, rejects a mismatched compatibility `creatorId`, and applies
the product's configured name, phone, and consent requirements. It stores normalized contact data,
the exact consent-text snapshot, and a request fingerprint. Identical key replays return the same
generic `{ "accepted": true }` result; reusing a key for different input returns `409`. No product
file path or redirect URL is returned. Capturing a lead does not yet send email—the eventual email
worker must deliver a short-lived signed link rather than expose the private fulfillment URL.

`PATCH /api/v1/store` is the authenticated storefront-authoring boundary. It persists the public headline, tagline, visibility toggles, theme, accent color, background, button style, and font style after server-side allowlist validation. `GET /api/public/{handle}` returns the saved design so the admin phone preview and public storefront use the same headline and model.

## Promotions and external links

`url-media` appears as the ninth visual store-item type but is deliberately persisted separately from checkout products. `/api/v1/promotions` provides owner-scoped create/edit/delete/reorder/publish and schedule behavior with brand, image, CTA, coupon, offer, and disclosure metadata. Only absolute HTTPS destinations and thumbnails are accepted. Public reads filter draft, future, and expired records. `/api/events/click` validates that the link belongs to the supplied creator and is currently public before storing bounded attribution metadata, preventing arbitrary link-ID inflation.

`POST /api/events/view` accepts `{handle, path, referrer}`. It resolves the published storefront by
normalized handle, verifies that the path belongs to that handle, stores a canonical server-derived
path, and bounds referrer metadata before recording the visit. The legacy `POST /events` page-view
shape remains available, but now resolves a published storefront and cross-checks any supplied
creator ID instead of trusting an arbitrary tenant ID.

## Instagram Auto DM

Signed comment webhooks can enqueue idempotent keyword rules through `/api/v1/automations/instagram-comment-rules`. Delivery uses a leased PostgreSQL queue, a seven-day private-reply expiry guard, bounded retries for retryable provider failures, and a permanent dead state for non-retryable failures. Instagram remains disabled until Meta App Review, per-creator OAuth token encryption, and the production gates in `docs/INSTAGRAM_AUTODM.md` are complete.

The current test tier does not yet have a trustworthy creator-subscription ledger, verified
creator-billing webhook, or server-side Premium entitlement check for Auto DM. Keep Instagram in
`disabled` or tightly controlled `test` mode; do not advertise or enable the live mutation path as
a paid entitlement until those backend controls exist. This is an explicit pre-production gate,
not an inferred plan from a browser request.

## Deployment

For the complete containerized dev workflow (three containers, smoke test), start with the [infrastructure repository](https://github.com/kvsram/creator-link-store-infrastructure). For an actual production deployment, see [`infrastructure/production/README.md`](https://github.com/kvsram/creator-link-store-infrastructure/tree/main/production) — a simple single-VM setup (Caddy for TLS + a managed Postgres), not the full multi-region AWS/EKS path.

## Regional Kubernetes deployment

`deploy/overlays/region-a`, `region-b`, and `region-c` target three independent Kubernetes clusters. Each deploys one API replica initially, with an HPA that can grow to three replicas in that region. The application remains stateless; PostgreSQL is an external managed service, not a Pod in every cluster.

Create the `creator-store-db` Secret in each cluster using its private managed-PostgreSQL endpoint. It is deliberately not generated from the placeholder template by Kustomize. For the first low-scale phase, use one active writer region and keep another region warm; the current API has no read/write datasource split and arbitrary cross-region writes are unsafe. Do not commit a real database password: create the Secret through External Secrets or an equivalent cloud secret-manager integration.

The integration ConfigMap deliberately keeps `PAYMENTS_MODE` and `INSTAGRAM_MODE` disabled. Create `creator-store-runtime-secrets` through External Secrets before switching a stage to `test`. `deploy/base/integration-secret.template.yaml` documents names only and is intentionally excluded from Kustomize.

GitHub Actions builds an immutable GHCR image on every `main` push. The infrastructure repository adds a cloud-neutral manual promotion workflow for EKS, AKS, and OKE. It uses a private runner attached to exactly one cloud/stage and deploys exact backend, frontend, and infrastructure SHAs. No kubeconfig or database value is stored in GitHub.
