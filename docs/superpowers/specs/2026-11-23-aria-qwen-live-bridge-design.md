# Aria × Qwen Live Bridge (Aria side)

- Date: 2026-11-23
- Status: design for review. No implementation until this spec and its plan are approved.
- Owner: aria-conductor. The phone contract lives in `aria-glass/.worktrees/gemini-dual-loop/docs/superpowers/plans/2026-11-23-phase2-qwen-live-mvp.md` ("Aria-side contract", X1–X11). That table is the authority for the wire format; this spec implements it and does not extend it.
- Depends on: the LAN stack brought up on 2026-11-23 (backend 8080, OpenSandbox 8090, DeepSeek provider, operator bearer).

## Goal

Add the Aria-owned server half of the phone's live loop: one operator-authenticated WebSocket at `/api/v1/aria/live`, backed by one Qwen realtime bridge process per socket, translating Qwen events into the phone's eleven server events, executing the fixed read-only tool set with Aria's governance, injecting TOWER results, and keeping every credential and payload out of logs.

## Decisions already taken (operator, 2026-11-23)

| Id | Decision |
|----|----------|
| L1 | Bridge transport is a per-socket child process spawned by the backend, JSON-lines over stdio; PCM travels base64 in those lines (640-byte chunks; outbound deltas are ~8 KB, so the overhead is acceptable and there is no extra port or auth surface). |
| L2 | Qwen credentials (`DASHSCOPE_API_KEY`, `DASHSCOPE_WORKSPACE`) are read by Java from `~/.aria-conductor/qwen-live.env` (0600) and passed to the child only through its environment. Never argv, never a log line. |
| L3 | The endpoint echoes `aria-live.v1` when the client offers it; a wrong offered value closes 4002 after `ready`; no header (the phone sends none) is accepted. |
| L4 | A **conversation-scoped live run is created lazily on the first tool call of a turn**, never for a pure chat turn. Every `approval_required` carries that runId and `POST /api/v1/runs/{id}/finalize` settles it. The run appears in the dashboard and audit like any other run. |
| L5 | Frame validation (JPEG, base64 ≤ 256 KB) happens in Java; an oversized or invalid frame is answered with `frame_too_large` / `frame_invalid` and never tears the socket down. |
| L6 | Logs are metadata only: event type, counts, byte counts, milliseconds. No audio, image, base64, bearer, key or workspace ever reaches a log line. |
| L7 | A new read-only tool `get_time` is added because P0 showed the model invents clock answers. |

## Verified facts this design rests on

| Claim | Evidence |
|---|---|
| The dashboard WebSocket is STOMP at `/ws/events`; act-aria has no raw WS handler | `act-dashboard-api/.../config/WebSocketConfig.java` |
| Operator authority is already a single resolver: bearer → operator, worker bearer → 403, nothing on a non-loopback peer → 401 | `OperatorAuthorityResolver.java:55-96` |
| The operator bearer is configured by `aria.operator.bearer-token` / `ARIA_OPERATOR_BEARER_TOKEN`; the backend currently has it from `.env` | `OperatorSessionService.java:64`, `.env` |
| `RunService.createRun` publishes `RunStartedEvent`, and `RunExecutionListener` then starts the core | `RunService.java:185`, `RunExecutionListener.java:23` — so a live run must be inserted without that event |
| `/runs/{id}/finalize` only wakes a run parked in this process via `RunInputPort`; otherwise 409 | `RunController.java:130-147`, `RunInputPort.java` |
| `Approval.runId` is a required FK; approvals need a real run row | `Approval.java:28-29` |
| Domain events for approvals and runs already exist | `ApprovalRequestedEvent`, `ApprovalDecidedEvent`, `RunStartedEvent`, `RunCompletedEvent` |
| The MCP catalog already has the read-only tools | `get_run`, `get_latest_conversation`, `list_approvals`, `get_dashboard_summary`, `list_agents`, `get_workflow` (no `get_time`) |
| Packages are independent Node packages with `pnpm build` / `pnpm test` | `packages/mcp-server`, `packages/qoder-acp-bridge` |

