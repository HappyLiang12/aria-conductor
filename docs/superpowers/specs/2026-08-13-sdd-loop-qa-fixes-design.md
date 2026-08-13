# SDD Loop QA Report Fixes - Design Spec

## 1. Summary

This spec defines fixes for ALL 22 findings recorded in the real-LLM QA exercise
(`.qoder/qa-sdd-loop/QA-REPORT.md`, 2026-08-13) against PR #71 (spec-driven
development workflow). The findings span two blockers (opencode sandbox
connection drops + weak-model DSML parsing), 7 HIGH product defects, and 13
medium/low UX/environment issues.

All 22 findings are fixed or explicitly mitigated, grouped into 7 change
clusters:

- G1 Sandbox resilience (F11, F12, F8)
- G2 Weak-model compatibility (F16)
- G3 Seed configuration (F6, F9, F15, F2, F13)
- G4 Governance and entrypoints (F4, F10)
- G5 Lifecycle and recovery (F7, F17, F19)
- G6 UX and quality (F1, F3, F14, F18, F21)
- G7 Environment and docs (F5, F20, F22)

Design decisions confirmed in brainstorming:

- F11: HTTP-layer retry in `OpenCodeHttpClient.send()` (2 retries, 1s/4s
  backoff, IOException-only) - task-level rebuild retry rejected (too costly).
- F12: fresh `isHealthy()` probe in `getOrPrepareInstance` before returning a
  cached instance - TTL cache and threshold-lowering rejected.
- F1: CSS `visibility`/`pointer-events` rules for closed modal state - merged
  into this spec's implementation instead of a separate spec.
- F8: minimal fix only (deadline already driven by F9+F11 fixes); full async
  streaming is a non-goal.

## 2. Finding Triage

| ID | Sev | Cluster | Fix |
|----|-----|---------|-----|
| F11 | HIGH | G1 | HTTP retry + keep-alive |
| F12 | medium | G1 | Fresh health probe in getOrPrepareInstance |
| F8 | medium | G1 | Covered by F9+F11; document timeout semantics |
| F16 | HIGH | G2 | DSML fallback parser in langchain-adk agent.py |
| F6 | HIGH | G3 | V43: seed agents taskApprovalRequired:false |
| F9 | HIGH | G3 | V43: max_iterations 6/10/6 -> 15/20/15 + remove round-to-time translation |
| F15 | low | G3 | V43: BA prompt requires spec sections |
| F2 | medium | G3 | V43: system_config token budget 100K -> 300K |
| F13 | medium | G3 | Resolved by F6 (task gate eliminated) |
| F4 | HIGH | G4 | create_workflow rejects BA/DEV/QA kinds + prompt rule |
| F10 | HIGH | G4 | UpdateKnowledgeRequest.yamlContent + carry-forward |
| F7 | medium | G5 | ApplicationReadyEvent orphaned-run recovery |
| F17 | medium | G5 | No-verdict failure message with retry hint |
| F19 | low | G5 | resubmit-approval 400 with error body |
| F1 | low | G6 | Modal closed-state CSS visibility rules |
| F3 | low | G6 | Chain name ASCII sanitization |
| F14 | low | G6 | MarkdownViewer wraps <li> in <ul> |
| F18 | medium | G6 | Spec content strip marker + truncate |
| F21 | low | G6 | ApprovalController optional status param |
| F5 | HIGH(env) | G7 | start script mvn install + docs |
| F20 | medium(env) | G7 | Configurable python path + repo-root script resolution |
| F22 | info(env) | G7 | Preflight checks in start script |

No finding is left unfixed or unmitigated. None are deferred.

## 3. Cluster Designs

### 3.1 G1: Sandbox resilience (F11, F12, F8)

#### F11 - HTTP-layer retry

File: `agent-control-tower/act-execution/src/main/java/io/aria/conductor/execution/adk/opencode/OpenCodeHttpClient.java`

Root cause: `send()` (L184-198) performs a single synchronous
`httpClient.send()` with no retry. Long tasks (15-31 min) get their connection
dropped by `opencode serve` ("header parser received no bytes") -> IOException
-> PROVIDER_ERROR -> task fails. 7/7 real BA attempts failed in opencode mode.

Design:

