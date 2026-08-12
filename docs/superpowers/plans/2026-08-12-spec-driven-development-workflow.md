# Spec-Driven Development Workflow Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Turn a GitHub-issue-driven BA→spec-approval→Dev→QA loop into Aria Conductor's first governed "development-workflow" template, with deterministic QA-verdict routing (PASS/DEFECT/SPEC_GAP) layered on the existing WorkflowChain + DoD + ApprovalGate machinery.

**Architecture:** Extend the sequential workflow engine with step kinds (`BA`/`DEV`/`QA`) and a chain-level `WAITING_APPROVAL` state. A new `SpecReviewCoordinator` stores the BA output as a versioned `SPEC` knowledge item, opens a `SPEC_REVIEW` approval, and writes the decision back to knowledge + resumes the chain. QA submits a DoD verdict on the `qa` stage; the router re-schedules the Dev step (DEFECT) or BA step (SPEC_GAP). Entry point stays the existing preset-workflow template mechanism.

**Tech Stack:** Java 21 / Spring Boot 3.3 (JPA + Flyway), React 19 / Vite / TanStack Query, Playwright E2E, JUnit 5 + Mockito, `IntegrationTestBase` + H2 test profile.

**Spec:** `docs/superpowers/specs/2026-08-12-spec-driven-development-workflow-design.md`

---

## File Structure

**Modify (entities & enums — additive, backward compatible):**
- `agent-control-tower/act-common/src/main/java/io/aria/conductor/common/model/WorkflowStep.java` — add `StepKind` enum, `kind`, `attemptCount`
- `agent-control-tower/act-common/src/main/java/io/aria/conductor/common/model/WorkflowChain.java` — `Status.WAITING_APPROVAL`, `reportArtifactId`
- `agent-control-tower/act-common/src/main/java/io/aria/conductor/common/model/Approval.java` — `ApprovalType`, `ContentKind`, `content`, `knowledgeItemId`
- `agent-control-tower/act-common/src/main/java/io/aria/conductor/common/model/KnowledgeType.java` — add `SPEC`
- `agent-control-tower/act-common/src/main/java/io/aria/conductor/common/event/ApprovalRequestedEvent.java` + `ApprovalDecidedEvent.java` — optional `approvalType`
- `agent-control-tower/act-execution/src/main/java/io/aria/conductor/execution/dod/DoDRecord.java` — `stagesJson`
- `agent-control-tower/act-execution/src/main/java/io/aria/conductor/execution/dod/DoDStageReview.java` — `verdict`
- `agent-control-tower/act-execution/src/main/java/io/aria/conductor/execution/kanban/KanbanStatus.java` — add `REVIEW`

**Modify (services):**
- `agent-control-tower/act-execution/.../dod/DoDService.java` + `DoDController.java` + `dto/InitDoDRequest.java` + `dto/SubmitReviewRequest.java` — custom stages + optional verdict
- `agent-control-tower/act-execution/.../listener/WorkflowAutoChainer.java` — kind routing
- `agent-control-tower/act-agent/.../service/WorkflowService.java` — `rescheduleStep`, `cancelWorkflow(WAITING)`, `findChainByRunId` scope, kind helpers
- `agent-control-tower/act-agent/.../dto/CreateWorkflowRequest.java` — `StepDef.kind`
- `agent-control-tower/act-knowledge/.../converter/WorkflowTemplateConverter.java` — kind parse + emit
- `agent-control-tower/act-knowledge/.../service/WorkflowTemplateService.java` — instantiation wiring (DoD + kanban + kinds)
- `agent-control-tower/act-execution/.../controller/ApprovalController.java` — expose content fields
- `agent-control-tower/act-execution/.../approval/ApprovalExpiryChecker.java` — expire `SPEC_REVIEW`
- `agent-control-tower/act-dashboard-api/.../listener/EventBroadcastListener.java` — null-safe toolCallId + approvalType

**Create:**
- `agent-control-tower/act-execution/src/main/java/io/aria/conductor/execution/sdd/SpecReviewCoordinator.java`
- `agent-control-tower/act-app/src/main/resources/db/migration/V40__sdd_workflow.sql`
- `agent-control-tower/act-dashboard/src/components/MarkdownViewer.tsx`
- `agent-control-tower/act-dashboard/e2e/sdd-workflow.spec.ts`

**Modify (frontend):**
- `agent-control-tower/act-dashboard/src/types/index.ts`
- `agent-control-tower/act-dashboard/src/pages/ApprovalsPage.tsx`
- `agent-control-tower/act-dashboard/src/pages/WorkflowsPage.tsx`
- `agent-control-tower/act-dashboard/src/pages/KnowledgePage.tsx`
- `agent-control-tower/act-dashboard/src/api/approvals.ts` (types only)

**Test files:** unit tests next to each service, `act-execution/src/test/.../sdd/SpecReviewCoordinatorTest.java`, `act-execution/src/test/.../listener/WorkflowAutoChainerSddTest.java`, integration tests under `act-app/src/test/.../sdd/` extending `IntegrationTestBase`.

**Build/test commands (run from `agent-control-tower/`):**
- Single module: `mvn test -pl act-execution` / `-pl act-agent` / `-pl act-knowledge` / `-pl act-app`
- All Java: `mvn clean test -Dspring.profiles.active=h2`
- Frontend: `cd act-dashboard && pnpm build` and `npx playwright test`

---

## Task 1: Schema & contract foundation (contract freeze)

**Files:**
- Create: `agent-control-tower/act-app/src/main/resources/db/migration/V40__sdd_workflow.sql`
- Modify: `WorkflowStep.java`, `WorkflowChain.java`, `Approval.java`, `KnowledgeType.java`, `DoDRecord.java`, `DoDStageReview.java`, `KanbanStatus.java`, `ApprovalRequestedEvent.java`, `ApprovalDecidedEvent.java`, `act-dashboard/src/types/index.ts`
- Test: `agent-control-tower/act-execution/src/test/java/io/aria/conductor/execution/sdd/SddContractTest.java`

- [ ] **Step 1: Write the contract test (RED)**

`agent-control-tower/act-execution/src/test/java/io/aria/conductor/execution/sdd/SddContractTest.java`:

```java
package io.aria.conductor.execution.sdd;

import io.aria.conductor.common.model.*;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SddContractTest {

    @Test
    void workflowStepHasKindAndAttemptCountDefaults() {
        WorkflowStep step = WorkflowStep.builder().build();
        assertThat(step.getKind()).isEqualTo(WorkflowStep.StepKind.GENERIC);
        assertThat(step.getAttemptCount()).isZero();
        assertThat(WorkflowStep.StepKind.values()).containsExactly(
                WorkflowStep.StepKind.GENERIC, WorkflowStep.StepKind.BA,
                WorkflowStep.StepKind.DEV, WorkflowStep.StepKind.QA,
                WorkflowStep.StepKind.CODE_REVIEW);
    }

    @Test
    void workflowChainSupportsWaitingApprovalAndReportLink() {
        assertThat(WorkflowChain.Status.values()).contains(WorkflowChain.Status.WAITING_APPROVAL);
        WorkflowChain chain = WorkflowChain.builder().build();
        assertThat(chain.getReportArtifactId()).isNull();
    }

    @Test
    void approvalCarriesTypeContentAndKnowledgeLink() {
        assertThat(Approval.ApprovalType.values())
                .containsExactly(Approval.ApprovalType.TOOL_CALL, Approval.ApprovalType.SPEC_REVIEW);
        assertThat(Approval.ContentKind.values())
                .containsExactly(Approval.ContentKind.MARKDOWN, Approval.ContentKind.HTML);
        Approval a = Approval.builder().build();
        assertThat(a.getApprovalType()).isEqualTo(Approval.ApprovalType.TOOL_CALL);
        assertThat(a.getContent()).isNull();
        assertThat(a.getKnowledgeItemId()).isNull();
    }

    @Test
    void knowledgeTypeHasSpecAndKanbanHasReview() {
        assertThat(KnowledgeType.values()).contains(KnowledgeType.SPEC);
        assertThat(io.aria.conductor.execution.kanban.KanbanStatus.values())
                .contains(io.aria.conductor.execution.kanban.KanbanStatus.REVIEW);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd agent-control-tower && mvn test -pl act-execution -Dtest=SddContractTest`
Expected: COMPILATION ERROR (fields/enums missing) — this is the RED anchor for the contract.

- [ ] **Step 3: Create V40 migration**

`agent-control-tower/act-app/src/main/resources/db/migration/V40__sdd_workflow.sql`:

```sql
-- V40: Spec-Driven Development workflow support.
-- Adds step-kind routing fields, DoD custom stages, verdict reviews,
-- SPEC_REVIEW approval content, and the seeded development-workflow template.

ALTER TABLE dod_stage_reviews ADD COLUMN verdict VARCHAR(20);
ALTER TABLE dod_records ADD COLUMN stages_json TEXT;
ALTER TABLE approvals ADD COLUMN approval_type VARCHAR(20) NOT NULL DEFAULT 'TOOL_CALL';
ALTER TABLE approvals ADD COLUMN content TEXT;
ALTER TABLE approvals ADD COLUMN content_kind VARCHAR(20);
ALTER TABLE approvals ADD COLUMN knowledge_item_id UUID;
ALTER TABLE workflow_chains ADD COLUMN report_artifact_id UUID;

-- Seed the development-workflow template (WORKFLOW knowledge item + version with YAML).
INSERT INTO knowledge_items (id, name, type, description, status, sensitivity, current_version, created_at, escalation_count) VALUES
('d0000001-0000-0000-0000-000000000001', 'development-workflow', 'WORKFLOW',
 'Spec-driven development loop: BA -> spec approval -> Dev -> QA.', 'APPROVED', 'INTERNAL', 'v1.0.0', CURRENT_TIMESTAMP, 0);

INSERT INTO knowledge_versions (id, knowledge_item_id, version, status, content, yaml_content, created_at, approved_at) VALUES
('d0000002-0000-0000-0000-000000000001', 'd0000001-0000-0000-0000-000000000001', 'v1.0.0', 'APPROVED',
 'Spec-driven development loop template.',
'steps:
  - kind: ba
    agent_role: ba
    prompt_template: "Analyze issue {issueRef} and write a spec. End your output with SPEC_ID=<uuid> after approval."
    max_iterations: 6
  - kind: dev
    agent_role: dev
    prompt_template: "Implement per approved spec {specRef}; run unit + integration tests and verify CI before finishing."
    max_iterations: 10
  - kind: qa
    agent_role: qa
    prompt_template: "Verify against spec {specRef}; generate a QA report via generate_report and submit the DoD verdict. End your output with REPORT_ID=<uuid>."
    max_iterations: 6', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);
```

- [ ] **Step 4: Extend `WorkflowStep.java`**

Replace the body of the class fields section in `agent-control-tower/act-common/src/main/java/io/aria/conductor/common/model/WorkflowStep.java`:

```java
public class WorkflowStep {

    public enum Status { PENDING, RUNNING, COMPLETED, FAILED, SKIPPED }

    /** Semantic role of the step; drives SDD routing. Null-safe via @Builder.Default. */
    public enum StepKind { GENERIC, BA, DEV, QA, CODE_REVIEW }

    /** Agent to execute this step. */
    private UUID agentId;

    /** Prompt template; may contain {@code {previousOutput}} and {@code {specRef}} placeholders. */
    private String promptTemplate;

    /** Max LLM iterations for this step's run. */
    @Builder.Default
    private int maxIterations = 3;

    /** Semantic kind for SDD routing. Defaults to GENERIC (existing behaviour). */
    @Builder.Default
    private StepKind kind = StepKind.GENERIC;

    /** Number of times this step has been (re)scheduled. */
    @Builder.Default
    private int attemptCount = 0;

    /** Run ID once this step has been started. */
    private UUID runId;

    /** Current step status. */
    @Builder.Default
    private Status status = Status.PENDING;

    /** The finalOutput from this step's run (populated on completion). */
    private String output;
}
```

