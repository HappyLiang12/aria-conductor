# Spec-Driven Development Workflow - Design Spec

## 1. Summary

Aria Conductor gains a **spec-driven development (SDD) loop** as its first governed workflow template - the **"development-workflow"**. The loop runs a GitHub-issue-driven pipeline: a BA agent analyzes the issue and writes a spec (stored as a governed knowledge item), the user approves the spec through an **enhanced approval gate** (markdown-rendered `SPEC_REVIEW` approval), a Dev agent implements with TDD, and a QA agent verifies against the spec by submitting a **DoD verdict** (`PASS` / `DEFECT` / `SPEC_GAP`). Routing is deterministic: `PASS` completes the chain, `DEFECT` re-schedules the Dev step (attempt counter + feedback), `SPEC_GAP` re-schedules the BA step (new spec version -> new approval). Aria acts as orchestrator; the platform remains an **adaptive harness** - users copy the template to customise their own workflows (e.g., insert a `code_review` step after Dev).

Design decisions confirmed in brainstorming:

- QA verdict is carried by an extended **DoD review** (`submit_dod_review` gains `verdict`), and the router reacts to the recorded review - deterministic, auditable, unit-testable.
- The spec is stored as a **knowledge item of new type `SPEC`**, reusing the existing versioned, governed (PENDING->APPROVED) knowledge lifecycle; Dev/QA agents consume the approved spec via the existing `query_knowledge` tool.
- Spec approval flows through the **approval gate**: submitting a spec creates a `SPEC_REVIEW` approval (markdown content, `knowledgeItemId` link); the user's decision writes back to knowledge status. Approvals page gains a lightweight markdown renderer; `TOOL_CALL` cards are unchanged.
- The chain **waits at the chain level** via a new `WorkflowChain.Status.WAITING_APPROVAL`; routing is event-driven step re-scheduling (attempt counts), reusing `startStep` / `{previousOutput}` mechanics.
- **Entry point is the existing preset-workflow mechanism** - no new tool. The template is a `WORKFLOW` knowledge item (YAML in `knowledge_versions.yaml_content`), instantiated via the existing `instantiateTemplate`. Enhancement lives in the template schema (step `kind`), instantiation wiring (DoD init, kanban item), and the routing engine.
- Spec approval renders with a **lightweight markdown renderer** in the Approvals page; QA reports reuse the existing `generate_report` + sandboxed-iframe Reports page unchanged.
- Implementation approach: **kind-routing embedded in the engine** (`WorkflowAutoChainer` + `WorkflowService`) with a new `SpecReviewCoordinator` for approval/knowledge write-back; simple metadata (step-kind enum + derived defaults), not graph routing.

Critical feasibility finding (verified): most machinery already exists.

| Existing capability | Location |
|---|---|
| BA / Dev / QA agent templates (`createFromTemplate`) | `act-agent/.../AgentTemplateService.java` |
| Role/tool/skill resolution (dev/qa/ba keywords) | `act-agent/.../AgentService.java` |
| Sequential multi-agent chains, `{previousOutput}`, auto-advance | `WorkflowService.java` + `WorkflowAutoChainer.java` |
| WORKFLOW knowledge templates, YAML versioning, APPROVED gate, `instantiateTemplate` | `act-knowledge/.../WorkflowTemplateService.java` + `KnowledgeVersion.yaml_content` (V18) |
| DoD stage gate (dev->qa->ba->pm; dev/qa required) with init/review/evidence | `act-execution/.../dod/DoDService.java` |
| Approval gate (blocking future, TTL, WS, approve/deny) | `ApprovalGate` + `ApprovalsPage.tsx` |
| Knowledge governance (PENDING->APPROVED, versioning) | `act-knowledge` |
| Report generation + sandboxed iframe rendering | `ReportService` / `ReportToolHandler` / `ReportsPage.tsx` |
| GitHub operations (issue/PR read-write, CI checks via `http_request` + `gh`) | PR #66 (headers, `gh` in image, `curl` whitelisted) |
| Adaptive harness profiles (tool steering, self-verify ESCALATE->HITL, budgets) | `HarnessProfile` + V37 seed |

