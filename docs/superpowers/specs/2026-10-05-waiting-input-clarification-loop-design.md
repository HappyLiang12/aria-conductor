# Clarification-Question Continuation: WAITING_INPUT Turn Loop

- Date: 2026-10-05
- Status: design approved by the operator (2026-10-05, brainstorming session; Approach 1 selected). Implementation not started.
- Scope: agent-control-tower (act-common, act-execution, act-agent, act-dashboard-api), act-dashboard.

## Problem

When a Qoder-core agent asks a clarification question mid-task, the question is the agent's
final turn message: the turn ends, the run is finalized COMPLETED, and the core child is torn
down (stop -> capture -> destroy -> release). The post-completion REVIEW_REQUEST ask the
operator sees is display-only. The operator answers in the card comments and clicks Approve;
the answer is never relayed anywhere and the run cannot continue.

Root causes (investigated 2026-10-05):

1. No waiting state: `RunStatus` has no input-waiting value
   (`act-common/.../common/model/RunStatus.java:3-7`); a turn end unconditionally finalizes
   (`agent-control-tower/act-execution/.../engine/AgentLoopEngine.java:429-457`,
   `completeRun` `:1745-1837`).
2. No operator-to-core text channel: kanban transition comments are dropped
   (`KanbanService.transition`, `agent-control-tower/act-execution/.../kanban/KanbanService.java:207-242`);
   `/decide` discards `reason` for native asks; `PermissionReply` carries an option id only
   (`PermissionCoordinator.java:289-322`); `POST /runs/{id}/inject` never reaches a coordinated
   Qoder session.
3. Post-completion asks are display-only: `KanbanReviewAskCreator` runs on
   `RunCompletedEvent` (`KanbanReviewAskCreator.java:44-114`); `ApprovalAnswerService.answer`
   stores text and nothing reads it back ("no run resume", `ApprovalAnswerService.java:18-27`).
4. COMPLETED is terminal: `RunService.VALID_TRANSITIONS`
   (`act-agent/.../service/RunService.java:31-42`); `KanbanTransitionService.resume()` refuses
   any linked run that is not PAUSED (`KanbanTransitionService.java:247-255`).

## Operator Decisions (2026-10-05)

- D1: Same-session true continuation. The operator's answer must reach the SAME ACP session
  as a follow-up turn; no new-run relay.
- D2: Explicit new `RunStatus.WAITING_INPUT`; do not overload PAUSED.
- D3: Infinite wait. No auto-timeout while waiting. The operator finalizes manually. The
  held admission slot and core child are an accepted cost.
- D4: Marker-based detection with a question-mark heuristic fallback. A detection miss
  degrades to today's behavior (COMPLETED + review card).
- D5: Answer rides the existing `POST /api/v1/approvals/{id}/answer` route, extended to wake
  the engine. The ask uses `AskType.QUESTION` with a NEW `ApprovalSource.CLARIFICATION` so
  the card-layer bulk approve (which filters `source = LEGACY_GATE`,
  `ApprovalRepository.java:87-100`) can never auto-answer a clarification.
- D6: The answer route stays NOT operator-gated (its current state,
  `ApprovalController.java:240-252`). Recorded deviation from
  `docs/superpowers/specs/2026-09-22-agent-core-execution-modes-design.md` §6.2: the local
  deployment is a single-operator localhost stack; approval `/decide` gating is unchanged.

## Design

### 1. Run lifecycle

- Add `RunStatus.WAITING_INPUT` (`act-common/.../model/RunStatus.java`).
- Transitions (`RunService.VALID_TRANSITIONS`): `RUNNING -> WAITING_INPUT`,
  `WAITING_INPUT -> RUNNING` (answer accepted), `WAITING_INPUT -> COMPLETED` (manual
  finalize / deny), `WAITING_INPUT -> FAILED` / `-> CANCELLED` (existing abort/cancel paths).
- No `RunCompletedEvent` while waiting: the admission permit stays held
  (`RunAdmissionQueue.settle` only reacts to `RunCompletedEvent`,
  `RunAdmissionQueue.java:100-115`), matching PAUSED behavior today.
