# Aria × Phase 3 AR Accuracy (Aria side)

- Date: 2026-11-24
- Status: design for review. Phone plan: `aria-glass` `docs/superpowers/plans/2026-11-24-phase3-ar-accuracy.md` (`bf1f06d`), contract X10, X12–X18; decisions D14 (Qwen LIVE primary / DeepSeek direct async backup) and D15 (tool tiers).
- Scope: `act-aria` live channel and tool execution, `packages/qwen-live-bridge`, one new Aria-side DeepSeek locator. No phone changes here.
- Depends on PR #130 (merged as `a56ef7c2`).

## What already exists (verified)

| Claim | Evidence |
|---|---|
| The live channel accepts `frame{mime,dataBase64}` (JPEG, ≤256 KB base64) and sends it to the bridge, which appends it before the utterance commit | `LiveWebSocketHandler` frame path, `LiveWebSocketHandlerTest.aLegal256KibFrame...` |
| The bridge appends the image before `input_audio_buffer.commit` and never asks for an image-only response | `packages/qwen-live-bridge/src/session.ts` `commit()`; P0 image probe |
| An image-only commit (no audio) is rejected by the provider | live observation 2026-11-24; no audio since last commit |
| The live channel has 11 server event types; `ar_render` exists only on the run SSE stream | spec `2026-11-21-aria-glasses-observe-ar-design.md` §5.2; `LiveFrames` |
| `ar_render` renders `{primitive, target{trackId,objectId,label,bbox}, text, directionDeg, durationS, …}`; `bbox` is normalized `[x,y,w,h]` in `[0,1]` | ar_render tool + spec §5.3 |
| The live tool set is the seven read-only tools; `ToolOutcome.PendingApproval` exists but no tool produces it yet | `LiveToolExecutor`, `LiveEventRouter` |
| The active DeepSeek provider is `api.deepseek.com` / `deepseek-flash`; its key is in Aria's env (`LLM_API_KEY`) | dashboard provider row; `.env` |
| Qwen `turn_detection.create_response=false` behaviour is unproven | P0 used `null`; Phase 2 uses `semantic_vad_ec` |

## Decisions for review

### D1 — `ar_render` v2 travels on the live channel

`ar_render` becomes the 12th live server event:

```json
{"type":"ar_render","v":1,"renderId":"r-…","render":{
  "primitive":"highlight",
  "target":{"label":"5mm hex key","bbox":[0.41,0.24,0.18,0.13]},
  "text":"5mm 六角匙","ttlMs":8000,"coord":"normalized","safetyAlert":null}}
```

- Phone default `ttlMs` 8000, clamped 1–60 s; `safetyAlert` optional `{level,text}`; `coord` must be `normalized`.
- At most one render per turn; unique `renderId` per render; unknown `type`/`v≠1`/unknown `coord` are dropped by the phone.
- **Open conflict in the phone contract:** X12's example carries `runId`, X13 says `highlight_target` is display-only with **no run**. Aria's proposal: omit `runId` for a display-only render (the phone must accept its absence); if the turn already has a lazy live run for an ops tool call, carry that runId. The phone must confirm which.

### D2 — `highlight_target` is executed by the bridge (no run, no approval)

The model's tool call is display-only: no run row, no approval, no nested tool call. The bridge declares the tool, intercepts `response.function_call_arguments.done`, emits the `ar_render` control itself with the translated render, and answers the model with a short `function_call_output` (`{"ack":true,"renderId":"…"}`) so it speaks one line. Audit is a metadata log line (`renderId` and counts only), not a run row — this intentionally narrows the old "every ar_render call writes an audit row" rule for the live display tool.

Declared function (verbatim contract):

```json
{"name":"highlight_target","description":"Point the HUD at exactly one object the wearer just asked about. Display only.",
 "parameters":{"type":"object","properties":{
  "label":{"type":"string"},"box_2d":{"type":"array","items":{"type":"number"},"minItems":4,"maxItems":4},
  "text":{"type":"string"}},"required":["label","box_2d","text"]}}
```

