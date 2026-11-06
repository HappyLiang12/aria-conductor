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
- DoD stage progression for SDD chains uses a **custom stage list (`[dev, qa]`)** stored on the record; the engine **auto-submits the dev-stage review** when a DEV step completes (deterministic, no model dependency), so the QA verdict lands on the `qa` stage and "DoD passed" is reachable.
- The **spec reference is injected into Dev/QA prompts after approval** (coordinator rewrites `{specRef}` to the SPEC knowledge item UUID); `review()` never globally requires `verdict` - only the SDD router does, so existing non-SDD reviewers (EvidenceDrawer, DoDToolHandler) stay untouched.

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
| `WorkflowStep` (JSON CLOB in `steps_json`, no table) | add `kind` enum (`GENERIC`/`BA`/`DEV`/`QA`/`CODE_REVIEW`) + `attemptCount` (int) | Missing JSON fields deserialize to `GENERIC`/`0` via `@Builder.Default` (matching existing `status`/`maxIterations`) - existing chains unaffected |
| `WorkflowChain.Status` (VARCHAR) | add `WAITING_APPROVAL` | New enum value only; no migration |
| `DoDStageReview` (table) | add `verdict` column (`PASS`/`DEFECT`/`SPEC_GAP`, nullable; only meaningful for the `qa` stage) | Nullable - existing reviews unaffected |
| `Approval` (table) | add `approvalType` (`TOOL_CALL` default / `SPEC_REVIEW`), `content` (TEXT markdown), `contentKind` (`MARKDOWN`/`HTML`), `knowledgeItemId` (UUID, write-back link) | All nullable/defaulted; existing rows -> `TOOL_CALL` |
| `KnowledgeType` (enum) | add `SPEC` | Java enum only |
| `DoDRecord` (table) | add `stagesJson` (TEXT) - per-record custom stage list; SDD chains use `[dev, qa]`, default remains `DEFAULT_STAGES` | Nullable - existing records unaffected |
| `WorkflowChain` (table) | add `reportArtifactId` (UUID, nullable) - link to the loop's latest QA report artifact | Nullable - existing chains unaffected |
| `KanbanStatus` (enum) | add `REVIEW` + transition matrix (`IN_PROGRESS->REVIEW`, `REVIEW->IN_PROGRESS/DONE/BLOCKED`) | DB column is VARCHAR(20) - no migration; Java enum only |

`act-app/src/main/resources/db/migration/V40__sdd_workflow.sql`:

```sql
ALTER TABLE dod_stage_reviews ADD COLUMN verdict VARCHAR(20);
ALTER TABLE dod_records ADD COLUMN stages_json TEXT;
ALTER TABLE approvals ADD COLUMN approval_type VARCHAR(20) NOT NULL DEFAULT 'TOOL_CALL';
ALTER TABLE approvals ADD COLUMN content TEXT;
ALTER TABLE approvals ADD COLUMN content_kind VARCHAR(20);
ALTER TABLE approvals ADD COLUMN knowledge_item_id UUID;
ALTER TABLE workflow_chains ADD COLUMN report_artifact_id UUID;
```

Entities and V40 land in the same PR (`ddl-auto: validate` boot check).

## 3. Template Schema & Instantiation

Template YAML gains an optional per-step `kind`; workflow-level declarations are optional with **derived defaults** (template stays simple):

```yaml
name: development-workflow   # NOTE: converter ignores top-level name/description; chain name comes from the knowledge item
steps:
  - kind: ba            # derived: chain produces a spec that needs approval
    agent_role: ba
    prompt_template: "Analyze issue {issueRef} and write a spec. End your output with SPEC_ID=<uuid> after approval."
  - kind: dev           # derived: DoD stages [dev, qa]
    agent_role: dev
    prompt_template: "Implement per approved spec {specRef}; run unit + integration tests and verify CI before finishing."
  - kind: qa
    agent_role: qa
    prompt_template: "Verify against spec {specRef}; generate a QA report via generate_report and submit the DoD verdict."
# Optional overrides: dodStages: [dev, qa] / specApproval: true / maxAttempts: 3
```

