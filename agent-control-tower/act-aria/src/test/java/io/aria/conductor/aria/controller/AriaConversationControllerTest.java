package io.aria.conductor.aria.controller;

import io.aria.conductor.agent.repository.AuditEventRepository;
import io.aria.conductor.agent.repository.RunRepository;
import io.aria.conductor.common.model.Run;
import io.aria.conductor.common.model.RunStatus;
import io.aria.conductor.common.model.SessionTrajectory;
import io.aria.conductor.execution.repository.SessionTrajectoryRepository;
import io.aria.conductor.execution.repository.ToolCallRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AriaConversationControllerTest {

    private MockMvc mockMvc;
    private final RunRepository runRepository = mock(RunRepository.class);
    private final SessionTrajectoryRepository trajectoryRepository = mock(SessionTrajectoryRepository.class);
    private final AuditEventRepository auditEventRepository = mock(AuditEventRepository.class);
    private final ToolCallRepository toolCallRepository = mock(ToolCallRepository.class);

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new AriaConversationController(
                runRepository, trajectoryRepository, auditEventRepository, toolCallRepository)).build();
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

    private SessionTrajectory trajectory(UUID runId, int turn, String role, String content) {
        return SessionTrajectory.builder()
                .id(UUID.randomUUID()).runId(runId).turnNumber(turn)
                .role(role).content(content).createdAt(Instant.now())
                .build();
    }
}
