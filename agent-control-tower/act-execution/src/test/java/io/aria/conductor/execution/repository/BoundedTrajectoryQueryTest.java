package io.aria.conductor.execution.repository;

import io.aria.conductor.common.model.SessionTrajectory;
import io.aria.conductor.test.DataJpaTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P6: a run's transcript is loaded with a bounded query. A run with more turns than
 * the bound must yield exactly the 200 newest turns, newest first -- the oldest turns
 * fall out of the bound, and another run's rows never leak in. Spec P6 bounds the
 * *last* 200 turns, so an ascending query would keep the wrong end.
 */
class BoundedTrajectoryQueryTest extends DataJpaTestBase {

    private static final int BOUND = 200;

    @Autowired
    SessionTrajectoryRepository trajectories;

    @Test
    void returnsAtMostTheTwoHundredNewestTurnsNewestFirst() {
        UUID runId = UUID.randomUUID();
        UUID otherRunId = UUID.randomUUID();
        for (int i = 0; i < 250; i++) {
            trajectories.saveAndFlush(trajectory(runId, i));
        }
        trajectories.saveAndFlush(trajectory(otherRunId, 0));
        flushAndClear();

        List<SessionTrajectory> rows = trajectories.findTop200ByRunIdOrderByTurnNumberDesc(runId);

        assertThat(rows).hasSize(BOUND);
        assertThat(rows.get(0).getTurnNumber()).as("newest first")
                .isEqualTo(249);
        assertThat(rows.get(BOUND - 1).getTurnNumber()).as("the oldest 50 turns are out of the bound")
                .isEqualTo(50);
        assertThat(rows).allMatch(row -> runId.equals(row.getRunId()));

        // What the history loader renders: the same page reversed into chronological order.
        List<SessionTrajectory> chronological = rows.reversed();
        assertThat(chronological).extracting(SessionTrajectory::getTurnNumber)
                .startsWith(50, 51)
                .endsWith(248, 249);
    }

    @Test
    void aShortRunIsReturnedInFullNewestFirst() {
        UUID runId = UUID.randomUUID();
        for (int i = 0; i < 3; i++) {
            trajectories.saveAndFlush(trajectory(runId, i));
        }
        flushAndClear();

        assertThat(trajectories.findTop200ByRunIdOrderByTurnNumberDesc(runId))
                .extracting(SessionTrajectory::getTurnNumber)
                .containsExactly(2, 1, 0);
    }

    private SessionTrajectory trajectory(UUID runId, int turnNumber) {
        return SessionTrajectory.builder()
                .id(UUID.randomUUID())
                .runId(runId)
                .turnNumber(turnNumber)
                .role(turnNumber % 2 == 0 ? "user" : "assistant")
                .content("turn " + turnNumber)
                .createdAt(Instant.now())
                .build();
    }
}