- Derivation rules: any step `kind=BA` -> chain enables spec approval; any step `kind=DEV`/`QA` -> chain initialises DoD with custom stages `[dev, qa]` (stored in `stagesJson`, taskId=chainId); templates may explicitly override (customisation extension point; v1 ships derivation + optional overrides only).
- `WorkflowTemplateConverter.yamlToWorkflowSteps` maps `kind` -> `WorkflowStep.kind` with **case normalisation** (`valueOf(raw.toUpperCase())`, so YAML `ba`/`dev`/`qa` work); missing -> `GENERIC`. `workflowChainToYaml` also emits `kind` so export/re-import of a chain keeps step semantics.
- `WorkflowTemplateService.instantiateTemplate` additionally wires: init DoD (custom stages `[dev, qa]`, taskId=chainId), create a **chain-level kanban item without `linkedRunId`** (avoids the run-level `RunKanbanAutoCreator` auto-transition conflict), preserve kinds through `CreateWorkflowRequest.StepDef`.
- **SPEC knowledge item naming convention**: `spec-{chainId}` (deterministic, searchable via `query_knowledge`); the coordinator rewrites Dev/QA step prompts after approval, replacing `{specRef}` with the SPEC item UUID.
- Seed migration V40 also inserts the **development-workflow** `WORKFLOW` knowledge item (APPROVED, name `development-workflow`) with YAML in `knowledge_versions.yaml_content` (deterministic IDs, mirroring V22/V33 seed pattern). Users copy the item to customise.

## 4. Component Responsibilities

