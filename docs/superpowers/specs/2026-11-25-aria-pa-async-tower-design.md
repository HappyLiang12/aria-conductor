# Aria PA — Async TOWER Dispatch (Phase 4) — Design (2026-11-25)

Status: approved by the operator (2026-11-25), including the four defaults in §3.
**Deployed 2026-11-25 on 8080** (Java `f5ad8328`, bridge dist with the tower modes). Verified
live: an approved `start_agent_run` produced the child run's `run_event{origin:"dispatch"}`
progress, the 20 s reminder fired, and the child's failure arrived as
`run_event{state:"failed",origin:"dispatch"}` while the socket stayed open (the child cannot
complete while the LLM gateway's model weights are missing). The deterministic integration
test in the plan (Task 8) is still to land; the frames below are what the deployment sends.
`tower{text,mode}` is Java→bridge only and never crosses to the phone.
Scope: make a dispatched run non-blocking on the live (LIVE) channel: the voice turn ends
after an immediate acknowledgement, the dispatched run's lifecycle reaches the phone, and
its outcome comes back as a spoken TOWER notification. The SSE/chat path's parity is Phase 5
and is pinned here but not implemented (§10).
Related: extends the live bridge spec `2026-11-23-aria-qwen-live-bridge-design.md` (X7/X15
ops tier, TOWER injection) and the Phase 3 AR spec `2026-11-24-aria-phase3-ar-design.md`
(D7). Amends the PA experience design `2026-11-11-aria-pa-experience-design.md`: its Wave A
covers approval/queueing stalls, not the child-run lifecycle defined here.

## 1. Problem

On the live channel a gated write is approval-correct but lifecycle-blind:

- `start_agent_run` / `run_skill` return immediately after the operator approves
  (`LiveWriteService.startAgentRun` → `RunService.createRun`, dispatch after commit). The
  model gets `tool_result` with `[tower] {"runId":…,"status":"PENDING"|"RUNNING",…}` and the
  turn ends. The dispatched run then runs unobserved: the phone's chip settles at
  `finished` for the *turn's* run and nothing reports the child run.
- Nothing tells the phone the child run started, progressed, or finished, so the HUD either
  shows a stale chip or nothing at all. A failure is silent.
- The model has no instruction to say a transition line, so it may answer with silence or
  imply completion.
- On the SSE/chat path the turn runs the whole agent loop inline
  (`AriaStreamService.streamChat` → `AgentLoopEngine.startRunStream`), measured as >50 s of
  「思考中」 on the phone (glasses side, 2026-11-24). That is Phase 5.

## 2. Goals

1. A dispatched run never holds the voice turn after the approval decision: the turn ends
   within one model reply, and the phone returns to Listening.
2. The phone sees the child run's lifecycle with the same `run_event` vocabulary it already
   renders (`started|progress|finished|failed`), distinguishable from the turn's own run.
3. The outcome is spoken: a TOWER message on completion/failure, WHEN_IDLE by default and
   INTERRUPT for safety or short outcomes.
4. A long run is announced: one 仲做緊 line after N seconds; a hard timeout reports failure
   and stops the run.
5. Nothing new is exposed to the model: approvals/finalize/answer stay REST-only.

## 3. Operator decisions (2026-11-25, approved)

1. The transition line is spoken **after the approval**, not when the tool call arrives.
2. `N` = **20 s**, configurable.
3. Hard timeout = the run's wall-clock deadline when set, else **10 min**.
4. SSE closes the stream after the acknowledgement and reconciles through WS/REST
   (Phase 5; pinned, not built here).

## 4. Contract

### 4.1 Acknowledgement (unchanged shape, new text)

After the approval commits, the sequence to the bridge and the phone is exactly:

| # | Frame | Fields |
|---|---|---|
| 1 | `tool_status` | `{type,v:1,name:"start_agent_run",state:"running"}` |
| 2 | `run_event` | `{type,v:1,runId:<turn run>,state:"started",summary:"tool: start_agent_run"}` |
| 3 | `tool_status` | `{name,state:"approval"}` |
| 4 | `approval_required` | `{type,v:1,runId:<turn run>,approvalId,toolName,riskTier:"WRITE_LOCAL"}` |
| 5 | `tool_result` (bridge only) | `{type,id:<provider call id>,ok:true,output:"[tower] {\"runId\":<child>,\"agentId\":…,\"status\":\"PENDING\",\"maxIterations\":N}"}` |
| 6 | `tool_status` | `{name,state:"done"}` |
| 7 | `run_event` | `{runId:<turn run>,state:"finished",summary:"finished"}` |

Rows 1–7 are shipped today (verified in `LiveToolCallHandler:134`,
`LiveEventRouter.parkForApproval:148-149`, `LiveEventRouter.approve:330-340`,
`LiveToolExecutor.startAgentRun:262-273`). Phase 4 adds only the child-run frames (§4.2)
and the instruction (§4.4).

### 4.2 Child-run lifecycle — `run_event` with `origin:"dispatch"`

```
{"type":"run_event","v":1,"runId":"<child run uuid>","state":"started|progress|finished|failed",
 "summary":"<stable sentence>","origin":"dispatch"}
```

- `origin` is additive: absent means the turn's own run (today's semantics). The phone keys
  the run chip by `runId` and may show the origin.
- Emitted on: run started, each iteration/tool progress (coalesced to at most one per
  second per run), terminal status.
- `summary` is a stable, payload-free sentence: `running`, `iteration <n>`,
  `finished`, `failed: <stable reason>`; it never includes a prompt, tool arguments or run
  output.
- No frame is emitted for a run the socket did not dispatch, and none after the socket
  closed.

