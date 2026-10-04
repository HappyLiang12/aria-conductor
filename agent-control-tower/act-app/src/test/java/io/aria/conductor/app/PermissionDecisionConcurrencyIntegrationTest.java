package io.aria.conductor.app;

import io.aria.conductor.agent.repository.AgentRepository;
import io.aria.conductor.agent.repository.RunRepository;
import io.aria.conductor.common.model.AcpPermissionRequest;
import io.aria.conductor.common.model.Agent;
import io.aria.conductor.common.model.AgentType;
import io.aria.conductor.common.model.Approval;
import io.aria.conductor.common.model.ApprovalStatus;
import io.aria.conductor.common.model.HealthStatus;
import io.aria.conductor.common.model.Run;
import io.aria.conductor.common.model.RunStatus;
import io.aria.conductor.common.repository.AcpPermissionRequestRepository;
import io.aria.conductor.common.security.ActorPrincipal;
import io.aria.conductor.execution.approval.NativePermission;
import io.aria.conductor.execution.approval.PermissionChoice;
import io.aria.conductor.execution.approval.PermissionCoordinator;
import io.aria.conductor.execution.approval.PermissionDeliveryState;
import io.aria.conductor.execution.approval.PermissionOption;
import io.aria.conductor.execution.approval.PermissionTarget;
import io.aria.conductor.execution.approval.WriteGrantService;
import io.aria.conductor.execution.kanban.KanbanRepository;
import io.aria.conductor.execution.kanban.KanbanStatus;
import io.aria.conductor.execution.repository.ApprovalRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/**
 * Real-database verification of the approval-coordination decision path
 * ({@link PermissionCoordinator#decide}) — the leg the unit lane cannot reach.
 *
 * <p>{@code PermissionCoordinatorTest} races two decisions against a
 * mock-backed store, so it only proves the in-process
 * {@code synchronized (decisionMonitor)}; it never executes the database row
 * lock. This class drives two PARALLEL operator decisions (double-click /
 * client retry) at one PLATFORM_MCP ALLOW_ONCE ask per round on the real
 * migrated H2 schema — the same idiom as {@code KnowledgeReviewConcurrencyIntegrationTest}
 * for the knowledge review — and asserts the exact end state: one winner, one
 * settled refusal, one delivered ask row, one WRITE_GRANT ledger row and
 * exactly one usable use.
 *
 * <p>The row lock is load-bearing even in a single-instance deployment: the
 * monitor is exited before the {@code @Transactional} proxy commits, so a
 * decider that acquires the monitor inside that window reads the approval while
 * the winner's transaction is still open. Only
 * {@code ApprovalDecisionLockRepository.findByIdForDecision}
 * ({@code SELECT ... FOR UPDATE}, held until commit) makes it block, observe the
 * committed APPROVED row and refuse; without that lock both deciders would read
 * PENDING, both would settle, and the loser would re-issue — and re-arm — the
 * one-use grant of the identical call.
 *
 * <p>Each round of the first test waits (bounded) until the ask's asynchronous
 * review-card mirroring has linked {@code kanban_item_id} before the two
 * decisions are fired, so the race under test is operator-vs-operator only: that
 * mirror runs in its own transaction. Since fix round 3 the mirror writes only
 * the one column it owns, guarded on it still being null, so it can never revert
 * a committed decision; the second method below proves exactly that interaction
 * — the decision settles before or around the mirror's write. That round drains
 * the single mirror thread and branches: the mirror either won its first read
 * and linked a card (the settled decision must survive the late write, and the
 * card must settle with it) or observed the settled row and skipped (nothing
 * may be created or linked after the fact). A third method forces that skip
 * deterministically by holding the single mirror thread until after the
 * decision commits.
 */
class PermissionDecisionConcurrencyIntegrationTest extends BaseH2IntegrationTest {

    private static final int ROUNDS = 24;
    private static final String TOOL = "write_file";

    /** The authenticated operator the decision surface resolved from its own transport. */
    private static final ActorPrincipal OPERATOR =
            new ActorPrincipal(ActorPrincipal.Role.OPERATOR, null, null);

    /** The ask offers deny + allow-once; both raced decisions pick allow-once (a double-click). */
    private static final List<PermissionOption> OFFERED = List.of(
            new PermissionOption("cancel", PermissionChoice.DENY),
            new PermissionOption("proceed_once", PermissionChoice.ALLOW_ONCE));

