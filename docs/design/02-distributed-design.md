# Distributed design for millions of users

Oct 10, 2026 · @Sriram

> Source of truth. This file is the design any session (Claude Code on the Mac, a claude.ai chat, a human) reads and edits by pull request. A readable copy with the diagrams lives in the Claude Doc (https://claude.ai/code/artifact/ef02df90-0f59-4d16-bbf8-fa7f3bef448c); when this file changes, the Doc is updated to match, not the other way round. Diagrams are shown here as `[diagram: ...]` placeholders.

This tab takes the launch design in the first tab and re-shapes it for millions of followers, hundreds of thousands of creators and automatic scaling. The actors, requirements, entities and API surface from the first tab still hold; what changes is how the system is split, where data lives, how work flows between parts, and how each part grows and shrinks on its own.

## 1. What changes and why

The target is 500,000 creators reaching 200 million followers, with every tier growing and shrinking on its own in response to load. Three forces break the launch monolith on the way there, and the distributed design answers each one directly.

| Force | Where it appears first | What breaks in the monolith | The answer |
| --- | --- | --- | --- |
| Burst load from social media | Comment webhooks and DM sending: a viral post sends 50,000 comments in an hour to one creator, while thousands of other creators carry on | One process handles both checkout and webhook bursts; one database takes both writes; a hot creator starves the queue | Separate services with separate scaling signals; an event backbone (Kafka) that absorbs bursts; per-creator partitioning with a hot lane |
| Write volume on log-shaped data | dm\_logs, tracking\_links, webhook\_events, page\_views: billions of rows a year | A single PostgreSQL primary caps at a few thousand writes per second and the tables outgrow it | Move high-volume, TTL-shaped data to a wide-column store (DynamoDB or ScyllaDB) and analytics to a column store (ClickHouse); keep PostgreSQL for money and catalogue |
| Geography and availability | Creators in India and the US, followers everywhere; 99.95% for checkout | One region, one primary, one failover | Edge-served storefronts worldwide, single-writer shards per region, cross-region replicas for reads and failover |

**Principles of the distributed design**

1. Partition by creator everywhere. Every table, topic and cache key is partitioned by `creator_id`. A creator's data lives together, ordering is guaranteed per creator, and a hot creator is isolated rather than contagious.
2. Money stays strongly consistent and small. Orders, payments, refunds and payouts live in PostgreSQL with transactions; they are 1% of the write volume and 100% of the risk.
3. Everything else is an event. Comments, DMs, clicks, page views and deliveries are events on Kafka; consumers are idempotent and can be replayed.
4. Each service scales on the signal that reflects its own work: requests per second for APIs, consumer lag for processors, connection and CPU for databases, never a shared global number.
5. The edge serves the store. Storefront pages and tracking-link redirects are answered from the CDN and an edge key-value store in under 50 ms anywhere in the world; the origin is only for cache misses and writes.
6. Degrade in a fixed order. When something is short, dashboards and analytics go first, DM sending slows next, and checkout is never shed by choice.
7. Same code, new boundaries. The services are the monolith's modules deployed separately; the module rules in the first tab (own tables, call only services, events after commit) are what make the split mechanical.

## 2. Capacity at target scale

At 500,000 creators the system handles about 200 comment webhooks per second on average and 4,000 at peak, writes 5 billion DM-log rows a year, and serves 14 TB of file downloads a day. The first two numbers are why the data tier changes; the third is the largest line in the bill.

**Assumptions (same model as the launch tab, 100x the creators, flatter peaks)**

| Input | Launch | Target | Basis for the change |
| --- | --- | --- | --- |
| Creators | 5,000 | 500,000 | The ask: millions of followers served |
| Weekly active creators | 2,000 (40%) | 200,000 (40%) | Unchanged ratio |
| Campaigns per active creator per week | 2 | 2 | Unchanged |
| Keyword comments per campaign | 300 | 300 | Unchanged average; the distribution gets a longer tail (viral posts) |
| Monthly unique customers | 60,000 | 20,000,000 | Followers who open a store at least once |
| Peak factor | 30x | 20x | Global spread flattens the evening peak |

**Derived load**

| Quantity | Per day | Average per second | Peak per second | Growth vs launch |
| --- | --- | --- | --- | --- |
| Comment and DM webhooks | 17 M | 200 | 4,000 | 100x |
| Auto-DMs sent | 14 M | 160 | 3,200 | 100x |
| Tracking-link redirects | 5.5 M | 64 | 1,300 | 100x |
| Storefront page views | 16.5 M | 190 | 3,800 | 100x |
| Checkouts started | 400 k | 4.6 | 92 | 100x |
| Orders paid | 275 k (8.3 M per month) | 3.2 | 64 | 165x |
| Payment webhooks | 825 k | 10 | 200 | 165x |
| File downloads | 275 k, 14 TB | 3.2 | 64 | 165x |
| SMS sent | 50 k | 0.6 | 12 | 100x |

**Storage growth per year**

| Data | Rows per year | Raw size | Store | Retention |
| --- | --- | --- | --- | --- |
| dm\_logs | 5 B | 1.5 TB | Wide-column store with TTL (DynamoDB or ScyllaDB) | 180 days hot; daily aggregates kept forever in ClickHouse |
| tracking\_links | 2 B | 0.5 TB | Wide-column store + edge KV for the redirect | 180 days after last click |
| webhook\_events (raw payloads) | 6.2 B | 6 TB | Kafka (7-day retention) + object storage archive | 30 days queryable, 1 year archived |
| page\_views and click events | 8 B | 1.6 TB raw, 0.2 TB compressed | ClickHouse | 2 years |
| orders, payments, fulfillments | 100 M | 50 GB | PostgreSQL (sharded by creator) | 7 years |
| creators, products, prices, rules, pages | 10 M | 5 GB | PostgreSQL per service | Life of the account |
| Product files | 2.5 M files | 125 TB | Object storage, multi-region | While the product exists |

**The hot-creator case.** The averages hide the real design problem: one post can draw 50,000 comments in an hour (14 per second) while Meta allows that account only a few hundred outbound messages per hour. The system must accept the burst instantly, queue it durably, drain it at Meta's pace over hours, show the creator a truthful estimate, and let the other 199,999 active creators continue at full speed. Sections 6 and 7 are built around this case.

**What this rules out.** A single PostgreSQL primary for everything (5 billion inserts a year on one table), synchronous DM sending in the request path, a single worker pool shared by all creators, and per-request database reads for storefront pages.

## 3. Target architecture

The monolith's modules become sixteen independently deployed services in two families: request services that answer HTTP and scale on traffic, and stream processors that consume Kafka and scale on lag. Kafka sits between them; every event is keyed by creator\_id. Five data stores each hold the data they are good at.

[diagram: target architecture · 8 request services, 8 stream processors, 5 data stores, 3 external systems]
**How to read it.** Browser traffic enters through a global CDN that answers storefront pages and tracking-link redirects itself from an edge key-value store; only cache misses and writes reach the API gateway. Request services write their own tables and publish events through an outbox; processors consume those events and write results back. Webhooks from Meta and the payment provider land on the ingest service, which validates the signature, writes the raw event to Kafka and returns 200 within 100 ms.

**What is new compared with the launch design**

| Element | Launch | Target | Reason |
| --- | --- | --- | --- |
| Process boundaries | 1 API + 1 worker deployment | 16 services, each with its own deployment, scaling rule and on-call page | Independent scaling and failure isolation |
| Queue | PostgreSQL tables with SKIP LOCKED | Kafka topics, partitioned by creator\_id | Throughput, replay, ordering per creator, consumer groups |
| High-volume stores | Partitioned PostgreSQL tables | DynamoDB (or ScyllaDB) for dm logs and links; ClickHouse for events and analytics | Billions of rows a year with TTL and cheap scans |
| Storefront serving | API reads Postgres via a 60 s cache | Edge KV holds the rendered page JSON per creator; invalidated on publish | Sub-50 ms worldwide and zero origin load for reads |
| Tracking-link redirect | API endpoint | Edge function reading edge KV; click event to Kafka | 1,300 redirects per second without touching the origin |
| Rate limiting | Redis in one region | Edge rate limits per IP plus Redis cluster per region for per-creator and per-account limits | Stops floods before they reach services |
| Database | One PostgreSQL | One PostgreSQL cluster per service that needs it; order and catalog sharded by creator\_id | No shared schema between services; write capacity grows by adding shards |
| Regions | One | Two or more active regions; each creator's shard has one writer region | Latency and availability |

**What is deliberately not in the picture.** A service mesh, a schema registry, a workflow engine and a dedicated search cluster are all optional at this scale; sections 6 and 12 say which of them earn their place and when.

## 4. Service catalogue

Sixteen services, each owning its data, its scaling rule and its SLO. A service never reads another service's database; it calls the other service's API or consumes its events. The code for each is the corresponding monolith module, so the Java structure (entity, repository, service, controller) carries over unchanged.

**Request services (HTTP, stateless, horizontal pod autoscaling on requests per second and CPU)**

| Service | Owns | Store | Publishes | Consumes | Min to max pods | SLO (p95, availability) |
| --- | --- | --- | --- | --- | --- | --- |
| identity | creators, admin\_users, refresh\_tokens, phone\_verification\_tokens | PostgreSQL (one cluster, not sharded: 500k rows) + Redis for lockouts and OAuth state | creator.created, creator.status-changed, notify.sms | — | 3 to 30 | 150 ms, 99.95% |
| catalog | products, prices, product\_files, booking\_sessions, booking\_slots, store\_pages | PostgreSQL sharded by creator\_id; Redis cache of active price and page JSON | product.changed, page.published | creator.status-changed (unpublish) | 3 to 30 | 150 ms, 99.9% |
| automation | social\_accounts, automation\_rules; Instagram connect | PostgreSQL (not sharded); rules cached in Redis per account | rule.changed, account.connected | creator.status-changed, subscription.past-due (deactivate rules) | 2 to 20 | 200 ms, 99.9% |
| storefront | public reads: page JSON, product detail, tracking-link resolve; renders the page JSON that the edge caches | Reads catalog cache and edge KV; no tables of its own except page\_views (to Kafka) | click, page-view | page.published (push JSON to edge KV) | 5 to 100 | 100 ms at origin, 99.95% |
| order | orders, fulfillments; checkout start | PostgreSQL sharded by creator\_id | order.created, order.paid, order.failed, order.refunded | payment.succeeded, payment.failed, refund.completed (as the order-events processor) | 5 to 60 | 300 ms, 99.95% |
| payment | payments, refunds, disputes, payout\_accounts, payout\_batches, creator\_payouts, creator\_subscriptions; provider adapters | PostgreSQL, one cluster per currency region, PCI-scoped network | payment.succeeded, payment.failed, refund.completed, dispute.opened, subscription.changed | order.created (create session), payout.requested | 3 to 30 | 500 ms, 99.95% |
| ingest | webhook endpoints for Meta and payment providers; signature check; raw event to Kafka | None (Kafka is the store); Redis for replay protection | comment.received, message.received, payment.event | — | 5 to 200 | 100 ms, 99.99% |
| admin | admin console APIs; support tickets, audit\_logs, system\_errors; creator search | PostgreSQL (not sharded); OpenSearch optional for creator search beyond 1 M | admin.action | everything, for dashboards (reads ClickHouse) | 2 to 10 | 500 ms, 99.5% |

**Stream processors (Kafka consumers, scaled by KEDA on consumer-group lag)**

| Processor | Consumes | Does | Writes | Publishes | Parallelism |
| --- | --- | --- | --- | --- | --- |
| matcher | comment.received, message.received | Load rules for the account (Redis), match keyword, dedupe per (rule, customer, 24 h), create tracking link | customers (Postgres, not sharded), tracking\_links + dm\_logs (DynamoDB), edge KV (link) | dm.command | 1 consumer per partition; 256 partitions |
| dm-sender | dm.command | Per-account token bucket (Redis), call Meta, record result | dm\_logs status (DynamoDB) | dm.sent, dm.failed | 1 per partition; hot lane (section 7) |
| order-events | payment.event, refund.completed | Apply provider events to orders and payments under the order row lock | orders, payments (Postgres) | order.paid, order.failed | 1 per partition; 64 partitions |
| delivery | order.paid | Create fulfillment, sign download URL or reserve booking slot (calls catalog), write outbox email | fulfillments (Postgres) | fulfillment.done, notify.email | 1 per partition |
| notifier | notify.sms, notify.email | Send through provider adapters with idempotency key, record delivery | outbox\_messages (Postgres) | notification.result | 1 per partition; bulkhead per provider |
| analytics | every topic | Append to ClickHouse; roll up hourly stats per creator | ClickHouse | — | 1 per partition, batched inserts |
| payouts | payout.requested, scheduled weekly | Compute net per creator from ClickHouse-verified sums against Postgres truth, create creator\_payouts, call provider | payment tables | payout.sent, payout.failed | Serial per currency |
| cleanup | scheduled | TTL enforcement where the store has none, partition drops, orphan files, expired tokens | all | — | 1 |

**Shared platform components**

| Component | Role | Scaling |
| --- | --- | --- |
| API gateway | TLS from CDN, JWT validation (public key cached), per-route rate limits, routing by path prefix to services | Managed or Envoy, horizontal |
| Kafka | Event backbone; 3 brokers per region minimum; replication factor 3; topics in section 6 | Add brokers; partitions sized ahead |
| Redis cluster | Per-region: rate limits, locks, caches, OAuth state, idempotency keys | Cluster mode, resharding online |
| Edge KV | Store page JSON per slug; tracking link token to target; \~100 ms global propagation | Managed by the CDN provider |
| Secrets manager, config service | Per-service secrets; feature flags for gradual rollout | Managed |
| Schema registry | Avro or JSON schemas for every topic; backward-compatible evolution enforced in CI | Managed |

## 5. Data partitioning

Every row, event and cache entry carries a `creator_id`, and that single key decides which shard, partition and region it lives in. The money tables stay in PostgreSQL with transactions; the billions of log-shaped rows move to stores built for them.

**Where each kind of data lives and why**

| Data | Store | Partition or shard key | Consistency | Why this store |
| --- | --- | --- | --- | --- |
| creators, admins, sessions, verification tokens | PostgreSQL, one cluster | none (500 k rows) | Strong | Small, relational, login needs uniqueness |
| products, prices, files, bookings, store pages | PostgreSQL, 8 shards | hash(creator\_id) | Strong within a creator | A creator's catalogue is always read together; a shard holds about 60 k creators |
| orders, payments, fulfillments, refunds | PostgreSQL, 16 shards | hash(creator\_id) | Strong within an order; the order row is the lock | Money; 100 M rows a year is fine across 16 shards |
| payouts, subscriptions, disputes | PostgreSQL, one cluster per currency region | creator\_id | Strong | Low volume, regulated, needs reporting |
| social\_accounts, rules | PostgreSQL, one cluster | none (1 M rows) | Strong; rules cached in Redis with 30 s TTL | Read on every event, so the cache carries the load |
| customers | PostgreSQL, 8 shards | hash(platform\_user\_id) | Strong | 20 M rows; looked up by platform id, not by creator |
| dm\_logs | DynamoDB (or ScyllaDB) | partition key creator\_id, sort key timestamp#event\_id | Eventual (single-row atomic) | 5 B rows a year, TTL 180 days, per-creator time-range queries |
| tracking\_links | DynamoDB + edge KV copy | partition key token | Eventual | Point lookup by token at the edge; 2 B rows a year |
| webhook\_events (raw) | Kafka 7 days, then S3 archive (Parquet by day) | creator\_id (Kafka key) | n/a | Replayable; cheap to keep |
| page\_views, clicks, dm events (analytics) | ClickHouse, 3-node cluster | ordered by (creator\_id, event\_time), partitioned by month | Eventual, minutes | Column store: 10x compression, fast aggregates per creator |
| daily\_stats (dashboard numbers) | ClickHouse materialised views + Redis cache | creator\_id | Eventual, 1 h | Dashboards never scan raw data |
| product files | Object storage, multi-region bucket | key prefix creator\_id/ | Strong after PUT | Durable, served by CDN |

**How sharding works in practice**

1. A shard directory (`creator_id` range to shard, 1,024 virtual buckets mapped to physical shards) lives in the identity service and is cached in every service; moving a bucket between physical shards is a background copy plus a directory switch, with dual writes for the minutes in between.
2. Services use a routing datasource: every repository call is scoped by creator\_id, which picks the connection pool; a query without a creator\_id is a compile-time error (ArchUnit test) except in admin, which fans out and merges.
3. Admin and reporting queries never run on shards. They run on ClickHouse (events) or on a replica of each shard through a read-only federated view, with a 1-minute staleness that the admin UI shows.
4. Cross-shard operations are avoided by design: an order and its payment share the creator's shard; a customer buying from two creators is two independent orders. The only cross-shard write is a creator's status change, which is an event every shard-owning service consumes.

**What stays strongly consistent, and how**

- Within one creator: catalogue edits, order and payment state, booking capacity. All in one PostgreSQL shard, with row locks (order row, booking slot row).
- Payment provider events for one order: serialised by Kafka partition (keyed by creator\_id) and by the order row lock, so out-of-order delivery cannot mark a refunded order paid.
- Uniqueness of email and username: a single identity cluster, so the unique constraints still work. If identity ever needs sharding, uniqueness moves to a dedicated lookup table keyed by the normalised value.

**What becomes eventually consistent, and the user-visible rule**

- A product edit reaches the edge-cached store page within 5 seconds (invalidate on publish, propagate at the edge).
- DM counts and clicks on the dashboard are up to 60 seconds behind; the page shows the time of the last update.
- A tracking-link redirect works within 1 second of the DM being sent, because the link is written to edge KV before the DM is sent, never after.

**Hot partitions.** A viral creator makes one Kafka partition and one DynamoDB partition hot. Kafka: the key is creator\_id, so one creator maps to one partition; the hot lane in section 7 moves that creator's commands to a dedicated topic. DynamoDB: the partition key creator\_id is suffixed with a 4-way write shard (`creator_id#0..3`) for dm\_logs, and reads fan out across the 4; adaptive capacity absorbs the rest.

## 6. Event backbone

Kafka carries every fact that crosses a service boundary. Each topic is keyed by `creator_id`, so all events for one creator are in order on one partition; partitions are sized for the peak in section 2 with 4x headroom, because adding partitions later re-maps keys.

**Topics**

| Topic | Producer | Key | Partitions | Retention | Consumers | Peak msgs/s |
| --- | --- | --- | --- | --- | --- | --- |
| comment.received, message.received | ingest | creator\_id | 256 | 7 days | matcher, analytics | 4,000 |
| dm.command | matcher | creator\_id | 256 | 3 days | dm-sender, analytics | 3,200 |
| dm.command.hot | matcher (when a creator is flagged hot) | creator\_id | 32 | 3 days | dm-sender hot lane | bursty |
| dm.result | dm-sender | creator\_id | 64 | 7 days | analytics, automation (stats) | 3,200 |
| click, page.view | storefront, edge function | creator\_id | 128 | 3 days | analytics | 5,000 |
| order.created, order.paid, order.failed, order.refunded | order (outbox) | creator\_id | 64 | 30 days | payment, delivery, analytics, notifier | 100 |
| payment.event (raw provider webhook) | ingest | creator\_id (resolved from the provider metadata) | 64 | 30 days | order-events | 200 |
| payment.succeeded, payment.failed, refund.completed, dispute.opened | payment (outbox) | creator\_id | 64 | 30 days | order-events, analytics, notifier | 200 |
| creator.status-changed, subscription.changed, page.published, product.changed, rule.changed | identity, payment, catalog, automation (outbox) | creator\_id | 32 | 30 days | catalog, automation, storefront, admin | 10 |
| notify.sms, notify.email | any service (outbox) | creator\_id or customer id | 64 | 7 days | notifier | 100 |
| dead-letter.\* | every consumer | original key | 16 | 90 days | admin (replay tool) | rare |

**Producing: the transactional outbox.** A service never calls Kafka inside a request. It writes the business row and an `outbox` row in one PostgreSQL transaction; a relay (Debezium reading the WAL, or a polling publisher per service) publishes outbox rows to Kafka and marks them sent. This gives at-least-once delivery with no lost events and no dual-write bug. Ingest is the one exception: it has no database, so it produces straight to Kafka with `acks=all` and returns 200 only after the broker acknowledges.

**Consuming: idempotent, ordered per key, replayable**

- Every event has an `event_id` (UUID) and a `creator_id`. Consumers record processed event ids in their own store (a `processed_events` table or a DynamoDB item with TTL) and skip repeats, so redelivery is harmless.
- One consumer thread per partition keeps per-creator order. A consumer that must call out (dm-sender, notifier) commits offsets only after the call's result is stored.
- Poison messages: after 3 processing failures the event goes to `dead-letter.<topic>` with the error; the partition keeps moving. Admin can inspect and replay.
- Replay: any consumer group can be reset to an offset or timestamp to rebuild a projection (for example, re-populate ClickHouse) without touching producers.

**Schemas.** Every topic has a versioned schema in the registry (Avro or JSON Schema). Rules: fields are only added, never removed or retyped; consumers ignore unknown fields; a breaking change is a new topic name with a `.v2` suffix and a migration period where both are produced.

**Exactly-once effects without exactly-once delivery.** The platform promises at-least-once delivery and idempotent effects: Meta sends are keyed by dm\_log id, provider calls by our row id, database writes by event id. That combination makes duplicates invisible to users at a fraction of the cost of Kafka transactions, which are therefore not used.

**Sizing.** At 4,000 events per second peak and about 1 KB per event, the backbone carries 4 MB/s in and roughly 12 MB/s out (three consumers per topic): a 3-broker cluster per region with 2 TB of storage covers 7-day retention with headroom. Partition counts above are 4x the peak-per-second divided by a conservative 50 events per second per consumer thread.

## 7. The comment-to-DM pipeline at scale

The pipeline accepts any burst at the ingest tier, buffers it on Kafka, matches it per creator in order, and sends at Meta's pace with a separate lane for any creator whose queue grows past 500. Dashed boxes are Kafka topics; the accented box is the only call that leaves the system.

[diagram: comment-to-DM pipeline · ingest, topic, matcher, hot-or-normal lane, sender, Meta]
**Why each stage is shaped this way**

1. Ingest does nothing but verify and produce. It has no database, so it scales to hundreds of pods in seconds and its p99 stays under 100 ms during a burst; Meta stops retrying as soon as it sees 200.
2. The topic is the buffer. A burst of 50,000 comments becomes 50,000 messages on one partition in seconds; nobody downstream has to keep up in real time, and nothing is dropped.
3. The matcher is cheap and ordered. Rules are read from Redis (30-second TTL, invalidated on rule change), the dedupe check is a conditional write in DynamoDB (`PutItem` with `attribute_not_exists` on `rule#customer#day`), and the tracking link is written to the edge KV before the DM command is produced, so a follower who taps the link a second later is never too early.
4. The hot decision uses a per-creator counter in Redis (commands produced minus results received). Above 500 the matcher routes that creator's commands to `dm.command.hot` and raises a flag with a 1-hour TTL; below 50 the flag clears. Switching lanes keeps per-creator order because the matcher is the only producer and it switches at a message boundary.
5. The senders enforce Meta's limits per account with a Redis token bucket sized from the account's observed allowance; a rate-limit response pauses that account for the returned retry-after without counting as an attempt. The normal lane scales on lag with KEDA; the hot lane has fixed pods, because its lag is expected and must not trigger scaling.
6. The result is an event. `dm.result` updates the DynamoDB status, feeds ClickHouse counts, and drives the creator's dashboard line "4,120 sent, 31,000 queued, about 6 hours remaining".

**Numbers for the viral case**

| Quantity | Value |
| --- | --- |
| Comments on the viral post in the first hour | 50,000 |
| Time for ingest to accept them | under 1 minute (14 per second average, 200 per second peak) |
| Time for the matcher to process them | under 5 minutes on one partition at 200 per second |
| Meta allowance for one account (assumed, to be confirmed in the LLD) | a few hundred private replies per hour |
| Time to drain | several hours, shown as an ETA; the creator can pause or cancel the rest |
| Effect on other creators | none: separate topic, separate consumer pods, separate buckets |
| Rows written | 50,000 dm\_logs, 50,000 tracking links, 1 edge-KV write each |

**Failure behaviour.** If Redis is unavailable the matcher falls back to loading rules from PostgreSQL and the hot flag defaults to off; if DynamoDB is throttled the matcher pauses its partition (lag grows, KEDA adds consumers, which does not help a throttled table, so an alert fires at 2 minutes of lag); if Meta is down the senders stop consuming and the lag alarm pages; nothing is lost in any of these cases because Kafka keeps the events for 7 days.

## 8. Checkout and payment as a saga

With order, payment, catalog and delivery in separate services there is no single transaction around a purchase, so the purchase is a saga: a sequence of local transactions joined by events, each with a compensating action, and a reconciliation loop that repairs anything left half-done.

**The happy path**

| Step | Service | Local transaction | Event out | What the customer sees |
| --- | --- | --- | --- | --- |
| 1 | storefront → order | Validate product and active price (catalog API, cached), create order PENDING with amount snapshot and 1-hour expiry; reserve the booking slot if any (catalog API, row lock, 15-minute hold) | order.created | Spinner |
| 2 | payment | Consume order.created; create a provider checkout session with idempotency key = order id; store payment PENDING with session id | payment.session-created | Redirect to the provider page (the order service long-polls this event for up to 5 s, then shows retry) |
| 3 | provider | Customer pays | webhook → ingest → payment.event | Provider success page → redirect to our order page |
| 4 | order-events (in order) | Consume payment.event under the order row lock: payment SUCCEEDED, order PAID, platform fee computed | order.paid | Order page shows "paid, preparing delivery" |
| 5 | delivery | Consume order.paid; create fulfillment; sign download URL or confirm the slot; write notify.email | fulfillment.done | Download button or booking confirmation |
| 6 | notifier | Send the email | notification.result | Email with the link |

**Compensations (what undoes a step when a later step fails)**

| Failure | Detected by | Compensation |
| --- | --- | --- |
| Provider session creation fails or times out (step 2) | payment, after 1 retry | Order marked FAILED, slot hold released (catalog API), customer offered retry; nothing was charged |
| Customer abandons the provider page | Order expiry job (PENDING older than 1 h) | Order EXPIRED, slot released, session cancelled at the provider if the API allows |
| Payment succeeded but order.paid never consumed (delivery down) | Lag alert on delivery; reconciliation | Nothing to undo; delivery resumes from the topic; customer page keeps showing "preparing" with support contact after 10 min |
| Slot capacity lost between hold and PAID (hold expired because payment took over 15 min) | delivery, when confirming the slot | Automatic refund (refund.requested with reason SLOT\_UNAVAILABLE), email with alternative slots |
| Payment webhook arrives but the order is already EXPIRED (slow customer) | order-events | Order revived to PAID if the product is still ACTIVE and (for bookings) the slot is free; otherwise automatic refund |
| Refund requested after delivery | payment | Refund at the provider; order REFUNDED; download URL revoked (signed URL key rotated for that order); slot released |
| Dispute opened | payment | Order DISPUTED; download revoked; evidence pack (order, delivery log, IP, email) prepared for the admin |

**Reconciliation loop.** Every 15 minutes the payment service lists orders PENDING older than 10 minutes and asks the provider for each session's status; every night it compares the provider's settlement report with payments SUCCEEDED for the day. Any difference creates a system\_error row and, for money differences, a FINANCE ticket. This loop is what makes the saga safe against lost webhooks, lost events and bugs.

**Idempotency and ordering.** The order row is the serialisation point: every state change takes `SELECT ... FOR UPDATE` on it, checks the current state against the allowed transition table, and ignores events that no longer apply (a second payment.succeeded for a PAID order is a no-op with a log line). Provider event ids are stored, so a replayed webhook is ignored. State transitions: PENDING → PAID | FAILED | EXPIRED; EXPIRED → PAID (late payment); PAID → PARTIALLY\_REFUNDED | REFUNDED | DISPUTED; DISPUTED → PAID (won) | REFUNDED (lost). Any other transition is rejected.

**Why not a workflow engine.** Temporal or Step Functions would model this saga well; at six steps and one compensation table the event-driven version is simpler to run and debug, and the reconciliation loop covers the failure cases an engine would. Revisit if a third saga appears (for example multi-item carts).

## 9. Autoscaling

Each tier scales on the one signal that measures its own backlog: requests per second for request services, consumer lag for processors, connections and CPU for databases, nothing for the edge (it is already global). Scaling up is fast and aggressive; scaling down is slow and conservative, because a late scale-down costs money while a late scale-up costs sales.

**Scaling rules per tier**

| Tier | Platform | Signal and target | Min / max | Scale-up | Scale-down | Hard limit to respect |
| --- | --- | --- | --- | --- | --- | --- |
| Request services | Kubernetes HPA | requests per second per pod (target 60% of load-tested capacity) and CPU 60% | per service table in section 4 | +50% or +4 pods per minute, whichever is larger | -10% every 5 minutes after 10 minutes below target | Database connections: pods x pool size must stay under 80% of the shard's limit; PgBouncer makes this a soft limit |
| ingest | Kubernetes HPA | requests per second and p99 latency (target 100 ms) | 5 / 200 | +100% per minute | -10% every 5 minutes | Kafka producer throughput; 200 pods x 50 rps = 10,000 rps, 2.5x the peak |
| Stream processors | KEDA on Kafka consumer-group lag | lag per partition (target 1,000 messages) and lag age (target 30 s) | 2 / partitions of the topic | +4 pods per 30 s | -1 pod every 5 minutes at lag 0 | Max useful pods = partition count; beyond that, repartition |
| dm-sender hot lane | fixed | — | 4 / 4 | — | — | Its lag is expected; alert only on lag age over 24 h |
| Kubernetes nodes | Cluster autoscaler or Karpenter | pending pods | 6 / 300 nodes per region | new node in about 90 s | consolidate every 10 minutes | Keep 20% headroom of warm capacity so pod scale-up does not wait for nodes |
| PostgreSQL shards | Managed vertical scaling plus read-replica autoscaling | CPU 70%, connections 80%, replica lag 5 s | 1 primary + 1 to 5 replicas per shard | Add replica in 10 minutes; vertical resize in a maintenance window | Remove replica after 1 hour idle | Write capacity scales only by adding shards (a planned operation, section 5) |
| DynamoDB | On-demand capacity | none (pay per request) | — | instant, with partition warm-up for sudden 2x | — | Hot partition throughput: 1,000 WCU per partition key, which is why dm\_logs keys are suffixed |
| ClickHouse | Manual nodes; inserts batched by the analytics consumer | insert rate, query p95 | 3 / 9 nodes | planned | planned | Batch inserts of 10,000 rows; never one row at a time |
| Redis cluster | Managed resharding | memory 70%, CPU 60% | 3 / 24 shards | planned, online | planned | Keys hashed by creator\_id so a hot creator stays on one shard; rate-limit keys are tiny |
| Kafka | Add brokers; partitions fixed | disk 70%, network 60%, under-replicated partitions 0 | 3 / 12 brokers per region | planned | planned | Partition count is the parallelism ceiling for consumers; set 4x peak from day one |
| CDN and edge KV | Provider | — | — | — | — | Edge KV write rate (roughly 1 write per second per key); fine for page publishes and link writes |

**Predictive scaling.** Traffic has a daily shape (evenings in India and the US) and a weekly one. A scheduled pre-scale raises the minimum pods of storefront, order, ingest and matcher 30 minutes before each known peak and lowers it after, so the HPA starts from a warm floor instead of chasing the ramp. The schedule is recomputed weekly from ClickHouse traffic data per region.

**Scaling that is deliberately absent**

- No scaling on queue depth for the hot lane: depth is the design, not a problem.
- No scaling of payment and payouts beyond 30 pods: provider rate limits bind first; extra pods would only queue at the provider.
- No automatic shard splitting for PostgreSQL: splitting moves data and is run by a person, with the dual-write procedure in section 5, when a shard passes 60% of its write capacity for a week.

**Back-pressure, the other half of autoscaling.** When a downstream tier cannot grow (Meta limits, a throttled DynamoDB table, a saturated shard), upstream must slow rather than pile up: processors pause their partitions when their writes are throttled, the matcher stops producing hot-lane commands for a creator past 100,000 queued (the creator is told the campaign is capped), and the gateway sheds non-essential traffic (dashboards, analytics reads) first when request services hit their maximum.

**Load tests that validate the rules.** Before launch and after each major change: a 20x ramp over 5 minutes on ingest with a single hot creator at 200 comments per second; a checkout storm of 100 orders per second for 10 minutes; a Meta outage of 30 minutes followed by recovery; a shard failover during the checkout storm. Pass criteria are the SLOs in section 4 plus zero lost events and zero duplicate charges.

## 10. Caching and the edge

The two highest-volume reads, the storefront page and the tracking-link redirect, never reach the origin on the hot path; they are answered at the CDN edge from a key-value store that the services write to. Every other cache has one owner, one TTL and one invalidation event.

| What | Where | Key | Written by | TTL | Invalidated by | Miss path |
| --- | --- | --- | --- | --- | --- | --- |
| Rendered store page JSON | Edge KV + CDN cache | slug | storefront on page.published and product.changed (debounced 2 s) | 24 h | Overwrite on publish; delete on unpublish or creator suspension | storefront service renders from catalog cache, writes KV, returns |
| Tracking link target | Edge KV | token | matcher, before the DM is produced | 180 days | Expiry; order attribution reads DynamoDB, not KV | Edge function returns 404 page "link expired" and the creator's store link |
| Static assets (JS, CSS, images) | CDN | content-hashed URL | CI deploy | 1 year | Never (new hash per build) | Origin bucket |
| Product files | CDN in front of object storage | storage key + signature | delivery signs per order | 24 h signature, 5 downloads | Key rotation on refund or dispute | Object storage |
| Active price per product | Redis per region | product id | catalog on price change (write-through) | 5 min | Delete on change | catalog shard |
| Rules per Instagram account | Redis per region | account id | automation on rule.changed (write-through) | 30 s | Delete on change | automation PostgreSQL |
| Per-account DM token bucket | Redis per region | account id | dm-sender | n/a (counter) | n/a | Start full |
| Hot-creator flag and queue counter | Redis per region | creator id | matcher, dm-sender | 1 h | Counter below 50 | Default off |
| Login lockout and OAuth state | Redis per region | creator id, state | identity | 15 min, 10 min | Expiry | Lockout falls back to the identity database counters |
| Idempotency keys for checkout and refunds | Redis per region | header value | order, payment | 24 h | Expiry | Order unique key catches the rest |
| Dashboard numbers | Redis per region | creator id + day | analytics roll-up | 60 s | Overwrite | ClickHouse query |
| JWT verification key | In-process per pod | key id | Secrets manager at startup, refreshed hourly | 1 h | Key rotation publishes a config event | Secrets manager |

**Rules**

- Cache aside only for reads that tolerate staleness; write-through for anything a creator just changed and expects to see (prices, rules), so the dashboard never shows the old value after save.
- Every cache key includes the creator id or a derivative, so a hot creator's keys stay on one Redis shard and a cache flush for one creator is a prefix delete.
- Edge KV is written by the service that owns the data, never by a consumer of a cached copy, so there is exactly one writer per key.
- Stampede protection on page render: a per-slug lock in Redis (1 s) so one miss renders while the rest wait; the rendered page is then served to all.
- Nothing personal is cached at the edge: the store page JSON contains the creator's public catalogue only; the customer's order page is always served by the origin with a no-store header.

## 11. Multi-region

Reads are served from every region; each creator's writes go to one home region. Two regions at launch of the distributed design (Mumbai for Indian creators, Virginia for the rest), a third when Europe passes 10% of creators or a data-residency requirement appears.

**Placement rules**

| Thing | Placement | Why |
| --- | --- | --- |
| Store pages, link redirects, static files, product downloads | Every CDN edge worldwide | Followers are everywhere; this is 95% of requests |
| A creator's home region | Chosen at signup from country; movable by a background migration | Writes to one PostgreSQL shard primary avoid cross-region conflicts |
| PostgreSQL shards | Primary in the creator's home region; an async replica in the other region | Reads for dashboards local; failover target ready |
| Kafka | One cluster per region; topics mirrored to the other region (MirrorMaker 2) for analytics and failover | Consumers run in the home region of the key; mirrored copy is for replay elsewhere |
| DynamoDB | Global table (multi-region replication, last-writer-wins) | dm\_logs and links are single-writer per creator in practice, so conflicts do not arise |
| Redis | Per region, not replicated | Everything in it is reconstructible or region-local (rate limits are per region by design) |
| ClickHouse | One cluster in the analytics region, fed from both Kafka clusters | Analytics tolerate minutes of lag |
| Object storage | Multi-region bucket with replication | Durability; CDN picks the nearest copy |
| identity, admin, automation (unsharded PostgreSQL) | Primary in the main region, replica in the other; writes cross regions when needed (login is a write: last\_login\_at, counters) | Low write volume; a 100 ms cross-region write on login is acceptable; move to per-region identity shards only if login p95 breaches |

**Request routing.** The gateway reads the creator's home region from a signed claim in the JWT (set at login) or from the shard directory for public requests (slug → creator → region) and routes write requests to that region; read requests are served locally from the replica when staleness under 5 s is acceptable (dashboards) and routed home when it is not (checkout, order status).

**Webhooks.** Meta and payment providers are given one global URL; the CDN routes it to the nearest healthy ingest. Ingest resolves the creator's home region from the event (account id or order metadata) and produces to that region's Kafka cluster, or to the local cluster with a region tag when the home cluster is unreachable; the mirror carries it home.

**Failover (a region is lost)**

1. Health checks fail; DNS and CDN stop routing to the region within 60 s.
2. For each shard whose primary was in the lost region, the replica in the surviving region is promoted (automated runbook; about 2 minutes; up to 5 s of writes may be lost, the RPO).
3. The shard directory is updated; services pick it up within 30 s; the creators of that region now write to the surviving region.
4. Kafka consumers for the mirrored topics start in the surviving region from the mirrored offsets; at most the mirror lag (seconds) is reprocessed, which idempotent consumers absorb.
5. Edge-served pages and links keep working throughout, because the edge KV is global.

**Fail-back** is a planned operation: re-establish replication in the original direction, then move home regions back shard by shard during low traffic.

**Data residency.** A creator whose home region is Mumbai has their catalogue, orders and payment records stored there; the cross-region replica exists for availability and can be disabled per shard for jurisdictions that forbid it, at the cost of a longer RTO for that shard. Analytics in ClickHouse hold event data without personal fields (hashed customer ids, no emails).

**What multi-region costs in complexity.** Cross-region writes for unsharded services, replica promotion runbooks, mirrored topics and a global DynamoDB table are the price; it is paid once, at the move to two regions, and is the reason the launch design is single-region.

## 12. Resilience in a distributed system

Sixteen services and five stores give many more ways to fail than one monolith; the design makes each failure local, bounded in time, and invisible to checkout. The tools are the usual ones, applied with fixed numbers rather than defaults.

**Every service-to-service call**

| Control | Setting | Reason |
| --- | --- | --- |
| Connection timeout | 500 ms | A service that cannot accept a connection in 500 ms is overloaded; wait no longer |
| Request timeout | 2 s for reads, 5 s for writes, 10 s for provider calls | Bounded by the caller's own SLO; a 150 ms SLO cannot afford a 5 s dependency |
| Retries | 1 retry on connection failure or 503, none on timeout, never on non-idempotent writes without an idempotency key | Retrying timeouts doubles load on a struggling service |
| Circuit breaker | Per target service: open at 50% failures over 20 calls, half-open after 10 s | Fail fast and recover automatically |
| Bulkhead | Separate connection pool and thread pool per dependency (catalog calls cannot exhaust the pool used for payment calls) | One slow dependency cannot take the whole service down |
| Fallback | Defined per call: cached value, degraded response, or a clear error; never a silent null | The caller knows what it got |
| Deadline propagation | The gateway stamps a deadline header; every hop subtracts its own time and refuses work that cannot finish in time | Stops wasted work deep in the call chain |

**Every consumer**

- Pause the partition, not the pod, when a dependency is throttled; resume on a timer.
- Commit offsets only after the effect is durably stored.
- Dead-letter after 3 failures with the exception attached; alert when a dead-letter topic grows faster than 10 per minute.
- Startup catch-up: a consumer that was down for an hour processes the backlog at full speed, bounded only by downstream rate limits.

**Degradation order under pressure**

1. Analytics consumer paused (ClickHouse falls behind; dashboards show "as of").
2. Creator dashboard reads served from stale cache; writes still accepted.
3. DM sending slowed (buckets shrink to 50%); queue grows; ETAs lengthen.
4. Admin console read-only.
5. Storefront pages served from edge cache even if stale; new publishes delayed.
6. Checkout: never degraded by choice; if the order shard is down, the page says so and the customer can retry; nothing is charged.

**Blast-radius limits.** A creator is confined to one shard, one partition, one Redis slot and one hot-lane flag; a service is confined to its own pools, breakers and pods; a region is confined by the failover plan in section 11; a bad deploy is confined by canary rollout (5% of pods for 10 minutes, automatic rollback on error-rate or latency regression).

**Chaos tests (run monthly in staging, quarterly in production during low traffic).** Kill a random pod of each service; partition Redis from the matcher; throttle DynamoDB to 10%; drop a Kafka broker; fail over one PostgreSQL shard; black-hole Meta for 15 minutes; inject 2 s latency into the catalog service. Each test has a written expected outcome from this section and a pass/fail record.

**Game-day scenarios the team rehearses.** A viral creator; a payment-provider outage during an evening peak; a region loss; a bad migration on one shard; a leaked Instagram app secret (rotate, re-verify webhooks, invalidate tokens). Each has a runbook and an owner.

## 13. Observability at scale

One trace follows a comment from the Meta webhook through Kafka to the DM and, hours later, to the order it produced; one dashboard per service shows its SLO and the error budget left this month. Without both, a distributed system of this size cannot be operated.

**Tracing across services and topics**

- OpenTelemetry in every service; trace context is carried in HTTP headers between services and in Kafka message headers between producers and consumers, so a consumer continues the producer's trace.
- The business ids (creator\_id, order\_id, dm\_log id, event\_id) are span attributes, so a support engineer can search by order id and see every hop.
- Sampling: 100% for errors, for anything slower than its SLO, and for all payment and order spans; 5% for the rest. At 4,000 events per second peak this is about 300 sampled traces per second, which the tracing backend handles.
- Long-lived correlation: the tracking-link token carries the originating event id, so a purchase hours after the DM links back to the comment.

**SLOs and error budgets**

| Journey | SLI | SLO | Error budget per 30 days | Burn-rate alert |
| --- | --- | --- | --- | --- |
| Open a store page | p95 time to first byte at the edge; success rate | 100 ms; 99.95% | 21.6 minutes of failure | 2% of budget in 1 hour pages; 5% in 6 hours notifies |
| Checkout start to provider redirect | p95 latency; success rate | 1 s; 99.9% | 43 minutes | same |
| Payment to delivery | p95 time from provider webhook to fulfillment done | 60 s; 99.9% | 43 minutes | same |
| Comment to DM (normal lane) | p95 time from webhook to Meta accept | 60 s; 99% | 7.2 hours | 10% of budget in 1 hour |
| Creator dashboard | p95 latency; freshness | 500 ms; data under 2 minutes old | 3.6 hours | notify only |
| Login | p95 latency; success rate for valid credentials | 300 ms; 99.9% | 43 minutes | pages |

When a service exhausts its error budget, feature work on it stops until reliability work restores it; this is the rule that keeps autoscaling and resilience honest.

**Metrics that matter at this scale (per service, per region, per shard)**

- Request services: RPS, p50/p95/p99, error rate by code, pod count vs. HPA target, pool wait time per dependency, breaker state per dependency.
- Processors: lag per partition and lag age, processing time per message, dead-letter rate, pause count, pod count vs. partitions.
- Kafka: under-replicated partitions, request latency, disk, mirror lag.
- Data: shard CPU, connections, replica lag, slow queries (over 100 ms logged with the plan), DynamoDB throttles per table, ClickHouse insert batch size and merge backlog, Redis memory and evictions, edge KV write errors.
- Business: DMs sent per minute, checkout funnel per region, orders in PENDING over 10 minutes, hot creators active, reconciliation differences.

**Logs.** Structured, with trace id and the business ids; sampled at 10% for successful high-volume paths (ingest, matcher, storefront) and 100% elsewhere; 14 days hot, 1 year in cold storage; PII fields redacted at the logger.

**Cost of observability.** Budgeted at 5 to 8% of infrastructure cost; above that, sampling rates and retention are the first levers.

**On-call.** One rotation per service family (request services, processors, data platform) with a shared escalation to the payment owner for money incidents. Every page links to the runbook and to the dashboard for the SLO that fired.

## 14. Cost model at target scale

The distributed design costs roughly $40k to $90k a month in infrastructure at the section 2 scale, about $60k at the mid estimate, and two lines dominate: file egress through the CDN and the fleet of PostgreSQL clusters. Against 500,000 creators on a $29 plan, infrastructure is well under 1% of revenue; the figures are order-of-magnitude estimates to be replaced with a cloud calculator run before the migration.

[diagram: estimated in chat from the section 2 capacity figures · 10 components · monthly USD, list prices, approximate]
**What moves the big lines**

- Egress is proportional to file size times downloads. Levers: compress and cap product files, serve video courses as embedded unlisted streams rather than downloads, negotiate committed-use CDN pricing (typically 40 to 60% below list at this volume), and limit downloads per order (already 5).
- PostgreSQL cost is proportional to the number of clusters, not the data. The 28 clusters are the shard counts in section 5 at full headroom; starting with 4 catalog and 8 order shards and splitting on the section 9 trigger roughly halves this line for the first years.
- Compute follows the autoscaling rules; processors run on spot or preemptible nodes (they are idempotent and resumable), which cuts their share by about 60%.

**What is cheap and should stay that way.** Kafka, DynamoDB, Redis and ClickHouse together are under 20% at this scale; the design does not need to economise on them. Observability is budgeted at 5 to 8%; it is the first line to review if it grows past that.

**Cost per unit of business.** At the mid estimate: about $0.12 per creator per month, $0.007 per order, and $0.0001 per DM. The DM figure matters because it bounds what a viral creator can cost the platform: a 1,000,000-comment campaign costs about $100 to process.

## 15. Migration path from the launch monolith

The monolith is not thrown away; it is pulled apart along the module seams, one service at a time, each step reversible and gated by a measured trigger. The order follows risk and load: the burstiest, least money-coupled module leaves first; the money modules leave last, together.

| Step | What moves out | Trigger to start | How | Exit criterion | Rollback |
| --- | --- | --- | --- | --- | --- |
| 0 | Nothing yet: add Kafka, the outbox relay and ClickHouse beside the monolith; every module starts publishing its events; analytics reads ClickHouse | Launch plus first 1,000 creators | Outbox tables in the monolith, Debezium or polling relay, analytics consumer | All dashboards served from ClickHouse; events flowing for 30 days with zero gaps in reconciliation | Switch dashboards back to SQL views |
| 1 | ingest: the webhook endpoints | Webhook p95 over 300 ms during bursts, or 50 per second sustained | New service with the same URL behind the CDN; produces to Kafka; the monolith's matcher consumes the topic instead of the table | 2 weeks with zero webhook 5xx and lag under 30 s | CDN routes the URL back to the monolith |
| 2 | matcher and dm-sender: the automation processors | DM queue age over 5 minutes at peak, twice in a week | Deploy the automation module's consumers as their own pods (same jar, consumer profile); dm\_logs and tracking\_links written to DynamoDB with dual writes for 7 days; edge KV for links | Hot-lane test passes (section 9 load test); monolith worker DM code disabled for 30 days | Re-enable the monolith worker; stop the consumers |
| 3 | storefront: public reads to the edge | Store p95 over 300 ms or origin reads over 500 per second | storefront service renders page JSON to edge KV; CDN serves pages and /l redirects from the edge | 95% of store requests answered at the edge for 30 days | CDN bypass to origin |
| 4 | catalog and automation (rules, connect) APIs | Monolith deploys taking over 30 minutes, or two teams | Services with their own databases; the monolith calls them over HTTP behind the same gateway path; data copied by table with a cut-over per table | Monolith no longer holds those tables | Point the gateway back; tables still present for 30 days |
| 5 | identity | After step 4 | Same pattern; JWT issuing moves; the public key is shared, so tokens keep working across the cut-over | All logins served by the new service for 14 days | Gateway route back |
| 6 | order and payment, together | Order writes over 2,000 per second on the monolith database, or PCI scope isolation required | Shard the order database first (still inside the monolith) with the routing datasource; then extract order and payment as two services that keep the saga in section 8; delivery and notifier as processors | Reconciliation shows zero differences for 30 days; chaos test of shard failover passes | Services can run the same code as the monolith modules; routing back is a gateway change, data stays sharded |
| 7 | Second region | Creators in a second geography pass 20%, or an availability requirement above 99.95% | Section 11 placement; shard home-region assignment; mirrored topics | Failover drill meets RPO 5 s, RTO 5 min | Single-region routing |

**What makes each step safe**

- Same code: a service is a monolith module with its own main class and its own database URL; business rules do not change during extraction, only wiring.
- Strangler routing: the gateway sends a path to the new service or the monolith by configuration, so each step is a flag flip with the old path still deployed.
- Dual writes with verification: when a table moves, both stores are written for a period and a nightly job compares them; the old store is dropped only after a clean month.
- Events first: because every module already publishes events from step 0, consumers can be extracted without touching producers.
- ArchUnit tests in the monolith enforce the boundaries that make steps 4 to 6 mechanical; keep them green from the first release.

**What is not migrated.** The admin module stays a single service for the life of the product; support, audit and errors are low-volume and benefit from one place to look.

## 16. Open decisions before implementation

Seven choices shape the code and must be settled before the implementation discussion; each has a recommendation so the discussion can start from a position rather than a blank.

| # | Decision | Options | Recommendation | Why |
| --- | --- | --- | --- | --- |
| 1 | Cloud and managed services | AWS (EKS, MSK, DynamoDB, Aurora, CloudFront + KV), GCP (GKE, Pub/Sub or Kafka, Bigtable, Cloud SQL), self-managed Kubernetes on any cloud | AWS: every store named in this design exists as a managed service with the needed scaling knobs; India region available | Fewer things to run; the design maps one-to-one |
| 2 | Wide-column store | DynamoDB vs ScyllaDB | DynamoDB on AWS; ScyllaDB if cloud-neutral is a requirement | On-demand capacity, TTL and global tables are exactly the three features used |
| 3 | Outbox relay | Debezium (WAL-based) vs polling publisher per service | Polling publisher at first (same jar, simple), Debezium when any outbox table passes 500 rows per second | Debezium needs a connector cluster to operate |
| 4 | Edge platform | Cloudflare Workers + KV vs CloudFront Functions + a regional KV | Cloudflare for the edge logic and KV, in front of whichever cloud runs the services | Mature edge KV and functions; global rate limiting built in |
| 5 | Kafka vs Pub/Sub vs SQS | Kafka (ordering per key, replay, consumer groups), SQS FIFO (simpler, limited throughput per group), Pub/Sub (ordering keys, no replay beyond retention) | Kafka (managed) | Per-creator ordering and replay are load-bearing in sections 6 and 7 |
| 6 | Service runtime | Spring Boot on JVM as now vs GraalVM native images for request services | JVM with CDS and tuned startup; native only for ingest if scale-up time under 10 s is needed | One toolchain; ingest is the one service where cold start matters |
| 7 | Sharding mechanism | Application-level routing datasource (as designed) vs Citus or Aurora Limitless | Application-level for order and catalog; revisit Aurora Limitless when it is proven at this write volume | Keeps the service in control of the key and the migration procedure |

**Settled by this design and not reopened in implementation**

- creator\_id as the universal partition key.
- Money in PostgreSQL with row locks; no distributed transactions.
- At-least-once delivery with idempotent effects; no Kafka transactions.
- Edge-served storefront and link redirects.
- Hot lane for viral creators.
- Strangler migration in the section 15 order.

**What the implementation discussion should produce, in order**

1. The service template: one Spring Boot module layout for request services and one for processors (Kafka consumer, idempotency store, dead-letter, metrics) that every service copies.
2. The outbox relay and event schema conventions, with the schema registry CI check.
3. The routing datasource and shard directory client.
4. The local developer environment: Docker Compose with Kafka, PostgreSQL, Redis, DynamoDB Local, ClickHouse, and the monolith running in "all modules" mode, so one laptop runs the whole system.
5. The load-test harness for the section 9 scenarios, run in CI nightly against staging.
6. Then the services, in the section 15 order, each with its LLD checklist from the first tab.
