package io.aria.conductor.aria.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.aria.conductor.agent.repository.AuditEventRepository;
import io.aria.conductor.agent.repository.RunRepository;
import io.aria.conductor.aria.controller.AriaConversationController;
import io.aria.conductor.common.exception.GlobalExceptionHandler;
import io.aria.conductor.common.model.Run;
import io.aria.conductor.common.model.RunStatus;
import io.aria.conductor.execution.repository.SessionTrajectoryRepository;
import io.aria.conductor.execution.repository.ToolCallRepository;
import io.aria.conductor.execution.security.OperatorAuthorityResolver;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * A failed turn with no recorded error message is told twice: live, as the SSE
 * {@code error} payload's {@code reason}, and after a reload, as the synthetic
 * failed-turn entry the timeline rebuilds. The panel dedupes those two tellings by
 * matching the reason text, so the two producers must arrive at the same string by
 * construction. This test pins them to each other rather than to a literal, so it
 * fails if either side invents its own fallback again.
 */
class FailureReasonParityTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Instant BASE = Instant.parse("2026-01-10T10:00:00Z");

    @Test
    void aBlankErrorMessageYieldsOneReasonForTheLiveAndRestoredTelling() throws Exception {
        UUID runId = UUID.randomUUID();
        Run failed = Run.builder()
                .id(runId)
                .conversationId("conv-blank")
                .status(RunStatus.FAILED)
                .createdAt(BASE)
                .completedAt(BASE.plusSeconds(5))
                .build();

        String livePayload = AriaStreamService.errorPayloadJson("Aria streaming failed: boom", failed, null);
        String liveReason = MAPPER.readTree(livePayload).get("reason").asText();

        String restoredContent = restoredTimelineContent(failed);

        assertThat(restoredContent).isEqualTo("回合執行失敗：" + liveReason);
    }

    /** The synthetic failed-turn entry the restore path builds for {@code run}. */
    private String restoredTimelineContent(Run run) throws Exception {
        RunRepository runRepository = mock(RunRepository.class);
        SessionTrajectoryRepository trajectoryRepository = mock(SessionTrajectoryRepository.class);
        ToolCallRepository toolCallRepository = mock(ToolCallRepository.class);
        when(runRepository.findByConversationIdOrderByCreatedAtAsc(run.getConversationId()))
                .thenReturn(List.of(run));
        when(trajectoryRepository.findByRunIdInOrderByTurnNumberAsc(List.of(run.getId())))
                .thenReturn(List.of());

        AriaConversationController controller = new AriaConversationController(
                runRepository, trajectoryRepository, mock(AuditEventRepository.class),
                toolCallRepository, mock(AriaService.class),
                mock(io.aria.conductor.agent.service.RunService.class),
                // T42 item 4 added the authority resolver for the destructive DELETE route. This
                // test exercises the timeline READ, which resolves no authority, so a bare mock is
                // enough and no stub is added: an unnecessary stub would hide a future gate on reads.
                mock(OperatorAuthorityResolver.class));
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler(new MockEnvironment()))
                .build();

        String body = mockMvc.perform(get("/api/v1/aria/conversations/" + run.getConversationId()))
                .andReturn().getResponse().getContentAsString();
        return MAPPER.readTree(body).get(0).get("content").asText();
    }
}
