# Aria Chat Image Attachments Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let the operator paste, drop or pick images in the Aria dashboard chat, have the model see them for that turn, and see thumbnails in the composer and the sent message, while the transcript keeps only a text summary.

**Architecture:** The existing chat/stream JSON gains an optional `attachments` array of base64 images; a shared validator turns them into `CoreAttachment`s, which the already-existing run/core path carries as file parts. The persisted prompt seed carries a names/sizes summary only. The dashboard composer reads files, downscales them in the browser, and sends the base64 payload.

**Tech Stack:** Java 21, Spring Boot 3.4, React 19 / Vite / Vitest / Playwright, `CoreAttachment` + opencode data-URL file parts.

**Spec:** `docs/superpowers/specs/2026-11-22-aria-chat-image-attachments-design.md` (approved 2026-11-22). Read it before starting; this plan implements it and does not extend it.

## Global Constraints

- Phase 1 accepts images only: `image/jpeg`, `image/png`, `image/webp`.
- At most 5 attachments per turn; each decoded image at most 4 MiB; the decoded total at most 10 MiB.
- Pre-stream rejections answer JSON 400 (malformed base64/missing member), 413 (sizes), 415 (MIME), 422 (count/zero bytes).
- Images are turn-only run context. Never persist, log, or put base64 on the SSE stream; the prompt seed carries only `[attached images: name (mime, N KB), …]`.
- The request DTO must not print base64 through a generated `toString()`.
- Both `POST /api/v1/aria/chat/stream` and `POST /api/v1/aria/chat` accept attachments through the same validator.
- The design branch is stacked on PR #124 (governed model image capability); do not rebase onto an older main.
- Existing tests stay green; coverage ratchets unchanged.
- Code, comments and UI copy stay in English.

---

## File Structure

New files:

- `agent-control-tower/act-aria/src/main/java/io/aria/conductor/aria/service/AriaChatAttachmentValidator.java`
- `agent-control-tower/act-aria/src/test/java/io/aria/conductor/aria/service/AriaChatAttachmentValidatorTest.java`
- `agent-control-tower/act-dashboard/src/components/ariaAttachments.ts`
- `agent-control-tower/act-dashboard/src/components/__tests__/ariaAttachments.test.ts`
- `agent-control-tower/act-dashboard/e2e/aria-attachments.spec.ts`

Modified files:

- `agent-control-tower/act-aria/src/main/java/io/aria/conductor/aria/dto/AriaChatRequest.java`
- `agent-control-tower/act-aria/src/main/java/io/aria/conductor/aria/controller/AriaChatController.java`
- `agent-control-tower/act-aria/src/main/java/io/aria/conductor/aria/service/AriaStreamService.java`
- `agent-control-tower/act-aria/src/main/java/io/aria/conductor/aria/service/AriaService.java`
- `agent-control-tower/act-execution/src/main/java/io/aria/conductor/execution/engine/AgentLoopEngine.java`
- `agent-control-tower/act-aria/src/test/java/io/aria/conductor/aria/controller/AriaChatControllerTest.java`
- `agent-control-tower/act-aria/src/test/java/io/aria/conductor/aria/service/AriaStreamServiceTest.java`
- `agent-control-tower/act-aria/src/test/java/io/aria/conductor/aria/service/AriaServiceChatTest.java`
- `agent-control-tower/act-dashboard/src/api/aria.ts`
- `agent-control-tower/act-dashboard/src/api/__tests__/aria.test.ts`
- `agent-control-tower/act-dashboard/src/components/ariaPanelState.ts`
- `agent-control-tower/act-dashboard/src/components/__tests__/ariaPanelState.test.ts`
- `agent-control-tower/act-dashboard/src/components/AriaPanel.tsx`
- `agent-control-tower/act-dashboard/src/components/__tests__/AriaPanel.test.tsx`
- `agent-control-tower/act-dashboard/src/styles/index.css`

---

### Task 1: Request DTO and attachment validator

**Files:**
- Modify: `agent-control-tower/act-aria/src/main/java/io/aria/conductor/aria/dto/AriaChatRequest.java`
- Create: `agent-control-tower/act-aria/src/main/java/io/aria/conductor/aria/service/AriaChatAttachmentValidator.java`
- Test: `agent-control-tower/act-aria/src/test/java/io/aria/conductor/aria/service/AriaChatAttachmentValidatorTest.java`

**Interfaces:**
- Produces: `AriaChatRequest.getAttachments()` returning `List<AriaChatRequest.Attachment>`; `Attachment {mimeType, filename, dataBase64, width, height}` with the base64 excluded from `toString()`.
- Produces: `AriaChatAttachmentValidator.validate(List<AriaChatRequest.Attachment>)` returning `Result(Rejection rejection, Validated validated)`.
- Produces: `Rejection(HttpStatus status, String message)`.
- Produces: `Validated(List<CoreAttachment> parts, String summary)` where `summary` is `"[attached images: photo.jpg (image/jpeg, 120 KB)]"` and empty for no attachments.

- [ ] **Step 1: Write the failing validator test**

```java
package io.aria.conductor.aria.service;

import io.aria.conductor.aria.dto.AriaChatRequest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AriaChatAttachmentValidatorTest {

    private static final int KB = 1024;

    @Test
    void noAttachmentsYieldAnEmptyResult() {
        AriaChatAttachmentValidator.Result result = AriaChatAttachmentValidator.validate(null);

        assertThat(result.rejection()).isNull();
        assertThat(result.validated().parts()).isEmpty();
        assertThat(result.validated().summary()).isEmpty();
    }

    @Test
    void twoImagesDecodeAndSummarizeWithoutBase64() {
        AriaChatAttachmentValidator.Result result = AriaChatAttachmentValidator.validate(List.of(
                attachment("image/jpeg", "photo.jpg", bytes(2 * KB)),
                attachment("image/png", "shot.png", bytes(KB))));

        assertThat(result.rejection()).isNull();
        assertThat(result.validated().parts()).hasSize(2);
        assertThat(result.validated().parts().get(0).mimeType()).isEqualTo("image/jpeg");
        assertThat(result.validated().parts().get(0).bytes()).hasSize(2 * KB);
        assertThat(result.validated().summary())
                .isEqualTo("[attached images: photo.jpg (image/jpeg, 2 KB), shot.png (image/png, 1 KB)]");
        assertThat(result.validated().summary()).doesNotContain("AAAA");
    }

    @Test
    void anUnsupportedMimeIs415() {
        assertRejected(List.of(attachment("application/pdf", "doc.pdf", bytes(10))),
                HttpStatus.UNSUPPORTED_MEDIA_TYPE);
    }

    @Test
    void invalidBase64Is400() {
        AriaChatRequest.Attachment broken = attachment("image/jpeg", "x.jpg", bytes(10));
        broken.setDataBase64("not base64 !!");

        assertRejected(List.of(broken), HttpStatus.BAD_REQUEST);
    }

    @Test
    void moreThanFiveIs422() {
        List<AriaChatRequest.Attachment> many = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            many.add(attachment("image/jpeg", "x" + i + ".jpg", bytes(10)));
        }

        assertRejected(many, HttpStatus.UNPROCESSABLE_ENTITY);
    }

    @Test
    void aZeroByteImageIs422() {
        assertRejected(List.of(attachment("image/jpeg", "empty.jpg", new byte[0])),
                HttpStatus.UNPROCESSABLE_ENTITY);
    }

    @Test
    void anImageOverFourMebibytesIs413() {
        assertRejected(List.of(attachment("image/jpeg", "big.jpg", bytes(4 * 1024 * KB + 1))),
                HttpStatus.PAYLOAD_TOO_LARGE);
    }

    @Test
    void aTotalOverTenMebibytesIs413() {
        assertRejected(List.of(
                        attachment("image/jpeg", "a.jpg", bytes(4 * 1024 * KB)),
                        attachment("image/jpeg", "b.jpg", bytes(4 * 1024 * KB)),
                        attachment("image/jpeg", "c.jpg", bytes(3 * 1024 * KB))),
                HttpStatus.PAYLOAD_TOO_LARGE);
    }

    private static void assertRejected(List<AriaChatRequest.Attachment> attachments, HttpStatus status) {
        AriaChatAttachmentValidator.Result result = AriaChatAttachmentValidator.validate(attachments);
        assertThat(result.rejection()).isNotNull();
        assertThat(result.rejection().status()).isEqualTo(status);
    }

    private static AriaChatRequest.Attachment attachment(String mime, String name, byte[] bytes) {
        return AriaChatRequest.Attachment.builder()
                .mimeType(mime)
                .filename(name)
                .dataBase64(Base64.getEncoder().encodeToString(bytes))
                .build();
    }

    private static byte[] bytes(int count) {
        byte[] out = new byte[count];
        for (int i = 0; i < count; i++) {
            out[i] = (byte) (i % 251 + 1);
        }
        return out;
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd /Users/happy/Documents/project/aria-conductor && bash scripts/mvn-lane.sh inner act-aria -Dtest=AriaChatAttachmentValidatorTest`
Expected: compilation failure (`AriaChatAttachmentValidator` and `Attachment` missing).

