package io.aria.conductor.memory.service;

import io.aria.conductor.common.context.ContextAdapter;
import io.aria.conductor.common.context.ContextMessage;
import io.aria.conductor.common.context.ContextRequest;
import io.aria.conductor.common.context.ContextSection;
import io.aria.conductor.common.model.Sensitivity;
import io.aria.conductor.memory.dto.MemoryEntityView;
import io.aria.conductor.memory.dto.MemoryFactView;
import io.aria.conductor.memory.dto.RecallQuery;
import io.aria.conductor.memory.dto.RecallResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The memory context contributor (spec §8.4 step 7, plan Lane B Task B-2): id {@code memory},
 * order 40, optional; it renders the ranked recall slice into one typed section with
 * provenance ids and a maximum sensitivity, and every failure mode — timeout, repository
 * exception, empty recall — degrades a section instead of throwing into the assembler.
 * <p>
 * The timeout is exercised with a controllable future, so the test asserts the degraded
 * section without waiting out the 200 ms budget.
 */
@ExtendWith(MockitoExtension.class)
class MemoryContextContributorTest {

    private static final Instant VALID_FROM = Instant.parse("2026-01-01T00:00:00Z");

    @Mock
    MemoryRecallService recall;

    @Mock
    ExecutorService executor;

    MemoryContextContributor contributor;

    @BeforeEach
    void setUp() {
        contributor = new MemoryContextContributor(recall, executor, 200);
    }

    @Test
    void contributorIdentityIsMemoryAtOrderFortyAndOptional() {
        assertThat(contributor.id()).isEqualTo("memory");
        assertThat(contributor.order()).isEqualTo(40);
        assertThat(contributor.required()).isFalse();
    }

    @Test
    void factsAreRenderedWithProvenanceAndTheHighestSensitivity() {
        submitReturns(CompletableFuture.completedFuture(new RecallResult(
                List.of(entity("entity-1", "PERSON", "Operator", Sensitivity.INTERNAL)),
                List.of(fact("fact-1", "entity-1", "timezone", "Asia/Hong_Kong", "OPERATOR",
                        Sensitivity.CONFIDENTIAL)),
                false)));

        List<ContextSection> sections = contributor.contribute(request("my timezone", "D:/repo", List.of()));

        assertThat(sections).hasSize(1);
        ContextSection section = sections.get(0);
        assertThat(section.id()).isEqualTo("memory");
        assertThat(section.title()).isEqualTo("Memory");
        assertThat(section.content())
                .contains("PERSON/Operator")
                .contains("timezone = Asia/Hong_Kong")
                .contains("OPERATOR")
                .contains("entity-1", "fact-1");
        assertThat(section.sourceIds()).containsExactly("entity-1", "fact-1");
        assertThat(section.sensitivity()).isEqualTo(Sensitivity.CONFIDENTIAL);
        assertThat(section.degradedReason()).isNull();
        assertThat(section.truncated()).isFalse();
    }

    @Test
    void aSlowRecallDegradesInsteadOfBlocking() throws Exception {
        @SuppressWarnings("unchecked")
        Future<RecallResult> future = mock(Future.class);
        submitReturns(future);
        when(future.get(200, TimeUnit.MILLISECONDS)).thenThrow(new TimeoutException());

        ContextSection section = contributor.contribute(request("operator", "", List.of())).get(0);

        assertThat(section.content()).isEmpty();
        assertThat(section.degradedReason()).contains("timeout");
        verify(future).cancel(true);
    }

    @Test
    void aFailingRecallDegradesInsteadOfThrowing() {
        submitReturns(CompletableFuture.failedFuture(new IllegalStateException("database unavailable")));

        ContextSection section = contributor.contribute(request("operator", "", List.of())).get(0);

        assertThat(section.content()).isEmpty();
        assertThat(section.degradedReason()).contains("error");
        assertThat(section.degradedReason()).doesNotContain("database unavailable");
    }

    @Test
    void anEmptyRecallIsRecordedAsDegradedRatherThanOmitted() {
        submitReturns(CompletableFuture.completedFuture(RecallResult.empty()));

        List<ContextSection> sections = contributor.contribute(request("nobody special", "", List.of()));

        assertThat(sections).hasSize(1);
        assertThat(sections.get(0).content()).isEmpty();
        assertThat(sections.get(0).degradedReason()).contains("empty");
    }

    @Test
    void aRequestWithNothingToRecallProducesNoSection() {
        List<ContextSection> sections = contributor.contribute(request("  ", "", List.of()));

        assertThat(sections).isEmpty();
        verifyNoInteractions(recall, executor);
    }

