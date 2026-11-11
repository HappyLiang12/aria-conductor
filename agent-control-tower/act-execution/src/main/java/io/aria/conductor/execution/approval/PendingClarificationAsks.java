package io.aria.conductor.execution.approval;

import io.aria.conductor.common.model.Approval;

import java.util.Collection;
import java.util.Comparator;
import java.util.Optional;

/**
 * The one selection rule for "the ask this parked run is waiting on" (A4).
 *
 * <p>Two readers name that ask for one park: the sync chat response
 * ({@code AriaService.findPendingClarification}) and the panel's
 * {@code waiting_input} card ({@code RunSignalSseListener}). The finder they share
 * ({@code ApprovalRepository.findByRunIdAndStatusAndSource}) is a derived query
 * with no {@code ORDER BY}, so a {@code findFirst()} would make each reader depend
 * on the row order the database happened to return — and a run can briefly own two
 * PENDING clarification rows (the terminal-race ask of a recorded residual beside a
 * live park, or a duplicate waiting event). Two readers of one park would then name
 * two different questions. Both resolve the pick here instead, from the row's own
 * identity: newest {@code requestedAt} first, ties broken by {@code id}, with both
 * keys nulls-first so the order stays total even for an unset column.
 */
public final class PendingClarificationAsks {

    /** Newest first; an unstored timestamp never outranks a stored one; ties by id. */
    private static final Comparator<Approval> NEWEST_FIRST =
            Comparator.comparing(Approval::getRequestedAt,
                            Comparator.nullsFirst(Comparator.naturalOrder()))
                    .thenComparing(Approval::getId,
                            Comparator.nullsFirst(Comparator.naturalOrder()));

    private PendingClarificationAsks() {
    }

    /**
     * The newest ask among the given rows, or empty when there is none. Safe on a
     * null collection: a failed read must not turn a parked reply into an error.
     */
    public static Optional<Approval> newest(Collection<Approval> asks) {
        if (asks == null || asks.isEmpty()) {
            return Optional.empty();
        }
        return asks.stream().max(NEWEST_FIRST);
    }
}
