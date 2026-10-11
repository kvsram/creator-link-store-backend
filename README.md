# Creator Link Store — backend

Spring Boot modular monolith for the Creator Link Store platform: creators sell digital products,
courses and bookings from a link-in-bio page; Instagram keyword auto-DMs send followers a personalised link.

**Start with [HANDOFF.md](HANDOFF.md).** It holds the module rules, what is implemented, and what to build next.
The design this code follows is in [`docs/design/`](docs/design/) (launch design, distributed design, delivery plan).

## Quick start

Requirements: Docker Desktop, Java 21, Maven 3.9.

```bash
make up        # PostgreSQL 16 + Redis 7 + the API (built from this checkout) with a demo creator and admin
make smoke     # signup -> login -> profile -> refresh -> logout -> admin login against localhost:8080
make logs      # follow the API log; SMS verification links are printed there
make down      # stop (keeps the database volume); `make clean` removes it
```

Demo accounts (local only): creator `demo` / `demo-password-123`, admin `admin@example.test` /
`local-admin-password-123`. `make` alone lists every target.

To run the API from source (or from the IDE) against the containers: `make db`, then `make run`.
The `local` Spring profile turns on the demo seed (`DevSeed`) and DEBUG logging; it is never set in the cloud.
Health: `GET /actuator/health` returns `{"status":"UP"}` and nothing else.

## Verify

```bash
make verify    # = mvn verify
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
