# Aria Glasses Observe and AR Render (Aria side)

- Date: 2026-11-21
- Status: design approved by the operator (2026-11-21, brainstorming session). Implementation not started.
- Scope: `agent-control-tower` (act-aria, act-execution, act-mcp, opencode-sandbox). The phone app and its local AR engine are out of scope; their side is owned by the ai-glass-v1 session.
- Client contract: shared with `/Users/happy/Documents/project/tmp/ai-glass-v1/docs/superpowers/specs/2026-11-21-aria-glasses-agent-design.md` (commits `7d19de8`, `9bb06d1`, `220821b`, `faaf5b2`). The camelCase correction to `ar_render` was sent to that session after `faaf5b2`; see Section 12.

## 1. Problem

Aria's PA is text-only. The glasses need to send an interval observation (JPEG plus local AR metadata), have Aria reason about the scene, and receive structured AR instructions. Nothing exists today for any of the three: no image input, no observation endpoint, no AR render tool.

Fixed hardware context: XREAL One (DisplayPort display plus USB audio), vivo X Fold 5 host, XREAL Eye camera not available. The phone performs all real-time AR work (capture, detection, tracking, overlay). Aria receives interval observations only, never continuous video.

## 2. Operator decisions

| Id | Decision |
|----|----------|
| D1 | The image goes into the Aria turn itself as an opencode `file` part with a `data:` URL. No digest, no separate vision provider. The active model must be multimodal; there is no silent degradation. |
| D2 | `POST /api/v1/aria/observe` answers with SSE using the existing `chat/stream` frames, not a sync JSON body. |
| D3 | `ar_render` is classified `WORKER_READ` and auto-approved. It renders on the device and changes no platform state; every call still writes an audit row. |
| D4 | `conversationId` is optional. When omitted the server uses the latest PA conversation, so glasses and dashboard share one conversation. |
| D5 | `observe` requires operator authority. On the LAN the phone sends the existing operator bearer; no device-scoped token in phase 1. |
| D6 | opencode is upgraded from 1.18.15 to the latest stable, 1.19.6. |
| D7 | Phase 1 supports the opencode core only. A Qoder-core run with attachments is refused, never silently stripped. |
| D8 | Out of scope: continuous video, world-locked 3D, device-scoped token, XREAL Eye. |

## 3. Verified evidence

