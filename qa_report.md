# SDD QA Report — spec `sdd/194c0409-3eae-46c3-86ab-c5d97faa3d8a`

- **Branch under test:** `sdd/194c0409-3eae-46c3-86ab-c5d97faa3d8a`
- **Repo:** `HappyLiang12/aria-conductor`
- **Head commit:** `2a3aa31` "sdd dev" (parent `62eeef5` "sdd: approve spec")
- **Spec reviewed:** `spec/spec.md` (R9-F2 issue-grounding of SDD spec tasks; unresolvable `#qoder-regression` reference regression)
- **QA date:** 2026-09-05
- **QA toolchain:** Temurin JDK 21.0.12.1 + Apache Maven 3.9.6, local repo `/workspace/.m2/repository`
- **REPORT_ID:** `53c0f4e4-27a4-4749-a888-3ce81ed67cef`

## Verdict

**SPEC_GAP** — see [Findings & verdict rationale](#findings--verdict-rationale).

## Scope of change under review

Commit `2a3aa31` adds an SDD spec-task issue-grounding path (labeled R9-F2):

- `act-execution` …/execution/git/: new `GitHubIssueClient` (GitHub REST client with bounded
  retry/backoff, slug→search resolution, numeric/`#n`/URL resolution), `GitHubIssue` record
  (title/body/labels + `bodySha256()`), `IssueReferenceException` (fail-fast carrier with 422/502/503
  semantics), `GitHandoffMetadata` (new keys `issueRef`, `issueRepo`, `issueNumber`, `issueBodySha`),
  `GitHubIssueClientConfig` (token-less bean degrades to fail-fast).
- `act-knowledge` …/knowledge/: `WorkflowTemplateService.instantiateTemplate` now, when the template
  carries a BA step that references `{issueRepo}` or a `gh issue view` instruction, resolves the issue
  **before** dispatch, inlines the authoritative issue context into the BA prompt, strips the
  `gh issue view` fetch clause, canonicalizes the ref, persists an audit record, and aborts on any
  unresolvable key / un-substituted `issueRepo`. `KnowledgeController` maps `IssueReferenceException`
  to HTTP 422/502/503.
- New/updated tests: `GitHubIssueClientTest`, `WorkflowTemplateServiceSddTest`,
  `WorkflowTemplateServiceTest`, `KnowledgeControllerTest`, `GitPipelineIntegrationTest`.

## Build & test results (actual, run in this sandbox)

| Step | Command | Result |
|---|---|---|
| Full compile + install (tests skipped) | `mvn install -DskipTests` | **SUCCESS** (all 8 modules) |
| act-execution unit (full) | `mvn test -pl act-execution` | **693 run, 0 fail, 0 err** (with ambient `OPENCODE=1` env var present, the pre-existing `OpenCodePropertiesBindingTest` reports 2 errors — see note) |
| act-knowledge unit (full) | `mvn test -pl act-knowledge` | **264 run, 0 fail, 0 err (4 skipped)** |
| New client contract tests | `GitHubIssueClientTest` | **9/9 PASS** (WireMock) |
| New SDD wiring regression tests | `WorkflowTemplateServiceSddTest` | **6/6 PASS** |
| Template service regression | `WorkflowTemplateServiceTest` | **16/16 PASS** |
| Controller 422 mapping | `KnowledgeControllerTest` | **40/40 PASS** (incl. `instantiateWorkflow_unresolvableIssueKey_returns422WithoutDispatching`) |
| Full-cycle integration | `GitPipelineIntegrationTest` (failsafe, H2) | **5/5 PASS** |

> Environment note (not caused by this change): `OpenCodePropertiesBindingTest` binds the whole OS
> environment; the sandbox exports `OPENCODE=1`, which relaxed-binds to the `opencode` property and
> makes 2 of its 4 tests throw `BindException`. Re-running with `env -u OPENCODE -u OPENCODE_PID`
> yields 4/4 PASS. This test/class and `OpenCodeProperties` are untouched by this branch — the
> failure is a pre-existing test fragility to ambient env, unrelated to the R9-F2 change.

## Acceptance-criteria assessment

| # | AC | Status | Evidence |
|---|---|---|---|
| 1 | Spec task dispatched only after `{issueRepo}` substituted + issue resolved to numeric # / URL existing in repo | **Met (scope caveat)** | `groundSpecTaskIssue` runs before `createAndStart`; `parseRepository` rejects placeholder/blank/invalid repo; `resolveIssue` fetches numeric/URL directly and slug via search. Caveat: grounding is triggered only when a **BA** step references `{issueRepo}`/`gh issue view`; a BA prompt referencing only `{issueRef}` is not grounded (by design of the heuristic). |
| 2 | BA message inlines full title/body/labels; fresh run needs no `gh` | **Met** | `issueContextBlock` appends Repository/#/Title/Labels/Body; gh-fetch clause neutralized. Unit test asserts inline content and absence of `gh issue view`. |
| 3 | Unresolvable/non-numeric key fails fast naming key+repo; no task emitted | **Met** | `IssueReferenceException.notFound` names key+repo, HTTP 422 via controller; SddTest (a) + controller test assert `createAndStart` never called. |
| 4 | Empty `{issueRepo}` fails fast with explicit error | **Met** | `IllegalArgumentException` from `groundSpecTaskIssue`/`parseRepository`; SddTest (b) asserts `resolveIssue`/`createAndStart` never called. |
| 5 | Fixture knowledge items (`e2e-skill-source-*`, `e2e-shared-guideline-*`, `e2e-knowledge-race-*`) inline full content OR filtered; names-only attachments eliminated | **NOT ADDRESSED in this branch** | No code change touches knowledge-context seeding/filtering for spec tasks. The fixture items are injected by the external SDD/e2e harness as name+label-only knowledge context (reproduced in the QA context of this very run). No in-repo dispatch path attaches these fixtures to a step prompt, so the reported symptom is not fixed end-to-end. |
| 6 | Dispatch records task ID, resolved repo, issue number, body hash (audit) | **Met** | `resolvedParams` (issueRepo/issueNumber/issueBodySha/canonical `#n`) persisted via `chain.templateParams` + structured log line; SddTest (c) asserts 64-hex sha and repo/number on the saved chain. |
| 7 | Regression tests (a) slug fail-fast, (b) empty repo fail-fast, (c) happy path inlined body | **Met** | All present and green (see test table). |

## Findings & verdict rationale

**What is solid.** The fix is well-scoped for the aria-conductor dispatch path. Dispatch is fail-fast
before any BA task is emitted; the full issue payload is inlined server-side so the sub-agent never
needs `gh` or network; errors name the offending key and repository; the 422-vs-503/502 mapping
matches the spec's Error Handling; audit data (repo, number, body sha) is persisted on the chain; and
all three mandated regression scenarios are covered by real, passing tests plus a full-cycle
integration test that exercises the real instantiate→BA→approve→Dev→QA pipeline.

**Why not PASS.** One explicit acceptance criterion (AC5 / Proposed-Solution item 4 — the fixture
knowledge context that started the regression) is not implemented anywhere in this branch, and the
spec never resolved where the request-construction defect actually lives. The observed symptom
— spec/QA sub-agents receiving `e2e-skill-source-*`, `e2e-shared-guideline-*`, `e2e-knowledge-race-*`
knowledge items attached by name only, with no content — originates in the external SDD/harness layer
that seeds task context (it recurs in the QA context of this run and in the `act-dashboard/e2e/api/*`
fixture seeds), not in `WorkflowTemplateService`. Within aria-conductor there is no task-context
knowledge assembly to fix, so AC5 cannot be conformed in this repository.

**Why DEFECT would be too strong.** Every criterion that maps onto code in this repo is implemented
and verified green; the outstanding item is blocked by the spec's own unanswered Questions, not by an
obvious coding omission the developer could fix inside `aria-conductor`.

**Unresolved spec ambiguities to feed back (Questions left open in `spec/spec.md`):**
1. Authoritative scope: aria-conductor dispatch layer vs. the external harness that composes BA-agent
   task messages / seeds knowledge context. AC5 is only satisfiable if the latter is in scope.
2. Where the full content of the Phase-E fixture knowledge items is stored so it can be inlined or
   filtered — the fixtures are attached by ID only, with no accessible body in-repo.
3. Error-code convention (422 vs 400) is now hard-coded as 422/502/503 in `IssueReferenceException`;
   confirm this matches the intended convention.
4. Inline-full-body vs. server-side-reference policy for the issue payload (implementation chose
   inline, which AC2 requires — consistent).

**Recommendations.**
- Confirm (or amend) the spec so AC5's target component is explicit; if the fixture-seeding harness is
  in scope, implement content-inline-or-filter there and add a regression test.
- Optionally widen the grounding trigger (currently gated on `{issueRepo}`/`gh issue view` appearing
  in a BA prompt) to any spec-authoring BA step that carries an issue reference, and document the
  trigger contract.

## Artifacts

- This report committed to the branch as `qa_report.md`.
- Test reports: `agent-control-tower/{act-execution,act-knowledge,act-app}/target/{surefire,failsafe}-reports/`.