| Component | Change |
|---|---|
| `SpecReviewCoordinator` (**new**) | On BA-step completion: store the spec as a `SPEC` knowledge item named `spec-{chainId}` (PENDING; content = BA run `finalOutput` markdown) - **idempotent: skip if a SPEC_REVIEW approval already exists for that BA run** - then create a `SPEC_REVIEW` approval (content=markdown, `knowledgeItemId`, runId=BA run, `expiresAt` required) -> chain to `WAITING_APPROVAL`. On decision: APPROVED -> write knowledge APPROVED, **rewrite Dev/QA step prompts replacing `{specRef}` with the SPEC UUID**, route chain onward; REJECTED -> write knowledge REJECTED, re-schedule BA step with rejection reason as feedback. Locates a chain's pending approval as the latest PENDING `SPEC_REVIEW` approval whose runId belongs to the chain's BA step. **Startup recovery**: scans APPROVED approvals whose chain is still WAITING_APPROVAL and re-routes them (covers the decided-but-unrouted restart window). Exposes `resubmitApproval(chainId)` (new `POST /api/v1/workflows/{id}/resubmit-approval`) to re-create an approval after EXPIRED. Publishes `ApprovalRequestedEvent` with a null-safe toolCallId. |
| `WorkflowAutoChainer` (extend) | Kind-aware routing on `RunCompletedEvent`, centralised in private `routeStepCompletion`: BA completion -> hand to coordinator; **DEV completion -> if the DoD record's currentStage is `dev`, auto-submit the dev-stage review (`passed=true`, reviewer=engine) so the record advances to `qa`; on DEFECT rework the stage is already `qa` - no re-submission - then advance the chain** (deterministic stage progression, no model dependency); QA completion -> read latest `qa`-stage DoD review verdict -> PASS advance / DEFECT re-schedule Dev / SPEC_GAP re-schedule BA; `GENERIC`/`CODE_REVIEW` completion -> current advance logic unchanged. All transitions guarded by chain-status preconditions (only route while RUNNING). |
| `WorkflowService` (extend) | New `rescheduleStep(chainId, stepIdx, feedback)`: `attemptCount++`, feedback appended to prompt, new run started (reuses `startStep`); **truncation policy: feedback is appended last and truncated first at the 10,000-char `startStep` limit, with a warning log**; exceeding `maxAttempts` (default 3, template-overridable) -> chain FAILED with reason. `cancelWorkflow` accepts `WAITING_APPROVAL` and invalidates the pending SPEC_REVIEW approval + PENDING spec knowledge item. `findChainByRunId` also scans `WAITING_APPROVAL` chains; `runId->chainId` resolution uses a direct repository query (no per-chain `steps_json` deserialisation on the hot path). |
| `DoDService`/`DoDController` (extend) | `init()` accepts an optional stages list (SDD chains pass `[dev, qa]`, persisted in `stagesJson`); `review()` accepts optional `verdict` - **purely recorded, never globally validated**; the SDD router requires a verdict on the chain's `qa` stage at chain-completion time (see Sec. 6), so existing non-SDD reviewers (EvidenceDrawer, DoDToolHandler) are unaffected. Verdict to `passed` mapping: PASS->true, DEFECT/SPEC_GAP->false (frozen in Phase 0 contract). |
| `ApprovalController`/DTOs (extend) | Expose `approvalType`/`content`/`contentKind`/`knowledgeItemId` on list/get responses. |
| `ApprovalsPage.tsx` (extend) | Branch by `approvalType` before rendering; `SPEC_REVIEW` cards render `content` via a lightweight markdown renderer **sanitised with DOMPurify** (content is untrusted - sourced from the BA run over a GitHub issue); `toolCallId` made optional (`?`); `TOOL_CALL` cards unchanged. |
| `EventBroadcastListener` (extend) | Null-safe `toolCallId` handling on `ApprovalRequestedEvent`; `approval.requested` WS payload carries `approvalType`. |
| `WorkflowTemplateConverter` (extend) | `kind` parse with case normalisation; `workflowChainToYaml` emits `kind` (round-trip preserves step semantics). |
| `KanbanService`/`KanbanStatus` (extend) | New `REVIEW` status + transition matrix (`IN_PROGRESS->REVIEW`, `REVIEW->IN_PROGRESS/DONE/BLOCKED`); chain-level items carry no `linkedRunId` so run-level auto-transitions do not compete. |
| `ApprovalExpiryChecker` (extend) | Scheduled scan also expires `SPEC_REVIEW` approvals (they have no blocking future; `expiresAt` is required on creation). |
| `WorkflowController` (extend) | New `POST /api/v1/workflows/{id}/resubmit-approval` (re-create approval after EXPIRED). |
| `AriaDefaultAgentInitializer` (extend) | System prompt section: how to find (`findMatchingTemplates`) and instantiate the development-workflow; note that users can copy the template to customise. |
| Kanban linkage | Chain RUNNING -> item IN_PROGRESS; WAITING_APPROVAL -> REVIEW; COMPLETED -> DONE; FAILED -> BLOCKED (REVIEW is a new status; other transitions are existing). |

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

**Happy path:** `instantiateTemplate("development-workflow", {issueRef})` -> chain created (ba/dev/qa with kinds) + DoD init (custom stages `[dev, qa]`) + chain-level kanban item (no `linkedRunId`) -> BA run -> completion -> coordinator stores SPEC knowledge `spec-{chainId}` (PENDING) + creates SPEC_REVIEW approval (markdown content, `expiresAt` set) + chain -> WAITING_APPROVAL -> user approves (sanitised markdown rendered in Approvals) -> knowledge APPROVED + **coordinator rewrites Dev/QA prompts replacing `{specRef}` with the SPEC UUID** + chain -> RUNNING -> Dev run (implements + runs unit/integration tests + verifies CI; fetches spec via `query_knowledge`) -> completion -> **engine submits the dev-stage DoD review (`passed=true`) when the record is at `dev`, advancing it to `qa`** -> QA run (generates QA report via `generate_report`; calls `submit_dod_review(verdict=...)` - lands on the `qa` stage) -> router reads the `qa`-stage verdict -> PASS -> chain COMPLETED, DoD overallStatus PASSED (both `[dev, qa]` stages advanced), latest QA report id stored on the chain (`reportArtifactId`).

