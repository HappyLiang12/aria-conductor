# Aria × Qwen Live Bridge Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development or superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Ship the Aria half of the phone's live loop: an operator-authenticated WebSocket at `/api/v1/aria/live`, a per-socket Qwen realtime bridge child, the eleven-event translation with `v:1`/`seq`, read-only tools with a lazily created live run, approval/finalize, image input, and metadata-only logging.

**Architecture:** Java owns the socket, auth, limits, frames, runs and approvals. A Node package (`packages/qwen-live-bridge`) owns the DashScope connection and event allowlist, spoken to over stdio JSON lines. Credentials reach the child only through its environment. TOWER injection is a Qwen `conversation.item.create` message item.

**Tech Stack:** Java 21 / Spring Boot 3.4.5 / spring-websocket (already a dependency of act-aria), Node 20 + `ws` 8, pnpm, Vitest, Maven lanes.

**Spec:** `docs/superpowers/specs/2026-11-23-aria-qwen-live-bridge-design.md` (approved 2026-11-23). Phone contract: `aria-glass/.worktrees/gemini-dual-loop/docs/superpowers/plans/2026-11-23-phase2-qwen-live-mvp.md` §"Aria-side contract" (read-only reference; never edit that repo).

## Global Constraints

- Path is exactly `GET /api/v1/aria/live`. Non-upgrade → 426; missing/invalid operator bearer → 401; worker bearer → 403.
- `ready` is emitted by Aria as soon as `hello` validates (does not wait for SG); `v:1`, `maxSessionSec:7200` always.
- Subprotocol: echo `aria-live.v1` when offered; a different offered value → `ready` then close 4002; no header accepted.
- Server events: exactly the eleven types plus binary audio; unknown Qwen events never cross; every event carries `v:1`.
- `speech_end.tMs` = bridge receive wall-clock epoch ms. `interrupted` precedes any further audio delta. `output_transcript`/`input_transcript` `seq` monotonic per stream.
- Error codes exactly `auth_failed, capability_mismatch, frame_too_large, frame_invalid, provider_rate_limited, provider_unavailable, session_expired, bridge_internal`; close codes 4001/4002/4012/1011.
- Binary out is 24 kHz PCM16LE mono; binary in is 16 kHz PCM16LE mono, chunks ≤ 32 KiB; text frames > 64 KiB dropped.
- `frame` is JPEG only, base64 ≤ 256 KiB; oversized/invalid answers `frame_too_large`/`frame_invalid` without teardown.
- Live run: created only on the first tool call of a turn; `RunRepository.save` without `RunStartedEvent`; audited; `ABORTED` on `/finalize`; `approval_required` always carries its runId.
- Privacy: no audio/image/base64/bearer/key/workspace in any log line, Java or Node.
- Credentials file `~/.aria-conductor/qwen-live.env` (0600) with `DASHSCOPE_API_KEY` and `DASHSCOPE_WORKSPACE`; sourced from `~/.config/live-spike/{key,workspace}` at deploy time without printing.
- Existing tests stay green; Python-free; no new Java dependency.

---

## File Structure

New Java (act-aria unless noted):

- `config/LiveProperties.java` — `aria.live.*` bindings.
- `live/LiveUpgradeFilter.java` — 426 for non-upgrade GETs.
- `live/LiveHandshakeInterceptor.java` — operator auth + offered subprotocol.
- `live/LiveWebSocketConfig.java` — registers handler/interceptor/filter.
- `live/LiveWebSocketHandler.java` — hello/ready, caps, frames, routing.
- `live/LiveSession.java`, `live/LiveSessionRegistry.java` — per-socket state.
- `live/LiveBridge.java` (interface), `live/LiveBridgeFactory.java`, `live/QwenLiveBridgeProcess.java` — child process seam.
- `live/LiveCredentials.java` — env-file loader (0600, never logs values).
- `live/LiveRunService.java` — lazy run, transitions, audit.
- `live/LiveToolExecutor.java` — fixed read-only tools; `ToolOutcome` sealed type.
- `live/LiveEventRouter.java` — approval/run events → tower injection.
- `live/LiveRunPark.java` (act-common port) + `LiveRunParkAdapter.java` (act-execution).

New Java tests: same packages under `src/test/java/.../live/` for units; the endpoint and seam tier are failsafe tests in `act-app` (`LiveEndpointIntegrationTest`, `LiveEndToEndIntegrationTest`) extending `BaseH2IntegrationTest` (in-memory H2, random port) and driving `java.net.http.WebSocket` with the operator bearer.