| Claim | Evidence |
|-------|----------|
| Aria's turns are executed by the run-owned opencode core, not the in-process LLM loop | `AgentLoopEngine.dispatchRun` delegates to `executeCoreRun` when `CoreRunLauncher.owns(agent)`; `agent-control-tower/act-execution/src/main/java/io/aria/conductor/execution/engine/AgentLoopEngine.java:1043-1047` |
| The in-process LLM message model is text-only | `LlmMessage` has `role/content/toolCallId/toolCalls` only; `DefaultLlmClient.buildMessagesArray` writes `content` as a string; `.../execution/llm/LlmMessage.java:5-9`, `.../execution/llm/DefaultLlmClient.java:138-166` |
| Our opencode client sends text parts only | `OpenCodeHttpClient.sendPrompt` builds `{type:"text"}` parts; `.../execution/adk/opencode/OpenCodeHttpClient.java:160-171` |
| opencode 1.18.15 already accepts image file parts | Published SDK 1.18.15 types include `FilePartInput {type:"file", mime, filename?, url, source?}` in the `POST /session/{id}/message` parts union (`@opencode-ai/sdk` 1.18.15, `dist/gen/types.gen.d.ts:1245`, `:2257`) |
| opencode accepts `data:` URLs for images | At tag `v1.18.15`, `packages/opencode/src/session/prompt.ts:785-788` branches on `case "data:"`, and `:1010-1016` normalizes every file part whose mime starts with `image/`. Identical at `v1.19.6` (`:787`, `:1012`) |
| Latest stable opencode is 1.19.6 | `npm view opencode-ai dist-tags` → `latest: 1.19.6`; SDK 1.19.6 still exports `FilePartInput` |
| The pinned sandbox version is 1.18.15 | `agent-control-tower/opencode-sandbox/Dockerfile:45` |
| Progress streaming cannot leak the image | `CoreProgressPump` emits only assistant messages (`.../runtime/progress/CoreProgressPump.java:555-575`), and `PartSnapshot` carries no `url` (`OpenCodeHttpClient.listMessages`, `:365-372`) |
| Tool args on the wire are redacted but stay JSON | `CoreEventTelemetry.streamSignal` puts `CredentialRedactor.redact(serialized(input))` in `args`; `CredentialRedactor` only replaces token-shaped values and named secret JSON keys; `.../telemetry/CoreEventTelemetry.java:160-195`, `.../security/CredentialRedactor.java:46-58` |
| `@ToolParam` cannot rename a JSON argument | Spring AI 1.0.9 `org.springframework.ai.tool.annotation.ToolParam` declares only `required` and `description`; `MethodToolCallback.buildMethodArguments` reads each argument by the Java parameter name |
| New MCP tools register automatically | `McpServerConfig.ariaToolCallbackProvider` bridges every `McpTool` bean; `act-mcp/.../McpServerConfig.java:86-92` |
| Aria's prompt tool list is generated from the catalog | `ToolCatalogContextContributor` reads `ToolCatalogProvider`; `act-execution/.../context/contributor/ToolCatalogContextContributor.java:31-43` |
| A clarification park exists only on the qoder core | `CoreExecutionService` asks `ClarificationQuestions.awaitingInput` only when `"qoder".equals(current.coreId())`; `.../runtime/CoreExecutionService.java:511-513` |
| `/answer` has no 404 path | `ApprovalAnswerService.answer` throws `IllegalArgumentException` for missing/decided asks; `GlobalExceptionHandler` maps it to 400; `.../approval/ApprovalAnswerService.java:56-115`, `act-common/.../GlobalExceptionHandler.java:56-66` |
| `approved=false` on a waiting ask is a 400 | `ApprovalAnswerService.java:72-75` |
| Sandboxes are per run and destroyed with the run | `SandboxLifecycle.destroy(UUID runId)`; `.../runtime/sandbox/SandboxLifecycle.java:538-545` |

Not verified, and gating Phase 1 acceptance:

- The active model (`Flint.APEX-I-MiniPlus-V2.1` at `http://host.containers.internal:8000/v1`) accepting an image. The model server was not running during this design; this is a live probe, not a code fact.
- opencode 1.19.6 behaviour under the repo's pinned-behaviour tests (progress cadence, permission hiding, session model pin). The version bump runs those tests.

## 4. Architecture

```
phone (CameraX, detector, tracker, HUD)
  |  POST /api/v1/aria/observe  (JSON + base64 JPEG + AR metadata, operator bearer)
  v
AriaObserveController  --operator check, validation, 400/413/415/422/409
  v
AriaObserveService  --conversation resolve, seed text, Run row, attachments
  v
AgentLoopEngine.startRunStream(runId, emitter, priorContext, "observe", attachments)
  v
CoreRunLauncher -> CoreExecutionService -> OpenCodeCoreSession.prompt(CoreTask)
  v
OpenCodeHttpClient.sendPrompt  --text parts + file part (data URL)
  v
opencode 1.19.6 (per-run sandbox) -> multimodal model
  |
  v  SSE: thinking / tool_call(ar_render) / tool_result / message / done / error
phone renders primitives from tool_call(name=ar_render).args
```

The phone remains the only real-time AR processor. Aria receives one snapshot per turn and returns high-level primitives.

## 5. Contract

### 5.1 `POST /api/v1/aria/observe`

Headers: `Authorization: Bearer <operator bearer>`, `Content-Type: application/json`, `Accept: text/event-stream`.

```json
{
  "conversationId": "conv-…",
  "note": "AR mode observation",
  "image": {
    "mimeType": "image/jpeg",
    "dataBase64": "…",
    "width": 640,
    "height": 480
  },
  "clientCapabilities": { "kind": "glasses", "ar": true, "vision": true, "audio": true },
  "observation": {
    "timestamp": 1760000000000,
    "heading": 123.4,
    "pitch": -2.1,
    "roll": 0.3,
    "objects": [
      {
        "id": "o1",
        "trackId": 7,
        "label": "remote",
        "confidence": 0.91,
        "bbox": [0.42, 0.55, 0.12, 0.08],
        "center": [0.48, 0.59],
        "description": "black TV remote on desk"
      }
    ],
    "ocrText": "SONY",
    "localSummary": "1 remote, 1 cup"
  }
}
```