    @Autowired
    private PermissionCoordinator coordinator;

    @Autowired
    private WriteGrantService writeGrants;

    @Autowired
    private ApprovalRepository approvals;

    @Autowired
    private AcpPermissionRequestRepository ledger;

    @Autowired
    private AgentRepository agentRepository;

    @Autowired
    private RunRepository runRepository;

    @Autowired
    private KanbanRepository kanbanRepository;

    /**
     * The single mirror thread. A task submitted to it runs after every mirror
     * task already enqueued: the drain barrier the second test uses to make its
     * round deterministic under both the link and the guard-skip interleavings.
     */
    @Autowired
    @Qualifier("kanbanMirrorExecutor")
    private Executor mirrorExecutor;

    @Test
    void parallelDecisionsOnOnePlatformMcpAsk_settleExactlyOneAndArmExactlyOneUse() throws Exception {
        UUID agentId = agentRepository.save(Agent.builder()
                .id(UUID.randomUUID())
                .name("permission-race-agent")
                .description("Task 12 decision race against the real database")
                .agentType(AgentType.NATIVE)
                .role("tester")
                .model("gpt-4o-mini")
                .provider("openai")
                .config("{}")
                .healthStatus(HealthStatus.HEALTHY)
                .createdAt(Instant.now())
                .build()).getId();

        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<String> outcomes = new ArrayList<>();
        try {
            for (int round = 0; round < ROUNDS; round++) {
                // A fresh run and ask per round: no ledger/grant correlation can leak across rounds.
                UUID runId = runRepository.save(Run.builder()
                        .agentId(agentId)
                        .status(RunStatus.RUNNING)
                        .promptSeed("permission decision race round " + round)
                        .build()).getId();
                String requestId = "race-req-" + round;
                String argumentsJson = "{\"path\":\"race-" + round + ".txt\",\"content\":\"task 12\"}";
                String digest = WriteGrantService.digestOf(argumentsJson);

                // The production registration path persists ask + correlation before the race.
                UUID approvalId = coordinator.register(new NativePermission(runId, "ses_race_" + round,
                        requestId, TOOL, PermissionTarget.PLATFORM_MCP, argumentsJson, OFFERED,
                        Instant.now().plusSeconds(600)));

                // Let the asynchronous review-card mirroring of this ask settle first
                // (KanbanReviewCardListener links kanban_item_id from its own
                // transaction). That listener's write is a different defect — it can
                // rewrite the whole approval row from a stale snapshot and revert a
                // committed decision to PENDING (see the Fix round 2 report) — and it
                // must not be part of the operator-vs-operator race under test here.
                await().atMost(20, TimeUnit.SECONDS).until(() -> approvals.findById(approvalId)
                        .orElseThrow().getKanbanItemId() != null);

                CountDownLatch go = new CountDownLatch(1);
                Future<Outcome> first = pool.submit(decideOnce(approvalId, go));
                Future<Outcome> second = pool.submit(decideOnce(approvalId, go));
                go.countDown();
                Outcome left = first.get(30, TimeUnit.SECONDS);
                Outcome right = second.get(30, TimeUnit.SECONDS);

                assertThat(left.unexpected())
                        .as("round %d: left decider failed outside the settled check", round)
                        .isNull();
                assertThat(right.unexpected())
                        .as("round %d: right decider failed outside the settled check", round)
                        .isNull();

                int winners = (left.won() ? 1 : 0) + (right.won() ? 1 : 0);
                assertThat(winners)
                        .as("round %d: exactly one decision may win; left=%s right=%s", round, left, right)
                        .isEqualTo(1);

                Outcome loser = left.won() ? right : left;
                assertThat(loser.settledRefusal())
                        .as("round %d: the loser must observe the settled row and refuse", round)
                        .isTrue();
                assertThat(loser.refusalMessage())
                        .as("round %d: the loser refuses with the coordinator's settled-refusal message", round)
                        .isEqualTo("Approval " + approvalId
                                + " is already APPROVED; a decision on a settled request is refused");

                // The mirror linked a review card for this ask before the race;
                // the winner's settle must move it out of Review (I2/R-RFUX3)
                // even though the linked run is still RUNNING.
                String cardId = approvals.findById(approvalId).orElseThrow().getKanbanItemId();
                assertThat(cardId)
                        .as("round %d: the mirror linked a review card before the race", round)
                        .isNotNull();
                assertThat(kanbanRepository.findById(cardId).orElseThrow().getStatus())
                        .as("round %d: the approved tool-call card leaves Review for Done while the run is RUNNING", round)
                        .isEqualTo(KanbanStatus.DONE);

                Approval approval = approvals.findById(approvalId).orElseThrow();
                assertThat(approval.getStatus())
                        .as("round %d: the approval keeps the winner's verdict", round)
                        .isEqualTo(ApprovalStatus.APPROVED);
                assertThat(approval.getReason())
                        .as("round %d: the winner's decision reason is not rewritten", round)
                        .isEqualTo("Operator allowed one use of " + TOOL
                                + " (native permission request " + requestId + ")");
                assertThat(approval.getDecidedAt())
                        .as("round %d: the winner's decision is recorded", round)
                        .isNotNull();

                AcpPermissionRequest askRow = ledger.findByApprovalId(approvalId).orElseThrow();
                assertThat(askRow.getDeliveryState())
                        .as("round %d: the ledger row carries the winner's delivery", round)
                        .isEqualTo(PermissionDeliveryState.DELIVERED.name());
                assertThat(askRow.getSelectedOptionId()).isEqualTo("proceed_once");
                assertThat(askRow.getDecidedAt()).isNotNull();
                assertThat(askRow.getDeliveredAt()).isNotNull();

                AcpPermissionRequest grant = ledger.findByRunIdAndSessionIdAndRequestId(
                        runId, WriteGrantService.GRANT_SESSION_ID, "grant:" + digest).orElseThrow();
                assertThat(ledger.findByRunId(runId).stream()
                        .filter(row -> row.getKind() == AcpPermissionRequest.Kind.WRITE_GRANT)
                        .count())
                        .as("round %d: exactly one grant row exists for the identical call", round)
                        .isEqualTo(1L);
                assertThat(grant.getKind()).isEqualTo(AcpPermissionRequest.Kind.WRITE_GRANT);
                assertThat(grant.getToolName()).isEqualTo(TOOL);
                assertThat(grant.getArgumentsDigest()).isEqualTo(digest);
                assertThat(grant.getDeliveryState()).isEqualTo(PermissionDeliveryState.DELIVERED.name());
                assertThat(grant.getConsumedAt())
                        .as("round %d: the losing decision must not touch the winner's grant", round)
                        .isNull();

                // The winner's single use: the platform boundary may spend it exactly once, a replay not at all.
                assertThat(writeGrants.consume(runId, TOOL, digest))
                        .as("round %d: the winner's grant authorizes exactly one matching call", round)
                        .isTrue();
                assertThat(writeGrants.consume(runId, TOOL, digest))
                        .as("round %d: the second consume of the identical call is refused", round)
                        .isFalse();
                assertThat(ledger.findByRunIdAndSessionIdAndRequestId(runId,
                        WriteGrantService.GRANT_SESSION_ID, "grant:" + digest).orElseThrow().getConsumedAt())
                        .as("round %d: the winner's single use is recorded as consumed", round)
                        .isNotNull();

                outcomes.add("round " + round + " winner="
                        + (left.won() ? "decider-1" : "decider-2")
                        + " loserRefusal=\"" + loser.refusalMessage() + "\"");
            }
        } finally {
            pool.shutdownNow();
        }

        // Per-round evidence in the captured report (every round asserted above).
        System.out.println("[PermissionDecisionConcurrency] " + ROUNDS + " rounds, exactly one winner each: "
                + outcomes);
    }