- [ ] **Step 3: Extend the request DTO**

Add to `AriaChatRequest`, plus `import lombok.ToString;`:

```java
    /** Optional images for this turn; bytes are run context only. */
    private List<Attachment> attachments;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Attachment {
        private String mimeType;
        private String filename;
        @ToString.Exclude
        private String dataBase64;
        private Integer width;
        private Integer height;
    }
```

- [ ] **Step 4: Create the validator**

```java
package io.aria.conductor.aria.service;

import io.aria.conductor.aria.dto.AriaChatRequest;
import io.aria.conductor.execution.runtime.CoreAttachment;
import org.springframework.http.HttpStatus;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Set;

/**
 * One validation pass over the chat's image attachments: allowlist, count,
 * decoded sizes and base64. The images are run context only; the summary it
 * returns is the only form that may be persisted.
 */
public final class AriaChatAttachmentValidator {

    public static final int MAX_ATTACHMENTS = 5;
    public static final int MAX_IMAGE_BYTES = 4 * 1024 * 1024;
    public static final int MAX_TOTAL_BYTES = 10 * 1024 * 1024;
    private static final Set<String> ALLOWED_MIME = Set.of("image/jpeg", "image/png", "image/webp");

    public record Rejection(HttpStatus status, String message) {}
    public record Validated(List<CoreAttachment> parts, String summary) {}
    public record Result(Rejection rejection, Validated validated) {}

    private AriaChatAttachmentValidator() {
    }

    public static Result validate(List<AriaChatRequest.Attachment> attachments) {
        if (attachments == null || attachments.isEmpty()) {
            return new Result(null, new Validated(List.of(), ""));
        }
        if (attachments.size() > MAX_ATTACHMENTS) {
            return reject(HttpStatus.UNPROCESSABLE_ENTITY,
                    "At most " + MAX_ATTACHMENTS + " images per turn");
        }
        List<CoreAttachment> parts = new ArrayList<>();
        List<String> described = new ArrayList<>();
        long total = 0;
        for (AriaChatRequest.Attachment attachment : attachments) {
            if (attachment == null || attachment.getMimeType() == null
                    || !ALLOWED_MIME.contains(attachment.getMimeType())) {
                return reject(HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                        "Attachments must be image/jpeg, image/png or image/webp");
            }
            if (attachment.getDataBase64() == null || attachment.getDataBase64().isBlank()) {
                return reject(HttpStatus.BAD_REQUEST, "attachment.dataBase64 is required");
            }
            byte[] bytes;
            try {
                bytes = Base64.getDecoder().decode(attachment.getDataBase64());
            } catch (IllegalArgumentException e) {
                return reject(HttpStatus.BAD_REQUEST, "attachment.dataBase64 is not valid base64");
            }
            if (bytes.length == 0) {
                return reject(HttpStatus.UNPROCESSABLE_ENTITY, "attachment image is empty");
            }
            if (bytes.length > MAX_IMAGE_BYTES) {
                return reject(HttpStatus.PAYLOAD_TOO_LARGE,
                        "Each image must be at most " + MAX_IMAGE_BYTES + " bytes");
            }
            total += bytes.length;
            if (total > MAX_TOTAL_BYTES) {
                return reject(HttpStatus.PAYLOAD_TOO_LARGE,
                        "Attachments must total at most " + MAX_TOTAL_BYTES + " bytes");
            }
            String name = attachment.getFilename() == null || attachment.getFilename().isBlank()
                    ? "image" : attachment.getFilename();
            parts.add(new CoreAttachment(attachment.getMimeType(), bytes, name));
            described.add(name + " (" + attachment.getMimeType() + ", "
                    + Math.max(1, Math.round(bytes.length / 1024.0)) + " KB)");
        }
        return new Result(null, new Validated(List.copyOf(parts),
                "[attached images: " + String.join(", ", described) + "]"));
    }

    private static Result reject(HttpStatus status, String message) {
        return new Result(new Rejection(status, message), null);
    }
}
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `cd /Users/happy/Documents/project/aria-conductor && bash scripts/mvn-lane.sh inner act-aria -Dtest=AriaChatAttachmentValidatorTest`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add agent-control-tower/act-aria/src/main/java/io/aria/conductor/aria/dto/AriaChatRequest.java \
        agent-control-tower/act-aria/src/main/java/io/aria/conductor/aria/service/AriaChatAttachmentValidator.java \
        agent-control-tower/act-aria/src/test/java/io/aria/conductor/aria/service/AriaChatAttachmentValidatorTest.java
git commit -m "feat(aria): validate chat image attachments into run-context parts"
```

---

### Task 2: Engine sync overload and service wiring

**Files:**
- Modify: `agent-control-tower/act-execution/src/main/java/io/aria/conductor/execution/engine/AgentLoopEngine.java` (after `startRun(UUID, List<LlmMessage>)`)
- Modify: `agent-control-tower/act-aria/src/main/java/io/aria/conductor/aria/service/AriaStreamService.java`
- Modify: `agent-control-tower/act-aria/src/main/java/io/aria/conductor/aria/service/AriaService.java`
- Test: `agent-control-tower/act-aria/src/test/java/io/aria/conductor/aria/service/AriaStreamServiceTest.java`
- Test: `agent-control-tower/act-aria/src/test/java/io/aria/conductor/aria/service/AriaServiceChatTest.java`

**Interfaces:**
- Consumes: `AriaChatAttachmentValidator.Validated`.
- Produces: `AgentLoopEngine.startRun(UUID, List<LlmMessage>, List<CoreAttachment>)`.
- Produces: `AriaStreamService.streamChat(AriaChatRequest, AriaChatAttachmentValidator.Validated, SseEmitter)`; the two-argument form stays and passes an empty validated result.
- Produces: `AriaService.chat(AriaChatRequest, AriaChatAttachmentValidator.Validated)`; the one-argument form stays.

