package io.aria.conductor.app.e2e;

import io.aria.conductor.execution.llm.LlmMessage;
import io.aria.conductor.execution.llm.LlmRequest;
import io.aria.conductor.execution.llm.LlmToolCall;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Match-key contract of {@link DeterministicLlmClient} (Task 16, fix round 1).
 *
 * <p>Lives next to the class under test in the harness package on purpose: this
 * JUnit-bearing class is a live probe that the packaging keeps test-framework
 * bytecode off the harness classpath -- the distribution pins the harness class
 * files individually instead of globbing the package, and the packaging test
 * pins the exact shipped set.
 *
 * <p>Every request that differs from the registered value in any field --
 * {@code maxTokens}, {@code temperature}, the tool definitions, or a message's
 * {@code toolCallId}/{@code toolCalls} -- must throw instead of silently
 * reusing the registered response. Before the fix the client's key rendered
 * only model plus {@code role:content}, so the drifted requests below matched
 * the registered response and this test failed (RED).
 */
class DeterministicLlmClientTest {

    private static final String MODEL = "qoder-pro";

    private static final LlmToolCall READ_FILE_CALL =
            new LlmToolCall("call-1", "read_file", "{\"path\":\"a.txt\"}");

    private static final List<LlmMessage> CONVERSATION = List.of(
            LlmMessage.system("You are a deterministic test agent."),
            LlmMessage.user("Read a.txt"),
            LlmMessage.assistant("", List.of(READ_FILE_CALL)),
            LlmMessage.tool("file contents", "call-1"));

    /** The tool definitions the engine binds for the registered conversation. */
    private static List<Map<String, Object>> readFileTools() {
        return List.of(Map.of(
                "type", "function",
                "function", Map.of(
                        "name", "read_file",
                        "description", "Read a file",
                        "parameters", Map.of("type", "object"))));
    }

    private static LlmRequest registeredRequest() {
        return LlmRequest.withTools(MODEL, CONVERSATION, 4096, 0.7, readFileTools());
    }

    @Test
    void identicalRequestValuesMatchEvenWhenBuiltAsSeparateInstances() {
        DeterministicLlmClient client = new DeterministicLlmClient();
        client.registerExact(registeredRequest(), "file contents");

        // A structurally identical request (tool maps rebuilt in fresh instances) matches.
        LlmRequest replay = LlmRequest.withTools(MODEL, CONVERSATION, 4096, 0.7, readFileTools());

        assertThat(client.complete(replay).content()).isEqualTo("file contents");
        assertThat(client.callCount()).isEqualTo(1);
    }

    @Test
    void everyOtherFieldOfTheRequestIsPartOfTheMatchKey() {
        DeterministicLlmClient client = new DeterministicLlmClient();
        client.registerExact(registeredRequest(), "file contents");

        // One field changes per case; every case must fail loudly instead of matching.
        List<LlmRequest> drifted = List.of(
                LlmRequest.withTools(MODEL, CONVERSATION, 2048, 0.7, readFileTools()),
                LlmRequest.withTools(MODEL, CONVERSATION, 4096, 0.2, readFileTools()),
                LlmRequest.withTools(MODEL, CONVERSATION, 4096, 0.7, List.of()),
                LlmRequest.withTools(MODEL, List.of(
                        CONVERSATION.get(0), CONVERSATION.get(1), CONVERSATION.get(2),
                        LlmMessage.tool("file contents", "call-2")), 4096, 0.7, readFileTools()),
                LlmRequest.withTools(MODEL, List.of(
                        CONVERSATION.get(0), CONVERSATION.get(1),
                        LlmMessage.assistant("", List.of(
                                new LlmToolCall("call-9", "read_file", "{\"path\":\"a.txt\"}"))),
                        CONVERSATION.get(3)), 4096, 0.7, readFileTools()));

        for (LlmRequest request : drifted) {
            assertThatThrownBy(() -> client.complete(request))
                    .as("a request that drifted from the registered value must throw, not match")
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("no exact scenario response is registered")
                    .hasMessageContaining("Unmatched request:");
        }
        // The failed calls are recorded too, so a test can see exactly what was attempted.
        assertThat(client.callCount()).isEqualTo(drifted.size());
    }
}