New Node package `packages/qwen-live-bridge/`: `package.json`, `tsconfig.json`, `src/{index,protocol,session,translate,log}.ts`, `src/__tests__/*.test.ts`, `scripts/smoke.mjs`, `test/fake-bridge.mjs`.

Modified: `act-execution/.../security/OperatorAuthorityResolver.java` (extract transport-neutral authorize), `act-execution/.../runtime/RunInputCoordinator.java` (live parks), `act-mcp/.../tools/*` + `ToolCatalog` (add `get_time`), `act-app/src/main/resources/application.yml` (live properties default off), `scripts/start-backend.sh` (export bridge script path when enabled), `docs/` deployment note.

---

### Task 1: Operator auth, endpoint skeleton and hello/ready

**Files:** `OperatorAuthorityResolver.java` (refactor), `live/LiveProperties.java`, `live/LiveUpgradeFilter.java`, `live/LiveHandshakeInterceptor.java`, `live/LiveWebSocketConfig.java`, `live/LiveWebSocketHandler.java` (minimal), tests.

**Interfaces:**
- Produces: `OperatorAuthorityResolver.authorizeTransport(String authorization, String remoteAddress)` returning `ActorPrincipal`, throwing `SecurityException` (401) / `ForbiddenMutationException` (403). `resolveOperator(HttpServletRequest, boolean)` keeps its behaviour and calls it.
- Produces: `/api/v1/aria/live` accepting upgrade; `hello` → `ready`.
- Produces: `LiveProperties.bridgeScript()`, `credentialsFile()`, `readyDeadlineMs()` (5000), `maxSessionSeconds()` (7200).

- [ ] **Step 1: Failing resolver test** — add to `OperatorAuthorityResolverTest`:
  - `authorizeTransport("Bearer " + operatorCredential, anyAddress)` → operator.
  - `authorizeTransport(null, "192.168.0.5")` → `SecurityException`.
  - `authorizeTransport("Bearer " + workerToken, "192.168.0.5")` → `ForbiddenMutationException`.
  - `authorizeTransport(null, "127.0.0.1")` → operator.
- [ ] **Step 2: Run it** — `bash scripts/mvn-lane.sh inner act-execution -Dtest=OperatorAuthorityResolverTest` — RED (method missing).
- [ ] **Step 3: Refactor** — extract the credential/loopback tail of `resolveOperator` verbatim into `authorizeTransport`; add `private boolean isLoopback(String address)` used by both. No behaviour change to HTTP tests.
- [ ] **Step 4: Failing endpoint tests** — `act-app/src/test/java/io/aria/conductor/app/LiveEndpointIntegrationTest` (extends `BaseH2IntegrationTest`, `java.net.http.WebSocket`):
  - plain GET `/api/v1/aria/live` → 426.
  - WS connect without bearer → handshake failure (401).
  - WS connect with worker token → 403.
  - WS connect with operator bearer, send `{"type":"hello","v":1,"conversationId":"conv-live","audio":{"inRate":16000,"outRate":24000,"format":"pcm16le","channels":1},"mic":true,"ar":false}` → first text frame is `ready` with `v==1`, `maxSessionSec==7200`, within 1 s.
  - Same offered `Sec-WebSocket-Protocol: aria-live.v1` → response header echoes it.
  - Offered `aria-live.v2` → `ready` then close 4002.
  - No hello within 5 s (test override 200 ms) → close 4002.
- [ ] **Step 5: Implement** — `OncePerRequestFilter` answers 426 for GET without `Upgrade: websocket`; interceptor authenticates before upgrade; handler parses hello with Jackson, validates v/conversationId/audio, emits `ready`, schedules the hello deadline, closes 4002 for subprotocol/hello violations. Handler is wired with a no-op `LiveBridgeFactory` until Task 2.
- [ ] **Step 6: Run** — `bash scripts/mvn-lane.sh inner act-execution -Dtest=OperatorAuthorityResolverTest` and `bash scripts/mvn-lane.sh itg act-app -Dit.test=LiveEndpointIntegrationTest` — GREEN.
- [ ] **Step 7: Commit** — `feat(live): authenticate and open the aria live websocket`.

### Task 2: Session state, bridge process seam and limits

**Files:** `live/LiveSession.java`, `live/LiveSessionRegistry.java`, `live/LiveBridge.java`, `live/LiveBridgeFactory.java`, `live/QwenLiveBridgeProcess.java`, `live/LiveCredentials.java`, `live/LiveWebSocketHandler.java` (wire), `src/test/resources/live/fake-bridge.mjs`.

