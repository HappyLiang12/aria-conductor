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
                        .seq(1L).content("thinking").toolName(null).partId("prt_think")
                        .createdAt(Instant.parse("2026-09-06T00:00:00Z")).build(),
                RunProgressEventEntity.builder().id(UUID.randomUUID()).runId(RUN_ID)
                        .agentId(UUID.randomUUID()).iteration(1).kind("ANSWER")
                        .seq(2L).content("more").toolName(null).partId("prt_answer")
                        .createdAt(Instant.parse("2026-09-06T00:00:01Z")).build()));
        when(progressRepository.findByRunIdAndSeqAfterOrderBySeqAsc(RUN_ID, 0L)).thenReturn(List.of(
                RunProgressEventEntity.builder().id(UUID.randomUUID()).runId(RUN_ID)
                        .agentId(UUID.randomUUID()).iteration(1).kind("THINKING")
                        .seq(1L).content("thinking").toolName(null).partId("prt_think")
                        .createdAt(Instant.parse("2026-09-06T00:00:00Z")).build(),
                RunProgressEventEntity.builder().id(UUID.randomUUID()).runId(RUN_ID)
                        .agentId(UUID.randomUUID()).iteration(1).kind("ANSWER")
                        .seq(2L).content("more").toolName(null).partId("prt_answer")
                        .createdAt(Instant.parse("2026-09-06T00:00:01Z")).build()));
        when(progressRepository.findByRunIdAndSeqAfterOrderBySeqAsc(RUN_ID, 1L)).thenReturn(List.of(
                RunProgressEventEntity.builder().id(UUID.randomUUID()).runId(RUN_ID)
                        .agentId(UUID.randomUUID()).iteration(1).kind("ANSWER")
                        .seq(2L).content("more").toolName(null).partId("prt_answer")
                        .createdAt(Instant.parse("2026-09-06T00:00:01Z")).build()));
        // Issue #103: two observations of ONE growing part are two rows of one partId.
        // The endpoint replays both in seq order and does not collapse them — the
        // client merge is the single place the growth becomes one line.
        when(progressRepository.findByRunIdAndSeqAfterOrderBySeqAsc(RUN_ID, 2L)).thenReturn(List.of(
                RunProgressEventEntity.builder().id(UUID.randomUUID()).runId(RUN_ID)
                        .agentId(UUID.randomUUID()).iteration(1).kind("ANSWER")
                        .seq(3L).content("more of the same answer").toolName(null)
                        .partId("prt_answer")
                        .createdAt(Instant.parse("2026-09-06T00:00:02Z")).build()));
    }

    @Test
    void progressEndpoint_returnsAscendingList() throws Exception {
        mvc.perform(get("/api/v1/runs/" + RUN_ID + "/progress"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].kind").value("THINKING"))
                .andExpect(jsonPath("$[0].seq").value(1))
                .andExpect(jsonPath("$[0].partId").value("prt_think"))
                // Issue #103: the assistant's answer reaches the replay as its own
                // kind, so the drawer can render it apart from the thinking.
                .andExpect(jsonPath("$[1].kind").value("ANSWER"))
                .andExpect(jsonPath("$[1].seq").value(2))
                .andExpect(jsonPath("$[1].partId").value("prt_answer"));
    }

    @Test
    void progressEndpoint_afterSeqFilters() throws Exception {
        mvc.perform(get("/api/v1/runs/" + RUN_ID + "/progress").param("afterSeq", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].kind").value("ANSWER"))
                .andExpect(jsonPath("$[0].seq").value(2))
                .andExpect(jsonPath("$[0].partId").value("prt_answer"));
    }

    /**
     * The growth contract of {@code GET /runs/{id}/progress}: a later observation of
     * the same part is a SECOND row with the same partId and a greater seq, both
     * present, ordered. Collapsing here would leave the reopened drawer unable to show
     * what the live stream showed, so the endpoint stays the ordered log.
     */
    @Test
    void progressEndpoint_replaysEveryObservationOfOnePartInSeqOrder() throws Exception {
        mvc.perform(get("/api/v1/runs/" + RUN_ID + "/progress").param("afterSeq", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].partId").value("prt_answer"))
                .andExpect(jsonPath("$[0].seq").value(2));
        mvc.perform(get("/api/v1/runs/" + RUN_ID + "/progress").param("afterSeq", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].partId").value("prt_answer"))
                .andExpect(jsonPath("$[0].seq").value(3))
                .andExpect(jsonPath("$[0].content").value("more of the same answer"));
    }
}