## Architecture

```
phone ── wss /api/v1/aria/live ──> LiveWebSocketHandler (act-aria)
                                      │  auth (operator resolver), limits, frame validation
                                      │
                                      ├── LiveSession ── stdio JSON lines ──> qwen-live-bridge (child)
                                      │        │                                  │
                                      │        │                                  └── SG realtime WSS
                                      │        └── LiveToolExecutor ──> act-* services (governed tool layer)
                                      │
                                      └── LiveEventRouter <── ApprovalDecided / RunCompleted events
```

Five units, each with one responsibility:

| Unit | Responsibility |
|---|---|
| `LiveWebSocketHandler` + `LiveHandshakeInterceptor` | Authenticate the upgrade, enforce 426/401/403, negotiate the optional subprotocol, enforce text (64 KiB) and binary (32 KiB) frame caps, route frames. |
| `LiveSession` | One socket's state: conversationId, run id (lazy), bridge process, `ready` deadline, close codes, counters. Owns the bridge lifecycle. |
| `QwenLiveBridge` (Java side of stdio) | Spawns the child with env-only credentials; writes/reads newline-delimited JSON; never logs payloads. |
| `qwen-live-bridge` (Node package) | Owns the DashScope connection, event allowlist translation, `seq`, `speech_end.tMs`, barge-in ordering, 120-minute reopen, image commit. |
| `LiveToolExecutor` + `LiveRunService` | Read-only tool execution, lazy live run creation, PENDING_APPROVAL translation, park/finalize support, TOWER injection payloads. |

## Endpoint and handshake (X1)

- Path stays exactly `/api/v1/aria/live` (both `ws://` on the LAN and `wss://` through a TLS terminator).
- A plain GET (no `Upgrade: websocket`) answers **426 Upgrade Required** from an explicit MVC mapping.
- Missing/invalid operator bearer → **401**; a valid worker bearer → **403**; both before the upgrade completes.
- `Sec-WebSocket-Protocol: aria-live.v1` is echoed when offered. An offered value other than `aria-live.v1` is accepted for the handshake, then `ready` is sent and the socket closes with **4002**. No header is accepted (phone sends none).
- `hello` must arrive within 5 s; otherwise close 4002 (`capability_mismatch`). `ready` is emitted by Aria the moment `hello` validates — it does not wait for the SG connection, so the 1 s DoD is met and a later provider failure arrives as `error`/1011. `ready` carries `v:1`, `sessionId`, `model`, `modalities:["text","audio"]`, `maxSessionSec:7200`.
- One socket = one conversation. `hello.conversationId` (≤36 chars) binds the session; a second `hello` is `capability_mismatch`. Two sockets for the same conversation are not treated as an error: each owns its own bridge and run ids, and Aria's run/approval store stays authoritative.
- The handshake reuses the operator decision itself, not a copy of its rules: the credential/loopback precedence in `OperatorAuthorityResolver` is extracted into one method that both the HTTP resolver and the WS interceptor call.

## Bridge process (X2, X3, X9, X11)

- Java spawns `node <packages/qwen-live-bridge/dist/index.js>` per socket, with a working directory and env: `DASHSCOPE_API_KEY`, `DASHSCOPE_WORKSPACE`, `QWEN_REALTIME_URL` (SG WSS), `LIVE_SESSION_ID`. The child is killed when the socket closes, the backend stops, or it exits.
- Each stdio line is one JSON object with a `type`. Audio is `{"type":"audio","dir":"in|out","dataBase64":"…"}`; phone binary never passes through JSON framing without being re-encoded, and the Java side enforces the 32 KiB binary cap before encoding.
- Child stdout carries only control JSON. Metadata logging goes to stderr, which Java captures, filters through the same allowlist and redacts before writing.
- Qwen session config: `modalities:["text","audio"]`, `voice: Kiki`, input transcription `qwen3-asr-flash-realtime`, `turn_detection: semantic_vad_ec`.
- At 119 minutes the bridge emits `session_expired`, closes the Qwen socket, reopens it, reseeds context from the Java-supplied summary, and emits `resumed{"handleAgeSec": <elapsed since first open>}`. Close code 4012 accompanies a failed reopen.
- Bridge failure or unexpected child exit → `error{code:"bridge_internal",retryable:true}` then close **1011**; the child is reaped as soon as the socket closes.

