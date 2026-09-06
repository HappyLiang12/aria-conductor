package io.aria.conductor.agent.controller;

import io.aria.conductor.agent.service.RunService;
import io.aria.conductor.common.model.RunProgressEventEntity;
import io.aria.conductor.common.repository.RunProgressEventRepository;
import io.aria.conductor.test.WebMvcTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class RunProgressControllerTest extends WebMvcTestBase {

    private final RunService runService = mock(RunService.class);
    private final RunProgressEventRepository progressRepository = mock(RunProgressEventRepository.class);
    private final MockMvc mvc = mockMvcFor(new RunController(runService, progressRepository));

    private static final UUID RUN_ID = UUID.fromString("00000000-0000-0000-0000-00000000abcd");

    @BeforeEach
    void stub() {
        when(progressRepository.findByRunIdOrderBySeqAsc(RUN_ID)).thenReturn(List.of(
                RunProgressEventEntity.builder().id(UUID.randomUUID()).runId(RUN_ID)
                        .agentId(UUID.randomUUID()).iteration(1).kind("THINKING")
                        .seq(1L).content("thinking").toolName(null)
                        .createdAt(Instant.parse("2026-09-06T00:00:00Z")).build(),
                RunProgressEventEntity.builder().id(UUID.randomUUID()).runId(RUN_ID)
                        .agentId(UUID.randomUUID()).iteration(1).kind("THINKING")
                        .seq(2L).content("more").toolName(null)
                        .createdAt(Instant.parse("2026-09-06T00:00:01Z")).build()));
        when(progressRepository.findByRunIdAndSeqAfterOrderBySeqAsc(RUN_ID, 0L)).thenReturn(List.of(
                RunProgressEventEntity.builder().id(UUID.randomUUID()).runId(RUN_ID)
                        .agentId(UUID.randomUUID()).iteration(1).kind("THINKING")
                        .seq(1L).content("thinking").toolName(null)
                        .createdAt(Instant.parse("2026-09-06T00:00:00Z")).build(),
                RunProgressEventEntity.builder().id(UUID.randomUUID()).runId(RUN_ID)
                        .agentId(UUID.randomUUID()).iteration(1).kind("THINKING")
                        .seq(2L).content("more").toolName(null)
                        .createdAt(Instant.parse("2026-09-06T00:00:01Z")).build()));
        when(progressRepository.findByRunIdAndSeqAfterOrderBySeqAsc(RUN_ID, 1L)).thenReturn(List.of(
                RunProgressEventEntity.builder().id(UUID.randomUUID()).runId(RUN_ID)
                        .agentId(UUID.randomUUID()).iteration(1).kind("THINKING")
                        .seq(2L).content("more").toolName(null)
                        .createdAt(Instant.parse("2026-09-06T00:00:01Z")).build()));
    }

    @Test
    void progressEndpoint_returnsAscendingList() throws Exception {
        mvc.perform(get("/api/v1/runs/" + RUN_ID + "/progress"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].kind").value("THINKING"))
                .andExpect(jsonPath("$[0].seq").value(1))
                .andExpect(jsonPath("$[1].seq").value(2));
    }

    @Test
    void progressEndpoint_afterSeqFilters() throws Exception {
        mvc.perform(get("/api/v1/runs/" + RUN_ID + "/progress").param("afterSeq", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].seq").value(2));
    }
}