- [ ] **Step 5: Extend `WorkflowChain.java`**

In `agent-control-tower/act-common/src/main/java/io/aria/conductor/common/model/WorkflowChain.java`, change the status enum and add the report link:

```java
    public enum Status { PENDING, RUNNING, WAITING_APPROVAL, COMPLETED, FAILED, CANCELLED }
```

Add field after `description`:

```java
    @Column(name = "report_artifact_id", columnDefinition = "UUID")
    private UUID reportArtifactId;
```

- [ ] **Step 6: Extend `Approval.java`**

In `agent-control-tower/act-common/src/main/java/io/aria/conductor/common/model/Approval.java`, add enums and fields:

```java
    public enum ApprovalType { TOOL_CALL, SPEC_REVIEW }
    public enum ContentKind { MARKDOWN, HTML }

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "approval_type", nullable = false, length = 20)
    private ApprovalType approvalType = ApprovalType.TOOL_CALL;

    @Column(columnDefinition = "TEXT")
    private String content;

    @Enumerated(EnumType.STRING)
    @Column(name = "content_kind", length = 20)
    private ContentKind contentKind;

    @Column(name = "knowledge_item_id", columnDefinition = "UUID")
    private UUID knowledgeItemId;
```

- [ ] **Step 7: Add `SPEC` to `KnowledgeType.java`**

```java
public enum KnowledgeType {
    SKILL, SCRIPT, PROMPT, TOOL, TEMPLATE, GUIDELINE, WORKFLOW, SPEC
}
```

- [ ] **Step 8: Add `verdict` to `DoDStageReview.java`**

In `agent-control-tower/act-execution/src/main/java/io/aria/conductor/execution/dod/DoDStageReview.java`, add after `passed`:

```java
    /** SDD verdict for the qa stage: PASS / DEFECT / SPEC_GAP. Null for non-SDD reviews. */
    @Column(length = 20)
    private String verdict;
```

- [ ] **Step 9: Add `stagesJson` to `DoDRecord.java`**

In `DoDRecord.java`, add after `overallStatus`:

```java
    /** Optional custom stage list (JSON array). Null = use DoDService.DEFAULT_STAGES. */
    @Column(name = "stages_json", columnDefinition = "TEXT")
    private String stagesJson;
```

- [ ] **Step 10: Add `REVIEW` to `KanbanStatus.java`**

Locate `agent-control-tower/act-execution/src/main/java/io/aria/conductor/execution/kanban/KanbanStatus.java` and append `REVIEW` to the enum values.

- [ ] **Step 11: Add optional `approvalType` to approval events**

In `agent-control-tower/act-common/src/main/java/io/aria/conductor/common/event/ApprovalRequestedEvent.java` and `ApprovalDecidedEvent.java`, add a nullable `approvalType` field with a backward-compatible constructor that defaults it to `"TOOL_CALL"`:

```java
// In ApprovalRequestedEvent — add field + getter, keep old ctor delegating with "TOOL_CALL"
private final String approvalType;

public ApprovalRequestedEvent(Object source, UUID approvalId, UUID runId, UUID toolCallId) {
    this(source, approvalId, runId, toolCallId, "TOOL_CALL");
}

public ApprovalRequestedEvent(Object source, UUID approvalId, UUID runId, UUID toolCallId, String approvalType) {
    super(source);
    this.approvalId = approvalId;
    this.runId = runId;
    this.toolCallId = toolCallId;
    this.approvalType = approvalType;
}
```

(Do the analogous two-constructor addition to `ApprovalDecidedEvent`.)

- [ ] **Step 12: Update frontend types**

In `agent-control-tower/act-dashboard/src/types/index.ts`:

```typescript
export type ApprovalStatus = 'PENDING' | 'APPROVED' | 'DENIED' | 'EXPIRED';
export type ApprovalType = 'TOOL_CALL' | 'SPEC_REVIEW';
export type ContentKind = 'MARKDOWN' | 'HTML';
export type KnowledgeType = 'SKILL' | 'SCRIPT' | 'PROMPT' | 'TOOL' | 'TEMPLATE' | 'GUIDELINE' | 'WORKFLOW' | 'SPEC';
export type WorkflowStatus = 'PENDING' | 'RUNNING' | 'WAITING_APPROVAL' | 'COMPLETED' | 'FAILED' | 'CANCELLED';
```

Update the `Approval` interface:

```typescript
export interface Approval {
  id: string;
  runId: string;
  toolCallId: string | null;
  status: ApprovalStatus;
  reason: string;
  requestedAt: string;
  decidedAt: string | null;
  expiresAt: string;
  approvalType?: ApprovalType;
  content?: string;
  contentKind?: ContentKind;
  knowledgeItemId?: string;
  toolName?: string;
  arguments?: string;
  riskTier?: string;
}
```

- [ ] **Step 13: Run contract test to verify it passes + full compile**

Run: `cd agent-control-tower && mvn test -pl act-execution -Dtest=SddContractTest`
Expected: PASS. Then verify entities match migration: `cd agent-control-tower && mvn verify -pl act-app`
Expected: BUILD SUCCESS, app context boots (Flyway applies V40; `ddl-auto` default profile validates).

- [ ] **Step 14: Commit**

```bash
git add agent-control-tower/act-app/src/main/resources/db/migration/V40__sdd_workflow.sql \
  agent-control-tower/act-common/src/main/java/io/aria/conductor/common/model/WorkflowStep.java \
  agent-control-tower/act-common/src/main/java/io/aria/conductor/common/model/WorkflowChain.java \
  agent-control-tower/act-common/src/main/java/io/aria/conductor/common/model/Approval.java \
  agent-control-tower/act-common/src/main/java/io/aria/conductor/common/model/KnowledgeType.java \
  agent-control-tower/act-common/src/main/java/io/aria/conductor/common/event/ApprovalRequestedEvent.java \
  agent-control-tower/act-common/src/main/java/io/aria/conductor/common/event/ApprovalDecidedEvent.java \
  agent-control-tower/act-execution/src/main/java/io/aria/conductor/execution/dod/DoDStageReview.java \
  agent-control-tower/act-execution/src/main/java/io/aria/conductor/execution/dod/DoDRecord.java \
  agent-control-tower/act-execution/src/main/java/io/aria/conductor/execution/kanban/KanbanStatus.java \
  agent-control-tower/act-execution/src/test/java/io/aria/conductor/execution/sdd/SddContractTest.java \
  agent-control-tower/act-dashboard/src/types/index.ts
git commit -m "feat(sdd): V40 schema + contract enums (step kind, WAITING_APPROVAL, SPEC_REVIEW approval, DoD stages/verdict)"
```

---

## Task 2: Phase 1 E2E / contract test (RED anchor)

**Files:**
- Create: `agent-control-tower/act-dashboard/e2e/sdd-workflow.spec.ts`

This test is written FIRST so it fails until the full backend + frontend wiring exists. It drives the loop over the REST API and asserts the Approvals page renders the SPEC_REVIEW card.

- [ ] **Step 1: Write the failing E2E spec**

`agent-control-tower/act-dashboard/e2e/sdd-workflow.spec.ts`:

```typescript
import { test, expect } from '@playwright/test';
import { API_URL, pollUntil } from './api/helpers'; // existing parameterised contract

test('development-workflow: spec approval then PASS verdict completes the chain', async ({ page, request }) => {
  // 1. Instantiate the seeded development-workflow template.
  const templates = await request.get(`${API_URL}/api/v1/knowledge?type=WORKFLOW&status=APPROVED`);
  const tpl = (await templates.json()).find((k: any) => k.name === 'development-workflow');
  expect(tpl).toBeTruthy();

  const inst = await request.post(`${API_URL}/api/v1/knowledge/${tpl.id}/instantiate-workflow`, {
    data: { parameters: { issueRef: '#1-test' } },
  });
  expect(inst.ok()).toBeTruthy();
  const chain = await inst.json();

  // 2. Poll until the chain enters WAITING_APPROVAL with a SPEC_REVIEW approval.
  const approval = await pollUntil(async () => {
    const list = await (await request.get(`${API_URL}/api/v1/approvals`)).json();
    return list.find((a: any) => a.approvalType === 'SPEC_REVIEW' && a.status === 'PENDING');
  }, 30_000);
  expect(approval.content).toContain('#');
  expect(approval.knowledgeItemId).toBeTruthy();
  expect(approval.toolCallId).toBeNull();

  // 3. Approvals page renders the card without crashing (null toolCallId) and shows markdown.
  await page.goto('/approvals');
  await expect(page.getByText('SPEC_REVIEW')).toBeVisible();
  await expect(page.locator('.spec-review-markdown')).toBeVisible();

  // 4. Approve -> chain resumes.
  await request.post(`${API_URL}/api/v1/approvals/${approval.id}/decide`, { data: { approved: true, reason: 'lgtm' } });

  // 5. Poll until the chain completes.
  await pollUntil(async () => {
    const wf = await (await request.get(`${API_URL}/api/v1/workflows/${chain.id}`)).json();
    return wf.status === 'COMPLETED' ? wf : null;
  }, 120_000);
});

test('development-workflow: resubmit-approval recreates an EXPIRED approval', async ({ request }) => {
  const list = await request.get(`${API_URL}/api/v1/workflows`);
  const waiting = (await list.json()).find((w: any) => w.status === 'WAITING_APPROVAL');
  test.skip(!waiting, 'requires a WAITING_APPROVAL chain (fixture-dependent)');
  const res = await request.post(`${API_URL}/api/v1/workflows/${waiting.id}/resubmit-approval`);
  expect(res.ok()).toBeTruthy();
});
```

> If `e2e/api/helpers.ts` does not already export `API_URL`/`pollUntil`, add minimal equivalents beside the existing API_URL-parameterised helper (mirror `workflow-governance.spec.ts`).

- [ ] **Step 2: Run the E2E to verify it fails**

Run: `cd agent-control-tower/act-dashboard && npx playwright test e2e/sdd-workflow.spec.ts`
Expected: FAIL - SPEC_REVIEW approvals never appear (feature not implemented). Leave failing until Tasks 3-10 land.

- [ ] **Step 3: Commit**

```bash
git add agent-control-tower/act-dashboard/e2e/sdd-workflow.spec.ts
git commit -m "test(sdd): RED E2E anchor for the development-workflow loop"
```

---

## Task 3: DoD custom stages + verdict recording

**Files:**
- Modify: `act-execution/.../dod/DoDService.java`, `DoDController.java`, `dto/InitDoDRequest.java`, `dto/SubmitReviewRequest.java`
- Modify: `act-execution/.../dod/DoDStageReviewRepository.java` (add stage query)
- Test: `act-execution/src/test/java/io/aria/conductor/execution/dod/DoDServiceSddTest.java`

- [ ] **Step 1: Write the failing test**

`DoDServiceSddTest.java` (extend the existing `DoDServiceTest` mock setup - the three repositories):