## Event translation (X4, X5, X6, X8)

Only these Qwen events cross to Java/phone. Everything else is counted and dropped.

| Qwen event | Phone event |
|---|---|
| `input_audio_buffer.speech_stopped` **or** `input_audio_buffer.committed` (semantic VAD ends an utterance with a commit and sends no `speech_stopped`; one `speech_end` per utterance) | `speech_end{tMs: bridgeReceiveEpochMs}` |
| `conversation.item.input_audio_transcription.delta/completed` | `input_transcript{seq, text, final}` |
| `response.audio_transcript.delta/done` | `output_transcript{seq, text, final}` |
| `input_audio_buffer.speech_started` (while a response is active) | `interrupted{reason:"user_speech"}` **before** any further audio delta, then `response.cancel` upstream |
| `response.audio.delta` | binary 24 kHz PCM16LE, one delta at a time |
| `response.done` + tool calls | `tool_status` then either the tool result continuation or a TOWER injection |
| `error` | `error{code in the fixed set, retryable, message}` |

- `seq` starts at 1 per stream per socket and increases monotonically across reconnects for the socket's lifetime.
- `ready.maxSessionSec` is always 7200.
- The closed error set is exactly `auth_failed`, `capability_mismatch`, `frame_too_large`, `frame_invalid`, `provider_rate_limited`, `provider_unavailable`, `session_expired`, `bridge_internal`.
- Close codes: 4001 auth, 4002 protocol/version, 4012 session expired, 1011 bridge internal.

## Tools, approvals, live run (X7, X8, L4)

- The bridge declares read-only functions: `get_time`, `get_run`, `get_latest_conversation`, `list_approvals`, `get_dashboard_summary`, `list_agents`, `get_workflow`.
- A call is forwarded to Java (`tool_call` over stdio). Java executes it through `LiveToolExecutor`, which calls the owning service (no new MCP transport hop) and returns either a value or `PENDING_APPROVAL`.
- **Lazy live run**: on the first tool call of a turn, `LiveRunService` inserts a `Run` row with `RunRepository.save` (no `RunStartedEvent`, so no engine dispatch) — `agentId = aria`, `conversationId`, `promptSeed = "[live] tool: <name>"`, `status = RUNNING` — and publishes an `AuditLogEvent` (`RUN`, run id, `"Run started (live tool turn)"`) so it appears in audit/dashboard like any run. A pure chat turn creates nothing. The run is finalized at the end of the tool-using turn (`COMPLETED`, or `FAILED` with the error text).
- `PENDING_APPROVAL` → run transitions to `WAITING_INPUT`; a live park (`LiveRunPark` port implemented in act-execution alongside `RunInputCoordinator`) is registered so `POST /runs/{id}/finalize` returns **202** and settles the run as `ABORTED` with `"Operator finalized the live run"`, emits `run_event{state:"failed"}`, injects `[tower] finalized`, and unparks. Without a pending park, finalize keeps returning 409.
- Approval decisions use the existing REST endpoints. On approve, Java executes the gated tool, injects the result as `[tower] …` and emits `run_event` progress/finished; on deny it injects `[tower] denied by operator` and fails the live run. `ApprovalDecidedEvent` and `RunCompletedEvent` are filtered by the live run id before forwarding.
- `tool_status` uses `running` before execution, `approval` while parked, `done` when the result returns.
- Tools execute through the owning services directly (`LiveToolExecutor`), preserving the governed approval decision before any gated call runs; the MCP HTTP transport is not in this latency path. The fixed set is read-only, so a normal call returns a value and never parks.

