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