```java
@Test
void init_withCustomStages_setsCurrentStageToFirstCustomStage() {
    DoDRecord record = dodService.init("chain-1", "SDD", List.of("dev", "qa"));
    assertThat(record.getCurrentStage()).isEqualTo("dev");
}

@Test
void init_withoutStages_keepsDefaultStages() {
    DoDRecord record = dodService.init("chain-2", "TASK");
    assertThat(record.getStagesJson()).isNull();
    assertThat(record.getCurrentStage()).isEqualTo("dev");
}

@Test
void review_recordsVerdictAndAdvancesThroughCustomStages() {
    DoDRecord record = dodService.init("chain-3", "SDD", List.of("dev", "qa"));
    stubFind(record);
    dodService.review("chain-3", "eng", "Engine", true, null, null, null); // dev passed
    assertThat(record.getCurrentStage()).isEqualTo("qa");
    dodService.review("chain-3", "qa", "QA", true, null, null, "PASS");   // qa passed
    assertThat(record.getOverallStatus()).isEqualTo(DoDService.STATUS_PASSED);
}

@Test
void review_withNullVerdict_isStillRecorded() {
    DoDRecord record = dodService.init("chain-4", "TASK");
    stubFind(record);
    dodService.review("chain-4", "u", "U", true, null, "c", null);
    verify(reviewRepository).save(argThat(rv -> rv.getVerdict() == null));
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd agent-control-tower && mvn test -pl act-execution -Dtest=DoDServiceSddTest`
Expected: FAIL (overloaded `init`/`review` signatures missing).

- [ ] **Step 3: Extend `DoDService.java`**

Add an overloaded `init`, an overloaded `review` with `verdict`, a stage-list resolver, and an engine hook. Replace `advance`/`computeStageRollup` to use `stagesOf`:

```java
    /** Idempotent init with default stages. */
    @Transactional
    public DoDRecord init(String taskId, String taskType) { return init(taskId, taskType, null); }

    /** Idempotent init; optional custom stage list (null = DEFAULT_STAGES). */
    @Transactional
    public DoDRecord init(String taskId, String taskType, List<String> stages) {
        Objects.requireNonNull(taskId, "taskId is required");
        return dodRepository.findByTaskId(taskId)
                .orElseGet(() -> {
                    List<String> effective = (stages == null || stages.isEmpty()) ? DEFAULT_STAGES : stages;
                    DoDRecord record = DoDRecord.builder()
                            .id(UUID.randomUUID().toString())
                            .taskId(taskId)
                            .taskType(taskType)
                            .currentStage(effective.get(0))
                            .overallStatus(STATUS_IN_PROGRESS)
                            .stagesJson(stages != null ? toJson(stages) : null)
                            .build();
                    DoDRecord saved = dodRepository.save(record);
                    log.info("DoD initialized: taskId={} firstStage={} stages={}", taskId, saved.getCurrentStage(), effective);
                    return saved;
                });
    }

    /** Backward-compatible review (no verdict). */
    @Transactional
    public DoDRecord review(String taskId, String reviewerId, String reviewerName,
                            boolean passed, String evidence, String comment) {
        return review(taskId, reviewerId, reviewerName, passed, evidence, comment, null);
    }

    /** Review with optional SDD verdict (purely recorded; never globally validated). */
    @Transactional
    public DoDRecord review(String taskId, String reviewerId, String reviewerName,
                            boolean passed, String evidence, String comment, String verdict) {
        // body identical to the existing review(), but the built DoDStageReview
        // gets .verdict(verdict), and stage progression uses stagesOf(record).
    }

    /** Engine hook: submit a stage review programmatically (DEV-step auto-submit). */
    @Transactional
    public DoDRecord submitStageReview(String taskId, String reviewerId, String reviewerName,
                                       boolean passed, String comment) {
        return review(taskId, reviewerId, reviewerName, passed, null, comment, null);
    }

    private List<String> stagesOf(DoDRecord record) {
        if (record.getStagesJson() == null || record.getStagesJson().isBlank()) return DEFAULT_STAGES;
        return fromJson(record.getStagesJson());
    }
```

Add a private static `ObjectMapper MAPPER = new ObjectMapper();` and `toJson(List<String>)`/`fromJson(String)` helpers.

- [ ] **Step 4: Add the stage query to `DoDStageReviewRepository`**

```java
    List<DoDStageReview> findByDodIdAndStageOrderByReviewedAtDesc(String dodId, String stage);
```

- [ ] **Step 5: Update DTOs + controller**

`dto/InitDoDRequest.java`:

```java
public record InitDoDRequest(@NotBlank String taskId, String taskType, List<String> stages) {}
```

`dto/SubmitReviewRequest.java`:

```java
public record SubmitReviewRequest(
        @NotBlank String taskId,
        @NotBlank String reviewerId,
        String reviewerName,
        boolean passed,
        String evidence,
        String comment,
        String verdict
) {}
```

`DoDController.java` - thread the new fields through:

```java
    @PostMapping("/init")
    public ResponseEntity<DoDRecord> init(@Valid @RequestBody InitDoDRequest request) {
        DoDRecord record = dodService.init(request.taskId(), request.taskType(), request.stages());
        return ResponseEntity.ok(record);
    }
    // in /review: dodService.review(request.taskId(), request.reviewerId(), request.reviewerName(),
    //                               request.passed(), request.evidence(), request.comment(), request.verdict());
```

- [ ] **Step 6: Run tests GREEN + existing DoD tests stay green**

Run: `cd agent-control-tower && mvn test -pl act-execution -Dtest=DoDServiceSddTest,DoDServiceTest`
Expected: PASS. The 6-arg `review` overload keeps existing callers unaffected (they never pass a verdict).

- [ ] **Step 7: Commit**

```bash
git add agent-control-tower/act-execution/src/main/java/io/aria/conductor/execution/dod/
git add agent-control-tower/act-execution/src/test/java/io/aria/conductor/execution/dod/DoDServiceSddTest.java
git commit -m "feat(sdd): DoD custom stages + optional verdict recording (no global validation)"
```

---

## Task 4: Template converter + StepDef kind propagation

**Files:**
- Modify: `act-knowledge/.../converter/WorkflowTemplateConverter.java`
- Modify: `act-agent/.../dto/CreateWorkflowRequest.java` (StepDef)
- Modify: `act-agent/.../service/WorkflowService.java` + `act-knowledge/.../service/WorkflowTemplateService.java` (propagate kind)
- Test: `act-knowledge/src/test/java/io/aria/conductor/knowledge/converter/WorkflowTemplateConverterKindTest.java`

- [ ] **Step 1: Write the failing test**

```java
@Test
void yamlToWorkflowSteps_parsesKindCaseInsensitive_andDefaultsGeneric() {
    String yaml = "steps:\n  - kind: ba\n    agent_role: ba\n    prompt_template: \"write spec\"\n"
            + "  - agent_role: dev\n    prompt_template: \"implement\"\n";
    List<WorkflowStep> steps = converter.yamlToWorkflowSteps(yaml);
    assertThat(steps.get(0).getKind()).isEqualTo(WorkflowStep.StepKind.BA);
    assertThat(steps.get(1).getKind()).isEqualTo(WorkflowStep.StepKind.GENERIC);
}

@Test
void workflowChainToYaml_emitsKind() {
    WorkflowStep ba = WorkflowStep.builder().agentId(UUID.randomUUID())
            .kind(WorkflowStep.StepKind.BA).promptTemplate("p").build();
    String yaml = converter.workflowChainToYaml(chain("wf"), List.of(ba), null);
    assertThat(yaml).contains("kind: BA");
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd agent-control-tower && mvn test -pl act-knowledge -Dtest=WorkflowTemplateConverterKindTest`
Expected: FAIL (kind not parsed/emitted).

- [ ] **Step 3: Implement kind parsing**

In `yamlToWorkflowSteps`, after `step.setPromptTemplate(...)` add:

```java
            // SDD step kind (case-normalised; unknown/missing -> GENERIC).
            step.setKind(parseKind(getStringValue(raw, "kind")));
```

Add the helper near `getStringValue`:

```java
    /** Parse a step kind with case normalisation; null/unknown -> GENERIC. */
    static WorkflowStep.StepKind parseKind(String raw) {
        if (raw == null || raw.isBlank()) return WorkflowStep.StepKind.GENERIC;
        try {
            return WorkflowStep.StepKind.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            log.warn("Unknown workflow step kind '{}', defaulting to GENERIC", raw);
            return WorkflowStep.StepKind.GENERIC;
        }
    }
```

- [ ] **Step 4: Implement kind emission**

In `workflowChainToYaml`, inside the per-step map build, after `ys.put("max_iterations", step.getMaxIterations());` add:

```java
                if (step.getKind() != null && step.getKind() != WorkflowStep.StepKind.GENERIC) {
                    ys.put("kind", step.getKind().name());
                }
```

- [ ] **Step 5: Add `kind` to `CreateWorkflowRequest.StepDef` and propagate**

In `act-agent/.../dto/CreateWorkflowRequest.java`, add to the nested `StepDef` builder class:

```java
        private WorkflowStep.StepKind kind;
```

In `WorkflowService.createAndStart`'s step-mapping, add `.kind(s.getKind() != null ? s.getKind() : WorkflowStep.StepKind.GENERIC)`. In `WorkflowTemplateService.instantiateTemplate`'s `StepDef` mapping, add `.kind(s.getKind())`.

- [ ] **Step 6: Run tests GREEN + existing converter/workflow tests green**

Run: `cd agent-control-tower && mvn test -pl act-knowledge -Dtest=WorkflowTemplateConverterKindTest,WorkflowTemplateServiceTest && mvn test -pl act-agent -Dtest=WorkflowServiceExistingTest`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add agent-control-tower/act-knowledge/src/main/java/io/aria/conductor/knowledge/converter/WorkflowTemplateConverter.java \
  agent-control-tower/act-knowledge/src/main/java/io/aria/conductor/knowledge/service/WorkflowTemplateService.java \
  agent-control-tower/act-agent/src/main/java/io/aria/conductor/agent/dto/CreateWorkflowRequest.java \
  agent-control-tower/act-agent/src/main/java/io/aria/conductor/agent/service/WorkflowService.java \
  agent-control-tower/act-knowledge/src/test/java/io/aria/conductor/knowledge/converter/WorkflowTemplateConverterKindTest.java
git commit -m "feat(sdd): workflow step kind (YAML parse/emit + StepDef propagation)"
```

---

## Task 5: WorkflowService rescheduleStep + cancelWorkflow(WAITING) + chain lookup scope

**Files:**
- Modify: `act-agent/.../service/WorkflowService.java`
- Modify: `act-common/.../repository/WorkflowChainRepository.java` (add status query)
- Test: `act-agent/src/test/java/io/aria/conductor/agent/service/WorkflowServiceSddTest.java`

- [ ] **Step 1: Write the failing test**

`WorkflowServiceSddTest.java` (mirror `WorkflowServiceExistingTest` mocks):

```java
@Test
void rescheduleStep_incrementsAttempt_appendsFeedback_startsNewRun() {
    // chain with a DEV step at index 1, attemptCount 0
    when(workflowChainRepository.findById(chain.getId())).thenReturn(Optional.of(chain));
    when(runService.createRun(any())).thenReturn(RunResponse.builder().id(UUID.randomUUID()).build());

    workflowService.rescheduleStep(chain.getId(), 1, "QA found a defect: off-by-one");

    List<WorkflowStep> steps = workflowService.deserializeSteps(chain.getStepsJson());
    assertThat(steps.get(1).getAttemptCount()).isEqualTo(1);
    assertThat(steps.get(1).getPromptTemplate()).contains("QA found a defect: off-by-one");
    verify(runService).createRun(any());
}