- [ ] **Step 1: Write the failing stream-service test**

Add to `AriaStreamServiceTest` (it already mocks `agentLoopEngine` and captures the saved `Run`):

```java
    @Test
    void aTurnWithImagesCarriesThemAndSummarizesThemInTheSeed() {
        Run saved = Run.builder().id(RUN_ID).status(RunStatus.RUNNING).build();
        when(runRepository.save(any(Run.class))).thenReturn(saved);
        AriaChatRequest request = AriaChatRequest.builder()
                .conversationId("conv-1")
                .message("what is this?")
                .build();
        var validated = AriaChatAttachmentValidator.validate(List.of(
                AriaChatRequest.Attachment.builder()
                        .mimeType("image/jpeg").filename("photo.jpg")
                        .dataBase64(java.util.Base64.getEncoder().encodeToString(new byte[]{1, 2, 3}))
                        .build()));

        streamService.streamChat(request, validated.validated(), emitter);

        ArgumentCaptor<Run> run = ArgumentCaptor.forClass(Run.class);
        verify(runRepository).save(run.capture());
        assertThat(run.getValue().getPromptSeed())
                .startsWith("what is this?")
                .contains("[attached images: photo.jpg (image/jpeg,")
                .doesNotContain(java.util.Base64.getEncoder().encodeToString(new byte[]{1, 2, 3}));
        verify(agentLoopEngine).startRunStream(eq(RUN_ID), eq(emitter), anyList(), eq("general"),
                eq(validated.validated().parts()));
    }
```

- [ ] **Step 2: Write the failing sync-service test**

Add to `AriaServiceChatTest`:

```java
    @Test
    void theSyncPathAppendsTheSummaryAndStartsWithTheParts() {
        Run saved = Run.builder().id(RUN_ID).status(RunStatus.RUNNING).build();
        when(runRepository.save(any(Run.class))).thenReturn(saved);
        AriaChatRequest request = AriaChatRequest.builder().message("describe").build();
        var validated = AriaChatAttachmentValidator.validate(List.of(
                AriaChatRequest.Attachment.builder()
                        .mimeType("image/png").filename("shot.png")
                        .dataBase64(java.util.Base64.getEncoder().encodeToString(new byte[]{9, 9}))
                        .build()));

        ariaService.chat(request, validated.validated());

        ArgumentCaptor<Run> run = ArgumentCaptor.forClass(Run.class);
        verify(runRepository).save(run.capture());
        assertThat(run.getValue().getPromptSeed())
                .startsWith("describe")
                .contains("[attached images: shot.png (image/png,");
        verify(agentLoopEngine).startRun(eq(RUN_ID), eq(List.of()), eq(validated.validated().parts()));
    }
```

- [ ] **Step 3: Run the tests to verify they fail**

Run: `cd /Users/happy/Documents/project/aria-conductor && bash scripts/mvn-lane.sh inner act-aria -Dtest="AriaStreamServiceTest,AriaServiceChatTest"`
Expected: compilation failure (the new overloads do not exist).

- [ ] **Step 4: Add the engine overload**

After the existing `startRun(UUID, List<LlmMessage>)`:

```java
    /**
     * The attachment-carrying form of {@link #startRun(UUID, List)}: binary parts
     * travel with this turn only and reach the core task as-is.
     */
    public void startRun(UUID runId, List<LlmMessage> initialContext, List<CoreAttachment> attachments) {
        startRunInternal(runId, null, initialContext != null ? initialContext : List.of(), null,
                attachments != null ? attachments : List.of());
    }
```

- [ ] **Step 5: Wire the stream service**

In `AriaStreamService`, add the overload and keep the old one:

```java
    public void streamChat(AriaChatRequest request, SseEmitter emitter) {
        streamChat(request, new AriaChatAttachmentValidator.Validated(List.of(), ""), emitter);
    }

    public void streamChat(AriaChatRequest request,
                           AriaChatAttachmentValidator.Validated attachments,
                           SseEmitter emitter) {
```

Inside the new method, use a seed helper for the Run:

```java
            run = Run.builder()
                    .agentId(ARIA_AGENT_ID)
                    .promptSeed(seed(request.getMessage(), attachments.summary()))
                    .maxIterations(maxIterations)
                    .status(RunStatus.PENDING)
                    .conversationId(conversationId)
                    .build();
```

and pass the parts:

```java
            agentLoopEngine.startRunStream(run.getId(), emitter, contextMessages, intent, attachments.parts());
```

Add the helper at the end of the class:

```java
    /** The operator's text plus the attachment summary; the summary is the only persisted trace. */
    private static String seed(String message, String summary) {
        return summary == null || summary.isBlank() ? message : message + "\n\n" + summary;
    }
```

- [ ] **Step 6: Wire the sync service**

In `AriaService`, change `chat(AriaChatRequest)` to delegate and add the overload:

```java
    public AriaChatResponse chat(AriaChatRequest request) {
        return chat(request, new AriaChatAttachmentValidator.Validated(List.of(), ""));
    }

    public AriaChatResponse chat(AriaChatRequest request, AriaChatAttachmentValidator.Validated attachments) {
```

Use `promptSeed(AriaService.seed(...))` for the Run and `agentLoopEngine.startRun(run.getId(), List.of(), attachments.parts());`. Add the same private `seed` helper shape (duplicated 3 lines is fine).

- [ ] **Step 7: Update the existing service verifications to the new signatures**

`AriaStreamServiceTest` currently verifies the four-argument `startRunStream` in four places (lines with `startRunStream(eq(RUN_ID), eq(emitter), anyList(), eq("general"))`, `never()`, `when(...)`, and the captor form). Change each to the five-argument form with the parts list:

```java
        verify(agentLoopEngine).startRunStream(eq(RUN_ID), eq(emitter), anyList(), eq("general"), eq(List.of()));
        verify(agentLoopEngine, never()).startRunStream(any(), any(), anyList(), anyString(), anyList());
        org.mockito.Mockito.lenient().when(agentLoopEngine)
                .startRunStream(any(), any(), anyList(), anyString(), anyList());
        verify(agentLoopEngine).startRunStream(eq(RUN_ID), eq(emitter), captor.capture(), anyString(), eq(List.of()));
```

`AriaServiceChatTest` verifies the two-argument `startRun` at `:211` and `:223`; change both to the three-argument form, for example:

```java
        verify(agentLoopEngine).startRun(eq(RUN_ID), anyList(), eq(List.of()));
        verify(agentLoopEngine).startRun(RUN_ID, List.of(), List.of());
```

- [ ] **Step 8: Run the tests to verify they pass**

Run: `cd /Users/happy/Documents/project/aria-conductor && bash scripts/mvn-lane.sh inner act-aria -Dtest="AriaStreamServiceTest,AriaServiceChatTest,AriaStreamServiceFailureTest"`
Expected: PASS (the pre-existing tests use the two-argument/one-argument forms and the updated verifications).

- [ ] **Step 9: Commit**

```bash
git add agent-control-tower/act-execution/src/main/java/io/aria/conductor/execution/engine/AgentLoopEngine.java \
        agent-control-tower/act-aria/src/main/java/io/aria/conductor/aria/service/AriaStreamService.java \
        agent-control-tower/act-aria/src/main/java/io/aria/conductor/aria/service/AriaService.java \
        agent-control-tower/act-aria/src/test/java/io/aria/conductor/aria/service/AriaStreamServiceTest.java \
        agent-control-tower/act-aria/src/test/java/io/aria/conductor/aria/service/AriaServiceChatTest.java
git commit -m "feat(aria): carry chat attachments into the run and summarize them in the seed"
```