    @Test
    void theGoalAndTheLastThreeUserTurnsFeedRecall() {
        submitRunsTask();
        ContextRequest request = new ContextRequest(
                UUID.randomUUID(), "conversation-1", UUID.randomUUID(), ContextAdapter.CORE, 4,
                "goal text", null, Sensitivity.INTERNAL, 24_000, "D:/repo", "",
                List.of(
                        new ContextMessage("user", "turn-1", null),
                        new ContextMessage("user", "turn-2", null),
                        new ContextMessage("user", "turn-3", null),
                        new ContextMessage("assistant", "skip me", null),
                        new ContextMessage("user", "turn-4", null),
                        new ContextMessage("user", "turn-5", null)));

        contributor.contribute(request);

        ArgumentCaptor<RecallQuery> query = ArgumentCaptor.forClass(RecallQuery.class);
        verify(recall).recall(query.capture());
        assertThat(query.getValue().text())
                .contains("goal text", "turn-3", "turn-4", "turn-5")
                .doesNotContain("turn-1", "turn-2", "skip me");
        assertThat(query.getValue().focusRef()).isEqualTo("D:/repo");
        assertThat(query.getValue().maxSensitivity()).isEqualTo(Sensitivity.INTERNAL);
    }

    @Test
    void theSectionIsBoundedToTwelveEntityLinesAndFortyFactLines() {
        List<MemoryEntityView> manyEntities = new ArrayList<>();
        for (int i = 0; i < 15; i++) {
            manyEntities.add(entity("entity-" + i, "PROJECT", "Project " + i, Sensitivity.INTERNAL));
        }
        submitReturns(CompletableFuture.completedFuture(new RecallResult(manyEntities, List.of(), false)));

        ContextSection section = contributor.contribute(request("project", "", List.of())).get(0);

        assertThat(lineCount(section.content(), "- ")).isEqualTo(12);
        assertThat(section.truncated()).isTrue();
    }

    @Test
    void theSectionContentStaysWithinTheMemoryBudget() {
        List<MemoryFactView> manyFacts = new ArrayList<>();
        MemoryEntityView entity = entity("entity-1", "PERSON", "Operator", Sensitivity.INTERNAL);
        for (int i = 0; i < 45; i++) {
            manyFacts.add(fact("fact-" + i, "entity-1", "key-" + i, "value-" + i, "OPERATOR",
                    Sensitivity.INTERNAL));
        }
        submitReturns(CompletableFuture.completedFuture(
                new RecallResult(List.of(entity), manyFacts, false)));

        ContextSection section = contributor.contribute(request("operator", "", List.of())).get(0);

        assertThat(section.content().length()).isLessThanOrEqualTo(4_000);
        assertThat(lineCount(section.content(), "  - ")).isLessThanOrEqualTo(40);
        assertThat(section.truncated()).isTrue();
    }

    // ---------------------------------------------------------------- fixtures

    private void submitReturns(Future<RecallResult> future) {
        when(executor.submit(org.mockito.ArgumentMatchers.<Callable<RecallResult>>any())).thenReturn(future);
    }

    /** A stub executor that runs the submitted task, so the recall call itself is observable. */
    private void submitRunsTask() {
        when(executor.submit(org.mockito.ArgumentMatchers.<Callable<RecallResult>>any()))
                .thenAnswer(invocation -> CompletableFuture.completedFuture(
                        invocation.<Callable<RecallResult>>getArgument(0).call()));
    }

    private static ContextRequest request(String goal, String workspaceRef, List<ContextMessage> history) {
        return new ContextRequest(UUID.randomUUID(), "conversation-1", UUID.randomUUID(),
                ContextAdapter.CORE, 1, goal, null, Sensitivity.CONFIDENTIAL, 24_000, workspaceRef, "",
                history);
    }

    private static MemoryEntityView entity(String id, String kind, String displayName, Sensitivity sensitivity) {
        return new MemoryEntityView(id, kind, "key-" + id, displayName, sensitivity.name(), "ACTIVE",
                "OPERATOR", null, 0, VALID_FROM, VALID_FROM);
    }

    private static MemoryFactView fact(String id, String entityId, String factKey, String value,
                                       String origin, Sensitivity sensitivity) {
        return new MemoryFactView(id, entityId, factKey, value, "STRING", "ACTIVE", origin,
                sensitivity.name(), new BigDecimal("1.00"), VALID_FROM, null, 0);
    }

    /** Entity lines start with {@code "- "}; fact lines are indented with {@code "  - "}. */
    private static long lineCount(String content, String marker) {
        return content.lines().filter(line -> line.startsWith(marker)).count();
    }
}
