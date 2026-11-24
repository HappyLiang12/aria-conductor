# Aria × Phase 3 AR Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:subagent-driven-development. Steps use `- [ ]`.

**Goal:** Deliver the Aria side of Phase 3: enforced frame ingest, `ar_render` v2 on the live channel, the `highlight_target` display tool, the AR grace/`ar` capability, the DeepSeek direct backup locator, and the ops tool tier with approvals.

**Architecture:** The bridge keeps the Qwen connection and owns the display tool's translation; Java keeps the socket, frame accounting, approvals and the DeepSeek locator. The phone receives `ar_render` v2 as a control event on the same live socket.

**Spec:** `docs/superpowers/specs/2026-11-24-aria-phase3-ar-design.md` (this repo). Phone contract: aria-glass `2026-11-24-phase3-ar-accuracy.md` X10, X12–X18.

## Global Constraints

- Image: JPEG, one per turn, ≤190 KiB raw / ≤256 KB base64; `frame_too_large`/`frame_invalid` never tear the socket.
- `ar_render` v2: `v:1`, unique `renderId`, `ttlMs` default 8000, `coord:"normalized"`, `bbox` `[x,y,w,h]` in `[0,1]`, ≤1 render per turn, `safetyAlert` optional/null.
- Display tool: no run row, no approval, no nested tool; box `[y0,x0,y1,x1]` 0–1000 → bbox.
- X16 grace: ≤300 ms after `speech_end`; no frame → say 睇唔到; unverified mechanism must be probed, with a recorded fallback.
- DeepSeek: `api.deepseek.com` / `deepseek-flash`, key only from env, stateless, 1.5 s, no retry, backup only, never delays speech.
- Ops tiers: reads direct; every write returns `PENDING_APPROVAL` + `approval_required{runId}`, executes only after REST, result `[tower]`; never declare approve/finalize/answer.
- Privacy: no base64/bearer/key/workspace/DeepSeek body in logs; metadata only.
- Existing tests stay green; no migrations.

## File Structure

- `packages/qwen-live-bridge/src/session.ts` — tool declaration, highlight interception, AR grace, `ar` event.
- `packages/qwen-live-bridge/src/translate.ts` — `highlight_target` → `ar_render` render translation.
- `act-aria/.../live/LiveWebSocketHandler.java` — frame-per-turn accounting, `ar` event, image-only commit refusal, `ar_render` control relay (already relays control), locator trigger.
- `act-aria/.../live/LiveToolExecutor.java` — ops read tools + write tools returning `PendingApproval`.
- `act-aria/.../live/LiveEventRouter.java` / `LiveRunService.java` — approval creation/park for writes.
- `act-aria/.../live/DeepSeekLocator.java` (new) — direct locator.
- Tests: bridge vitest, act-aria unit, act-app failsafe seam with the scripted fake bridge and a stub DeepSeek HTTP server.

---

### Task 1: Frame ingest enforcement

**Files:** `LiveWebSocketHandler.java`, `LiveWebSocketHandlerTest.java`, `LiveSessionLimitTest.java`.
- Track `frameSeenThisTurn`, `audioBytesSinceCommit`, reset on `speech_end`/provider commit.
- `frame` twice in one turn → `error{frame_invalid,retryable:false}` (second dropped); `commit{image:true}` with `audioBytesSinceCommit==0` → same error, nothing forwarded; an uncommitted frame at the next `speech_end` is dropped.
- Tests: one frame + audio + commit reaches the bridge; second frame refused; image-only commit refused and no `image`/`commit` reaches the fake bridge; 300 KiB still `frame_too_large`.
- Commit: `feat(live): enforce one frame per turn and require audio for an image commit`.

### Task 2: `ar_render` v2 + `highlight_target` (bridge)