- Waiting runs are excluded from `ZombieRunReaper` (60s sweep fails RUNNING runs with no
  active context, `ZombieRunReaper.java:39-73`) and are treated as active by
  `HousekeepingService` partitions (`HousekeepingService.java:77-80,168`) and
  `KanbanService.guardLinkedRunNotActive` (`KanbanService.java:330-341`).
- Backend restart: `recoverOrphanedRuns` (`AgentLoopEngine.java:579-605`) adds WAITING_INPUT
  to the orphan set -> FAILED with "Run orphaned by backend restart". The core child cannot
  survive the restart; this is the honest terminal.

### 2. Engine turn loop

**Mechanics correction (2026-10-05, during planning):** the original draft placed the park
in the engine's `executeCoreRun`, but `CoreExecutionService.execute` finalizes the runtime
(stop -> capture -> destroy -> release) inside itself before returning
(`CoreExecutionService.java:165-218`) — a park after `launcher.execute` returns would find
the core child already destroyed. The turn loop therefore lives INSIDE
`CoreExecutionService.execute`, between the prompt completing and `finalizeRuntime`:

`AgentLoopEngine.executeCoreRun` (virtual thread per run,
`AgentLoopEngine.java:141,310-323,429-457`) stays the driver; the loop is:

1. Prompt the core (unchanged first turn, frozen binding deadline).
2. On turn end, inspect `result.finalOutput()`.
3. If the run was cancelled -> existing CANCELLED path (unchanged).
4. If the turn ends with a clarification question (see detection): publish
   `TurnCompletedEvent` (engine records usage/iteration/trajectory for the intermediate
   turn), publish `RunWaitingForInputEvent`, then park the virtual thread on an answer
   future held by a new `RunInputCoordinator` (`RunContext.awaitResume` pattern,
   `RunContext.java:168-203`). The engine's event listener persists `WAITING_INPUT` and
   creates the CLARIFICATION ask; no `RunCompletedEvent` is published, so the admission
   permit stays held.
5. Wake-up cases:
   - Answer received (`RunInputCoordinator.submitAnswer`) -> the coordinator publishes
     `RunInputReceivedEvent` (engine flips the run back to RUNNING), records the answer as
     a `SessionTrajectory` row is done by the answer service, composes a follow-up
     `CoreTask` with `userPrompt = <answer>` and empty history (the ACP session carries
     conversation context), re-prompts the SAME session, and loops back to step 2.
   - Finalize signal (deny / manual finalize / external cancel) -> loop exits with the
     question turn's result as the run's `CoreResult`; the existing finalize chain
     (stop -> capture -> destroy -> release, `CoreExecutionService.java:460-473`) and
     `completeRun` path run unchanged.
6. Token usage and trajectory rows accumulate per turn (intermediate turns via the
   `TurnCompletedEvent` listener, the final turn via the existing `executeCoreRun` code).

Scope guard: the loop is gated to the Qoder core (`spec.coreId()`), and the
`[NEED-INPUT]` seed rule is appended by `QoderCoreSession.prompt` — OpenCode runs are
byte-identical to today. A follow-up turn gets a fresh per-turn deadline window (the
frozen binding deadline is absolute and would be long expired after an unbounded wait);
the PARK itself is unbounded per operator decision D3.

### 3. Detection

- The prompt seed's system rules gain one rule (composed where the task prompt is built for
  core runs): "If you need the operator's answer to a clarifying question before you can
  continue, end your turn with the question as the final line prefixed with `[NEED-INPUT]`.
  Never use `[NEED-INPUT]` for anything else."
- Detection on turn end, against the final assistant message (`result.finalOutput()`):
  1. Marker: the last non-empty line starts with `[NEED-INPUT]` (the ask content is the
     remainder).
  2. Fallback heuristic: the last non-empty line ends with `?` or `？`.
- Miss -> today's behavior (COMPLETED + review card + Request-changes rework). False
  positive -> the run waits with the question visible; the operator finalizes manually.

### 4. Ask plumbing

