package io.aria.conductor.agent.repository;

import io.aria.conductor.common.model.Run;
import io.aria.conductor.common.model.RunStatus;
import io.aria.conductor.test.DataJpaTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The conversation list aggregate behind GET /api/v1/aria/conversations:
 * one row per conversation, newest conversation first, and ids that are null
 * or blank are not conversations at all.
 */
class RunRepositoryConversationListIntegrationTest extends DataJpaTestBase {

    @Autowired
    RunRepository repository;

    private Run persistRun(String conversationId, String promptSeed, Instant createdAt) {
        Run run = Run.builder()
                .id(UUID.randomUUID())
                .agentId(UUID.randomUUID())
                .status(RunStatus.COMPLETED)
                .promptSeed(promptSeed)
                .conversationId(conversationId)
                .createdAt(createdAt)
                .build();
        entityManager.persist(run);
        return run;
    }

    @Test
    void aggregateGroupsByConversationCountsRunsAndOrdersByNewestActivity() {
        Instant base = Instant.parse("2026-11-01T10:00:00Z");
        persistRun("conv-a", "first", base);
        persistRun("conv-b", "second conversation", base.plusSeconds(60));
        persistRun("conv-a", "third", base.plusSeconds(120));

        flushAndClear();

        List<RunRepository.ConversationAggregate> rows =
                repository.findConversationAggregates(PageRequest.of(0, 10));

        assertThat(rows).extracting(RunRepository.ConversationAggregate::getConversationId)
                .containsExactly("conv-a", "conv-b");
        assertThat(rows.get(0).getRunCount()).isEqualTo(2);
        assertThat(rows.get(0).getLastMessageAt()).isEqualTo(base.plusSeconds(120));
        assertThat(rows.get(1).getRunCount()).isEqualTo(1);
        assertThat(rows.get(1).getLastMessageAt()).isEqualTo(base.plusSeconds(60));
    }

    @Test
    void aggregateExcludesNullAndBlankConversationIds() {
        Instant now = Instant.parse("2026-11-02T09:00:00Z");
        persistRun(null, "no conversation", now);
        persistRun("  ", "blank conversation", now.plusSeconds(1));
        persistRun("conv-real", "real", now.plusSeconds(2));

        flushAndClear();

        List<RunRepository.ConversationAggregate> rows =
                repository.findConversationAggregates(PageRequest.of(0, 10));

        assertThat(rows).extracting(RunRepository.ConversationAggregate::getConversationId)
                .containsExactly("conv-real");
    }

    @Test
    void aggregateAppliesThePageLimit() {
        Instant base = Instant.parse("2026-11-03T08:00:00Z");
        persistRun("conv-1", "one", base);
        persistRun("conv-2", "two", base.plusSeconds(1));
        persistRun("conv-3", "three", base.plusSeconds(2));

        flushAndClear();

        List<RunRepository.ConversationAggregate> rows =
                repository.findConversationAggregates(PageRequest.of(0, 2));

        assertThat(rows).extracting(RunRepository.ConversationAggregate::getConversationId)
                .containsExactly("conv-3", "conv-2");
    }

    @Test
    void firstSeedsPerConversationAreReadableInOneQuery() {
        Instant base = Instant.parse("2026-11-04T07:00:00Z");
        persistRun("conv-a", "older seed", base);
        persistRun("conv-a", "newer seed", base.plusSeconds(30));
        persistRun("conv-b", "other seed", base.plusSeconds(60));

        flushAndClear();

        List<Run> runs = repository.findByConversationIdInOrderByCreatedAtAsc(List.of("conv-a", "conv-b"));

        assertThat(runs).extracting(Run::getPromptSeed)
                .containsExactly("older seed", "newer seed", "other seed");
    }
}
