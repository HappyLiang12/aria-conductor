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
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * T50 F-2: the Clear writer window, on the shipped repositories (embedded H2).
 *
 * <p>{@code DELETE /api/v1/aria/conversations/{id}} reads the conversation's runs,
 * cancels the live ones through the VERIFIED stop path — which can take seconds — then
 * purges the transcript it read and detaches the runs. At {@code e2d9e134} the detach was
 * keyed to the run ids read before the cancels, so a run created for the same conversation
 * during that window kept its {@code conversationId} and {@code /latest} answered the
 * cleared conversation again: pressing Clear could be undone by a concurrent writer.
 *
 * <p>This test creates exactly that run from inside the window — the verified cancel of the
 * run that WAS read is what writes it — and asserts the conversation is still gone from
 * {@code /latest}. The late run was never read, so nothing may purge its record or its
 * transcript; that is asserted too, so the fix cannot buy the detach with an over-broad
 * purge. The pre-Clear read of {@code /latest} is the positive control: the conversation
 * really was the latest one before Clear.
 */
class ConversationClearWriterWindowTest extends DataJpaTestBase {

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
    void aRunCreatedWhileTheClearIsCancellingDoesNotResurrectTheConversation() {
        Instant base = Instant.parse("2026-11-12T00:00:00Z");
        Run live = runs.saveAndFlush(run("conv-cleared", RunStatus.RUNNING, base,
                "the turn the clear is stopping"));
        Run otherTurn = runs.saveAndFlush(run("conv-other", RunStatus.COMPLETED, base.minusSeconds(600),
                "an older conversation"));
        trajectories.saveAndFlush(trajectory(live.getId(), 1, "user",
                "the turn the clear is stopping", base));
        flushAndClear();

        // Positive control, before Clear: the conversation IS the latest one.
        ConversationSummary before = latest();
        assertThat(before.getConversationId()).isEqualTo("conv-cleared");
        assertThat(before.getRunCount()).isEqualTo(1);

        // The writer that wins the window: the verified stop of `live` is still running
        // when a new turn for the SAME conversation is created and committed. That is the
        // send the panel can issue today (its send path is guarded by busy/gated only).
        Instant inWindow = base.plusSeconds(30);
        Run[] late = new Run[1];
        doAnswer(invocation -> {
            late[0] = runs.saveAndFlush(run("conv-cleared", RunStatus.PENDING, inWindow,
                    "sent while the clear was in flight"));
            trajectories.saveAndFlush(trajectory(late[0].getId(), 1, "user",
                    "sent while the clear was in flight", inWindow));
            return null;
        }).when(runService).cancelRun(live.getId());

        ResponseEntity<Object> deleted = controller.deleteConversation("conv-cleared",
                new MockHttpServletRequest());
        assertThat(deleted.getStatusCode().value()).isEqualTo(204);
        flushAndClear();
        assertThat(late[0]).as("the window fixture is real: a run was created mid-clear").isNotNull();

        // The cleared conversation stays forgotten: a run it never read cannot keep the
        // link alive, or /latest resurrects a conversation the operator just cleared.
        ConversationSummary after = latest();
        assertThat(after.getConversationId())
                .as("a run created while Clear was cancelling must not resurrect the conversation")
                .isEqualTo("conv-other");
        assertThat(controller.getTimeline("conv-cleared").getBody()).isEmpty();

        // The late run was never read and never stopped: it is detached, but its record
        // and its transcript are untouched. Clear may not purge a run it never handled.
        Run lateRow = runs.findById(late[0].getId()).orElseThrow();
        assertThat(lateRow.getConversationId()).isNull();
        assertThat(lateRow.getStatus()).isEqualTo(RunStatus.PENDING);
        assertThat(trajectories.findByRunIdInOrderByTurnNumberAsc(List.of(late[0].getId())))
                .as("a run that was never read and never stopped keeps its transcript")
                .hasSize(1);

        // The run Clear did read keeps its record, loses the link, and loses its transcript.
        Run retained = runs.findById(live.getId()).orElseThrow();
        assertThat(retained.getConversationId()).isNull();
        assertThat(retained.getPromptSeed()).isEqualTo("the turn the clear is stopping");
        assertThat(trajectories.findByRunIdInOrderByTurnNumberAsc(List.of(live.getId())))
                .as("the transcript of the runs Clear read is purged")
                .isEmpty();
        assertThat(runs.findById(otherTurn.getId()).orElseThrow().getConversationId())
                .as("the detach names only the cleared conversation")
                .isEqualTo("conv-other");
    }

    private ConversationSummary latest() {
        ResponseEntity<ConversationSummary> latest = controller.getLatest();
        assertThat(latest.getStatusCode().value()).isEqualTo(200);
        return latest.getBody();
    }

    private Run run(String conversationId, RunStatus status, Instant createdAt, String promptSeed) {
        return Run.builder()
                .id(UUID.randomUUID())
                .agentId(UUID.randomUUID())
                .conversationId(conversationId)
                .status(status)
                .promptSeed(promptSeed)
                .createdAt(createdAt)
                .build();
    }

    private SessionTrajectory trajectory(UUID runId, int turn, String role, String content, Instant at) {
        return SessionTrajectory.builder()
                .id(UUID.randomUUID()).runId(runId).turnNumber(turn)
                .role(role).content(content).createdAt(at)
                .build();
    }
}
