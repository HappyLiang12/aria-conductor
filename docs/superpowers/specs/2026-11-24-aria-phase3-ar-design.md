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
- **Decided (operator, 2026-11-24):** a display-only render **omits `runId`**; if the turn already has a lazy live run for an ops tool call, that runId is carried. The phone accepts its absence.

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

### D4 — AR grace (X16) — decided from a live probe

Probe on the SG endpoint (2026-11-24, real key, audio question + red JPEG):

| Variant | Result |
|---|---|
| `semantic_vad_ec` + `create_response:false`, image appended after the auto-commit | session.update accepted, but **no commit and no response**; the image turn never answers |
| `turn_detection:null`, explicit `input_audio_buffer.commit` **after** the image append, then `response.create` | **works**: input transcript, committed, response, final text 「紅。」 |

Decision:

- **AR off** keeps `semantic_vad_ec` exactly as Phase 2 (server VAD, barge-in).
- **AR on** switches the session to `turn_detection:null`. The phone sends `commit` at `speech_end` (a contract delta: AR-on uses the existing client `commit` event; the bridge no longer relies on provider auto-commit), then the bridge waits at most 300 ms for a `frame`, appends the image if one arrived, commits and asks `response.create`. No frame → commit without an image; the instruction makes the model say 睇唔到 rather than guess.
- **Amended 2026-11-25 from the field defect and a live probe.**
  - The provider refuses a `turn_detection` change while its input buffer is non-empty: `Cannot change turn_detection while input audio is pending` (`invalid_request_error`, param `session.turn_detection`). The phone streams continuously, so the guard-lifting switch failed in the field. The bridge now pauses the phone's audio for the switch, sends `input_audio_buffer.clear` for the silent tail already buffered, then the `session.update`, and replays the held audio after the acknowledgement. A switch whose pending tail contains speech waits for the commit that empties the buffer instead of clearing it.
  - With no turn detection the provider never ends an utterance, so the server emits no `speech_end` on its own and a phone that waits for it deadlocks. **Deployed contract:** in AR-on the bridge's own audio-energy end-of-speech is the EOS. It counts ≥200 ms of speech (peak ≥1200 s16le) then 600 ms below it from the phone's PCM and emits exactly one `speech_end` per utterance; the provider's echoed `committed` does not emit a second. The phone keeps its current trigger (send `frame`, then `commit{image:true}` on `speech_end`). AR-off is unchanged.
  - The first AR turn is still VAD-ended (the guard), unless the phone declares `hello.arCommit` (D5 amendment). Its provider commit already answered the utterance, so the phone's commit crossing then would be an empty-buffer provider error and a second response would race the live answer: the bridge consumes that commit as the mode proof, drops the image for that answered turn, and crosses nothing. From the next utterance the manual path is the deployed one. If the buffer still holds the utterance's own speech (no VAD commit yet), the switch would drop it, so the switch waits for that commit; the held turn answers under the applied mode after the mode-proof settle window (2 s) instead of waiting forever.
- An `ar` toggle mid-session sends a new `session.update` with the matching `turn_detection` (proven live; the buffer is emptied first as above). A refusal still reverts to the applied mode and is counted.
- Barge-in in AR is the phone's tap-to-interrupt (`cancel`); server VAD barge-in only exists in AR-off.

### D5 — Client `ar` event (X17)

Accept `{"type":"ar","v":1,"on":true|false}` (before or after `hello`), remember it per socket, use `hello.ar` on reconnect, ignore duplicates/unknown values. AR state only gates D4; it changes no other frame.

**Amendment 2026-11-25 — `hello.arCommit` (the first look is a normal AR turn).** A phone that drives every AR turn itself (its own end-of-speech detection, then `frame` + `commit{image:true}`) declares `"arCommit": true` in `hello`. Aria remembers it per socket, echoes it in `ready` (`{"type":"ready",…,"arCommit":true}`) as the acknowledgement the phone waits for, and seeds it into the bridge `hello`; the bridge then starts with the commit guard already satisfied, so the handshake carries `turn_detection:null` and the **first** AR look attaches its frame and gets exactly one response like every later one. Absent or false keeps the guard (D5 as written). The field never changes the AR-off path.

### D6 — DeepSeek direct locator (X14)

New Aria service (act-aria, no new module): stateless `POST https://api.deepseek.com/chat/completions`, model `deepseek-flash`, key from `DEEPSEEK_API_KEY` only — never the generic `LLM_API_KEY`, and a deployment with no DeepSeek key leaves the locator disabled (never argv/logs), prompt asks for strict JSON `{"box_2d":[y0,x0,y1,x1],"confidence":0..1}` with **no prose**, 1.5 s budget, no retries. Trigger: **backup only** — when a live turn carried an image and a look-intent input transcript (`睇下|邊度|邊個`) and Qwen finished without a `highlight_target` render. The locator emits one `ar_render` v2 (ttl 8000) on the same live socket; it never delays the spoken answer (best-effort overlay after the line — accepted by the operator; the primary Qwen path is the one that meets A1), degrades silently, and never runs for text-only turns. Privacy: only counts/latency logged; request/response bodies never.

### D7 — Ops tool tiers (X15)

Authoritative list (see the plan's table): the seven read-only tools plus `list_skills`, `list_runs`, `get_agent` (read, direct); `start_agent_run` (catalog `run_agent`: `{agentId, prompt, maxIterations?}`) and `run_skill` (`{skillId, prompt?}`, new) are write tools: every call returns `PENDING_APPROVAL`, creates the approval on the turn's lazy live run, emits `approval_required{runId}`, and executes only after the existing REST decision; the result is injected as `[tower] …`. `approve`, `finalize`, `answer` and every other operator-decision tool are never declared. **Decided (operator, 2026-11-24):** `run_skill` is a new Aria-side write tool — it resolves the skill and starts the Aria run with that skill applied, one approval. It does not compose `assign_skill` + `run_agent`.

### D8 — Frame ingest enforcement (X10)

At most one frame per turn; `frame` still ≤256 KB base64, JPEG only. A `commit{image:true}` with no audio since the previous commit answers `error{code:"frame_invalid",retryable:false}` and is not forwarded. An image that never reaches a commit is dropped at turn end. The existing `frame_too_large`/`frame_invalid` paths keep the socket.

## Out of scope

Overlay rendering/state, tracker correction, safety-banner UX and HUD metrics on the phone; world-locked AR; async TOWER runs beyond the existing live-run approval path.

## Remaining phone deltas (decided here; phone adapts)

1. `ar_render` v2 has no `runId` for display-only renders (unless a turn run exists); `LiveProtocol` adds the event and accepts an absent `runId`.
2. AR-on requires the phone's explicit `commit` at `speech_end`; AR-off is unchanged (`semantic_vad_ec`). In AR-on `speech_end` arrives from the bridge's audio-energy EOS (≈600 ms after the wearer stops), not from provider VAD; add ~600 ms to the first-audio budget.
3. The phone must add `{"type":"ar","v":1,"on":…}` (X17) and send `hello.ar` on reconnect.
4. DeepSeek backup renders may appear after the spoken line; they are best-effort and do not promise A1.