---

### Task 3: Chat controller HTTP surface

**Files:**
- Modify: `agent-control-tower/act-aria/src/main/java/io/aria/conductor/aria/controller/AriaChatController.java`
- Test: `agent-control-tower/act-aria/src/test/java/io/aria/conductor/aria/controller/AriaChatControllerTest.java`

**Interfaces:**
- Consumes: `AriaChatAttachmentValidator`, the service overloads from Task 2.
- Produces: `POST /api/v1/aria/chat/stream` returning `ResponseEntity<SseEmitter>` (JSON rejections before the emitter); `POST /api/v1/aria/chat` returning `ResponseEntity<AriaChatResponse>` with the same rejections.

- [ ] **Step 1: Write the failing controller tests**

Add to `AriaChatControllerTest` (standalone MockMvc already builds the controller):

```java
    @Test
    void aStreamWithAnImageIsAcceptedAndPassesTheParts() throws Exception {
        String base64 = java.util.Base64.getEncoder().encodeToString(new byte[]{1, 2, 3});
        when(systemConfigService.getLong(eq("aria.sse.timeout.ms"), anyLong(), anyLong(), anyLong()))
                .thenReturn(600_000L);

        MvcResult result = mockMvc.perform(post("/api/v1/aria/chat/stream")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.TEXT_EVENT_STREAM)
                        .content("{\"conversationId\":\"conv-1\",\"message\":\"see this\","
                                + "\"attachments\":[{\"mimeType\":\"image/jpeg\",\"filename\":\"p.jpg\","
                                + "\"dataBase64\":\"" + base64 + "\"}]}"))
                .andExpect(status().isOk())
                .andReturn();

        ArgumentCaptor<AriaChatAttachmentValidator.Validated> validated =
                ArgumentCaptor.forClass(AriaChatAttachmentValidator.Validated.class);
        verify(ariaStreamService).streamChat(any(AriaChatRequest.class), validated.capture(), any(SseEmitter.class));
        assertThat(validated.getValue().parts()).hasSize(1);
        result.getRequest().getAsyncContext().complete();
    }

    @Test
    void anUnsupportedAttachmentMimeIs415Json() throws Exception {
        mockMvc.perform(post("/api/v1/aria/chat/stream")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.TEXT_EVENT_STREAM)
                        .content("{\"message\":\"x\",\"attachments\":[{\"mimeType\":\"application/pdf\","
                                + "\"filename\":\"d.pdf\",\"dataBase64\":\"QQ==\"}]}"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.error").exists());
        verifyNoInteractions(ariaStreamService);
    }

    @Test
    void sixAttachmentsAre422Json() throws Exception {
        StringBuilder attachments = new StringBuilder("[");
        for (int i = 0; i < 6; i++) {
            if (i > 0) attachments.append(',');
            attachments.append("{\"mimeType\":\"image/jpeg\",\"filename\":\"x").append(i)
                    .append(".jpg\",\"dataBase64\":\"QQ==\"}");
        }
        attachments.append(']');

        mockMvc.perform(post("/api/v1/aria/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"x\",\"attachments\":" + attachments + "}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").exists());
        verifyNoInteractions(ariaService);
    }
```

Imports to add in the test: `MvcResult`, `anyLong`, `ArgumentCaptor`, `verifyNoInteractions`, `content`, plus the validator type.

- [ ] **Step 2: Run the tests to verify they fail**

Run: `cd /Users/happy/Documents/project/aria-conductor && bash scripts/mvn-lane.sh inner act-aria -Dtest=AriaChatControllerTest`
Expected: FAIL (the controller validates nothing and the stream method is not a `ResponseEntity`).

- [ ] **Step 3: Rewrite the controller**

```java
@Validated
@RestController
@RequestMapping("/api/v1/aria")
public class AriaChatController {

    private final AriaService ariaService;
    private final AriaStreamService ariaStreamService;
    private final SystemConfigService systemConfigService;

    public AriaChatController(AriaService ariaService, AriaStreamService ariaStreamService,
                              SystemConfigService systemConfigService) {
        this.ariaService = ariaService;
        this.ariaStreamService = ariaStreamService;
        this.systemConfigService = systemConfigService;
    }

    @PostMapping("/chat")
    public ResponseEntity<AriaChatResponse> chat(@Valid @RequestBody AriaChatRequest request) {
        return ResponseEntity.ok(ariaService.chat(request, validated(request)));
    }

    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<SseEmitter> chatStream(@Valid @RequestBody AriaChatRequest request) {
        AriaChatAttachmentValidator.Validated attachments = validated(request);
        long sseTimeoutMs = systemConfigService.getLong("aria.sse.timeout.ms", 600_000L, 30_000L, 3_600_000L);
        SseEmitter emitter = new SseEmitter(sseTimeoutMs);
        ariaStreamService.streamChat(request, attachments, emitter);
        return ResponseEntity.ok().contentType(MediaType.TEXT_EVENT_STREAM).body(emitter);
    }

    private static AriaChatAttachmentValidator.Validated validated(AriaChatRequest request) {
        AriaChatAttachmentValidator.Result result =
                AriaChatAttachmentValidator.validate(request.getAttachments());
        if (result.rejection() != null) {
            throw new AttachmentRejection(result.rejection());
        }
        return result.validated();
    }

    /** A pre-stream attachment refusal; answered as JSON, never as an SSE frame. */
    static final class AttachmentRejection extends RuntimeException {
        private final AriaChatAttachmentValidator.Rejection rejection;

        AttachmentRejection(AriaChatAttachmentValidator.Rejection rejection) {
            super(rejection.message());
            this.rejection = rejection;
        }

        AriaChatAttachmentValidator.Rejection rejection() {
            return rejection;
        }
    }

    @ExceptionHandler(AttachmentRejection.class)
    public ResponseEntity<Map<String, String>> rejected(AttachmentRejection rejection) {
        return ResponseEntity.status(rejection.rejection().status())
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("error", rejection.rejection().message()));
    }
}
```

Keep the class javadoc that explains the SSE contract and add a sentence about the JSON rejections.

- [ ] **Step 4: Update the existing controller test stubs and verifications**

`AriaChatControllerTest` stubs `ariaService.chat(any(AriaChatRequest.class))` and verifies `ariaStreamService.streamChat(requestCaptor.capture(), emitterCaptor.capture())` (and the `never()` form). Update each to the new signatures:

```java
        when(ariaService.chat(any(AriaChatRequest.class), any(AriaChatAttachmentValidator.Validated.class)))
                .thenReturn(response);
        verify(ariaStreamService).streamChat(requestCaptor.capture(),
                any(AriaChatAttachmentValidator.Validated.class), emitterCaptor.capture());
        verify(ariaStreamService, never()).streamChat(any(), any(), any());
```

Keep each test's existing assertions; only the call shapes change.

- [ ] **Step 5: Run the tests to verify they pass**

Run: `cd /Users/happy/Documents/project/aria-conductor && bash scripts/mvn-lane.sh inner act-aria -Dtest=AriaChatControllerTest`
Expected: PASS (existing cases plus the three new ones).

- [ ] **Step 6: Commit**

