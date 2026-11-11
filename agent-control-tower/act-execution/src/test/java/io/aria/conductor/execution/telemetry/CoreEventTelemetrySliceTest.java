package io.aria.conductor.execution.telemetry;

import io.aria.conductor.agent.repository.RunRepository;
import io.aria.conductor.common.model.Run;
import io.aria.conductor.common.model.RunStatus;
import io.aria.conductor.common.model.SessionTrajectory;
import io.aria.conductor.common.model.ToolCall;
import io.aria.conductor.common.model.ToolCallStatus;
import io.aria.conductor.execution.repository.SessionTrajectoryRepository;
import io.aria.conductor.execution.repository.ToolCallRepository;
import io.aria.conductor.execution.runtime.CoreEvent;
import io.aria.conductor.test.DataJpaTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F17/F9: the exact bridge payloads, fed through the runtime's event-to-telemetry
 * adapter, land in the rows the product API already reads.
 *
 * <p>A real H2 slice rather than a mock: the claim is about persisted rows. The
 * event shapes are the ones packages/qoder-acp-bridge/src/tool-events.ts and
 * usage.ts emit (object input, capped string input marked " [truncated]", object
 * result, null counters, carried zero).
 */
class CoreEventTelemetrySliceTest extends DataJpaTestBase {

    private static final String TOOL_CALL_OBJECT_INPUT = """
            {"toolCallId":"call-obj","toolName":"Bash","kind":"bash","title":"git status",
             "input":{"command":"git status"},"inputBytes":22,"inputTruncated":false}
            """;

    private static final String TOOL_CALL_TRUNCATED_INPUT = """
            {"toolCallId":"call-big","toolName":"Read","kind":"read","title":"Read file",
             "input":"line one\\nline two [truncated]","inputBytes":16384,"inputTruncated":true}
            """;

    private static final String TOOL_RESULT_COMPLETED = """
            {"toolCallId":"call-obj","toolName":"Bash","status":"completed","isError":false,
             "result":"clean tree","resultBytes":10,"resultTruncated":false}
            """;

    private static final String TOOL_RESULT_OBJECT = """
            {"toolCallId":"call-mcp","toolName":"mcp__aria__list","status":"completed",
             "isError":false,"result":{"items":[]},"resultBytes":13,"resultTruncated":false}
            """;

    @Autowired private ToolCallRepository toolCalls;
    @Autowired private SessionTrajectoryRepository trajectories;
    @Autowired private RunRepository runs;

    private CoreEventTelemetry telemetry;
    private UUID runId;

    @BeforeEach
    void setUp() {
        telemetry = new CoreEventTelemetry(new RunTelemetryService(toolCalls, trajectories, runs));
        Run run = runs.save(Run.builder()
                .agentId(UUID.randomUUID())
                .status(RunStatus.RUNNING)
                .build());
        runId = run.getId();
    }

    private void deliver(String type, String payloadJson) {
        assertThat(telemetry.record(runId, new CoreEvent(type, runId, "session-1", null, payloadJson))).isTrue();
    }

