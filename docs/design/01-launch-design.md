# Creator Store — High-Level Design

Oct 10, 2026 · @Sriram

> Source of truth. This file is the design any session (Claude Code on the Mac, a claude.ai chat, a human) reads and edits by pull request. A readable copy with the diagrams lives in the Claude Doc (https://claude.ai/code/artifact/ef02df90-0f59-4d16-bbf8-fa7f3bef448c); when this file changes, the Doc is updated to match, not the other way round. Diagrams are shown here as `[diagram: ...]` placeholders.

## 1. Purpose and scope

Creator Store lets an influencer sell digital products, courses and bookings from one link, and turns an Instagram comment into a personalised checkout link by auto-DM. This document is the high-level design (HLD): it fixes the system's shape, boundaries, data, flows and external dependencies so that each module can then be designed in detail (LLD) without re-opening these decisions.

**In scope for v1:** creator signup and verification, Instagram keyword automation, storefront pages, checkout and payment for digital products and bookings, delivery, creator payouts, creator subscription billing, admin operations and support.

**Out of scope for v1:** physical-goods shipping, marketplace discovery, email marketing, affiliates, multi-currency pricing per product, iOS/Android native apps (the web app is mobile-first and is what the DM link opens).

**Architecture decision in one line:** a modular monolith (one Spring Boot deployable, one PostgreSQL) with strict module boundaries, plus a separate worker process for queues and schedulers, plus Redis for rate limits and caching. Section 13 says what would make us split it.

**How to read this:** sections 2 to 4 say what the system must do and how big it is; 5 to 8 say what it is made of; 9 to 11 say how it behaves at runtime; 12 to 16 say how it runs in production; 17 lists what is still open before LLD starts.

**Scale beyond launch:** the distributed, auto-scaling version of this design for millions of users is in Distributed design (scale). It keeps sections 2, 3, 7 and 8 of this tab and replaces the rest.

## 2. Actors and use cases

Three human actors and four external systems touch the platform. Customers never create an account; their identity is their Instagram user id plus the email they give at checkout.

| Actor | Who | What they do | How they authenticate |
| --- | --- | --- | --- |
| Creator | Influencer selling to followers | Sign up, verify phone, connect Instagram, add products and booking slots, create keyword rules, publish storefront, view sales, receive payouts, pay the monthly plan | Email or username + password; 15-min JWT + 30-day rotating refresh token |
| Customer | Follower who comments a keyword or opens the store link | Receive auto-DM, open the personalised page, pay, download or book, request support | None. Tracking token in the link identifies the visit; email at checkout identifies the buyer |
| Admin | Platform staff (SUPER\_ADMIN, SUPPORT, FINANCE) | Review and suspend creators, review flagged products, handle refunds, disputes, payouts, support tickets, watch error dashboards | Email + password; 8-hour JWT, no refresh |
| Meta (Instagram Graph API) | External system | Sends comment and message webhooks; receives our DM send calls; issues OAuth tokens | App secret, webhook signature, per-creator access token |
| Payment provider (Stripe or Razorpay) | External system | Hosts the card form, charges the customer, sends payment, refund and dispute webhooks, pays out to creators | API key, webhook signing secret |
| SMS provider (Twilio, MSG91) | External system | Delivers verification links | API key |
| Object storage + CDN (S3-compatible) | External system | Stores product files and images; serves them through signed URLs | IAM credentials |

**Primary use cases, by actor**

1. Creator: sign up → verify phone → connect Instagram → add a product → set a price → create a rule "comment SKIN on post X" → publish storefront.
2. Customer: comment SKIN → receive DM with link → open page → pay → receive file or booking confirmation.
3. Creator: see orders, clicks and DM counts per rule; request a payout; change plan.
4. Admin: see new creators and flagged products; suspend a creator for chargebacks; refund an order; run the weekly payout batch; answer a ticket.
5. System: receive a payment webhook → mark order paid → deliver → record platform fee; retry failed DMs; renew expiring Instagram tokens; bill creator subscriptions monthly.

## 3. Requirements

The hard requirements are: never lose a paid order, never send a DM Meta would not allow, and never let an unverified or suspended creator sell. Everything else can degrade.

**Functional**

| # | Requirement | Owner module |
| --- | --- | --- |
| F1 | Creator signup with unique email and username, optional phone, SMS-link verification | auth, verification |
| F2 | Login by email or username; access token + rotating refresh token; lockout after 5 failures | auth |
| F3 | Connect one Instagram Professional account per creator via Meta OAuth; store token encrypted; refresh before expiry | automation |
| F4 | Products of type DIGITAL, COURSE, BOOKING with one active price; files up to 500 MB stored privately | product, booking |
| F5 | Keyword rules: trigger on comment or DM, optional post filter, reply template with {link} | automation |
| F6 | Auto-DM within 60 seconds of a matching comment, at most once per customer per rule per 24 h, respecting Meta's messaging window | automation |
| F7 | Tracking link per DM; the storefront page shows the product for that creator and attributes the order | automation, storefront |
| F8 | Public storefront at /{username}; served only when creator ACTIVE, phone verified, page published | storefront |
| F9 | Checkout without account; email required; payment through hosted provider page; webhook marks order PAID | order, payment |
| F10 | Delivery: signed download URL valid 24 h, or booking slot reserved with capacity check | order, booking |
| F11 | Refunds (admin or creator within 7 days), disputes with evidence deadline, platform fee per order | payment |
| F12 | Weekly payout batch per creator, never the same period twice | payment |
| F13 | Creator plan billing (CREATOR, CREATOR\_PRO) monthly; PAST\_DUE after 7 days disables automation | payment |
| F14 | Admin: list and inspect creators, suspend and reinstate with reason, review products, tickets, error list, audit log | admin, support |

**Non-functional**

| Attribute | Target | Why this number |
| --- | --- | --- |
| Storefront page latency | p95 under 300 ms server time, under 1.5 s first paint on 4G | A DM link opened on a phone; slow pages lose the sale |
| API latency | p95 under 200 ms for authenticated reads, under 500 ms for writes | Dashboard feels instant |
| Comment to DM delay | p95 under 60 s, p99 under 5 min | The follower is still looking at the post |
| Availability | 99.9% monthly for storefront and checkout (43 min downtime per month); 99.5% for creator dashboard | Checkout is revenue; dashboard can wait |
| Durability | Zero lost paid orders; webhook events stored before processing | Money |
| Consistency | Orders and payments strongly consistent (one database); analytics eventually consistent (minutes) | Only money needs ACID |
| Security | Passwords BCrypt-12; tokens stored hashed; Instagram tokens AES-GCM encrypted; TLS everywhere; no card data stored | PCI SAQ-A scope only |
| Data retention | Verification tokens 7 days after expiry; refresh tokens 30 days after expiry; session IP and user agent 90 days; orders 7 years | Privacy plus tax law |
| Recovery | RPO 5 min, RTO 1 h | Managed Postgres with point-in-time recovery |
| Scale target for v1 | 5,000 creators, 2 M followers reached, 50,000 orders per month | Section 4 derives the load from this |

## 4. Capacity estimates

At the v1 target (5,000 creators, 50,000 orders a month) the whole system fits on two small application instances, one worker, one managed PostgreSQL and one Redis; the only large numbers are DM log rows and file egress. All figures below are derived from the assumptions in the first table and should be re-done when real numbers exist.

**Assumptions**

| Input | Value | Basis |
| --- | --- | --- |
| Creators | 5,000 total, 2,000 active in a given week | Typical 40% weekly activity for creator tools |
| Campaigns ("comment X" posts) | 2 per active creator per week | Observed cadence of link-in-bio sellers |
| Keyword comments per campaign | 300 | Mid-size accounts (10k to 100k followers) |
| DM click-through | 40% | Direct-message links convert far above bio links |
| Page-to-order conversion | 3% from DM traffic, 1% from bio-link traffic | Industry range for low-ticket digital goods |
| Average product file | 50 MB | PDFs, presets, short video courses |
| Peak factor | 30x average | Traffic clusters in evening hours in one or two time zones |

**Derived load**

| Quantity | Per day | Average per second | Peak per second | Notes |
| --- | --- | --- | --- | --- |
| Comment and DM webhooks from Meta | 171,000 | 2 | 60 | Must be acknowledged in under 5 s; stored first, processed later |
| Auto-DMs sent | 137,000 | 1.6 | 48 | After 24 h dedup per customer per rule; subject to Meta per-account limits |
| Storefront page views | 165,000 | 1.9 | 57 | DM clicks plus bio-link visits; cacheable for 60 s per creator |
| Orders | 1,700 | 0.02 | 2 | 50,000 per month |
| Payment webhooks | 5,000 | 0.06 | 5 | About 3 events per order (created, succeeded, payout) |
| File downloads | 1,700 | 0.02 | 2 | 85 GB per day of CDN egress, 2.5 TB per month |
| SMS sent | 500 | 0.006 | 1 | Signups plus number changes; cost item, not a load item |

**Storage growth per year**

| Table or store | Rows per year | Size per year | Retention |
| --- | --- | --- | --- |
| dm\_logs | 50 M | 15 GB | 180 days, then aggregate to daily counts |
| tracking\_links | 50 M | 12 GB | 180 days after last click |
| orders + payments + fulfillments | 0.6 M | 0.5 GB | 7 years |
| creators, products, prices, rules | under 0.5 M | under 0.2 GB | Life of the account |
| audit\_logs, system\_errors | 2 M | 1 GB | 2 years |
| Product files (object storage) | 25,000 files | 1.25 TB | While the product exists |

**What this means for the design:** PostgreSQL handles this comfortably for years (tens of GB, hundreds of writes per second at peak). The two append-heavy tables (dm\_logs, tracking\_links) get time-based partitioning from day one so retention is a partition drop, not a DELETE. File bytes never pass through the application: uploads go straight to object storage with a pre-signed URL, downloads are signed CDN URLs. At 10x growth (50,000 creators) the same shape holds with larger instances and read replicas; nothing needs to be re-architected before roughly 100x.

## 5. Architecture overview

The system is one Spring Boot codebase deployed in two roles (API and worker), one PostgreSQL database that holds all state, Redis for rate limits and short-lived cache, and object storage for files. Three browser clients reach the API only through the CDN and WAF; three external systems are reached by the application and call back through webhooks.

[diagram: system overview · 3 clients, 1 application in 2 roles, 3 data stores, 3 external systems]
The accented box is what we build; every other box is a managed service or an outside system. Webhooks from Meta and the payment provider pass through the same CDN and WAF as browser traffic before they reach the API.

**Why a modular monolith and not microservices from day one**

- One team, one deployable, one database: no network calls between our own modules, so no partial failures inside a business transaction (an order, its payment and its fulfilment commit together).
- Module boundaries are enforced in code (each module exposes a service, owns its tables, and never touches another module's tables). That keeps the option to extract a module later without rewriting it.
- The two roles share the jar and differ by Spring profile: the API role serves HTTP; the worker role runs queues and schedulers. This isolates slow outbound work (Meta, SMS) from request latency without a second codebase.
- The first candidate for extraction, if ever needed, is the automation module (webhook ingestion and DM sending), because its load is the most bursty and least coupled to money. Section 13 gives the triggers.

**Request path**

1. Browser or webhook → CDN/WAF (TLS termination, static assets from cache, per-IP rate limits, bot filtering).
2. → Load balancer → one of N stateless API pods (no sticky sessions; identity is the JWT).
3. → Module service → PostgreSQL primary for writes, read replica for storefront reads and dashboards.
4. Anything that leaves the system (DM, SMS, email, payout) is written to a table first and sent by the worker; only the payment checkout session and Meta OAuth are called synchronously, because the user is waiting for the redirect.

## 6. Component catalogue

Each module owns its tables, exposes one service, and depends only on the services listed. The dependency graph has no cycles: common, config and security are shared by all; the money modules (order, payment) depend on the catalogue modules (product, booking), never the reverse.

| Module | Owns (tables) | Exposes (service API, used by) | Depends on | Runs in |
| --- | --- | --- | --- | --- |
| common | — | ApiException, Normalizer, Tokens, error handler | — | API, worker |
| config, security | — | AppProperties, JWT encode/decode, security filter chain, AuthenticatedUser | — | API, worker |
| creator | creators | register, find, status checks, login counters, phone change (used by auth, verification, storefront, admin, payment) | — | API |
| auth | refresh\_tokens | signup, login, refresh, logout (used by controllers only) | creator, verification | API |
| verification | phone\_verification\_tokens | issueLink, confirm (used by auth, creator endpoints) | creator, notification | API, worker (cleanup) |
| notification | outbox\_messages | enqueue SMS/email; SmsSender and EmailSender adapters (used by verification, order, payment, admin) | — | API (enqueue), worker (send) |
| admin | admin\_users | admin login, admin management, suspend/reinstate creator (writes audit log) | creator, support | API |
| product | products, prices, product\_files | create/edit/hide product, set price, pre-signed upload URL, getActivePrice (used by storefront, automation, order) | creator | API |
| booking | booking\_sessions, booking\_slots | define session, generate slots, reserve/release slot with capacity lock (used by order) | product | API |
| storefront | store\_pages, page\_views | render public page by slug, publish/unpublish, resolve tracking token to product (used by nothing; it is a leaf) | creator, product, automation | API |
| automation | social\_accounts, automation\_rules, webhook\_events, dm\_logs, tracking\_links | connect Instagram, manage rules, ingest webhook, match rule, enqueue DM, send DM, mint tracking link (used by storefront, order for attribution) | creator, product, customer | API (ingest, rules), worker (send, token refresh) |
| customer | customers | findOrCreate by platform id, attach email (used by automation, order) | — | API |
| order | orders, fulfillments | create order from price snapshot, mark paid/failed, deliver, signed download URL (used by payment via events, storefront checkout) | product, booking, customer, automation, notification | API, worker (delivery retry) |
| payment | payments, refunds, disputes, payout\_accounts, payout\_batches, creator\_payouts, creator\_subscriptions | create checkout session, handle provider webhook, refund, dispute evidence, payout batch, plan billing (used by order, admin) | order, creator, notification | API (webhooks, checkout), worker (payouts, billing) |
| support | support\_tickets, support\_messages, system\_errors, audit\_logs, creator\_status\_changes | open ticket, reply, log system error, append audit entry (used by admin, every module for errors) | creator, customer, order | API |

**Module interaction rules (enforced in code review and, later, by ArchUnit tests)**

- A module calls another module's service, never its repository or entity setters.
- A module that needs to react to another module's change (order PAID → deliver → email) listens to an in-process domain event published after commit, so the modules stay decoupled and the reaction can be retried by the worker.
- Shared read models for dashboards (sales per creator, DM counts) are SQL views over the owning modules' tables, read-only, and live in a reporting package; they never become a dependency of a write path.
- Every outbound side effect (DM, SMS, email, payout call) is recorded in the owning module's table inside the business transaction and executed by the worker. Only two calls are synchronous because the user waits on a redirect: payment checkout-session creation and Meta OAuth token exchange.

## 7. Data model

One PostgreSQL database, 28 tables, owned by module. The entity map shows the 15 that carry the business; the catalogue below lists every table with its keys and lifecycle. Each arrow is a foreign key and points at the parent row.

[diagram: entity map · 15 core tables, arrows point from child to parent]
Log and support tables (dm\_logs, webhook\_events, outbox\_messages, audit\_logs, system\_errors, support tickets) and the auth tables (refresh\_tokens, phone\_verification\_tokens, admin\_users) are omitted from the picture because they have at most one parent each.

**Conventions that apply to every table**

- `id BIGSERIAL` primary key; `created_at` always; `updated_at` where rows change. Timestamps are `TIMESTAMPTZ`.
- Enum-like columns store the Java enum name in upper case with a `CHECK` constraint.
- Money is `BIGINT` minor units plus `CHAR(3)` currency; never floating point.
- Secrets are stored only as hashes (passwords, tokens) or encrypted (Instagram access tokens).
- Uniqueness is declared only for real identifiers: email, username, provider ids, platform ids. Names and phone numbers are never unique.
- Every webhook table has a unique provider event id so a redelivered event is a no-op.

**Entity catalogue**

| Entity | Module | Identity and key fields | Parent(s) | Lifecycle and notes |
| --- | --- | --- | --- | --- |
| creators | creator | email (unique), username (unique), password\_hash, phone\_number, phone\_verified, account\_status | — | ACTIVE → SUSPENDED → ACTIVE or BANNED; login counters and lock live here |
| refresh\_tokens | auth | token\_hash (unique), family\_id, expires\_at, revoked\_at, replaced\_by\_id | creators | Rotated on every refresh; reuse revokes the family; purged 30 days after expiry |
| phone\_verification\_tokens | verification | token\_hash (unique), phone\_number, expires\_at, used\_at | creators | One open link per creator (partial unique index); purged 7 days after expiry |
| admin\_users | admin | email (unique), role (SUPER\_ADMIN, SUPPORT, FINANCE), is\_active | — | Separate from creators on purpose; no refresh tokens |
| social\_accounts | automation | (platform, platform\_account\_id) unique, access\_token\_encrypted, token\_expires\_at, connection\_status | creators | One Instagram account per creator in v1; CONNECTED → EXPIRED or REVOKED or RESTRICTED |
| products | product | title, product\_type (DIGITAL, COURSE, BOOKING), status (DRAFT, ACTIVE, HIDDEN, ARCHIVED), review\_status | creators | A product never deletes once ordered; it archives |
| prices | product | amount\_minor, currency, valid\_from, valid\_to, is\_active | products | Orders snapshot the amount; changing a price creates a new row |
| product\_files | product | storage\_key, size\_bytes, content\_type, upload\_status | products | Uploaded straight to object storage by pre-signed URL; row confirms completion |
| booking\_sessions | booking | duration\_minutes, timezone, capacity | products (1:1) | Only for product\_type BOOKING |
| booking\_slots | booking | starts\_at, ends\_at, booked\_count; (session, starts\_at) unique | booking\_sessions | booked\_count ≤ capacity enforced under a row lock |
| store\_pages | storefront | slug (unique), display\_name, theme, is\_published | creators (1:1) | Served only when creator ACTIVE, phone verified, published |
| automation\_rules | automation | trigger\_keyword, trigger\_source (COMMENT, DM, BOTH), source\_post\_id, reply\_template, is\_active | creators, social\_accounts, products | Keyword stored lowercase; at most one active rule per (account, keyword, post) |
| webhook\_events | automation | (provider, provider\_event\_id) unique, payload\_json, received\_at, processed\_at, status | — | Stored before any processing; the worker reads from here |
| customers | customer | (platform, platform\_user\_id) unique, platform\_username, email | — | No password; email attached at first checkout |
| dm\_logs | automation | platform\_event\_id (unique), status (QUEUED, SENT, FAILED, CANCELLED), attempts, next\_attempt\_at | automation\_rules, creators, customers | The DM queue; partitioned by month; 180-day retention |
| tracking\_links | automation | token (unique, short), click\_count, expires\_at | creators, products, customers, dm\_logs | Personalises the page and attributes the order |
| orders | order | amount\_minor, currency, platform\_fee\_minor, status (PENDING, PAID, FAILED, REFUNDED, PARTIALLY\_REFUNDED, DISPUTED) | creators, customers, products, prices, tracking\_links, booking\_slots | The money record of truth; status changes only through PaymentService events |
| fulfillments | order | delivery\_type (FILE, BOOKING, ACCESS), file\_key, status, attempts, access\_expires\_at | orders (1:1) | Created when the order is PAID; retried by the worker until DELIVERED |
| payments | payment | (provider, provider\_payment\_id) unique, amount\_minor, status | orders | One order may have several attempts; exactly one SUCCEEDED |
| refunds | payment | provider\_refund\_id (unique), amount\_minor, reason, status | orders, payments, admin\_users (optional) | Partial refunds allowed; total never exceeds the payment |
| disputes | payment | provider\_dispute\_id (unique), status, response\_due\_at | orders, payments | Admin uploads evidence before the deadline |
| payout\_accounts | payment | provider\_account\_id, status (PENDING, VERIFIED, REJECTED) | creators (1:1) | Never raw bank numbers; the provider holds them |
| payout\_batches | payment | status, created\_by | admin\_users | One batch per payout run |
| creator\_payouts | payment | (creator, period\_start, period\_end) unique, amount\_minor, status | payout\_batches, creators | The unique key makes double payment impossible |
| creator\_subscriptions | payment | plan (CREATOR, CREATOR\_PRO), status (TRIALING, ACTIVE, PAST\_DUE, CANCELLED), current\_period\_end | creators (1:1) | PAST\_DUE for 7 days disables automation rules |
| outbox\_messages | notification | channel (SMS, EMAIL), recipient, template, payload\_json, status, attempts, next\_attempt\_at | — | Written in the caller's transaction; sent by the worker |
| support\_tickets, support\_messages | support | requester\_type, category, priority, status, assigned\_admin\_id; is\_internal\_note on messages | creators or customers, orders, admin\_users | Internal notes never returned to requesters |
| system\_errors | support | source, error\_code, context\_json, resolved\_at | creators (optional) | The admin error list; 2-year retention |
| audit\_logs | support | admin\_id, action, target\_type, target\_id, details\_json | admin\_users | Append-only; written in the same transaction as the admin action |
| creator\_status\_changes | support | from\_status, to\_status, reason\_code, reason\_text | creators, admin\_users | History behind creators.account\_status |

**Partitioning and retention.** `dm_logs`, `webhook_events`, `tracking_links` and `page_views` are range-partitioned by month from the first migration; retention is a monthly partition drop. All other tables are small enough to keep whole.

## 8. API surface

All endpoints are JSON over HTTPS under `/api`. Auth levels: **public** (no token), **creator** (JWT role CREATOR), **admin** (JWT role ADMIN, optionally a specific admin\_role), **webhook** (provider signature, no JWT). Errors always have the shape `{"code": "USERNAME_TAKEN", "message": "..."}`.

**Creator identity and sessions** (built)

| Method | Path | Auth | Purpose |
| --- | --- | --- | --- |
| GET | /api/auth/username-availability?username= | public | Live check while typing |
| POST | /api/auth/signup | public | Create creator; sends SMS link if phone given |
| POST | /api/auth/login | public | Access + refresh token; lockout after 5 failures |
| POST | /api/auth/refresh | public | Rotate refresh token; reuse revokes the family |
| POST | /api/auth/logout, /logout-all | public / creator | Revoke one or all sessions |
| POST | /api/verification/phone/confirm | public | Consume the SMS link token |
| POST | /api/verification/phone/resend | creator | New link to current number |
| PUT | /api/verification/phone | creator | Change number, re-verify |
| GET | /api/creators/me | creator | Own profile |

**Products, bookings, storefront**

| Method | Path | Auth | Purpose |
| --- | --- | --- | --- |
| GET, POST | /api/products | creator | List own products; create (DRAFT) |
| GET, PUT, DELETE | /api/products/{id} | creator | Read, edit, archive (owner-checked) |
| POST | /api/products/{id}/prices | creator | New active price; old one closes |
| POST | /api/products/{id}/files/upload-url | creator | Pre-signed PUT URL to object storage |
| POST | /api/products/{id}/files/{fileId}/complete | creator | Confirm upload; row becomes READY |
| PUT | /api/products/{id}/booking | creator | Duration, timezone, capacity |
| POST | /api/products/{id}/booking/slots | creator | Generate or add slots |
| GET, PUT | /api/storefront | creator | Own page settings |
| POST | /api/storefront/publish, /unpublish | creator | Gated: ACTIVE, phone verified, 1+ ACTIVE product |
| GET | /api/public/stores/{slug} | public | Page data; cached 60 s; 404 unless published |
| GET | /api/public/stores/{slug}/products/{id} | public | Product detail |
| GET | /api/public/l/{token} | public | Resolve tracking link → product; increments clicks; 302 to page |

**Automation**

| Method | Path | Auth | Purpose |
| --- | --- | --- | --- |
| GET | /api/automation/instagram/connect | creator | Redirect to Meta OAuth with state |
| GET | /api/automation/instagram/callback | public (state-checked) | Exchange code; store encrypted token |
| DELETE | /api/automation/instagram | creator | Disconnect; deactivate rules |
| GET, POST | /api/automation/rules | creator | List, create rule |
| PUT, DELETE | /api/automation/rules/{id} | creator | Edit, deactivate |
| GET | /api/automation/rules/{id}/stats | creator | DMs sent, clicks, orders (read replica) |
| GET | /api/webhooks/meta | webhook | Verification challenge |
| POST | /api/webhooks/meta | webhook (X-Hub-Signature-256) | Store event, return 200 within 1 s |

**Checkout, orders, payments**

| Method | Path | Auth | Purpose |
| --- | --- | --- | --- |
| POST | /api/public/checkout | public | Create PENDING order from (product, price, email, tracking token, slot); returns provider checkout URL |
| GET | /api/public/orders/{orderToken} | public | Order status page after redirect |
| GET | /api/public/orders/{orderToken}/download | public | Signed file URL, valid 24 h, max 5 uses |
| POST | /api/webhooks/payments | webhook (provider signature) | Store event; worker applies it |
| GET | /api/orders | creator | Own orders, filters, pagination |
| POST | /api/orders/{id}/refund | creator (7-day window) | Full refund of own order |
| GET, PUT | /api/payouts/account | creator | Payout account onboarding (provider-hosted) |
| GET | /api/payouts | creator | Payout history |
| GET, POST | /api/subscription | creator | Current plan; start or change plan (provider checkout) |

**Admin**

| Method | Path | Auth | Purpose |
| --- | --- | --- | --- |
| POST | /api/admin/auth/login | public | 8-hour admin token |
| GET | /api/admin/me | admin | Own admin profile |
| GET | /api/admin/creators?status=&q= | admin | Search and list |
| GET | /api/admin/creators/{id} | admin | Full detail: products, rules, orders, tickets, status history |
| POST | /api/admin/creators/{id}/suspend, /reinstate, /ban | admin (SUPER\_ADMIN, SUPPORT) | Writes creator\_status\_changes + audit\_logs; disables rules |
| GET, POST | /api/admin/products/review | admin | Queue of PENDING products; approve or reject |
| POST | /api/admin/orders/{id}/refund | admin (SUPER\_ADMIN, FINANCE) | Any amount, with reason |
| GET, POST | /api/admin/disputes, /{id}/evidence | admin (FINANCE) | Open disputes; submit evidence |
| POST | /api/admin/payouts/batches | admin (FINANCE) | Create and run a payout batch |
| GET, PUT | /api/admin/tickets, /{id} | admin | Support queue, assignment, replies, internal notes |
| GET | /api/admin/errors | admin | system\_errors, unresolved first |
| GET | /api/admin/audit?target= | admin (SUPER\_ADMIN) | Audit log |
| GET, POST | /api/admin/users | admin (SUPER\_ADMIN) | Manage admin accounts |

**Cross-cutting API rules**

- Pagination: `?page=&size=` with `size` capped at 100; responses carry `total` and `nextPage`.
- Idempotency: `POST /api/public/checkout` and refund endpoints accept an `Idempotency-Key` header stored for 24 h in Redis; a repeat returns the first response.
- Rate limits (Redis token buckets): signup 5 per IP per hour, login 10 per IP per 15 min, checkout 20 per IP per hour, public store reads 300 per IP per minute, webhooks unlimited but signature-checked.
- Versioning: none in v1; breaking changes add `/api/v2/...` for the affected module only.

## 9. Key flows

The flow that earns money is drawn; the others are listed as steps. In every flow, anything that leaves the system is written to a table first and sent by the worker, so a crash between two steps loses nothing.

[diagram: comment to delivery · 17 messages across 6 participants]
The API answers both webhooks in under a second by storing the event and returning 200; all matching, sending and state changes happen in the worker, which retries on failure. Two calls are synchronous because a person is waiting on a redirect: creating the checkout session and (not shown) the Meta OAuth exchange.

**Flow A: creator signup and phone verification** (built)

1. Browser checks username availability while typing (GET, debounced).
2. POST /signup: normalise, check reserved names, insert creator; unique constraints settle races.
3. If a phone was given: close older links, insert a hashed token, write an outbox SMS, all in the same transaction. Response 201.
4. Worker sends the SMS (3 per phone per hour).
5. User taps the link; the frontend POSTs the token; the row is locked, checked (unused, unexpired, same phone), marked used, and phone\_verified becomes true.

**Flow B: login and session refresh** (built)

1. POST /login: look up by email or username; unknown identifiers still cost one BCrypt comparison.
2. Reject if not ACTIVE or locked; on wrong password increment the counter atomically (5th failure locks 15 min).
3. On success: reset counters, create a refresh-token family, return a 15-minute JWT and a 30-day refresh token.
4. POST /refresh: lock the token row; revoked means reuse, revoke the whole family and return 401; otherwise revoke it, insert the successor, return new tokens.

**Flow C: connect Instagram**

1. GET /automation/instagram/connect: store a random `state` in Redis (10 min, bound to creator id); redirect to Meta's OAuth dialog with the required scopes (instagram\_basic, instagram\_manage\_comments, instagram\_manage\_messages, pages\_show\_list).
2. Meta redirects to /callback with `code` and `state`; reject unknown or expired state.
3. Exchange code for a short-lived token, then for a long-lived token (60 days) in one synchronous call chain (timeout 10 s).
4. Resolve the Instagram Professional account id; refuse personal accounts with a clear error.
5. Encrypt the token (AES-GCM, key from the secrets manager), upsert social\_accounts, subscribe the account to comment and message webhooks.
6. Worker refreshes tokens 7 days before expiry; a failed refresh sets EXPIRED and emails the creator.

**Flow D: comment to DM (detail of the diagram's first half)**

1. Webhook arrives with `X-Hub-Signature-256`; verify with the app secret; reject if invalid.
2. Insert into webhook\_events keyed by Meta's event id; a duplicate is a silent no-op; return 200.
3. Worker claims NEW events in batches of 100 with `SELECT ... FOR UPDATE SKIP LOCKED`.
4. For each: find the social account; skip if the creator is not ACTIVE or the subscription is PAST\_DUE beyond grace; load active rules for that account; match keyword (case-insensitive, word boundary) and post filter.
5. Upsert the customer; skip if a dm\_log exists for (rule, customer) in the last 24 h; insert tracking\_link and dm\_log QUEUED.
6. DM sender takes QUEUED rows per creator with a Redis token bucket matching Meta's limits; sends the private reply; on success SENT, on rate-limit error reschedule with backoff, on permanent error FAILED and a system\_error row.

**Flow E: payout batch**

1. FINANCE admin creates a batch for a period; the worker computes per creator: sum of PAID order amounts minus platform fee minus refunds, for orders older than the 7-day refund window, minus amounts already paid.
2. Insert creator\_payouts rows; the unique (creator, period) key prevents a second run paying twice.
3. Worker calls the provider's transfer API per row with an idempotency key equal to the payout id; records provider\_payout\_id; SENT or FAILED with reason.
4. Audit log entry for the batch; creators get an email per payout.

**Flow F: admin suspends a creator**

1. Admin POSTs suspend with a reason code and text.
2. One transaction: update creators.account\_status, insert creator\_status\_changes, insert audit\_logs, deactivate automation rules, cancel QUEUED dm\_logs, unpublish the store page.
3. Existing paid customers keep their downloads; new checkouts return 404 for that store.
4. Reinstate reverses the status and re-enables rules; the store page stays unpublished until the creator republishes.

**Flow G: creator subscription billing**

1. Creator picks a plan; API creates a provider subscription checkout; webhook `invoice.paid` sets ACTIVE and current\_period\_end.
2. `invoice.payment_failed` sets PAST\_DUE; after 7 days the worker deactivates automation rules and emails the creator; `invoice.paid` restores them.
3. Cancellation keeps access until period end, then sets CANCELLED and unpublishes the store.

## 10. External integrations and network calls

Every call that leaves the system goes through one adapter class per provider, with a fixed timeout, a retry policy and an idempotency key. Only two calls are made while a user waits; everything else runs in the worker from a table.

**Outbound calls**

| Call | From | Provider API | Sync or worker | Timeout | Retry | Idempotency | Failure handling |
| --- | --- | --- | --- | --- | --- | --- | --- |
| OAuth code exchange + long-lived token | automation | Meta Graph `oauth/access_token` | sync (user redirect) | 10 s | none | n/a (code is single-use) | Show "could not connect"; user retries |
| Subscribe account to webhooks | automation | Meta Graph `/{ig-user-id}/subscribed_apps` | sync, right after connect | 10 s | 3x, 1 s backoff | idempotent by nature | Mark CONNECTED but flag `webhooks_subscribed=false`; worker retries hourly |
| Send private reply or DM | automation | Meta Graph `/{ig-user-id}/messages` | worker, from dm\_logs | 15 s | up to 5 attempts, backoff 1, 5, 25, 125 min | dm\_log id as client reference; one row per (rule, customer, 24 h) | Rate-limit codes reschedule without counting an attempt; policy errors (window closed, user blocked) set FAILED, no retry |
| Refresh long-lived token | automation | Meta Graph `refresh_access_token` | worker, daily, 7 days before expiry | 10 s | 3 attempts across 3 days | n/a | On final failure set EXPIRED, deactivate rules, email creator |
| Create checkout session | payment | Stripe `checkout.sessions` or Razorpay `orders` | sync (user redirect) | 8 s | 1 retry on timeout only | order id as idempotency key | Order stays PENDING; user sees "try again"; PENDING orders expire after 1 h |
| Refund | payment | `refunds` | worker, from refunds table | 15 s | 3x | refund id as idempotency key | FAILED with reason; admin notified |
| Submit dispute evidence | payment | `disputes/{id}` | sync (admin waits) | 15 s | none | n/a | Admin retries |
| Create transfer or payout | payment | Stripe Connect `transfers` / Razorpay Route | worker, from creator\_payouts | 20 s | 3x over 1 h | payout id as idempotency key | FAILED with reason; batch shows partial |
| Create subscription session, cancel | payment | `subscriptions` | sync | 8 s | none | creator id + plan | User retries |
| Send SMS | notification | Twilio / MSG91 | worker, from outbox | 5 s | 3x, 1, 5, 30 min | outbox id as message reference | FAILED; creator can request a new link |
| Send email | notification | SES / Postmark | worker, from outbox | 5 s | 5x over 24 h | outbox id as Message-Id | FAILED; download link also visible on the order page |
| Pre-signed upload URL | product | S3 `PUT` presign (local computation, no call) | sync | n/a | n/a | n/a | n/a |
| Signed download URL | order | S3 or CDN signed URL (local computation) | sync | n/a | n/a | n/a | n/a |
| Delete product file | product | S3 `DeleteObject` | worker, 30 days after archive | 10 s | 3x | key | Orphan report weekly |

**Inbound calls (webhooks)**

| Webhook | Sender | Verification | What the API does synchronously | What the worker does |
| --- | --- | --- | --- | --- |
| Comment created, message received | Meta | HMAC-SHA256 of raw body with app secret (`X-Hub-Signature-256`); GET challenge on subscribe | Insert webhook\_events (unique on event id), return 200 within 1 s | Match rules, create customers, dm\_logs, tracking\_links (Flow D) |
| payment succeeded, failed, refunded, dispute opened or closed, invoice paid or failed | Payment provider | Provider signature header with timestamp tolerance 5 min; raw body kept | Insert webhook\_events, return 200 | Apply to payments, orders, refunds, disputes, creator\_subscriptions; create fulfillments; write outbox email |
| Payout paid or failed | Payment provider | Same | Same | Update creator\_payouts |
| Delivery status (optional) | SMS, email provider | Provider signature or basic auth | Update outbox\_messages | — |

Webhooks are never processed inside the HTTP request: the API only verifies, stores and acknowledges. If the database is unavailable the API returns 500 and the provider redelivers (Meta retries for up to 24 h, Stripe for 3 days), so no event is lost.

**Client-side rules for every adapter**

- Connection timeout 2 s, read timeout as in the table, no infinite waits; one HTTP client pool per provider with a maximum of 20 connections.
- Circuit breaker per provider (open after 50% failures over 20 calls, half-open after 30 s); when open, worker rows are rescheduled without counting an attempt.
- Every call logs provider, operation, latency, status and our idempotency key; never the request body for payment calls.
- Secrets (app secret, API keys, webhook signing secrets, token encryption key) come from the secrets manager at startup, never from the repository or the image.
- Provider sandboxes (Meta test app, Stripe test mode, SMS log sender) are the default in every non-production environment; production keys exist only in the production secrets manager.

## 11. Asynchronous processing

All queues are PostgreSQL tables polled by the worker with `SELECT ... FOR UPDATE SKIP LOCKED`; there is no message broker in v1. At the v1 load (under 60 events per second at peak) this is simpler, transactional with the business data, and needs no new infrastructure. Section 13 states when a broker becomes worth it.

**Why table queues and not Kafka or SQS now**

- The work item and the business row commit together (an order and its outbox email in one transaction), so there is no dual-write problem and no lost or phantom messages.
- Retries, scheduling (`next_attempt_at`) and inspection are plain SQL; the admin error page is a query.
- `SKIP LOCKED` lets several worker pods share one queue safely.
- The cost is polling load and table bloat, both manageable below a few hundred items per second with partial indexes on the pending status and monthly partitions.

**Queues and jobs**

| Queue table or job | Producer | Consumer | Trigger | Concurrency | Backoff | Dead-letter |
| --- | --- | --- | --- | --- | --- | --- |
| webhook\_events (status NEW) | API webhook endpoints | Event processor | Poll every 1 s, batch 100 | 2 pods, 4 threads each | none (processing is idempotent; failures go to ERROR with the exception) | status ERROR, visible in admin errors; replay button |
| dm\_logs (QUEUED, next\_attempt\_at ≤ now) | Event processor | DM sender | Poll every 1 s, batch 50 per creator | 1 thread per creator at a time (Redis lock), 8 threads total | 1, 5, 25, 125 min; rate-limit responses reschedule without counting | FAILED after 5 attempts |
| outbox\_messages (PENDING) | Any module inside its transaction | Notification sender | Poll every 2 s, batch 100 | 4 threads | 1, 5, 30 min (SMS); 5 tries over 24 h (email) | FAILED; surfaced in admin errors |
| fulfillments (PENDING) | Payment event handler | Delivery job | Poll every 5 s | 2 threads | 1, 10, 60 min | FAILED; support ticket auto-created |
| refunds, creator\_payouts (PENDING) | Admin or creator action; payout batch | Payment executor | Poll every 10 s | 1 thread (money moves serially) | 3 tries over 1 h | FAILED with provider reason |
| Expire PENDING orders | — | Scheduler, every 5 min | Orders PENDING older than 1 h | 1 | — | — |
| Instagram token refresh | — | Scheduler, daily 03:00 UTC | Tokens expiring within 7 days | 1 | 3 days of attempts | EXPIRED + email |
| Subscription grace enforcement | — | Scheduler, hourly | PAST\_DUE older than 7 days | 1 | — | Rules deactivated + email |
| Payout batch computation | FINANCE admin | On demand | Batch created | 1 | — | Batch FAILED |
| Retention: drop old partitions, delete expired tokens, orphan files | — | Scheduler, daily 03:15 to 03:45 UTC | Age thresholds in section 4 | 1 | — | Alert on failure |
| Daily aggregates for dashboards | — | Scheduler, hourly | Sums per creator per day into `daily_stats` | 1 | — | Alert; dashboards show "as of" time |

**Scheduler safety.** All schedulers use a database advisory lock per job name so two worker pods never run the same job at once. Every job is idempotent: running it twice produces the same state. Jobs record start, end and row counts in a `job_runs` table so a missed night is visible the next morning.

**Ordering guarantees.** Events are processed per aggregate in arrival order where it matters: payment events for one order are serialised by locking the order row; Meta events for one account are processed by one thread at a time. Across different orders or accounts there is no ordering, and none is needed.

**Exactly-once effects.** Each side effect carries an idempotency key derived from our row id (dm\_log id, outbox id, refund id, payout id). A crash after the provider accepted the call but before we recorded it leads to a retry with the same key, which the provider treats as the same request. Where a provider has no idempotency support (some SMS gateways), the worker marks the row SENDING before the call and an operator reviews rows stuck in SENDING for more than 10 minutes.

## 12. Infrastructure and deployment

Production is one cloud region with managed services for everything stateful; the application itself is two container deployments (API, worker) behind a load balancer. The whole v1 footprint costs in the low hundreds of dollars per month before CDN egress.

**Production topology**

| Component | Choice | Size for v1 | Notes |
| --- | --- | --- | --- |
| Edge | Cloudflare (or AWS CloudFront + WAF) | — | TLS, DDoS, bot rules, per-IP rate limits, static asset cache, signed-URL file delivery |
| Load balancer | Cloud ALB | — | Health check `GET /actuator/health/readiness`; drains on deploy |
| API | Container (Spring Boot, profile `api`) on Kubernetes or ECS | 2 pods, 1 vCPU, 2 GB each; HPA on CPU 60% up to 6 | Stateless; JWT only; graceful shutdown 30 s |
| Worker | Same image, profile `worker` | 2 pods, 1 vCPU, 2 GB | Runs queues and schedulers; scales on queue depth |
| PostgreSQL | Managed (RDS, Cloud SQL, Neon) | 2 vCPU, 8 GB, 100 GB SSD, 1 read replica | PITR with 5-min granularity; connection pool via PgBouncer or built-in pooler |
| Redis | Managed, single node with replica | 1 GB | Rate limits, OAuth state, idempotency keys, storefront cache; losing it loses nothing durable |
| Object storage | S3 (or compatible) | 2 TB | Private bucket; pre-signed PUT for upload; CDN signed URLs for download; versioning on |
| Secrets | Cloud secrets manager | — | JWT secret, DB password, Meta app secret, provider keys, token encryption key; rotated yearly, JWT secret supports two active keys during rotation |
| Observability | Managed Prometheus + Grafana, Loki or CloudWatch logs, OpenTelemetry traces | — | Section 16 |
| Frontend | Static React builds on the CDN | — | Three apps: creator, store, admin; same origin as API through CDN routing |

**Environments**

| Environment | Purpose | Data | External providers |
| --- | --- | --- | --- |
| local | Developer laptop; Docker Compose with Postgres and Redis | Seed script | SMS logged; Stripe test mode; Meta test app with a developer Instagram account |
| staging | Shared pre-production; same topology, smaller sizes | Anonymised copy refreshed weekly | Test modes; webhooks via provider test endpoints |
| production | Live | Real | Live keys, only here |

**Deployment pipeline**

1. Push to main → CI: compile, unit tests, integration tests against a real Postgres container (Testcontainers), ArchUnit module-boundary tests, container build, image scan.
2. Deploy to staging automatically; run the smoke suite (signup, login, publish store, checkout in test mode, webhook replay).
3. Promote the same image to production by a manual approval; rolling deploy, one pod at a time, readiness-gated; Flyway migrations run on API startup and are backward compatible with the previous version (expand-then-contract: add columns first, drop them a release later).
4. Roll back = redeploy the previous image; migrations are never rolled back, which is why they must be backward compatible.

**Configuration.** Everything in `application.yml` has an environment-variable override; the image is identical across environments and only variables and secrets differ. A missing required value (JWT secret, DB URL) fails startup immediately rather than at first use.

## 13. Scalability plan

The design scales about 20x from the v1 target by adding pods, a bigger database and read replicas, without code changes. Beyond that, three specific pressures appear, and each has a planned response that the module boundaries already allow.

**How each tier scales**

| Tier | Scales by | Limit before a change is needed | What changes then |
| --- | --- | --- | --- |
| API pods | Horizontal (stateless) | Database connections: 6 pods x 20 = 120 of a 200 limit | PgBouncer in transaction mode; pods can then grow to 20+ |
| Worker pods | Horizontal with SKIP LOCKED | Polling pressure on queue tables at roughly 500 items per second | Move dm\_logs and webhook\_events to a broker (SQS or Kafka); tables stay as the record, the broker carries the signal |
| Storefront reads | CDN cache 60 s per page + Redis cache of page JSON + read replica | Replica lag over 5 s during bursts | Second replica; pre-render page JSON on publish |
| PostgreSQL writes | Vertical; partitioning of log tables | Single primary at roughly 5,000 writes per second | Move dm\_logs and tracking\_links to their own database (same module, different datasource), which the module boundary makes a configuration change |
| File delivery | CDN | None in practice | — |
| Meta DM sending | Per-account rate limits, not our capacity | Meta's per-account caps | Nothing to scale; back-pressure per creator is already designed in |

**Hot spots and their mitigations**

- A viral post: one creator receives 50,000 comments in an hour. Webhook ingestion is a single insert per event and absorbs it; the DM sender is per-creator rate-limited so one creator cannot starve others; the storefront page for that creator is cached at the CDN. The creator sees a queue depth and an estimated send time in the dashboard rather than silent delay.
- Payout day: thousands of transfer calls. Serial execution is deliberate (money) and takes under an hour at 5,000 creators; split into parallel lanes per currency when that exceeds four hours.
- Dashboard analytics: never query raw dm\_logs or orders for a creator's chart. `daily_stats` is pre-aggregated hourly; the dashboard reads it from the replica.

**When to split the monolith (triggers, not dates)**

1. Automation first, when any of: the DM queue exceeds 500 items per second sustained; webhook bursts cause API p95 to breach 500 ms for other endpoints; a second social platform (TikTok, YouTube) is added. It is already the least coupled module: its only inbound dependencies are storefront (resolve tracking token) and order (attribution), both read-only and replaceable by an HTTP call or a shared read model.
2. Payment second, only for compliance or team reasons (a dedicated team, PCI scope isolation). It is coupled to order by events, which become broker messages.
3. Never split creator, product, storefront, order from each other: they share transactions and would gain nothing.

A split keeps the same database at first (separate schema per service), then separates data only when the write load demands it. The ArchUnit tests that enforce module boundaries today are what make this a mechanical change rather than a rewrite.

## 14. Reliability

The system is designed so that the only component whose loss stops checkout is PostgreSQL, and PostgreSQL is managed with automatic failover and point-in-time recovery. Every other failure degrades one feature and leaves a durable record for retry.

**Failure modes and responses**

| Failure | Effect on users | Automatic response | Operator action |
| --- | --- | --- | --- |
| An API pod dies | None (load balancer routes around it) | Kubernetes restarts it; HPA keeps capacity | None |
| All API pods down (bad deploy) | Storefront and dashboard unavailable | Readiness gate stops the rollout at the first pod; previous pods keep serving | Roll back the image |
| Worker pods down | DMs, emails, deliveries delayed; nothing lost (all queued in tables) | Restart; backlog drains at normal rate | Alert if queue age exceeds 5 min |
| PostgreSQL primary fails | Writes fail for 30 to 60 s | Managed failover to standby; pods reconnect | Verify replication; page on repeated failovers |
| PostgreSQL unreachable for longer | Checkout and dashboard down; webhooks get 500 | Providers redeliver webhooks (Meta 24 h, Stripe 3 days); CDN keeps serving cached store pages read-only | Incident |
| Read replica lags or dies | Dashboards stale or slow | Reads fall back to primary via datasource routing | Rebuild replica |
| Redis down | Rate limits, OAuth state, idempotency keys, page cache lost | Rate limiting fails open with a warning log; OAuth connects fail until back; checkout idempotency falls back to the order unique key | Restore; nothing durable was in Redis |
| Object storage or CDN down | Downloads and uploads fail; store pages still load | Delivery job retries; download page shows "try again" | Provider status |
| Meta API down or rate-limiting | DMs delayed | Circuit breaker opens; rows rescheduled without burning attempts | Alert if open longer than 15 min |
| Meta revokes a creator's token | That creator's rules stop | Status REVOKED, rules deactivated, creator emailed to reconnect | None |
| Payment provider down | Checkout cannot start; PENDING orders not confirmed | Circuit breaker; checkout page shows a clear message; webhooks catch up later | Alert |
| Payment webhook never arrives | Order stays PENDING although paid | Reconciliation job every 15 min queries the provider for PENDING orders older than 10 min and applies the result | Review orders in PENDING over 1 h |
| SMS provider down | Signup works; verification link delayed | Outbox retries 3 times over 30 min | Switch provider by configuration |
| Duplicate webhook delivery | None | Unique event id makes the second insert a no-op | None |
| Worker crash mid-DM | At most one duplicate DM to one customer | Row was SENDING; a stuck-row sweep after 10 min re-queues it; Meta ignores duplicate message references where supported | Weekly report of stuck rows |
| Bad migration | Startup fails | Readiness never passes; rollout halts; old pods keep running | Fix forward with a new migration |

**Backups and recovery**

- PostgreSQL: continuous WAL archiving, daily snapshots retained 30 days, point-in-time recovery to any minute (RPO 5 min). Quarterly restore drill into staging with a timing record (RTO target 1 h).
- Object storage: bucket versioning on, cross-region replication for the product-files bucket, lifecycle rule keeps deleted versions 30 days.
- Redis: no backup; everything in it is reconstructible.
- Secrets: versioned in the secrets manager; previous version kept for rollback.

**Graceful degradation order.** If capacity must be shed, it is shed in this order: dashboard analytics → creator dashboard → DM sending (queued, not dropped) → admin console → storefront reads (served from CDN cache) → checkout (last; never shed by choice).

**Data integrity guarantees**

- An order is PAID only after a verified provider event; the application never marks PAID on a client redirect.
- Money columns never change after PAID except through refund rows; sums are derived, never edited.
- A payout period can be paid once (unique key), a refund cannot exceed its payment (checked in the service under the payment row lock), a booking slot cannot exceed capacity (row lock).

## 15. Security and compliance

The platform never sees a card number, stores every secret as a hash or ciphertext, and checks ownership on every creator endpoint. The three highest-value assets are Instagram access tokens (they can message a creator's followers), the payment webhook secret (it can mark orders paid) and the JWT secret (it is every session).

**Authentication and sessions**

| Control | Design |
| --- | --- |
| Passwords | BCrypt cost 12; length 8 to 72; no composition rules; breached-password check at signup (k-anonymity API) is a v1.1 item |
| Access tokens | HS256 JWT, 15 min for creators, 8 h for admins; claims sub, role, admin\_role; issuer validated; clock skew 60 s |
| Refresh tokens | 256-bit random, SHA-256 stored, 30 days, rotated on every use, family revoked on reuse; bound to nothing else in v1 (device binding is a later option) |
| Lockout | 5 failures lock 15 min per account; per-IP limits at the edge stop distributed guessing |
| Enumeration | Same message and same BCrypt cost for unknown and wrong-password; suspended accounts get the same message |
| Admin | Separate table and login; SUPER\_ADMIN creates admins; MFA (TOTP) required for admins before launch |
| Public endpoints | Ignore the Authorization header entirely so stale tokens cannot break refresh or login |

**Authorization**

- Path rules: `/api/admin/**` needs ROLE\_ADMIN, `/api/**` needs ROLE\_CREATOR, everything else denied; finer admin rights by `@PreAuthorize` on admin\_role.
- Ownership: every creator service method takes the caller's id and queries `WHERE creator_id = :callerId`; a foreign id returns 404, not 403, so ids cannot be probed.
- Customers have no principal; access to an order page uses a 128-bit order token in the URL that is shown once after payment and emailed.

**Data protection**

| Data | At rest | In transit | Access |
| --- | --- | --- | --- |
| Instagram access tokens | AES-256-GCM, key in secrets manager, key id stored with the ciphertext for rotation | TLS | Decrypted only in the automation adapter, never logged |
| Passwords, refresh and verification tokens | Hash only | TLS | Never readable |
| Card data | Never stored; hosted provider page; PCI SAQ-A | n/a | n/a |
| Customer email, IP, user agent | Plain, in Postgres with disk encryption | TLS | Creator sees email of own buyers only; IP and UA purged after 90 days |
| Product files | Private bucket, server-side encryption | Signed URLs, 24 h, max 5 downloads per order | Only after PAID |
| Logs | No tokens, no passwords, no card data, no full phone numbers (last 4 only); request ids for correlation | TLS to log store | Engineers |
| Backups | Encrypted by the managed service | — | Operators |

**Application security controls**

- Input: Bean Validation on every DTO; normalisation in one place; database CHECK constraints as the last line.
- Injection: JPA parameters everywhere; native SQL only with named parameters; no string-built queries.
- Webhooks: signature verified on the raw body before parsing; replay window 5 min; event ids unique.
- Uploads: content type and size checked at pre-sign time and again on completion; files are never executed or served inline from our origin; CDN serves them with `Content-Disposition: attachment`.
- Output: JSON only; CORS limited to the three frontend origins; security headers (HSTS, CSP, X-Content-Type-Options) at the edge.
- SSRF: the application never fetches user-supplied URLs.
- Dependencies: image scanning in CI; monthly dependency updates; Spring Boot patch releases within 30 days.
- Secrets: never in the repository, image or logs; startup fails on a missing secret; rotation runbook per secret.

**Abuse and platform policy**

- Meta platform policy: DMs only in response to a user action, within the messaging window, one per keyword comment, opt-out respected; creators agree to these rules at connect time and a violation suspends the rule, not the platform account.
- Content: products in restricted categories (health claims, financial advice) require admin review before going ACTIVE; a reporting link on every store page creates a support ticket.
- Fraud: velocity checks at checkout (same email or IP more than 5 orders per hour), chargeback-rate suspension threshold (2% over 30 days), payouts held 7 days after the order for refunds.

**Privacy and legal**

- Data subject requests: export and delete endpoints for creators (self-service) and customers (via support); deletion anonymises orders rather than removing them, because financial records are retained 7 years.
- Regional: India's DPDP Act and GDPR both require purpose limitation and consent for marketing; the DM is transactional (a reply to a request), and the email at checkout is used only for delivery unless the customer opts in.
- Tax: provider-side invoicing handles GST or VAT on the platform fee; creators are responsible for their own sales tax, stated in the terms.

## 16. Observability

Five signals tell us whether the business is working: checkout success rate, comment-to-DM delay, webhook backlog age, payment webhook lag, and error rate per provider. Everything else supports diagnosing those.

**Logs**

- Structured JSON from Spring Boot (Logback JSON encoder) with `requestId`, `creatorId` where known, module, and the provider idempotency key for outbound calls. No secrets, no card data, phone last 4 only.
- One log line per request at INFO with method, path template (not raw path), status, duration; one per worker item with queue, id, outcome, attempt.
- Retention 30 days hot, 1 year cold.

**Metrics (Micrometer to Prometheus)**

| Metric | Type | Labels | Alert |
| --- | --- | --- | --- |
| http\_server\_requests | histogram | uri, method, status | p95 over 500 ms for 5 min; 5xx rate over 1% for 5 min |
| checkout\_started, checkout\_paid | counters | provider | paid/started under 60% for 30 min (normal is 70 to 85%) |
| webhook\_backlog\_age\_seconds | gauge | provider | over 300 s |
| dm\_queue\_depth, dm\_queue\_age\_seconds | gauge | — | age over 300 s; depth over 50,000 |
| dm\_sent, dm\_failed | counters | error\_code | failed/sent over 5% for 15 min |
| comment\_to\_dm\_seconds | histogram | — | p95 over 60 s for 15 min |
| payment\_webhook\_lag\_seconds | histogram | provider | p95 over 120 s |
| orders\_pending\_over\_1h | gauge | — | over 20 |
| outbox\_failed, fulfillment\_failed | counters | channel | any increase |
| provider\_call\_duration, provider\_call\_errors | histogram, counter | provider, operation | circuit open longer than 15 min |
| db\_pool\_active, db\_pool\_wait | gauge | pod | wait over 100 ms |
| job\_run\_duration, job\_last\_success\_timestamp | gauge | job | last success older than 2x the interval |
| ig\_tokens\_expiring\_7d, ig\_tokens\_expired | gauge | — | expired over 0 for 24 h |

**Traces**

- OpenTelemetry auto-instrumentation for HTTP, JDBC and the HTTP clients; trace id in every log line; 10% sampling in production, 100% for errors and for any request over 1 s.
- The comment-to-DM path is traced across the webhook request and the worker item by storing the trace id on the webhook\_events row and continuing it in the worker.

**Dashboards**

1. Business: orders and revenue per hour, checkout funnel (page views → checkout started → paid), DMs sent, active creators.
2. Platform health: request rates and latencies per module, error rates, queue depths and ages, job freshness.
3. Providers: call volume, latency, error codes and circuit state for Meta, payments, SMS, email.
4. Database: connections, slow queries (log over 200 ms), replication lag, partition sizes.

**Alert routing.** Checkout, payment and database alerts page on-call at any hour; DM delay and provider circuit alerts page in business hours and otherwise notify; everything else goes to a channel reviewed daily. Every alert links to a runbook page that states the first three checks.

**Audit and support visibility.** The admin console reads system\_errors and audit\_logs directly, so support can see a creator's failed DMs and an admin's past actions without engineering help.

## 17. Open decisions and LLD checklist

Eight decisions are still open; none blocks the LLD of auth, creator, product or storefront, and the first three must be settled before the payment and automation LLDs start.

**Open decisions**

| # | Decision | Options | Recommendation | Needed before |
| --- | --- | --- | --- | --- |
| 1 | Payment provider for launch market | Razorpay (India: UPI, cards, Route payouts) vs Stripe (global, Connect payouts) | Razorpay if launching in India first; abstract behind one PaymentProvider interface either way | payment LLD |
| 2 | Platform fee model | Flat monthly plan only (Stan-style) vs plan + percentage | Flat plan, 0% on sales; keeps the order model simple and the pitch clear | payment LLD |
| 3 | Meta API path | Instagram Graph API via Facebook Page vs Instagram API with Instagram Login (no Page) | Instagram Login path; fewer permissions, no Facebook Page requirement for creators | automation LLD |
| 4 | DM content | Private reply to the comment vs DM to the user | Private reply first (allowed in response to a comment), DM fallback when the user has messaged before | automation LLD |
| 5 | Customer accounts | None (order token by email) vs optional magic-link login | None in v1; magic link in v1.1 if repeat buyers ask for history | order LLD |
| 6 | File size limit and video | 500 MB files vs streaming video courses | 500 MB files only; video courses by embedded Vimeo/YouTube unlisted links in v1 | product LLD |
| 7 | Multi-currency | Single currency per creator vs per product | Single currency per creator, set once from country | product LLD |
| 8 | Admin MFA | TOTP vs passkeys | TOTP at launch | admin LLD |

**Not decided on purpose (deferred until data exists)**

- Message broker: table queues until the triggers in section 13 are hit.
- Read-replica routing per endpoint: start with storefront and dashboards only.
- Search over products or creators: none in v1; there is no marketplace.

**LLD order and what each LLD must contain**

The order follows dependencies: product → storefront → customer and order → payment → automation → booking → support and admin screens. Auth, creator and verification are already built and serve as the template.

Each module LLD is complete when it has:

- [ ] The Flyway migration, applied and tested on PostgreSQL, with every constraint exercised
- [ ] Entity classes with intention-revealing methods (no setters) and the state machine written out as a table
- [ ] The service interface other modules will call, with transaction boundaries marked
- [ ] Controller endpoints with request and response DTOs and validation rules
- [ ] Error codes the module can return, added to the shared list
- [ ] Concurrency hazards named and the lock or atomic statement that handles each
- [ ] Outbound calls listed with timeout, retry, idempotency key (section 10 format)
- [ ] Worker jobs listed with trigger, batch size, backoff, dead-letter (section 11 format)
- [ ] Metrics and alerts the module emits (section 16 format)
- [ ] Test plan: unit tests for rules, Testcontainers integration tests for the repository and service, one end-to-end test per key flow
- [ ] ArchUnit test asserting the module's allowed dependencies
- [ ] HANDOFF.md updated

**Review checklist for this HLD (what to challenge before accepting it)**

1. Do the capacity assumptions in section 4 match the launch plan? A 10x error here changes nothing; a 100x error changes section 13.
2. Is the flat-fee model (decision 2) right? If a percentage fee is wanted, orders gain `platform_fee_minor` logic and payouts become net of it (already modelled).
3. Is one Instagram account per creator enough for launch?
4. Is a 7-day payout hold acceptable to creators, or should it be configurable per trust level?
5. Are the retention periods (section 3 and 4) consistent with the legal advice for the launch country?
6. Is anything in the API surface (section 8) missing a screen you expect to build?