Rules:

- `conversationId`: optional. When absent the server uses the latest conversation (the most recent run with a non-blank `conversationId`; `AriaConversationController.getLatest` and `AriaTools.getLatestConversation` both apply that rule). When none exists, a new UUID is generated. The chosen id is returned in the `done` frame.
- `timestamp`: epoch milliseconds. Rendered into the seed as ISO-8601 UTC.
- `bbox`: normalized `[x, y, w, h]`; `center`: normalized `[x, y]`; both in `[0, 1]` with the top-left origin. `bbox` width and height must be > 0.
- `heading`, `pitch`, `roll`: degrees. `heading` 0 = magnetic north, clockwise. In v2.0 it is recorded only; rendering is screen-space.
- Limits: decoded image <= 1,572,864 bytes (1.5 MiB); `objects` <= 32; `label` <= 64 chars; `description` <= 200; `ocrText` <= 2000; `localSummary` <= 500; `note` <= 500.
- MIME allowlist: `image/jpeg`, `image/png`.
- Phase 1 accepts JSON plus base64 only. Multipart is not implemented.
- One observation in flight per phone. Aria's admission pool serializes turns of one conversation, so overlapping calls would queue behind each other and delay the HUD.

Status codes, decided before a stream exists and returned as JSON:

| Status | Condition |
|--------|-----------|
| 400 | malformed JSON, missing image, invalid base64 |
| 413 | `Content-Length` > 3,000,000 (early guard) or decoded image > 1,572,864 bytes |
| 415 | MIME not in the allowlist |
| 422 | structural violation: `objects` > 32, bbox/center outside `[0,1]`, bbox width or height <= 0 |
| 401 | no verifiable operator identity (LAN without bearer) |
| 403 | a worker credential, or a failed cookie mutation check |
| 409 | Aria's resolved core is not `opencode`. A null or blank core is the documented default (opencode) and is allowed. |

Over-cap strings are truncated, not rejected; only the numeric and list violations above produce 422.

The authority call is `OperatorAuthorityResolver.resolveOperator(request, true)` before any side effect. Loopback without a credential remains the operator, unchanged.

The run's persisted prompt seed is text only. No base64 ever enters the run row, the trajectory, or a log:

```text
[glasses observation] note: AR mode observation
capabilities: ar=true,vision=true,audio=true
captured_at: 2026-11-21T09:12:03Z
orientation: heading=123.4 pitch=-2.1 roll=0.3
objects:
- trackId=7 objectId=o1 label="remote" confidence=0.91 bbox=(0.420,0.550,0.120,0.080) center=(0.480,0.590) description="black TV remote on desk"
ocr: "SONY"
summary: "1 remote, 1 cup"
```

Seed cap: 8 KiB. Per-field caps as above; an over-cap field is truncated with a ` [truncated]` suffix and the whole seed is truncated last.

### 5.2 SSE frames

Identical names and payloads to `chat/stream` (`act-dashboard/src/api/aria.ts` is the reference parser):

| Frame | Payload | Glasses use |
|-------|---------|-------------|
| `thinking` | `{runId}` | status indicator |
| `tool_call` | `{runId, name, toolCallId, args}` | `name == "ar_render"` → parse `args`, apply the primitive |
| `tool_result` | `{runId, name, result, ok}` | optional status |
| `message` | `{content}` | HUD card and TTS |
| `done` | `{runId, conversationId, intent}` | end of turn; `intent` is `observe` |
| `error` | `{message, turnFailed, runId, reason}` | HUD error and voice |
| `approval_required` | `{runId, approvalId, toolName, riskTier, toolPolicy, optionsJson, expiresAt}` | decision card (Section 5.4) |
| `waiting_input` | `{runId, approvalId, question}` | question card (Section 5.4); unreachable on opencode, see below |

