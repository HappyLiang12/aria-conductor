package io.aria.conductor.mcp.tools;

import io.aria.conductor.execution.dod.DoDRecord;
import io.aria.conductor.execution.dod.DoDService;
import io.aria.conductor.execution.dod.dto.DoDStatusResponse;
import io.aria.conductor.execution.mcp.McpProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DoDToolsTest {

    @Mock DoDService dodService;
    McpProperties mcpProperties;
    DoDTools tools;

    @BeforeEach
    void setUp() {
        mcpProperties = new McpProperties();
        tools = new DoDTools(dodService, mcpProperties);
    }

    private DoDRecord record(String taskId, String stage, String overallStatus) {
        return DoDRecord.builder()
                .id("d1")
                .taskId(taskId)
                .currentStage(stage)
                .overallStatus(overallStatus)
                .build();
    }

    @Test
    void initDod_delegates() {
        when(dodService.init(eq("task-1"), eq("bug")))
                .thenReturn(record("task-1", "dev", "IN_PROGRESS"));

        String json = tools.initDod("task-1", "bug");

        assertThat(json).contains("\"ok\":true").contains("task-1").contains("dev");
    }

    @Test
    void initDod_blankTaskIdIsValidationErrorWithoutStack() {
        String json = tools.initDod(" ", null);

        assertThat(json).contains("\"errorType\":\"VALIDATION\"").contains("taskId is required");
        assertThat(json).doesNotContain("stackTrace");
    }

    @Test
    void submitDodReview_delegatesWithReviewerName() {
        when(dodService.review(eq("task-1"), eq("rev-1"), eq("Alice"), eq(true), eq("tests pass"), eq("lgtm")))
                .thenReturn(record("task-1", "qa", "IN_PROGRESS"));

        String json = tools.submitDodReview("task-1", "rev-1", true, "tests pass", "lgtm", "Alice");

        verify(dodService).review("task-1", "rev-1", "Alice", true, "tests pass", "lgtm");
        assertThat(json).contains("\"ok\":true").contains("qa");
    }

    @Test
    void submitDodReview_blankReviewerIdIsValidationError() {
        String json = tools.submitDodReview("task-1", " ", true, null, null, null);

        assertThat(json).contains("\"errorType\":\"VALIDATION\"").contains("reviewerId is required");
        assertThat(json).doesNotContain("stackTrace");
    }

    @Test
    void submitDodReview_completedWorkflowMapsConflictWithoutStack() {
        when(dodService.review(any(), any(), any(), anyBoolean(), any(), any()))
                .thenThrow(new IllegalStateException("DoD already completed for task: task-1"));

        String json = tools.submitDodReview("task-1", "rev-1", false, null, null, null);

        assertThat(json).contains("\"errorType\":\"CONFLICT\"");
        assertThat(json).doesNotContain("stackTrace");
    }

    @Test
    void getDodStatus_returnsAggregate() {
        DoDStatusResponse response = new DoDStatusResponse(
                "d1", "task-1", "bug", "qa", "IN_PROGRESS",
                Instant.now(), Instant.now(),
                List.of(new DoDStatusResponse.StageStatus("dev", true, "PASSED", 1, Instant.now())),
                List.of(), 2L);
        when(dodService.buildStatusResponse("task-1")).thenReturn(response);

        String json = tools.getDodStatus("task-1");

        assertThat(json).contains("\"ok\":true")
                .contains("\"currentStage\":\"qa\"")
                .contains("\"evidenceCount\":2");
    }

    @Test
    void getDodStatus_missingRecordMapsConflict() {
        when(dodService.buildStatusResponse("task-1"))
                .thenThrow(new IllegalStateException("No DoD record for task: task-1"));

        String json = tools.getDodStatus("task-1");

        assertThat(json).contains("\"errorType\":\"CONFLICT\"").contains("No DoD record");
        assertThat(json).doesNotContain("stackTrace");
    }
}