What is genuinely new (must be built): step-kind metadata + routing, `WAITING_APPROVAL` chain state, `SPEC` knowledge type, DoD `verdict`, `SPEC_REVIEW` approval kind + markdown rendering, `SpecReviewCoordinator`, the seeded development-workflow template.

## 2. Data Model & Migration (V40)

| Model | Change | Compatibility |
|---|---|---|
| `WorkflowStep` (JSON CLOB in `steps_json`, no table) | add `kind` enum (`GENERIC`/`BA`/`DEV`/`QA`/`CODE_REVIEW`) + `attemptCount` (int) | Missing JSON fields deserialize to `GENERIC`/`0` - existing chains unaffected |
| `WorkflowChain.Status` (VARCHAR) | add `WAITING_APPROVAL` | New enum value only; no migration |
| `DoDStageReview` (table) | add `verdict` column (`PASS`/`DEFECT`/`SPEC_GAP`, nullable; only meaningful for the `qa` stage) | Nullable - existing reviews unaffected |
| `Approval` (table) | add `approvalType` (`TOOL_CALL` default / `SPEC_REVIEW`), `content` (TEXT markdown), `contentKind` (`MARKDOWN`/`HTML`), `knowledgeItemId` (UUID, write-back link) | All nullable/defaulted; existing rows -> `TOOL_CALL` |
| `KnowledgeType` (enum) | add `SPEC` | Java enum only |

`act-app/src/main/resources/db/migration/V40__sdd_workflow.sql`:

```sql
ALTER TABLE dod_stage_reviews ADD COLUMN verdict VARCHAR(20);
ALTER TABLE approvals ADD COLUMN approval_type VARCHAR(20) NOT NULL DEFAULT 'TOOL_CALL';
ALTER TABLE approvals ADD COLUMN content TEXT;
ALTER TABLE approvals ADD COLUMN content_kind VARCHAR(20);
ALTER TABLE approvals ADD COLUMN knowledge_item_id UUID;
```

Entities and V40 land in the same PR (`ddl-auto: validate` boot check).

## 3. Template Schema & Instantiation

Template YAML gains an optional per-step `kind`; workflow-level declarations are optional with **derived defaults** (template stays simple):

```yaml
name: development-workflow
description: Spec-driven development loop: BA -> spec approval -> Dev -> QA
steps:
  - kind: ba            # derived: chain produces a spec that needs approval
    agent_role: ba
    prompt_template: "Analyze issue {issueRef} and write a spec..."
  - kind: dev           # derived: DoD stages [dev, qa]
    agent_role: dev
    prompt_template: "Implement per approved spec {specRef}..."
  - kind: qa
    agent_role: qa
    prompt_template: "Verify against spec {specRef}; submit DoD verdict..."
# Optional overrides: dodStages: [dev, qa] / specApproval: true / maxAttempts: 3
```

- Derivation rules: any step `kind=BA` -> chain enables spec approval; any step `kind=DEV`/`QA` -> chain initialises DoD (dev/qa required stages, taskId=chainId); templates may explicitly override (customisation extension point; v1 ships derivation + optional overrides only).
- `WorkflowTemplateConverter.yamlToWorkflowSteps` maps `kind` -> `WorkflowStep.kind`; missing -> `GENERIC`.
- `WorkflowTemplateService.instantiateTemplate` additionally wires: init DoD (taskId=chainId), create kanban item, preserve kinds through `CreateWorkflowRequest.StepDef`.
- Seed migration V40 also inserts the **development-workflow** `WORKFLOW` knowledge item (APPROVED, name `development-workflow`) with YAML in `knowledge_versions.yaml_content` (deterministic IDs, mirroring V22/V33 seed pattern). Users copy the item to customise.

## 4. Component Responsibilities

