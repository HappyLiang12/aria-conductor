# 2026-10-03 Run admission limit: bounded concurrent runs with Aria's reserved slot

Status: design approved by the operator (2026-10-03); implemented 2026-11-11 (`RunAdmissionQueue`, see the amendment below); the engine call site that supplies the conversation id SHIPPED on 2026-11-11 (`AgentLoopEngine.java:281` passes `run.getConversationId()` to the four-argument `acquire`), so the per-conversation guarantee is exhibitible and is exercised by the live PA bar.

Amended 2026-11-11 (operator decision, Aria PA experience design, section 2 item 2):
the Aria reservation's default rises from 1 to 3 and Aria runs serialize per
conversation. The changed decisions and notes are marked "amended 2026-11-11" below.

## Problem (evidence)

Under a large fan-out (the operator's hotel-research batch), the podman/OpenSandbox daemon breaks on concurrent sandbox creation: the log from the f7d0ad88 stack shows **17 `DOCKER::SANDBOX_EXECD_DISTRIBUTION_FAILED` 500s** ("passing bulk input to subprocess: write |1: broken pipe") and concurrent `pre_send` upload retries. The create-retry classification (PR #100) engages (`Sandbox creation attempt X/3 failed … retrying`) but runs still fail when all three attempts hit the same contention. The lever that addresses the root cause is **bounding how many runs execute at once**; everything else queues.

## Decisions (operator, 2026-10-03)

- **D1** — A **global admission limit** gates every dispatch path (API, Aria, kanban) equally, at the engine (before the core launches / a sandbox is created). Not a kanban-only gate.
- **D2** — **Aria keeps a reserved pool, serialized per conversation** (amended 2026-11-11): the regular cap is 6 concurrent runs (`aria.runs.max-active`, 0 = unlimited) plus **3** reserved concurrent Aria runs (`aria.runs.aria-reserved`, 0 = unlimited; was 1); a worker flood can never starve Aria entirely. Aria runs also **serialize per conversation**: a waiter is granted only while the global Aria count is below the cap and no active Aria run shares its conversation, so a run parked on an approval blocks its own conversation only and other conversations proceed. Granting scans the FIFO deque and **skips** a waiter whose conversation is busy instead of stopping at the head; Aria runs of the same conversation queue FIFO among themselves.
- **D3** — A run that waits for a slot **stays `PENDING`** and its mirror card **waits in TODO**; when the run actually starts, the card moves to `IN_PROGRESS` (the operator's mental model: "other tasks wait in TODO").
- **D4** — The per-run deadline must not burn while queued: the frozen binding (settings + deadline) is created **at admission**, not at queue entry.

## Design

### 1. Admission mechanism

- New in-process `RunAdmissionQueue` component. Acquisition happens at the **core-launch boundary** — the gate sits above `CoreRunLauncher.execute`, whose first call freezes the binding, so the freeze (settings + deadline) happens when the run is admitted, never while queued, and no sandbox is created before a permit is held. The run remains `PENDING` while waiting.
- **Release**: every terminal transition (COMPLETED, FAILED, CANCELLED, ABORTED) releases the permit. `PAUSED` counts as occupying a slot (its sandbox is alive). Release is driven by the existing run-completion events so no terminal path can leak a permit.
- **FIFO** by run `createdAt`; when a permit frees, the next admitted run starts (its binding/deadline freeze then).
- **Cancel while queued**: the existing cancel path for PENDING runs dequeues it (no permit was taken). Departed/cancelled entries are dropped lazily.
- **Restart resilience**: on boot, `PENDING` non-terminal runs are re-enqueued (FIFO by `createdAt`), so a restart cannot strand queued runs.

### 2. Slots and configuration

- `aria.runs.max-active` (default **6**; `0` = unlimited, today's behavior) — concurrent non-Aria runs.
- `aria.runs.aria-reserved` (default **3**, amended 2026-11-11 from 1; `0` = unlimited) — concurrent Aria-assistant runs; Aria runs never consume the worker pool beyond this reservation, and workers never take the Aria reservation. At most one active Aria run per conversation: the queue enforces it as soon as the caller supplies `Run.conversationId` (that engine call site lands with Task 5; until then every caller passes `null`, which is never isolated).
- Effective concurrency = up to `max-active` workers + `aria-reserved` Aria runs, with at most one active Aria run per conversation.

### 3. Card UX (waiting in TODO)

- The mirror card is **born `TODO`** and the run's **actual start** moves it to `IN_PROGRESS`. This requires the missing **core-path start signal** (today only the legacy turn loop publishes `RunIterationEvent`): the run-owned coordinator publishes a start event when the attempt begins; `RunKanbanAutoCreator` reacts with the existing TODO→IN_PROGRESS hook.
- This supersedes PR #100's "born IN_PROGRESS" mechanism: an immediately-admitted run shows a ~1 s TODO→IN_PROGRESS transition (accepted); a queued run honestly shows TODO ("waiting for a slot"); completion settles to REVIEW/CANCELLED exactly as today.

### 4. Edge cases

- `max-active=0` removes the worker cap; `aria-reserved=0` removes the Aria slot cap only (amended 2026-11-11: the Aria pool is no longer fully unbounded, the per-conversation rule still applies, so one conversation stays serialized at `0`).
- Aria chat UX (amended 2026-11-11): a turn that parks on an approval holds only its own conversation; a turn in another conversation is admitted under the global cap while the first waits, and the parked turn's *slot* is not released (the run still owns a live core process, so releasing it would make the resource accounting lie). Within one conversation — or once the global Aria cap of 3 is reached — a new chat turn queues, so Aria replies become slower instead of piling up sandboxes or hiding behind a question nobody can see; recorded as an accepted UX change, originally with a single Aria slot.
- Interaction with the review-flow batch (spec 2026-10-03-review-flow-ux-design): "Rework all failed" dispatches many runs — they will now queue behind the admission limit; the confirm dialog's "N runs will be dispatched" stays accurate (they are dispatched, then queued).

### 5. Testing

- Unit (admission queue): cap enforcement, FIFO order, Aria reservation, release on each terminal status, cancel-while-queued, boot re-enqueue, `0`-unlimited.
- Integration: engine launch respects the cap; binding deadline freezes at admission (not queue entry).
- Card: run-born-TODO + start-signal moves to IN_PROGRESS; queued run keeps TODO; end states unchanged.
- Live: with defaults (6 workers + 3 Aria slots) dispatch 8 worker runs → 6 execute, 2 queue as PENDING with TODO cards; completing one starts the next; verify no daemon 500s under the capped fan-out. For the Aria pool, park one conversation's run on an approval and confirm a second conversation's turn is admitted while the first waits — the engine call site that supplies `Run.conversationId` shipped on 2026-11-11 (`AgentLoopEngine.java:281`), so this check is now exhibitible and belongs to the live PA bar rather than to a future task.

## Out of scope

- Per-agent or priority-based scheduling (global FIFO + Aria reservation only; revisit if demonstrated).
- A distributed/persistent queue (single-process monolith; boot rebuild covers restarts).
- Reworking the run status machine (queued runs stay `PENDING`; no new status).
