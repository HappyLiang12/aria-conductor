# Aria turn resilience — failed turns visible, completions wake the conversation, dead relays heal

**Date:** 2026-10-03
**Status:** design approved in brainstorming; spec for review before planning
**Base:** targets `main` after PR #102 (run admission limit) merges; branch fresh from main.

## Context and evidence

Diagnosed live on 2026-10-03 against conversation `77dd02ff-bed5-4a2d-ae7a-b8fef325186c`
(the Okinawa research drill) under `systematic-debugging`:

- The conversation timeline (`GET /api/v1/aria/conversations/{id}` → `AriaConversationController.getTimeline`)
  is assembled from `SessionTrajectory` rows across the conversation's runs — not from
  `run.promptSeed`/`finalOutput`. A turn whose run dies BEFORE its first iteration records no
  assistant trajectory, so failures are structurally invisible: the user sees their own message
  and then silence. The dashboard's only surface is a `run.failed` notification (bell) plus the
  panel warning, which keys off *pending asks* and can be actively misleading ("Aria may be
  waiting for your approval — 14 pending asks") when the turn actually died of infra.
- The stuck turn (`5d6bb073`) failed at the workspace-upload relay phase:
  `Workspace upload failed for sandbox <id>: ... Failed to connect to localhost/127.0.0.1:59217`
  after 14 attempts inside the existing 90s window. Same class as run `932ad2e5` earlier the same
  day (2 occurrences; pre-existing, VM-dependent). For such sandboxes the relay port NEVER opens —
  waiting longer is useless.
- `AriaService.loadConversationHistory` intentionally excludes FAILED runs from LLM context
  ("prevent error-loop pollution"), with the side effect that after a failure Aria itself does not
  know the previous turn happened when the user retries.
- Runs dispatched by Aria's tool (`RunToolHandler` → `CreateRunRequest`) carry NO conversation
  linkage: `CreateRunRequest` has no `conversationId` field and the child runs are stamped with
  neither conversation nor dispatching turn. When children complete, nothing can route a wake-up
  back to the conversation (current behavior: silence until the user re-prompts; the orchestrating
  turn had explicitly handed control back with a question).

## Goals

- **A — Failed-turn visibility:** a failed Aria turn is visible in the conversation (error entry
  with reason and a retry affordance), the next turn's LLM context contains a one-line note about
  it (user seed preserved), the live stream reports the failure, and the side panel stops
  mis-reporting dead turns as waiting-for-approval.
- **B — Completion wake:** optionally-stamped child runs; when a dispatch group's children are all
  terminal, one notification lands on the conversation, and the dashboard offers a one-click
  "彙整" that sends a composed synthesis prompt through the normal chat path.
- **C — Relay heal:** when a sandbox's workspace-upload relay never establishes a connection
  within the window, destroy that sandbox and recreate once, then re-upload; the run survives.

## Non-goals

- No fully automatic synthesis turn (the one-click path is the approved compromise; token spend is
  user-triggered).
- No root-cause fix inside the OpenSandbox server's port-forwarding (out of our codebase).
- No CANCELLED-turn entry in v1 (user-initiated; revisit later).
- No new user-facing configuration knobs in v1 (recreate budget is a constant).

## Decision log (operator, 2026-10-03)

| Fork | Decision |
|---|---|
| Failed turn handling | Show error entry in the conversation AND give the LLM a one-line summary next turn (user seed preserved) |
| Completion wake | Notification + one-click synthesis (not auto-turn, not notify-only) |
| Relay-dead sandbox | Destroy + recreate once (not merely a longer window; not telemetry-only) |
| Phasing | C → A → B (C stops the bleeding, A makes failures visible, B is the largest and carries the migration) |

## Feature A — Failed-turn visibility

**A1. Timeline failure entry (backend, act-aria).**
`AriaConversationController.getTimeline` (or the service it delegates to) appends, right after a
FAILED run's trajectory entries, one synthetic `TimelineEntry`:

- `role: "assistant"`, `error: true` (new field), `runId: <failed run>`,
  `timestamp: run.completedAt` (fallback `updatedAt`),
  `content: "回合執行失敗：" + clip(errorMessage, 300)` (single line; empty message → "原因不明"),
  `retryPrompt: run.promptSeed` (new field) so the UI can offer retry without extra lookups.
- Only for runs in that conversation with status FAILED. Runs that produced trajectory content
  keep it and get the entry appended (partial outputs are not hidden).

**A2. LLM context note (backend, act-aria).**
`AriaService.loadConversationHistory`: stop excluding FAILED runs; their recorded trajectories
(the user seed and any partial assistant content) stay in context, and each FAILED run
contributes one appended synthetic assistant line from a fixed template, e.g.
`（系統註記：上一回合因「<clip(errorMessage, 200)>」失敗，未產生回覆。）`.
The fixed template keeps the note injection-safe; length caps bound the pollution the original
filter feared. Non-FAILED handling unchanged.

**A3. Panel warning state machine (frontend, AriaPanel.tsx; planning review 2026-10-03).**
The panel already renders `error:true` bubbles and offers Retry (`reportRunUncertain` /
`handleRetry`); the defects are (i) the copy keys off pending asks even when the turn itself
failed, and (ii) Retry is suppressed (`noRetry`) in that case. Fix: when the failure detail
identifies a failed turn, prefer the actual reason and allow retry; only fall back to the
pending-ask copy when the failure is NOT a turn failure:

1. Failure detail is a turn failure (from A4) **or** the latest timeline entry is the synthetic
   failure (A1) → "上一回合失敗：<reason 摘要>" + Retry (resends the failed turn's promptSeed).
2. Pending asks exist (and no turn failure) → existing approval copy.
3. Latest entry is a user message whose run is RUNNING/PENDING → "正在處理" copy.
4. Else → no warning.
Timeline-sourced failure bubbles carry `retryPrompt` (A1); stream-sourced bubbles carry the
reason from A4. `handleRetry` prefers the bubble's own `retryText` over the last sent message.

**A4. Streaming failure event (backend, act-aria; planning review 2026-10-03).**
The error SSE event already exists (`AriaStreamService.sendErrorSilent` emits name `error` with
`{"message": ...}` and completes). The change is additive: the payload for a failed turn also
carries `runId` and a `turnFailed: true` flag + the clipped run `errorMessage`, so the panel can
branch as in A3 without extra lookups (the plan pins the exact payload shape).

**Acceptance (A):** unit/IT — timeline entry appended for a failed pre-iteration run and absent
for completed runs; context assembly contains the seed + one-line note (bounded length);
`AriaPanel` tests for the four states. Live — resend after an induced failure shows the entry and
Aria references it on retry.

## Feature B — Completion wake (notification + one-click synthesis)

**B1. Linkage (schema + stamping).**
- Flyway `V65__run_dispatch_group.sql`: `ALTER TABLE runs ADD COLUMN dispatched_by_run_id UUID
  NULL` + index `idx_runs_dispatched_by`.
- `Run` entity field; `RunToolHandler` stamps `dispatched_by_run_id = <the dispatching Aria
  turn's runId>` on every child run it creates (the handler sees it via the engine-injected
  `_runContext`). Every dispatch — including a re-dispatch — forms its own group keyed by that
  turn's runId.
- **Children MUST NOT be stamped with `conversationId`** (planning review 2026-10-03): the
  conversation timeline and the LLM context both select runs by `conversationId`, so stamping it
  on children would merge the researchers' whole transcripts into the Aria chat and its context.
  The conversation is resolved through the PARENT run (`dispatchedByRunId` → parent →
  `parent.conversationId`), which already carries it.
- Exact seam: `ToolExecutionEngine` injects `_runId` / `_runContext` into tool arguments
  (precedent: `GitPackHandler` reads `_runId`); the plan pins it.

**B2. Group watcher (backend, act-aria).**
Listener on `RunCompletedEvent` (AFTER_COMMIT, fallbackExecution): if the completed run has a
`dispatchedByRunId`, load the group; when ALL group members are terminal, create exactly one
notification. Dedupe: check-then-insert scoped to the dispatch GROUP — skip when a
notification for the same dispatchedByRunId already exists on the conversation-scoped resource
(matched via the body's `Batch <dispatchedByRunId>:` marker); a LATER batch in the same
conversation notifies again. The residual simultaneous-completion race is accepted and documented.

**B3. Notification.**
Type `run.batch.completed`; title `子任務批次完成（N 個：成功 X／失敗 Y）`; `resourceType
= "CONVERSATION"`, `resourceId = conversationId`; body starts with `Batch <dispatchedByRunId>: `
(the dedupe marker) then lists child runIds + statuses (clipped).

**B4. One-click synthesis.**
- New endpoint `POST /api/v1/aria/conversations/{id}/synthesize` body `{dispatchedByRunId}` →
  returns the composed prompt (server-side template: list children with statuses, instruct Aria
  to read the reports it needs via its run tools and deliver the synthesis + next step). It does
  NOT run the turn itself.
- Frontend: the notification drawer renders a 「彙整」button for this type (`NotificationBell`
  row, gated on the notification type); it calls the endpoint and hands the prompt to the Aria
  panel through a small `window` CustomEvent (`aria:compose`) that the panel listens for, opens,
  and sends through its normal `streamMessage` path.

**Acceptance (B):** IT — a dispatch of N children stamps the group; last completion creates one
notification; duplicate completions do not double-notify; synthesize composes a prompt listing
all children. Live — the Okinawa flow: after "重跑" the batch completion notifies and one click
produces the synthesis.

## Feature C — Relay heal (bounded sandbox recreate)

**C1. Detection.** In the sandbox start path (`SandboxLifecycle`, opencode/SANDBOX), when the
workspace-upload window (existing `DEFAULT_UPLOAD_WINDOW_MS` = 90s, attempts + 5s-capped backoff)
is exhausted, classify the failure from the attempt evidence: all attempts failed with
connection-refused to the relay port ⇒ "relay never established".

**C2. Heal.** For that classification and `recreateBudget > 0` (constant default 1): destroy the
sandbox (best effort), create a fresh one through the existing create path (with its own retry
classification), re-run the upload window against the new sandbox. On success the run proceeds
normally; on second exhaustion the run fails as today.

**C3. Observability.** Log lines: `Relay never established for sandbox <id> (run <id>);
recreating (1/1)` and `Recreated sandbox <new-id> for run <id>`. No new config surface in v1.

**Acceptance (C):** unit — window exhaustion with connect-refused evidence triggers destroy +
create + re-upload, and a successful second upload completes the start; a non-relay failure
(e.g. execd 500 with retries) does NOT trigger recreate. Live — next occurrence of the class on
the local stack is survived (best effort).

## Risks and open items

- The LLM note could nudge Aria's behavior (accepted; that is the point) — bounded template +
  clip.
- Notification dedupe race (documented, tiny).
- Recreate adds one more sandbox creation on an already weak local VM (bounded to 1 per run).
- `TimelineEntry` gains fields (`error`, `retryPrompt`) — additive; frontend types updated in the
  same phase.