- New enum value `ApprovalSource.CLARIFICATION` (`act-common/.../model/ApprovalSource.java`).
- The ask row: `approvalType = TOOL_CALL` (existing column semantics), `askType = QUESTION`,
  `source = CLARIFICATION`, `runId` set, `content` = question text + run id, `expiresAt` =
  the run's frozen task deadline reference for display only (no expiry job touches it).
- Card-layer bulk approve keeps filtering `source = LEGACY_GATE`; add an explicit negative
  test that CLARIFICATION asks are never bulk-settled.
- `KanbanReviewAskCreator` / run-end settle paths are untouched (no `RunCompletedEvent`
  while waiting).

### 5. Answer and finalize channels

- `POST /api/v1/approvals/{id}/answer` (`ApprovalController.java:240-252` ->
  `ApprovalAnswerService`): after storing the answer, resolve the parked run. The answer
  service calls a new internal port (act-common `port/`, e.g. `RunInputPort`) implemented by
  `AgentLoopEngine` (same pattern as `RunRuntimeControlPort` /
  `CoordinatedRunRuntimeControl`): `submitAnswer(runId, answer)` completes the run's answer
  future; unknown/not-waiting run -> 409.
- The ask row is settled (status APPROVED, decision reason = the answer text).
- The answer text is also appended as a `SessionTrajectory` row (role=user) for auditability.
- Idempotency: a second answer on a settled ask -> 409 (existing behavior).
- Finalize is a separate, NOT operator-gated route: `POST /api/v1/runs/{id}/finalize` in
  `RunController` (sibling of pause/resume, same port pattern), implemented by the engine:
  settles the CLARIFICATION ask (DENIED, "finalized by operator"), sends the finalize
  signal through `RunInputPort`, and the run leaves the loop to the existing
  `completeRun(ctx, COMPLETED)` path with the question preserved in the outcome text.
  Deny intentionally does NOT reuse `/decide` (`ApprovalController.java:152-221`): that
  route is operator-gated and would re-introduce the operator-session friction this design
  is decoupling from.

### 6. Dashboard

- `RunStatus` TS union + `StatusBadge` + `OpsPage.STATUS_TONE` + `ReviewPanels.runOutcome`
  mappings gain WAITING_INPUT (amber, "Waiting for your answer").
- `KanbanService.runOutcome` maps WAITING_INPUT; linked card renders a waiting banner with
  the question, an answer box, and two actions: `Answer & continue` (calls `/answer`) and
  `Finalize` (calls `POST /runs/{id}/finalize`). The card footer Approve is replaced by the
  waiting banner for waiting runs — the operator's old "answer in comments + Approve" habit
  has no effect on a waiting run, by design; the answer box is the only channel.
- WebSocket: `RunWaitingForInputEvent` -> `EventBroadcastListener` broadcasts
  `run.waiting_input` on the existing events topic (`EventBroadcastListener.java:70-89`).
- The dead `pendingAskCount` guard on the single-item kanban payload is a known separate
  defect (investigation 2026-10-05, F8); the waiting banner does not depend on it.

## Testing

- Unit: detection (marker / heuristic / miss); transition map additions; CLARIFICATION
  source never bulk-settled; reaper + housekeeping exemptions; restart recovery marks
  WAITING_INPUT FAILED; admission permit retained while waiting.
- Integration (mock core adapter, act-test-support): full loop — hold -> ask created ->
  answer -> same-session re-prompt -> next turn -> finalize; deny -> finalize with question
  in outcome; cancel while waiting; restart recovery.
- Dashboard (vitest): waiting banner rendering, answer submit, finalize action, status tone.
- Cadence per operator standard: per-task impact tests, wave integration runs, full
  regression at phase end; pre-merge operator drill on the local stack with a real Qoder
  agent asking a clarification.

## Out of scope

- Operator session provisioning, silent approval-refusal surfacing, CSRF multi-tab
  (the "need credential" bug cluster) — aligned separately.
- Qoder runtime credential refactor to the llm-config pattern — direction given by the
  operator 2026-10-05; separate spec.
- Qoder-native question tools that may surface as ACP `request_permission` (they already
  flow the permission-ask path); verify during implementation, no design change.
- Storing kanban transition comments (`KanbanItem` has no comment column).