```bash
git add agent-control-tower/act-aria/src/main/java/io/aria/conductor/aria/controller/AriaChatController.java \
        agent-control-tower/act-aria/src/test/java/io/aria/conductor/aria/controller/AriaChatControllerTest.java
git commit -m "feat(aria): accept chat image attachments with JSON pre-stream refusals"
```

---

### Task 4: Frontend API payload and pre-stream error contract

**Files:**
- Modify: `agent-control-tower/act-dashboard/src/api/aria.ts`
- Test: `agent-control-tower/act-dashboard/src/api/__tests__/aria.test.ts`

**Interfaces:**
- Produces: `AriaAttachmentPayload {mimeType, filename, dataBase64, width?, height?}`.
- Produces: `StreamCallbacks.onRejected?: (status: number, message: string) => void`.
- Produces: `streamMessage(..., options?: {isCancelled?, skillId?, attachments?: AriaAttachmentPayload[]})`; `sendMessage(..., attachments?: AriaAttachmentPayload[])`.

- [ ] **Step 1: Write the failing API tests**

Add to `aria.test.ts`:

```ts
  it('sends attachments in the request body', async () => {
    fetchMock.mockResolvedValue(sseResponse([
      'event: done\ndata: {"runId":"r1","conversationId":"c1","intent":"chat"}\n\n',
    ]));

    await streamMessage('c1', 'hi', [], {}, undefined, {
      attachments: [{ mimeType: 'image/jpeg', filename: 'p.jpg', dataBase64: 'AAEC' }],
    });

    const body = JSON.parse(String((fetchMock.mock.calls[0][1] as RequestInit).body));
    expect(body.attachments).toEqual([
      { mimeType: 'image/jpeg', filename: 'p.jpg', dataBase64: 'AAEC' },
    ]);
  });

  it('reports a pre-stream rejection as status plus the JSON error', async () => {
    fetchMock.mockResolvedValue({
      ok: false,
      status: 415,
      statusText: 'Unsupported Media Type',
      text: async () => '{"error":"Attachments must be image/jpeg, image/png or image/webp"}',
    });
    const onRejected = vi.fn();
    const onError = vi.fn();

    await streamMessage('c1', 'hi', [], { onRejected, onError });

    expect(onRejected).toHaveBeenCalledWith(415, 'Attachments must be image/jpeg, image/png or image/webp');
    expect(onError).not.toHaveBeenCalled();
  });
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `cd /Users/happy/Documents/project/aria-conductor/agent-control-tower/act-dashboard && pnpm test -- aria.test.ts`
Expected: FAIL (unknown options/callbacks, attachments not sent).

- [ ] **Step 3: Extend the API client**

Add the payload type, the two callbacks and the options member, then:

```ts
  const body: Record<string, unknown> = {
    conversationId,
    message,
    history,
    ...(options?.skillId ? { skillId: options.skillId } : {}),
    ...(options?.attachments && options.attachments.length > 0
      ? { attachments: options.attachments }
      : {}),
  };
```

Before the existing `if (!response.ok || !response.body)`:

```ts
  if (!response.ok) {
    let message = response.statusText || `HTTP ${response.status}`;
    try {
      const text = await response.text();
      const parsed = JSON.parse(text) as { error?: string; message?: string };
      message = parsed.error || parsed.message || message;
    } catch {
      // non-JSON body: keep the status text
    }
    if (callbacks.onRejected) callbacks.onRejected(response.status, message);
    else callbacks.onError?.(`Stream request failed: ${message}`);
    return;
  }
  if (!response.body) {
    callbacks.onError?.('Stream request failed: empty response body');
    return;
  }
```

Add `attachments` to `sendMessage` as an optional last parameter and include it in the axios body when non-empty.

- [ ] **Step 4: Run the tests to verify they pass**

Run: `cd /Users/happy/Documents/project/aria-conductor/agent-control-tower/act-dashboard && pnpm test -- aria.test.ts aria.sync.test.ts`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add agent-control-tower/act-dashboard/src/api/aria.ts agent-control-tower/act-dashboard/src/api/__tests__/aria.test.ts
git commit -m "feat(dashboard): send chat attachments and surface pre-stream refusals"
```

---

### Task 5: Panel state for attachments and failed sends

**Files:**
- Modify: `agent-control-tower/act-dashboard/src/components/ariaPanelState.ts`
- Test: `agent-control-tower/act-dashboard/src/components/__tests__/ariaPanelState.test.ts`

**Interfaces:**
- Produces: `AriaMessageAttachment {filename, mimeType, previewUrl}`.
- Produces: the user `PanelItem` gains `attachments?: AriaMessageAttachment[]`; `userSent` accepts `attachments?`.
- Produces: `userSendFailed { id }` removing that user item.

- [ ] **Step 1: Write the failing reducer tests**

Add to `ariaPanelState.test.ts`:

```ts
  it('keeps the sent attachments on the user item', () => {
    const next = panelReducer(base, {
      type: 'userSent',
      id: 'u1',
      content: 'look',
      timestamp: '2026-11-22T10:00:00.000Z',
      attachments: [{ filename: 'p.jpg', mimeType: 'image/jpeg', previewUrl: 'blob:1' }],
    });

    const item = next.items.find((entry) => entry.id === 'u1');
    expect(item?.kind).toBe('user');
    if (item?.kind === 'user') {
      expect(item.attachments).toEqual([{ filename: 'p.jpg', mimeType: 'image/jpeg', previewUrl: 'blob:1' }]);
    }
  });

  it('removes a rejected optimistic user item and keeps the rest', () => {
    const sent = panelReducer(base, {
      type: 'userSent', id: 'u1', content: 'look', timestamp: '2026-11-22T10:00:00.000Z',
    });
    const withAssistant = panelReducer(sent, {
      type: 'assistantMessage', id: 'a1', content: 'ok', timestamp: '2026-11-22T10:00:01.000Z',
    });

    const next = panelReducer(withAssistant, { type: 'userSendFailed', id: 'u1' });

    expect(next.items.map((entry) => entry.id)).toEqual(['a1']);
  });
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `cd /Users/happy/Documents/project/aria-conductor/agent-control-tower/act-dashboard && pnpm test -- ariaPanelState.test.ts`
Expected: FAIL (unknown event/field).

- [ ] **Step 3: Extend the state**

- Add `export type AriaMessageAttachment = { filename: string; mimeType: string; previewUrl: string };`.
- User item: `{ kind: 'user'; id; content; timestamp; attachments?: AriaMessageAttachment[] }`.
- `userSent` event gains `attachments?: AriaMessageAttachment[]`; the reducer passes them into `appendMessage`.
- Add the event `{ type: 'userSendFailed'; id: string }` and a case that returns `{ ...state, items: state.items.filter((item) => item.id !== event.id) }`.

- [ ] **Step 4: Run the tests to verify they pass**

Run: `cd /Users/happy/Documents/project/aria-conductor/agent-control-tower/act-dashboard && pnpm test -- ariaPanelState.test.ts`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add agent-control-tower/act-dashboard/src/components/ariaPanelState.ts \
        agent-control-tower/act-dashboard/src/components/__tests__/ariaPanelState.test.ts
git commit -m "feat(dashboard): keep attachment previews on the user turn and roll back rejected sends"
```

---

### Task 6: Attachment helper (validation, downscale, payload)

**Files:**
- Create: `agent-control-tower/act-dashboard/src/components/ariaAttachments.ts`
- Test: `agent-control-tower/act-dashboard/src/components/__tests__/ariaAttachments.test.ts`

