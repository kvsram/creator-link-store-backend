## Story

`E?.?` — <one line from docs/design/03-delivery-plan.md section 5>

Design sections this follows: <e.g. 01-launch-design.md §7 data model, §8 API>

## What changed, commit by commit

<!-- One short paragraph per commit, in order. For each new or changed file: what it does and why it exists.
     The reviewer is learning the codebase; write for that reader. -->

1. `<commit subject>` — ...
2. `<commit subject>` — ...

## Decisions and alternatives

<!-- Where two reasonable ways existed, which was chosen and why, in one or two lines each. -->

## Cloud cost

<!-- Required if this PR creates, changes or deploys anything in AWS. Otherwise write "none". -->
Expected monthly cost: none / ₹… (US$…) — <resources and assumptions>

## Definition of done (docs/design/03-delivery-plan.md section 8)

- [ ] Migration applied and every new constraint exercised by a test
- [ ] Entity with intention-revealing methods; state transitions as a table in the code
- [ ] Service method with transaction boundary; owner check wherever a creator id is involved
- [ ] Controller with validated DTOs; error codes added to the shared list
- [ ] Unit tests for rules; Testcontainers test for repository and service; one end-to-end test per flow
- [ ] `ArchitectureTest` still green; no new cross-module repository access
- [ ] `mvn verify` green locally (or CI if Docker was unavailable — say which)
- [ ] `HANDOFF.md` section 2b (story status) and section 2 (module table, if a module changed) updated
- [ ] `docs/design/` updated if the design changed
- [ ] No secrets, keys or passwords anywhere in the diff

## Not verified here

<!-- Anything the author could not run (a provider sandbox, a Meta test account, AWS). The owner runs that step. -->