**DEFECT loop:** QA verdict DEFECT (comment/evidence = defect description) -> `rescheduleStep(chain, devIdx, feedback)` -> Dev reworks -> QA re-reviews.
**SPEC_GAP loop:** verdict SPEC_GAP -> `rescheduleStep(chain, baIdx, feedback)` -> BA rewrites -> **new version of the same SPEC knowledge item** (version++) -> new SPEC_REVIEW approval -> chain re-enters WAITING_APPROVAL (revisions always require re-approval).

## 6. Error Handling

| Scenario | Behaviour |
|---|---|
| Step run FAILED/CANCELLED/ABORTED | Existing semantics: chain FAILED; manual `retry_workflow_step` remains available (no auto-retry in v1). |
| SDD chain QA run completed without a verdict review | Chain FAILED with explicit message ("QA completed but no verdict submitted") - deterministic, no silent loop. Non-SDD chains never check verdicts. |
| Approval TTL EXPIRED | Chain stays WAITING_APPROVAL; no auto-routing (expiry != rejection). The user re-requests via `POST /api/v1/workflows/{id}/resubmit-approval` (new approval record, reuses the spec knowledge item) or cancels the chain (now supported in WAITING_APPROVAL). |
| maxAttempts exceeded (default 3 per step) | Chain FAILED with reason (step, attempt count, last feedback summary). |
| Event re-delivery / concurrency | All routing transitions guarded by chain-status preconditions; coordinator idempotent on both paths - creation keyed on "a SPEC_REVIEW approval already exists for this BA run", decision keyed on approvalId; knowledge write-back guarded. |
| Spec knowledge persistence failure | Chain FAILED with message; retryable. |
| REJECTED | Rejection reason appended as feedback to the BA rewrite prompt (next version must address it). |
| Restart between approval decision and routing | Startup recovery scan re-routes APPROVED approvals whose chain is still WAITING_APPROVAL (idempotent; chain-status guard prevents double routing). |

## 7. Testing Plan (top-down TDD)

Every code change is covered by at least one test; existing behaviour is regression-guarded.

**Phase 0 - Contract freeze (no code):** API/DTO contracts and frontend behaviour pinned:
- `POST /api/v1/dod/review` (taskId in body) gains optional `verdict` - **purely recorded, never globally validated**; verdict-to-`passed` mapping frozen (PASS->true, DEFECT/SPEC_GAP->false)
- `GET /api/v1/approvals` returns new fields (`approvalType`/`content`/`contentKind`/`knowledgeItemId`); `toolCallId` declared nullable in the TS type
- New `POST /api/v1/workflows/{id}/resubmit-approval` (re-create approval after EXPIRED)
- Template YAML schema (`kind`, case-normalised) and `instantiateTemplate` parameter contract; SPEC knowledge naming `spec-{chainId}`
- Approvals page SPEC_REVIEW card behaviour: markdown render **sanitised with DOMPurify**, null `toolCallId` safe, `KnowledgeType` union + form options gain `SPEC`
- `KanbanStatus.REVIEW` + transition matrix

**Phase 1 - E2E/contract tests first (RED):** `act-dashboard/e2e/sdd-workflow.spec.ts` - API-driven instantiation -> SPEC_REVIEW card with markdown rendered (assert the card does not crash with a null `toolCallId`) -> approve -> poll chain COMPLETED (reuse the API_URL-parameterised contract from existing specs). Contract tests assert the new fields exist. Must fail before implementation, pass after.

**Phase 2 - Integration tests (RED->GREEN):** `IntegrationTestBase` + `MockAdkRuntime`: happy path (template -> BA -> SPEC_REVIEW approval -> APPROVED -> **Dev/QA prompts carry the injected SPEC UUID** -> Dev completion **auto-submits the dev-stage DoD review and advances to qa** -> QA verdict=PASS -> COMPLETED; assert knowledge APPROVED, DoD overallStatus PASSED, kanban DONE, chain `reportArtifactId` set); DEFECT loop (attempt=2, feedback in prompt, final PASS); SPEC_GAP loop (knowledge v2, new approval, Dev consumes v2); boundaries (maxAttempts -> FAILED; SDD QA without verdict -> FAILED; approval EXPIRED -> chain stays WAITING_APPROVAL, `resubmit-approval` re-creates it; **restart between approval decision and routing recovers the chain**).