1. Wrap the `httpClient.send()` call in a retry loop inside `send()`:
   - Retry condition: `IOException` (connection reset, broken pipe, EOF,
     connect failure). Max 2 retries, exponential backoff 1s then 4s.
   - No retry for: `HttpTimeoutException` (deadline semantics - retrying a
     timed-out task is meaningless), non-2xx responses (server responded -
     retrying would repeat side effects).
   - Log every retry decision at WARN with method/path for auditability.
2. Configure the shared `HttpClient` with `keepAlive(Duration.ofMinutes(10))`
   so the pooled connection survives the idle windows of long tasks.

Accepted risk: if the connection drops AFTER the message reached the sandbox
but BEFORE the response headers arrived, a retry may cause the message to be
processed twice. This is the trade-off for turning 7/7 failures into
completable tasks. Mitigation: only IOExceptions (not timeouts, not HTTP
errors) are retried; all retries are WARN-logged.

#### F12 - Fresh health probe

File: `agent-control-tower/act-execution/src/main/java/io/aria/conductor/execution/adk/opencode/OpenCodeAdkProvider.java`

Root cause: `getOrPrepareInstance` (L259-266) checks the cached `healthy`
boolean flag - the result of the LAST external health probe. A sandbox that
dies between probes is returned as stale-healthy -> `executeTask` fails ->
no rebuild (external probe needs 3 consecutive failures to rebuild).

Design: before returning the cached instance, call
`existing.client().isHealthy()` (existing method, 3s timeout). If unhealthy,
fall through to the existing destroy-and-rebuild path (L264-266). The probe
result is NOT written back to the cache (fresh probe every time). 3s overhead
on a 30-min task is <0.2%, acceptable.

The external periodic `isHealthy` (RESTART_AFTER_FAILURES=3) stays unchanged
as a background supplement.

#### F8 - Synchronous timeout (minimal)

The 12-min hard deadline that made sync POST fragile is fixed by F9 (iteration
caps raised + round-to-time translation removed). The task-path timeout is now
driven solely by `maxTaskMinutes` (raised to 45). No additional code; document
in `OpenCodeHttpClient` javadoc that `DEFAULT_REQUEST_TIMEOUT` (5 min) applies
to non-task paths (abort/health) while task paths use the deadline.

Full async streaming (SSE) is a NON-GOAL (architectural refactor).

#### G1 tests

- `OpenCodeHttpClientRetryTest` (WireMock fault injection): connection reset
  then success on retry; timeout NOT retried; non-2xx NOT retried; backoff
  timing asserted.
- `OpenCodeAdkProviderTest`: stale-healthy cached instance (mock isHealthy
  returns false) triggers rebuild path; healthy instance reused; fresh probe
  called on every getOrPrepareInstance.

### 3.2 G2: Weak-model compatibility (F16)

File: `langchain-adk/src/agent.py`

Root cause: `parse_tool_calls` only recognizes structured
`response.tool_calls` and `additional_kwargs["tool_calls"]`. Weak models
(deepseek-v4-flash) emit tool-call intent as raw DSML text in the content
field. The parser returns an empty list -> the loop treats it as a final
answer -> QA can never submit its DoD verdict.

Design:

1. New function `parse_tool_calls_from_text(content: str) -> list[ToolCall]`
   recognizing three patterns conservatively:
   a. XML style: `<tool_call name="...">args</tool_call>` and
      `<function_calls><invoke name="...">` (DSML common forms)
   b. Markdown JSON code block: ```json {"name": "...", "arguments": {...}}```
      or an array form
   c. `function_name(args)` direct-call style - attempted only when (a) and
      (b) produce no match (highest false-positive risk)
2. Activation: in `run_agent_stream`, when `parse_tool_calls(response)` returns
   empty AND `req.tools` is non-empty AND `content` is non-empty, attempt the
   fallback.
3. Anti-false-positive guards:
   - Only active when the model had tools bound but structured parsing failed
   - Parsed tool names must exist in the bound `req.tools` list, else dropped
   - Every successful fallback parse logs at WARNING for audit
4. Java side unchanged (no dual-layer maintenance).

