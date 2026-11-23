package io.aria.conductor.aria.live;

import io.aria.conductor.agent.repository.RunRepository;
import io.aria.conductor.common.model.Run;
import io.aria.conductor.common.model.SessionTrajectory;
import io.aria.conductor.execution.repository.SessionTrajectoryRepository;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Context seeding reuses the exact query the conversation timeline reads (runs by
 * conversation, trajectories by run id and turn), keeps the last 20 user/assistant text
 * entries and is empty — never an exception — when the conversation is unknown.
 */
class LiveConversationContextTest {

    private final RunRepository runs = mock(RunRepository.class);
    private final SessionTrajectoryRepository trajectories = mock(SessionTrajectoryRepository.class);
    private final LiveConversationContext context = new LiveConversationContext(runs, trajectories);

    @Test
    void theLastTwentyUserAndAssistantTextsAreSeededInOrder() {
        UUID runId = UUID.randomUUID();
        when(runs.findByConversationIdOrderByCreatedAtAsc("conv-1"))
                .thenReturn(List.of(Run.builder().id(runId).conversationId("conv-1").build()));
        List<SessionTrajectory> rows = new ArrayList<>();
        int turn = 1;
        for (int message = 1; message <= 25; message++) {
            rows.add(row(runId, turn++, message % 2 == 1 ? "user" : "assistant", "message-" + message));
        }
        rows.add(row(runId, turn++, "tool", "tool output is not context"));
        rows.add(row(runId, turn, "assistant", "   "));
        when(trajectories.findByRunIdInOrderByTurnNumberAsc(List.of(runId))).thenReturn(rows);

        List<LiveContextSource.Entry> entries = context.context("conv-1");

        assertThat(entries).hasSize(20);
        assertThat(entries.get(0)).isEqualTo(new LiveContextSource.Entry("assistant", "message-6"));
        assertThat(entries.get(19)).isEqualTo(new LiveContextSource.Entry("user", "message-25"));
    }

    @Test
    void anUnknownConversationSeedsNothing() {
        when(runs.findByConversationIdOrderByCreatedAtAsc("unknown")).thenReturn(List.of());

        assertThat(context.context("unknown")).isEmpty();
    }

    @Test
    void aNullConversationIdSeedsNothing() {
        assertThat(context.context(null)).isEmpty();
        assertThat(context.context("  ")).isEmpty();
    }

    @Test
    void aRepositoryFailureSeedsNothingRatherThanBreakingTheHandshake() {
        when(runs.findByConversationIdOrderByCreatedAtAsc("broken"))
                .thenThrow(new IllegalStateException("database down"));

        assertThat(context.context("broken")).isEmpty();
    }

    private static SessionTrajectory row(UUID runId, int turn, String role, String content) {
        return SessionTrajectory.builder()
                .id(UUID.randomUUID())
                .runId(runId)
                .turnNumber(turn)
                .role(role)
                .content(content)
                .build();
    }
}