**Interfaces:**
- Produces: `LiveBridge { void send(String json); void sendBinary(byte[] pcm); void close(); }`, `LiveBridgeFactory.open(LiveSession session, LiveBridgeListener listener)`.
- Produces: `LiveBridgeListener.onControl(String json)`, `.onAudio(byte[] pcm)`, `.onExit(int code)`.
- Produces: `LiveSession { id, conversationId, ws, runId (nullable), bridge, offerSubprotocol }`.

- [ ] **Step 1: Failing tests** — `LiveSessionLimitTest`: 64 KiB+ text frame dropped; 32 KiB+ binary dropped; `frame` with >256 KiB base64 → `frame_too_large`, socket remains open; non-JPEG mime → `frame_invalid`; `hello` twice → 4002.
- [ ] **Step 2: Failing process test** — `QwenLiveBridgeProcessTest` spawns `node src/test/resources/live/fake-bridge.mjs` (echoes `{"type":"control_ack"}` and forwards one audio line), asserts env contains `DASHSCOPE_API_KEY` only from the provided map, stdout control is parsed, stderr is not surfaced raw, and `close()` reaps the child.
- [ ] **Step 3: Implement** — `ProcessBuilder` with env map; line reader on stdout (Jackson `ObjectMapper`), stderr reader through the redactor; writer with UTF-8; `onExit` closes the socket with 1011 if it was not a phone-initiated close.
- [ ] **Step 4: Wire handler** — after `ready`, open the bridge with a `hello` payload built by Java from the conversation's last 20 user/assistant text entries (`{"type":"hello","v":1,"conversationId":…,"context":[{"role":"user|assistant","text":"…"}]}`); map phone `text`→bridge `text`, `mic{on:false}`→bridge `mic` and stop forwarding audio, `cancel`→bridge `cancel`. Forward phone binary as `{"type":"audio","dir":"in","dataBase64":…}`; bridge `{"type":"audio","dir":"out"}` → binary to phone; bridge control JSON → text to phone. Phone close → bridge close. `ready.sessionId` is Aria's per-socket UUID; the Qwen session id never leaves the bridge.
- [ ] **Step 5: Extract frame validator** — `live/LiveFrameValidator.java` (pure: mime, base64 decode, ≤256 KiB) used by the handler; unit-tested in Task 2.
- [ ] **Step 6: Run** — `bash scripts/mvn-lane.sh inner act-aria -Dtest="LiveSessionLimitTest,QwenLiveBridgeProcessTest,LiveFrameValidatorTest"` — GREEN.
- [ ] **Step 7: Commit** — `feat(live): own one bridge child per socket with frame limits`.

### Task 3: Read-only tools and the lazy live run

**Files:** `act-mcp/.../tools/TimeTools.java` (new), `ToolCatalog.java`, `act-mcp` parity test, `live/LiveToolExecutor.java`, `live/LiveRunService.java`, tests.

**Interfaces:**
- Produces: `LiveToolExecutor.execute(String name, JsonNode args) -> ToolOutcome` where `ToolOutcome = Value(String json) | PendingApproval(UUID runId, UUID approvalId, String toolName, String riskTier)`.
- Produces: `LiveRunService.runForToolTurn(String conversationId, String toolName) -> UUID` (idempotent per turn context) and `settle(UUID runId, RunStatus status, String reason)`.
- Tool set: `get_time`, `get_run`, `get_latest_conversation`, `list_approvals`, `list_agents`, `get_dashboard_summary`, `get_workflow` (all read-only; direct service calls, no MCP HTTP hop).

- [ ] **Step 1: Failing tool tests** — `LiveToolExecutorTest`: each name returns a JSON value; an unknown name returns an error outcome (never throws); `get_time` returns ISO-8601 with the injected `Clock`.
- [ ] **Step 2: Failing run tests** — `LiveRunServiceTest`: first tool call saves one run with `status=RUNNING`, `conversationId`, `"[live] tool:"` seed, and publishes one `AuditLogEvent`; a second call in the same turn reuses the id; no tool call → no save; `settle` writes the terminal status.
- [ ] **Step 3: Implement tools** — `TimeTools` with `@Tool(name="get_time", ...)` following the existing read-only tool pattern; register in `ToolCatalog` and update `ToolCatalogParityTest`/any catalog inventory test. `LiveToolExecutor` calls the owning service/repository per name.
- [ ] **Step 4: Implement run** — `RunRepository.save` only (assert no `RunStartedEvent` by wiring a recording publisher); `AuditLogEvent("RUN", runId, "Run started (live tool turn)", conversationId)`.
- [ ] **Step 5: Run** — `bash scripts/mvn-lane.sh inner act-aria -Dtest="LiveToolExecutorTest,LiveRunServiceTest"` and `bash scripts/mvn-lane.sh inner act-mcp -Dtest=ToolCatalogParityTest` — GREEN.
- [ ] **Step 6: Commit** — `feat(live): execute read-only tools under a lazy live run`.