| Component | Change |
|---|---|
| `SpecReviewCoordinator` (**new**) | On BA-step completion: store the spec as a `SPEC` knowledge item (PENDING) - the spec content is the BA step's run `finalOutput` (markdown) - then create a `SPEC_REVIEW` approval (content=spec markdown, `knowledgeItemId`, runId=BA run) -> chain to `WAITING_APPROVAL`. Listens to approval decisions: APPROVED -> write knowledge APPROVED -> route chain onward; REJECTED -> write knowledge REJECTED -> re-schedule BA step with rejection reason as feedback. Idempotent; locates a chain's pending approval as the latest PENDING `SPEC_REVIEW` approval whose runId belongs to the chain's BA step (DB-derivable, restart-safe, no new chain column). |
| `WorkflowAutoChainer` (extend) | Kind-aware routing on `RunCompletedEvent`: BA completion -> hand to coordinator; QA completion -> read latest `qa`-stage DoD review verdict -> PASS advance / DEFECT re-schedule Dev / SPEC_GAP re-schedule BA; `GENERIC`/`CODE_REVIEW` completion -> current advance logic unchanged. Routing matrix centralised in a private `routeStepCompletion` method (unit-testable). All transitions guarded by chain-status preconditions (only route while RUNNING). |
| `WorkflowService` (extend) | New `rescheduleStep(chainId, stepIdx, feedback)`: `attemptCount++`, feedback appended to prompt, new run started (reuses `startStep`); exceeding `maxAttempts` (default 3, template-overridable) -> chain FAILED with reason. `instantiateTemplate` wiring as in Sec. 3. |
| `DoDService`/`DoDController` (extend) | `review()` accepts optional `verdict`; required for the `qa` stage, ignored elsewhere. |
| `ApprovalController`/DTOs (extend) | Expose `approvalType`/`content`/`contentKind`/`knowledgeItemId` on list/get responses. |
| `ApprovalsPage.tsx` (extend) | `SPEC_REVIEW` cards render `content` via a lightweight markdown renderer (also reusable by the knowledge detail view); `TOOL_CALL` cards unchanged. |
| `AriaDefaultAgentInitializer` (extend) | System prompt section: how to find (`findMatchingTemplates`) and instantiate the development-workflow; note that users can copy the template to customise. |
| Kanban linkage | Chain RUNNING -> item IN_PROGRESS; WAITING_APPROVAL -> REVIEW; COMPLETED -> DONE; FAILED -> BLOCKED (existing valid transitions). |

## 5. State Machine & Data Flow

```
PENDING -(start BA)-> RUNNING -(BA done + spec submitted)-> WAITING_APPROVAL
WAITING_APPROVAL -(APPROVED)-> RUNNING -(Dev done)-> RUNNING -(QA done)-> route
WAITING_APPROVAL -(REJECTED)-> RUNNING -(re-schedule BA step)
route: verdict=PASS -> COMPLETED
       verdict=DEFECT -> RUNNING (re-schedule Dev step, attempt+1, feedback)
       verdict=SPEC_GAP -> RUNNING (re-schedule BA step, attempt+1, feedback)
any -(run failure / maxAttempts exceeded / QA without verdict)-> FAILED
any -(user cancel)-> CANCELLED
```

**Happy path:** `instantiateTemplate("development-workflow", {issueRef})` -> chain created (ba/dev/qa with kinds) + DoD init + kanban item -> BA run -> completion -> coordinator stores SPEC knowledge (PENDING) + creates SPEC_REVIEW approval + chain -> WAITING_APPROVAL -> user approves (markdown rendered in Approvals) -> knowledge APPROVED + chain -> RUNNING -> Dev run (prompt carries specRef; agent fetches the approved spec via `query_knowledge`) -> QA run (QA calls `submit_dod_review(verdict=...)` inside the run) -> router reads verdict -> PASS -> COMPLETED.

**DEFECT loop:** QA verdict DEFECT (comment/evidence = defect description) -> `rescheduleStep(chain, devIdx, feedback)` -> Dev reworks -> QA re-reviews.
**SPEC_GAP loop:** verdict SPEC_GAP -> `rescheduleStep(chain, baIdx, feedback)` -> BA rewrites -> **new version of the same SPEC knowledge item** (version++) -> new SPEC_REVIEW approval -> chain re-enters WAITING_APPROVAL (revisions always require re-approval).

