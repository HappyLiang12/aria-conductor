package io.aria.conductor.execution.approval;

import io.aria.conductor.common.model.Approval;
import io.aria.conductor.common.model.ApprovalSource;
import io.aria.conductor.common.model.ApprovalStatus;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The shared selection rule behind both readers of one park (A4): the sync chat
 * response and the panel's {@code waiting_input} card. The finder they call has no
 * {@code ORDER BY}, so the rule — not the row order — must decide which ask is named.
 */
class PendingClarificationAsksTest {

    private static final UUID RUN_ID = UUID.randomUUID();

    /**
     * The live park outranks a stale residual whatever order the database returns the
     * two rows in. Both orders are run through the SAME rule, which is what makes the
     * two readers of one park agree.
     */
    @Test
    void theNewerAskWinsWhateverOrderTheRowsArriveIn() {
        Approval stale = clarification("superseded question", Instant.parse("2026-11-11T10:00:00Z"));
        Approval live = clarification("Which database should the migration target?",
                Instant.parse("2026-11-11T10:05:00Z"));

        assertThat(PendingClarificationAsks.newest(List.of(stale, live)))
                .as("stale row first: the pick is still the live ask")
                .contains(live);
        assertThat(PendingClarificationAsks.newest(List.of(live, stale)))
                .as("live row first: same pick")
                .contains(live);
    }

    /**
     * F2, the honest half of the guarantee. {@code approvals.requested_at} is a plain
     * {@code TIMESTAMP} — second precision, no fractional part
     * ({@code V1__init_schema.sql:91}) — so two asks created in the same second come
     * back with the SAME stored value and the id tie-break decides. The ids here are
     * what the column can hold, not two instants we wish it held.
     *
     * <p>This pins DETERMINISM, not chronology: the pick must be a property of the rows
     * (both orders, same answer) and is the higher id — the id is a random UUID, so
     * inside one second nothing here can say which ask was created last, and this
     * program ships no migration to change that.
     */
    @Test
    void twoAsksTheColumnStoredInTheSameSecondResolveByIdInEitherOrder() {
        Instant storedInTheSameSecond = Instant.parse("2026-11-11T10:00:00Z");
        Approval lowerId = clarification(UUID.fromString("00000000-0000-4000-8000-000000000001"),
                "first question", storedInTheSameSecond);
        Approval higherId = clarification(UUID.fromString("00000000-0000-4000-8000-000000000002"),
                "second question", storedInTheSameSecond);

        assertThat(PendingClarificationAsks.newest(List.of(lowerId, higherId)))
                .as("same stored second: the id tie-break decides, lower id first in the list")
                .contains(higherId);
        assertThat(PendingClarificationAsks.newest(List.of(higherId, lowerId)))
                .as("same stored second: the id tie-break decides again, and identically")
                .contains(higherId);
    }

    /**
     * A row with no stored timestamp (a fixture, or a hand-written row) must never beat
     * one the column actually stamped: null sorts first, so it can only win by id when
     * every row is null.
     */
    @Test
    void anAskWithoutAStoredTimestampNeverOutranksOneWithIt() {
        Approval unstamped = clarification("unstamped question", null);
        Approval stamped = clarification("stamped question", Instant.parse("2026-11-11T10:00:00Z"));

        assertThat(PendingClarificationAsks.newest(List.of(unstamped, stamped))).contains(stamped);
        assertThat(PendingClarificationAsks.newest(List.of(stamped, unstamped))).contains(stamped);
    }

    /** No rows is not an error: both readers keep their "no question to carry" branch. */
    @Test
    void noRowsResolveToNothing() {
        assertThat(PendingClarificationAsks.newest(List.of())).isEmpty();
        assertThat(PendingClarificationAsks.newest(null)).isEmpty();
    }

    private static Approval clarification(String question, Instant requestedAt) {
        return clarification(UUID.randomUUID(), question, requestedAt);
    }

    private static Approval clarification(UUID id, String question, Instant requestedAt) {
        return Approval.builder()
                .id(id)
                .runId(RUN_ID)
                .status(ApprovalStatus.PENDING)
                .approvalType(Approval.ApprovalType.TOOL_CALL)
                .askType(Approval.AskType.QUESTION)
                .source(ApprovalSource.CLARIFICATION)
                .requestedAt(requestedAt)
                .content(question)
                .build();
    }
}