- Multiple `ar_render` calls may arrive in one turn. Apply them in arrival order.
- `args` is a redacted JSON string. Redaction only replaces token-shaped values and named secret keys, so the string remains valid JSON; the client must still `try/catch` the parse and drop a malformed render rather than crash.
- There is no replay. A reconnect does not receive earlier frames; the phone re-observes or reads `GET /api/v1/approvals?status=PENDING` to rebuild cards.
- On opencode, a clarification park cannot occur (`CoreExecutionService:511-513`), so `waiting_input` is effectively unreachable for an observe turn. The frame stays in the contract because the same parser serves plain chat and because a future Qoder observe path would produce it.
- The stream stays open while a run is parked on the qoder core (the coordinator blocks on `requestInput`), so the phone can answer without reconnecting.

### 5.3 `ar_render` tool

Registered in `act-mcp` as one more `McpTool` bean, with one `ToolCatalog` entry, one `McpToolInventory` names entry, and the existing parity tests updated.

```json
{
  "primitive": "track|arrow_to|highlight|label|indicator|step|timer|clear",
  "target": { "trackId": 7, "objectId": "o1", "label": "remote", "bbox": [0.42, 0.55, 0.12, 0.08] },
  "text": "TV remote",
  "directionDeg": 45,
  "distanceM": 2.0,
  "durationS": 180,
  "anchor": "screen|heading",
  "confidence": 0.8
}
```

- Only `primitive` is required. `track`, `arrow_to`, `highlight` and `label` require a locatable target: at least one of `trackId`, `objectId`, `label`, `bbox`. `indicator` needs `directionDeg` (a target is optional). `timer` requires `durationS` > 0. `clear` ignores `target`.
- `directionDeg`: with `anchor=screen`, clockwise from the screen centre; with `anchor=heading`, relative to magnetic north. Default `anchor` is `screen`.
- `durationS` applies to `timer` and to any primitive that should auto-clear; for non-timer primitives, `0` or absent means "until replaced or cleared".
- `clear` ignores `target` and removes the current overlay.
- Argument names are camelCase because Spring AI 1.0.9 names JSON arguments from Java parameters. A wrapper record would add an extra level to the payload, so the tool takes individual parameters.
- Return the shared envelope `{"ok":true,"data":{"ack":true,"primitive":"…"}}` via `ToolResponses.ok`. A validation failure returns the standard `VALIDATION` error envelope so Aria can correct itself in the same turn.
- Policy: `WORKER_READ`, auto-approve true. It changes no platform state; the audit row is the existing tool-call record.
- Target resolution priority is the phone's: `trackId`, then `objectId`, then `label`, then `bbox`.

### 5.4 Approvals and control tower

No new endpoint. The glasses call the existing ones:

| Signal | Glasses action | Call | Handling |
|--------|----------------|------|----------|
| `approval_required` | decision card | `POST /api/v1/approvals/{approvalId}/decide` `{"approved":true|false,"reason":"…"}` with the operator bearer | 400 unknown/parameter error; 409 state conflict; 401/403 credential |
| `waiting_input` (answer or continue) | question card | `POST /api/v1/approvals/{approvalId}/answer` `{"answer":"…","approved":true}` | 400 unknown/decided; 409 wake race |
| `waiting_input` (operator refuses or cancels) | cancel card | `POST /api/v1/runs/{runId}/finalize` | `runId` comes from the frame |

- Never send `approved:false` to `/answer` on a waiting ask: it is a 400 by design. Denial is `/finalize`.
- `/answer` is not operator-gated but rejects a foreign `Origin`; a native app sends none and passes. Sending the bearer is still recommended so an expiring run scope is never mistaken for auth.
- Voice decisions require an explicit second confirmation on the phone. That is client behaviour; the server contract does not change.
- After any 400 or 409 the glasses clear the card and re-read `GET /api/v1/approvals?status=PENDING`.
- `GET /api/v1/approvals/{id}` returns the enriched ask (tool name, tier, policy, arguments) when the card needs detail.

### 5.5 Plain PA chat (no AR)

Unchanged and usable on day one: `POST /api/v1/aria/chat/stream` with `{conversationId, message, history, skillId?}`. The glasses sends `history: []` and relies on server-side history for the conversation id. All frames above except `ar_render` apply.

### 5.6 Example: TV remote

