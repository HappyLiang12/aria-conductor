# Aria Chat Image Attachments (Dashboard)

- Date: 2026-11-22
- Status: design approved by the operator (2026-11-22, brainstorming session). Implementation not started.
- Depends on: PR #124 (governed model image capability). The implementation branch is stacked on it until that PR lands.
- Scope: act-aria (chat API), act-execution (sync start overload), act-dashboard (AriaPanel composer, API client). No migrations, no storage.

## Problem

The active model is now multimodal (`deepseek-flash`), and `/api/v1/aria/observe` already carries an image into the Aria turn. The dashboard chat is still text-only: `AriaPanel` has a plain textarea, `AriaChatRequest` has no attachment field, and the conversation timeline has no notion of an attachment. The operator expects to paste or attach images in chat, see thumbnails, and have the model see them.

## Decisions (operator, 2026-11-22)

| Id | Decision |
|----|----------|
| D1 | Phase 1 accepts **images only**: JPEG, PNG, WebP, up to 5 per turn. PDF/Office/audio are rejected in the UI with a clear message. |
| D2 | Attachments are **ephemeral**: they exist for that turn's run context. The database stores only a text summary in the prompt seed; there is no attachment table, no thumbnail storage, and no migration. |
| D3 | Transport is the **existing chat/stream JSON**: an optional `attachments` array of base64 images. No multipart, no separate upload endpoint. |
| D4 | Pre-stream validation failures answer **JSON 400/413/415/422**, not an SSE error frame, matching the observe endpoint's contract. |
| D5 | Both the streaming and the sync chat paths accept attachments through the same request DTO and the same validation. |

## Verified evidence

| Claim | Evidence |
|-------|----------|
| The dashboard composer is text-only | `AriaPanel.tsx` sends `streamMessage(conversationId, text, history, callbacks)` from `sendStreamed`; the history is `{role, content}` only |
| The chat request has no attachments | `AriaChatRequest` carries `sessionId`, `conversationId`, `message`, `history`, `skillId` |
| The stream client reads only statusText on failure | `aria.ts` `streamMessage`: `if (!response.ok ...) onError("Stream request failed: " + statusText)` |
| The run/core attachment path already exists | `CoreAttachment` -> `CoreTask.attachments()` -> `OpenCodeCoreSession` -> `OpenCodeHttpClient` file part; `AgentLoopEngine.startRunStream(..., List<CoreAttachment>)` at `AgentLoopEngine.java:236`; observe uses it |
| The governed model declares image input | `OpenCodeCoreAdapter` `models.<id>` now carries `"attachment": true` and `"modalities": {"input": ["text","image"]}` (commit `ed7ff542`) |
| The active model is multimodal | Live `/observe` with a 640x480 JPEG: `deepseek-flash` described "VISION-42", a red circle, a blue square and three black stars |
| Images are accepted inline by the provider | Direct probe: `{"type":"image_url","image_url":{"url":"data:image/jpeg;base64,..."}}` returns a correct description |
| PDFs are not inline | Direct probe: non-image `file_data` -> 400 "file_data only accepts images; upload documents ... through the Files API"; Files API upload + flat `{type:"file", file_id}` works, but the opencode core has no Files API step |
| The sync path cannot carry attachments today | `AgentLoopEngine` exposes `startRun(UUID)` and `startRun(UUID, List<LlmMessage>)` only |

## API contract

`POST /api/v1/aria/chat/stream` and `POST /api/v1/aria/chat` both accept an optional body member:

```json
"attachments": [
  {
    "mimeType": "image/jpeg",
    "filename": "photo.jpg",
    "dataBase64": "…",
    "width": 1280,
    "height": 720
  }
]
```

- `mimeType` allowlist: `image/jpeg`, `image/png`, `image/webp`.
- Count: at most 5. Each decoded image at most 4 MiB. Total decoded at most 10 MiB.
- `dataBase64` must decode; an empty payload is rejected. `width`/`height` are informational.
- The images are run context only. They are never persisted, never logged, never placed on the SSE stream.
- The persisted prompt seed is the operator's text plus one summary line, for example
  `[attached images: photo.jpg (image/jpeg, 120 KB), shot.png (image/png, 88 KB)]`.
  This is what the timeline shows after a reload; the summary never contains base64.

Status codes for a pre-stream rejection (stream endpoint returns JSON for these):

| Status | Condition |
|--------|-----------|
| 400 | malformed base64, missing required members |
| 413 | a decoded image over 4 MiB, or the total over 10 MiB |
| 415 | a MIME outside the allowlist |
| 422 | more than 5 attachments, or a zero-byte image |

The sync endpoint returns the same statuses through the controller's explicit rejection (the validator's status and message) and never starts a Run for a rejected request.

## Backend changes

