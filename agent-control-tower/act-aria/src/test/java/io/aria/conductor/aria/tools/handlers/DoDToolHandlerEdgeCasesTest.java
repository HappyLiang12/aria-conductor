package io.aria.conductor.aria.tools.handlers;

import io.aria.conductor.execution.dod.DoDRecord;
import io.aria.conductor.execution.dod.DoDService;
import io.aria.conductor.execution.dod.dto.DoDStatusResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * get_dod_status rendering, passed-flag coercion and failure mapping of
 * {@link DoDToolHandler}; DoDToolHandlerTest only covers init/review basics.
 */
@ExtendWith(MockitoExtension.class)
class DoDToolHandlerEdgeCasesTest {

    @Mock private DoDService dodService;

    @InjectMocks
    private DoDToolHandler handler;

    private DoDRecord record(String stage) {
        return DoDRecord.builder()
                .id("dod-1").taskId("task-1").currentStage(stage)
                .overallStatus("IN_PROGRESS")
                .createdAt(Instant.now()).updatedAt(Instant.now())
                .build();
    }

    @Test
    void getDodStatus_rendersStageRollup() {
        DoDStatusResponse response = new DoDStatusResponse(
                "dod-1", "task-1", "story", "qa", "IN_PROGRESS",
                Instant.now(), Instant.now(),
                List.of(
                        new DoDStatusResponse.StageStatus("dev", true, "PASSED", 2, Instant.now()),
                        new DoDStatusResponse.StageStatus("qa", true, "PENDING", 0, null)),
                List.of(), 3);
        when(dodService.buildStatusResponse("task-1")).thenReturn(response);

        String result = handler.execute(Map.of(
                "toolName", "get_dod_status", "taskId", "task-1"));

        assertThat(result).contains("DoD status for task 'task-1'")
                .contains("Stage: qa")
                .contains("Overall Status: IN_PROGRESS")
                .contains("- dev: PASSED (2 reviews)")
                .contains("- qa: PENDING (0 reviews)")
                .contains("Evidence Count: 3");
    }

    @Test
    void getDodStatus_omitsStageSectionWhenNoStages() {
        DoDStatusResponse response = new DoDStatusResponse(
                "dod-1", "task-1", null, "dev", "IN_PROGRESS",
                Instant.now(), Instant.now(), List.of(), List.of(), 0);
        when(dodService.buildStatusResponse("task-1")).thenReturn(response);

        String result = handler.execute(Map.of(
                "toolName", "get_dod_status", "taskId", "task-1"));

        assertThat(result).doesNotContain("Stage Reviews:");
        assertThat(result).contains("Evidence Count: 0");
    }

    @Test
    void getDodStatus_missingTaskIdReturnsError() {
        String result = handler.execute(Map.of("toolName", "get_dod_status"));

        assertThat(result).startsWith("Error").contains("taskId");
        verifyNoInteractions(dodService);
    }

    @Test
    void submitDodReview_failedDecisionIsReported() {
        when(dodService.review("task-1", "rev-1", null, false, null, null))
                .thenReturn(record("dev"));

        String result = handler.execute(Map.of(
                "toolName", "submit_dod_review",
                "taskId", "task-1", "reviewerId", "rev-1", "passed", false));

        assertThat(result).contains("Decision: FAILED");
    }

    @Test
    void submitDodReview_coercesStringPassedValues() {
        when(dodService.review("task-1", "rev-1", null, true, null, null))
                .thenReturn(record("dev"));

        String result = handler.execute(Map.of(
                "toolName", "submit_dod_review",
                "taskId", "task-1", "reviewerId", "rev-1", "passed", "true"));

        verify(dodService).review("task-1", "rev-1", null, true, null, null);
        assertThat(result).contains("Decision: PASSED");
    }

    @Test
    void submitDodReview_nonBooleanPassedTypeTreatedAsMissing() {
        String result = handler.execute(Map.of(
                "toolName", "submit_dod_review",
                "taskId", "task-1", "reviewerId", "rev-1", "passed", 1));

        assertThat(result).startsWith("Error").contains("passed");
        verifyNoInteractions(dodService);
    }

    @Test
    void submitDodReview_blankOptionalFieldsAreNulledBeforeServiceCall() {
        when(dodService.review("task-1", "rev-1", null, true, null, "looks good"))
                .thenReturn(record("qa"));

        handler.execute(Map.of(
                "toolName", "submit_dod_review",
                "taskId", "task-1", "reviewerId", "rev-1", "passed", true,
                "reviewerName", "  ", "evidence", "", "comment", "looks good"));

        verify(dodService).review("task-1", "rev-1", null, true, null, "looks good");
    }

    @Test
    void initDod_blankTaskTypeIsNulled() {
        Map<String, Object> args = new HashMap<>();
        args.put("toolName", "init_dod");
        args.put("taskId", "task-1");
        args.put("taskType", "   ");
        when(dodService.init("task-1", null)).thenReturn(record("dev"));

        String result = handler.execute(args);

        verify(dodService).init("task-1", null);
        assertThat(result).contains("DoD initialized for task 'task-1'");
    }

    @Test
    void serviceExceptionIsMappedToErrorString() {
        when(dodService.buildStatusResponse("task-1"))
                .thenThrow(new IllegalStateException("no DoD record for task"));

        String result = handler.execute(Map.of(
                "toolName", "get_dod_status", "taskId", "task-1"));

        assertThat(result).isEqualTo("Error: no DoD record for task");
    }

    @Test
    void unknownToolReturnsError() {
        String result = handler.execute(Map.of("toolName", "close_dod"));

        assertThat(result).isEqualTo("Error: Unknown tool: close_dod");
    }
}
