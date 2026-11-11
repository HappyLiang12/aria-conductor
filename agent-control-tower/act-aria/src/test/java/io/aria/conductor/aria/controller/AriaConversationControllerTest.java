package io.aria.conductor.aria.controller;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.aria.conductor.agent.repository.AuditEventRepository;
import io.aria.conductor.agent.repository.RunRepository;
import io.aria.conductor.aria.dto.TimelineEntry;
import io.aria.conductor.aria.repository.ConversationRunRepository;
import io.aria.conductor.aria.service.AriaService;
import io.aria.conductor.common.exception.GlobalExceptionHandler;
import io.aria.conductor.common.model.Run;
import io.aria.conductor.common.model.RunStatus;
import io.aria.conductor.common.model.SessionTrajectory;
import io.aria.conductor.common.model.ToolCall;
import io.aria.conductor.common.model.ToolCallStatus;
import io.aria.conductor.common.security.ActorPrincipal;
import io.aria.conductor.execution.repository.SessionTrajectoryRepository;
import io.aria.conductor.execution.repository.ToolCallRepository;
import io.aria.conductor.execution.security.OperatorAuthorityResolver;
import io.aria.conductor.execution.security.OperatorSessionService;
import io.aria.conductor.execution.telemetry.RunTelemetryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AriaConversationControllerTest {

    private MockMvc mockMvc;
    private final RunRepository runRepository = mock(RunRepository.class);
    private final ConversationRunRepository conversationRunRepository =
            mock(ConversationRunRepository.class);
    private final SessionTrajectoryRepository trajectoryRepository = mock(SessionTrajectoryRepository.class);
    private final AuditEventRepository auditEventRepository = mock(AuditEventRepository.class);
    private final ToolCallRepository toolCallRepository = mock(ToolCallRepository.class);
    private final AriaService ariaService = mock(AriaService.class);
    private final io.aria.conductor.agent.service.RunService runService =
            mock(io.aria.conductor.agent.service.RunService.class);
    private final OperatorAuthorityResolver operatorAuthority = mock(OperatorAuthorityResolver.class);

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new AriaConversationController(
                runRepository, conversationRunRepository, trajectoryRepository, auditEventRepository,
                toolCallRepository, ariaService, runService, operatorAuthority))
                .setControllerAdvice(new GlobalExceptionHandler(new MockEnvironment()))
                .build();
        // The operator path is the default: every existing case below is the local operator.
        lenient().when(operatorAuthority.resolveOperator(any(), anyBoolean()))
                .thenReturn(ActorPrincipal.operator(null));
    }

    private Run run(String conversationId, RunStatus status, Instant createdAt) {
        return Run.builder()
                .id(UUID.randomUUID())
                .conversationId(conversationId)
                .status(status)
                .createdAt(createdAt)
                .build();
    }

    @Test
    void latest_returns204WhenNoRunsExist() throws Exception {
        when(runRepository.findAll()).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/aria/conversations/latest"))
                .andExpect(status().isNoContent());
    }

    @Test
    void latest_ignoresRunsWithoutConversationId() throws Exception {
        when(runRepository.findAll()).thenReturn(List.of(
                run(null, RunStatus.COMPLETED, Instant.now()),
                run("  ", RunStatus.COMPLETED, Instant.now())));

        mockMvc.perform(get("/api/v1/aria/conversations/latest"))
                .andExpect(status().isNoContent());
    }

    @Test
    void latest_picksMostRecentConversationAndCountsItsRuns() throws Exception {
        Instant now = Instant.parse("2026-01-10T10:00:00Z");
        Run older = run("conv-old", RunStatus.COMPLETED, now.minusSeconds(3600));
        Run newer = run("conv-new", RunStatus.COMPLETED, now);
        when(runRepository.findAll()).thenReturn(List.of(older, newer));
        when(runRepository.findByConversationIdOrderByCreatedAtAsc("conv-new"))
                .thenReturn(List.of(newer, run("conv-new", RunStatus.COMPLETED, now.minusSeconds(60))));

        mockMvc.perform(get("/api/v1/aria/conversations/latest"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.conversationId").value("conv-new"))
                .andExpect(jsonPath("$.runCount").value(2));
    }

    @Test
    void timeline_returnsEmptyListForUnknownConversation() throws Exception {
        when(runRepository.findByConversationIdOrderByCreatedAtAsc("nope")).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/aria/conversations/nope"))
                .andExpect(status().isOk())
                .andExpect(content().json("[]"));

        verify(trajectoryRepository, never()).findByRunIdInOrderByTurnNumberAsc(any());
    }

    @Test
    void timeline_ordersEntriesByRunCreationTime() throws Exception {
        Instant now = Instant.parse("2026-01-10T10:00:00Z");
        Run firstRun = run("conv-1", RunStatus.COMPLETED, now.minusSeconds(600));
        Run secondRun = run("conv-1", RunStatus.COMPLETED, now);
        when(runRepository.findByConversationIdOrderByCreatedAtAsc("conv-1"))
                .thenReturn(List.of(firstRun, secondRun));
        // repository returns them interleaved; the controller must re-sort by run createdAt
        when(trajectoryRepository.findByRunIdInOrderByTurnNumberAsc(
                List.of(firstRun.getId(), secondRun.getId())))
                .thenReturn(List.of(
                        trajectory(secondRun.getId(), 1, "user", "second question"),
                        trajectory(firstRun.getId(), 1, "user", "first question"),
                        trajectory(firstRun.getId(), 2, "assistant", "first answer")));

        mockMvc.perform(get("/api/v1/aria/conversations/conv-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0].content").value("first question"))
                .andExpect(jsonPath("$[1].content").value("first answer"))
                .andExpect(jsonPath("$[2].content").value("second question"))
                .andExpect(jsonPath("$[0].role").value("user"))
                .andExpect(jsonPath("$[1].role").value("assistant"))
                .andExpect(jsonPath("$[2].runId").value(secondRun.getId().toString()));
    }

    @Test
    void timeline_appendsSyntheticErrorEntryForFailedRun() throws Exception {
        Instant base = Instant.parse("2026-01-10T10:00:00Z");
        Run completedRun = run("conv-1", RunStatus.COMPLETED, base);
        Run failedRun = Run.builder()
                .id(UUID.randomUUID())
                .conversationId("conv-1")
                .status(RunStatus.FAILED)
                .createdAt(base.plusSeconds(60))
                .completedAt(base.plusSeconds(90))
                .errorMessage("Workspace upload failed for sandbox x: Failed to connect")
                .promptSeed("what next")
                .build();
        when(runRepository.findByConversationIdOrderByCreatedAtAsc("conv-1"))
                .thenReturn(List.of(completedRun, failedRun));
        when(trajectoryRepository.findByRunIdInOrderByTurnNumberAsc(
                List.of(completedRun.getId(), failedRun.getId())))
                .thenReturn(List.of(
                        trajectory(completedRun.getId(), 1, "user", "first question"),
                        trajectory(completedRun.getId(), 2, "assistant", "first answer"),
                        trajectory(failedRun.getId(), 1, "user", "second question")));

        String body = mockMvc.perform(get("/api/v1/aria/conversations/conv-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(4))
                .andExpect(jsonPath("$[0].content").value("first question"))
                .andExpect(jsonPath("$[1].content").value("first answer"))
                .andExpect(jsonPath("$[2].content").value("second question"))
                .andExpect(jsonPath("$[3].role").value("assistant"))
                .andExpect(jsonPath("$[3].content").value(
                        "回合執行失敗：Workspace upload failed for sandbox x: Failed to connect"))
                .andExpect(jsonPath("$[3].error").value(true))
                .andExpect(jsonPath("$[3].retryPrompt").value("what next"))
                .andExpect(jsonPath("$[3].runId").value(failedRun.getId().toString()))
                .andReturn().getResponse().getContentAsString();

        List<TimelineEntry> timeline = timelineFrom(body);
        assertThat(timeline.get(3).getTimestamp()).isEqualTo(base.plusSeconds(90));
    }

    @Test
    void timeline_usesFallbackContentWhenFailedRunHasNoErrorMessage() throws Exception {
        Instant base = Instant.parse("2026-01-10T10:00:00Z");
        Run failedRun = Run.builder()
                .id(UUID.randomUUID())
                .conversationId("conv-2")
                .status(RunStatus.FAILED)
                .createdAt(base)
                .updatedAt(base.plusSeconds(30))
                .build();
        when(runRepository.findByConversationIdOrderByCreatedAtAsc("conv-2"))
                .thenReturn(List.of(failedRun));
        when(trajectoryRepository.findByRunIdInOrderByTurnNumberAsc(List.of(failedRun.getId())))
                .thenReturn(List.of());

        String body = mockMvc.perform(get("/api/v1/aria/conversations/conv-2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].role").value("assistant"))
                .andExpect(jsonPath("$[0].content").value("回合執行失敗：原因不明"))
                .andExpect(jsonPath("$[0].error").value(true))
                .andReturn().getResponse().getContentAsString();

        List<TimelineEntry> timeline = timelineFrom(body);
        assertThat(timeline.get(0).getTimestamp()).isEqualTo(base.plusSeconds(30));
    }

    @Test
    void timeline_addsNoSyntheticEntryForCompletedRun() throws Exception {
        Instant base = Instant.parse("2026-01-10T10:00:00Z");
        Run completedRun = run("conv-3", RunStatus.COMPLETED, base);
        when(runRepository.findByConversationIdOrderByCreatedAtAsc("conv-3"))
                .thenReturn(List.of(completedRun));
        when(trajectoryRepository.findByRunIdInOrderByTurnNumberAsc(List.of(completedRun.getId())))
                .thenReturn(List.of(
                        trajectory(completedRun.getId(), 1, "user", "hello"),
                        trajectory(completedRun.getId(), 2, "assistant", "hi")));

        mockMvc.perform(get("/api/v1/aria/conversations/conv-3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].error").value(false))
                .andExpect(jsonPath("$[1].error").value(false));
    }

    @Test
    void timeline_placesSyntheticErrorEntryBeforeLaterRunsEntries() throws Exception {
        Instant base = Instant.parse("2026-01-10T10:00:00Z");
        Run failedRun = Run.builder()
                .id(UUID.randomUUID())
                .conversationId("conv-order")
                .status(RunStatus.FAILED)
                .createdAt(base)
                .completedAt(base.plusSeconds(30))
                .errorMessage("boom")
                .promptSeed("try again")
                .build();
        Run laterRun = run("conv-order", RunStatus.COMPLETED, base.plusSeconds(600));
        when(runRepository.findByConversationIdOrderByCreatedAtAsc("conv-order"))
                .thenReturn(List.of(failedRun, laterRun));
        when(trajectoryRepository.findByRunIdInOrderByTurnNumberAsc(
                List.of(failedRun.getId(), laterRun.getId())))
                .thenReturn(List.of(
                        trajectory(failedRun.getId(), 1, "user", "question that killed the run"),
                        trajectory(laterRun.getId(), 1, "user", "new question"),
                        trajectory(laterRun.getId(), 2, "assistant", "new answer")));

        mockMvc.perform(get("/api/v1/aria/conversations/conv-order"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(4))
                .andExpect(jsonPath("$[0].content").value("question that killed the run"))
                .andExpect(jsonPath("$[1].content").value("回合執行失敗：boom"))
                .andExpect(jsonPath("$[1].error").value(true))
                .andExpect(jsonPath("$[1].retryPrompt").value("try again"))
                .andExpect(jsonPath("$[1].runId").value(failedRun.getId().toString()))
                .andExpect(jsonPath("$[2].content").value("new question"))
                .andExpect(jsonPath("$[3].content").value("new answer"));
    }

    @Test
    void timeline_keepsErrorMessageOfExactly300CharsUnchanged() throws Exception {
        Instant base = Instant.parse("2026-01-10T10:00:00Z");
        String message = "x".repeat(300);
        Run failedRun = Run.builder()
                .id(UUID.randomUUID())
                .conversationId("conv-300")
                .status(RunStatus.FAILED)
                .createdAt(base)
                .completedAt(base.plusSeconds(10))
                .errorMessage(message)
                .build();
        when(runRepository.findByConversationIdOrderByCreatedAtAsc("conv-300"))
                .thenReturn(List.of(failedRun));
        when(trajectoryRepository.findByRunIdInOrderByTurnNumberAsc(List.of(failedRun.getId())))
                .thenReturn(List.of());

        mockMvc.perform(get("/api/v1/aria/conversations/conv-300"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].content").value("回合執行失敗：" + message));
    }

    @Test
    void timeline_clipsErrorMessageLongerThan300Chars() throws Exception {
        Instant base = Instant.parse("2026-01-10T10:00:00Z");
        String message = "y".repeat(301);
        Run failedRun = Run.builder()
                .id(UUID.randomUUID())
                .conversationId("conv-301")
                .status(RunStatus.FAILED)
                .createdAt(base)
                .completedAt(base.plusSeconds(10))
                .errorMessage(message)
                .build();
        when(runRepository.findByConversationIdOrderByCreatedAtAsc("conv-301"))
                .thenReturn(List.of(failedRun));
        when(trajectoryRepository.findByRunIdInOrderByTurnNumberAsc(List.of(failedRun.getId())))
                .thenReturn(List.of());

        mockMvc.perform(get("/api/v1/aria/conversations/conv-301"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].content").value("回合執行失敗：" + message.substring(0, 300)));
    }

    @Test
    void timeline_usesFallbackContentWhenFailedRunHasBlankErrorMessage() throws Exception {
        Instant base = Instant.parse("2026-01-10T10:00:00Z");
        Run failedRun = Run.builder()
                .id(UUID.randomUUID())
                .conversationId("conv-blank")
                .status(RunStatus.FAILED)
                .createdAt(base)
                .completedAt(base.plusSeconds(10))
                .errorMessage("   ")
                .build();
        when(runRepository.findByConversationIdOrderByCreatedAtAsc("conv-blank"))
                .thenReturn(List.of(failedRun));
        when(trajectoryRepository.findByRunIdInOrderByTurnNumberAsc(List.of(failedRun.getId())))
                .thenReturn(List.of());

        mockMvc.perform(get("/api/v1/aria/conversations/conv-blank"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].content").value("回合執行失敗：原因不明"))
                .andExpect(jsonPath("$[0].error").value(true));
    }

    @Test
    void timeline_carriesTheToolStatusAndToolCallIdOfEachRestoredToolRow() throws Exception {
        Instant base = Instant.parse("2026-01-10T10:00:00Z");
        Run run = run("conv-tools", RunStatus.COMPLETED, base);
        when(runRepository.findByConversationIdOrderByCreatedAtAsc("conv-tools"))
                .thenReturn(List.of(run));
        when(trajectoryRepository.findByRunIdInOrderByTurnNumberAsc(List.of(run.getId())))
                .thenReturn(List.of(
                        trajectory(run.getId(), 1, "tool", "tool_call: bash {\"cmd\":\"rm -rf /\"}", "call-a"),
                        trajectory(run.getId(), 2, "tool", "denied by policy", "call-a"),
                        trajectory(run.getId(), 3, "tool", "tool_call: list_agents {}", "call-b"),
                        trajectory(run.getId(), 4, "tool", "{\"ok\":true}", "call-b")));
        when(toolCallRepository.findByRunId(run.getId())).thenReturn(List.of(
                toolCall(run.getId(), "call-a", ToolCallStatus.DENIED),
                toolCall(run.getId(), "call-b", ToolCallStatus.COMPLETED)));

        mockMvc.perform(get("/api/v1/aria/conversations/conv-tools"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(4))
                .andExpect(jsonPath("$[0].toolCallId").value("call-a"))
                .andExpect(jsonPath("$[0].toolStatus").value("DENIED"))
                .andExpect(jsonPath("$[1].toolCallId").value("call-a"))
                .andExpect(jsonPath("$[1].toolStatus").value("DENIED"))
                .andExpect(jsonPath("$[2].toolCallId").value("call-b"))
                .andExpect(jsonPath("$[2].toolStatus").value("COMPLETED"))
                .andExpect(jsonPath("$[3].toolCallId").value("call-b"))
                .andExpect(jsonPath("$[3].toolStatus").value("COMPLETED"));
    }

    @Test
    void timeline_reportsAnInFlightStatusVerbatimInsteadOfCallingItASuccess() throws Exception {
        Instant base = Instant.parse("2026-01-10T10:00:00Z");
        Run run = run("conv-running", RunStatus.RUNNING, base);
        when(runRepository.findByConversationIdOrderByCreatedAtAsc("conv-running"))
                .thenReturn(List.of(run));
        when(trajectoryRepository.findByRunIdInOrderByTurnNumberAsc(List.of(run.getId())))
                .thenReturn(List.of(
                        trajectory(run.getId(), 1, "tool", "tool_call: bash {\"cmd\":\"sleep 60\"}", "call-c")));
        when(toolCallRepository.findByRunId(run.getId())).thenReturn(List.of(
                toolCall(run.getId(), "call-c", ToolCallStatus.EXECUTING)));

        mockMvc.perform(get("/api/v1/aria/conversations/conv-running"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].toolStatus").value("EXECUTING"))
                .andExpect(jsonPath("$[0].toolCallId").value("call-c"));
    }

    @Test
    void timeline_omitsBothFieldsForAToolRowWithoutAToolCallId() throws Exception {
        Instant base = Instant.parse("2026-01-10T10:00:00Z");
        Run run = run("conv-anon", RunStatus.COMPLETED, base);
        when(runRepository.findByConversationIdOrderByCreatedAtAsc("conv-anon"))
                .thenReturn(List.of(run));
        when(trajectoryRepository.findByRunIdInOrderByTurnNumberAsc(List.of(run.getId())))
                .thenReturn(List.of(
                        trajectory(run.getId(), 1, "tool", "tool_call: bash {}", "  "),
                        trajectory(run.getId(), 2, "tool", "some output", null)));
        when(toolCallRepository.findByRunId(run.getId())).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/aria/conversations/conv-anon"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].toolStatus").doesNotExist())
                .andExpect(jsonPath("$[0].toolCallId").doesNotExist())
                .andExpect(jsonPath("$[1].toolStatus").doesNotExist())
                .andExpect(jsonPath("$[1].toolCallId").doesNotExist());
    }

    @Test
    void timeline_omitsTheStatusRatherThanGuessingItWhenNoToolCallRowExists() throws Exception {
        Instant base = Instant.parse("2026-01-10T10:00:00Z");
        Run run = run("conv-nocalls", RunStatus.COMPLETED, base);
        when(runRepository.findByConversationIdOrderByCreatedAtAsc("conv-nocalls"))
                .thenReturn(List.of(run));
        when(trajectoryRepository.findByRunIdInOrderByTurnNumberAsc(List.of(run.getId())))
                .thenReturn(List.of(
                        trajectory(run.getId(), 1, "tool", "tool_call: bash {}", "call-d"),
                        trajectory(run.getId(), 2, "tool", "output", "call-d")));
        when(toolCallRepository.findByRunId(run.getId())).thenReturn(List.of());

        // The id is the trajectory row's own column, so it stands on its own; the status
        // is only ever read off a tool_calls row, and a missing one is not a success.
        mockMvc.perform(get("/api/v1/aria/conversations/conv-nocalls"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].toolStatus").doesNotExist())
                .andExpect(jsonPath("$[0].toolCallId").value("call-d"))
                .andExpect(jsonPath("$[1].toolStatus").doesNotExist());
    }

    @Test
    void delete_refusesAWorkerCredentialAndPurgesNothing() throws Exception {
        when(operatorAuthority.resolveOperator(any(), anyBoolean()))
                .thenThrow(new OperatorSessionService.ForbiddenMutationException("Operator authority required"));

        mockMvc.perform(delete("/api/v1/aria/conversations/conv-1")
                        .header("Authorization", "Bearer worker-run-token"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value(
                        "Operator authority required: deleting a conversation is an operator decision. "
                                + "Deleting a conversation cancels its runs and destroys its history; do it "
                                + "from the dashboard as the local operator."));

        // Before the first read: a refused caller cannot even enumerate the conversation's runs,
        // let alone cancel one or destroy its history.
        verify(runRepository, never()).findByConversationIdOrderByCreatedAtAsc(any());
        verify(runService, never()).cancelRun(any());
        verify(runRepository, never()).save(any(Run.class));
        verify(toolCallRepository, never()).deleteByRunIdIn(any());
        verify(trajectoryRepository, never()).deleteByRunIdIn(any());
        verify(auditEventRepository, never()).deleteByConversationId(any());
        verify(conversationRunRepository, never()).detachFromConversation(any());
    }

    @Test
    void delete_returns404WhenConversationHasNoRuns() throws Exception {
        when(runRepository.findByConversationIdOrderByCreatedAtAsc("ghost")).thenReturn(List.of());

        mockMvc.perform(delete("/api/v1/aria/conversations/ghost"))
                .andExpect(status().isNotFound());

        verify(toolCallRepository, never()).deleteByRunIdIn(any());
        verify(conversationRunRepository, never()).detachFromConversation(any());
    }

    @Test
    void delete_cancelsActiveRunsAndPurgesChildData() throws Exception {
        Run running = run("conv-1", RunStatus.RUNNING, Instant.now());
        Run pending = run("conv-1", RunStatus.PENDING, Instant.now());
        Run completed = run("conv-1", RunStatus.COMPLETED, Instant.now());
        when(runRepository.findByConversationIdOrderByCreatedAtAsc("conv-1"))
                .thenReturn(List.of(running, pending, completed));

        mockMvc.perform(delete("/api/v1/aria/conversations/conv-1"))
                .andExpect(status().isNoContent());

        // The active runs go through the verified cancel path; the delete never
        // writes CANCELLED directly (I3).
        verify(runService).cancelRun(running.getId());
        verify(runService).cancelRun(pending.getId());
        verify(runService, never()).cancelRun(completed.getId());
        verify(runRepository, never()).save(any(Run.class));

        List<UUID> allIds = List.of(running.getId(), pending.getId(), completed.getId());
        verify(toolCallRepository).deleteByRunIdIn(allIds);
        verify(trajectoryRepository).deleteByRunIdIn(allIds);
        verify(auditEventRepository).deleteByConversationId("conv-1");
        // The conversation link is cleared for every run, so /latest and the timeline can no
        // longer resolve this conversation (the end-to-end effect is pinned on the real
        // repositories in ConversationClearContractTest).
        verify(conversationRunRepository).detachFromConversation(allIds);
    }

    @Test
    void delete_whenAVerifiedCancelIsRefused_returns409AndPurgesNothing() throws Exception {
        Run running = run("conv-1", RunStatus.RUNNING, Instant.now());
        Run pending = run("conv-1", RunStatus.PENDING, Instant.now());
        when(runRepository.findByConversationIdOrderByCreatedAtAsc("conv-1"))
                .thenReturn(List.of(running, pending));
        String reason = "Run " + pending.getId()
                + " was not stopped: core reported RUNNING (verified=false)";
        when(runService.cancelRun(pending.getId()))
                .thenThrow(new IllegalStateException(reason));

        mockMvc.perform(delete("/api/v1/aria/conversations/conv-1"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(reason));

        // The refusal aborts the delete before any child data is purged: nothing
        // was relabelled and no history was destroyed.
        verify(runService).cancelRun(running.getId());
        verify(runService).cancelRun(pending.getId());
        verify(runRepository, never()).save(any(Run.class));
        verify(toolCallRepository, never()).deleteByRunIdIn(any());
        verify(trajectoryRepository, never()).deleteByRunIdIn(any());
        verify(auditEventRepository, never()).deleteByConversationId(any());
        verify(conversationRunRepository, never()).detachFromConversation(any());
    }

    @Test
    void synthesize_returnsComposedPromptForTheLatestGroupWhenBodyIsAbsent() throws Exception {
        when(ariaService.composeSynthesisPrompt("conv-1", null))
                .thenReturn(Optional.of("COMPOSED PROMPT"));

        mockMvc.perform(post("/api/v1/aria/conversations/conv-1/synthesize"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.prompt").value("COMPOSED PROMPT"));

        verify(ariaService).composeSynthesisPrompt("conv-1", null);
    }

    @Test
    void synthesize_treatsAnEmptyObjectBodyAsNoExplicitGroup() throws Exception {
        when(ariaService.composeSynthesisPrompt("conv-1", null))
                .thenReturn(Optional.of("COMPOSED PROMPT"));

        mockMvc.perform(post("/api/v1/aria/conversations/conv-1/synthesize")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.prompt").value("COMPOSED PROMPT"));

        verify(ariaService).composeSynthesisPrompt("conv-1", null);
    }

    @Test
    void synthesize_passesTheExplicitDispatchedByRunIdFromTheBody() throws Exception {
        UUID groupId = UUID.randomUUID();
        when(ariaService.composeSynthesisPrompt("conv-1", groupId))
                .thenReturn(Optional.of("COMPOSED PROMPT"));

        mockMvc.perform(post("/api/v1/aria/conversations/conv-1/synthesize")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"dispatchedByRunId\":\"" + groupId + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.prompt").value("COMPOSED PROMPT"));

        verify(ariaService).composeSynthesisPrompt("conv-1", groupId);
    }

    @Test
    void synthesize_returns404WhenNoDispatchGroupResolves() throws Exception {
        when(ariaService.composeSynthesisPrompt("ghost", null)).thenReturn(Optional.empty());

        mockMvc.perform(post("/api/v1/aria/conversations/ghost/synthesize"))
                .andExpect(status().isNotFound());
    }

    @Test
    void synthesize_returns400WhenDispatchedByRunIdIsNotAUuid() throws Exception {
        mockMvc.perform(post("/api/v1/aria/conversations/conv-1/synthesize")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"dispatchedByRunId\":\"not-a-uuid\"}"))
                .andExpect(status().isBadRequest());

        verify(ariaService, never()).composeSynthesisPrompt(any(), any());
    }

    private SessionTrajectory trajectory(UUID runId, int turn, String role, String content) {
        return SessionTrajectory.builder()
                .id(UUID.randomUUID()).runId(runId).turnNumber(turn)
                .role(role).content(content).createdAt(Instant.now())
                .build();
    }

    private SessionTrajectory trajectory(UUID runId, int turn, String role, String content, String toolCallId) {
        SessionTrajectory row = trajectory(runId, turn, role, content);
        row.setToolCallId(toolCallId);
        return row;
    }

    /** A persisted tool call, keyed by the same derivation the telemetry service writes. */
    private ToolCall toolCall(UUID runId, String rawToolCallId, ToolCallStatus status) {
        return ToolCall.builder()
                .id(RunTelemetryService.toolCallRowId(runId, rawToolCallId))
                .runId(runId)
                .toolName("bash")
                .status(status)
                .createdAt(Instant.now())
                .build();
    }

    /** Deserialize the timeline response; standalone MockMvc writes Instants as epoch seconds. */
    private List<TimelineEntry> timelineFrom(String body) throws Exception {
        ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        return objectMapper.readValue(body, new TypeReference<List<TimelineEntry>>() {});
    }
}
