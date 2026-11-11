package io.aria.conductor.agent.repository;

import io.aria.conductor.common.model.Run;
import io.aria.conductor.common.model.RunStatus;
import io.aria.conductor.test.DataJpaTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P6: conversation history is loaded with a bounded query, not a full-table read
 * followed by a {@code subList}. With more rows than the bound, the repository
 * must return exactly the 20 newest runs of the requested conversation, newest
 * first, and nothing from another conversation.
 */
class BoundedRunHistoryQueryTest extends DataJpaTestBase {

    private static final int BOUND = 20;

    @Autowired
    RunRepository runs;

    @Test
    void returnsAtMostTheTwentyNewestRuns() {
        Instant base = Instant.parse("2026-11-01T00:00:00Z");
        for (int i = 0; i < 25; i++) {
            runs.saveAndFlush(run("conv", base.plusSeconds(i)));
        }
        runs.saveAndFlush(run("other", base.plusSeconds(120)));
        flushAndClear();

        List<Run> history = runs.findTop20ByConversationIdOrderByCreatedAtDesc("conv");

        assertThat(history).hasSize(BOUND);
        assertThat(history).extracting(Run::getCreatedAt)
                .isSortedAccordingTo(Comparator.reverseOrder());
        assertThat(history.get(0).getCreatedAt()).as("newest first").isEqualTo(base.plusSeconds(24));
        assertThat(history.get(BOUND - 1).getCreatedAt()).as("the bound drops the oldest six")
                .isEqualTo(base.plusSeconds(5));
        assertThat(history).allMatch(run -> "conv".equals(run.getConversationId()));
    }

    @Test
    void aConversationWithoutRunsHasNoHistory() {
        runs.saveAndFlush(run("other", Instant.parse("2026-11-01T00:00:00Z")));
        flushAndClear();

        assertThat(runs.findTop20ByConversationIdOrderByCreatedAtDesc("conv")).isEmpty();
    }

    private Run run(String conversationId, Instant createdAt) {
        return Run.builder()
                .id(UUID.randomUUID())
                .agentId(UUID.randomUUID())
                .status(RunStatus.COMPLETED)
                .conversationId(conversationId)
                .createdAt(createdAt)
                .build();
    }
}