**Interfaces:**
- Produces: `MAX_ATTACHMENTS = 5`, `MAX_IMAGE_BYTES = 4 * 1024 * 1024`, `MAX_TOTAL_BYTES = 10 * 1024 * 1024`, `ALLOWED_MIME`.
- Produces: `PendingImage {id, filename, mimeType, dataBase64, width, height, previewUrl, bytes}`.
- Produces: `rejectionFor(files: File[]): string | null` (type/count on raw files).
- Produces: `prepareImage(file: File): Promise<PendingImage>` (reads, downscales over 2048 px or over 1.5 MB, encodes base64).
- Produces: `toPayload(images: PendingImage[]): AriaAttachmentPayload[]`.

- [ ] **Step 1: Write the failing helper test**

```ts
import { describe, expect, it } from 'vitest';
import { ALLOWED_MIME, MAX_ATTACHMENTS, rejectionFor } from '../ariaAttachments';

function file(name: string, type: string, size = 1024): File {
  return new File([new Uint8Array(size)], name, { type });
}

describe('ariaAttachments', () => {
  it('accepts the three image types within the limits', () => {
    expect(ALLOWED_MIME).toEqual(['image/jpeg', 'image/png', 'image/webp']);
    expect(rejectionFor([file('a.jpg', 'image/jpeg'), file('b.png', 'image/png')])).toBeNull();
  });

  it('rejects documents with the phase-1 copy', () => {
    expect(rejectionFor([file('d.pdf', 'application/pdf')]))
      .toBe('Phase 1 supports images only (JPEG, PNG, WebP).');
  });

  it('rejects more than five images', () => {
    const files = Array.from({ length: MAX_ATTACHMENTS + 1 }, (_, i) => file(`i${i}.jpg`, 'image/jpeg'));
    expect(rejectionFor(files)).toBe('At most 5 images per turn.');
  });

  it('rejects an image over four mebibytes before downscaling', () => {
    expect(rejectionFor([file('big.jpg', 'image/jpeg', 4 * 1024 * 1024 + 1)]))
      .toBe('Each image must be at most 4 MB.');
  });
});
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd /Users/happy/Documents/project/aria-conductor/agent-control-tower/act-dashboard && pnpm test -- ariaAttachments.test.ts`
Expected: FAIL (module missing).

- [ ] **Step 3: Create the helper**

```ts
import type { AriaAttachmentPayload } from '../api/aria';

export const ALLOWED_MIME = ['image/jpeg', 'image/png', 'image/webp'];
export const MAX_ATTACHMENTS = 5;
export const MAX_IMAGE_BYTES = 4 * 1024 * 1024;
export const MAX_TOTAL_BYTES = 10 * 1024 * 1024;
const DOWNSCALE_EDGE = 2048;
const DOWNSCALE_BYTES = 1.5 * 1024 * 1024;

export interface PendingImage {
  id: string;
  filename: string;
  mimeType: string;
  dataBase64: string;
  width: number;
  height: number;
  previewUrl: string;
  bytes: number;
}

export function rejectionFor(files: File[]): string | null {
  if (files.length > MAX_ATTACHMENTS) return `At most ${MAX_ATTACHMENTS} images per turn.`;
  for (const file of files) {
    if (!ALLOWED_MIME.includes(file.type)) {
      return 'Phase 1 supports images only (JPEG, PNG, WebP).';
    }
    if (file.size > MAX_IMAGE_BYTES) return 'Each image must be at most 4 MB.';
  }
  return null;
}

function readBase64(blob: Blob): Promise<string> {
  return new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onload = () => {
      const value = String(reader.result ?? '');
      resolve(value.slice(value.indexOf(',') + 1));
    };
    reader.onerror = () => reject(reader.error ?? new Error('image read failed'));
    reader.readAsDataURL(blob);
  });
}

async function loadBitmap(file: File): Promise<{ source: CanvasImageSource; width: number; height: number }> {
  const bitmap = await createImageBitmap(file);
  return { source: bitmap, width: bitmap.width, height: bitmap.height };
}

export async function prepareImage(file: File): Promise<PendingImage> {
  const image = await loadBitmap(file);
  const needsDownscale =
    Math.max(image.width, image.height) > DOWNSCALE_EDGE || file.size > DOWNSCALE_BYTES;
  let blob: Blob = file;
  let width = image.width;
  let height = image.height;
  let mimeType = file.type;
  if (needsDownscale) {
    const scale = DOWNSCALE_EDGE / Math.max(image.width, image.height);
    width = Math.round(image.width * scale);
    height = Math.round(image.height * scale);
    const canvas = document.createElement('canvas');
    canvas.width = width;
    canvas.height = height;
    const context = canvas.getContext('2d');
    if (!context) throw new Error('canvas unavailable');
    context.drawImage(image.source, 0, 0, width, height);
    blob = await new Promise<Blob>((resolve, reject) =>
      canvas.toBlob((value) => (value ? resolve(value) : reject(new Error('encode failed'))),
        'image/jpeg', 0.85));
    mimeType = 'image/jpeg';
  }
  if ('close' in image.source && typeof image.source.close === 'function') image.source.close();
  return {
    id: crypto.randomUUID(),
    filename: needsDownscale ? file.name.replace(/\.[^.]+$/, '') + '.jpg' : file.name,
    mimeType,
    dataBase64: await readBase64(blob),
    width,
    height,
    previewUrl: URL.createObjectURL(blob),
    bytes: blob.size,
  };
}

export function toPayload(images: PendingImage[]): AriaAttachmentPayload[] {
  return images.map((image) => ({
    mimeType: image.mimeType,
    filename: image.filename,
    dataBase64: image.dataBase64,
    width: image.width,
    height: image.height,
  }));
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `cd /Users/happy/Documents/project/aria-conductor/agent-control-tower/act-dashboard && pnpm test -- ariaAttachments.test.ts`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add agent-control-tower/act-dashboard/src/components/ariaAttachments.ts \
        agent-control-tower/act-dashboard/src/components/__tests__/ariaAttachments.test.ts
git commit -m "feat(dashboard): prepare chat images with the phase-1 limits"
```

---

### Task 7: Composer UI, thumbnails and send/retry wiring

**Files:**
- Modify: `agent-control-tower/act-dashboard/src/components/AriaPanel.tsx`
- Modify: `agent-control-tower/act-dashboard/src/styles/index.css`
- Test: `agent-control-tower/act-dashboard/src/components/__tests__/AriaPanel.test.tsx`

**Interfaces:**
- Consumes: `ariaAttachments`, `AriaAttachmentPayload`, the stream callbacks from Task 4, the state from Task 5.

- [ ] **Step 1: Write the failing panel tests**

In `AriaPanel.test.tsx`, add a mock of the helper (jsdom has no canvas/bitmap) and tests:

```tsx
vi.mock('../ariaAttachments', () => ({
  ALLOWED_MIME: ['image/jpeg', 'image/png', 'image/webp'],
  MAX_ATTACHMENTS: 5,
  MAX_IMAGE_BYTES: 4 * 1024 * 1024,
  MAX_TOTAL_BYTES: 10 * 1024 * 1024,
  rejectionFor: () => null,
  prepareImage: vi.fn(async (file: File) => ({
    id: 'img-1', filename: file.name, mimeType: file.type, dataBase64: 'AAEC',
    width: 640, height: 480, previewUrl: 'blob:img-1', bytes: 3,
  })),
  toPayload: (images: Array<{ mimeType: string; filename: string; dataBase64: string; width: number; height: number }>) =>
    images.map(({ mimeType, filename, dataBase64, width, height }) => ({ mimeType, filename, dataBase64, width, height })),
}));
```