Tests in `langchain-adk/tests/test_agent.py`:
- DSML XML fixture -> correct tool calls extracted
- Markdown JSON fixture -> correct extraction
- Plain text without tool markers -> empty (no false positive)
- Unknown tool name -> dropped

### 3.3 G3: Seed configuration (F6, F9, F15, F2, F13)

File: `agent-control-tower/act-app/src/main/resources/db/migration/V43__fix_sdd_seed_configs.sql` (new)

Four statements:

1. Seed SDD agents config `'{}'` ->
   `'{"taskApprovalRequired": false, "maxToolCallRounds": 15}'`
   (F6, F13: SDD is governed by spec review + DoD verdict; the task-level
   approval gate is redundant)
2. Template `yaml_content` `max_iterations` 6/10/6 -> 15/20/15 via precise
   string REPLACE (F9)
3. BA prompt: append "with sections: Problem Statement, Proposed Solution,
   Acceptance Criteria, Error Handling" (F15)
4. `system_config` insert `circuit.breaker.max.tokens.per.run = 300000`
   (F2; the QA run consumed 153k tokens against the 100k default)

Linked fix: `OpenCodeAdkProvider.resolveMaxDuration` (L418-430) currently
translates `maxRounds * 2L` minutes into a secondary time cap. Remove that
translation - `maxTaskMinutes` (default raised 30 -> 45 in
`OpenCodeProperties`) becomes the sole time authority. `maxRounds` still
constrains LLM turn count via the system prompt, but no longer compresses the
wall-clock deadline.

Tests:
- `V43SeedConfigTest` (integration): asserts seed agent configs contain
  taskApprovalRequired:false; template max_iterations >= 15; BA prompt
  contains "Acceptance Criteria"; system_config token budget = 300000
- `OpenCodeAdkProviderTest.resolveMaxDuration_usesMaxTaskMinutesOnly`:
  small maxRounds + large maxTaskMinutes -> returns maxTaskMinutes

### 3.4 G4: Governance and entrypoints (F4, F10)

#### F4 - create_workflow rejects SDD kinds

File: `agent-control-tower/act-aria/src/main/java/io/aria/conductor/aria/tools/handlers/WorkflowToolHandler.java`

Hard guard in `createWorkflow()` (after step parsing): if any step kind is
BA/DEV/QA, return error:
"SDD workflow steps (BA/DEV/QA) must be created via instantiate_template to
ensure the SPEC_REVIEW gate. Use the approved 'development-workflow' template."

Soft guard: both Aria prompts (`AriaService.buildSystemPrompt` +
`AriaDefaultAgentInitializer.ARIA_SYSTEM_PROMPT`) add: "NEVER use
create_workflow for the BA->Dev->QA loop; always use instantiate_template."

Tests:
- `WorkflowToolHandlerTest.createWorkflow_rejectsSddKinds`
- `WorkflowToolHandlerTest.createWorkflow_genericKindsStillAllowed` (regression)

#### F10 - Template YAML editable

Files:
- `act-knowledge/.../dto/UpdateKnowledgeRequest.java`: add
  `private String yamlContent;`
- `act-knowledge/.../service/KnowledgeService.java`: in `updateKnowledge()`,
  set `.yamlContent(request.getYamlContent())`; when null, carry forward from
  the current version
  (`versionRepository.findByKnowledgeItemIdAndVersion(item.getId(),
  item.getCurrentVersion()).map(KnowledgeVersion::getYamlContent)`)
- Verify `KnowledgeVersionResponse` exposes yamlContent (add if missing) so
  the frontend can display/edit it

Tests:
- `KnowledgeServiceTest.updateKnowledge_preservesYamlContent`
- `KnowledgeServiceTest.updateKnowledge_withNewYaml_storesIt`
- Round-trip (core regression guard): create WORKFLOW item with YAML -> PUT
  update -> `instantiateTemplate` succeeds

### 3.5 G5: Lifecycle and recovery (F7, F17, F19)

#### F7 - Startup orphaned-run recovery

Files:
- `act-execution/.../engine/AgentLoopEngine.java`: add
  `@EventListener(ApplicationReadyEvent.class)` method - query
  `runRepository.findByStatusIn([RUNNING, INITIALIZING])`, mark each FAILED
  with errorMessage "Run orphaned by backend restart", set completedAt,
  publish `RunCompletedEvent` (lets WorkflowAutoChainer/SpecReviewCoordinator
  handle chain state through the normal failure path)
