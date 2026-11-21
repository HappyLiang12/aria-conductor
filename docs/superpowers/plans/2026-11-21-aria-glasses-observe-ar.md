# Aria Glasses Observe and AR Render Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let the glasses send one observation (JPEG plus AR metadata) to Aria, have Aria see the image and answer in the same turn, and return `ar_render` primitives the phone renders.

**Architecture:** A new `POST /api/v1/aria/observe` endpoint validates and authorizes the request, builds a text-only prompt seed, and starts a normal Aria run with the image carried as an opencode `file` part (data URL) through `CoreTask` into `OpenCodeHttpClient`. Aria calls a new `ar_render` MCP tool, whose tool call streams back on the existing SSE frames.

**Tech Stack:** Java 21, Spring Boot 3.3, Spring AI 1.0.9, opencode 1.19.6 (sandbox image), JUnit 5 + Mockito + WireMock, AssertJ.

**Spec:** `docs/superpowers/specs/2026-11-21-aria-glasses-observe-ar-design.md` (approved 2026-11-21). Read it before starting; this plan implements it and does not extend it.

## Global Constraints

- Java package root: `io.aria.conductor`.
- No secrets, base64 image data or raw frames in any log line, database row, trajectory row or SSE frame.
- `ar_render` argument names are camelCase: `primitive`, `target` (`trackId`, `objectId`, `label`, `bbox`), `text`, `directionDeg`, `distanceM`, `durationS`, `anchor`, `confidence`.
- Image limit: decoded <= 1,572,864 bytes. Accepted MIME: `image/jpeg`, `image/png`. JSON plus base64 only, no multipart.
- observe is operator-gated; loopback without a credential stays the operator.
- `ar_render` is `WORKER_READ` with auto-approve true.
- opencode pin becomes `1.19.6`; no other version change.
- Existing tests stay green; coverage ratchets unchanged.
- Documentation and code comments stay in English (repo convention).
- Use `bash scripts/mvn-lane.sh inner <module> -Dtest=<Class>` for single classes; never run a bare `mvn test` for one class.

---

## File Structure

New files:

- `agent-control-tower/act-execution/src/main/java/io/aria/conductor/execution/runtime/CoreAttachment.java`
- `agent-control-tower/act-aria/src/main/java/io/aria/conductor/aria/dto/AriaObserveRequest.java`
- `agent-control-tower/act-aria/src/main/java/io/aria/conductor/aria/service/AriaObservationValidator.java`
- `agent-control-tower/act-aria/src/main/java/io/aria/conductor/aria/service/AriaObservationSeed.java`
- `agent-control-tower/act-aria/src/main/java/io/aria/conductor/aria/service/AriaObserveService.java`
- `agent-control-tower/act-aria/src/main/java/io/aria/conductor/aria/controller/AriaObserveController.java`
- `agent-control-tower/act-mcp/src/main/java/io/aria/conductor/mcp/tools/ArRenderTools.java`

Modified files:

- `agent-control-tower/act-execution/src/main/java/io/aria/conductor/execution/adk/opencode/OpenCodeHttpClient.java`
- `agent-control-tower/act-execution/src/main/java/io/aria/conductor/execution/runtime/CoreTask.java`
- `agent-control-tower/act-execution/src/main/java/io/aria/conductor/execution/runtime/core/OpenCodeCoreSession.java`
- `agent-control-tower/act-execution/src/main/java/io/aria/conductor/execution/runtime/core/QoderCoreSession.java`
- `agent-control-tower/act-execution/src/main/java/io/aria/conductor/execution/engine/RunContext.java`
- `agent-control-tower/act-execution/src/main/java/io/aria/conductor/execution/engine/AgentLoopEngine.java`
- `agent-control-tower/act-mcp/src/main/java/io/aria/conductor/mcp/catalog/ToolCatalog.java`
- `agent-control-tower/act-mcp/src/test/java/io/aria/conductor/mcp/McpToolInventory.java`
- `agent-control-tower/act-aria/src/main/java/io/aria/conductor/aria/prompt/AriaPromptCatalog.java`
- `agent-control-tower/opencode-sandbox/Dockerfile`

---

### Task 1: Carry binary attachments to opencode

**Files:**
- Create: `agent-control-tower/act-execution/src/main/java/io/aria/conductor/execution/runtime/CoreAttachment.java`
- Modify: `agent-control-tower/act-execution/src/main/java/io/aria/conductor/execution/adk/opencode/OpenCodeHttpClient.java:160-184`
- Test: `agent-control-tower/act-execution/src/test/java/io/aria/conductor/execution/adk/opencode/OpenCodeHttpClientTest.java`

**Interfaces:**
- Produces: `io.aria.conductor.execution.runtime.CoreAttachment(String mimeType, byte[] bytes, String filename)`.
- Produces: `OpenCodeHttpClient.sendPrompt(String sessionId, String systemPrompt, List<String> textParts, Duration timeout, List<CoreAttachment> attachments)`.
- Keeps: the existing 4-argument `sendPrompt` overload with unchanged behaviour.

- [ ] **Step 1: Write the failing test**

Add these two tests to `OpenCodeHttpClientTest`, plus the import `io.aria.conductor.execution.runtime.CoreAttachment`:

```java
    @Test
    void sendPrompt_appendsAttachmentsAsDataUrlFileParts() {
        stubFor(post(urlEqualTo("/session/sess-abc/message"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"info":{"id":"msg-1","role":"assistant"},"parts":[{"type":"text","text":"ok"}]}
                                """)));

        client.sendPrompt("sess-abc", null, List.of("look at this"), Duration.ofSeconds(5),
                List.of(new CoreAttachment("image/jpeg", new byte[]{(byte) 0xFF, (byte) 0xD8}, "frame.jpg")));

        verify(postRequestedFor(urlEqualTo("/session/sess-abc/message"))
                .withRequestBody(matchingJsonPath("$.parts[0].type", equalTo("text")))
                .withRequestBody(matchingJsonPath("$.parts[0].text", equalTo("look at this")))
                .withRequestBody(matchingJsonPath("$.parts[1].type", equalTo("file")))
                .withRequestBody(matchingJsonPath("$.parts[1].mime", equalTo("image/jpeg")))
                .withRequestBody(matchingJsonPath("$.parts[1].filename", equalTo("frame.jpg")))
                .withRequestBody(matchingJsonPath("$.parts[1].url", equalTo("data:image/jpeg;base64,/9g="))));
    }

    @Test
    void sendPrompt_withoutAttachmentsKeepsTheTextOnlyPayload() {
        stubFor(post(urlEqualTo("/session/sess-abc/message"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"info":{"id":"msg-1","role":"assistant"},"parts":[{"type":"text","text":"ok"}]}
                                """)));

        client.sendPrompt("sess-abc", null, List.of("hello"), Duration.ofSeconds(5));

        verify(postRequestedFor(urlEqualTo("/session/sess-abc/message"))
                .withRequestBody(notMatching("(?s).*\"type\"\\s*:\\s*\"file\".*")));
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd /Users/happy/Documents/project/aria-conductor && bash scripts/mvn-lane.sh inner act-execution -Dtest=OpenCodeHttpClientTest`
Expected: compilation failure, `CoreAttachment` not found.

- [ ] **Step 3: Create `CoreAttachment`**

```java
package io.aria.conductor.execution.runtime;

import java.util.Arrays;

/**
 * One binary part sent with a core prompt. The bytes live in memory for the
 * turn only; no platform table, trajectory row or log line receives them.
 */
public record CoreAttachment(String mimeType, byte[] bytes, String filename) {

    public CoreAttachment {
        if (mimeType == null || mimeType.isBlank()) {
            throw new IllegalArgumentException("mimeType is required");
        }
        if (bytes == null || bytes.length == 0) {
            throw new IllegalArgumentException("attachment bytes are required");
        }
        bytes = Arrays.copyOf(bytes, bytes.length);
        filename = filename == null || filename.isBlank() ? "attachment" : filename;
    }

    @Override
    public byte[] bytes() {
        return Arrays.copyOf(bytes, bytes.length);
    }
}
```

- [ ] **Step 4: Split `sendPrompt` into a delegating pair**

In `OpenCodeHttpClient`, add `import io.aria.conductor.execution.runtime.CoreAttachment;` and `import java.util.Base64;`, then replace the existing `sendPrompt` body with:

```java
    public MessageResult sendPrompt(String sessionId, String systemPrompt,
            List<String> textParts, Duration timeout) {
        return sendPrompt(sessionId, systemPrompt, textParts, timeout, List.of());
    }

    /**
     * The governed prompt path with binary attachments: the ordered text parts
     * first, then one {@code file} part per attachment as a data URL. opencode
     * resolves {@code data:} URLs and normalizes every {@code image/*} part, so
     * no file is staged in the sandbox workspace.
     */
    public MessageResult sendPrompt(String sessionId, String systemPrompt,
            List<String> textParts, Duration timeout, List<CoreAttachment> attachments) {
        ObjectNode body = objectMapper.createObjectNode();
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            body.put("system", systemPrompt);
        }
        ArrayNode parts = body.putArray("parts");
        for (String text : textParts) {
            parts.addObject().put("type", "text").put("text", text == null ? "" : text);
        }
        List<CoreAttachment> attachmentList = attachments == null ? List.of() : attachments;
        for (CoreAttachment attachment : attachmentList) {
            parts.addObject()
                    .put("type", "file")
                    .put("mime", attachment.mimeType())
                    .put("filename", attachment.filename())
                    .put("url", "data:" + attachment.mimeType() + ";base64,"
                            + Base64.getEncoder().encodeToString(attachment.bytes()));
        }
        String path = "/session/" + sessionId + "/message";
        Duration effectiveTimeout = timeout != null ? timeout : requestTimeout;
        HttpResponse<String> resp = sendNoRetry("POST", path, toJson(body), effectiveTimeout);
        if (resp.statusCode() / 100 != 2) {
            throw refusalError(path, resp.statusCode(), resp.body());
        }
        MessageResult result = parseMessageResult(resp.body());
        if (result.providerError() != null) {
            throw providerError("OpenCode returned an assistant message error for " + path + ": "
                    + result.providerError());
        }
        return result;
    }
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `cd /Users/happy/Documents/project/aria-conductor && bash scripts/mvn-lane.sh inner act-execution -Dtest=OpenCodeHttpClientTest`
Expected: PASS, including every pre-existing test in the class.

- [ ] **Step 6: Commit**

```bash
git add agent-control-tower/act-execution/src/main/java/io/aria/conductor/execution/runtime/CoreAttachment.java \
        agent-control-tower/act-execution/src/main/java/io/aria/conductor/execution/adk/opencode/OpenCodeHttpClient.java \
        agent-control-tower/act-execution/src/test/java/io/aria/conductor/execution/adk/opencode/OpenCodeHttpClientTest.java