```tsx
  it('sends picked images with the turn and shows a thumbnail in the bubble', async () => {
    const user = userEvent.setup();
    renderPanel();
    const input = await screen.findByPlaceholderText(/Ask Aria anything/);
    fireEvent.change(input, { target: { value: 'what is this?' } });

    const fileInput = document.querySelector<HTMLInputElement>('input[type="file"]');
    expect(fileInput).not.toBeNull();
    await user.upload(fileInput as HTMLInputElement, new File([new Uint8Array([1, 2, 3])], 'p.png', { type: 'image/png' }));
    await screen.findByText('p.png');

    const { streamMessage } = await import('../../api/aria');
    (streamMessage as Mock).mockImplementation(async (_c, _m, _h, callbacks) => {
      callbacks.onDone?.({ runId: 'r1', conversationId: 'c1', intent: 'chat' });
    });
    fireEvent.click(screen.getByRole('button', { name: 'Send message' }));

    await waitFor(() => {
      expect(streamMessage).toHaveBeenCalledWith(
        expect.any(String), 'what is this?', expect.any(Array), expect.any(Object), expect.anything(),
        expect.objectContaining({ attachments: [expect.objectContaining({ filename: 'p.png' })] }),
      );
    });
    expect(await screen.findByAltText('p.png')).toBeInTheDocument();
  });

  it('keeps the draft and shows the reason when the server refuses an attachment', async () => {
    renderPanel();
    const input = await screen.findByPlaceholderText(/Ask Aria anything/);
    fireEvent.change(input, { target: { value: 'see this' } });
    const { streamMessage } = await import('../../api/aria');
    (streamMessage as Mock).mockImplementation(async (_c, _m, _h, callbacks) => {
      callbacks.onRejected?.(415, 'Attachments must be image/jpeg, image/png or image/webp');
    });

    fireEvent.click(screen.getByRole('button', { name: 'Send message' }));

    await screen.findByText('Attachments must be image/jpeg, image/png or image/webp');
    expect((input as HTMLTextAreaElement).value).toBe('see this');
  });
```

Follow the existing helpers/mocks in the file (`renderPanel`, the streamMessage mock setup) rather than inventing new ones; the send button's accessible name is `Send message`.

- [ ] **Step 2: Run the tests to verify they fail**

Run: `cd /Users/happy/Documents/project/aria-conductor/agent-control-tower/act-dashboard && pnpm test -- AriaPanel.test.tsx`
Expected: FAIL (no file input, no attachments in the call).

- [ ] **Step 3: Wire the panel**

In `AriaPanel.tsx`:

1. Imports and state:

```tsx
import { prepareImage, rejectionFor, toPayload, type PendingImage } from './ariaAttachments';
```

```tsx
  const [pendingImages, setPendingImages] = useState<PendingImage[]>([]);
  const [attachmentError, setAttachmentError] = useState<string | null>(null);
  const fileInputRef = useRef<HTMLInputElement>(null);
  const lastAttachmentsRef = useRef<PendingImage[]>([]);
```

2. Add files (paste, drop, picker):

```tsx
  const addFiles = useCallback(async (files: FileList | File[]) => {
    const list = Array.from(files);
    const rejection = rejectionFor(list);
    if (rejection) {
      setAttachmentError(rejection);
      return;
    }
    try {
      const prepared = await Promise.all(list.map(prepareImage));
      setPendingImages((current) => {
        const next = [...current, ...prepared].slice(0, 5);
        return next;
      });
      setAttachmentError(null);
    } catch {
      setAttachmentError('That image could not be read. Try another file.');
    }
  }, []);

  const removeImage = useCallback((id: string) => {
    setPendingImages((current) => {
      const target = current.find((image) => image.id === id);
      if (target) URL.revokeObjectURL(target.previewUrl);
      return current.filter((image) => image.id !== id);
    });
  }, []);
```

3. `sendStreamed` signature and rollback. Change the signature to:

```tsx
    (rawText: string, options?: { skillId?: string; attachments?: PendingImage[] }) => {
      const text = rawText.trim();
      const images = options?.attachments ?? [];
      if (!text || busy || gated || clearingRef.current) return;
      const skillId = options?.skillId ?? pendingSkillId;
```

Keep the existing optimistic dispatch and clear, but capture the draft and the images so a pre-stream refusal can put them back. Replace the existing `dispatch({ type: 'userSent', ... })` and `setInput('')` lines with:

```tsx
      const userId = crypto.randomUUID();
      const attachmentPreviews = images.map((image) => ({
        filename: image.filename, mimeType: image.mimeType, previewUrl: image.previewUrl,
      }));
      dispatch({
        type: 'userSent',
        id: userId,
        content: text,
        timestamp: new Date().toISOString(),
        attachments: attachmentPreviews,
      });
      setInput('');
      setPendingImages([]);
      lastAttachmentsRef.current = images;
```

Then add the refusal callback to the `streamMessage` callbacks object:

```tsx
          onRejected: (status, message) => {
            if (staleTurn()) return;
            // The optimistic bubble announced a message the server refused: take it
            // back and restore the draft and images so the operator can fix them.
            dispatch({ type: 'userSendFailed', id: userId });
            setInput(text);
            setPendingImages(images);
            setAttachmentError(message);
            setBusy(false);
          },
```

Keep every existing callback. In the stream `onError` (transport/turn failures after acceptance) the existing handling stays; the optimistic message stays because the server did accept it.

The streamMessage call gains the attachments option:

```tsx
        ctrl.signal,
        { isCancelled: () => cancelledRef.current, skillId, attachments: toPayload(images) },
```

Remove the earlier `setLastSentMessage`, `lastSentSkillIdRef`, `slash.clearSkill` ordering only if they were before the stream; keep them.

4. Composer JSX: add the hidden file input, the paperclip button, the chips and error, and the paste/drop handlers:

```tsx
          <div
            className="ai-compose"
            onDragOver={(event) => event.preventDefault()}
            onDrop={(event) => {
              event.preventDefault();
              if (event.dataTransfer.files.length > 0) void addFiles(event.dataTransfer.files);
            }}
          >
            {pendingImages.length > 0 && (
              <div className="ai-compose-attachments">
                {pendingImages.map((image) => (
                  <span key={image.id} className="ai-attachment-chip">
                    <img src={image.previewUrl} alt={image.filename} className="ai-attachment-thumb" />
                    <span className="ai-attachment-name">{image.filename}</span>
                    <button type="button" onClick={() => removeImage(image.id)} aria-label={`Remove ${image.filename}`}>✕</button>
                  </span>
                ))}
              </div>
            )}
            {attachmentError && <div className="ai-attachment-error" role="alert">{attachmentError}</div>}
            <input
              ref={fileInputRef}
              type="file"
              accept="image/jpeg,image/png,image/webp"
              multiple
              className="ai-attach-input"
              data-testid="aria-attachment-input"
              onChange={(event) => {
                if (event.target.files) void addFiles(event.target.files);
                event.target.value = '';
              }}
            />
```

Add the paperclip button next to the send/cancel button:

```tsx
              <button
                type="button"
                className="ai-attach-btn"
                onClick={() => fileInputRef.current?.click()}
                disabled={busy || gated || clearing}
                aria-label="Attach images"
              >
                <span aria-hidden="true">📎</span>
              </button>
```

Add a paste handler on the textarea:

```tsx
              onPaste={(event) => {
                const images = Array.from(event.clipboardData.files).filter((file) => file.type.startsWith('image/'));
                if (images.length > 0) {
                  event.preventDefault();
                  void addFiles(images);
                }
              }}
```

5. Handle send and retry:

```tsx
  const handleSend = useCallback(() => {
    sendStreamed(input, { attachments: pendingImages });
  }, [input, pendingImages, sendStreamed]);
```

Retry:

```tsx
  const handleRetry = useCallback(() => {
    if (busy || gated || clearingRef.current) return;
    const retryText = (tail?.kind === 'assistant' ? tail.retryText : undefined) ?? lastSentMessage;
    if (!retryText) return;
    sendStreamed(retryText, { skillId: lastSentSkillIdRef.current, attachments: lastAttachmentsRef.current });
  }, [busy, gated, tail, lastSentMessage, sendStreamed]);
```

6. Render the sent attachments in the user bubble, above the text:

```tsx
                      {item.kind === 'user' && item.attachments && item.attachments.length > 0 && (
                        <div className="ai-msg-attachments">
                          {item.attachments.map((attachment) => (
                            <img
                              key={attachment.previewUrl}
                              src={attachment.previewUrl}
                              alt={attachment.filename}
                              className="ai-msg-attachment-thumb"
                            />
                          ))}
                        </div>
                      )}
```

7. Revoke object URLs when the panel unmounts (in the existing cleanup effect or a new one):

```tsx
  useEffect(() => () => {
    pendingImages.forEach((image) => URL.revokeObjectURL(image.previewUrl));
  }, [pendingImages]);
```

Adjust that effect so it does not revoke URLs still referenced by sent bubbles: revoke only on removal and on final unmount with a ref of outstanding URLs. Simpler and correct: track created URLs in a ref set, remove on `removeImage`, and revoke the set on unmount. Use that in the implementation.

8. CSS in `src/styles/index.css` next to `.ai-compose`:

```css
.ai-compose-attachments { display: flex; flex-wrap: wrap; gap: 6px; margin-bottom: 8px; }
.ai-attachment-chip { display: inline-flex; align-items: center; gap: 6px; padding: 3px 6px;
  border: 1px solid var(--line-2); border-radius: 8px; background: rgba(0,0,0,.20); font-size: 11px; }
body.light .ai-attachment-chip { background: #f6f6f9; }
.ai-attachment-thumb { width: 28px; height: 28px; object-fit: cover; border-radius: 5px; }
.ai-attachment-chip button { background: none; border: 0; color: var(--text-mute); cursor: pointer; }
.ai-attachment-error { color: #ff8a8a; font-size: 11px; margin-bottom: 6px; }
.ai-attach-input { display: none; }
.ai-attach-btn { background: none; border: 0; color: var(--text-mute); font-size: 16px; cursor: pointer; padding: 4px; }
.ai-msg-attachments { display: flex; flex-wrap: wrap; gap: 6px; margin-bottom: 6px; }
.ai-msg-attachment-thumb { max-width: 160px; max-height: 120px; border-radius: 8px; border: 1px solid var(--line-2); }
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `cd /Users/happy/Documents/project/aria-conductor/agent-control-tower/act-dashboard && pnpm test -- AriaPanel.test.tsx`
Expected: PASS (existing tests plus the two new ones).

- [ ] **Step 5: Commit**

```bash
git add agent-control-tower/act-dashboard/src/components/AriaPanel.tsx \
        agent-control-tower/act-dashboard/src/styles/index.css \
        agent-control-tower/act-dashboard/src/components/__tests__/AriaPanel.test.tsx
git commit -m "feat(dashboard): attach, preview and send images in the Aria composer"
```

---

### Task 8: Playwright E2E and gates

**Files:**
- Create: `agent-control-tower/act-dashboard/e2e/aria-attachments.spec.ts`

**Interfaces:**
- Consumes: the composer input `data-testid="aria-attachment-input"`, the chip/bubble classes from Task 7.

- [ ] **Step 1: Write the E2E spec**

Follow `aria-inflow-decisions.spec.ts` for the page/panel setup (the `openAria` helper, the base fixture). The test attaches an in-memory PNG; no fixture file is needed:

```ts
import { test, expect, type Page } from '@playwright/test';

const PNG = Buffer.from(
  'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==',
  'base64',
);

async function openAria(page: Page): Promise<void> {
  const fab = page.locator('button.ai-fab');
  await fab.waitFor({ state: 'visible', timeout: 15_000 });
  if (!(await page.locator('.ai-panel').isVisible().catch(() => false))) {
    await fab.click();
  }
  await expect(page.locator('.ai-panel')).toBeVisible({ timeout: 10_000 });
}

test('an attached image is shown as a thumbnail and sent with the turn', async ({ page }) => {
  await page.goto('/');
  await openAria(page);

  await page.setInputFiles('[data-testid="aria-attachment-input"]', {
    name: 'probe.png',
    mimeType: 'image/png',
    buffer: PNG,
  });
  await expect(page.locator('.ai-panel .ai-attachment-chip')).toHaveCount(1);

  const textarea = page.locator('.ai-panel .ai-compose-input');
  await textarea.fill('Describe the attached image');
  await textarea.press('Enter');

  await expect(page.locator('.ai-panel .ai-msg-user .ai-msg-attachments img')).toHaveCount(1, {
    timeout: 10_000,
  });
  await expect(page.locator('.ai-panel .ai-compose-input')).toBeEnabled({ timeout: 180_000 });
});
```

- [ ] **Step 2: Run the E2E**

Run: `cd /Users/happy/Documents/project/aria-conductor/agent-control-tower/act-dashboard && npx playwright test e2e/aria-attachments.spec.ts`
Expected: PASS against the harness stack. If the environment is not running, start it with the repository's documented harness flow and re-run.

- [ ] **Step 3: Run the gates**

```bash
cd /Users/happy/Documents/project/aria-conductor
bash scripts/mvn-lane.sh gate act-aria
bash scripts/mvn-lane.sh gate act-execution
cd agent-control-tower/act-dashboard
pnpm build
pnpm test
```

Expected: all green, coverage met.

- [ ] **Step 4: Commit**

```bash
git add agent-control-tower/act-dashboard/e2e/aria-attachments.spec.ts
git commit -m "test(e2e): prove an attached image reaches the chat turn"
```

---

## Self-Review

Spec coverage:

| Spec section | Task |
|---|---|
| Images only, limits, statuses | 1 |
| Seed summary, no base64 persistence | 1, 2 |
| Stream + sync parity, run/core parts | 2, 3 |
| JSON pre-stream refusals | 3 |
| Client payload, pre-stream error, acceptance | 4 |
| Attachments on the user turn, rollback | 5 |
| Downscale/validate/payload helper | 6 |
| Paste/drop/picker, chips, thumbnails, retry, CSS | 7 |
| Playwright E2E, gates | 8 |
| Ephemeral history (summary only) | 2, 5 |

Placeholder scan: no TBD/TODO; every code step carries its content.

Type consistency: `Validated(parts, summary)` is used in Tasks 2-3; `AriaAttachmentPayload` in Tasks 4, 6-7; `PendingImage` in Tasks 6-7; the `userSent` attachments type matches `AriaMessageAttachment` in Task 5 and the panel preview map in Task 7.

Known deviation: the plan implements the spec's "retry resends the last attachments" with an in-memory ref; nothing persists across a reload, which matches the ephemeral decision.