### 4.3 Outcome TOWER

On terminal status the run's text is injected through the existing bridge `tower` line,
extended with a mode:

```
{"type":"tower","v":1,"text":"<text>","mode":"idle|interrupt"}
```

- Completion: `[tower] <agent name> 已完成：<final output preview>`, preview bounded at 500
  chars with the same rule as tool output (no prompt, no workspace path).
- Failure / timeout / abort: `[tower] <agent name> 失敗：<stable reason>`.
- **WHEN_IDLE** (`mode:"idle"`, default): the bridge queues the item and injects it when no
  response is active and no deferred AR commit is held, then asks for one response.
- **INTERRUPT** (`mode:"interrupt"`): safety-flagged results and failures; the bridge
  cancels an active response (`response.cancel`), injects, and asks for one response.
- The phone renders it as a new voice turn; it does not receive a separate tower frame.

### 4.4 Model instruction (transition line)

Add to the live instructions: 「批准咗叫 agent 做嘢之後，用一句講已經開始（例如：等我查下），
唔好講已完成；完成或者失敗會另外通知。」 The `[tower]` start acknowledgement must be spoken
as a start, never as a result.

### 4.5 Reminder and timeout

- After **N = 20 s** (`aria.live.dispatch.reminder-ms`) with the run still active and no
  outcome TOWER sent, inject `[tower] <agent name> 仲做緊` (`mode:"idle"`), once per run.
- At the run's wall-clock deadline, or 10 min after dispatch when it has none
  (`aria.live.dispatch.timeout-ms`), stop the run through the existing run-stop service,
  then emit `run_event{state:"failed",summary:"failed: timeout",origin:"dispatch"}` and the
  failure TOWER with `mode:"interrupt"`.
- A run already terminal when a timer fires emits nothing.

## 5. Architecture

- **Dispatch registry (act-aria, per socket).** `LiveDispatchService` keeps
  `childRunId -> {socket, agentName, startedAt, reminderSent, towerSent}` for runs started
  by this socket's approved writes. The executor reports the child run id to the router;
  the registry is cleaned on socket close and on terminal status. No persistence: the
  authoritative row is the run itself.
- **Lifecycle listener.** One `@EventListener` on `RunStartedEvent`, `RunIterationEvent`,
  `RunProgressEvent`, `RunCompletedEvent`; it filters by childRunId in the registry and
  emits §4.2 to that socket only. Existing live-run filtering (`LiveEventRouter` filters by
  the turn's run id) is unchanged; the two never collide because the registry holds only
  child ids.
- **Timers.** Two scheduled actions per run: the 20 s reminder and the deadline. Both are
  cancelled on terminal status and on socket close; a fired timer re-reads the run state
  before acting (the run may have finished between scheduling and firing).
- **Bridge.** `session.ts` gains a tower queue: `idle` queues until `response.done` and no
  held commit; `interrupt` cancels an active response first. Both keep the "one response per
  message" rule the bridge already enforces.
- **Turn boundary.** No park: the turn's run settles at the tool result as today
  (`LiveEventRouter.approve`). The child run is independent; finalizing the socket never
  aborts it (the operator owns that through the existing stop).

## 6. Privacy and logs

- Tower previews and summaries are bounded and payload-free; prompts, tool arguments,
  tokens and workspace paths never reach a log line or a frame beyond the existing
  projections.
- Metadata only in logs: run id, origin, state, elapsed ms.
- The phone is the same operator authority that approved the run; no new egress.

## 7. Configuration

| Property | Default | Meaning |
|---|---|---|
| `aria.live.dispatch.reminder-ms` | `20000` | One 仲做緊 line after this long |
| `aria.live.dispatch.timeout-ms` | `600000` | Hard timeout when the run has no deadline |
| `aria.live.dispatch.preview-chars` | `500` | Bound on the completion preview |

## 8. Testing

- **Unit.** Registry add/cleanup; listener filters by child id and ignores the turn run;
  reminder fires once and not for a finished run; timeout stops the run and emits the
  failure frames; tower text bounded and payload-free; `origin` present only for child runs.
- **Bridge unit.** Idle queue waits for `response.done`; interrupt cancels an active
  response; one `response.create` per injected message; AR held commit defers an idle tower.
- **Integration (Spring, fake bridge).** Approve `start_agent_run`, publish the four run
  events for the child id, assert the phone frames (§4.2) and the bridge `tower` modes;
  socket close mid-run emits nothing further; a second socket's run never leaks.
- **E2E (deterministic harness).** A scripted run completes and the phone receives
  started → finished and one idle tower; a scripted long run gets the 20 s reminder and the
  timeout failure with run stopped.
- **Phone conformance.** The glasses harness asserts the §4.1 sequence, then
  `origin:"dispatch"` progress and one tower-driven model turn; SSE latency is Phase 5.

## 9. Rollout

Ship behind the existing live feature flag (`aria.live.enabled`); the two timers default on.
Deploy the bridge and backend together (the tower frame gains `mode`; old bridges ignore the
extra field and inject immediately, which is the INTERRUPT behaviour). No migration.

## 10. Phase 5 (pinned, not built here)

- SSE closes the stream after the acknowledgement; the child run's lifecycle and the outcome
  arrive through WS (the same `run_event`/tower semantics) and REST reconciles on mount.
- The chat surface renders the same chip and the same spoken-style notification; the
  approval cards are unchanged.
- Phase 5 reuses §4.2 and §4.3 verbatim; no second vocabulary.

## Out of scope

Follow-up questions to a finished run; run cancellation by voice; multi-run fan-out;
world-locked AR; HUD visuals (the phone mirrors the states, this spec only fixes frames).