@Test
void rescheduleStep_exceedingMaxAttempts_failsChain() {
    // DEV step already at attemptCount == maxAttempts (3)
    workflowService.rescheduleStep(chain.getId(), 1, "still broken");
    assertThat(chain.getStatus()).isEqualTo(WorkflowChain.Status.FAILED);
    verify(runService, never()).createRun(any());
}

@Test
void cancelWorkflow_acceptsWaitingApproval() {
    chain.setStatus(WorkflowChain.Status.WAITING_APPROVAL);
    WorkflowResponse resp = workflowService.cancelWorkflow(chain.getId());
    assertThat(chain.getStatus()).isEqualTo(WorkflowChain.Status.CANCELLED);
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd agent-control-tower && mvn test -pl act-agent -Dtest=WorkflowServiceSddTest`
Expected: FAIL (`rescheduleStep` missing; `cancelWorkflow` throws for WAITING_APPROVAL).

- [ ] **Step 3: Implement `rescheduleStep` + kind helper**

Add to `WorkflowService.java`:

```java
    private static final int DEFAULT_MAX_ATTEMPTS = 3;

    /**
     * Re-run a step within the same chain (SDD DEFECT / SPEC_GAP loop-back).
     * Increments the attempt counter, appends feedback to the prompt, starts a new run.
     * Fails the chain if maxAttempts is exceeded.
     */
    @Transactional
    public WorkflowResponse rescheduleStep(UUID chainId, int stepIndex, String feedback) {
        WorkflowChain chain = workflowChainRepository.findById(chainId)
                .orElseThrow(() -> new ResourceNotFoundException("WorkflowChain", chainId));
        List<WorkflowStep> steps = deserializeSteps(chain.getStepsJson());
        if (stepIndex >= steps.size()) {
            throw new IllegalArgumentException("Step index out of range: " + stepIndex);
        }
        WorkflowStep step = steps.get(stepIndex);
        if (step.getAttemptCount() >= DEFAULT_MAX_ATTEMPTS) {
            chain.setStatus(WorkflowChain.Status.FAILED);
            chain.setCompletedAt(Instant.now());
            chain.setStepsJson(serializeSteps(steps));
            workflowChainRepository.save(chain);
            log.info("SDD loop exhausted: chain={} step={} attempts={}", chainId, stepIndex, step.getAttemptCount());
            return toResponse(chain);
        }
        step.setAttemptCount(step.getAttemptCount() + 1);
        step.setStatus(WorkflowStep.Status.PENDING);
        String base = step.getPromptTemplate() != null ? step.getPromptTemplate() : "";
        String augmented = feedback != null && !feedback.isBlank()
                ? base + "\n\nFeedback from the previous round:\n" + feedback
                : base;
        step.setPromptTemplate(augmented);   // note: startStep truncates at 10,000 chars
        chain.setStepsJson(serializeSteps(steps));
        workflowChainRepository.save(chain);
        startStep(chain, stepIndex, null);
        log.info("SDD step rescheduled: chain={} step={} attempt={}", chainId, stepIndex, step.getAttemptCount());
        return toResponse(chain);
    }

    /** Find the index of the first step with the given kind, or -1. */
    public int findStepIndexByKind(WorkflowChain chain, WorkflowStep.StepKind kind) {
        List<WorkflowStep> steps = deserializeSteps(chain.getStepsJson());
        for (int i = 0; i < steps.size(); i++) {
            if (steps.get(i).getKind() == kind) return i;
        }
        return -1;
    }
```

- [ ] **Step 4: Extend `cancelWorkflow` to accept WAITING_APPROVAL**

In `cancelWorkflow`, change the guard from:

```java
        if (chain.getStatus() != WorkflowChain.Status.RUNNING
                && chain.getStatus() != WorkflowChain.Status.PENDING) {
            throw new IllegalArgumentException(...);
        }
```

to also permit `WorkflowChain.Status.WAITING_APPROVAL`:

```java
        if (chain.getStatus() != WorkflowChain.Status.RUNNING
                && chain.getStatus() != WorkflowChain.Status.PENDING
                && chain.getStatus() != WorkflowChain.Status.WAITING_APPROVAL) {
            throw new IllegalArgumentException(...);
        }
```

- [ ] **Step 5: Extend `findChainByRunId` to include WAITING_APPROVAL**

In `findChainByRunId`, add the WAITING_APPROVAL scan:

```java
        List<WorkflowChain> activeChains = workflowChainRepository.findByStatus(WorkflowChain.Status.RUNNING);
        activeChains.addAll(workflowChainRepository.findByStatus(WorkflowChain.Status.PENDING));
        activeChains.addAll(workflowChainRepository.findByStatus(WorkflowChain.Status.WAITING_APPROVAL));
```

Ensure `WorkflowChainRepository` declares `List<WorkflowChain> findByStatus(WorkflowChain.Status status);` (it already does, since the first two calls exist).

- [ ] **Step 6: Run tests GREEN + existing workflow tests green**

Run: `cd agent-control-tower && mvn test -pl act-agent -Dtest=WorkflowServiceSddTest,WorkflowServiceExistingTest,WorkflowServiceLifecycleTest`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add agent-control-tower/act-agent/src/main/java/io/aria/conductor/agent/service/WorkflowService.java \
  agent-control-tower/act-agent/src/test/java/io/aria/conductor/agent/service/WorkflowServiceSddTest.java
git commit -m "feat(sdd): rescheduleStep loop-back + cancelWorkflow(WAITING_APPROVAL) + chain lookup scope"
```

---

## Task 6: WorkflowAutoChainer kind routing (routeStepCompletion)

**Files:**
- Modify: `act-execution/.../listener/WorkflowAutoChainer.java`
- Test: `act-execution/src/test/java/io/aria/conductor/execution/listener/WorkflowAutoChainerSddTest.java`

The chainer remains the ONLY `RunCompletedEvent` listener. It dispatches by step kind: BA hands off to the coordinator (no advance), DEV auto-submits the dev-stage DoD review then advances, QA routes on verdict, GENERIC/CODE_REVIEW keep the existing advance behaviour.

- [ ] **Step 1: Write the failing 9-case routing test**

`WorkflowAutoChainerSddTest.java` — mocks: `WorkflowService`, `DoDService`, `SpecReviewCoordinator`, `ApplicationEventPublisher`. Build chains with `TestDataBuilder.aWorkflowChain()` and steps carrying `kind`.

```java
@Test void baCompletion_publishesBaStepCompletedEvent_doesNotAdvance() { /* kind=BA -> verify(eventPublisher).publishEvent(any(BaStepCompletedEvent.class)); verify(workflowService, never()).advanceWorkflow(...) */ }
@Test void devCompletion_whenDoDAtDev_submitsDevReviewAndAdvances() { /* kind=DEV, record.currentStage=dev -> verify(dodService).submitStageReview(taskId, .., true, ..); verify(workflowService).advanceWorkflow(...) */ }
@Test void devCompletion_whenDoDAtQa_skipsDevReviewAndAdvances() { /* DEFECT-rework: currentStage=qa -> verify(dodService, never()).submitStageReview(...) */ }
@Test void qaCompletion_verdictPass_advances() { /* verdict=PASS -> advanceWorkflow */ }
@Test void qaCompletion_verdictDefect_reschedulesDevStep() { /* verdict=DEFECT -> rescheduleStep(chain, devIdx, feedback) */ }
@Test void qaCompletion_verdictSpecGap_reschedulesBaStep() { /* verdict=SPEC_GAP -> rescheduleStep(chain, baIdx, feedback) */ }
@Test void qaCompletion_noVerdict_failsChain() { /* no qa-stage review -> markStepFailed("QA completed but no verdict") */ }
@Test void genericCompletion_advancesUnchanged() { /* kind=null/GENERIC -> advanceWorkflow (existing behaviour) */ }
@Test void failedRun_marksStepFailed_unchanged() { /* FAILED status -> markStepFailed (existing) */ }
```

(Expand each into a full test with `when(...)`/`verify(...)`, mirroring the structure of the existing `WorkflowAutoChainerTest`.)

- [ ] **Step 2: Run test to verify it fails**

Run: `cd agent-control-tower && mvn test -pl act-execution -Dtest=WorkflowAutoChainerSddTest`
Expected: FAIL (routing not implemented; coordinator/DoD not wired).

- [ ] **Step 3: Implement routing in `WorkflowAutoChainer.java`**

Add dependencies to the constructor: `DoDService dodService`, `WorkflowChainRepository chainRepository` (keep `WorkflowService`, `ApplicationEventPublisher`). The chainer does NOT depend on the coordinator (it lives in act-knowledge); BA completion is signalled via a `BaStepCompletedEvent` (defined in act-common) so the coordinator listens without a compile-time cycle. Replace the success branch of `onRunCompleted` with a call to `routeStepCompletion`:

```java
            // Replace the existing advanceWorkflow(...) success branch with:
            routeStepCompletion(chain, stepIndex, event.getFinalOutput());
```

Add the routing method:

```java
    /**
     * Kind-aware routing for a completed step. GENERIC/CODE_REVIEW/null keep the existing
     * linear advance; BA hands off to the spec coordinator; DEV advances the DoD dev stage;
     * QA routes on its recorded verdict. All branches are guarded by chain-status preconditions.
     */
    private void routeStepCompletion(WorkflowChain chain, int stepIndex, String finalOutput) {
        WorkflowStep step = workflowService.stepAt(chain, stepIndex);
        WorkflowStep.StepKind kind = step != null && step.getKind() != null
                ? step.getKind() : WorkflowStep.StepKind.GENERIC;

        switch (kind) {
            case BA -> {
                // Signal the coordinator (act-knowledge) via a domain event; do NOT advance here.
                eventPublisher.publishEvent(new BaStepCompletedEvent(
                        this, chain.getId(), stepIndex, step.getRunId(), finalOutput));
                return; // chain stays RUNNING until the coordinator moves it to WAITING_APPROVAL
            }
            case DEV -> {
                autoSubmitDevStageReviewIfAtDev(chain);
                boolean started = workflowService.advanceWorkflow(chain.getId(), stepIndex, finalOutput);
                publishAdvanced(chain, stepIndex, started);
            }
            case QA -> routeOnQaVerdict(chain, stepIndex, finalOutput);
            default -> {
                boolean started = workflowService.advanceWorkflow(chain.getId(), stepIndex, finalOutput);
                publishAdvanced(chain, stepIndex, started);
            }
        }
    }

    private void autoSubmitDevStageReviewIfAtDev(WorkflowChain chain) {
        try {
            DoDRecord record = dodService.getStatus(chain.getId().toString());
            if (record != null && "dev".equals(record.getCurrentStage())) {
                dodService.submitStageReview(chain.getId().toString(), "engine", "SDD Engine",
                        true, "auto: dev step completed");
            }
        } catch (IllegalStateException e) {
            log.debug("No DoD record for chain {} (non-SDD chain); skipping dev review", chain.getId());
        }
    }

    private void routeOnQaVerdict(WorkflowChain chain, int stepIndex, String finalOutput) {
        DoDRecord record = dodService.getStatus(chain.getId().toString());
        DoDStageReview latest = dodService.latestQaReview(record);
        if (latest == null || latest.getVerdict() == null) {
            workflowService.markStepFailed(chain.getId(), stepIndex,
                    "QA completed but no verdict submitted");
            return;
        }
        String verdict = latest.getVerdict().toUpperCase();
        switch (verdict) {
            case "PASS" -> {
                storeQaReportIdIfPresent(chain, finalOutput);
                boolean started = workflowService.advanceWorkflow(chain.getId(), stepIndex, finalOutput);
                publishAdvanced(chain, stepIndex, started);
            }
            case "DEFECT" -> {
                int devIdx = workflowService.findStepIndexByKind(chain, WorkflowStep.StepKind.DEV);
                workflowService.rescheduleStep(chain.getId(), devIdx, latest.getComment());
            }
            case "SPEC_GAP" -> {
                int baIdx = workflowService.findStepIndexByKind(chain, WorkflowStep.StepKind.BA);
                workflowService.rescheduleStep(chain.getId(), baIdx, latest.getComment());
            }
            default -> workflowService.markStepFailed(chain.getId(), stepIndex,
                    "Unknown QA verdict: " + latest.getVerdict());
        }
    }

    private void publishAdvanced(WorkflowChain chain, int stepIndex, boolean started) {
        if (started) {
            eventPublisher.publishEvent(new WorkflowAdvancedEvent(
                    this, chain.getId(), chain.getName(), stepIndex, stepIndex + 1, WorkflowChain.Status.RUNNING));
        } else {
            eventPublisher.publishEvent(new WorkflowAdvancedEvent(
                    this, chain.getId(), chain.getName(), stepIndex, -1, WorkflowChain.Status.COMPLETED));
        }
    }
```

Also add to `WorkflowService` a small accessor `stepAt(WorkflowChain chain, int index)` (deserialize + get, null-safe) and to `DoDService` a helper `latestQaReview(DoDRecord record)` that returns `reviewRepository.findByDodIdAndStageOrderByReviewedAtDesc(record.getId(), "qa").stream().findFirst().orElse(null)`.

Add the QA-report capture helper to `WorkflowAutoChainer` (string parse, no cross-module dependency - the chain field is in act-common):

```java
    /** Capture the QA report id from the QA run's finalOutput (convention: REPORT_ID=<uuid>). */
    private void storeQaReportIdIfPresent(WorkflowChain chain, String finalOutput) {
        if (finalOutput == null) return;
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("REPORT_ID=([0-9a-fA-F-]{36})").matcher(finalOutput);
        if (m.find()) {
            try {
                chain.setReportArtifactId(UUID.fromString(m.group(1)));
                chainRepository.save(chain); // persist before advanceWorkflow reloads the chain
            } catch (IllegalArgumentException ignored) { /* malformed id - skip */ }
        }
    }
```

The QA step template (seeded in Task 1) instructs the QA agent to emit `REPORT_ID=<uuid>` after calling `generate_report`.

**New domain event** (act-common, so both modules can share it): create `agent-control-tower/act-common/src/main/java/io/aria/conductor/common/event/BaStepCompletedEvent.java`:

```java
package io.aria.conductor.common.event;

import org.springframework.context.ApplicationEvent;
import java.util.UUID;

/** Published by WorkflowAutoChainer when a BA-kind step completes; consumed by SpecReviewCoordinator. */
public class BaStepCompletedEvent extends ApplicationEvent {
    private final UUID chainId;
    private final int baStepIndex;
    private final UUID baRunId;
    private final String finalOutput;

    public BaStepCompletedEvent(Object source, UUID chainId, int baStepIndex, UUID baRunId, String finalOutput) {
        super(source);
        this.chainId = chainId;
        this.baStepIndex = baStepIndex;
        this.baRunId = baRunId;
        this.finalOutput = finalOutput;
    }

    public UUID getChainId() { return chainId; }
    public int getBaStepIndex() { return baStepIndex; }
    public UUID getBaRunId() { return baRunId; }
    public String getFinalOutput() { return finalOutput; }
}
```

- [ ] **Step 4: Run routing tests GREEN + existing chainer tests green**

Run: `cd agent-control-tower && mvn test -pl act-execution -Dtest=WorkflowAutoChainerSddTest,WorkflowAutoChainerTest`
Expected: PASS. The existing `WorkflowAutoChainerTest` uses GENERIC steps (null kind) so its behaviour is unchanged.

- [ ] **Step 5: Commit**

```bash
git add agent-control-tower/act-common/src/main/java/io/aria/conductor/common/event/BaStepCompletedEvent.java \
  agent-control-tower/act-execution/src/main/java/io/aria/conductor/execution/listener/WorkflowAutoChainer.java \
  agent-control-tower/act-execution/src/main/java/io/aria/conductor/execution/dod/DoDService.java \
  agent-control-tower/act-agent/src/main/java/io/aria/conductor/agent/service/WorkflowService.java \
  agent-control-tower/act-execution/src/test/java/io/aria/conductor/execution/listener/WorkflowAutoChainerSddTest.java
git commit -m "feat(sdd): kind-aware workflow routing (BA->event, DEV->DoD advance, QA->verdict routing)"
```

---

## Task 7: SpecReviewCoordinator (spec knowledge + SPEC_REVIEW approval + resume)

**Files:**
- Create: `act-knowledge/src/main/java/io/aria/conductor/knowledge/sdd/SpecReviewCoordinator.java`
- Modify: `act-knowledge/.../repository/KnowledgeItemRepository.java` (add `findByName`)
- Modify: `act-execution/.../controller/ApprovalController.java` (expose content fields)
- Modify: `act-agent/.../controller/WorkflowController.java` (resubmit-approval endpoint)
- Test: `act-knowledge/src/test/java/io/aria/conductor/knowledge/sdd/SpecReviewCoordinatorTest.java`

The coordinator lives in **act-knowledge** (which already depends on act-agent + act-execution, so it can reach `WorkflowService`, `ApprovalRepository`, and `KnowledgeService` without a cycle). It listens to `BaStepCompletedEvent` and `ApprovalDecidedEvent`.

- [ ] **Step 1: Add repository finder**

In `act-knowledge/.../repository/KnowledgeItemRepository.java`:

```java
    java.util.Optional<io.aria.conductor.common.model.KnowledgeItem> findByName(String name);
```

- [ ] **Step 2: Write the failing test**

`SpecReviewCoordinatorTest.java` — mocks: `KnowledgeService`, `KnowledgeItemRepository`, `ApprovalRepository`, `WorkflowChainRepository`, `WorkflowService`, `ApplicationEventPublisher`.

```java
@Test
void onBaStepCompleted_createsSpecKnowledgeAndApproval_andPausesChain() {
    when(itemRepository.findByName("spec-" + chainId)).thenReturn(Optional.empty());
    when(knowledgeService.submitKnowledge(any())).thenReturn(specResponse(specItemId));
    when(chainRepository.findById(chainId)).thenReturn(Optional.of(chain));

    coordinator.onBaStepCompleted(new BaStepCompletedEvent(this, chainId, 0, baRunId, "# Spec\ncontent"));

    verify(knowledgeService).submitKnowledge(argThat(r ->
            r.getType() == KnowledgeType.SPEC && r.getName().equals("spec-" + chainId)));
    verify(approvalRepository).save(argThat(a ->
            a.getApprovalType() == Approval.ApprovalType.SPEC_REVIEW
            && a.getKnowledgeItemId().equals(specItemId)
            && a.getContent().contains("# Spec")
            && a.getExpiresAt() != null));
    assertThat(chain.getStatus()).isEqualTo(WorkflowChain.Status.WAITING_APPROVAL);
    verify(eventPublisher).publishEvent(any(ApprovalRequestedEvent.class));
}

@Test
void onBaStepCompleted_isIdempotent_whenApprovalExists() {
    when(approvalRepository.findByRunId(baRunId)).thenReturn(List.of(pendingSpecReview()));
    coordinator.onBaStepCompleted(new BaStepCompletedEvent(this, chainId, 0, baRunId, "# Spec"));
    verify(knowledgeService, never()).submitKnowledge(any());
    verify(approvalRepository, never()).save(any());
}

@Test
void onApprovalApproved_writesBack_rewritesSpecRef_andAdvances() {
    when(approvalRepository.findById(approvalId)).thenReturn(Optional.of(specReviewApproval));
    when(chainRepository.findById(chainId)).thenReturn(Optional.of(chain)); // chain has DEV/QA steps with {specRef}

    coordinator.onApprovalDecided(new ApprovalDecidedEvent(this, approvalId, ApprovalStatus.APPROVED));

    verify(knowledgeService).reviewKnowledge(eq(specItemId), argThat(r ->
            r.getDecision() == ReviewDecisionRequest.ReviewDecision.APPROVED));
    // {specRef} in DEV/QA prompts replaced with the SPEC UUID
    assertThat(deserialize(chain)).anySatisfy(s ->
            assertThat(s.getPromptTemplate()).doesNotContain("{specRef}"));
    assertThat(chain.getStatus()).isEqualTo(WorkflowChain.Status.RUNNING);
    verify(workflowService).advanceWorkflow(eq(chainId), eq(0), any());
}

@Test
void onApprovalDenied_writesBackRejected_andReschedulesBaStep() {
    when(approvalRepository.findById(approvalId)).thenReturn(Optional.of(specReviewApproval));
    when(chainRepository.findById(chainId)).thenReturn(Optional.of(chain));
    coordinator.onApprovalDecided(new ApprovalDecidedEvent(this, approvalId, ApprovalStatus.DENIED));
    verify(knowledgeService).reviewKnowledge(eq(specItemId), argThat(r ->
            r.getDecision() == ReviewDecisionRequest.ReviewDecision.REJECTED));
    verify(workflowService).rescheduleStep(eq(chainId), eq(0), any());
}

@Test
void onApprovalDecided_ignoresToolCallApprovals() {
    when(approvalRepository.findById(approvalId)).thenReturn(Optional.of(toolCallApproval));
    coordinator.onApprovalDecided(new ApprovalDecidedEvent(this, approvalId, ApprovalStatus.APPROVED));
    verifyNoInteractions(knowledgeService, workflowService);
}
```

- [ ] **Step 3: Run test to verify it fails**

Run: `cd agent-control-tower && mvn test -pl act-knowledge -Dtest=SpecReviewCoordinatorTest`
Expected: FAIL (class missing).

- [ ] **Step 4: Implement `SpecReviewCoordinator.java`**

Create `act-knowledge/src/main/java/io/aria/conductor/knowledge/sdd/SpecReviewCoordinator.java`:

```java
package io.aria.conductor.knowledge.sdd;

import io.aria.conductor.agent.repository.WorkflowChainRepository;
import io.aria.conductor.agent.service.WorkflowService;
import io.aria.conductor.common.event.ApprovalDecidedEvent;
import io.aria.conductor.common.event.ApprovalRequestedEvent;
import io.aria.conductor.common.event.BaStepCompletedEvent;
import io.aria.conductor.common.model.*;
import io.aria.conductor.execution.repository.ApprovalRepository;
import io.aria.conductor.knowledge.dto.CreateKnowledgeRequest;
import io.aria.conductor.knowledge.dto.KnowledgeItemResponse;
import io.aria.conductor.knowledge.dto.ReviewDecisionRequest;
import io.aria.conductor.knowledge.dto.UpdateKnowledgeRequest;
import io.aria.conductor.knowledge.repository.KnowledgeItemRepository;
import io.aria.conductor.knowledge.service.KnowledgeService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Coordinates the spec-review gate of the SDD loop. On BA-step completion it stores the spec
 * as a versioned SPEC knowledge item, opens a SPEC_REVIEW approval, and pauses the chain.
 * On approval it writes back to knowledge, injects the spec UUID into Dev/QA prompts, and
 * resumes the chain. Idempotent; recovers decided-but-unrouted approvals on startup.
 */
@Slf4j
@Component
public class SpecReviewCoordinator {

    private final KnowledgeService knowledgeService;
    private final KnowledgeItemRepository itemRepository;
    private final ApprovalRepository approvalRepository;
    private final WorkflowChainRepository chainRepository;
    private final WorkflowService workflowService;
    private final ApplicationEventPublisher eventPublisher;
    private final Duration approvalTimeout;

    public SpecReviewCoordinator(KnowledgeService knowledgeService,
                                 KnowledgeItemRepository itemRepository,
                                 ApprovalRepository approvalRepository,
                                 WorkflowChainRepository chainRepository,
                                 WorkflowService workflowService,
                                 ApplicationEventPublisher eventPublisher,
                                 @Value("${approvals.timeout-ms:1800000}") long approvalTimeoutMs) {
        this.knowledgeService = knowledgeService;
        this.itemRepository = itemRepository;
        this.approvalRepository = approvalRepository;
        this.chainRepository = chainRepository;
        this.workflowService = workflowService;
        this.eventPublisher = eventPublisher;
        this.approvalTimeout = Duration.ofMillis(approvalTimeoutMs);
    }

    /** BA step finished: persist the spec, open a SPEC_REVIEW approval, pause the chain. */
    @EventListener
    @Transactional
    public void onBaStepCompleted(BaStepCompletedEvent event) {
        UUID chainId = event.getChainId();
        UUID baRunId = event.getBaRunId();

        // Idempotency: a pending SPEC_REVIEW approval for this BA run means we already handled it.
        boolean alreadyPending = approvalRepository.findByRunId(baRunId).stream()
                .anyMatch(a -> a.getApprovalType() == Approval.ApprovalType.SPEC_REVIEW
                        && a.getStatus() == ApprovalStatus.PENDING);
        if (alreadyPending) {
            log.info("SDD spec approval already pending for BA run {}; skipping", baRunId);
            return;
        }

        String specName = specName(chainId);
        UUID specItemId = upsertSpecKnowledge(specName, event.getFinalOutput());

        Approval approval = Approval.builder()
                .runId(baRunId)
                .approvalType(Approval.ApprovalType.SPEC_REVIEW)
                .content(event.getFinalOutput())
                .contentKind(Approval.ContentKind.MARKDOWN)
                .knowledgeItemId(specItemId)
                .status(ApprovalStatus.PENDING)
                .reason("Spec ready for review: " + specName)
                .expiresAt(Instant.now().plus(approvalTimeout))
                .build();
        approvalRepository.save(approval);

        WorkflowChain chain = chainRepository.findById(chainId).orElse(null);
        if (chain != null) {
            chain.setStatus(WorkflowChain.Status.WAITING_APPROVAL);
            chainRepository.save(chain);
        }

        // Null-safe toolCallId + approvalType carried on the event.
        eventPublisher.publishEvent(new ApprovalRequestedEvent(
                this, approval.getId(), baRunId, null, "SPEC_REVIEW"));
        log.info("SDD spec submitted for review: chain={} spec={} approval={}", chainId, specItemId, approval.getId());
    }

    /** Approval decided: write back to knowledge and route the chain. */
    @EventListener
    @Transactional
    public void onApprovalDecided(ApprovalDecidedEvent event) {
        Approval approval = approvalRepository.findById(event.getApprovalId()).orElse(null);
        if (approval == null || approval.getApprovalType() != Approval.ApprovalType.SPEC_REVIEW) {
            return; // not our concern
        }
        UUID specItemId = approval.getKnowledgeItemId();
        if (specItemId == null) return;

        WorkflowChain chain = workflowService.findChainByRunId(approval.getRunId());
        if (chain == null) {
            log.warn("SDD approval {} resolved but no chain found for run {}", approval.getId(), approval.getRunId());
            return;
        }

        boolean approved = approval.getStatus() == ApprovalStatus.APPROVED;
        knowledgeService.reviewKnowledge(specItemId, ReviewDecisionRequest.builder()
                .decision(approved ? ReviewDecisionRequest.ReviewDecision.APPROVED
                                   : ReviewDecisionRequest.ReviewDecision.REJECTED)
                .reason(approval.getReason())
                .build());

        int baIdx = workflowService.findStepIndexByKind(chain, WorkflowStep.StepKind.BA);
        if (approved) {
            injectSpecReference(chain, specItemId);
            chain.setStatus(WorkflowChain.Status.RUNNING);
            chainRepository.save(chain);
            workflowService.advanceWorkflow(chain.getId(), baIdx, approval.getReason());
            log.info("SDD spec approved: chain={} advancing to Dev", chain.getId());
        } else {
            workflowService.rescheduleStep(chain.getId(), baIdx,
                    "Spec was rejected: " + (approval.getReason() != null ? approval.getReason() : ""));
            log.info("SDD spec rejected: chain={} re-scheduling BA step", chain.getId());
        }
    }

    /** Startup recovery: re-route APPROVED approvals whose chain is still WAITING_APPROVAL. */
    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void recoverPendingDecisions() {
        List<Approval> approved = approvalRepository.findByStatusAndApprovalType(
                ApprovalStatus.APPROVED, Approval.ApprovalType.SPEC_REVIEW);
        for (Approval a : approved) {
            WorkflowChain chain = workflowService.findChainByRunId(a.getRunId());
            if (chain != null && chain.getStatus() == WorkflowChain.Status.WAITING_APPROVAL) {
                log.info("SDD startup recovery: re-routing chain {}", chain.getId());
                onApprovalDecided(new ApprovalDecidedEvent(this, a.getId(), ApprovalStatus.APPROVED));
            }
        }
    }

    /** Re-create an approval for a chain stuck in WAITING_APPROVAL (e.g. after EXPIRED). */
    @Transactional
    public Approval resubmitApproval(UUID chainId) {
        WorkflowChain chain = chainRepository.findById(chainId)
                .orElseThrow(() -> new IllegalArgumentException("Chain not found: " + chainId));
        int baIdx = workflowService.findStepIndexByKind(chain, WorkflowStep.StepKind.BA);
        WorkflowStep baStep = workflowService.stepAt(chain, baIdx);
        String specName = specName(chainId);
        KnowledgeItem item = itemRepository.findByName(specName)
                .orElseThrow(() -> new IllegalStateException("No spec knowledge item: " + specName));
        Approval approval = Approval.builder()
                .runId(baStep.getRunId())
                .approvalType(Approval.ApprovalType.SPEC_REVIEW)
                .content(item.getDescription())
                .contentKind(Approval.ContentKind.MARKDOWN)
                .knowledgeItemId(item.getId())
                .status(ApprovalStatus.PENDING)
                .reason("Spec resubmitted for review: " + specName)
                .expiresAt(Instant.now().plus(approvalTimeout))
                .build();
        return approvalRepository.save(approval);
    }

    private UUID upsertSpecKnowledge(String name, String content) {
        return itemRepository.findByName(name)
                .map(existing -> knowledgeService.updateKnowledge(existing.getId(),
                        UpdateKnowledgeRequest.builder().content(content).build()).getId())
                .orElseGet(() -> knowledgeService.submitKnowledge(CreateKnowledgeRequest.builder()
                        .name(name)
                        .type(KnowledgeType.SPEC)
                        .description("SDD spec")
                        .content(content)
                        .build()).getId());
    }

    private void injectSpecReference(WorkflowChain chain, UUID specItemId) {
        List<WorkflowStep> steps = workflowService.deserializeSteps(chain.getStepsJson());
        for (WorkflowStep s : steps) {
            if ((s.getKind() == WorkflowStep.StepKind.DEV || s.getKind() == WorkflowStep.StepKind.QA)
                    && s.getPromptTemplate() != null) {
                s.setPromptTemplate(s.getPromptTemplate().replace("{specRef}", specItemId.toString()));
            }
        }
        chain.setStepsJson(workflowService.serializeSteps(steps));
    }

    private String specName(UUID chainId) { return "spec-" + chainId; }
}
```

Add to `ApprovalRepository`:

```java
    java.util.List<Approval> findByRunId(UUID runId);
    java.util.List<Approval> findByStatusAndApprovalType(ApprovalStatus status, Approval.ApprovalType type);
```

Expose `deserializeSteps`/`serializeSteps` as package/public on `WorkflowService` if currently private.

- [ ] **Step 5: Expose approval content fields in `ApprovalController`**

Extend the `ApprovalDetail` record with `approvalType`, `content`, `contentKind`, `knowledgeItemId`, and populate them in `toDetail`:

```java
    public record ApprovalDetail(
            UUID id, UUID runId, UUID toolCallId, ApprovalStatus status, String reason,
            Instant requestedAt, Instant decidedAt, Instant expiresAt,
            String approvalType, String content, String contentKind, UUID knowledgeItemId,
            String toolName, String arguments, String riskTier) {}

    // in toDetail(...):
    return new ApprovalDetail(
            a.getId(), a.getRunId(), a.getToolCallId(), a.getStatus(), a.getReason(),
            a.getRequestedAt(), a.getDecidedAt(), a.getExpiresAt(),
            a.getApprovalType() != null ? a.getApprovalType().name() : "TOOL_CALL",
            a.getContent(), a.getContentKind() != null ? a.getContentKind().name() : null,
            a.getKnowledgeItemId(),
            toolName, tc != null ? tc.getArguments() : null, riskTier);
```

- [ ] **Step 6: Add the resubmit endpoint in `WorkflowController`**

In `act-agent/.../controller/WorkflowController.java`, inject `SpecReviewCoordinator` is not visible (act-agent can't depend on act-knowledge). Instead expose a thin POST that delegates through a service interface, OR place the endpoint in a controller inside act-knowledge. **Place the endpoint in act-knowledge** to keep the dependency direction clean: create `act-knowledge/src/main/java/io/aria/conductor/knowledge/controller/SddWorkflowController.java`:

```java
package io.aria.conductor.knowledge.controller;

import io.aria.conductor.common.model.Approval;
import io.aria.conductor.knowledge.sdd.SpecReviewCoordinator;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/workflows")
public class SddWorkflowController {

    private final SpecReviewCoordinator coordinator;

    public SddWorkflowController(SpecReviewCoordinator coordinator) { this.coordinator = coordinator; }

    @PostMapping("/{id}/resubmit-approval")
    public ResponseEntity<Approval> resubmitApproval(@PathVariable UUID id) {
        try {
            return ResponseEntity.ok(coordinator.resubmitApproval(id));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ResponseEntity.badRequest().build();
        }
    }
}
```

- [ ] **Step 7: Reject agent self-approval of SPEC_REVIEW in `decide_approval`**

In `act-aria/.../tools/handlers/ApprovalToolHandler.java` `decide(...)`, before delegating, load the approval and refuse SPEC_REVIEW:

```java
    // Governance: SPEC_REVIEW approvals require a human decision, not an agent's.
    Approval target = approvalRepository.findById(approvalId).orElse(null);
    if (target != null && target.getApprovalType() == Approval.ApprovalType.SPEC_REVIEW) {
        return error("SPEC_REVIEW approvals must be decided by a human via the dashboard, not by an agent.");
    }
```

- [ ] **Step 8: Run coordinator tests GREEN**

Run: `cd agent-control-tower && mvn test -pl act-knowledge -Dtest=SpecReviewCoordinatorTest`
Expected: PASS.

- [ ] **Step 9: Commit**

```bash
git add agent-control-tower/act-knowledge/src/main/java/io/aria/conductor/knowledge/sdd/SpecReviewCoordinator.java \
  agent-control-tower/act-knowledge/src/main/java/io/aria/conductor/knowledge/controller/SddWorkflowController.java \
  agent-control-tower/act-knowledge/src/main/java/io/aria/conductor/knowledge/repository/KnowledgeItemRepository.java \
  agent-control-tower/act-knowledge/src/test/java/io/aria/conductor/knowledge/sdd/SpecReviewCoordinatorTest.java \
  agent-control-tower/act-execution/src/main/java/io/aria/conductor/execution/repository/ApprovalRepository.java \
  agent-control-tower/act-execution/src/main/java/io/aria/conductor/execution/controller/ApprovalController.java \
  agent-control-tower/act-aria/src/main/java/io/aria/conductor/aria/tools/handlers/ApprovalToolHandler.java
git commit -m "feat(sdd): SpecReviewCoordinator (spec knowledge + SPEC_REVIEW approval + resume + recovery)"
```

---

## Task 8: Template instantiation wiring (DoD + kanban) + seed integrity

**Files:**
- Modify: `act-knowledge/.../service/WorkflowTemplateService.java`
- Test: `act-knowledge/src/test/java/io/aria/conductor/knowledge/service/WorkflowTemplateServiceSddTest.java`

When a SDD template (has BA/DEV/QA kinds) is instantiated, wire the DoD record (custom stages `[dev, qa]`) and create a chain-level kanban item with no `linkedRunId`.

- [ ] **Step 1: Write the failing test**

```java
@Test
void instantiateTemplate_withSddKinds_initsDoDAndKanban() {
    // seeded WORKFLOW template with ba/dev/qa steps (from V40 seed)
    WorkflowResponse resp = service.instantiateTemplate(templateItemId, Map.of("issueRef", "#1"));
    WorkflowChain chain = chainRepository.findById(resp.getId()).orElseThrow();

    // DoD initialised with custom stages [dev, qa] for taskId = chainId
    verify(dodService).init(eq(chain.getId().toString()), anyString(), eq(List.of("dev", "qa")));
    // chain-level kanban item created WITHOUT linkedRunId
    verify(kanbanService).create(argThat(req -> req.getLinkedRunId() == null
            && req.getTitle().contains(chain.getName())));
    // steps carry kinds
    assertThat(deserialize(chain)).extracting(WorkflowStep::getKind)
            .containsExactly(WorkflowStep.StepKind.BA, WorkflowStep.StepKind.DEV, WorkflowStep.StepKind.QA);
}

@Test
void instantiateTemplate_withoutSddKinds_skipsWiring() {
    // generic template (no ba/dev/qa kinds)
    WorkflowResponse resp = service.instantiateTemplate(genericTemplateId, Map.of());
    verify(dodService, never()).init(any(), any(), any());
    verify(kanbanService, never()).create(any());
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd agent-control-tower && mvn test -pl act-knowledge -Dtest=WorkflowTemplateServiceSddTest`
Expected: FAIL (wiring missing).

- [ ] **Step 3: Implement wiring in `instantiateTemplate`**

Inject `DoDService` and `KanbanService` into `WorkflowTemplateService`. After `createAndStart` and before linking `sourceKnowledgeItemId`, add:

```java
        List<WorkflowStep> parsedSteps = templateConverter.yamlToWorkflowSteps(yamlContent);
        boolean isSdd = parsedSteps.stream().anyMatch(s ->
                s.getKind() == WorkflowStep.StepKind.BA
                || s.getKind() == WorkflowStep.StepKind.DEV
                || s.getKind() == WorkflowStep.StepKind.QA);
        if (isSdd) {
            // DoD with SDD stages (taskId = chainId); kanban item without a linked run
            // so RunKanbanAutoCreator does not auto-transition it.
            dodService.init(response.getId().toString(), "SDD", List.of("dev", "qa"));
            kanbanService.create(CreateKanbanItemRequest.builder()
                    .title(response.getName())
                    .description("SDD workflow: " + item.getName())
                    .build());
        }
```

(Import `io.aria.conductor.execution.dod.DoDService`, `io.aria.conductor.execution.kanban.KanbanService`, and `CreateKanbanItemRequest`. Ensure `CreateKanbanItemRequest` has a builder; if not, use its constructor.)

- [ ] **Step 4: Add the `REVIEW` transition to `KanbanService`**

In `KanbanService` static `ALLOWED_TRANSITIONS`, add:

```java
        map.put(KanbanStatus.IN_PROGRESS,
                EnumSet.of(KanbanStatus.DONE, KanbanStatus.BLOCKED, KanbanStatus.CANCELLED, KanbanStatus.REVIEW));
        map.put(KanbanStatus.REVIEW,
                EnumSet.of(KanbanStatus.IN_PROGRESS, KanbanStatus.DONE, KanbanStatus.BLOCKED, KanbanStatus.CANCELLED));
```

Add a unit test asserting these transitions are valid in `KanbanServiceTest`.

- [ ] **Step 5: Run tests GREEN + seed integrity**

Run: `cd agent-control-tower && mvn test -pl act-knowledge -Dtest=WorkflowTemplateServiceSddTest,WorkflowTemplateServiceTest && mvn test -pl act-execution -Dtest=KanbanServiceTest`
Expected: PASS.

Also run the app context once to verify V40 seed applies and the template is discoverable + instantiable (covered fully by Task 9 integration + Task 12 seed-integrity assertion).

- [ ] **Step 6: Commit**

```bash
git add agent-control-tower/act-knowledge/src/main/java/io/aria/conductor/knowledge/service/WorkflowTemplateService.java \
  agent-control-tower/act-knowledge/src/test/java/io/aria/conductor/knowledge/service/WorkflowTemplateServiceSddTest.java \
  agent-control-tower/act-execution/src/main/java/io/aria/conductor/execution/kanban/KanbanService.java \
  agent-control-tower/act-execution/src/test/java/io/aria/conductor/execution/kanban/KanbanServiceTest.java
git commit -m "feat(sdd): template instantiation wiring (DoD stages + kanban) + REVIEW transition"
```

---

## Task 9: Integration tests (happy path + DEFECT + SPEC_GAP + boundaries)

**Files:**
- Create: `agent-control-tower/act-app/src/test/java/io/aria/conductor/app/sdd/SddWorkflowIntegrationTest.java` (extends `IntegrationTestBase`, uses `MockAdkRuntime`)

These exercise the real Spring context + Flyway (V40 seed) end to end, mocking only the LLM/ADK runtime so run completions are deterministic.

- [ ] **Step 1: Write the integration test**

```java
package io.aria.conductor.app.sdd;

import io.aria.conductor.test.IntegrationTestBase;
// + imports for WorkflowTemplateService, WorkflowService, ApprovalRepository, DoDService, etc.

class SddWorkflowIntegrationTest extends IntegrationTestBase {

    @Test
    void happyPath_baApproval_dev_qaPass_completesChain() {
        // 1. instantiate the seeded development-workflow template (issueRef param)
        // 2. complete the BA run (MockAdkRuntime returns a markdown spec) -> assert:
        //    - chain status == WAITING_APPROVAL
        //    - a PENDING SPEC_REVIEW approval exists with content + knowledgeItemId
        // 3. approve the approval via ApprovalGate.decideApproval -> assert:
        //    - knowledge item APPROVED
        //    - Dev/QA prompts no longer contain {specRef} (replaced with the SPEC UUID)
        //    - chain RUNNING, Dev run started
        // 4. complete the Dev run -> assert DoD advanced dev->qa
        // 5. complete the QA run after QA submits DoD review verdict=PASS -> assert:
        //    - chain COMPLETED
        //    - DoD overallStatus PASSED
    }

    @Test
    void defectLoop_qaDefect_reschedulesDevWithFeedback_thenPasses() {
        // happy path up to QA; QA verdict=DEFECT -> assert Dev step attemptCount==1,
        // Dev prompt contains the defect feedback; complete Dev + QA verdict=PASS -> COMPLETED
    }

    @Test
    void specGapLoop_qaSpecGap_reschedulesBa_createsNewSpecVersion_reapproval() {
        // happy path up to QA; QA verdict=SPEC_GAP -> BA re-scheduled (attemptCount==1);
        // complete BA again -> NEW SPEC version (v0.2.0), NEW SPEC_REVIEW approval,
        // chain back to WAITING_APPROVAL; approve -> Dev consumes the new spec
    }

    @Test
    void boundaries_maxAttempts_failsChain() {
        // force 3 DEFECT loops -> 4th reschedule fails the chain (maxAttempts exceeded)
    }

    @Test
    void boundaries_qaWithoutVerdict_failsChain() {
        // QA run completes but no DoD verdict submitted -> chain FAILED with clear message
    }

    @Test
    void seedIntegrity_templateExistsAndInstantiates() {
        // knowledge item 'development-workflow' exists, APPROVED, version row matches
        // findByKnowledgeItemIdAndVersion; instantiateTemplate succeeds
    }
}
```

(Fill each test with concrete steps using the injected services and `MockAdkRuntime` to script run completions, mirroring existing integration tests under `act-app/src/test`.)

- [ ] **Step 2: Run the integration suite**

Run: `cd agent-control-tower && mvn test -pl act-app -Dtest=SddWorkflowIntegrationTest -Dspring.profiles.active=h2`
Expected: initially RED where wiring is incomplete, GREEN once Tasks 3-8 land.

- [ ] **Step 3: Commit**

```bash
git add agent-control-tower/act-app/src/test/java/io/aria/conductor/app/sdd/SddWorkflowIntegrationTest.java
git commit -m "test(sdd): integration coverage (happy path, DEFECT/SPEC_GAP loops, boundaries, seed integrity)"
```

---

## Task 10: Frontend - SPEC_REVIEW rendering + sanitised markdown

**Files:**
- Create: `agent-control-tower/act-dashboard/src/components/MarkdownViewer.tsx`
- Modify: `agent-control-tower/act-dashboard/src/pages/ApprovalsPage.tsx`
- Modify: `agent-control-tower/act-dashboard/src/pages/WorkflowsPage.tsx`
- Modify: `agent-control-tower/act-dashboard/src/pages/KnowledgePage.tsx`
- Modify: `agent-control-tower/act-dashboard/package.json` (add `dompurify` if absent)
- Test: `agent-control-tower/act-dashboard/src/components/MarkdownViewer.test.tsx` (vitest)

- [ ] **Step 1: Install DOMPurify (if not present)**

Run: `cd agent-control-tower/act-dashboard && pnpm add dompurify @types/dompurify`
Expected: dependency added.

- [ ] **Step 2: Write the MarkdownViewer component test (RED)**

`agent-control-tower/act-dashboard/src/components/MarkdownViewer.test.tsx`:

```tsx
import { render } from '@testing-library/react';
import { MarkdownViewer } from './MarkdownViewer';

test('renders markdown headings and sanitises scripts', () => {
  const { container } = render(
    <MarkdownViewer content={'# Title\n\n**bold**\n<script>alert(1)</script>'} />
  );
  expect(container.querySelector('h1')?.textContent).toBe('Title');
  expect(container.querySelector('script')).toBeNull(); // sanitised away
});
```

- [ ] **Step 3: Run test to verify it fails**

Run: `cd agent-control-tower/act-dashboard && pnpm vitest run src/components/MarkdownViewer.test.tsx`
Expected: FAIL (component missing).

- [ ] **Step 4: Implement `MarkdownViewer.tsx`**

```tsx
import DOMPurify from 'dompurify';

interface Props { content: string; className?: string; }

// Minimal markdown -> HTML (headings, bold, code, lists, paragraphs) then sanitise.
// For richer rendering swap `toHtml` for a markdown lib; keep the DOMPurify step.
function toHtml(md: string): string {
  return md
    .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
    .replace(/^### (.*)$/gm, '<h3>$1</h3>')
    .replace(/^## (.*)$/gm, '<h2>$1</h2>')
    .replace(/^# (.*)$/gm, '<h1>$1</h1>')
    .replace(/\*\*(.+?)\*\*/g, '<strong>$1</strong>')
    .replace(/`([^`]+)`/g, '<code>$1</code>')
    .replace(/^- (.*)$/gm, '<li>$1</li>')
    .replace(/\n{2,}/g, '<br/><br/>');
}

export function MarkdownViewer({ content, className }: Props) {
  const html = DOMPurify.sanitize(toHtml(content ?? ''));
  return (
    <div
      className={`spec-review-markdown ${className ?? ''}`}
      dangerouslySetInnerHTML={{ __html: html }}
    />
  );
}
```

- [ ] **Step 5: Run component test GREEN**

Run: `cd agent-control-tower/act-dashboard && pnpm vitest run src/components/MarkdownViewer.test.tsx`
Expected: PASS.

- [ ] **Step 6: Branch `ApprovalsPage.tsx` by approvalType + null-safe toolCallId**

In `ApprovalsPage.tsx`, import `MarkdownViewer`. Replace the `Tool/Action` info row block and the reason area so SPEC_REVIEW cards render markdown. Update the existing tool display:

```tsx
const isSpecReview = approval.approvalType === 'SPEC_REVIEW';
// ...
<div className="approval-card-body">
  {isSpecReview ? (
    <>
      <div className="approval-info-row">
        <span className="approval-label">Spec Review</span>
        <span className="cell-mono">{approval.knowledgeItemId?.slice(0, 8)}</span>
      </div>
      <MarkdownViewer content={approval.content ?? ''} />
    </>
  ) : (
    <>
      <div className="approval-info-row">
        <span className="approval-label">Tool/Action</span>
        <span className="cell-mono">{approval.toolName ?? (approval.toolCallId ? approval.toolCallId.slice(0, 8) : '—')}</span>
      </div>
      {approval.arguments && ( /* existing arguments row unchanged */ )}
    </>
  )}
  {/* risk / reason / countdown rows unchanged */}
</div>
```

Key fixes vs the current code: (a) the `toolCallId.slice(0,8)` fallback is now null-safe; (b) SPEC_REVIEW cards render `content` through `MarkdownViewer` (DOMPurify-sanitised).

- [ ] **Step 7: Update `WorkflowsPage.tsx` for WAITING_APPROVAL + resubmit**

- Render a `WAITING_APPROVAL` status badge (map it to a warning style).
- Enable the Cancel button for `WAITING_APPROVAL` chains (the backend now accepts it).
- Add a "Resubmit approval" button shown when a chain is WAITING_APPROVAL, calling `POST /api/v1/workflows/{id}/resubmit-approval` (add a helper in `src/api/workflows.ts`).

- [ ] **Step 8: Add SPEC to `KnowledgePage.tsx` type options**

Add `'SPEC'` to the knowledge-type select options (and any type-filter whitelist) so SPEC items display and can be created.

- [ ] **Step 9: Run frontend type-check + build + the E2E anchor**

Run: `cd agent-control-tower/act-dashboard && pnpm build`
Expected: no TypeScript errors (the new `Approval` fields are optional, so existing usages compile).
Run: `cd agent-control-tower/act-dashboard && npx playwright test e2e/sdd-workflow.spec.ts`
Expected: now PASSes (backend Tasks 3-9 + frontend rendering in place).

- [ ] **Step 10: Commit**

```bash
git add agent-control-tower/act-dashboard/src/components/MarkdownViewer.tsx \
  agent-control-tower/act-dashboard/src/components/MarkdownViewer.test.tsx \
  agent-control-tower/act-dashboard/src/pages/ApprovalsPage.tsx \
  agent-control-tower/act-dashboard/src/pages/WorkflowsPage.tsx \
  agent-control-tower/act-dashboard/src/pages/KnowledgePage.tsx \
  agent-control-tower/act-dashboard/src/api/workflows.ts \
  agent-control-tower/act-dashboard/package.json
git commit -m "feat(sdd): SPEC_REVIEW approval rendering (sanitised markdown) + WAITING_APPROVAL UI"
```

---

## Task 11: SPEC_REVIEW expiry + Aria prompt guidance

**Files:**
- Modify: `act-execution/.../approval/ApprovalExpiryChecker.java`
- Modify: `act-aria/.../init/AriaDefaultAgentInitializer.java` + `AriaService.java` system prompt
- Test: `act-execution/src/test/java/io/aria/conductor/execution/approval/ApprovalExpiryCheckerSddTest.java`

- [ ] **Step 1: Write the failing expiry test**

```java
@Test
void expiredSpecReviewApproval_isMarkedExpired_andChainStaysWaiting() {
    Approval a = specReviewApproval().toBuilder().expiresAt(Instant.now().minusSeconds(1)).build();
    when(approvalRepository.findByStatus(ApprovalStatus.PENDING)).thenReturn(List.of(a));
    checker.expireOverdue();
    verify(approvalRepository).save(argThat(x -> x.getStatus() == ApprovalStatus.EXPIRED));
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd agent-control-tower && mvn test -pl act-execution -Dtest=ApprovalExpiryCheckerSddTest`
Expected: FAIL (checker ignores SPEC_REVIEW or has no generic scan).

- [ ] **Step 3: Extend `ApprovalExpiryChecker`**

Ensure the scheduled scan expires ANY PENDING approval past `expiresAt` (not just blocking-future ones). If the current checker only handles tool-call approvals, broaden the query to `findByStatus(PENDING)` and mark those with `expiresAt < now` as EXPIRED:

```java
    @Scheduled(fixedDelayString = "${approvals.expiry-check-ms:60000}")
    public void expireOverdue() {
        Instant now = Instant.now();
        approvalRepository.findByStatus(ApprovalStatus.PENDING).stream()
                .filter(a -> a.getExpiresAt() != null && a.getExpiresAt().isBefore(now))
                .forEach(a -> {
                    a.setStatus(ApprovalStatus.EXPIRED);
                    a.setReason("Auto-expired: approval timed out");
                    a.setDecidedAt(now);
                    approvalRepository.save(a);
                });
    }
```

(The chain stays WAITING_APPROVAL by design; the user re-requests via `resubmit-approval`.)

- [ ] **Step 4: Add Aria prompt guidance**

In `AriaService.buildSystemPrompt()` (and the matching `ARIA_SYSTEM_PROMPT` in `AriaDefaultAgentInitializer`), add under **Workflows**:

```
- Spec-driven development: to run the BA->Dev->QA development loop on a GitHub issue, find the
  approved "development-workflow" template and instantiate it with an issueRef parameter. The loop
  pauses for human spec approval (SPEC_REVIEW), then routes on the QA verdict. Users can copy the
  template knowledge item and edit its YAML to customise their own workflow.
```

- [ ] **Step 5: Run tests GREEN**

Run: `cd agent-control-tower && mvn test -pl act-execution -Dtest=ApprovalExpiryCheckerSddTest,ApprovalExpiryCheckerTest`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add agent-control-tower/act-execution/src/main/java/io/aria/conductor/execution/approval/ApprovalExpiryChecker.java \
  agent-control-tower/act-execution/src/test/java/io/aria/conductor/execution/approval/ApprovalExpiryCheckerSddTest.java \
  agent-control-tower/act-aria/src/main/java/io/aria/conductor/aria/service/AriaService.java \
  agent-control-tower/act-aria/src/main/java/io/aria/conductor/aria/init/AriaDefaultAgentInitializer.java
git commit -m "feat(sdd): SPEC_REVIEW expiry + Aria development-workflow guidance"
```

---

## Task 12: Phase 4 full regression + CI schema smoke

**Files:**
- Create: `agent-control-tower/act-app/src/test/java/io/aria/conductor/app/sdd/SddGoldenChainRegressionTest.java`
- Modify: `.github/workflows/ci.yml` (add schema-consistency smoke)

- [ ] **Step 1: Write the golden GENERIC-chain regression test**

```java
class SddGoldenChainRegressionTest extends IntegrationTestBase {
    @Test
    void genericChain_withoutKinds_walksExactCurrentPath() {
        // create a 2-step chain with GENERIC/null kinds -> complete step 0 ->
        // assert step 1 started (advance), no DoD interaction, no SPEC approval,
        // no WAITING_APPROVAL; events identical to pre-SDD behaviour.
    }
}
```

- [ ] **Step 2: Run the full Java regression**

Run: `cd agent-control-tower && mvn clean test -Dspring.profiles.active=h2`
Expected: ALL modules green. Watch specifically: `WorkflowAutoChainerTest`, `WorkflowServiceExistingTest`, `WorkflowTemplateServiceTest`, `DoDServiceTest`, `KanbanServiceTest`, approval tests.

- [ ] **Step 3: Run frontend + E2E regression**

Run: `cd agent-control-tower/act-dashboard && pnpm build && npx playwright test`
Expected: build clean; ALL existing specs (workflow-governance, approvals-decision-flow, etc.) + the new `sdd-workflow.spec.ts` green.

- [ ] **Step 4: Add CI schema-consistency smoke**

In `.github/workflows/ci.yml`, add a job (or step) that boots the app context under the h2 profile with `spring.jpa.hibernate.ddl-auto=validate` temporarily, to catch entity/V40 drift the `none`-profile tests would miss:

```yaml
      - name: Schema consistency smoke (ddl-auto validate)
        run: |
          cd agent-control-tower
          mvn -pl act-app test -Dtest='SchemaConsistencySmokeTest' \
            -Dspring.jpa.hibernate.ddl-auto=validate -Dspring.profiles.active=h2
```

And create `SchemaConsistencySmokeTest` (a `@SpringBootTest` that only asserts the context starts, which forces Hibernate to validate entities against the Flyway schema).

- [ ] **Step 5: Commit**

```bash
git add agent-control-tower/act-app/src/test/java/io/aria/conductor/app/sdd/SddGoldenChainRegressionTest.java \
  agent-control-tower/act-app/src/test/java/io/aria/conductor/app/sdd/SchemaConsistencySmokeTest.java \
  .github/workflows/ci.yml
git commit -m "test(sdd): golden GENERIC-chain regression + CI schema-consistency smoke"
```

- [ ] **Step 6: Final verification pass**

Run all four suites once more and confirm green:
```bash
cd agent-control-tower && mvn clean test -Dspring.profiles.active=h2
cd act-dashboard && pnpm build && npx playwright test
cd ../langchain-adk && python -m pytest tests/   # untouched, must stay green
cd ../packages/mcp-server && npx vitest run       # untouched, must stay green
```
Expected: everything green. The Task 2 E2E anchor (`sdd-workflow.spec.ts`) must be passing.

---

## Definition of Done

- [ ] All 12 tasks complete and committed.
- [ ] Task 2 E2E anchor passes (full loop: template -> SPEC_REVIEW approval rendered -> approve -> Dev -> QA PASS -> COMPLETED).
- [ ] DEFECT and SPEC_GAP loop-backs verified (integration tests).
- [ ] Existing GENERIC workflows, DoD, approvals, kanban behaviour unchanged (golden + existing suites green).
- [ ] No stored-XSS vector in SPEC_REVIEW rendering (DOMPurify).
- [ ] CI green including the new schema-consistency smoke.