**Files:** `session.ts`, `translate.ts`, `session.test.ts`, `translate.test.ts`.
- Add the function declaration (verbatim spec) to `TOOL_DECLARATIONS`; instruction line already added; do not add it to Java's tool executor.
- Intercept `response.function_call_arguments.done` where `name == "highlight_target"`: parse `label`, `box_2d[4]`, `text`; translate to `bbox` per D3; emit `control{event:{type:"ar_render",v:1,renderId,render:{primitive:"highlight",target:{label,bbox},text,ttlMs:8000,coord:"normalized",safetyAlert:null}}}` to Java; reply `conversation.item.create {function_call_output, output:"{\"ack\":true,\"renderId\":…}"}` + `response.create`; count one render per turn.
- Invalid box/short text: no render; answer the call with `{"ack":false}` and let the model say 睇唔到.
- Tests: declaration shape; translation values for the POC boxes; one render per turn; ack shape; invalid box; unknown tool names still forwarded to Java.
- Commit: `feat(live): render highlight_target as ar_render v2 on the live socket`.

### Task 3: AR grace (X16) and `ar` capability (X17)

**Files:** `LiveWebSocketHandler.java` (accept `ar` before/after hello, store, include in bridge hello), `session.ts` (turn_detection `create_response:false`, frame wait 300 ms, explicit response.create; AR-off path unchanged), tests.
- Probe the SG endpoint with the new `turn_detection` shape first (a small script run against the live bridge); record pass/fail in the report. On rejection, fall back to `semantic_vad_ec` auto-response, set a flag, and report the deviation to the phone.
- Tests (fake provider): AR on → after `speech_end` no auto `response.create` until the grace elapses or a frame/commit arrives; AR off → unchanged; `ar` before hello accepted; duplicate `ar` ignored.
- Commit: `feat(live): hold the AR turn for a frame and accept the ar capability`.

### Task 4: DeepSeek direct locator (X14)

**Files:** `DeepSeekLocator.java` (new), `LiveWebSocketHandler.java`/`LiveEventRouter.java` trigger, tests with a JDK `HttpServer` stub.
- `Optional<Render> locate(byte[] jpeg, String transcript)`; strict JSON `{box_2d,confidence}`; 1.5 s connect+read; no retry; key from `LLM_API_KEY`; never logs body.
- Trigger: on turn end (`turn_done`) when a frame was attached, the input transcript matches `睇下|邊度|邊個`, and no highlight render was emitted; emit one `ar_render` on the live socket; ignore low confidence; degrade silently.
- Tests: stub returns a valid box → one render; slow stub (>1.5 s) → none; malformed JSON → none; text-only turn → no call; privacy scan.
- Commit: `feat(live): add the DeepSeek direct locator as the AR backup`.

### Task 5: Ops tool tier (X15)

**Files:** `LiveToolExecutor.java`, `LiveEventRouter.java`, `LiveRunService.java`, tests.
- Reads: add `list_skills`, `list_runs`, `get_agent`.
- Writes: `start_agent_run` (catalog `run_agent` params) and `run_skill` (pending phone confirmation; stage the call so a new tool or `assign_skill`+`run_agent` can be selected); both return `PendingApproval`, create the approval on the lazy live run, emit `approval_required{runId}`, park; router executes after `ApprovalDecidedEvent`, injects `[tower]`.
- Never add `decide_approval`, approve/finalize/answer, agent/provider/memory/knowledge/kanban writes.
- Tests: reads; write returns PendingApproval and creates the approval; decision executes once; denial injects `[tower] denied`; no decision tools in the declared set.
- Commit: `feat(live): add the approval-gated ops tool tier`.

### Task 6: Seam E2E, privacy, gates, deploy

- Extend the scripted fake bridge to call `highlight_target`; assert the ordered phone frames (`ready`, transcripts, `ar_render` with bbox, `turn_done`), and the DeepSeek stub path.
- Privacy: no image/key/base64 in logs; DeepSeek body never logged.
- Gates: `gate act-aria`, `gate act-execution`, `gate act-mcp`, bridge `pnpm test && pnpm build`, live failsafe ITs; deploy 8080 from main; smoke + a spoken turn.
- Commit: `test(live): prove the Phase 3 seam and privacy`; PR.

## Self-review mapping

X10 → Task 1; X12/X13 → Task 2; X16/X17 → Task 3; X14 → Task 4; X15 → Task 5; X18 → Tasks 1–6. Open phone confirmations: `runId` omission (D1), `run_skill` shape, DeepSeek backup timing.