**Phase 3 - Unit tests (RED->GREEN):** routing matrix (9 cases: BA->coordinator; **DEV->auto-submit dev review + advance**; QA PASS/DEFECT/SPEC_GAP; GENERIC advance; run failure; status guards); `rescheduleStep` (attempt++/feedback/truncation policy/new run/maxAttempts); `SpecReviewCoordinator` (knowledge PENDING + approval + WAITING; **creation idempotency keyed on BA run**; APPROVED/REJECTED write-back + prompt rewrite + routing; **startup recovery scan**; pending-approval locating); `WorkflowTemplateConverter` kind parsing (case normalisation) + defaulting + round-trip; DoD `init` custom stages; verdict recording (no global validation).

**Phase 4 - Regression (all green):** golden compatibility tests - a `GENERIC` chain (no kinds) walks the exact current path (advance/fail/retry/events unchanged); `DoDService.init` overload keeps the default stages behaviour (existing callers pass no stages), `review()` never rejects existing qa-stage submissions without verdict (EvidenceDrawer/DoDToolHandler unaffected); existing suites must stay green: `WorkflowAutoChainerTest`, `WorkflowServiceExistingTest`, `WorkflowTemplateServiceTest`, `DoDServiceTest`, approval + approvals-page specs, workflow-governance spec. Seed integrity asserts: development-workflow item exists, is APPROVED, its version row matches `findByKnowledgeItemIdAndVersion`, instantiates, and the instantiated chain has no unreplaced `{specRef}` after approval. Full commands: `mvn clean test -Dspring.profiles.active=h2`, `cd act-dashboard && pnpm build`, `npx playwright test`, `cd langchain-adk && python -m pytest tests/`. CI via existing `.github/workflows/ci.yml`; PR merges only when green.

### Coverage mapping (code change -> tests)

| Change | Unit | Integration | E2E | Regression |
|---|---|---|---|---|
| `WorkflowStep.kind/attemptCount` | converter parse (case normalisation) + default | instantiated chain steps carry kinds | - | existing chain deserialisation golden |
| `WorkflowChain.Status.WAITING_APPROVAL` + `reportArtifactId` | status guards | happy path chain-state asserts | - | existing status-transition tests |
| `DoDStageReview.verdict` + `DoDRecord.stagesJson` | recording (no global validation); custom stages | verdict-driven routing; dev-review auto-submit | - | existing DoD tests (init overload default) |
| `Approval` new fields + API | DTO mapping | approval created with content | card rendering (null toolCallId safe) | existing approval tests/page |
| `SpecReviewCoordinator` (new) | idempotency/write-back/prompt-rewrite/recovery | approval/knowledge/chain three-way | advance after approve | - |
| `WorkflowAutoChainer` routing | 9-case matrix | three-branch + dev-advance flows | - | existing chainer tests green |
| `WorkflowService.rescheduleStep`/`cancelWorkflow`/`findChainByRunId` | count/feedback/truncation/limit; WAITING cancel; scan scope | DEFECT/SPEC_GAP loops; restart recovery | - | existing workflow lifecycle tests |
| `WorkflowTemplateService` wiring | - | instantiation creates DoD(stages)+kanban(no run link) | end-to-end instantiation | existing template tests |
| `WorkflowTemplateConverter` kind (both directions) | parse + default + round-trip | - | - | existing YAML conversion tests |
| ApprovalsPage markdown render (sanitised) + `EventBroadcastListener` null-safe | component tests | - | card render + WS assertions | existing approvals-page spec |
| `KanbanStatus.REVIEW` + `ApprovalExpiryChecker` + resubmit endpoint | transition matrix; expiry scan; endpoint | EXPIRED/resubmit boundary | - | existing kanban + approval tests |
| V40 seeds | - | template discoverable + instantiable; version row matches | - | seed integrity (no unreplaced `{specRef}`) |