1. The phone detects `label="remote"`, `trackId=7`, sends the observation above.
2. Aria calls `ar_render` with `{"primitive":"track","target":{"trackId":7},"text":"TV remote","anchor":"screen"}`.
3. The phone receives `tool_call` with that `args`, resolves track 7, and draws a reticle plus label on its current box.
4. Aria's `message` frame carries the spoken answer.
5. On `done`, the phone returns to observation cadence.

If the object is unknown, Aria can answer without a render, ask for a closer snapshot, or issue `label` with a `bbox`; the phone initializes a class-agnostic track from that box.

## 6. Server changes

| File | Change |
|------|--------|
| `act-aria/.../controller/AriaObserveController.java` (new) | `POST /api/v1/aria/observe`, `produces = text/event-stream`, returns `ResponseEntity<?>` so pre-stream errors can be JSON; operator check first; delegates to the service |
| `act-aria/.../service/AriaObserveService.java` (new) | validates, resolves the conversation, builds the seed, creates the Run (`promptSeed` = seed, `conversationId`), calls `startRunStream(..., "observe", attachments)`, reuses `AriaStreamService.errorPayloadJson` for startup failures |
| `act-aria/.../dto/AriaObserveRequest.java` (new) | request DTOs. Do not put the image on a type with a generated `toString()`; the base64 must never be printable |
| `act-execution/.../runtime/CoreAttachment.java` (new) | `record CoreAttachment(String mimeType, byte[] bytes, String filename)` |
| `act-execution/.../runtime/CoreTask.java` | add `List<CoreAttachment> attachments`, defensive copy, keep the 3-argument constructor |
| `act-execution/.../engine/RunContext.java` | hold the turn's attachments in memory |
| `act-execution/.../engine/AgentLoopEngine.java` | `startRunStream` overload taking attachments; thread them through `startRunInternal` into `RunContext`; `coreTask` copies them into the `CoreTask` |
| `act-execution/.../runtime/core/OpenCodeCoreSession.java` | append attachments after the user text part |
| `act-execution/.../adk/opencode/OpenCodeHttpClient.java` | `sendPrompt` overload appending `{type:"file", mime, filename, url:"data:<mime>;base64,<data>"}` |
| `act-execution/.../runtime/core/QoderCoreSession.java` | refuse a task with attachments (`TaskExecutionException`, clear message) |
| `act-aria/.../prompt/AriaPromptCatalog.java` | glasses rules (Section 7) |
| `act-mcp/.../tools/ArRenderTools.java` (new) | the `ar_render` `@Tool` bean |
| `act-mcp/.../catalog/ToolCatalog.java` | `ar_render` descriptor, `WORKER_READ`, auto-approve |
| `act-mcp/src/test/.../McpToolInventory.java` | add `ar_render` |
| `opencode-sandbox/Dockerfile` | `opencode-ai@1.19.6`; rebuild the image |

Provider model pin, session model handling and the progress pump are untouched.

## 7. Prompt policy

Add one block to `AriaPromptCatalog.POLICY`; the tool list itself is generated and needs no manual edit.

```text
IMPORTANT — glasses and AR:
When the user turn begins with "[glasses observation]" you are speaking into a
head-mounted display. Answer in Traditional Chinese written Cantonese, in at
most two short sentences, and never more than five lines. The phone speaks it
with a Cantonese (yue-HK) voice.
Use ar_render for spatial guidance. Prefer the observation's trackId when it
exists; never invent a trackId, objectId or bbox. If clientCapabilities has
ar=false, do not call ar_render.
Call ar_render before your text answer. One primitive per call; several calls
in one turn are applied in order.
If the object you need is not in the observation, ask for a closer snapshot or
use a label with a bbox instead of guessing a location.
```

## 8. Privacy and retention

- Image bytes live only in memory for the turn: not in the database, not in the trajectory, not in the prompt seed, not in SSE frames, not in logs.
- opencode persists the user message (including the data URL) in its own session store. In SANDBOX mode that store is inside the per-run sandbox, which `SandboxLifecycle.destroy(runId)` tears down at the end of the run. In HOST mode it survives under the Mac's opencode data directory; this is a known residual, documented here rather than silently ignored.
- Progress streaming is safe by construction: only assistant parts are streamed and the snapshot projection excludes `url`.
- Tool arguments are redacted by `CredentialRedactor` before they reach the audit row and the stream. `ar_render` carries no secret-shaped data.
- The request DTO must not expose the base64 through Lombok's generated `toString()`. Use `@ToString.Exclude` on the image field or a hand-written DTO.
- The glasses must not log the bearer; the phone side owns that check.