## 6. Error Handling

| Scenario | Behaviour |
|---|---|
| Step run FAILED/CANCELLED/ABORTED | Existing semantics: chain FAILED; manual `retry_workflow_step` remains available (no auto-retry in v1). |
| QA run completed without a verdict review | Chain FAILED with explicit message ("QA completed but no verdict submitted") - deterministic, no silent loop. |
| Approval TTL EXPIRED | Chain stays WAITING_APPROVAL; no auto-routing (expiry != rejection). BA may resubmit (new approval record) or user cancels the chain. |
| maxAttempts exceeded (default 3 per step) | Chain FAILED with reason (step, attempt count, last feedback summary). |
| Event re-delivery / concurrency | All routing transitions guarded by chain-status preconditions; coordinator idempotent per approvalId; knowledge write-back guarded. |
| Spec knowledge persistence failure | Chain FAILED with message; retryable. |
| REJECTED | Rejection reason appended as feedback to the BA rewrite prompt (next version must address it). |

## 7. Testing Plan (top-down TDD)

Every code change is covered by at least one test; existing behaviour is regression-guarded.

**Phase 0 - Contract freeze (no code):** API/DTO contracts and frontend behaviour pinned:
- `POST /api/v1/dod/{taskId}/review` gains optional `verdict` (required for qa stage)
- `GET /api/v1/approvals` returns new fields (`approvalType`/`content`/`contentKind`/`knowledgeItemId`)
- Template YAML schema (`kind`) and `instantiateTemplate` parameter contract
- Approvals page SPEC_REVIEW card rendering behaviour

**Phase 1 - E2E/contract tests first (RED):** `act-dashboard/e2e/sdd-workflow.spec.ts` - API-driven instantiation -> SPEC_REVIEW card with markdown rendered -> approve -> poll chain COMPLETED (reuse the API_URL-parameterised contract from existing specs). Contract tests assert the new fields exist. Must fail before implementation, pass after.

**Phase 2 - Integration tests (RED->GREEN):** `IntegrationTestBase` + `MockAdkRuntime`: happy path (template -> BA -> approval -> APPROVED -> Dev -> QA verdict=PASS -> COMPLETED; assert knowledge APPROVED, DoD passed, kanban DONE); DEFECT loop (attempt=2, feedback in prompt, final PASS); SPEC_GAP loop (knowledge v2, new approval, Dev consumes v2); boundaries (maxAttempts -> FAILED; QA without verdict -> FAILED; approval EXPIRED -> chain stays WAITING_APPROVAL).

**Phase 3 - Unit tests (RED->GREEN):** routing matrix (8 cases: BA->coordinator; QA PASS/DEFECT/SPEC_GAP; GENERIC advance; run failure; status guards); `rescheduleStep` (attempt++/feedback/new run/maxAttempts); `SpecReviewCoordinator` (knowledge PENDING + approval + WAITING; APPROVED/REJECTED write-back + routing; idempotency; pending-approval locating); `WorkflowTemplateConverter` kind parsing + defaulting; DoD verdict validation.

**Phase 4 - Regression (all green):** golden compatibility tests - a `GENERIC` chain (no kinds) walks the exact current path (advance/fail/retry/events unchanged); existing suites must stay green: `WorkflowAutoChainerTest`, `WorkflowServiceExistingTest`, `WorkflowTemplateServiceTest`, `DoDServiceTest`, approval + approvals-page specs, workflow-governance spec. Full commands: `mvn clean test -Dspring.profiles.active=h2`, `cd act-dashboard && pnpm build`, `npx playwright test`, `cd langchain-adk && python -m pytest tests/`. CI via existing `.github/workflows/ci.yml`; PR merges only when green.

### Coverage mapping (code change -> tests)

