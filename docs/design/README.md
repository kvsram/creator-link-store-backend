# Design documents

These three files are the design of Creator Store. They are the source of truth: a change to the
design is a pull request that edits the file, reviewed like code. A readable copy with the diagrams
lives in the Claude Doc "Creator Store — High-Level Design"
(https://claude.ai/code/artifact/ef02df90-0f59-4d16-bbf8-fa7f3bef448c) and is updated to match these
files, not the other way round.

| File | What it answers | When to read it |
| --- | --- | --- |
| `01-launch-design.md` | What v1 is: actors, requirements, capacity, components, data model, API, flows, integrations, infrastructure, security, observability | Before any story; the story's section is named in the delivery plan |
| `02-distributed-design.md` | How the same system is re-shaped for millions of users: services, partitioning, Kafka backbone, sagas, autoscaling, multi-region, cost, migration path | Only after launch, when section 15 triggers fire; never during the 18-week plan |
| `03-delivery-plan.md` | How the work is cut: first two weeks, what to keep from the old repos, access and CI/CD, roadmap, every story with its ID and dependencies, long-lead items, gates, definition of done, weekly rhythm, risks | Every session: pick the story, check its definition of done |

Story IDs (`E0.1`, `E3.4`, ...) are stable. Branches, pull requests and `HANDOFF.md` refer to them.

Diagrams did not survive the export and appear as `[diagram: ...]` placeholders; the Claude Doc has them.
