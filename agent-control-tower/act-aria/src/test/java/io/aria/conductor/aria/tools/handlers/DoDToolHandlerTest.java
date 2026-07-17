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
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DoDToolHandlerTest {

    @Mock
    private DoDService dodService;

    @InjectMocks
    private DoDToolHandler handler;

    @Test
    void initDodShouldReturnRecord() {
        DoDRecord record = DoDRecord.builder()
                .id("dod-1")
                .taskId("task-1")
                .taskType("story")
                .currentStage("dev")
                .overallStatus("IN_PROGRESS")
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();
        when(dodService.init("task-1", "story")).thenReturn(record);

        String result = handler.execute(Map.of(
                "toolName", "init_dod",
                "taskId", "task-1",
                "taskType", "story"
        ));

        assertTrue(result.contains("DoD initialized"));
        assertTrue(result.contains("task-1"));
        assertTrue(result.contains("dev"));
        verify(dodService).init("task-1", "story");
    }

    @Test
    void initDodMissingTaskIdShouldReturnError() {
        String result = handler.execute(Map.of(
                "toolName", "init_dod",
                "taskType", "story"
        ));

        assertTrue(result.startsWith("Error"));
        verifyNoInteractions(dodService);
    }

    @Test
    void submitDodReviewShouldCallService() {
        DoDRecord record = DoDRecord.builder()
                .id("dod-1")
                .taskId("task-1")
                .currentStage("qa")
                .overallStatus("IN_PROGRESS")
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();
        when(dodService.review(eq("task-1"), eq("user-1"), any(), eq(true), any(), any()))
                .thenReturn(record);

        String result = handler.execute(Map.of(
                "toolName", "submit_dod_review",
                "taskId", "task-1",
                "reviewerId", "user-1",
                "passed", "true",
                "evidence", "logs attached",
                "comment", "looks good"
        ));

        assertTrue(result.contains("qa"));
        verify(dodService).review(eq("task-1"), eq("user-1"), isNull(), eq(true),
                eq("logs attached"), eq("looks good"));
    }

    @Test
    void submitDodReviewMissingTaskIdShouldReturnError() {
        String result = handler.execute(Map.of(
                "toolName", "submit_dod_review",
                "reviewerId", "user-1",
                "passed", "true"
        ));

        assertTrue(result.startsWith("Error"));
        verifyNoInteractions(dodService);
    }

    @Test
    void submitDodReviewMissingReviewerIdShouldReturnError() {
        String result = handler.execute(Map.of(
                "toolName", "submit_dod_review",
                "taskId", "task-1",
                "passed", "true"
        ));

        assertTrue(result.startsWith("Error"));
        verifyNoInteractions(dodService);
    }

    @Test
    void submitDodReviewMissingPassedShouldReturnError() {
        String result = handler.execute(Map.of(
                "toolName", "submit_dod_review",
                "taskId", "task-1",
                "reviewerId", "user-1"
        ));

        assertTrue(result.startsWith("Error"));
        verifyNoInteractions(dodService);
    }



    @Test
    void getDodStatusMissingTaskIdShouldReturnError() {
        String result = handler.execute(Map.of("toolName", "get_dod_status"));

        assertTrue(result.startsWith("Error"));
        verifyNoInteractions(dodService);
    }

    @Test
    void unknownToolShouldReturnError() {
        String result = handler.execute(Map.of("toolName", "nonexistent_tool"));

        assertTrue(result.startsWith("Error"));
    }
}
