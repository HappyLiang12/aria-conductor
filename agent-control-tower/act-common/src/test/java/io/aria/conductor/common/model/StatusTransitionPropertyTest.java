package io.aria.conductor.common.model;

import io.aria.conductor.common.exception.InvalidStateTransitionException;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.Size;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * Phase F property-based tests over the {@link RunStatus} / {@link ApprovalStatus}
 * state machines and the {@link InvalidStateTransitionException} contract.
 *
 * <p>act-common owns the enums and the exception but the runtime transition table
 * lives downstream (act-agent {@code RunService.VALID_TRANSITIONS} for runs;
 * {@code ApprovalGate} only ever moves PENDING approvals). act-common cannot
 * depend on those modules, so the tables are mirrored here verbatim as the spec
 * under test, and a validator identical to {@code RunService#validateTransition}
 * exercises the shared exception. Properties: terminal states admit no outgoing
 * transitions, valid walks never leave and re-enter a terminal state, and the
 * relation is total/closed over the enums with only the declared exception type.
 */
class StatusTransitionPropertyTest {

    /** Mirror of act-agent RunService.VALID_TRANSITIONS (the production table). */
    private static final Map<RunStatus, Set<RunStatus>> RUN_TRANSITIONS;
    /** Approval lifecycle: only PENDING is live (ApprovalGate ignores decisions on non-PENDING). */
    private static final Map<ApprovalStatus, Set<ApprovalStatus>> APPROVAL_TRANSITIONS;

    static {
        Map<RunStatus, Set<RunStatus>> run = new EnumMap<>(RunStatus.class);
        run.put(RunStatus.PENDING, EnumSet.of(RunStatus.INITIALIZING, RunStatus.CANCELLED));
        run.put(RunStatus.INITIALIZING, EnumSet.of(RunStatus.RUNNING, RunStatus.FAILED, RunStatus.CANCELLED));
        run.put(RunStatus.RUNNING, EnumSet.of(RunStatus.PAUSED, RunStatus.COMPLETED, RunStatus.FAILED, RunStatus.CANCELLED, RunStatus.ABORTED));
        run.put(RunStatus.PAUSED, EnumSet.of(RunStatus.RUNNING, RunStatus.CANCELLED));
        run.put(RunStatus.COMPLETED, EnumSet.noneOf(RunStatus.class));
        run.put(RunStatus.FAILED, EnumSet.noneOf(RunStatus.class));
        run.put(RunStatus.CANCELLED, EnumSet.noneOf(RunStatus.class));
        // ABORTED (task-level engine timeout/budget/approval-denial) is a failure end
        // state that may still be cancelled by an operator: ABORTED -> CANCELLED.
        run.put(RunStatus.ABORTED, EnumSet.of(RunStatus.CANCELLED));
        RUN_TRANSITIONS = Collections.unmodifiableMap(run);

        Map<ApprovalStatus, Set<ApprovalStatus>> approval = new EnumMap<>(ApprovalStatus.class);
        approval.put(ApprovalStatus.PENDING,
                EnumSet.of(ApprovalStatus.APPROVED, ApprovalStatus.DENIED, ApprovalStatus.EXPIRED));
        approval.put(ApprovalStatus.APPROVED, EnumSet.noneOf(ApprovalStatus.class));
        approval.put(ApprovalStatus.DENIED, EnumSet.noneOf(ApprovalStatus.class));
        approval.put(ApprovalStatus.EXPIRED, EnumSet.noneOf(ApprovalStatus.class));
        APPROVAL_TRANSITIONS = Collections.unmodifiableMap(approval);
    }

    private static final Set<RunStatus> RUN_TERMINALS =
            EnumSet.of(RunStatus.COMPLETED, RunStatus.FAILED, RunStatus.CANCELLED);
    private static final Set<ApprovalStatus> APPROVAL_TERMINALS =
            EnumSet.of(ApprovalStatus.APPROVED, ApprovalStatus.DENIED, ApprovalStatus.EXPIRED);

    /** Same shape as RunService#validateTransition — throws the shared act-common exception. */
    private static <S extends Enum<S>> void validateTransition(
            Map<S, Set<S>> relation, String entityType, S current, S target) {
        Set<S> allowed = relation.get(current);
        if (allowed == null || !allowed.contains(target)) {
            throw new InvalidStateTransitionException(entityType, current.name(), target.name());
        }
    }

    // ── terminal states admit no outgoing transitions ────────────────────

    @Property
    void terminalRunStatesAdmitNoOutgoingTransitions(@ForAll("terminalRunStates") RunStatus terminal,
                                                     @ForAll RunStatus target) {
        assertThat(RUN_TRANSITIONS.get(terminal)).isEmpty();
        assertThatThrownBy(() -> validateTransition(RUN_TRANSITIONS, "Run", terminal, target))
                .isInstanceOf(InvalidStateTransitionException.class)
                .hasMessage("Invalid state transition for Run: %s -> %s", terminal, target);
    }

    @Property
    void terminalApprovalStatesAdmitNoOutgoingTransitions(@ForAll("terminalApprovalStates") ApprovalStatus terminal,
                                                          @ForAll ApprovalStatus target) {
        assertThat(APPROVAL_TRANSITIONS.get(terminal)).isEmpty();
        assertThatThrownBy(() -> validateTransition(APPROVAL_TRANSITIONS, "Approval", terminal, target))
                .isInstanceOf(InvalidStateTransitionException.class)
                .hasMessage("Invalid state transition for Approval: %s -> %s", terminal, target);
    }

    // ── valid walks never leave then re-enter a terminal state ───────────

    @Property
    void validRunWalksReachTerminalAtMostOnceAndOnlyAsLastState(
            @ForAll @Size(max = 25) List<@IntRange(min = 0, max = 1000) Integer> choices) {
        List<RunStatus> walk = walkFrom(RunStatus.PENDING, RUN_TRANSITIONS, choices);

        // Every consecutive pair is a valid transition (validator raises nothing).
        for (int i = 1; i < walk.size(); i++) {
            validateTransition(RUN_TRANSITIONS, "Run", walk.get(i - 1), walk.get(i));
        }
        // A terminal state can only ever be the final element — never left, never re-entered.
        for (int i = 0; i < walk.size() - 1; i++) {
            assertThat(RUN_TERMINALS).doesNotContain(walk.get(i));
        }
        assertThat(walk.stream().filter(RUN_TERMINALS::contains).count()).isLessThanOrEqualTo(1);
    }

    @Property
    void validApprovalWalksAreAtMostOneHopIntoATerminalState(
            @ForAll @Size(max = 10) List<@IntRange(min = 0, max = 1000) Integer> choices) {
        List<ApprovalStatus> walk = walkFrom(ApprovalStatus.PENDING, APPROVAL_TRANSITIONS, choices);

        // PENDING -> terminal is the whole lifecycle: max two states, terminal only last.
        assertThat(walk.size()).isLessThanOrEqualTo(2);
        assertThat(walk.get(0)).isEqualTo(ApprovalStatus.PENDING);
        if (walk.size() == 2) {
            assertThat(APPROVAL_TERMINALS).contains(walk.get(1));
        }
    }

    // ── relation is closed/total over the enum; only the declared exception ──

    @Property
    void anyRunTransitionAttemptEitherSucceedsOrThrowsOnlyInvalidStateTransition(
            @ForAll RunStatus from, @ForAll RunStatus to) {
        Throwable thrown = catchThrowable(() -> validateTransition(RUN_TRANSITIONS, "Run", from, to));
        if (RUN_TRANSITIONS.get(from).contains(to)) {
            assertThat(thrown).isNull();
        } else {
            assertThat(thrown)
                    .isExactlyInstanceOf(InvalidStateTransitionException.class)
                    .hasMessageContaining(from.name())
                    .hasMessageContaining(to.name());
        }
    }

    @Property
    void anyApprovalTransitionAttemptEitherSucceedsOrThrowsOnlyInvalidStateTransition(
            @ForAll ApprovalStatus from, @ForAll ApprovalStatus to) {
        Throwable thrown = catchThrowable(() -> validateTransition(APPROVAL_TRANSITIONS, "Approval", from, to));
        if (APPROVAL_TRANSITIONS.get(from).contains(to)) {
            assertThat(thrown).isNull();
        } else {
            assertThat(thrown).isExactlyInstanceOf(InvalidStateTransitionException.class);
        }
    }

    @Property
    void transitionRelationIsTotalAndClosedOverTheEnums(@ForAll RunStatus runState,
                                                        @ForAll ApprovalStatus approvalState) {
        // Totality: every enum value has a defined successor set …
        assertThat(RUN_TRANSITIONS).containsKey(runState);
        assertThat(APPROVAL_TRANSITIONS).containsKey(approvalState);
        // … and closure: every successor is itself a declared enum constant.
        assertThat(RUN_TRANSITIONS.get(runState)).isSubsetOf(RunStatus.values());
        assertThat(APPROVAL_TRANSITIONS.get(approvalState)).isSubsetOf(ApprovalStatus.values());
    }

    // ---- generators & helpers ----

    @Provide
    Arbitrary<RunStatus> terminalRunStates() {
        return Arbitraries.of(RUN_TERMINALS);
    }

    @Provide
    Arbitrary<ApprovalStatus> terminalApprovalStates() {
        return Arbitraries.of(APPROVAL_TERMINALS);
    }

    /**
     * Fold a list of arbitrary choice indices into a walk that only ever follows the
     * transition relation; the walk stops when a state has no successors (terminal).
     */
    private static <S extends Enum<S>> List<S> walkFrom(S start, Map<S, Set<S>> relation, List<Integer> choices) {
        List<S> walk = new ArrayList<>();
        walk.add(start);
        S current = start;
        for (int choice : choices) {
            List<S> successors = List.copyOf(relation.get(current));
            if (successors.isEmpty()) {
                break;
            }
            current = successors.get(choice % successors.size());
            walk.add(current);
        }
        return walk;
    }
}