## 8. Non-Goals (v1)

- No auto-retry of failed runs (manual `retry_workflow_step` stays).
- No graph-style routing metadata (`onReject -> arbitrary step`); only kind-derived BA/DEV/QA semantics; `CODE_REVIEW` and other kinds advance generically.
- No genui interactive-widget wiring; report mechanism unchanged (QA report = existing `generate_report`).
- No multi-outcome approval (approve/reject only; "request changes" = reject + reason).
- No multi-instance DB-backed approval signalling (existing single-instance in-memory + restart-recovery pattern).
- No template marketplace / template-editor UI (copy the knowledge item + edit YAML = customisation, governance gate unchanged).

## 9. Risks & Mitigations

- Entity/schema drift (V40): `ddl-auto: validate` exists only in the default profile - the h2/test profiles use `ddl-auto: none`, so `mvn verify` will NOT catch entity/SQL drift. Entities and V40 land in the same PR; CI adds a schema-consistency smoke (temporarily enable validate under the h2 profile).
- `steps_json` deserialisation compatibility -> defaulted `kind`/`attemptCount` (`@Builder.Default`) + golden regression tests.
- Listener races / double events -> chain-status preconditions + coordinator idempotency on both creation and decision paths; integration coverage of both loops.
- `WorkflowAutoChainer` growth -> routing matrix centralised in `routeStepCompletion`; direct unit tests.
- Weak-model reliability in BA/Dev/QA steps -> existing harness profiles (`weak-model-safe`) apply per agent as today; no new mechanism.
- Seeded template drift -> seed integrity test asserts the development-workflow item exists, is APPROVED, its version row matches, instantiates, and the instantiated chain has no unreplaced `{specRef}`.
- SPEC_REVIEW self-approval by agents -> `decide_approval` rejects SPEC_REVIEW decisions from agent runs (human/API only), keeping the governance gate meaningful.
- Stored XSS via spec content -> DOMPurify sanitisation on SPEC_REVIEW markdown rendering (content originates from an external GitHub issue).

## 10. Rejected Alternatives

- **Verdict via output parsing / Aria-interpreted routing**: fragile, un-auditable, untestable - DoD review verdict chosen (deterministic, persistent, testable).
- **Dedicated DevSpec entity / spec-as-report-artifact**: duplicates existing versioned governed knowledge lifecycle or misplaces agent-consumption semantics - `SPEC` knowledge item chosen.
- **Spec approval on the Knowledge page / on both surfaces**: dual surface + dual write; approval gate is the single human-decision surface, knowledge status is written back by the coordinator.
- **Run-level blocking approval (request_approval pattern)**: proven for tool gates but "reject -> re-route" is an abnormal run outcome the binary COMPLETED/FAILED chainer cannot carry - chain-level WAITING_APPROVAL chosen.
- **Dedicated `start_sdd_workflow` tool**: SDD is the first of many customisable workflows; entry point stays the existing preset-workflow mechanism (template), with enhancement in schema + engine.
- **Standalone SddWorkflowCoordinator facade (approach 2)**: overlaps `WorkflowAutoChainer` on the same events (mutual-exclusion complexity) while still needing the chain state change - kind routing embedded in the engine chosen.
- **Graph routing metadata**: deferred - simple step-kind semantics ship first; `dodStages`/`specApproval`/`maxAttempts` optional overrides are the extension point.
- **iframe rendering for spec approval / genui widgets**: spec is a text document (lightweight markdown renderer); interactive widgets are YAGNI for the first loop.
- **Verdict globally required for the `qa` stage**: would 400 every existing non-SDD qa-stage reviewer (EvidenceDrawer, DoDToolHandler) - rejected; `verdict` is purely recorded and only the SDD router enforces it at chain-completion time.
- **Kanban WAITING state mapped to existing BLOCKED**: rejected - a dedicated `REVIEW` status keeps approval-waiting semantics distinct from failure/blocking.