git commit -m "feat(core): send image attachments to opencode as data-url file parts"
```

---

### Task 2: Thread attachments through the run into the core task

**Files:**
- Modify: `agent-control-tower/act-execution/src/main/java/io/aria/conductor/execution/runtime/CoreTask.java`
- Modify: `agent-control-tower/act-execution/src/main/java/io/aria/conductor/execution/engine/RunContext.java`
- Modify: `agent-control-tower/act-execution/src/main/java/io/aria/conductor/execution/engine/AgentLoopEngine.java:210-247`, `:249-250`, `:309`, `:507-528`
- Modify: `agent-control-tower/act-execution/src/main/java/io/aria/conductor/execution/runtime/core/OpenCodeCoreSession.java:210-211`
- Test: `agent-control-tower/act-execution/src/test/java/io/aria/conductor/execution/runtime/CoreTaskTest.java` (new)
- Test: `agent-control-tower/act-execution/src/test/java/io/aria/conductor/execution/runtime/core/OpenCodeCoreSessionAttachmentTest.java` (new)

**Interfaces:**
- Consumes: `CoreAttachment` from Task 1.
- Produces: `CoreTask(String systemPrompt, List<LlmMessage> history, String userPrompt, List<CoreAttachment> attachments)`; the 3-argument constructor stays and carries no attachments.
- Produces: `RunContext.setAttachments(List<CoreAttachment>)` / `getAttachments()`.
- Produces: `AgentLoopEngine.startRunStream(UUID, SseEmitter, List<LlmMessage>, String, List<CoreAttachment>)`; the 4-argument overload delegates with an empty list.

- [ ] **Step 1: Write the failing tests**

Create `CoreTaskTest`:

```java
package io.aria.conductor.execution.runtime;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CoreTaskTest {

    @Test
    void theThreeArgumentConstructorCarriesNoAttachments() {
        CoreTask task = new CoreTask("sys", List.of(), "user");

        assertThat(task.attachments()).isEmpty();
    }

    @Test
    void attachmentsAreDefensivelyCopied() {
        List<CoreAttachment> source = new ArrayList<>();
        source.add(new CoreAttachment("image/png", new byte[]{1}, "a.png"));

        CoreTask task = new CoreTask("sys", List.of(), "user", source);
        source.clear();

        assertThat(task.attachments()).hasSize(1);
        assertThatThrownBy(() -> task.attachments()
                .add(new CoreAttachment("image/png", new byte[]{2}, "b.png")))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
```

Create `OpenCodeCoreSessionAttachmentTest`:

```java
package io.aria.conductor.execution.runtime.core;

import io.aria.conductor.common.runtime.AgentExecutionSettings;
import io.aria.conductor.common.runtime.ExecutionMode;
import io.aria.conductor.execution.adk.opencode.OpenCodeHttpClient;
import io.aria.conductor.execution.runtime.CoreAttachment;
import io.aria.conductor.execution.runtime.CoreTask;
import io.aria.conductor.execution.runtime.ExecutionSpec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OpenCodeCoreSessionAttachmentTest {

    private static final UUID RUN = UUID.randomUUID();
    private static final UUID AGENT = UUID.randomUUID();
    private static final String SESSION = "sess-1";

    private OpenCodeHttpClient client;
    private OpenCodeCoreSession session;

    @BeforeEach
    void setUp() {
        client = mock(OpenCodeHttpClient.class);
        session = new OpenCodeCoreSession(spec(), client, SESSION, "efficient");
    }

    @AfterEach
    void tearDown() {
        session.close();
    }

    @Test
    void attachmentsAreForwardedWithThePrompt() throws Exception {
        when(client.listMessages(SESSION)).thenReturn(new OpenCodeHttpClient.MessageListResult(List.of(), false));
        when(client.sendPrompt(eq(SESSION), any(), anyList(), any(), anyList())).thenReturn(
                new OpenCodeHttpClient.MessageResult("{\"info\":{\"id\":\"m\"},\"parts\":[]}", "m", "ok",
                        1L, 1L, "efficient", null));

        CoreAttachment image = new CoreAttachment("image/jpeg", new byte[]{1, 2, 3}, "frame.jpg");
        session.prompt(new CoreTask("sys", List.of(), "look", List.of(image)), event -> { })
                .toCompletableFuture().get(10, TimeUnit.SECONDS);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<CoreAttachment>> captor = ArgumentCaptor.forClass(List.class);
        verify(client).sendPrompt(eq(SESSION), any(), anyList(), any(), captor.capture());
        assertThat(captor.getValue()).containsExactly(image);
    }

    @Test
    void aTaskWithoutAttachmentsUsesTheTextOnlyCall() throws Exception {
        when(client.listMessages(SESSION)).thenReturn(new OpenCodeHttpClient.MessageListResult(List.of(), false));
        when(client.sendPrompt(eq(SESSION), any(), anyList(), any())).thenReturn(
                new OpenCodeHttpClient.MessageResult("{\"info\":{\"id\":\"m\"},\"parts\":[]}", "m", "ok",
                        1L, 1L, "efficient", null));

        session.prompt(new CoreTask("sys", List.of(), "look"), event -> { })
                .toCompletableFuture().get(10, TimeUnit.SECONDS);

        verify(client).sendPrompt(eq(SESSION), any(), anyList(), any());
    }

    private static ExecutionSpec spec() {
        return new ExecutionSpec(RUN, AGENT, "opencode", ExecutionMode.HOST,
                new AgentExecutionSettings("opencode", ExecutionMode.HOST, null, null, null),
                null, null, Instant.now().plusSeconds(60));
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd /Users/happy/Documents/project/aria-conductor && bash scripts/mvn-lane.sh inner act-execution -Dtest="CoreTaskTest,OpenCodeCoreSessionAttachmentTest"`
Expected: compilation failure (no 4-argument `CoreTask`, no 5-argument `sendPrompt` stub match).

- [ ] **Step 3: Extend `CoreTask`**

```java
package io.aria.conductor.execution.runtime;

import io.aria.conductor.execution.llm.LlmMessage;

import java.util.List;

/**
 * One prompt to a core session: the system material, the accepted conversation
 * history (defensively copied -- a late mutation of the caller's list cannot
 * change what was sent), the current user prompt, and any binary attachments
 * that travel with it for this turn only.
 */
public record CoreTask(String systemPrompt,
        List<LlmMessage> history,
        String userPrompt,
        List<CoreAttachment> attachments) {

    public CoreTask {
        history = List.copyOf(history);
        attachments = attachments == null ? List.of() : List.copyOf(attachments);
    }

    public CoreTask(String systemPrompt, List<LlmMessage> history, String userPrompt) {
        this(systemPrompt, history, userPrompt, List.of());
    }
}
```

- [ ] **Step 4: Add attachments to `RunContext`**

Add the field next to `priorContext`, plus accessors:

```java
    /** Binary parts for this run's turn; memory only, never persisted. */
    private List<CoreAttachment> attachments = List.of();
```

```java
    public List<CoreAttachment> getAttachments() { return attachments; }
    public void setAttachments(List<CoreAttachment> attachments) {
        this.attachments = attachments == null ? List.of() : List.copyOf(attachments);
    }
```

Add `import io.aria.conductor.execution.runtime.CoreAttachment;`.

- [ ] **Step 5: Thread attachments through `AgentLoopEngine`**

Add `import io.aria.conductor.execution.runtime.CoreAttachment;` and replace the `startRunStream`/`startRunInternal` block at lines 210-250 with:

```java
    public void startRunStream(UUID runId, SseEmitter emitter, List<LlmMessage> initialContext, String intent) {
        startRunStream(runId, emitter, initialContext, intent, List.of());
    }

    public void startRunStream(UUID runId, SseEmitter emitter, List<LlmMessage> initialContext, String intent,
                               List<CoreAttachment> attachments) {
        streamRegistry.register(runId, emitter);
        try {
            startRunInternal(runId, emitter, initialContext != null ? initialContext : List.of(), intent,
                    attachments != null ? attachments : List.of());
        } catch (RuntimeException e) {
            streamRegistry.unregister(runId);
            throw e;
        }
    }

    private void startRunInternal(UUID runId, @Nullable SseEmitter emitter, List<LlmMessage> initialContext) {
        startRunInternal(runId, emitter, initialContext, null, List.of());
    }

    private void startRunInternal(UUID runId, @Nullable SseEmitter emitter, List<LlmMessage> initialContext,
                                  @Nullable String intent) {
        startRunInternal(runId, emitter, initialContext, intent, List.of());
    }

    private void startRunInternal(UUID runId, @Nullable SseEmitter emitter, List<LlmMessage> initialContext,
                                  @Nullable String intent, List<CoreAttachment> attachments) {
```

Keep the existing body of the old 4-argument `startRunInternal` unchanged inside the new 5-argument method. After the line that constructs the context:

```java
        RunContext ctx = new RunContext(runId, agent.getId(), agent, run.getConversationId());
        ctx.setAttachments(attachments);
```

Then in `coreTask(...)`, change the return to:

```java
        return new CoreTask(system.toString(), List.copyOf(history), userPrompt, ctx.getAttachments());
```

- [ ] **Step 6: Forward attachments from `OpenCodeCoreSession`**

Replace the `sendPrompt` call in `prompt(...)`:

```java
                OpenCodeHttpClient.MessageResult message = task.attachments().isEmpty()
                        ? client.sendPrompt(sessionId, systemPrompt, parts, turnTimeout)
                        : client.sendPrompt(sessionId, systemPrompt, parts, turnTimeout, task.attachments());
```

- [ ] **Step 7: Run tests to verify they pass**

Run: `cd /Users/happy/Documents/project/aria-conductor && bash scripts/mvn-lane.sh inner act-execution -Dtest="CoreTaskTest,OpenCodeCoreSessionAttachmentTest,OpenCodeCoreSessionTest"`
Expected: PASS, including the pre-existing session tests (their 4-argument stubs still match the no-attachment path).

- [ ] **Step 8: Commit**

```bash
git add agent-control-tower/act-execution/src/main/java/io/aria/conductor/execution/runtime/CoreTask.java \
        agent-control-tower/act-execution/src/main/java/io/aria/conductor/execution/engine/RunContext.java \
        agent-control-tower/act-execution/src/main/java/io/aria/conductor/execution/engine/AgentLoopEngine.java \
        agent-control-tower/act-execution/src/main/java/io/aria/conductor/execution/runtime/core/OpenCodeCoreSession.java \
        agent-control-tower/act-execution/src/test/java/io/aria/conductor/execution/runtime/CoreTaskTest.java \
        agent-control-tower/act-execution/src/test/java/io/aria/conductor/execution/runtime/core/OpenCodeCoreSessionAttachmentTest.java
git commit -m "feat(execution): carry per-turn attachments from the run into the core task"
```

---

### Task 3: Refuse attachments on the Qoder core

**Files:**
- Modify: `agent-control-tower/act-execution/src/main/java/io/aria/conductor/execution/runtime/core/QoderCoreSession.java:154-175`
- Test: `agent-control-tower/act-execution/src/test/java/io/aria/conductor/execution/runtime/core/QoderCoreSessionAttachmentRefusalTest.java` (new)

**Interfaces:**
- Consumes: `CoreTask.attachments()` from Task 2.
- Produces: a failed `CompletionStage` carrying `TaskExecutionException` with cause `PROVIDER_ERROR` for any non-empty attachment list; the bridge is never touched.

- [ ] **Step 1: Write the failing test**

```java
package io.aria.conductor.execution.runtime.core;

import io.aria.conductor.common.runtime.AgentExecutionSettings;
import io.aria.conductor.common.runtime.ExecutionMode;
import io.aria.conductor.execution.adk.TaskExecutionException;
import io.aria.conductor.execution.runtime.CoreAttachment;
import io.aria.conductor.execution.runtime.CoreTask;
import io.aria.conductor.execution.runtime.ExecutionSpec;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class QoderCoreSessionAttachmentRefusalTest {

    @Test
    void anAttachmentIsRefusedBeforeTheBridgeIsTouched() {
        QoderBridgeClient client = mock(QoderBridgeClient.class);
        try (QoderCoreSession session = new QoderCoreSession(spec(), client, "efficient")) {
            CoreTask task = new CoreTask("sys", List.of(), "look",
                    List.of(new CoreAttachment("image/jpeg", new byte[]{1}, "frame.jpg")));

            assertThatThrownBy(() -> session.prompt(task, event -> { })
                    .toCompletableFuture().get(5, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class)
                    .cause()
                    .isInstanceOf(TaskExecutionException.class)
                    .hasMessageContaining("does not accept image attachments");

            verifyNoInteractions(client);
        }
    }

    private static ExecutionSpec spec() {
        return new ExecutionSpec(UUID.randomUUID(), UUID.randomUUID(), "qoder", ExecutionMode.HOST,
                new AgentExecutionSettings("qoder", ExecutionMode.HOST, null, null, null),
                null, null, Instant.now().plusSeconds(60));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd /Users/happy/Documents/project/aria-conductor && bash scripts/mvn-lane.sh inner act-execution -Dtest=QoderCoreSessionAttachmentRefusalTest`
Expected: FAIL because the prompt proceeds to the bridge (mock returns null) instead of the refusal message.

- [ ] **Step 3: Add the refusal**

In `QoderCoreSession.prompt(CoreTask task, Consumer<CoreEvent> events, Instant deadline)`, immediately after the two `Objects.requireNonNull` calls, add:

```java
        if (!task.attachments().isEmpty()) {
            return CompletableFuture.failedFuture(new TaskExecutionException(
                    TaskExecutionException.Cause.PROVIDER_ERROR,
                    "The Qoder core does not accept image attachments; use the opencode core for observations"));
        }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd /Users/happy/Documents/project/aria-conductor && bash scripts/mvn-lane.sh inner act-execution -Dtest=QoderCoreSessionAttachmentRefusalTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add agent-control-tower/act-execution/src/main/java/io/aria/conductor/execution/runtime/core/QoderCoreSession.java \
        agent-control-tower/act-execution/src/test/java/io/aria/conductor/execution/runtime/core/QoderCoreSessionAttachmentRefusalTest.java
git commit -m "fix(core): refuse image attachments on the Qoder core instead of dropping them"
```

---

### Task 4: Observe request, validation and prompt seed

**Files:**
- Create: `agent-control-tower/act-aria/src/main/java/io/aria/conductor/aria/dto/AriaObserveRequest.java`
- Create: `agent-control-tower/act-aria/src/main/java/io/aria/conductor/aria/service/AriaObservationValidator.java`
- Create: `agent-control-tower/act-aria/src/main/java/io/aria/conductor/aria/service/AriaObservationSeed.java`
- Test: `agent-control-tower/act-aria/src/test/java/io/aria/conductor/aria/service/AriaObservationValidatorTest.java` (new)
- Test: `agent-control-tower/act-aria/src/test/java/io/aria/conductor/aria/service/AriaObservationSeedTest.java` (new)

**Interfaces:**
- Produces: `AriaObserveRequest` with nested `Image`, `ClientCapabilities`, `Observation`, `SceneObject`.
- Produces: `AriaObservationValidator.validate(AriaObserveRequest request, long contentLength)` returning `Result(Rejection rejection, ObservedImage image)`; `Result.isRejected()`.
- Produces: `AriaObservationValidator.Rejection(HttpStatus status, String message)`.
- Produces: `AriaObservationValidator.ObservedImage(byte[] bytes, String mimeType)`.
- Produces: `AriaObservationSeed.build(AriaObserveRequest request)` returning the text seed (<= 8192 chars, never base64).

- [ ] **Step 1: Write the failing validator test**

```java
package io.aria.conductor.aria.service;

import io.aria.conductor.aria.dto.AriaObserveRequest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AriaObservationValidatorTest {

    private static final String JPEG_BASE64 = "/9j/2Q==";

    @Test
    void aValidObservationIsDecoded() {
        AriaObservationValidator.Result result =
                AriaObservationValidator.validate(request(objectWithBbox(0.1, 0.2, 0.3, 0.4)), 1_000);

        assertThat(result.isRejected()).isFalse();
        assertThat(result.image().mimeType()).isEqualTo("image/jpeg");
        assertThat(result.image().bytes()).isNotEmpty();
    }

    @Test
    void anUnknownMimeIs415() {
        AriaObserveRequest request = request(objectWithBbox(0.1, 0.2, 0.3, 0.4));
        request.getImage().setMimeType("image/gif");

        assertRejected(request, 1_000, HttpStatus.UNSUPPORTED_MEDIA_TYPE);
    }

    @Test
    void invalidBase64Is400() {
        AriaObserveRequest request = request(objectWithBbox(0.1, 0.2, 0.3, 0.4));
        request.getImage().setDataBase64("not base64 !!");

        assertRejected(request, 1_000, HttpStatus.BAD_REQUEST);
    }

    @Test
    void anOversizedContentLengthIs413() {
        assertRejected(request(objectWithBbox(0.1, 0.2, 0.3, 0.4)), 3_000_001,
                HttpStatus.PAYLOAD_TOO_LARGE);
    }

    @Test
    void moreThanThirtyTwoObjectsIs422() {
        AriaObserveRequest request = request(objectWithBbox(0.1, 0.2, 0.3, 0.4));
        List<AriaObserveRequest.SceneObject> objects = new ArrayList<>();
        for (int i = 0; i < 33; i++) {
            objects.add(objectWithBbox(0.1, 0.2, 0.3, 0.4));
        }
        request.getObservation().setObjects(objects);

        assertRejected(request, 1_000, HttpStatus.UNPROCESSABLE_ENTITY);
    }

    @Test
    void aBboxOutsideTheUnitSquareIs422() {
        assertRejected(request(objectWithBbox(-0.1, 0.2, 0.3, 0.4)), 1_000,
                HttpStatus.UNPROCESSABLE_ENTITY);
    }

    @Test
    void aZeroWidthBboxIs422() {
        assertRejected(request(objectWithBbox(0.1, 0.2, 0.0, 0.4)), 1_000,
                HttpStatus.UNPROCESSABLE_ENTITY);
    }

    @Test
    void aMissingImageIs400() {
        AriaObserveRequest request = request(objectWithBbox(0.1, 0.2, 0.3, 0.4));
        request.setImage(null);

        assertRejected(request, 1_000, HttpStatus.BAD_REQUEST);
    }

    private static void assertRejected(AriaObserveRequest request, long contentLength, HttpStatus status) {
        AriaObservationValidator.Result result = AriaObservationValidator.validate(request, contentLength);
        assertThat(result.isRejected()).isTrue();
        assertThat(result.rejection().status()).isEqualTo(status);
    }

    static AriaObserveRequest request(AriaObserveRequest.SceneObject object) {
        AriaObserveRequest.Image image = new AriaObserveRequest.Image();
        image.setMimeType("image/jpeg");
        image.setDataBase64(JPEG_BASE64);
        image.setWidth(640);
        image.setHeight(480);

        AriaObserveRequest.ClientCapabilities capabilities = new AriaObserveRequest.ClientCapabilities();
        capabilities.setKind("glasses");
        capabilities.setAr(true);
        capabilities.setVision(true);

        AriaObserveRequest.Observation observation = new AriaObserveRequest.Observation();
        observation.setTimestamp(1760000000000L);
        observation.setHeading(123.4);
        observation.setPitch(-2.1);
        observation.setRoll(0.3);
        observation.setObjects(new ArrayList<>(List.of(object)));
        observation.setOcrText("SONY");
        observation.setLocalSummary("1 remote");

        AriaObserveRequest request = new AriaObserveRequest();
        request.setConversationId("conv-1");
        request.setNote("AR mode observation");
        request.setImage(image);
        request.setClientCapabilities(capabilities);
        request.setObservation(observation);
        return request;
    }

    static AriaObserveRequest.SceneObject objectWithBbox(double x, double y, double w, double h) {
        AriaObserveRequest.SceneObject object = new AriaObserveRequest.SceneObject();
        object.setId("o1");
        object.setTrackId(7);
        object.setLabel("remote");
        object.setConfidence(0.91);
        object.setBbox(List.of(x, y, w, h));
        object.setCenter(List.of(x + w / 2, y + h / 2));
        object.setDescription("black TV remote on desk");
        return object;
    }
}
```

- [ ] **Step 2: Write the failing seed test**

```java
package io.aria.conductor.aria.service;

import io.aria.conductor.aria.dto.AriaObserveRequest;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AriaObservationSeedTest {

    @Test
    void theSeedIsTextOnlyAndNeverCarriesTheImage() {
        AriaObserveRequest request = AriaObservationValidatorTest.request(
                AriaObservationValidatorTest.objectWithBbox(0.42, 0.55, 0.12, 0.08));

        String seed = AriaObservationSeed.build(request);

        assertThat(seed).startsWith("[glasses observation] note: AR mode observation");
        assertThat(seed).contains("capabilities: ar=true,vision=true,audio=false");
        assertThat(seed).contains("captured_at: 2025-10-09T08:53:20Z");
        assertThat(seed).contains("trackId=7 objectId=o1 label=\"remote\"");
        assertThat(seed).contains("bbox=(0.420,0.550,0.120,0.080)");
        assertThat(seed).doesNotContain(request.getImage().getDataBase64());
    }

    @Test
    void theSeedIsCappedAtEightKilobytes() {
        AriaObserveRequest request = AriaObservationValidatorTest.request(
                AriaObservationValidatorTest.objectWithBbox(0.4, 0.5, 0.1, 0.1));
        List<AriaObserveRequest.SceneObject> objects = new ArrayList<>();
        for (int i = 0; i < 32; i++) {
            AriaObserveRequest.SceneObject object =
                    AriaObservationValidatorTest.objectWithBbox(0.4, 0.5, 0.1, 0.1);
            object.setDescription("d".repeat(250));
            objects.add(object);
        }
        request.getObservation().setObjects(objects);

        String seed = AriaObservationSeed.build(request);

        assertThat(seed.length()).isLessThanOrEqualTo(8192);
        assertThat(seed).endsWith(" [truncated]");
    }

    @Test
    void theRequestToStringNeverPrintsTheImage() {
        AriaObserveRequest request = AriaObservationValidatorTest.request(
                AriaObservationValidatorTest.objectWithBbox(0.4, 0.5, 0.1, 0.1));

        assertThat(request.toString()).doesNotContain(request.getImage().getDataBase64());
    }
}
```

- [ ] **Step 3: Run tests to verify they fail**

Run: `cd /Users/happy/Documents/project/aria-conductor && bash scripts/mvn-lane.sh inner act-aria -Dtest="AriaObservationValidatorTest,AriaObservationSeedTest"`
Expected: compilation failure (classes missing).

- [ ] **Step 4: Create the request DTO**

```java
package io.aria.conductor.aria.dto;

import lombok.Data;
import lombok.ToString;

import java.util.List;

/**
 * One glasses observation. The image is never part of the run's persisted
 * prompt; its base64 must stay out of every toString() and log line, which is
 * why the field carries {@link ToString.Exclude}.
 */
@Data
public class AriaObserveRequest {

    private String conversationId;
    private String note;
    private Image image;
    private ClientCapabilities clientCapabilities;
    private Observation observation;

    @Data
    public static class Image {
        private String mimeType;
        @ToString.Exclude
        private String dataBase64;
        private Integer width;
        private Integer height;
    }

    @Data
    public static class ClientCapabilities {
        private String kind;
        private Boolean ar;
        private Boolean vision;
        private Boolean audio;
    }

    @Data
    public static class Observation {
        private Long timestamp;
        private Double heading;
        private Double pitch;
        private Double roll;
        private List<SceneObject> objects;
        private String ocrText;
        private String localSummary;
    }

    @Data
    public static class SceneObject {
        private String id;
        private Integer trackId;
        private String label;
        private Double confidence;
        private List<Double> bbox;
        private List<Double> center;
        private String description;
    }
}
```

- [ ] **Step 5: Create the validator**

```java
package io.aria.conductor.aria.service;

import io.aria.conductor.aria.dto.AriaObserveRequest;
import org.springframework.http.HttpStatus;

import java.util.Base64;
import java.util.List;
import java.util.Set;

/**
 * The observe request's one validation pass: MIME, base64, size and geometry.
 * Every rejected request is answered before a Run exists, so a bad frame can
 * never consume an admission slot.
 */
public final class AriaObservationValidator {

    public static final int MAX_DECODED_BYTES = 1_572_864;
    public static final long MAX_CONTENT_LENGTH = 3_000_000L;
    private static final Set<String> ALLOWED_MIME = Set.of("image/jpeg", "image/png");
    private static final int MAX_OBJECTS = 32;

    public record Rejection(HttpStatus status, String message) {}
    public record ObservedImage(byte[] bytes, String mimeType) {}

    public record Result(Rejection rejection, ObservedImage image) {
        public boolean isRejected() {
            return rejection != null;
        }
    }

    private AriaObservationValidator() {
    }

    public static Result validate(AriaObserveRequest request, long contentLength) {
        if (contentLength > MAX_CONTENT_LENGTH) {
            return reject(HttpStatus.PAYLOAD_TOO_LARGE,
                    "Request body exceeds " + MAX_CONTENT_LENGTH + " bytes");
        }
        if (request == null || request.getImage() == null
                || request.getImage().getDataBase64() == null
                || request.getImage().getDataBase64().isBlank()) {
            return reject(HttpStatus.BAD_REQUEST, "image.dataBase64 is required");
        }
        String mime = request.getImage().getMimeType();
        if (mime == null || !ALLOWED_MIME.contains(mime)) {
            return reject(HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                    "image.mimeType must be image/jpeg or image/png");
        }
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(request.getImage().getDataBase64());
        } catch (IllegalArgumentException e) {
            return reject(HttpStatus.BAD_REQUEST, "image.dataBase64 is not valid base64");
        }
        if (bytes.length == 0) {
            return reject(HttpStatus.BAD_REQUEST, "image is empty");
        }
        if (bytes.length > MAX_DECODED_BYTES) {
            return reject(HttpStatus.PAYLOAD_TOO_LARGE,
                    "Decoded image exceeds " + MAX_DECODED_BYTES + " bytes");
        }
        if (request.getObservation() == null) {
            return reject(HttpStatus.BAD_REQUEST, "observation is required");
        }
        List<AriaObserveRequest.SceneObject> objects = request.getObservation().getObjects();
        if (objects != null) {
            if (objects.size() > MAX_OBJECTS) {
                return reject(HttpStatus.UNPROCESSABLE_ENTITY,
                        "observation.objects must not exceed " + MAX_OBJECTS);
            }
            for (AriaObserveRequest.SceneObject object : objects) {
                if (object == null) {
                    continue;
                }
                Rejection geometry = validateGeometry(object);
                if (geometry != null) {
                    return new Result(geometry, null);
                }
            }
        }
        return new Result(null, new ObservedImage(bytes, mime));
    }

    private static Rejection validateGeometry(AriaObserveRequest.SceneObject object) {
        if (object.getBbox() != null) {
            List<Double> bbox = object.getBbox();
            if (bbox.size() != 4 || !inUnitRange(bbox.get(0)) || !inUnitRange(bbox.get(1))
                    || !inUnitRange(bbox.get(2)) || !inUnitRange(bbox.get(3))
                    || bbox.get(2) <= 0 || bbox.get(3) <= 0) {
                return new Rejection(HttpStatus.UNPROCESSABLE_ENTITY,
                        "object " + object.getId() + " has an invalid bbox");
            }
        }
        if (object.getCenter() != null) {
            List<Double> center = object.getCenter();
            if (center.size() != 2 || !inUnitRange(center.get(0)) || !inUnitRange(center.get(1))) {
                return new Rejection(HttpStatus.UNPROCESSABLE_ENTITY,
                        "object " + object.getId() + " has an invalid center");
            }
        }
        if (object.getConfidence() != null && !inUnitRange(object.getConfidence())) {
            return new Rejection(HttpStatus.UNPROCESSABLE_ENTITY,
                    "object " + object.getId() + " has a confidence outside 0..1");
        }
        return null;
    }

    private static boolean inUnitRange(Double value) {
        return value != null && value >= 0.0 && value <= 1.0;
    }

    private static Result reject(HttpStatus status, String message) {
        return new Result(new Rejection(status, message), null);
    }
}
```

- [ ] **Step 6: Create the seed builder**

```java
package io.aria.conductor.aria.service;

import io.aria.conductor.aria.dto.AriaObserveRequest;

import java.time.Instant;
import java.util.List;
import java.util.Locale;

/**
 * The text a glasses observation becomes on the run's prompt seed. It is the
 * only form of the observation that is persisted, so it carries metadata and
 * never image data.
 */
public final class AriaObservationSeed {

    static final int MAX_SEED_CHARS = 8192;

    private AriaObservationSeed() {
    }

    public static String build(AriaObserveRequest request) {
        AriaObserveRequest.Observation observation = request.getObservation();
        AriaObserveRequest.ClientCapabilities capabilities = request.getClientCapabilities();
        StringBuilder seed = new StringBuilder();
        seed.append("[glasses observation] note: ")
                .append(clip(request.getNote(), 500, "(none)")).append('\n');
        seed.append("capabilities: ar=").append(flag(capabilities == null ? null : capabilities.getAr()))
                .append(",vision=").append(flag(capabilities == null ? null : capabilities.getVision()))
                .append(",audio=").append(flag(capabilities == null ? null : capabilities.getAudio()))
                .append('\n');
        seed.append("captured_at: ")
                .append(observation.getTimestamp() == null
                        ? "unknown" : Instant.ofEpochMilli(observation.getTimestamp()))
                .append('\n');
        if (observation.getHeading() != null || observation.getPitch() != null
                || observation.getRoll() != null) {
            seed.append("orientation: heading=").append(num(observation.getHeading()))
                    .append(" pitch=").append(num(observation.getPitch()))
                    .append(" roll=").append(num(observation.getRoll())).append('\n');
        }
        seed.append("objects:\n");
        List<AriaObserveRequest.SceneObject> objects = observation.getObjects();
        if (objects == null || objects.isEmpty()) {
            seed.append("- (none)\n");
        } else {
            for (AriaObserveRequest.SceneObject object : objects) {
                if (object != null) {
                    seed.append(objectLine(object)).append('\n');
                }
            }
        }
        if (isNotBlank(observation.getOcrText())) {
            seed.append("ocr: \"").append(clip(observation.getOcrText(), 2000, "")).append("\"\n");
        }
        if (isNotBlank(observation.getLocalSummary())) {
            seed.append("summary: \"")
                    .append(clip(observation.getLocalSummary(), 500, "")).append("\"\n");
        }
        return capSeed(seed.toString());
    }

    private static String capSeed(String seed) {
        if (seed.length() <= MAX_SEED_CHARS) {
            return seed;
        }
        String suffix = " [truncated]";
        return seed.substring(0, MAX_SEED_CHARS - suffix.length()) + suffix;
    }

    private static String objectLine(AriaObserveRequest.SceneObject object) {
        StringBuilder line = new StringBuilder("-");
        if (object.getTrackId() != null) {
            line.append(" trackId=").append(object.getTrackId());
        }
        if (isNotBlank(object.getId())) {
            line.append(" objectId=").append(object.getId());
        }
        if (isNotBlank(object.getLabel())) {
            line.append(" label=\"").append(clip(object.getLabel(), 64, "")).append('"');
        }
        if (object.getConfidence() != null) {
            line.append(" confidence=")
                    .append(String.format(Locale.ROOT, "%.2f", object.getConfidence()));
        }
        if (object.getBbox() != null && object.getBbox().size() == 4) {
            line.append(" bbox=(").append(vector(object.getBbox(), 4)).append(')');
        }
        if (object.getCenter() != null && object.getCenter().size() == 2) {
            line.append(" center=(").append(vector(object.getCenter(), 2)).append(')');
        }
        if (isNotBlank(object.getDescription())) {
            line.append(" description=\"")
                    .append(clip(object.getDescription(), 200, "")).append('"');
        }
        return line.toString();
    }

    private static String vector(List<Double> values, int expected) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < expected; i++) {
            if (i > 0) {
                out.append(',');
            }
            Double value = i < values.size() ? values.get(i) : null;
            out.append(value == null ? "?" : String.format(Locale.ROOT, "%.3f", value));
        }
        return out.toString();
    }

    private static String flag(Boolean value) {
        return Boolean.TRUE.equals(value) ? "true" : "false";
    }

    private static String num(Double value) {
        return value == null ? "unknown" : String.format(Locale.ROOT, "%.1f", value);
    }

    private static boolean isNotBlank(String value) {
        return value != null && !value.isBlank();
    }

    private static String clip(String value, int max, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value.length() <= max ? value : value.substring(0, max) + " [truncated]";
    }
}
```

- [ ] **Step 7: Run tests to verify they pass**

Run: `cd /Users/happy/Documents/project/aria-conductor && bash scripts/mvn-lane.sh inner act-aria -Dtest="AriaObservationValidatorTest,AriaObservationSeedTest"`
Expected: PASS.

- [ ] **Step 8: Commit**

```bash
git add agent-control-tower/act-aria/src/main/java/io/aria/conductor/aria/dto/AriaObserveRequest.java \
        agent-control-tower/act-aria/src/main/java/io/aria/conductor/aria/service/AriaObservationValidator.java \
        agent-control-tower/act-aria/src/main/java/io/aria/conductor/aria/service/AriaObservationSeed.java \
        agent-control-tower/act-aria/src/test/java/io/aria/conductor/aria/service/AriaObservationValidatorTest.java \
        agent-control-tower/act-aria/src/test/java/io/aria/conductor/aria/service/AriaObservationSeedTest.java
git commit -m "feat(aria): validate glasses observations and build a text-only prompt seed"
```

---

### Task 5: The observe endpoint and run wiring

**Files:**
- Create: `agent-control-tower/act-aria/src/main/java/io/aria/conductor/aria/service/AriaObserveService.java`
- Create: `agent-control-tower/act-aria/src/main/java/io/aria/conductor/aria/controller/AriaObserveController.java`
- Test: `agent-control-tower/act-aria/src/test/java/io/aria/conductor/aria/service/AriaObserveServiceTest.java` (new)
- Test: `agent-control-tower/act-aria/src/test/java/io/aria/conductor/aria/controller/AriaObserveControllerTest.java` (new)

**Interfaces:**
- Consumes: `AriaObservationValidator`, `AriaObservationSeed`, `AriaObserveRequest` from Task 4; `CoreAttachment`, `AgentLoopEngine.startRunStream(..., List<CoreAttachment>)` from Tasks 1-2.
- Produces: `AriaObserveService.preflight(AriaObserveRequest)` returning `Optional<AriaObservationValidator.Rejection>` (only a 409 core refusal today).
- Produces: `AriaObserveService.stream(AriaObserveRequest, AriaObservationValidator.ObservedImage, SseEmitter)`.
- Produces: `POST /api/v1/aria/observe` returning `ResponseEntity<?>`: `SseEmitter` on success, JSON `{"error": "…"}` on rejection.

- [ ] **Step 1: Write the failing service test**

```java
package io.aria.conductor.aria.service;

import io.aria.conductor.agent.repository.AgentRepository;
import io.aria.conductor.agent.repository.RunRepository;
import io.aria.conductor.common.AriaConstants;
import io.aria.conductor.common.model.Agent;
import io.aria.conductor.common.model.Run;
import io.aria.conductor.execution.engine.AgentLoopEngine;
import io.aria.conductor.execution.runtime.CoreAttachment;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AriaObserveServiceTest {

    private static final UUID RUN_ID = UUID.randomUUID();

    private final AgentRepository agentRepository = mock(AgentRepository.class);
    private final RunRepository runRepository = mock(RunRepository.class);
    private final AgentLoopEngine engine = mock(AgentLoopEngine.class);
    private final AriaObserveService service =
            new AriaObserveService(agentRepository, runRepository, engine);

    @Test
    void theRunCarriesTheSeedAndTheImageAsAnAttachment() {
        when(agentRepository.findById(AriaConstants.ARIA_AGENT_ID))
                .thenReturn(Optional.of(agent("opencode")));
        when(runRepository.findAll()).thenReturn(List.of());
        when(runRepository.save(any(Run.class))).thenAnswer(invocation -> {
            Run run = invocation.getArgument(0);
            run.setId(RUN_ID);
            return run;
        });

        SseEmitter emitter = new SseEmitter(1_000L);
        service.stream(AriaObservationValidatorTest.request(
                AriaObservationValidatorTest.objectWithBbox(0.4, 0.5, 0.1, 0.1)),
                new AriaObservationValidator.ObservedImage(new byte[]{1, 2, 3}, "image/jpeg"),
                emitter);

        ArgumentCaptor<Run> runCaptor = ArgumentCaptor.forClass(Run.class);
        verify(runRepository).save(runCaptor.capture());
        assertThat(runCaptor.getValue().getPromptSeed())
                .startsWith("[glasses observation]")
                .doesNotContain("/9j/2Q==");
        assertThat(runCaptor.getValue().getConversationId()).isNotBlank();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<CoreAttachment>> attachmentCaptor = ArgumentCaptor.forClass(List.class);
        verify(engine).startRunStream(eq(RUN_ID), eq(emitter), eq(List.of()), eq("observe"),
                attachmentCaptor.capture());
        assertThat(attachmentCaptor.getValue()).singleElement().satisfies(attachment -> {
            assertThat(attachment.mimeType()).isEqualTo("image/jpeg");
            assertThat(attachment.filename()).isEqualTo("observation.jpg");
            assertThat(attachment.bytes()).containsExactly(1, 2, 3);
        });
    }

    @Test
    void anOmittedConversationReusesTheLatestOne() {
        when(agentRepository.findById(AriaConstants.ARIA_AGENT_ID))
                .thenReturn(Optional.of(agent("opencode")));
        when(runRepository.findAll()).thenReturn(List.of(
                run("older", Instant.parse("2026-11-20T10:00:00Z")),
                run("newest", Instant.parse("2026-11-21T10:00:00Z"))));
        when(runRepository.save(any(Run.class))).thenAnswer(invocation -> {
            Run saved = invocation.getArgument(0);
            saved.setId(RUN_ID);
            return saved;
        });
        var request = AriaObservationValidatorTest.request(
                AriaObservationValidatorTest.objectWithBbox(0.4, 0.5, 0.1, 0.1));
        request.setConversationId(null);

        service.stream(request,
                new AriaObservationValidator.ObservedImage(new byte[]{1}, "image/jpeg"),
                new SseEmitter(1_000L));

        ArgumentCaptor<Run> runCaptor = ArgumentCaptor.forClass(Run.class);
        verify(runRepository).save(runCaptor.capture());
        assertThat(runCaptor.getValue().getConversationId()).isEqualTo("newest");
    }

    @Test
    void aNonOpencodeCoreIsRefusedBeforeAnyRunIsCreated() {
        when(agentRepository.findById(AriaConstants.ARIA_AGENT_ID))
                .thenReturn(Optional.of(agent("qoder")));

        Optional<AriaObservationValidator.Rejection> rejection =
                service.preflight(AriaObservationValidatorTest.request(
                        AriaObservationValidatorTest.objectWithBbox(0.4, 0.5, 0.1, 0.1)));

        assertThat(rejection).isPresent();
        assertThat(rejection.get().status().value()).isEqualTo(409);
        verify(runRepository, never()).save(any());
    }

    private static Agent agent(String core) {
        Agent agent = new Agent();
        agent.setId(AriaConstants.ARIA_AGENT_ID);
        agent.setAdkProvider(core);
        return agent;
    }

    private static Run run(String conversationId, Instant createdAt) {
        Run run = new Run();
        run.setConversationId(conversationId);
        run.setCreatedAt(createdAt);
        return run;
    }
}
```

- [ ] **Step 2: Write the failing controller test**

```java
package io.aria.conductor.aria.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.aria.conductor.agent.service.SystemConfigService;
import io.aria.conductor.aria.service.AriaObservationValidator;
import io.aria.conductor.aria.service.AriaObserveService;
import io.aria.conductor.execution.security.OperatorAuthorityResolver;
import io.aria.conductor.execution.security.OperatorSessionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AriaObserveControllerTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final AriaObserveService service = mock(AriaObserveService.class);
    private final OperatorAuthorityResolver authority = mock(OperatorAuthorityResolver.class);
    private final SystemConfigService systemConfig = mock(SystemConfigService.class);

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(
                new AriaObserveController(service, authority, systemConfig)).build();
        when(systemConfig.getLong(eq("aria.sse.timeout.ms"), anyLong(), anyLong(), anyLong()))
                .thenReturn(600_000L);
        when(service.preflight(any())).thenReturn(Optional.empty());
    }

    @Test
    void aWorkerCredentialIs403() throws Exception {
        when(authority.resolveOperator(any(), eq(true)))
                .thenThrow(new OperatorSessionService.ForbiddenMutationException("worker"));

        mockMvc.perform(post("/api/v1/aria/observe")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("image/jpeg", "/9j/2Q==")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("worker"));
    }

    @Test
    void noVerifiableIdentityIs401() throws Exception {
        when(authority.resolveOperator(any(), eq(true)))
                .thenThrow(new SecurityException("operator session required"));

        mockMvc.perform(post("/api/v1/aria/observe")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("image/jpeg", "/9j/2Q==")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void anUnsupportedMimeIs415() throws Exception {
        mockMvc.perform(post("/api/v1/aria/observe")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("image/gif", "/9j/2Q==")))
                .andExpect(status().isUnsupportedMediaType());
    }

    @Test
    void aCoreRefusalIs409() throws Exception {
        when(service.preflight(any())).thenReturn(Optional.of(
                new AriaObservationValidator.Rejection(HttpStatus.CONFLICT, "opencode only")));

        mockMvc.perform(post("/api/v1/aria/observe")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("image/jpeg", "/9j/2Q==")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("opencode only"));
    }

    @Test
    void aValidObservationStartsTheStream() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/aria/observe")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.TEXT_EVENT_STREAM)
                        .content(body("image/jpeg", "/9j/2Q==")))
                .andExpect(status().isOk())
                .andReturn();

        verify(service).stream(any(), any(), any());
        result.getRequest().getAsyncContext().complete();
    }

    private String body(String mime, String base64) throws Exception {
        return mapper.writeValueAsString(Map.of(
                "conversationId", "conv-1",
                "image", Map.of("mimeType", mime, "dataBase64", base64),
                "clientCapabilities", Map.of("kind", "glasses", "ar", true),
                "observation", Map.of(
                        "timestamp", 1760000000000L,
                        "objects", java.util.List.of(Map.of(
                                "id", "o1", "trackId", 7, "label", "remote",
                                "bbox", java.util.List.of(0.4, 0.5, 0.1, 0.1)))));
    }
}
```

- [ ] **Step 3: Run tests to verify they fail**

Run: `cd /Users/happy/Documents/project/aria-conductor && bash scripts/mvn-lane.sh inner act-aria -Dtest="AriaObserveServiceTest,AriaObserveControllerTest"`
Expected: compilation failure (service and controller missing).

- [ ] **Step 4: Create `AriaObserveService`**

```java
package io.aria.conductor.aria.service;

import io.aria.conductor.agent.repository.AgentRepository;
import io.aria.conductor.agent.repository.RunRepository;
import io.aria.conductor.aria.dto.AriaObserveRequest;
import io.aria.conductor.common.AriaConstants;
import io.aria.conductor.common.model.Agent;
import io.aria.conductor.common.model.Run;
import io.aria.conductor.common.model.RunErrorText;
import io.aria.conductor.common.model.RunStatus;
import io.aria.conductor.execution.engine.AgentLoopEngine;
import io.aria.conductor.execution.runtime.CoreAttachment;
import io.aria.conductor.execution.runtime.core.OpenCodeCoreAdapter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static io.aria.conductor.execution.engine.AgentLoopEngine.parseMaxIterationsFromConfig;

/**
 * Turns one glasses observation into a normal Aria run. The image travels as a
 * turn-only attachment; the persisted prompt seed is text built by
 * {@link AriaObservationSeed}. It owns no prompt of its own.
 */
@Slf4j
@Service
public class AriaObserveService {

    private final AgentRepository agentRepository;
    private final RunRepository runRepository;
    private final AgentLoopEngine agentLoopEngine;

    public AriaObserveService(AgentRepository agentRepository,
                              RunRepository runRepository,
                              AgentLoopEngine agentLoopEngine) {
        this.agentRepository = agentRepository;
        this.runRepository = runRepository;
        this.agentLoopEngine = agentLoopEngine;
    }

    /**
     * Refusals that must answer JSON before a stream exists. A null or blank
     * core is the documented default (opencode) and is accepted.
     */
    public Optional<AriaObservationValidator.Rejection> preflight(AriaObserveRequest request) {
        Agent aria = agentRepository.findById(AriaConstants.ARIA_AGENT_ID).orElse(null);
        if (aria == null) {
            return Optional.of(new AriaObservationValidator.Rejection(
                    HttpStatus.INTERNAL_SERVER_ERROR, "Aria agent not found"));
        }
        String core = aria.getAdkProvider();
        if (core != null && !core.isBlank() && !OpenCodeCoreAdapter.CORE_ID.equalsIgnoreCase(core)) {
            return Optional.of(new AriaObservationValidator.Rejection(HttpStatus.CONFLICT,
                    "Observations require the opencode core; Aria is on '" + core + "'"));
        }
        return Optional.empty();
    }

    public void stream(AriaObserveRequest request,
                       AriaObservationValidator.ObservedImage image,
                       SseEmitter emitter) {
        emitter.onTimeout(() -> {
            log.warn("Aria observation stream timed out");
            sendErrorSilent(emitter, AriaStreamService.errorPayloadJson("stream timed out", null, null));
            emitter.complete();
        });
        emitter.onError(t -> log.warn("Aria observation stream error: {}", t.getMessage()));

        Run run = null;
        try {
            String conversationId = resolveConversationId(request.getConversationId());
            Agent aria = agentRepository.findById(AriaConstants.ARIA_AGENT_ID)
                    .orElseThrow(() -> new IllegalStateException("Aria agent not found"));
            String seed = AriaObservationSeed.build(request);
            log.info("Aria observe: conversationId={}, objects={}", conversationId,
                    request.getObservation().getObjects() == null
                            ? 0 : request.getObservation().getObjects().size());

            run = Run.builder()
                    .agentId(AriaConstants.ARIA_AGENT_ID)
                    .promptSeed(seed)
                    .maxIterations(parseMaxIterationsFromConfig(aria, 0))
                    .status(RunStatus.PENDING)
                    .conversationId(conversationId)
                    .build();
            run = runRepository.save(run);

            String filename = "image/png".equals(image.mimeType())
                    ? "observation.png" : "observation.jpg";
            agentLoopEngine.startRunStream(run.getId(), emitter, List.of(), "observe",
                    List.of(new CoreAttachment(image.mimeType(), image.bytes(), filename)));
        } catch (Exception ex) {
            log.warn("Aria observation failed to start: {}", ex.getMessage(), ex);
            if (run != null) {
                try {
                    run.setStatus(RunStatus.FAILED);
                    run.setErrorMessage(RunErrorText.bound("Observation startup failed: " + ex.getMessage()));
                    runRepository.save(run);
                } catch (Exception ignored) {
                    // best-effort
                }
            }
            sendErrorSilent(emitter, AriaStreamService.errorPayloadJson(
                    "Aria observation failed: " + ex.getMessage(), run, ex.getMessage()));
            try {
                emitter.complete();
            } catch (Exception ignored) {
                // best-effort
            }
        }
    }

    private String resolveConversationId(String requested) {
        if (requested != null && !requested.isBlank()) {
            return requested;
        }
        return runRepository.findAll().stream()
                .filter(r -> r.getConversationId() != null && !r.getConversationId().isBlank())
                .max(Comparator.comparing(Run::getCreatedAt))
                .map(Run::getConversationId)
                .orElseGet(() -> UUID.randomUUID().toString());
    }

    private void sendErrorSilent(SseEmitter emitter, String json) {
        try {
            emitter.send(SseEmitter.event().name("error").data(json));
        } catch (Exception ignored) {
            // best-effort
        }
    }
}
```

- [ ] **Step 5: Create `AriaObserveController`**

```java
package io.aria.conductor.aria.controller;

import io.aria.conductor.agent.service.SystemConfigService;
import io.aria.conductor.aria.dto.AriaObserveRequest;
import io.aria.conductor.aria.service.AriaObservationValidator;
import io.aria.conductor.aria.service.AriaObserveService;
import io.aria.conductor.execution.security.OperatorAuthorityResolver;
import io.aria.conductor.execution.security.OperatorSessionService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;
import java.util.Optional;

/**
 * The glasses' one entry point: an observation becomes an Aria turn. Every
 * pre-stream refusal is JSON with its own status, so the phone never has to
 * read an SSE error for a request that never became a run.
 */
@RestController
@RequestMapping("/api/v1/aria")
public class AriaObserveController {

    private final AriaObserveService observeService;
    private final OperatorAuthorityResolver operatorAuthority;
    private final SystemConfigService systemConfigService;

    public AriaObserveController(AriaObserveService observeService,
                                 OperatorAuthorityResolver operatorAuthority,
                                 SystemConfigService systemConfigService) {
        this.observeService = observeService;
        this.operatorAuthority = operatorAuthority;
        this.systemConfigService = systemConfigService;
    }

    @PostMapping(value = "/observe", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<?> observe(@RequestBody(required = false) AriaObserveRequest request,
                                     HttpServletRequest httpRequest) {
        try {
            operatorAuthority.resolveOperator(httpRequest, true);
        } catch (OperatorSessionService.ForbiddenMutationException e) {
            return error(HttpStatus.FORBIDDEN, e.getMessage());
        } catch (SecurityException e) {
            return error(HttpStatus.UNAUTHORIZED, e.getMessage());
        }

        AriaObservationValidator.Result validated =
                AriaObservationValidator.validate(request, httpRequest.getContentLengthLong());
        if (validated.isRejected()) {
            AriaObservationValidator.Rejection rejection = validated.rejection();
            return error(rejection.status(), rejection.message());
        }

        Optional<AriaObservationValidator.Rejection> preflight = observeService.preflight(request);
        if (preflight.isPresent()) {
            return error(preflight.get().status(), preflight.get().message());
        }

        long sseTimeoutMs = systemConfigService.getLong(
                "aria.sse.timeout.ms", 600_000L, 30_000L, 3_600_000L);
        SseEmitter emitter = new SseEmitter(sseTimeoutMs);
        observeService.stream(request, validated.image(), emitter);
        return ResponseEntity.ok().contentType(MediaType.TEXT_EVENT_STREAM).body(emitter);
    }

    private static ResponseEntity<Map<String, String>> error(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(Map.of("error", message == null ? "error" : message));
    }
}
```

- [ ] **Step 6: Run tests to verify they pass**

Run: `cd /Users/happy/Documents/project/aria-conductor && bash scripts/mvn-lane.sh inner act-aria -Dtest="AriaObserveServiceTest,AriaObserveControllerTest"`
Expected: PASS. If the controller test's async assertion is flaky, assert `status().isOk()` only and keep the async-context completion in a `finally` block; do not delete the completion.

- [ ] **Step 7: Commit**

```bash
git add agent-control-tower/act-aria/src/main/java/io/aria/conductor/aria/service/AriaObserveService.java \
        agent-control-tower/act-aria/src/main/java/io/aria/conductor/aria/controller/AriaObserveController.java \
        agent-control-tower/act-aria/src/test/java/io/aria/conductor/aria/service/AriaObserveServiceTest.java \
        agent-control-tower/act-aria/src/test/java/io/aria/conductor/aria/controller/AriaObserveControllerTest.java
git commit -m "feat(aria): add the operator-gated observe endpoint and its run wiring"
```

---

### Task 6: The `ar_render` tool, catalog entry and glasses prompt

**Files:**
- Create: `agent-control-tower/act-mcp/src/main/java/io/aria/conductor/mcp/tools/ArRenderTools.java`
- Modify: `agent-control-tower/act-mcp/src/main/java/io/aria/conductor/mcp/catalog/ToolCatalog.java:248-253` (insert before `get_latest_conversation`)
- Modify: `agent-control-tower/act-mcp/src/test/java/io/aria/conductor/mcp/McpToolInventory.java:44-45`
- Modify: `agent-control-tower/act-aria/src/main/java/io/aria/conductor/aria/prompt/AriaPromptCatalog.java:112-122` (insert before the Workflows heading)
- Test: `agent-control-tower/act-mcp/src/test/java/io/aria/conductor/mcp/tools/ArRenderToolsTest.java` (new)
- Test: `agent-control-tower/act-aria/src/test/java/io/aria/conductor/aria/prompt/AriaGlassesPromptTest.java` (new)

**Interfaces:**
- Produces: `ar_render` MCP tool with arguments `primitive`, `target` (`ArRenderTools.ArTarget`), `text`, `directionDeg`, `distanceM`, `durationS`, `anchor`, `confidence`.
- Produces: catalog descriptor `ar_render` as `WORKER_READ` with auto-approve true.
- Produces: the `[glasses observation]` policy block in `AriaPromptCatalog.POLICY`.

- [ ] **Step 1: Write the failing tool test**

```java
package io.aria.conductor.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.aria.conductor.execution.mcp.McpProperties;
import io.aria.conductor.execution.security.CredentialRedactor;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ArRenderToolsTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private final ArRenderTools tools = new ArRenderTools(new McpProperties());

    @Test
    void trackWithATrackIdIsAcknowledged() throws Exception {
        String result = tools.arRender("track",
                new ArRenderTools.ArTarget(7, null, null, null),
                "TV remote", null, null, null, null, null);

        JsonNode node = JSON.readTree(result);
        assertThat(node.path("ok").asBoolean()).isTrue();
        assertThat(node.path("data").path("primitive").asText()).isEqualTo("track");
    }

    @Test
    void anUnknownPrimitiveIsAValidationError() throws Exception {
        JsonNode node = JSON.readTree(tools.arRender("explode",
                new ArRenderTools.ArTarget(7, null, null, null),
                null, null, null, null, null, null));

        assertThat(node.path("ok").asBoolean()).isFalse();
        assertThat(node.path("errorType").asText()).isEqualTo("VALIDATION");
    }

    @Test
    void trackWithoutALocatableTargetIsAValidationError() throws Exception {
        JsonNode node = JSON.readTree(tools.arRender("track", null,
                null, null, null, null, null, null));

        assertThat(node.path("ok").asBoolean()).isFalse();
        assertThat(node.path("message").asText()).contains("requires target");
    }

    @Test
    void indicatorRequiresADirection() throws Exception {
        JsonNode node = JSON.readTree(tools.arRender("indicator", null,
                null, null, null, null, null, null));

        assertThat(node.path("ok").asBoolean()).isFalse();
        assertThat(node.path("message").asText()).contains("directionDeg");
    }

    @Test
    void timerRequiresAPositiveDuration() throws Exception {
        JsonNode node = JSON.readTree(tools.arRender("timer", null,
                null, null, null, 0.0, null, null));

        assertThat(node.path("ok").asBoolean()).isFalse();
        assertThat(node.path("message").asText()).contains("durationS");
    }

    @Test
    void anUnknownAnchorIsAValidationError() throws Exception {
        JsonNode node = JSON.readTree(tools.arRender("step", null,
                "Step 2", null, null, null, "world", null));

        assertThat(node.path("ok").asBoolean()).isFalse();
        assertThat(node.path("message").asText()).contains("anchor");
    }

    @Test
    void clearNeedsNoTarget() throws Exception {
        JsonNode node = JSON.readTree(tools.arRender("clear", null,
                null, null, null, null, null, null));

        assertThat(node.path("ok").asBoolean()).isTrue();
    }

    @Test
    void redactedRenderArgumentsRemainParseableJson() throws Exception {
        String args = "{\"primitive\":\"track\",\"target\":{\"trackId\":7},\"text\":\"TV remote\"}";

        JsonNode node = JSON.readTree(CredentialRedactor.redact(args));

        assertThat(node.path("primitive").asText()).isEqualTo("track");
        assertThat(node.path("target").path("trackId").asInt()).isEqualTo(7);
    }
}
```

Also create `AriaGlassesPromptTest`:

```java
package io.aria.conductor.aria.prompt;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AriaGlassesPromptTest {

    @Test
    void thePolicyCarriesTheGlassesRules() {
        assertThat(AriaPromptCatalog.POLICY)
                .contains("[glasses observation]")
                .contains("ar_render")
                .contains("Traditional Chinese")
                .contains("never invent a trackId");
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd /Users/happy/Documents/project/aria-conductor && bash scripts/mvn-lane.sh inner act-mcp -Dtest=ArRenderToolsTest`
Expected: compilation failure (`ArRenderTools` missing).

- [ ] **Step 3: Create `ArRenderTools`**

```java
package io.aria.conductor.mcp.tools;

import io.aria.conductor.execution.mcp.McpProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

/**
 * AR primitives for the paired glasses display. The tool changes no platform
 * state: it validates the instruction and returns the acknowledgment the tool
 * call already streams to the device.
 */
@Component
@RequiredArgsConstructor
public class ArRenderTools implements McpTool {

    private static final Set<String> PRIMITIVES = Set.of(
            "track", "arrow_to", "highlight", "label", "indicator", "step", "timer", "clear");
    private static final Set<String> TARGET_REQUIRED = Set.of(
            "track", "arrow_to", "highlight", "label");

    private final McpProperties mcpProperties;

    /** The locatable target of a render instruction; all fields are optional, one must resolve. */
    public record ArTarget(Integer trackId, String objectId, String label, List<Double> bbox) {
        boolean locatable() {
            return trackId != null
                    || (objectId != null && !objectId.isBlank())
                    || (label != null && !label.isBlank())
                    || (bbox != null && bbox.size() == 4);
        }
    }

    @Tool(name = "ar_render",
            description = "Render one AR primitive on the paired glasses display. primitive is one of track, arrow_to, highlight, label, indicator, step, timer, clear. target carries trackId/objectId/label/bbox; prefer the observation's trackId and never invent one. directionDeg is clockwise from the screen centre when anchor=screen, relative to magnetic north when anchor=heading. Confirm the ack before describing the overlay in prose.")
    public String arRender(
            @ToolParam(description = "track, arrow_to, highlight, label, indicator, step, timer or clear") String primitive,
            @ToolParam(description = "Target object; at least one of trackId, objectId, label or bbox", required = false) ArTarget target,
            @ToolParam(description = "Short display text", required = false) String text,
            @ToolParam(description = "Clockwise direction in degrees", required = false) Double directionDeg,
            @ToolParam(description = "Distance in metres", required = false) Double distanceM,
            @ToolParam(description = "Duration in seconds", required = false) Double durationS,
            @ToolParam(description = "screen or heading", required = false) String anchor,
            @ToolParam(description = "Confidence 0..1", required = false) Double confidence) {
        try {
            String render = primitive == null ? "" : primitive.trim();
            if (!PRIMITIVES.contains(render)) {
                return ToolResponses.error("VALIDATION",
                        "Unknown primitive '" + primitive + "'. Valid: track, arrow_to, highlight,"
                                + " label, indicator, step, timer, clear",
                        null, mcpProperties.isDebug());
            }
            if (TARGET_REQUIRED.contains(render) && (target == null || !target.locatable())) {
                return ToolResponses.error("VALIDATION",
                        "primitive '" + render + "' requires target.trackId, objectId, label or bbox",
                        null, mcpProperties.isDebug());
            }
            if ("indicator".equals(render) && directionDeg == null) {
                return ToolResponses.error("VALIDATION",
                        "primitive 'indicator' requires directionDeg", null, mcpProperties.isDebug());
            }
            if ("timer".equals(render) && (durationS == null || durationS <= 0)) {
                return ToolResponses.error("VALIDATION",
                        "primitive 'timer' requires durationS greater than 0",
                        null, mcpProperties.isDebug());
            }
            String resolvedAnchor = anchor == null || anchor.isBlank() ? "screen" : anchor.trim();
            if (!"screen".equals(resolvedAnchor) && !"heading".equals(resolvedAnchor)) {
                return ToolResponses.error("VALIDATION",
                        "anchor must be screen or heading", null, mcpProperties.isDebug());
            }
            return ToolResponses.ok(java.util.Map.of("ack", true, "primitive", render));
        } catch (Exception e) {
            return ToolResponses.error("AR_RENDER_FAILED", e.getMessage(), e, mcpProperties.isDebug());
        }
    }
}
```

- [ ] **Step 4: Register the tool in the catalog and the inventory**

In `ToolCatalog`, insert before the `get_latest_conversation` entry:

```java
            read("ar_render",
                    "Render one AR primitive on the paired glasses display. primitive is one of track, arrow_to, highlight, label, indicator, step, timer, clear. target carries trackId/objectId/label/bbox; prefer the observation's trackId and never invent one. directionDeg is clockwise from the screen centre when anchor=screen, relative to magnetic north when anchor=heading. It renders on the device and changes no platform state.",
                    true),
```

In `McpToolInventory`, extend the aria assistant line:

```java
            "get_latest_conversation", "get_conversation_timeline",
            "ar_render"));
```

- [ ] **Step 5: Add the glasses rules to the prompt**

In `AriaPromptCatalog.POLICY`, insert before `## Workflows (multi-agent orchestration)`:

```text
            IMPORTANT — glasses and AR:
            When the user turn begins with "[glasses observation]" you are speaking into a
            head-mounted display. Answer in Traditional Chinese written Cantonese, in at most
            two short sentences, and never more than five lines. The phone speaks it with a
            Cantonese (yue-HK) voice.
            Use ar_render for spatial guidance. Prefer the observation's trackId when it
            exists; never invent a trackId, objectId or bbox. If clientCapabilities has
            ar=false, do not call ar_render.
            Call ar_render before your text answer. One primitive per call; several calls in
            one turn are applied in order.
            If the object you need is not in the observation, ask for a closer snapshot or
            use a label with a bbox instead of guessing a location.

```

- [ ] **Step 6: Run tests to verify they pass**

Run:
```bash
cd /Users/happy/Documents/project/aria-conductor
bash scripts/mvn-lane.sh inner act-mcp -Dtest="ArRenderToolsTest,ToolCatalogParityTest,AriaPromptToolNamesTest"
bash scripts/mvn-lane.sh inner act-aria -Dtest=AriaGlassesPromptTest
```
Expected: PASS. `AriaPromptToolNamesTest` fails if the policy names a snake_case token that is not in the catalog — the only new one is `ar_render`, which is registered.

- [ ] **Step 7: Commit**

```bash
git add agent-control-tower/act-mcp/src/main/java/io/aria/conductor/mcp/tools/ArRenderTools.java \
        agent-control-tower/act-mcp/src/main/java/io/aria/conductor/mcp/catalog/ToolCatalog.java \
        agent-control-tower/act-mcp/src/test/java/io/aria/conductor/mcp/McpToolInventory.java \
        agent-control-tower/act-aria/src/main/java/io/aria/conductor/aria/prompt/AriaPromptCatalog.java \
        agent-control-tower/act-mcp/src/test/java/io/aria/conductor/mcp/tools/ArRenderToolsTest.java \
        agent-control-tower/act-aria/src/test/java/io/aria/conductor/aria/prompt/AriaGlassesPromptTest.java
git commit -m "feat(mcp): add the ar_render tool and the glasses prompt rules"
```

---

### Task 7: Upgrade the sandbox core to opencode 1.19.6

**Files:**
- Modify: `agent-control-tower/opencode-sandbox/Dockerfile:41-45`

**Interfaces:**
- Consumes: nothing from earlier tasks.
- Produces: the sandbox image bundles opencode 1.19.6; the API shape used by Task 1 is unchanged (verified: `FilePartInput` and the `data:` image branch are identical in 1.19.6).

- [ ] **Step 1: Update the pin**

Replace the comment and install line:

```dockerfile
# Version pinned to the latest stable verified for the image (npm view opencode-ai dist-tags ->
# latest 1.19.6). 1.19.6 keeps the file-part API and the data: URL image path the observe
# endpoint uses.
ARG NPM_REGISTRY=https://registry.npmjs.org
RUN npm install -g opencode-ai@1.19.6 --registry=$NPM_REGISTRY
```

- [ ] **Step 2: Run the opencode regression subset**

Run:
```bash
cd /Users/happy/Documents/project/aria-conductor/agent-control-tower
mvn test -pl act-execution -Dtest="*OpenCode*"
cd /Users/happy/Documents/project/aria-conductor
bash scripts/mvn-lane.sh timing
```
Expected: PASS. A failure in the progress-pump timing lane means 1.19.6 changed the observed cadence; stop and report it rather than loosening the assertion.

- [ ] **Step 3: Rebuild the image (manual, requires a container runtime)**

```bash
cd /Users/happy/Documents/project/aria-conductor
docker build -t aria-conductor/opencode-sandbox:1.1 agent-control-tower/opencode-sandbox
docker run --rm aria-conductor/opencode-sandbox:1.1 opencode --version
```
Expected: the version prints `1.19.6`. If no container runtime is available, record the image rebuild as NOT VERIFIED and do not claim the live path works.

- [ ] **Step 4: Commit**

```bash
git add agent-control-tower/opencode-sandbox/Dockerfile
git commit -m "build(sandbox): pin opencode 1.19.6"
```

---

### Task 8: Full gates and evidence

**Files:**
- No source changes. This task produces the verification record the spec's acceptance section requires.

**Interfaces:**
- Consumes: every earlier task.

- [ ] **Step 1: Run the three module gates**

```bash
cd /Users/happy/Documents/project/aria-conductor
bash scripts/mvn-lane.sh gate act-execution
bash scripts/mvn-lane.sh gate act-mcp
bash scripts/mvn-lane.sh gate act-aria
```
Expected: PASS, coverage checks met (the lane deletes `target/jacoco.exec` first).

- [ ] **Step 2: Verify the privacy claims by inspection**

Run and confirm each line:

```bash
grep -rn "dataBase64" agent-control-tower/act-aria/src/main/java | grep -v "AriaObserveRequest"
grep -rn "getDataBase64" agent-control-tower/act-aria/src/main/java
grep -rn "log\." agent-control-tower/act-aria/src/main/java/io/aria/conductor/aria/service/AriaObserveService.java
```
Expected: the only references live in the validator (decode) and the DTO. No log call prints the request or image; `AriaObserveService` logs the conversation id and object count only.

- [ ] **Step 3: Verify the seed and run row never carry the image**

```bash
grep -c "jpg\|jpeg\|base64" agent-control-tower/act-aria/src/main/java/io/aria/conductor/aria/service/AriaObservationSeed.java || true
```
Expected: `0`. The seed test asserts the same behaviour at runtime.

- [ ] **Step 4: Record the acceptance status**

Report, with command output:

- Mocked observe produces `thinking`, an `ar_render` tool call, `message`, `done` — covered by `AriaObserveServiceTest` plus `ArRenderToolsTest`.
- Existing suites green — the three gate lines above.
- Live multimodal probe (one real JPEG through 1.19.6 and the active model) — run only when the model server is up; otherwise report NOT VERIFIED.
- Sandbox image rebuild — `opencode --version` output, or NOT VERIFIED.

- [ ] **Step 5: Commit any test-only fixes**

If a gate required a test fix, commit it separately:

```bash
git add <the fixed test files>
git commit -m "test: align <test> with the observe contract"
```

If nothing failed, there is no commit in this task.

---

## Self-Review

Spec coverage:

| Spec section | Task |
|---|---|
| 5.1 observe request, limits, status codes | 4 (validation), 5 (endpoint) |
| 5.1 seed format and caps | 4 |
| 5.2 SSE frames | 5 (reuses the existing stream) |
| 5.3 ar_render arguments, validation, ack, policy | 6 |
| 5.4 approvals | No server change; the spec documents the existing routes. No task adds code, intentionally. |
| 5.5 plain chat | Unchanged; no task. |
| 6 server changes, including the opencode pin | 1, 2, 3, 5, 6, 7 |
| 7 prompt policy | 6 |
| 8 privacy and retention | 2 (turn-only bytes), 4 (seed and toString), 8 (inspection) |
| 9 failure modes | 3 (qoder refusal), 5 (pre-stream statuses), 8 (probe status) |
| 10 testing | Every task plus 8 |
| 11 phases and acceptance | 8 |

Placeholder scan: no TBD/TODO; every code step carries the exact content.

Type consistency: `CoreAttachment(mimeType, bytes, filename)` is used identically in Tasks 1-5; `startRunStream(runId, emitter, initialMessages, intent, attachments)` in Tasks 2 and 5; `AriaObservationValidator.Result/ObservedImage/Rejection` in Tasks 4-5; `ArRenderTools.ArTarget(7, null, null, null)` in Task 6.

Known residual: the spec's HOST-mode opencode session retention is accepted, not addressed by any task.