Instruction added to the live session (Cantonese): 「有圖嘅時候，用戶講『睇下／邊度／邊個』，先叫 `highlight_target`，再用一句最多 15 字講出答案。冇圖或者唔肯定，就講睇唔到，唔准估框。」

### D3 — Coordinate translation

Model `box_2d:[y0,x0,y1,x1]` on a 0–1000 grid → `bbox=[x0,y0,x1-x0,y1-y0]/1000`, clamped to `[0,1]`, width/height > 0. Invalid/empty boxes are not rendered; the model is told to say it cannot see. No `box_2d` or `ttlMs` field reaches the phone.

### D4 — AR grace (X16)

The bridge sets `turn_detection.create_response=false` (shape to be proven against the SG endpoint) and, after `speech_end`, waits at most 300 ms for a `frame`; then it sends an explicit `response.create`. If the provider rejects that configuration, the fallback is the current auto-response behaviour: the frame may miss the turn, the model says 睇唔到 rather than guessing, and A1 is degraded — a recorded deviation, not a silent one.

### D5 — Client `ar` event (X17)

Accept `{"type":"ar","v":1,"on":true|false}` (before or after `hello`), remember it per socket, use `hello.ar` on reconnect, ignore duplicates/unknown values. AR state only gates D4; it changes no other frame.

### D6 — DeepSeek direct locator (X14)

New Aria service (act-aria, no new module): stateless `POST https://api.deepseek.com/chat/completions`, model `deepseek-flash`, key from `LLM_API_KEY` (never argv/logs), prompt asks for strict JSON `{"box_2d":[y0,x0,y1,x1],"confidence":0..1}` with **no prose**, 1.5 s budget, no retries. Trigger: **backup only** — when a live turn carried an image and a look-intent input transcript (`睇下|邊度|邊個`) and Qwen finished without a `highlight_target` render. The locator emits one `ar_render` v2 (ttl 8000) on the same live socket; it never delays the spoken answer (best-effort overlay after the line), degrades silently, and never runs for text-only turns. Privacy: only counts/latency logged; request/response bodies never.

### D7 — Ops tool tiers (X15)

Authoritative list (see the plan's table): the seven read-only tools plus `list_skills`, `list_runs`, `get_agent` (read, direct); `start_agent_run` (catalog `run_agent`: `{agentId, prompt, maxIterations?}`) and `run_skill` (`{skillId, prompt?}`, new) are write tools: every call returns `PENDING_APPROVAL`, creates the approval on the turn's lazy live run, emits `approval_required{runId}`, and executes only after the existing REST decision; the result is injected as `[tower] …`. `approve`, `finalize`, `answer` and every other operator-decision tool are never declared. **Open question:** `run_skill` does not exist in the catalog; confirm a new tool versus `assign_skill` + `run_agent` (two approvals).

### D8 — Frame ingest enforcement (X10)

At most one frame per turn; `frame` still ≤256 KB base64, JPEG only. A `commit{image:true}` with no audio since the previous commit answers `error{code:"frame_invalid",retryable:false}` and is not forwarded. An image that never reaches a commit is dropped at turn end. The existing `frame_too_large`/`frame_invalid` paths keep the socket.

## Out of scope

Overlay rendering/state, tracker correction, safety-banner UX and HUD metrics on the phone; world-locked AR; async TOWER runs beyond the existing live-run approval path.

## Open items for the phone

1. `ar_render` v2 `runId`: omitted for display-only renders (D1) — confirm the phone accepts that.
2. The phone's `LiveProtocol` must add the `ar_render` event; confirm its `render.bbox`/`ttlMs`/`safetyAlert` handling matches §D1.
3. `run_skill`: new tool or `assign_skill` + `run_agent`?
4. DeepSeek backup timing: a render after the spoken line is acceptable as best-effort (it cannot meet A1 alone), correct?