| Change | Unit | Integration | E2E | Regression |
|---|---|---|---|---|
| `WorkflowStep.kind/attemptCount` | converter parse + default | instantiated chain steps carry kinds | - | existing chain deserialisation golden |
| `WorkflowChain.Status.WAITING_APPROVAL` | status guards | happy path chain-state asserts | - | existing status-transition tests |
| `DoDStageReview.verdict` + API | qa-required validation | verdict-driven routing | - | existing DoD tests |
| `Approval` new fields + API | DTO mapping | approval created with content | card rendering | existing approval tests/page |
| `SpecReviewCoordinator` (new) | idempotency/write-back/locating | approval/knowledge/chain three-way | advance after approve | - |
| `WorkflowAutoChainer` routing | 8-case matrix | three-branch flows | - | existing chainer tests green |
| `WorkflowService.rescheduleStep` | count/feedback/limit | DEFECT/SPEC_GAP loops | - | existing workflow lifecycle tests |
| `WorkflowTemplateService` wiring | - | instantiation creates DoD+kanban | end-to-end instantiation | existing template tests |
| `WorkflowTemplateConverter` kind | parse + default | - | - | existing YAML conversion tests |
| ApprovalsPage markdown render | component test | - | card render assertions | existing approvals-page spec |
| V40 seeds | - | template discoverable + instantiable | - | seed integrity |

## 8. Non-Goals (v1)

- No auto-retry of failed runs (manual `retry_workflow_step` stays).
- No graph-style routing metadata (`onReject -> arbitrary step`); only kind-derived BA/DEV/QA semantics; `CODE_REVIEW` and other kinds advance generically.
- No genui interactive-widget wiring; report mechanism unchanged (QA report = existing `generate_report`).
- No multi-outcome approval (approve/reject only; "request changes" = reject + reason).
- No multi-instance DB-backed approval signalling (existing single-instance in-memory + restart-recovery pattern).
- No template marketplace / template-editor UI (copy the knowledge item + edit YAML = customisation, governance gate unchanged).

## 9. Risks & Mitigations

- `ddl-auto: validate` boot failure -> entities and V40 in same PR; `mvn verify` boots the context.
- `steps_json` deserialisation compatibility -> defaulted `kind`/`attemptCount` + golden regression tests.
- Listener races / double events -> chain-status preconditions + coordinator idempotency; integration coverage of both loops.
- `WorkflowAutoChainer` growth -> routing matrix centralised in `routeStepCompletion`; direct unit tests.
- Weak-model reliability in BA/Dev/QA steps -> existing harness profiles (`weak-model-safe`) apply per agent as today; no new mechanism.
- Seeded template drift -> seed integrity test asserts the development-workflow item exists, is APPROVED, and instantiates.

## 10. Rejected Alternatives

- **Verdict via output parsing / Aria-interpreted routing**: fragile, un-auditable, untestable - DoD review verdict chosen (deterministic, persistent, testable).
- **Dedicated DevSpec entity / spec-as-report-artifact**: duplicates existing versioned governed knowledge lifecycle or misplaces agent-consumption semantics - `SPEC` knowledge item chosen.
- **Spec approval on the Knowledge page / on both surfaces**: dual surface + dual write; approval gate is the single human-decision surface, knowledge status is written back by the coordinator.
- **Run-level blocking approval (request_approval pattern)**: proven for tool gates but "reject -> re-route" is an abnormal run outcome the binary COMPLETED/FAILED chainer cannot carry - chain-level WAITING_APPROVAL chosen.
- **Dedicated `start_sdd_workflow` tool**: SDD is the first of many customisable workflows; entry point stays the existing preset-workflow mechanism (template), with enhancement in schema + engine.
- **Standalone SddWorkflowCoordinator facade (approach 2)**: overlaps `WorkflowAutoChainer` on the same events (mutual-exclusion complexity) while still needing the chain state change - kind routing embedded in the engine chosen.
- **Graph routing metadata**: deferred - simple step-kind semantics ship first; `dodStages`/`specApproval`/`maxAttempts` optional overrides are the extension point.
- **iframe rendering for spec approval / genui widgets**: spec is a text document (lightweight markdown renderer); interactive widgets are YAGNI for the first loop.