- `act-agent/.../repository/RunRepository.java`: add
  `List<Run> findByStatusIn(List<RunStatus> statuses)`

Re-queuing rejected: risk of double-executing side effects. Marking FAILED +
UI retry is the safe choice. Division of labor: startup recovery handles cold
restart (zero delay); ZombieRunReaper handles runtime death (120-min
threshold).

Test: integration - seed a RUNNING run, trigger the recovery path, assert
FAILED + RunCompletedEvent + chain state transition.

#### F17 - No-verdict retry hint

File: `act-execution/.../listener/WorkflowAutoChainer.java` (no-verdict branch)

New message:
"QA completed but no verdict submitted. The QA agent must call
submit_dod_review with verdict=PASS|DEFECT|SPEC_GAP before finishing. Retry
the step after fixing the QA tool configuration."

Test: update `WorkflowAutoChainerSddTest.qaCompletion_noVerdict_failsChain` to
assert the message contains "submit_dod_review".

#### F19 - resubmit-approval 400 error body

File: `act-knowledge/.../controller/SddWorkflowController.java`

Change `ResponseEntity.badRequest().build()` to
`ResponseEntity.badRequest().body(Map.of("error", e.getMessage()))`.

Test: `SddWorkflowControllerTest.resubmitApproval_chainNotWaiting_returns400WithBody`.

### 3.6 G6: UX and quality (F1, F3, F14, F18, F21)

#### F1 - ConfigureModal closed-state CSS

File: `act-dashboard/src/styles/index.css` (or wherever .modal is defined -
locate during implementation)

Add:
```css
.modal:not(.open), .modal-scrim:not(.open) {
  visibility: hidden;
  pointer-events: none;
}
```
Keep the opacity transition for fade-out animation. If `.modal` is defined in
another CSS file, migrate it into index.css (the design-system file).

Test: Playwright - open Configure panel, click Done/Close, assert scrim is
hidden and not intercepting pointer events.

#### F3 - Chain name ASCII sanitization

File: `act-agent/.../service/WorkflowService.java` (createAndStart name write
point)

```java
private static String sanitizeName(String name) {
    if (name == null || name.isBlank()) return "workflow";
    return name.replaceAll("[^\\x20-\\x7E]", "-").trim();
}
```

Test: `WorkflowServiceTest.createAndStart_sanitizesNonAsciiName`
("QA->Dev" arrow input -> "QA-Dev").

#### F14 - MarkdownViewer list wrapping

File: `act-dashboard/src/components/MarkdownViewer.tsx`

After the `<li>` substitution step, add:
```tsx
.replace(/(<li>.*<\/li>\n?)+/g, '<ul>$&</ul>')
```

Test: vitest - multi-item list wrapped in `<ul>`; single heading/paragraph
unaffected.

#### F18 - Spec content cleanup

File: `act-knowledge/.../sdd/SpecReviewCoordinator.java`
(`onBaStepCompleted` storage point)

Conservative cleanup before `upsertSpecKnowledge`:
1. If output contains a `# Spec` or `## ` heading, extract from the first
   heading onward (strip stream-of-consciousness preamble)
2. Strip trailing `SPEC_ID=<uuid>` marker (store the id as metadata, not in
   content)
3. Truncate to 50KB (DB bloat guard)
4. If no heading marker exists, store verbatim (never guess-truncate content)

Test: `SpecReviewCoordinatorTest.onBaStepCompleted_stripsMarkerAndTruncates`
- marked output -> marker stripped; unmarked -> verbatim; oversized ->
  truncated.

#### F21 - Approvals history shows decided items

File: `act-execution/.../controller/ApprovalController.java`

Change `listPending()` to accept an optional status filter:
```java
@GetMapping
public ResponseEntity<List<ApprovalDetail>> listApprovals(
        @RequestParam(required = false) ApprovalStatus status) {
    List<Approval> items = status != null
        ? approvalRepository.findByStatus(status)
        : approvalRepository.findAll();
    ...
}
```
No status -> all approvals (frontend filters pending/history client-side).
Existing callers passing PENDING keep exact behavior (verify each caller).