### Task 4: Approvals, finalize park and TOWER injection

**Files:** `act-common/.../port/LiveRunPark.java` (new), `act-execution/.../runtime/RunInputCoordinator.java`, `act-execution/.../LiveRunParkAdapter.java`, `live/LiveEventRouter.java`, handler wiring, tests.

**Interfaces:**
- Produces: `LiveRunPark.park(UUID runId, Runnable onFinalize)` / `unpark(UUID)`; `RunInputCoordinator.requestFinalize` consults live parks before pending input and returns true when it wakes one.
- Produces: Java→bridge messages `{"type":"tower","text":"…"}`, `{"type":"tool_result","id":"…","ok":true,"output":"…"}`; bridge→Java `{"type":"tool_call","id":"…","name":"…","arguments":{…}}`.
- Produces: `/runs/{id}/finalize` → 202, run `ABORTED`, reason `Operator finalized the live run`, `run_event{state:"failed"}` and a `[tower] finalized` injection.

- [ ] **Step 1: Failing park tests** — `RunInputCoordinatorLiveParkTest`: `park` then `requestFinalize` → true, callback once, second call false; `unpark` then finalize → false; existing pending-input tests unchanged.
- [ ] **Step 2: Failing router tests** — `LiveEventRouterTest`: `ApprovalDecidedEvent` for the live run with approve → tool executes once, bridge receives `tower` and `run_event{finished}`; deny → run FAILED, `[tower] denied by operator`; event for another run ignored; socket close unparks.
- [ ] **Step 3: Implement park** — add a `ConcurrentHashMap<UUID, Runnable>` to the coordinator; `requestFinalize` tries it first; adapter registers/unregisters.
- [ ] **Step 4: Implement router** — listen with `@EventListener`; settle via `LiveRunService`; write through `LiveSessionRegistry`.
- [ ] **Step 5: Run** — `bash scripts/mvn-lane.sh inner act-execution -Dtest=RunInputCoordinatorLiveParkTest` and `bash scripts/mvn-lane.sh inner act-aria -Dtest=LiveEventRouterTest` — GREEN.
- [ ] **Step 6: Commit** — `feat(live): park approvals and inject tower results`.

### Task 5: `packages/qwen-live-bridge`

**Files:** new package as in the File Structure; `ws` ^8.18, `typescript`, `vitest`; Node 20.

**Interfaces (stdio, one JSON per line):**
- Java→bridge: `hello{v,conversationId,context[]}`, `audio{dir:"in",dataBase64}`, `text{text}`, `commit{image}`, `cancel`, `mic{on}`, `image{dataBase64}`, `tool_result{id,ok,output}`, `tower{text}`, `close`.
- bridge→Java: `audio{dir:"out",dataBase64}`, `control{event:{…phone event…}}`, `tool_call{id,name,arguments}`, `log{type,count,bytes,ms}`.

- [ ] **Step 1: Failing translate tests** — `translate.test.ts`: allowlist map for the eight Qwen event families; unknown dropped; `speech_end.tMs` equals the injected receive clock; `interrupted` emitted before any `response.audio.delta` that follows `speech_started`; `seq` monotonic across delta/completed per stream; text `done` maps to `final:true`.
- [ ] **Step 2: Failing session tests** — `session.test.ts` with a fake Qwen WS server: `session.update` carries `modalities:["text","audio"]`, `voice:"Kiki"`, `input_audio_transcription.model:"qwen3-asr-flash-realtime"`, `turn_detection:{type:"semantic_vad_ec"}`, workspace header; `input_image_buffer.append` precedes `input_audio_buffer.commit` on `commit{image:true}`; `response.function_call_arguments.done` triggers a `tool_call` to Java and a `function_call_output` + `response.create` after `tool_result`; `tower` sends a message item then `response.create`; 119-minute fake timer emits `session_expired`, reopens, emits `resumed{handleAgeSec}`.
- [ ] **Step 3: Failing log test** — `log.test.ts`: a turn containing audio/image/key/workspace produces only allowlisted fields; the serialized log contains none of the payloads.
- [ ] **Step 4: Implement** — `protocol.ts` types; `session.ts` (connect, config, forwards, reopen loop); `translate.ts` (pure functions, injected clock); `log.ts`; `index.ts` (stdio reader/writer, bridge state machine).
- [ ] **Step 5: Run** — `cd packages/qwen-live-bridge && pnpm install && pnpm test && pnpm build` — GREEN.
- [ ] **Step 6: Commit** — `feat(live): add the Qwen realtime bridge package`.