    @Test
    void objectInputCallAndItsResultPersistTheRowTheToolCallEndpointReads() {
        deliver("tool.call", TOOL_CALL_OBJECT_INPUT);
        deliver("tool.result", TOOL_RESULT_COMPLETED);

        flushAndClear();
        List<ToolCall> calls = toolCalls.findByRunId(runId);
        assertThat(calls).hasSize(1);
        ToolCall call = calls.get(0);
        assertThat(call.getToolName()).isEqualTo("Bash");
        assertThat(call.getArguments()).isEqualTo("{\"command\":\"git status\"}");
        assertThat(call.getResult()).isEqualTo("clean tree");
        assertThat(call.getStatus()).isEqualTo(ToolCallStatus.COMPLETED);

        List<SessionTrajectory> rows = trajectories.findByRunIdOrderByTurnNumberAsc(runId);
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).getRole()).isEqualTo("tool");
        assertThat(rows.get(0).getContent()).contains("tool_call: Bash").contains("git status");
        assertThat(rows.get(1).getContent()).isEqualTo("clean tree");
    }

    @Test
    void truncatedStringInputSurvivesPersistenceWithItsMarker() {
        deliver("tool.call", TOOL_CALL_TRUNCATED_INPUT);

        flushAndClear();
        assertThat(toolCalls.findByRunId(runId).get(0).getArguments())
                .isEqualTo("line one\nline two [truncated]");
        assertThat(trajectories.findByRunIdOrderByTurnNumberAsc(runId).get(0).getContent())
                .endsWith("line two [truncated]");
    }

    @Test
    void objectResultIsPersistedAsCompactJson() {
        deliver("tool.result", TOOL_RESULT_OBJECT);

        flushAndClear();
        assertThat(toolCalls.findByRunId(runId).get(0).getResult()).isEqualTo("{\"items\":[]}");
    }

    @Test
    void reDeliveredCallEventUpdatesItsOwnRowInsteadOfDuplicatingTheAuditTrail() {
        deliver("tool.call", TOOL_CALL_OBJECT_INPUT);
        deliver("tool.call", TOOL_CALL_OBJECT_INPUT);

        flushAndClear();
        assertThat(toolCalls.findByRunId(runId)).hasSize(1);
        assertThat(trajectories.findByRunIdOrderByTurnNumberAsc(runId)).hasSize(1);
    }

    @Test
    void anUnknownUsagePayloadLeavesTheRunUsageUnknownWhileAnAllZeroBlockIsNotAMeasurement() {
        Run unobserved = runs.save(Run.builder().agentId(UUID.randomUUID()).status(RunStatus.RUNNING).build());
        telemetry.record(unobserved.getId(), new CoreEvent("usage", unobserved.getId(), "session-1", null,
                "{\"promptId\":5,\"inputTokens\":null,\"outputTokens\":null,\"totalTokens\":null,\"source\":\"usage\"}"));

        Run reported = runs.save(Run.builder().agentId(UUID.randomUUID()).status(RunStatus.RUNNING).build());
        telemetry.record(reported.getId(), new CoreEvent("usage", reported.getId(), "session-1", null,
                "{\"promptId\":6,\"inputTokens\":0,\"outputTokens\":0,\"totalTokens\":0,\"source\":\"quota\"}"));

        flushAndClear();
        Run stillUnknown = runs.findById(unobserved.getId()).orElseThrow();
        assertThat(stillUnknown.getInputTokens()).isNull();
        assertThat(stillUnknown.getOutputTokens()).isNull();
        assertThat(stillUnknown.getTotalTokensUsed()).isZero();

        // Task 7 / spec 5.4 reversed the earlier pin: an all-zero block is the pinned
        // CLI's placeholder shape (a real LLM call cannot consume zero input tokens), so
        // it is not a measurement. Nothing is written and the run stays NOT_REPORTED;
        // storing the zero would present an unmeasured cost.
        Run allZero = runs.findById(reported.getId()).orElseThrow();
        assertThat(allZero.getInputTokens()).isNull();
        assertThat(allZero.getOutputTokens()).isNull();
        assertThat(allZero.getTotalTokensUsed()).isZero();
    }

    @Test
    void reportedUsageIsWrittenToTheRunWithoutInventingTheAbsentTotal() {
        deliver("usage", "{\"promptId\":7,\"inputTokens\":120,\"outputTokens\":30,\"totalTokens\":null,"
                + "\"source\":\"usage\"}");

        flushAndClear();
        Run run = runs.findById(runId).orElseThrow();
        assertThat(run.getInputTokens()).isEqualTo(120L);
        assertThat(run.getOutputTokens()).isEqualTo(30L);
        assertThat(run.getTotalTokensUsed()).isEqualTo(150L);
    }

    @Test
    void malformedPayloadsRecordNothingAndNeverThrow() {
        assertThat(telemetry.record(runId, new CoreEvent("tool.call", runId, "session-1", null, "{")))
                .isFalse();
        assertThat(telemetry.record(runId, new CoreEvent("usage", runId, "session-1", null, "[]")))
                .isFalse();

        flushAndClear();
        assertThat(toolCalls.findByRunId(runId)).isEmpty();
        assertThat(trajectories.findByRunIdOrderByTurnNumberAsc(runId)).isEmpty();
        Run run = runs.findById(runId).orElseThrow();
        assertThat(run.getInputTokens()).isNull();
        assertThat(run.getTotalTokensUsed()).isZero();
    }

    @Test
    void aPermissionRequestForTheSameToolCallNeverCreatesASecondToolCallRow() {
        deliver("tool.call", TOOL_CALL_OBJECT_INPUT);
        assertThat(telemetry.record(runId, new CoreEvent("permission.request", runId, "session-1", "7", """
                {"requestId":7,"toolCallId":"call-obj","toolName":"Bash","input":{"command":"git status"}}
                """))).isFalse();

        flushAndClear();
        assertThat(toolCalls.findByRunId(runId)).hasSize(1);
    }
}