## 9. Failure modes

| Situation | Behaviour |
|-----------|-----------|
| Active model is not multimodal | The run fails; the SSE `error` frame carries `turnFailed`, `runId` and the provider reason. The phone shows it and falls back to text chat or its Direct engine. No silent digest fallback. |
| Aria's core is not opencode | 409 before a Run is created |
| Image over the cap or wrong MIME | 413 / 415 |
| Invalid base64 | 400 |
| SSE disconnect | No replay; the phone re-observes. A `GET /approvals?status=PENDING` rebuilds cards. |
| Overlapping observations | They queue on the conversation admission pool; the phone must keep one in flight |
| Approval expiry | The frame carries `expiresAt`; the run deadline is unchanged |
| LAN request without bearer | 401 |
| Host-mode image retention | Documented residual; no cleanup task in phase 1 |

## 10. Testing

Every contract test runs without a real model.

| Test | Proves |
|------|--------|
| `AriaObserveControllerTest` (MockMvc) | 401/403 authority; 400/413/415/422 mapping; 409 on a non-opencode core; latest-conversation default; SSE emitter returned on success |
| `ObservationSeedTest` | seed contains no base64; 8 KiB cap; field truncation; ISO timestamp |
| `OpenCodeHttpClientAttachmentTest` | file part JSON shape (`type/mime/filename/url`), text parts order, no `model` member, attachment omitted when empty |
| `OpenCodeCoreSessionTest` | attachments appended after the user text part; empty attachments unchanged |
| `AriaObserveCoreRefusalTest` | Qoder session refuses attachments with a clear failure |
| `ArRenderToolsTest` | all eight primitives; missing target → `VALIDATION`; ack shape; `CredentialRedactor.redact(args)` still parses as JSON |
| `ToolCatalogParityTest` (act-mcp) | inventory equals the catalog, which now includes `ar_render` |
| `AriaPromptToolNamesTest` (act-mcp) | every snake_case tool name in the policy exists in the catalog, `ar_render` included |
| `AriaGlassesPromptTest` (new, act-aria) | the glasses block is present in the policy and still names the rules above |
| `Dockerfile` pin check | 1.19.6 referenced (build-time, manual) |

Commands (AGENTS.md lanes):

```bash
bash scripts/mvn-lane.sh gate act-execution
bash scripts/mvn-lane.sh gate act-mcp
bash scripts/mvn-lane.sh gate act-aria
cd agent-control-tower && mvn test -pl act-execution -Dtest="*OpenCode*"
```

The opencode upgrade also runs the progress-pump timing lane, because its cadence assertions describe 1.18.15.

## 11. Phases and acceptance

Phase 1: opencode 1.19.6 upgrade, core attachment plumbing, `/observe`, prompt rules, `ar_render`, tests.

Phase 2: contract E2E with the phone against a real backend, approvals round trip, HUD render of each primitive.

Phase 3 (only if needed): Qoder bridge image support. Eye and 6DoF are a separate spec.

Acceptance:

- The live multimodal probe passes: one real JPEG through opencode 1.19.6, answered by the active model. Until then this item is NOT VERIFIED.
- A mocked observe request produces `thinking`, at least one `ar_render` tool call from a fixture, a `message`, and `done` with the conversation id.
- An image is never present in a DB row, a trajectory row, a log line or an SSE frame (asserted by the seed test plus a log-scan review).
- Existing suites stay green, coverage ratchets unchanged.
- No secrets or raw frames in any artifact.

## 12. Open items

- The `ar_render` camelCase correction (`directionDeg`, `distanceM`, `durationS`) was sent to the phone session after its `faaf5b2` commit; its spec update is pending. Until it lands, the two specs differ on those three names.
- The live multimodal probe depends on the local model server being up.
- HOST-mode opencode session retention is accepted, not solved.
- Sharing the PA conversation means observation turns appear in the dashboard timeline as user turns with the observation seed. A dashboard filter for observation turns is a follow-up, not part of this spec.
- Qoder observe support is deferred; the endpoint answers 409 today.