### Task 6: End-to-end seam, frame path and privacy scan

**Files:** `src/test/resources/live/fake-bridge.mjs` (extend to replay a scripted session), `act-app/src/test/java/io/aria/conductor/app/LiveEndToEndIntegrationTest.java`, `packages/qwen-live-bridge/scripts/smoke.mjs`.

- [ ] **Step 1: Scripted seam test** — a fake bridge that emits `speech_end`, transcripts, one `tool_call`, then `tower`, an `interrupted` block, and an audio delta; the test drives the real handler with an in-process WS client and asserts the ordered phone events, the lazy run + audit row, `approval_required` carrying the runId, `/finalize` → 202/ABORTED, and the `[tower]` injection observed by the fake bridge.
- [ ] **Step 2: Frame path test** — `frame` 200 KiB base64 + `commit{image:true}` arrives at the fake bridge as `image` before `commit`; a 300 KiB frame gets `frame_too_large` and the socket stays open.
- [ ] **Step 3: Privacy scan** — capture all Java and fake-bridge log output during the seam test; assert no `dataBase64`, bearer, key or workspace string appears.
- [ ] **Step 4: Smoke script** — `scripts/smoke.mjs`: `ws://<host>:8080/api/v1/aria/live`, `Authorization` from `ARIA_OPERATOR_BEARER_TOKEN`, sends `hello`, prints the first event, exits 0 only when `ready.v===1 && maxSessionSec===7200` within 1 s; never prints the token.
- [ ] **Step 5: Run** — `bash scripts/mvn-lane.sh itg act-app -Dit.test=LiveEndToEndIntegrationTest`; `cd packages/qwen-live-bridge && pnpm test` — GREEN.
- [ ] **Step 6: Commit** — `test(live): prove the seam, the frame path and the privacy guard`.

### Task 7: Deployment config, gates and the L3 recipe

**Files:** `application.yml` (defaults off), `scripts/start-backend.sh` (export `ARIA_LIVE_*` when enabled), `docs/superpowers/plans/` companion note or `README` section, `.gitignore` (credentials file never in repo).

- [ ] **Step 1: Config** — `aria.live.enabled=false`, `aria.live.bridge-script`, `aria.live.credentials-file`; startup logs a single line `live bridge enabled=<bool> credentials=<present|absent>` with no values.
- [ ] **Step 2: Credentials** — copy `~/.config/live-spike/key` and `workspace` into `~/.aria-conductor/qwen-live.env` (`DASHSCOPE_API_KEY=…`, `DASHSCOPE_WORKSPACE=…`, 0600) without printing; verify mode and key length only.
- [ ] **Step 3: Gates** — `bash scripts/mvn-lane.sh gate act-aria`, `gate act-execution`, `gate act-mcp`; `cd packages/qwen-live-bridge && pnpm build && pnpm test`; dashboard gates untouched. All green.
- [ ] **Step 4: Deploy on the LAN** — rebuild the bridge, restart the backend with `aria.live.enabled=true`, run `node packages/qwen-live-bridge/scripts/smoke.mjs` from the Mac with the phone's bearer; expect `ready` in under 1 s.
- [ ] **Step 5: L3 recipes** — document:
  - Bridge-only outage: `pkill -f "qwen-live-bridge/dist/index.js"`; expected phone evidence: retryable `error` or close 1011, `LIVE · 重連中`, reconnect spawns a new child; keep it dead >29 s to exercise five failures and the spoken SSE fallback once. Restore: reconnect the phone (no server action) or restart the backend.
  - Backend outage: stop the backend process only; expected: connection refused → phone falls back to SSE; restore: `podman start aria-opensandbox` if needed, then `bash scripts/start-backend.sh`, health 200, smoke again.
- [ ] **Step 6: Commit and PR** — `docs(live): record the deployment and outage recipes`; open a PR and watch CI.

---

## Self-review

Spec coverage: X1→Task 1; X2/X3→Tasks 2+5; X4/X5/X6→Task 5; X7/X8→Tasks 3+4; X9→Tasks 2+5; X10→Tasks 2+6; X11→Tasks 2+5+6. Limits and close codes appear in Tasks 1, 2, 5. Deploy/L3 in Task 7.

Known risks to verify at deploy: `turn_detection` shape (`semantic_vad_ec`) is not in the P0 probe (which used null); if DashScope rejects it, the bridge reports `capability_mismatch` instead of pretending, and the contract table gets a one-line revision before we claim L1–L4. Qwen tool declarations use the P0-verified `parameters` shape.