| File | Change |
|------|--------|
| `act-aria/.../dto/AriaChatRequest.java` | add `List<Attachment> attachments`; `Attachment {mimeType, filename, dataBase64, width, height}`; the base64 must not appear in a generated `toString()` (same treatment as `AriaObserveRequest.Image`) |
| `act-aria/.../service/AriaChatAttachmentValidator.java` (new) | pure validation and decoding: allowlist, counts, decoded sizes, base64 validity. Returns either the decoded parts or a rejection (status + message). Shared by both controllers. |
| `act-aria/.../controller/AriaChatController.java` | validate before creating the emitter; the streaming method returns `ResponseEntity<SseEmitter>` so a rejection is JSON; the sync method validates before calling the service |
| `act-aria/.../service/AriaStreamService.java` | build `List<CoreAttachment>`, append the seed summary to the Run's `promptSeed`, call `startRunStream(..., attachments)` |
| `act-execution/.../engine/AgentLoopEngine.java` | add `startRun(UUID, List<LlmMessage>, List<CoreAttachment>)` delegating to the existing attachment-aware `startRunInternal`; keep the current overloads |
| `act-aria/.../service/AriaService.java` | sync path: append the seed summary and call the new `startRun` overload with the decoded attachments |

Nothing else in the core changes: the governed config already declares image input, and the file-part transport is the one observe uses.

## UI

`AriaPanel.tsx` composer gains three entry points, all producing the same pending-image list:

- paste: image items from the clipboard;
- drag and drop onto the composer;
- a paperclip button opening a file picker (`accept="image/jpeg,image/png,image/webp"`, multiple).

Rules:

- Downscaling is deterministic: an image whose longest side is over 2048 px, or whose encoded size is over 1.5 MB, is redrawn on a canvas and re-encoded as JPEG at quality 0.85. A PNG screenshot already within both limits is sent unchanged so its text stays crisp.
- The UI enforces the same limits: type, at most 5, per-image and total size after downscaling. A rejected drop or paste shows an inline message; PDF and other types say "phase 1 supports images only".
- On send, the request body carries the attachments; the user bubble renders the thumbnails from the client's object URLs (the transcript is client-side for this turn).
- A retry resends the last turn's attachments, which stay in memory until the next successful send.
- A pre-stream 400/413/415/422 is shown as the specific reason. The composer clears only once the server has accepted the request (a 2xx response); on a rejection the draft and pending images stay so the operator can fix them. The stream client reads the JSON error body instead of only `statusText`.
- Object URLs are revoked when an image is removed and when the panel unmounts.

`api/aria.ts` gains `AriaAttachmentPayload`, and `streamMessage`/`sendMessage` accept the attachments and forward them in the JSON body.

## Privacy and retention

- Image bytes live only in the browser and in the server's memory for the turn; the prompt seed carries names, types and sizes only.
- The request DTO must not expose base64 through `toString()`; the service logs nothing about the attachments beyond the summary line.
- The core's session store receives the image as it does for observe (per-run sandbox destroyed at the end; the HOST-mode residual is the same one documented for observe).
- The existing chat endpoints are not operator-gated; adding images does not change that posture, and this spec does not widen it.

## Failure modes

| Situation | Behaviour |
|-----------|-----------|
| Active model is text-only | The run fails; the existing `turnFailed` bubble says so. The seed summary still appears in the timeline. |
| Oversized or too many images | Pre-stream 413/422 with the specific reason; the draft is preserved. |
| Unsupported type (PDF, Office, audio) | The UI rejects it before sending; the server also returns 415 if one arrives. |
| Model error with images | The turn fails as usual; no attachment bytes appear in the error text. |
| Follow-up turn referring to an earlier image | The client history is text-only, so the earlier image is not re-sent; the model sees only what it said about it. Documented limitation of phase 1. |

## Testing

Backend:

- `AriaChatAttachmentValidatorTest`: allowlist, base64, per-image and total caps, count, zero bytes.
- `AriaChatControllerTest`: rejection statuses before a stream exists; success still returns the SSE emitter.
- `AriaStreamServiceTest` / `AriaServiceTest`: the Run's seed carries the summary and no base64; `startRunStream`/`startRun` receive the decoded parts.
- Existing core attachment tests stay green.

Frontend:

- `AriaPanel` tests: paste, drop and picker add chips; remove; type/size/count errors; the request body carries the attachments; the user bubble renders thumbnails.
- `aria.ts` test: a JSON error body on a non-2xx response reaches `onError` as its message.
- Playwright (deterministic harness): open the Aria panel, attach an image, send; assert the request body contains the attachment and the user bubble shows a thumbnail, then the run completes.

Commands follow AGENTS.md lanes: `bash scripts/mvn-lane.sh gate act-aria` and `gate act-execution`; `pnpm build` / `pnpm test` in `act-dashboard`; the harness smoke lane for the Playwright test.

## Out of scope

- PDF, Office and audio attachments. The provider needs its Files API for documents, and the opencode core has no provider-file-upload step; the UI and server reject those types clearly in phase 1.
- Persisting attachments or thumbnails; re-opening an attachment after a reload.
- Server-side text extraction or OCR.
- A capability check against the active model before sending; a text-only model fails loudly at run time.