## Image input (X10, L5)

- `frame{mime,dataBase64,capturedAtMs}`: JPEG only; Java validates base64 decodes, ≤256 KB base64, and that the MIME is `image/jpeg`. Failures emit `frame_too_large` / `frame_invalid` and keep the socket.
- At most one frame per utterance; the pending frame is sent to the bridge only with `commit{image:true}`, after the utterance's audio, so ordering is audio→image→commit.
- Nothing is written to disk; the bytes live in memory for the turn.

## Privacy (X11, L6)

- Java and Node share one allowlist for log fields; a redaction test scans captured log lines from an audio+frame+tool turn for `dataBase64`, the bearer, the key and the workspace id.
- The bridge never logs URLs, headers, or `sessionId`.
- No file receives audio or image bytes; workspace cleanup is not involved.

## Deployment (LAN, same backend as the phone)

- Config: `aria.live.enabled` (default false), `aria.live.bridge-script` (default `packages/qwen-live-bridge/dist/index.js` relative to the repo), `aria.live.credentials-file` (default `~/.aria-conductor/qwen-live.env`), `aria.live.realtime-url` (default empty: the child derives the SG WSS endpoint from the workspace region; when set it is passed as `QWEN_REALTIME_URL`, and it is not a secret).
- The credentials file is copied from `~/.config/live-spike/key` and `~/.config/live-spike/workspace` into `DASHSCOPE_API_KEY` / `DASHSCOPE_WORKSPACE` with mode 0600; values are never printed or committed.
- Smoke command from the LAN (phone can run it): connect with the operator bearer, send `hello`, expect `ready` with `v:1` within 1 s, then close. A committed script `packages/qwen-live-bridge/scripts/smoke.mjs` implements exactly that.
- Controlled outage for L3: kill only the bridge children (`pkill -f qwen-live-bridge/dist/index.js`), leave the backend up; the phone should see close 1011 or `provider_unavailable`, then fall back to SSE. Restore by reconnecting (the next socket spawns a fresh bridge); if the backend itself is stopped, expect connection refused and restore with the documented `scripts/start-backend.sh` sequence.

## Testing

- Unit: handshake statuses (426/401/403), `hello` validation, subprotocol decision, frame caps, event allowlist, seq monotonicity, interrupted-before-delta ordering, close codes.
- Bridge: Vitest with a fake Qwen WS server; metadata-only log assertions.
- Java integration (deterministic, no Qwen): a fake bridge script that replays a recorded Qwen event script; assert the phone frames, the lazy run row + audit, approval/finalize, TOWER injection and privacy.
- Smoke: the committed `smoke.mjs` against the live stack (requires the credentials file).
- Live acceptance (10 Cantonese turns, L1/L2/L4) stays with the phone; Aria reports only what it can verify.

## Out of scope

- AR capture/overlay (Phase 3), async TOWER runs, remote MCP, device tokens.
- Qwen resumption handles (none exist; reconnect rebuilds from the Aria summary).
- Persisting audio, images or transcripts from the live path beyond the normal run/timeline rows.

### Client compatibility notes (verified on the emulator, 2026-11-24)

- A phone that sends `mic` as its first text frame is tolerated: the frame is ignored until `hello` (which carries the authoritative mic flag); every other non-hello first frame still closes 4002.
- A `hello` without a conversationId is refused (4002). The phone must mint and persist a 36-char conversation id before connecting; a fresh install that connects first has no id.
- An image frame committed without an audio buffer is rejected by the provider; the phone's Phase 3 flow always carries an utterance with a frame.
- `get_time` returns `now` (local ISO-8601 with offset), `zone` and `utc`; the live instructions answer time from `now`.
