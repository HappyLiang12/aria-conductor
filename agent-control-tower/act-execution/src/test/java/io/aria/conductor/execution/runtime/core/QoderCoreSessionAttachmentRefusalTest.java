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
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The Qoder core's prompt path is text-only, so an attachment-carrying turn is
 * refused instead of being silently dropped: the prompt fails with a
 * {@link TaskExecutionException} of cause {@code PROVIDER_ERROR} and the bridge
 * is never asked to run anything.
 */
class QoderCoreSessionAttachmentRefusalTest {

    private static final UUID RUN = UUID.randomUUID();
    private static final UUID AGENT = UUID.randomUUID();

    @Test
    void anAttachmentIsRefusedBeforeTheBridgeIsTouched() {
        QoderBridgeClient client = mock(QoderBridgeClient.class);
        when(client.sessionBoundTo(RUN)).thenReturn(new QoderBridgeClient.SessionView(
                RUN.toString(), "/workspace", "efficient", "efficient", "native-session-1", "ready",
                null, null));
        try (QoderCoreSession session = new QoderCoreSession(spec(), client, "efficient")) {
            // The constructor reads the launch-time binding; every call after it
            // must stay untouched for a refused turn.
            clearInvocations(client);
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
        return new ExecutionSpec(RUN, AGENT, "qoder", ExecutionMode.HOST,
                new AgentExecutionSettings("qoder", ExecutionMode.HOST, null, null, null),
                null, null, Instant.now().plusSeconds(60));
    }
}
