# Creator Link Store — backend

Spring Boot modular monolith for the Creator Link Store platform: creators sell digital products,
courses and bookings from a link-in-bio page; Instagram keyword auto-DMs send followers a personalised link.

**Start with [HANDOFF.md](HANDOFF.md).** It holds the module rules, what is implemented, and what to build next.
The design this code follows is the "Creator Store — High-Level Design" document (launch design, distributed
design and delivery plan).

## Run locally

Requirements: Java 21, Maven 3.9, Docker.

```bash
docker compose up -d db
export JWT_SECRET='any-random-string-of-at-least-32-characters'
export ADMIN_BOOTSTRAP_EMAIL=admin@example.test
export ADMIN_BOOTSTRAP_PASSWORD='local-admin-password-123'
mvn spring-boot:run                 # http://localhost:8080
```

Or everything in containers: `docker compose up --build`.

Flyway creates the schema on first start. SMS messages are printed to the log (`SMS_PROVIDER=log`);
the phone-verification link is in that log line.

## Verify

```bash
mvn verify
```

Runs the unit tests, the architecture rules (`ArchitectureTest`, the module boundaries from HANDOFF.md),
and `AuthFlowIntegrationTest`, which boots the application against PostgreSQL 16 in Docker and walks
signup → SMS link → confirm → login → profile → refresh rotation → reuse detection → logout → admin login
→ lockout over HTTP. The same command is the pull-request gate in GitHub Actions.

## Layout

```
src/main/java/com/creatorstore/
  common/ config/ security/        shared: errors, properties, JWT, security filter chain
  creator/ auth/ verification/     built: accounts, sessions, phone verification
  notification/ admin/             built: SMS/email adapter, admin login and bootstrap
  product/ booking/ storefront/ automation/ customer/ order/ payment/ support/
                                   planned; each package-info.java states what it will own
src/main/resources/db/migration/   Flyway migrations (V1 = auth schema)
docs/planned-schema.sql            agreed tables for the planned modules (verified on PostgreSQL 16)
docs/legacy/                       the earlier prototype's schema and README, kept as reference input
```

## Delivery

Pull requests run the quality gate. Commits on `main` build an immutable image
`ghcr.io/kvsram/creator-link-store-backend:sha-<commit>`. Deployment to AWS (ECS Fargate, one region)
is the next story in the delivery plan and lives in the infrastructure repository.
