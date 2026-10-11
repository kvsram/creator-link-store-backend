# CLAUDE.md — working agreement for any Claude session in this repo

This file is read automatically by Claude Code on the laptop. A claude.ai chat does not read it
automatically: paste the starter prompt from `HANDOFF.md` section 0 instead. Both roads lead here.

## What this repository is

The backend of Creator Store, a Stan-Store-style creator-commerce platform (Spring Boot 3.5, Java 21,
PostgreSQL 16, Flyway, modular monolith). The owner is Sriram (`kvsram`), who is learning the
codebase as it is built. Two sibling repos: `creator-link-store-frontend` and
`creator-link-store-infrastructure`.

## Where the truth lives (chat context is not a record)

| Question | Read |
| --- | --- |
| What is built, what the rules are, what is next | `HANDOFF.md` |
| Why the system is shaped this way (entities, flows, API, security) | `docs/design/01-launch-design.md` |
| How it scales later (not for the 18-week plan) | `docs/design/02-distributed-design.md` |
| Stories, order, gates, definition of done | `docs/design/03-delivery-plan.md` |
| Status of each story | `HANDOFF.md` section 2b |

Anything decided in a chat and not written into one of these files is lost when the chat fills up.
A design change goes into `docs/design/` **in the same pull request** as the code that needs it.

## How a session runs

1. Read `HANDOFF.md` sections 0 to 2. Read the design section the story points to. Do not re-derive the design.
2. Work on **one story** (an ID from `docs/design/03-delivery-plan.md` section 5, e.g. `E0.5`).
   Branch `feat/<story-id>-<short-name>`, e.g. `feat/E0.5-deploy-dev`.
3. Before writing code, say in a few lines what files will change and why. Wait for a yes if the
   person is present; if they are not, write the plan at the top of the PR description and continue.
4. Commit in **small, readable steps** — one idea per commit, in the order a reader would want to learn
   it (schema → entity → repository → service → controller → tests → docs). The commit message says
   *why*, not only what. Never squash a story into one commit.
5. `mvn -q verify` must be green before every push. If Docker is not available for Testcontainers, say so
   and let GitHub Actions be the judge; never skip or weaken a test to get green.
6. Open a pull request with the template filled in. Update `HANDOFF.md` section 2b (story status) and,
   when a module changes, section 2 (module table) in the same PR.
7. Stop at the PR. Sriram reviews and merges. Do not merge, do not force-push a shared branch, do not
   rewrite history on `main`.

## Learning mode (standing instruction from the owner)

- Explain what each new or changed file does and why it exists, in the PR description, grouped by commit.
- Prefer plain English and short sentences. Name the Spring or JPA concept once, then explain it.
- When there are two reasonable ways to do something, show both in one or two lines and say which was chosen.

## Hard rules

- Module rules in `HANDOFF.md` section 4 are enforced by `ArchitectureTest`. Fix the design, not the test.
- Flyway owns the schema. New table or column = new `V<n>__<name>.sql`. Never edit a migration that
  has run anywhere but a laptop.
- No new Maven dependency without a one-line reason in the PR; prefer what Spring Boot already ships.
- Secrets never enter the repo, a log line, a Docker image or a PR. Local values come from environment
  variables; cloud values from AWS Secrets Manager.
- Nobody holds AWS keys. Deploys go through GitHub Actions with an OIDC role. The owner runs the one-time
  bootstrap himself with his own credentials; a session never asks for, stores or types his passwords or keys.
- **Every time something is built or deployed in the cloud, state the expected monthly cost in the PR and
  in the reply** (INR and USD, list price, assumptions). Prefer the cheapest size that meets the story.
- Scale-track work (Kafka, DynamoDB, multi-region, EKS) is out of scope until the triggers in
  `docs/design/02-distributed-design.md` section 15 are met on the live system.

## Local commands

```bash
docker compose up -d db                    # PostgreSQL 16 on localhost:5432
mvn -q verify                              # unit + ArchUnit + Testcontainers integration test
JWT_SECRET='local-development-secret-at-least-32-characters-long' \
ADMIN_BOOTSTRAP_EMAIL=admin@example.test ADMIN_BOOTSTRAP_PASSWORD='local-admin-password-123' \
mvn spring-boot:run                        # http://localhost:8080
```

Machine-specific settings (JAVA_HOME, PATH) belong in `CLAUDE.local.md`, which is git-ignored.
