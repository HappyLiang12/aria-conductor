package io.aria.conductor.aria.controller;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.aria.conductor.agent.repository.AuditEventRepository;
import io.aria.conductor.agent.repository.RunRepository;
import io.aria.conductor.aria.dto.TimelineEntry;
import io.aria.conductor.aria.service.AriaService;
import io.aria.conductor.common.model.Run;
import io.aria.conductor.common.model.RunStatus;
import io.aria.conductor.common.model.SessionTrajectory;
import io.aria.conductor.execution.repository.SessionTrajectoryRepository;
import io.aria.conductor.execution.repository.ToolCallRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
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
    private final SessionTrajectoryRepository trajectoryRepository = mock(SessionTrajectoryRepository.class);
    private final AuditEventRepository auditEventRepository = mock(AuditEventRepository.class);
    private final ToolCallRepository toolCallRepository = mock(ToolCallRepository.class);
    private final AriaService ariaService = mock(AriaService.class);

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new AriaConversationController(
                runRepository, trajectoryRepository, auditEventRepository, toolCallRepository,
                ariaService)).build();
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
    void delete_returns404WhenConversationHasNoRuns() throws Exception {
        when(runRepository.findByConversationIdOrderByCreatedAtAsc("ghost")).thenReturn(List.of());

        mockMvc.perform(delete("/api/v1/aria/conversations/ghost"))
                .andExpect(status().isNotFound());

        verify(toolCallRepository, never()).deleteByRunIdIn(any());
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

        // only the two active runs get soft-cancelled
        ArgumentCaptor<Run> captor = ArgumentCaptor.forClass(Run.class);
        verify(runRepository, org.mockito.Mockito.times(2)).save(captor.capture());
        assertThat(captor.getAllValues())
                .extracting(Run::getStatus)
                .containsOnly(RunStatus.CANCELLED);
        assertThat(captor.getAllValues())
                .extracting(Run::getId)
                .containsExactlyInAnyOrder(running.getId(), pending.getId());

        List<UUID> allIds = List.of(running.getId(), pending.getId(), completed.getId());
        verify(toolCallRepository).deleteByRunIdIn(allIds);
        verify(trajectoryRepository).deleteByRunIdIn(allIds);
        verify(auditEventRepository).deleteByConversationId("conv-1");
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

    /** Deserialize the timeline response; standalone MockMvc writes Instants as epoch seconds. */
    private List<TimelineEntry> timelineFrom(String body) throws Exception {
        ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        return objectMapper.readValue(body, new TypeReference<List<TimelineEntry>>() {});
    }
}