    /**
     * Proves the fixed interaction between the decision path and the
     * asynchronous review-card mirror (R2.4, fix round 3; re-scoped by R-RFUX3):
     * one operator decision settles while the mirror is (typically) still in
     * flight, and the round then drains the mirror thread so exactly one of two
     * interleavings is observed deterministically. When the mirror won its first
     * read (PENDING), its write lands before, during or after the decision, the
     * settled row must survive it whole — the write is confined to the one
     * column the mirror owns, so the verdict, reason and decision time, the
     * ask's delivery and the single one-use grant are all unchanged — and the
     * card it linked must have settled with the ask. When the mirror lost the
     * first read to the settled-ask guard, nothing may be created or linked
     * after the fact. Either way a further decision refuses with the exact
     * settled-refusal message, and nothing can re-arm the grant.
     */
    @Test
    void decisionSettledBeforeTheReviewCardMirror_neverRevertsAndFurtherDecisionRefuses() {
        UUID agentId = agentRepository.save(Agent.builder()
                .id(UUID.randomUUID())
                .name("permission-mirror-agent")
                .description("Task 12 late-mirror race against the real database")
                .agentType(AgentType.NATIVE)
                .role("tester")
                .model("gpt-4o-mini")
                .provider("openai")
                .config("{}")
                .healthStatus(HealthStatus.HEALTHY)
                .createdAt(Instant.now())
                .build()).getId();

        List<String> rounds = new ArrayList<>();
        for (int round = 0; round < ROUNDS; round++) {
            UUID runId = runRepository.save(Run.builder()
                    .agentId(agentId)
                    .status(RunStatus.RUNNING)
                    .promptSeed("permission mirror race round " + round)
                    .build()).getId();
            String requestId = "mirror-req-" + round;
            String argumentsJson = "{\"path\":\"mirror-" + round + ".txt\",\"content\":\"task 12\"}";
            String digest = WriteGrantService.digestOf(argumentsJson);

            UUID approvalId = coordinator.register(new NativePermission(runId, "ses_mirror_" + round,
                    requestId, TOOL, PermissionTarget.PLATFORM_MCP, argumentsJson, OFFERED,
                    Instant.now().plusSeconds(600)));

            // The decision settles immediately; the mirror is normally still in
            // flight and its guarded write lands before, during or after it.
            coordinator.decide(approvalId, PermissionChoice.ALLOW_ONCE, OPERATOR);

            Approval settled = approvals.findById(approvalId).orElseThrow();
            assertThat(settled.getStatus())
                    .as("round %d: the decision settles before the mirror's write", round)
                    .isEqualTo(ApprovalStatus.APPROVED);
            assertThat(settled.getReason())
                    .as("round %d: the winner's decision reason", round)
                    .isEqualTo("Operator allowed one use of " + TOOL
                            + " (native permission request " + requestId + ")");
            assertThat(settled.getDecidedAt()).isNotNull();
            String settledReason = settled.getReason();
            Instant settledDecidedAt = settled.getDecidedAt();

            AcpPermissionRequest askBeforeMirror = ledger.findByApprovalId(approvalId).orElseThrow();
            assertThat(askBeforeMirror.getDeliveryState())
                    .as("round %d: the ask is delivered by the decision itself", round)
                    .isEqualTo(PermissionDeliveryState.DELIVERED.name());
            assertThat(askBeforeMirror.getSelectedOptionId()).isEqualTo("proceed_once");
            assertThat(askBeforeMirror.getDecidedAt()).isNotNull();
            assertThat(askBeforeMirror.getDeliveredAt()).isNotNull();
            assertThat(askBeforeMirror.getConsumedAt()).isNull();
            assertThat(writeGrantCount(runId))
                    .as("round %d: exactly one grant row before the mirror's write", round)
                    .isEqualTo(1L);

            // The mirror may win the first read (PENDING: it links or creates the
            // card around the decision) or lose it to the settled-ask guard (it
            // skips and nothing lands). Drain the single mirror thread — a task
            // submitted behind the mirror's own — so the round observes exactly
            // one of the two interleavings deterministically, then branch.
            CountDownLatch mirrorDrained = new CountDownLatch(1);
            mirrorExecutor.execute(mirrorDrained::countDown);
            await().atMost(20, TimeUnit.SECONDS).until(() -> mirrorDrained.getCount() == 0);

            Approval afterMirror = approvals.findById(approvalId).orElseThrow();
            boolean mirrorLinked = afterMirror.getKanbanItemId() != null;
            if (mirrorLinked) {
                // The mirror won the first read: its write landed before, during
                // or after the decision, and the settled row must survive it
                // whole. Its card must have settled with the ask — whichever
                // writer lands second performs the settle — approved reaches
                // Done even though the linked run is still RUNNING (I2/R-RFUX3).
                assertThat(afterMirror.getStatus())
                        .as("round %d: the late mirror write must never revert the settled decision", round)
                        .isEqualTo(ApprovalStatus.APPROVED);
                assertThat(afterMirror.getReason())
                        .as("round %d: the mirror must not rewrite the decision reason", round)
                        .isEqualTo(settledReason);
                assertThat(afterMirror.getDecidedAt())
                        .as("round %d: the mirror must not rewrite the decision time", round)
                        .isEqualTo(settledDecidedAt);
                assertThat(kanbanRepository.findById(afterMirror.getKanbanItemId()).orElseThrow().getStatus())
                        .as("round %d: the approved card leaves Review for Done while the run is still RUNNING", round)
                        .isEqualTo(KanbanStatus.DONE);
            } else {
                // The guard-skipped interleaving: the mirror saw the settled row
                // before writing, so no card may exist or be linked for the ask.
                assertThat(afterMirror.getStatus())
                        .as("round %d: a guard-skipped mirror leaves the settled row untouched", round)
                        .isEqualTo(ApprovalStatus.APPROVED);
                assertThat(kanbanRepository.findByLinkedRunId(runId.toString()))
                        .as("round %d: a guard-skipped mirror creates no card", round)
                        .isEmpty();
            }

            AcpPermissionRequest askAfterMirror = ledger.findByApprovalId(approvalId).orElseThrow();
            assertThat(askAfterMirror.getDeliveryState())
                    .as("round %d: the ask's delivery state is unchanged", round)
                    .isEqualTo(PermissionDeliveryState.DELIVERED.name());
            assertThat(askAfterMirror.getSelectedOptionId()).isEqualTo("proceed_once");
            assertThat(askAfterMirror.getDecidedAt()).isEqualTo(askBeforeMirror.getDecidedAt());
            assertThat(askAfterMirror.getDeliveredAt()).isEqualTo(askBeforeMirror.getDeliveredAt());
            assertThat(askAfterMirror.getConsumedAt()).isNull();

            assertThat(writeGrantCount(runId))
                    .as("round %d: exactly one grant row after the mirror's write", round)
                    .isEqualTo(1L);
            AcpPermissionRequest grantAfterMirror = ledger.findByRunIdAndSessionIdAndRequestId(
                    runId, WriteGrantService.GRANT_SESSION_ID, "grant:" + digest).orElseThrow();
            assertThat(grantAfterMirror.getDeliveryState())
                    .isEqualTo(PermissionDeliveryState.DELIVERED.name());
            assertThat(grantAfterMirror.getConsumedAt()).isNull();

            // The winner's single use: the platform boundary may spend it exactly once, a replay not at all.
            assertThat(writeGrants.consume(runId, TOOL, digest))
                    .as("round %d: the grant authorizes exactly one matching call", round)
                    .isTrue();
            assertThat(writeGrants.consume(runId, TOOL, digest))
                    .as("round %d: the second consume of the identical call is refused", round)
                    .isFalse();

            // A further decision on the settled row must refuse with the exact
            // settled-refusal message, observed through the row the mirror just
            // wrote to.
            assertThatThrownBy(() -> coordinator.decide(approvalId, PermissionChoice.ALLOW_ONCE, OPERATOR))
                    .as("round %d: a further decision on the settled row is refused", round)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("Approval " + approvalId
                            + " is already APPROVED; a decision on a settled request is refused");

            // ... and it must not have re-armed the consumed one-use grant.
            assertThat(writeGrantCount(runId))
                    .as("round %d: the refused decision adds no grant row", round)
                    .isEqualTo(1L);
            assertThat(ledger.findByRunIdAndSessionIdAndRequestId(runId,
                    WriteGrantService.GRANT_SESSION_ID, "grant:" + digest).orElseThrow().getConsumedAt())
                    .as("round %d: the refused decision must not re-arm the consumed grant", round)
                    .isNotNull();

            rounds.add("round " + round + " mirror="
                    + (mirrorLinked ? "linked card=" + afterMirror.getKanbanItemId() : "guard-skipped card=none")
                    + " status=" + afterMirror.getStatus() + " grants=1");
        }

        // Per-round evidence in the captured report (every round asserted above).
        System.out.println("[PermissionDecisionLateMirror] " + ROUNDS + " rounds, the settled decision survived: "
                + rounds);
    }