Tests:
- `ApprovalControllerTest.listApprovals_noStatus_returnsAllIncludingDecided`
- `ApprovalControllerTest.listApprovals_pendingStatus_filtersOnly`

### 3.7 G7: Environment and docs (F5, F20, F22)

| Item | Design |
|------|--------|
| F5 | `scripts/start-backend.ps1/sh`: add `mvn install -DskipTests -q` pre-step (idempotent, incremental) + CONTRIBUTING.md section "branch switch requires mvn install" |
| F20 | `application-h2.yml`: `adk.runtime.python` configurable via `${ADK_PYTHON:python}`; `adk.runtime.server-script` resolved against repo root (not CWD); start scripts set `ADK_PYTHON=py` on Windows |
| F22 | `scripts/start-backend.ps1`: preflight block - Java 21 / Maven / Docker Desktop running / DEEPSEEK_API_KEY - report each missing item |

G7 has no automated tests (environment scripts); verification is manual +
doc review.

## 4. CI Coverage Gap Analysis

Why CI shipped these 22 findings: three layers of mocks isolate CI from
real-world behavior.

1. Unit tests mock `AdkProviderRegistry` + `LlmClient` - no real LLM, no real
   ADK, no real timing
2. Integration tests (`SddWorkflowIntegrationTest`) use `@MockBean
   AdkProviderRegistry` with instant responses - iterations complete in
   milliseconds, never hitting deadlines
3. E2E (`sdd-workflow.spec.ts`) skips without an ADK runtime (always in CI)

Eight gaps and their closing strategies:

| Gap | Missed findings | Strategy |
|-----|-----------------|----------|
| Seed data values unasserted (only column existence checked) | F6, F9, F15 | V43SeedConfigTest |
| resolveMaxDuration round-to-time translation untested | BLOCKER A | resolveMaxDuration unit test |
| No PUT /knowledge -> instantiate round-trip | F10 | round-trip test |
| No startup recovery test | F7 | integration test |
| No governance guard test | F4 | guard unit tests |
| No MarkdownViewer unit test | F14 | vitest cases |
| E2E history tab non-asserting | F21 | assert decided approval visible after decision |
| Real LLM/ADK path fully mocked | BLOCKER A/B, F11, F16 | nightly real-LLM smoke job (DEEPSEEK_API_KEY gated): one minimal SDD loop to WAITING_APPROVAL |

Strategy assessment: the existing three tiers (unit/integration/E2E) cover
deterministic logic well (state machine, routing, idempotency). The systematic
blind spot is environment-dependent behavior (real models, sandboxes, long
connections, seed semantics). Four additions close it: seed-assertion tests,
fault-injection tests (WireMock retry), a nightly real-LLM smoke, and UX
assertion tests (modal/markdown/history). No new test framework needed.

## 5. Non-Goals

- Full async streaming (SSE) for opencode session messages (F8 architectural
  refactor)
- Java-side DSML parsing (Python ADK is the single parsing authority)
- Task-level rebuild-retry in executeTask (HTTP retry + fresh probe cover the
  observed failure modes; rebuild-retry is a follow-up if needed)
- Re-queueing orphaned runs (double-execution risk; FAILED + UI retry is safe)
- Code-level spec extraction beyond the conservative marker-based cleanup in
  F18 (no LLM-based summarization)

## 6. Test Plan Summary

- Java unit: OpenCodeHttpClientRetryTest, OpenCodeAdkProvider fresh-probe
  tests, WorkflowToolHandler guard tests, KnowledgeService yamlContent tests,
  WorkflowService name sanitize, WorkflowAutoChainer retry-hint assertion,
  SddWorkflowController 400-body test, ApprovalController status filter tests
- Java integration: V43SeedConfigTest, orphaned-run recovery, PUT->instantiate
  round-trip
- Python: DSML parser fixtures (XML, markdown JSON, plain text, unknown tool)
- Frontend: vitest MarkdownViewer list wrapping, Playwright modal close +
  history visibility
- Nightly: real-LLM smoke job (one SDD loop to WAITING_APPROVAL)
- Regression: full existing suites must stay green (mvn test all modules,
  pnpm build + vitest, playwright suites)
