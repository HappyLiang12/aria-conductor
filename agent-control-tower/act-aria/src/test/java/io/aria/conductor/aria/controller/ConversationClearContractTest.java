package io.aria.conductor.aria.controller;

import io.aria.conductor.agent.repository.AuditEventRepository;
import io.aria.conductor.agent.repository.RunRepository;
import io.aria.conductor.aria.dto.ConversationSummary;
import io.aria.conductor.aria.repository.ConversationRunRepository;
import io.aria.conductor.aria.service.AriaService;
import io.aria.conductor.common.model.Run;
import io.aria.conductor.common.model.RunStatus;
import io.aria.conductor.common.model.SessionTrajectory;
import io.aria.conductor.common.security.ActorPrincipal;
import io.aria.conductor.execution.repository.SessionTrajectoryRepository;
import io.aria.conductor.execution.repository.ToolCallRepository;
import io.aria.conductor.execution.security.OperatorAuthorityResolver;
import io.aria.conductor.test.DataJpaTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The user-visible Clear contract, on the shipped repositories (embedded H2), not on
 * mocks: "Clear clears my history".
 *
 * <p>At {@code c902b2e5} the DELETE purged a conversation's tool calls, trajectories
 * and audit rows but left its Run rows, and {@code GET /latest} takes the newest run
 * that carries a {@code conversationId}. So after Clear, a reload adopted the cleared
 * conversation and drew an empty transcript under the old id. The e2e
 * ({@code aria-inflow-decisions.spec.ts:133-136}) asserts the contract this test pins.
 *
 * <p>The fix is the bounded one: the conversation's runs are detached
 * ({@code conversation_id = null}) rather than deleted, so the cleared conversation no
 * longer resolves anywhere while the run rows (the runs surface, reports, usage)
 * survive. That is why this test asserts BOTH sides: the conversation is gone from
 * {@code /latest} and its timeline, and the run row is still there, detached.
 */
class ConversationClearContractTest extends DataJpaTestBase {

    @Autowired
    private RunRepository runs;
    @Autowired
    private ConversationRunRepository conversationRuns;
    @Autowired
    private SessionTrajectoryRepository trajectories;
    @Autowired
    private ToolCallRepository toolCalls;
    @Autowired
    private AuditEventRepository auditEvents;

    private final AriaService ariaService = mock(AriaService.class);
    private final io.aria.conductor.agent.service.RunService runService =
            mock(io.aria.conductor.agent.service.RunService.class);
    private final OperatorAuthorityResolver operatorAuthority = mock(OperatorAuthorityResolver.class);

    private AriaConversationController controller;

    @BeforeEach
    void setUp() {
        controller = new AriaConversationController(runs, conversationRuns, trajectories, auditEvents,
                toolCalls, ariaService, runService, operatorAuthority);
        when(operatorAuthority.resolveOperator(any(), anyBoolean()))
                .thenReturn(ActorPrincipal.operator(null));
    }

    @Test
    void clearMakesTheConversationUnreachableWhileKeepingItsRunRecords() {
        Instant base = Instant.parse("2026-11-12T00:00:00Z");
        Run clearedTurn = runs.saveAndFlush(run("conv-cleared", RunStatus.COMPLETED, base,
                "the question I want forgotten", "the answer"));
        Run otherTurn = runs.saveAndFlush(run("conv-other", RunStatus.COMPLETED, base.minusSeconds(600),
                "an older conversation", "its answer"));
        trajectories.saveAndFlush(SessionTrajectory.builder()
                .id(UUID.randomUUID()).runId(clearedTurn.getId()).turnNumber(1)
                .role("user").content("the question I want forgotten").createdAt(base).build());
        flushAndClear();

        // Positive control, before Clear: the conversation IS the latest one and its
        // timeline is NOT empty. Without this, an empty timeline after Clear could just
        // mean the fixture never had one.
        ConversationSummary before = latest();
        assertThat(before.getConversationId()).isEqualTo("conv-cleared");
        assertThat(before.getRunCount()).isEqualTo(1);
        assertThat(controller.getTimeline("conv-cleared").getBody()).hasSize(1);

        ResponseEntity<Object> deleted = controller.deleteConversation("conv-cleared",
                new MockHttpServletRequest());
        assertThat(deleted.getStatusCode().value()).isEqualTo(204);
        flushAndClear();

        // The cleared conversation no longer resolves: not the latest, timeline empty.
        ConversationSummary after = latest();
        assertThat(after.getConversationId())
                .as("a cleared conversation must not come back as /latest")
                .isEqualTo("conv-other");
        assertThat(after.getRunCount()).isEqualTo(1);
        assertThat(controller.getTimeline("conv-cleared").getBody()).isEmpty();

        // The runs surface and reports keep the run: the record survives, only the
        // conversation link is forgotten. The other conversation is untouched.
        Run retained = runs.findById(clearedTurn.getId()).orElseThrow();
        assertThat(retained.getConversationId()).isNull();
        assertThat(retained.getStatus()).isEqualTo(RunStatus.COMPLETED);
        assertThat(retained.getPromptSeed()).isEqualTo("the question I want forgotten");
        assertThat(retained.getFinalOutput()).isEqualTo("the answer");
        assertThat(runs.findById(otherTurn.getId()).orElseThrow().getConversationId())
                .as("the detach names only the cleared conversation's runs")
                .isEqualTo("conv-other");
        assertThat(trajectories.findByRunIdInOrderByTurnNumberAsc(List.of(clearedTurn.getId())))
                .as("the transcript itself is purged")
                .isEmpty();
    }

    private ConversationSummary latest() {
        ResponseEntity<ConversationSummary> latest = controller.getLatest();
        assertThat(latest.getStatusCode().value()).isEqualTo(200);
        return latest.getBody();
    }

    private Run run(String conversationId, RunStatus status, Instant createdAt,
                    String promptSeed, String finalOutput) {
        return Run.builder()
                .id(UUID.randomUUID())
                .agentId(UUID.randomUUID())
                .conversationId(conversationId)
                .status(status)
                .promptSeed(promptSeed)
                .finalOutput(finalOutput)
                .createdAt(createdAt)
                .build();
    }
}