    /**
     * The guard-skip interleaving, forced deterministically: the single mirror
     * thread is held on a blocker while the ask is registered and settled, so
     * the mirror's first read observes the settled row — it must skip the link
     * and create nothing, and the settled row plus its delivery must stay
     * intact. This is the outcome the re-scoped await above tolerates; forced
     * here so the branch cannot silently rot behind scheduling luck.
     */
    @Test
    void mirrorBlockedUntilAfterTheSettlement_skipsTheLinkAndTouchesNothing() {
        UUID agentId = agentRepository.save(Agent.builder()
                .id(UUID.randomUUID())
                .name("permission-blocked-mirror-agent")
                .description("R-RFUX3 guard-skip interleaving, forced")
                .agentType(AgentType.NATIVE)
                .role("tester")
                .model("gpt-4o-mini")
                .provider("openai")
                .config("{}")
                .healthStatus(HealthStatus.HEALTHY)
                .createdAt(Instant.now())
                .build()).getId();

        // Hold the single mirror thread so the ask's mirror task stays queued
        // until after the decision has committed (FIFO: this blocker is
        // submitted before register enqueues the mirror task).
        CountDownLatch release = new CountDownLatch(1);
        mirrorExecutor.execute(() -> {
            try {
                release.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        UUID runId = runRepository.save(Run.builder()
                .agentId(agentId)
                .status(RunStatus.RUNNING)
                .promptSeed("permission blocked mirror")
                .build()).getId();
        UUID approvalId = coordinator.register(new NativePermission(runId, "ses_blocked_mirror",
                "blocked-req-0", TOOL, PermissionTarget.PLATFORM_MCP,
                "{\"path\":\"blocked.txt\",\"content\":\"task 12\"}", OFFERED,
                Instant.now().plusSeconds(600)));
        coordinator.decide(approvalId, PermissionChoice.ALLOW_ONCE, OPERATOR);

        release.countDown();
        // Drain: once the barrier behind the released mirror task completes, the
        // mirror has had its first (and only) read of the settled ask.
        CountDownLatch drained = new CountDownLatch(1);
        mirrorExecutor.execute(drained::countDown);
        await().atMost(20, TimeUnit.SECONDS).until(() -> drained.getCount() == 0);

        Approval approval = approvals.findById(approvalId).orElseThrow();
        assertThat(approval.getStatus()).isEqualTo(ApprovalStatus.APPROVED);
        assertThat(approval.getKanbanItemId())
                .as("a mirror that observed the settled row must not link the ask")
                .isNull();
        assertThat(kanbanRepository.findByLinkedRunId(runId.toString()))
                .as("a guard-skipped mirror must not create a card")
                .isEmpty();
        assertThat(ledger.findByApprovalId(approvalId).orElseThrow().getDeliveryState())
                .as("the skip must not disturb the decision's own delivery")
                .isEqualTo(PermissionDeliveryState.DELIVERED.name());
    }

    /** The run's WRITE_GRANT ledger row count, the one-use authorization ledger. */
    private long writeGrantCount(UUID runId) {
        return ledger.findByRunId(runId).stream()
                .filter(row -> row.getKind() == AcpPermissionRequest.Kind.WRITE_GRANT)
                .count();
    }

    /**
     * Runs one operator decision as soon as the start latch releases: a normal
     * return is the winning settlement, an {@link IllegalStateException} is the
     * coordinator's settled refusal, anything else (e.g. a lock timeout) is
     * reported as an unexpected failure instead of being swallowed.
     */
    private Callable<Outcome> decideOnce(UUID approvalId, CountDownLatch go) {
        return () -> {
            if (!go.await(30, TimeUnit.SECONDS)) {
                throw new IllegalStateException("the race never started for approval " + approvalId);
            }
            try {
                coordinator.decide(approvalId, PermissionChoice.ALLOW_ONCE, OPERATOR);
                return Outcome.winner();
            } catch (IllegalStateException e) {
                return Outcome.settled(e.getMessage());
            } catch (RuntimeException e) {
                return Outcome.failed(e);
            }
        };
    }

    /** One decider's result, in exactly one of the three exclusive states. */
    private record Outcome(boolean won, boolean settledRefusal, String refusalMessage, RuntimeException unexpected) {

        static Outcome winner() {
            return new Outcome(true, false, null, null);
        }

        static Outcome settled(String message) {
            return new Outcome(false, true, message, null);
        }

        static Outcome failed(RuntimeException failure) {
            return new Outcome(false, false, null, failure);
        }
    }
}
