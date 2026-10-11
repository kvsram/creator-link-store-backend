# Delivery plan

Oct 11, 2026 · @Sriram

> Source of truth. This file is the design any session (Claude Code on the Mac, a claude.ai chat, a human) reads and edits by pull request. A readable copy with the diagrams lives in the Claude Doc (https://claude.ai/code/artifact/ef02df90-0f59-4d16-bbf8-fa7f3bef448c); when this file changes, the Doc is updated to match, not the other way round. Diagrams are shown here as `[diagram: ...]` placeholders.

This tab turns the launch design (tab 1) into work: what to do first, what to keep from the existing repos, how code and infrastructure get to AWS without anyone handling credentials, and the full breakdown of epics, stories and gates to a general-availability launch in about 18 weeks for one engineer working with an AI pair. The scale track (tab 2) starts only after launch, when the metrics in tab 2 section 15 trigger it.

## 1. Where we start: the first two weeks

The first step is not a feature. It is making the three repositories match the design, getting a green pipeline that deploys the auth module to a dev environment in AWS, and starting the external approvals that take weeks. Everything after that is adding modules to a machine that already builds, tests and deploys itself.

**Week 1**

- Day 1: decide the three open items at the end of this section; they gate everything else

- Day 1: start the long-lead items in section 6 (Meta developer app and business verification, Razorpay account and KYC, domain, SMS and email sender setup); they run in the background for weeks

- Day 1 to 2: grant push access to the three repos and connect the AWS account the way section 3 describes (one IAM role trusted by GitHub Actions; no keys to anyone)

- Day 2 to 3: replace the backend repo contents with the modular monolith from this project (the `creatorstore` project already written: auth, creator, verification, admin modules, V1 schema); keep the old `schema.sql` and `docs/api-contract.md` as reference input, not as code

- Day 3: `mvn verify` green locally and in GitHub Actions; the two unit test classes plus a Testcontainers test that boots the app against real PostgreSQL and runs the signup → verify → login → refresh flow

- Day 4 to 5: simplify the infrastructure repo to one region and the v1 compute choice (section 3): Terraform for VPC, RDS PostgreSQL, Redis, ECR, Secrets Manager, the compute service, the load balancer and the GitHub OIDC role; `terraform plan` clean, `apply` into the dev environment

**Week 2**

- Day 6 to 7: deploy pipeline: push to main builds an immutable image, deploys to dev automatically, runs the smoke test (signup, verify, login) against dev; staging and prod promotions are manual approvals on GitHub Environments

- Day 7: Flyway runs on startup against RDS; the bootstrap SUPER_ADMIN is created from Secrets Manager values; `/api/admin/me` works with a real token on dev

- Day 8 to 9: frontend restart on a real structure (Vite + React + router + auth state + API client with token refresh); the creator app has signup, phone verification and login screens working against dev

- Day 9: local developer environment: one `docker compose up` brings PostgreSQL, Redis and the app with seed data; `make smoke` passes

- Day 10: ArchUnit test that enforces the module rules from tab 1 section 6; HANDOFF.md updated; gate G0 review (section 7)

**Three decisions to make on day 1**

| Decision                   | Options                                                                                | Recommendation                                                                                                                                                                                  |
|----------------------------|----------------------------------------------------------------------------------------|-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| Launch region and currency | Mumbai (ap-south-1) with INR and Razorpay, or Virginia (us-east-1) with USD and Stripe | Mumbai + INR + Razorpay, as the existing repos already assume; Stripe behind the same PaymentProvider interface for later                                                                       |
| v1 compute                 | ECS Fargate, or the private EKS already modelled in the infrastructure repo            | ECS Fargate: no cluster to patch, no self-hosted runner needed for deploys, autoscaling on CPU and request count out of the box; the design does not depend on Kubernetes until the scale track |
| Repository layout          | Keep three repos, or merge into one monorepo                                           | Keep three (frontend, backend, infrastructure); the pipelines and docs already assume it and the backend is one deployable until the scale track                                                |

## 2. What to keep from the existing repos

I read all three `kvsram` repositories at their current heads (18 Aug 2026). The infrastructure repo is the most valuable and is kept with simplifications; the backend is a two-file prototype whose schema and API-contract documents are useful input but whose code is replaced; the frontend is a 110-line shell and is restarted. Nothing has been applied to AWS by these repos, so there is nothing to tear down.

| Repo                              | What is there                                                                                                                                                                                                                                                                                                                                   | Verdict                                                                                                                | Keep                                                                                                                                                                                                                                         | Replace                                                                                                                                                                                      |
|-----------------------------------|-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|------------------------------------------------------------------------------------------------------------------------|----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| creator-link-store-backend        | 2 Java files (about 500 lines), raw JdbcTemplate SQL, 26-table `schema.sql`, demo seed, Razorpay and Stripe webhook endpoints, Instagram webhook and test-send endpoints, GHCR image build, deploy workflow for private EKS. No authentication: the creator id is a request parameter defaulting to 1 (the repo's own parity doc marks this P0) | Replace the code with the modular monolith; it already covers auth, creator, verification and admin with the V1 schema | `schema.sql` as a checklist of entities the design should not forget (payment plans, checkout fields, reviews, tags, leads, funnels); the Razorpay verify and webhook request shapes; `docs/api-contract.md`; the container workflow pattern | All Java code; `schema.sql` (Flyway migrations replace it); `application.yml`; the EKS deploy workflow (section 3)                                                                           |
| creator-link-store-frontend       | Vite + React, one 109-line `main.jsx` with a sidebar, section fetches by name, hard-coded `CREATOR_ID=1`, nginx container, one unit test                                                                                                                                                                                                        | Restart on a real structure                                                                                            | The nav model (Home, My Store, Income, Analytics, Customers, Settings) as the screen list; `format.js`; Dockerfile and nginx config                                                                                                          | `main.jsx`; add React Router, an auth store with token refresh, a typed API client, per-module page folders, component tests                                                                 |
| creator-link-store-infrastructure | Terraform modules for VPC, EKS (3 regions), GitHub OIDC role, ECR with immutable tags and scan-on-push, CloudWatch observability; Kustomize bases; GitHub Environments dev/preprod/prod with manual promotion by 40-char SHA; docs: AI handoff, feature parity, runbook, OIDC, India integrations; local bootstrap and smoke scripts            | Keep and simplify to one region and v1 compute                                                                         | `github-oidc` module; ECR; the environment and promotion model; `docs/production-runbook.md`, `docs/india-launch-integrations.md`, `docs/github-oidc.md`; `scripts/smoke-test.sh` and `doctor.sh`; `workspace-manifest.json`                 | Three-region layout (one region until the scale track); private EKS plus self-hosted runner (ECS Fargate for v1); add RDS, ElastiCache, Secrets Manager, ALB and the Fargate service modules |

**Why replace rather than refactor the backend.** The prototype has no module boundaries, no entities, no transactions, no authentication and no migrations; refactoring it into the design would touch every line. The monolith written in this project already compiles the same ideas into the structure the design needs, with the auth flow tested against PostgreSQL. The prototype's real contribution is the entity list and the webhook contracts, both of which carry over as input to the product, payment and automation LLDs.

**What the existing docs got right and the plan keeps.** Immutable SHA-addressed images, manual promotion through protected environments, OIDC instead of stored keys, secrets only in a secrets manager, integrations disabled by default with explicit `disabled`, `test`, `live` modes, and an honest parity matrix. These are the right habits and they are carried into section 3.

## 3. How we work: access and the CI/CD pipeline

Nobody, human or AI, holds AWS keys. Code reaches AWS only through GitHub Actions, which assumes one IAM role by OIDC; infrastructure changes are Terraform in the infrastructure repo applied by that same pipeline after a plan is reviewed. The AI pair gets push access to the repositories and read access to the AWS console through your own browser session when something must be inspected.

**Access model**

| Who or what                 | Needs                                                                                                                                     | How it is granted                                                                                                                                                              | Never                                         |
|-----------------------------|-------------------------------------------------------------------------------------------------------------------------------------------|--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|-----------------------------------------------|
| AI pair (this assistant)    | Read and push on the three repos; open pull requests; run CI                                                                              | Attach each repo to the session with push access (the GitHub app installation on `kvsram`); all changes land as pull requests you merge                                        | AWS keys, provider keys, production secrets   |
| GitHub Actions              | Deploy to AWS; apply Terraform                                                                                                            | One IAM role per environment (dev, staging, prod) trusted by the GitHub OIDC provider, scoped to the repo and the environment name; the `github-oidc` module already does this | Long-lived access keys in GitHub secrets      |
| You, in the AWS console     | Create the account baseline once (OIDC provider, Terraform state bucket, budget alarm), approve production promotions, look at dashboards | Your own sign-in with MFA; a temporary admin role for the bootstrap, then a read-only role for daily use                                                                       | Running Terraform by hand after the bootstrap |
| AI pair, in the AWS console | Inspect what exists, read logs or dashboards while debugging                                                                              | Through your browser while you are signed in (the browser extension), read-only actions, each one visible to you                                                               | Changing anything by hand; anything financial |
| Applications at runtime     | Database password, JWT secret, provider keys, token encryption key                                                                        | Secrets Manager, injected as environment variables by the task definition; rotated by a documented runbook                                                                     | Secrets in images, repos or logs              |

**One-time bootstrap you do yourself (about an hour).** Create the Terraform state bucket and lock table, the GitHub OIDC identity provider, and the three deploy roles (the `terraform/bootstrap` folder in the infrastructure repo does exactly this; it is the one `terraform apply` run from your laptop with your own credentials). Set a monthly budget alarm. After that, everything else is a pull request.

**Pipelines**

| Pipeline                   | Trigger                                  | Steps                                                                                                                                                         | Gate                                                                       | Output                 |
|----------------------------|------------------------------------------|---------------------------------------------------------------------------------------------------------------------------------------------------------------|----------------------------------------------------------------------------|------------------------|
| Backend quality            | every pull request                       | `mvn verify`: compile, unit tests, Testcontainers integration tests against PostgreSQL 16, ArchUnit module rules, OWASP dependency check, Spotless formatting | required check on the PR                                                   | pass or fail           |
| Backend image              | push to main                             | build the jar, build the image, Trivy scan, push to ECR as `sha-<40-char commit>` (immutable)                                                                 | scan has no critical findings                                              | image                  |
| Backend deploy dev         | after a successful image on main         | Flyway migrations run on task start; new task definition revision; ECS rolling update; smoke test against dev (signup, verify, login, refresh, admin login)   | smoke test passes, else automatic rollback to the previous task definition | dev updated            |
| Backend promote staging    | manual, `workflow_dispatch` with the SHA | same deploy to staging; full end-to-end suite (section 8)                                                                                                     | GitHub Environment approval                                                | staging updated        |
| Backend promote prod       | manual, SHA plus change note             | canary: 10% of tasks for 10 minutes with error-rate and latency checks, then 100%; automatic rollback on regression                                           | GitHub Environment approval by you                                         | prod updated           |
| Frontend quality and image | PR and main                              | lint, unit tests, Playwright component tests, build, image to ECR                                                                                             | required check                                                             | image                  |
| Frontend deploy            | same promotion model                     | static build to S3 behind CloudFront (no container needed once the app is static), cache invalidation                                                         | same approvals                                                             | site updated           |
| Infrastructure plan        | every PR in the infrastructure repo      | `terraform fmt`, `validate`, `tflint`, `plan` per environment posted as a PR comment                                                                          | plan reviewed                                                              | plan                   |
| Infrastructure apply       | merge to main                            | `apply` to dev automatically; staging and prod by manual approval with the saved plan                                                                         | GitHub Environment approval                                                | infrastructure changed |
| Nightly                    | schedule                                 | dependency updates PR (Renovate), load-test smoke on staging, backup restore check, cost report                                                               | —                                                                          | reports                |

**Rules the pipelines enforce**

- Migrations are backward compatible with the previous release (expand then contract), so a rollback never needs a database rollback.

- The same image SHA is promoted dev → staging → prod; production is never rebuilt.

- Every deploy writes a deployment marker to CloudWatch so a latency change can be matched to a release.

- `main` is protected: PR required, quality check required, no force push; the AI pair's PRs are reviewed by you like anyone else's.

**What this means for the EKS workflow in the current repos.** The existing deploy workflow needs a self-hosted runner inside the VPC to reach a private EKS API. With ECS Fargate, GitHub-hosted runners deploy through the AWS API, which removes that runner and the EKS operational load. The Kustomize files are kept in a `k8s/` folder for the scale track, when Kubernetes earns its place.

## 4. Roadmap

Eighteen weeks from the day-1 decisions to general availability, with the backend and frontend moving module by module, the infrastructure work front-loaded and then returning for production readiness, and the external approvals running from week 0 because they are the long pole.

[diagram: delivery roadmap · 6 workstreams and 7 gates over 18 weeks · estimates for one engineer with an AI pair]
Weeks are working weeks for one engineer with an AI pair doing most of the typing. The gates are reviews you hold; their exit criteria are in section 7. The two accented bars are the Meta developer app and its App Review, which can take four to eight weeks and cannot be accelerated by engineering effort; if it slips, automation ships behind a feature flag and everything else launches on time.

## 5. Work breakdown

Ten epics, in dependency order. Each story is sized in days for one engineer with the AI pair; a story is done when it meets section 8. Story IDs are stable so they can become GitHub issues as they are; the epic order is the build order from tab 1 section 17.

**E0 Foundation (weeks 0 to 2)**

| ID   | Story                                                                                                                                      | Days | Depends on |
|------|--------------------------------------------------------------------------------------------------------------------------------------------|------|------------|
| E0.1 | Replace backend repo contents with the modular monolith; `mvn verify` green locally                                                        | 1    | —          |
| E0.2 | Backend quality workflow: unit + Testcontainers + ArchUnit + dependency check on every PR                                                  | 1    | E0.1       |
| E0.3 | AWS bootstrap: state bucket, OIDC provider, deploy roles, budget alarm (you, from your laptop)                                             | 0.5  | —          |
| E0.4 | Terraform dev environment: VPC, RDS PostgreSQL 16, ElastiCache Redis, ECR, Secrets Manager, ALB, ECS Fargate service, CloudWatch log group | 3    | E0.3       |
| E0.5 | Backend image and deploy-dev workflow with smoke test and automatic rollback                                                               | 1.5  | E0.2, E0.4 |
| E0.6 | Frontend restart: Vite + React + Router + auth store with refresh + API client; signup, verify, login screens                              | 2    | E0.5       |
| E0.7 | Frontend build to S3 + CloudFront with deploy workflow                                                                                     | 1    | E0.4       |
| E0.8 | Local environment: docker compose (PostgreSQL, Redis, app), seed, `make smoke`                                                             | 1    | E0.1       |
| E0.9 | Staging environment from the same Terraform, promotion workflows with approvals                                                            | 1    | E0.4       |

**E1 Catalog (weeks 2 to 5)**

| ID   | Story                                                                                                                                    | Days | Depends on   |
|------|------------------------------------------------------------------------------------------------------------------------------------------|------|--------------|
| E1.1 | V2 migration: products, prices, product_files; entity, repository, service, controller; owner checks                                     | 2    | E0           |
| E1.2 | Pre-signed S3 upload URL and upload completion; file size and type limits; private bucket Terraform                                      | 1.5  | E1.1         |
| E1.3 | Booking: V3 migration, sessions, slot generation, reserve/release under row lock                                                         | 2    | E1.1         |
| E1.4 | Store page settings: V4 migration, slug, theme, publish gate (ACTIVE, phone verified, 1+ active product)                                 | 1    | E1.1         |
| E1.5 | Frontend: product list, create/edit forms per type, file upload with progress, price editor, booking availability editor, store settings | 4    | E1.1 to E1.4 |
| E1.6 | Admin product review queue (PENDING → APPROVED or REJECTED) for restricted categories                                                    | 1    | E1.1         |

**E2 Storefront (weeks 4 to 6)**

| ID   | Story                                                                                    | Days | Depends on |
|------|------------------------------------------------------------------------------------------|------|------------|
| E2.1 | Public page JSON endpoint with Redis cache (60 s) and 404 rules; product detail endpoint | 1.5  | E1.4       |
| E2.2 | Store app (customer-facing): mobile-first page, product detail, link resolution landing  | 3    | E2.1       |
| E2.3 | CloudFront caching rules for public endpoints; cache invalidation on publish             | 1    | E2.1       |
| E2.4 | page_views to a partitioned table; daily_stats roll-up job; dashboard numbers endpoint   | 1.5  | E2.1       |

**E3 Checkout, orders, payments (weeks 6 to 9)**

| ID   | Story                                                                                                              | Days | Depends on   |
|------|--------------------------------------------------------------------------------------------------------------------|------|--------------|
| E3.1 | V7 and V8 migrations: customers, orders, fulfillments; order state machine with transition table and row lock      | 2    | E1           |
| E3.2 | PaymentProvider interface; Razorpay adapter (order create, signature verify, webhook parse); Stripe adapter stub   | 2    | E3.1         |
| E3.3 | `POST /public/checkout`: validate price, create PENDING order, provider session, idempotency key; order expiry job | 1.5  | E3.2         |
| E3.4 | Webhook endpoint: signature check, store event (unique provider id), 200; worker applies to payments and orders    | 2    | E3.2         |
| E3.5 | Delivery: fulfillment creation, signed download URL (24 h, 5 uses), booking confirmation; outbox email             | 1.5  | E3.4, E1.3   |
| E3.6 | Refunds (creator 7-day window, admin any time) and disputes; download revocation                                   | 1.5  | E3.4         |
| E3.7 | Reconciliation job (PENDING older than 10 min; nightly settlement compare)                                         | 1    | E3.4         |
| E3.8 | Frontend: checkout page, redirect handling, order status page, download page; creator orders list with filters     | 3    | E3.3 to E3.5 |
| E3.9 | End-to-end test in Razorpay test mode through the real webhook (ngrok or a staging URL)                            | 1    | E3.8         |

**E4 Automation (weeks 9 to 12)**

| ID   | Story                                                                                                                   | Days | Depends on                                 |
|------|-------------------------------------------------------------------------------------------------------------------------|------|--------------------------------------------|
| E4.1 | V5 migration; Meta OAuth connect and callback; token encryption (AES-GCM, key in Secrets Manager); webhook subscription | 2    | E0, Meta app approved for development      |
| E4.2 | V6 migration; rules CRUD with validation; rules cache in Redis                                                          | 1.5  | E4.1                                       |
| E4.3 | Meta webhook endpoint: signature, store event, 200; event processor with rule matching and 24 h dedupe                  | 2    | E4.2                                       |
| E4.4 | Tracking links: mint, redirect endpoint with click count, attribution on checkout                                       | 1    | E4.3, E3.3                                 |
| E4.5 | DM sender: per-account token bucket, send private reply, backoff, error classes, dead-letter                            | 2    | E4.3                                       |
| E4.6 | Token refresh job; disconnect; creator emails on expiry                                                                 | 1    | E4.1                                       |
| E4.7 | Frontend: connect Instagram, rules editor with post picker, per-rule stats (sent, clicks, orders), queue status         | 3    | E4.2, E4.5                                 |
| E4.8 | End-to-end test with a test Instagram Professional account and a test post                                              | 1    | E4.7, Meta App Review passed for messaging |

**E5 Notification (week 12)**

| ID   | Story                                                                                               | Days | Depends on |
|------|-----------------------------------------------------------------------------------------------------|------|------------|
| E5.1 | Outbox table and worker; SMS provider adapter (MSG91 or Twilio); email adapter (SES) with templates | 2    | E0         |
| E5.2 | Transactional emails: receipt with download link, booking confirmation, verification, payout notice | 1    | E5.1, E3.5 |

**E6 Admin and support (weeks 12 to 14)**

| ID   | Story                                                                                                      | Days | Depends on |
|------|------------------------------------------------------------------------------------------------------------|------|------------|
| E6.1 | V12 migration; audit log written in the same transaction as every admin action; status change history      | 1.5  | E0         |
| E6.2 | Creator list and detail, suspend and reinstate with rule deactivation and unpublish, admin user management | 2    | E6.1       |
| E6.3 | Support tickets and internal notes; system_errors list with resolve; replay for dead-lettered events       | 2    | E6.1       |
| E6.4 | Admin console frontend                                                                                     | 3    | E6.2, E6.3 |

**E7 Payouts and plans (weeks 13 to 14)**

| ID   | Story                                                                                                                                          | Days | Depends on |
|------|------------------------------------------------------------------------------------------------------------------------------------------------|------|------------|
| E7.1 | V10 migration; payout account onboarding through the provider; weekly payout batch with the unique period key; transfer calls with idempotency | 3    | E3         |
| E7.2 | V11 migration; creator plan checkout, invoice webhooks, PAST_DUE grace enforcement                                                             | 2    | E3.2       |
| E7.3 | Frontend: payout account, payout history, plan page                                                                                            | 1.5  | E7.1, E7.2 |

**E8 Production readiness (weeks 10 to 16, in parallel)**

| ID   | Story                                                                                                                          | Days | Depends on |
|------|--------------------------------------------------------------------------------------------------------------------------------|------|------------|
| E8.1 | Metrics (Micrometer to CloudWatch or Prometheus), the tab 1 section 16 dashboards and alerts, structured logs with request ids | 2    | E0         |
| E8.2 | Tracing with OpenTelemetry across API and worker                                                                               | 1    | E8.1       |
| E8.3 | Security pass: headers at the edge, CORS, dependency scan gate, secrets rotation runbook, admin MFA (TOTP)                     | 2    | E6         |
| E8.4 | Load tests (k6): storefront 60 rps, checkout 2 rps, webhook burst 60 rps; fix what breaks                                      | 2    | E3, E4     |
| E8.5 | Canary deploy for prod; rollback drill; backup restore drill into staging with timing                                          | 1.5  | E0.9       |
| E8.6 | Production environment: Terraform apply, DNS, TLS, WAF rules, rate limits at the edge                                          | 1.5  | E8.3       |
| E8.7 | Runbooks for the tab 1 section 14 failure modes; on-call setup (you, with alert routing)                                       | 1    | E8.1       |
| E8.8 | Legal pages and consent: terms, privacy, refund policy, creator agreement incl. Meta platform rules                            | 1    | —          |

**E9 Beta and GA (weeks 16 to 18)**

| ID   | Story                                                                              | Days    | Depends on |
|------|------------------------------------------------------------------------------------|---------|------------|
| E9.1 | Onboard 10 creators by hand; daily check of errors, DM lag, payment reconciliation | ongoing | E8         |
| E9.2 | Fix list from beta, prioritised by revenue impact                                  | 5       | E9.1       |
| E9.3 | GA checklist (section 7, G6) signed off                                            | 0.5     | E9.2       |

**E10 Scale track (after GA, triggered, not scheduled)**

The steps in tab 2 section 15, each started by its trigger metric: outbox relay to Kafka and ClickHouse first, then ingest, then the automation processors, then the edge-served storefront, then service extraction, then the second region. None of them is on the 18-week plan.

**Totals.** About 95 engineering days across E0 to E9, which is the 18-week plan at roughly five productive days a week with the AI pair writing most of the code and tests; the external approvals, not engineering, decide whether automation ships at GA.

## 6. Long-lead external items to start on day one

Six things outside the codebase take weeks and are on the critical path; each is a form you fill or an account you open, and none can be done by the AI pair. Start all six in week 0.

| Item                                                                                                                     | Why it gates launch                                                                                                   | Typical lead time                                                                              | What to do now                                                                                                                                                                                                             | Owner                               |
|--------------------------------------------------------------------------------------------------------------------------|-----------------------------------------------------------------------------------------------------------------------|------------------------------------------------------------------------------------------------|----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|-------------------------------------|
| Meta developer app, business verification and App Review for `instagram_manage_messages` and `instagram_manage_comments` | Without approved permissions the app can only message accounts that are its own testers; no auto-DM for real creators | Business verification 1 to 2 weeks; App Review 2 to 6 weeks, often with one round of rejection | Create the app in Meta for Developers, add Instagram, complete business verification (documents, domain), prepare the screencast and test account the review asks for; keep a test Instagram Professional account for E4.8 | You                                 |
| Razorpay account, KYC and Route (payouts)                                                                                | Live payments and creator payouts                                                                                     | KYC 3 to 10 working days; Route activation separately                                          | Register the business, submit KYC, enable test mode keys immediately for E3; request Route for payouts                                                                                                                     | You                                 |
| Domain, DNS and TLS                                                                                                      | Storefront URLs, webhook URLs, email sending                                                                          | 1 day, plus propagation                                                                        | Buy the domain, put DNS on the CDN provider, issue certificates through the cloud                                                                                                                                          | You                                 |
| Email sending domain (SES)                                                                                               | Receipts and verification mail land in inboxes only from a verified domain with SPF, DKIM, DMARC                      | 1 to 3 days; SES production access request 1 to 2 days                                         | Verify the domain, request production sending, set DMARC to quarantine                                                                                                                                                     | You                                 |
| SMS sender (MSG91 or Twilio); India DLT registration if sending to Indian numbers                                        | Verification links by SMS                                                                                             | India DLT entity and template registration 1 to 3 weeks; Twilio international 1 day            | Register the entity and the verification template text early; the template text is fixed in the LLD, so settle it now                                                                                                      | You                                 |
| Legal pages and policies                                                                                                 | App Review and payment providers check them; creators and customers must accept them                                  | 1 week with a template, longer with a lawyer                                                   | Terms, privacy policy, refund policy, creator agreement including Meta platform rules and content restrictions                                                                                                             | You, with a lawyer if budget allows |

**The one that decides the launch shape.** If Meta App Review has not passed by week 12, the plan does not stop: automation ships behind a feature flag visible only to tester accounts, the store, checkout and payouts launch on schedule, and the flag flips for everyone when the review passes.

## 7. Milestones and exit criteria

Seven gates, each a short review you hold with the evidence listed; a gate that fails stops the next epic until it passes. The evidence is always something that runs, never a document.

| Gate            | Week | Exit criteria (all required)                                                                                                                                                                                                                                                                                      |
|-----------------|------|-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| G0 Foundation   | 2    | CI green on every PR; push to main deploys dev automatically and the smoke test passes; signup, verify, login and refresh work on dev through the real frontend; `terraform plan` is clean; local `docker compose up` plus `make smoke` works on a fresh clone; ArchUnit boundary test exists and passes          |
| G1 Store live   | 6    | A creator can create a product with a file and a price, publish, and the public page serves it on staging through CloudFront; publish is refused for an unverified phone; admin can reject a product; page p95 under 300 ms in a 60 rps k6 run                                                                    |
| G2 Test payment | 9    | A customer completes a Razorpay test-mode payment on staging; the webhook marks the order PAID; the download link works and expires; a refund flows back; the reconciliation job finds zero differences after 100 test orders; a duplicate webhook is a no-op                                                     |
| G3 First DM     | 12   | A test Instagram account comments the keyword on a test post and receives the DM with a working tracking link within 60 s on staging; a second comment within 24 h sends nothing; the rate-limit path reschedules; the link attributes the order                                                                  |
| G4 Ready review | 14   | Dashboards and alerts live; tracing across API and worker; security checklist done including admin MFA; backup restore drill timed under 1 h; canary deploy and rollback drilled on staging; runbooks for the section 14 failures of tab 1; load tests pass at the tab 1 section 3 targets; legal pages published |
| G5 Beta         | 16   | 10 real creators onboarded; at least 20 real paid orders; no P0 or P1 open; error budget for checkout not exceeded over the beta weeks; payouts run once                                                                                                                                                          |
| G6 GA           | 18   | Beta fix list closed; on-call rotation (even if it is you) with alert routing tested; cost report matches the estimate within 2x; HANDOFF.md current; the scale-track triggers are on a dashboard                                                                                                                 |

## 8. Definition of done and the weekly rhythm

A story is done when it is merged, deployed to dev by the pipeline, and visible in the running system; nothing is done on a laptop. The weekly rhythm is light because there is one engineer: a plan on Monday, a gate or demo on Friday, and the AI pair doing the typing in between.

**Definition of done for a backend story**

- Migration applied and every new constraint exercised by a test

- Entity with intention-revealing methods; state transitions as a table in the code

- Service method with transaction boundary; owner check where a creator id is involved

- Controller with validated DTOs; error codes added to the shared list

- Unit tests for rules; Testcontainers test for repository and service; one end-to-end test per flow

- ArchUnit still green; no new cross-module repository access

- Metrics and log lines for the new path; alert if it is on a money or DM path

- HANDOFF.md section 2 table updated

- PR reviewed and merged by you; deployed to dev by the pipeline; smoke test green

**Definition of done for a frontend story**

- Works on a 375 px phone and a laptop; keyboard reachable; loading, empty and error states present

- Uses the typed API client; no hard-coded ids or creator numbers

- Component test for the form logic; Playwright test for the screen's main path

- Deployed to dev by the pipeline

**Definition of done for an infrastructure story**

- `terraform plan` reviewed in the PR; applied to dev by the pipeline; staging and prod by approval

- Every resource tagged with environment and owner; nothing created by hand

- Runbook line added if the resource can fail in a way an operator must handle

**Weekly rhythm**

| Day               | What                                                                                            | Output                                            |
|-------------------|-------------------------------------------------------------------------------------------------|---------------------------------------------------|
| Monday, 30 min    | Pick the week's stories from section 5 in order; confirm nothing external is blocking           | Stories open as GitHub issues with the week label |
| Daily             | AI pair opens PRs story by story; you review and merge; CI deploys dev                          | Merged PRs, dev updated                           |
| Wednesday, 15 min | Check the long-lead tracker (section 6) and chase                                               | Status notes on the issues                        |
| Friday, 30 min    | Demo on dev or staging; gate review if a gate is due; update the roadmap if a date moved        | Gate verdict; next week's candidates              |
| Monthly           | Cost report against section 14 of tab 2 scaled down; dependency update PR merged; restore drill | Cost note; green nightly                          |

**Working with the AI pair.** Give it one story at a time with the story ID; it reads HANDOFF.md first, writes the migration, code and tests, runs them locally against PostgreSQL, and opens a PR with the checklist above ticked. Anything it cannot verify (a provider sandbox, a Meta test account) it says so in the PR, and you run that step. Decisions that change the design go back to the design tabs first, then into code.

## 9. Risks

The risks that can move the GA date are external approvals and scope creep toward the scale design; the technical risks are bounded by the gates. Each risk has an owner, a signal that it is happening, and a response decided now.

| Risk                                                                                   | Signal                                                         | Response                                                                                                                                                           | Owner                                     |
|----------------------------------------------------------------------------------------|----------------------------------------------------------------|--------------------------------------------------------------------------------------------------------------------------------------------------------------------|-------------------------------------------|
| Meta App Review rejected or slow                                                       | No approval by week 10                                         | Ship automation behind a tester-only flag; launch store and checkout on schedule; resubmit with the reviewer's notes; keep the screencast and test account current | You                                       |
| Razorpay KYC or Route delayed                                                          | No live keys by week 8                                         | Beta in test mode with friendly creators; payouts manual by bank transfer for the first batch, recorded in creator_payouts by hand                                 | You                                       |
| Building the scale design too early                                                    | Any Kafka, DynamoDB or multi-region work appearing in E0 to E9 | Stop; the scale track starts only on tab 2 section 15 triggers, measured on the live system                                                                        | You                                       |
| Scope creep from the Stan parity list (memberships, funnels, email flows, communities) | Stories not in section 5 being started                         | Park them in a v1.1 list; the 18-week scope is the tab 1 scope and nothing else                                                                                    | You                                       |
| One engineer, no backup                                                                | Illness or a week away                                         | Everything is in the repos and HANDOFF.md; the AI pair can continue any story from the issue; no knowledge lives only in a head                                    | You                                       |
| Instagram policy change on private replies or messaging windows                        | Meta changelog; sudden FAILED spikes with policy error codes   | The DM sender classifies policy errors and stops without retrying; the rules UI shows the reason; adapt the template and resubmit                                  | AI pair on detection, you on policy       |
| Payment webhook or reconciliation bug marks orders wrong                               | Reconciliation differences above zero                          | Orders are never marked PAID from a redirect; the nightly compare catches drift; a FINANCE ticket per difference                                                   | AI pair                                   |
| Cost surprise on AWS                                                                   | Budget alarm at 80%                                            | Dev and staging scale to zero outside working hours; RDS sized small until G5; CloudFront only in front of the storefront                                          | You                                       |
| Security incident from a leaked secret                                                 | A secret appears in a log, PR or image scan                    | Rotation runbook per secret; pipelines refuse PRs that match secret patterns; secrets only in Secrets Manager                                                      | AI pair for the scanner, you for rotation |
| The frontend takes longer than estimated                                               | Frontend stories slipping by more than a week by G1            | Reduce the admin console to the suspend, review and ticket screens for GA; defer analytics charts to v1.1                                                          | You                                       |

**What this plan does not try to do.** It does not promise feature parity with Stan Store at GA; it promises a creator can sell a product, a booking or a course from a link, receive payouts, and run keyword auto-DMs once Meta approves the app, on a system that scales by configuration to the first 5,000 creators and by the tab 2 plan beyond that.
