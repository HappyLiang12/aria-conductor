package io.aria.conductor.execution.approval;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * C4 ruling 6: one-use write grants bound to (runId, toolName, argsDigest).
 * A grant is consumed by the first matching call; an identical replay consumes
 * nothing; different arguments never match; two identical concurrent calls need
 * separate grants (design section 6.1) — exactly one winner; TTL is bounded and
 * grants die with the run.
 *
 * <p>{@link WriteGrantService#argsDigest(Map)} is the frozen canonicalizer C2/C3
 * reuse for approval-side digests, and
 * {@link WriteGrantService#effectiveArgsDigest(Map)} is the frozen binding digest
 * both sides must use (top-level nulls dropped), so their exact outputs are
 * pinned here.
 */
class WriteGrantServiceTest {

    private static final Instant T0 = Instant.parse("2026-09-18T12:00:00Z");

    private final MutableClock clock = new MutableClock(T0);
    private final WriteGrantService service = new WriteGrantService(clock);

    private static final UUID RUN = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID OTHER_RUN = UUID.fromString("22222222-2222-2222-2222-222222222222");

    private static String digest(String title, String description) {
        return WriteGrantService.argsDigest(Map.of("title", title, "description", description));
    }

    @Test
    void consume_matchingGrant_succeedsExactlyOnce() {
        String d = digest("report", "content");
        service.grant(RUN, "generate_report", d);

        assertThat(service.consume(RUN, "generate_report", d)).isTrue();
        // Identical replay consumes nothing and is denied.
        assertThat(service.consume(RUN, "generate_report", d)).isFalse();
    }

    @Test
    void consume_differentArgs_neverMatches_andLeavesTheGrantIntact() {
        String d = digest("report", "content");
        service.grant(RUN, "generate_report", d);

        assertThat(service.consume(RUN, "generate_report", digest("report", "other content"))).isFalse();
        // The mismatched attempt consumed nothing: the approved attempt still works.
        assertThat(service.consume(RUN, "generate_report", d)).isTrue();
    }

    @Test
    void consume_differentToolOrRun_isDenied() {
        String d = digest("report", "content");
        service.grant(RUN, "generate_report", d);

        assertThat(service.consume(RUN, "amend_report", d)).isFalse();
        assertThat(service.consume(OTHER_RUN, "generate_report", d)).isFalse();
        assertThat(service.consume(RUN, "generate_report", d)).isTrue();
    }

    @Test
    void consume_unknownGrant_isDenied() {
        assertThat(service.consume(RUN, "generate_report", digest("a", "b"))).isFalse();
    }

    @Test
    void consume_expiredGrant_isDenied() {
        String d = digest("report", "content");
        service.grant(RUN, "generate_report", d);

        clock.advance(WriteGrantService.GRANT_TTL.plus(Duration.ofSeconds(1)));

        assertThat(service.consume(RUN, "generate_report", d)).isFalse();
        // The consumed (expired) entry empties the queue, so the key is dropped too.
        assertThat(service.trackedKeyCount()).isZero();
    }

    @Test
    void consume_skipsExpiredEntries_soALiveGrantBehindADeadHeadStaysConsumable() {
        String d = digest("report", "content");
        service.grant(RUN, "generate_report", d);
        clock.advance(WriteGrantService.GRANT_TTL.plus(Duration.ofSeconds(1)));
        // A second approval's grant is minted after the first one lapsed unused: the dead head
        // must not deny the live grant sitting behind it (the same prune the retry probe does).
        service.grant(RUN, "generate_report", d);

        assertThat(service.consume(RUN, "generate_report", d)).isTrue();
        assertThat(service.consume(RUN, "generate_report", d)).isFalse();
        assertThat(service.trackedKeyCount()).isZero();
    }

    @Test
    void consume_dropsTheKeyOnceItsQueueIsEmpty_andNewGrantsStillWork() {
        String d = digest("report", "content");
        service.grant(RUN, "generate_report", d);
        assertThat(service.trackedKeyCount()).isEqualTo(1);

        assertThat(service.consume(RUN, "generate_report", d)).isTrue();
        // A fully consumed tuple tracks no queue: a long-lived process cannot accumulate keys.
        assertThat(service.trackedKeyCount()).isZero();

        // Removing the key does not poison the tuple: a new grant is tracked and consumable.
        service.grant(RUN, "generate_report", d);
        assertThat(service.trackedKeyCount()).isEqualTo(1);
        assertThat(service.consume(RUN, "generate_report", d)).isTrue();
        assertThat(service.trackedKeyCount()).isZero();
    }

    @Test
    void revoke_dropsEveryGrantOfThatRun_andLeavesOtherRunsAlone() {
        String d = digest("report", "content");
        service.grant(RUN, "generate_report", d);
        service.grant(RUN, "amend_report", d);
        service.grant(OTHER_RUN, "generate_report", d);

        service.revoke(RUN);

        assertThat(service.consume(RUN, "generate_report", d)).isFalse();
        assertThat(service.consume(RUN, "amend_report", d)).isFalse();
        assertThat(service.consume(OTHER_RUN, "generate_report", d)).isTrue();
    }

    @Test
    void grant_rejectsNullArguments() {
        assertThatThrownBy(() -> service.grant(null, "generate_report", "d"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.grant(RUN, null, "d"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.grant(RUN, "generate_report", null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void concurrentConsume_ofOneGrant_hasExactlyOneWinner() throws Exception {
        String d = digest("report", "content");
        service.grant(RUN, "generate_report", d);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Boolean>> outcomes = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                outcomes.add(pool.submit(() -> {
                    start.await();
                    return service.consume(RUN, "generate_report", d);
                }));
            }
            start.countDown();
            long winners = 0;
            for (Future<Boolean> outcome : outcomes) {
                if (outcome.get(10, TimeUnit.SECONDS)) {
                    winners++;
                }
            }
            assertThat(winners).isEqualTo(1L);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void concurrentConsume_ofTwoSeparateGrants_letsBothIdenticalCallsThrough() throws Exception {
        String d = digest("report", "content");
        // Design section 6.1: two identical concurrent tool calls need separate grants.
        service.grant(RUN, "generate_report", d);
        service.grant(RUN, "generate_report", d);

        assertThat(service.consume(RUN, "generate_report", d)).isTrue();
        assertThat(service.consume(RUN, "generate_report", d)).isTrue();
    }

    // ── retry preparation (F2: one approval authorizes one execution) ─────────

    @Test
    void prepareRetryGrant_reusesThePendingGrant_soExactlyOneConsumeSucceeds() {
        String d = digest("report", "content");
        service.grant(RUN, "generate_report", d);

        assertThat(service.prepareRetryGrant(RUN, "generate_report", d))
                .isEqualTo(WriteGrantService.RetryGrant.REUSED_PENDING);

        // The pending authorization is untouched: still exactly one consumable grant.
        assertThat(service.consume(RUN, "generate_report", d)).isTrue();
        assertThat(service.consume(RUN, "generate_report", d)).isFalse();
    }

    @Test
    void prepareRetryGrant_renewsOnlyAfterTheGrantExpiredUnconsumed() {
        String d = digest("report", "content");
        service.grant(RUN, "generate_report", d);
        clock.advance(WriteGrantService.GRANT_TTL.plus(Duration.ofSeconds(1)));

        assertThat(service.prepareRetryGrant(RUN, "generate_report", d))
                .isEqualTo(WriteGrantService.RetryGrant.RENEWED);

        // The dead predecessor was replaced, not accumulated: one fresh grant, one execution.
        assertThat(service.consume(RUN, "generate_report", d)).isTrue();
        assertThat(service.consume(RUN, "generate_report", d)).isFalse();
        assertThat(service.trackedKeyCount()).isZero();
    }

    @Test
    void prepareRetryGrant_refusesOnceTheGrantWasConsumed() {
        String d = digest("report", "content");
        service.grant(RUN, "generate_report", d);
        assertThat(service.consume(RUN, "generate_report", d)).isTrue();

        assertThat(service.prepareRetryGrant(RUN, "generate_report", d))
                .isEqualTo(WriteGrantService.RetryGrant.ALREADY_CONSUMED);
        // No authorization was minted: the invocation stays executed exactly once.
        assertThat(service.consume(RUN, "generate_report", d)).isFalse();
    }

    @Test
    void prepareRetryGrant_refusesForATupleThatWasNeverGranted() {
        assertThat(service.prepareRetryGrant(RUN, "generate_report", digest("a", "b")))
                .isEqualTo(WriteGrantService.RetryGrant.ALREADY_CONSUMED);
    }

    @Test
    void prepareRetryGrant_ofTwoApprovedIdenticalCalls_neverMintsASecondAuthorization() {
        String d = digest("report", "content");
        // Two separate approvals of the same invocation: one pending grant each (design 6.1).
        service.grant(RUN, "generate_report", d);
        service.grant(RUN, "generate_report", d);

        // A retry for one of them must not add a third consumable authorization.
        assertThat(service.prepareRetryGrant(RUN, "generate_report", d))
                .isEqualTo(WriteGrantService.RetryGrant.REUSED_PENDING);

        assertThat(service.consume(RUN, "generate_report", d)).isTrue();
        assertThat(service.consume(RUN, "generate_report", d)).isTrue();
        assertThat(service.consume(RUN, "generate_report", d)).isFalse();
    }

    @Test
    void prepareRetryGrant_dropsDeadEntries_soTheLiveGrantStaysConsumable() {
        String d = digest("report", "content");
        service.grant(RUN, "generate_report", d);
        clock.advance(WriteGrantService.GRANT_TTL.plus(Duration.ofSeconds(1)));
        // A second approval's live grant now sits behind the first one's dead entry.
        service.grant(RUN, "generate_report", d);

        assertThat(service.prepareRetryGrant(RUN, "generate_report", d))
                .isEqualTo(WriteGrantService.RetryGrant.REUSED_PENDING);

        // Without the prune, this consume would poll the dead head and deny the live grant.
        assertThat(service.consume(RUN, "generate_report", d)).isTrue();
        assertThat(service.consume(RUN, "generate_report", d)).isFalse();
    }

    @Test
    void prepareRetryGrant_isIdempotentUnderConcurrency_exactlyOneConsumeWins() throws Exception {
        String d = digest("report", "content");
        service.grant(RUN, "generate_report", d);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<WriteGrantService.RetryGrant>> outcomes = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                outcomes.add(pool.submit(() -> {
                    start.await();
                    return service.prepareRetryGrant(RUN, "generate_report", d);
                }));
            }
            start.countDown();
            for (Future<WriteGrantService.RetryGrant> outcome : outcomes) {
                assertThat(outcome.get(10, TimeUnit.SECONDS))
                        .isEqualTo(WriteGrantService.RetryGrant.REUSED_PENDING);
            }
            assertThat(service.consume(RUN, "generate_report", d)).isTrue();
            assertThat(service.consume(RUN, "generate_report", d)).isFalse();
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void prepareRetryGrant_rejectsNullArguments() {
        assertThatThrownBy(() -> service.prepareRetryGrant(null, "generate_report", "d"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.prepareRetryGrant(RUN, null, "d"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.prepareRetryGrant(RUN, "generate_report", null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ── F2 atomicity: the dequeue and the retry probe share one per-key critical section ─────

    /**
     * F2 atomicity pin: the retry probe and the dequeue must run in ONE per-key critical section
     * of the same {@code grants} map. Otherwise a concurrent consume can poll the only live entry
     * (authorizing an execution) between the retry's non-empty observation and its branch
     * decision, and the retry then takes the RENEWED branch and mints a second authorization —
     * precisely the consume-first bypass the mechanism exists to close.
     *
     * <p>Deterministic and single-threaded, following the trapping-map pattern of
     * {@link #grant_enqueuedWhileTheKeyIsBeingEmptied_isStillConsumable}: the map double records,
     * for every poll of a grant queue, whether the poll ran inside a per-key map operation on that
     * key. Both operations are map operations of this one map, so a poll recorded outside one is
     * the race window itself (against the earlier lock-free `pending.poll()` the count is zero).
     */
    @Test
    void consume_dequeuesInsideTheKeysOwnMapOperation_soNoRetryProbeCanSlipIn() throws Exception {
        String d = digest("report", "content");
        WriteGrantService raced = new WriteGrantService(clock);
        GrantOperationWitness witness = new GrantOperationWitness();
        Field field = WriteGrantService.class.getDeclaredField("grants");
        field.setAccessible(true);
        field.set(raced, witness.trappingMap());

        raced.grant(RUN, "generate_report", d);
        // The retry probe runs as a per-key operation of the same map (the shared critical
        // section), then the consume dequeues.
        assertThat(raced.prepareRetryGrant(RUN, "generate_report", d))
                .isEqualTo(WriteGrantService.RetryGrant.REUSED_PENDING);
        assertThat(raced.consume(RUN, "generate_report", d)).isTrue();

        assertThat(witness.dequeuesInsideTheirKeyOperation())
                .as("the dequeue that authorizes the execution must run inside the key's"
                        + " per-key map operation (one critical section shared with the retry probe)")
                .isEqualTo(1);
        assertThat(witness.dequeuesOutsideAnyOperation())
                .as("a poll outside a map operation is the interleaving window a retry probe"
                        + " can renew through")
                .isZero();
        assertThat(raced.trackedKeyCount()).isZero();
    }

    /**
     * Operational companion of {@link #consume_dequeuesInsideTheKeysOwnMapOperation_soNoRetryProbeCanSlipIn}:
     * race a real consume against a real retry probe under a start latch and assert the forbidden
     * combination never occurs — a consume that authorized an execution must never be followed by
     * a renewal (a second authorization for the same grant). With the shared critical section
     * every schedule is one of the two legal serial orders, so exactly one authorization exists
     * after the race; the deterministic trap pin above is the primary witness.
     */
    @Test
    void consumeRacedWithARetryProbe_neverRenewsAfterAConsumeAuthorizedTheExecution() throws Exception {
        String d = digest("report", "content");
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int round = 0; round < 100; round++) {
                WriteGrantService raced = new WriteGrantService(clock);
                raced.grant(RUN, "generate_report", d);
                CountDownLatch start = new CountDownLatch(1);
                Future<Boolean> consumed = pool.submit(() -> {
                    start.await();
                    return raced.consume(RUN, "generate_report", d);
                });
                Future<WriteGrantService.RetryGrant> retried = pool.submit(() -> {
                    start.await();
                    return raced.prepareRetryGrant(RUN, "generate_report", d);
                });
                start.countDown();
                boolean consumeWon = consumed.get(10, TimeUnit.SECONDS);
                WriteGrantService.RetryGrant retryOutcome = retried.get(10, TimeUnit.SECONDS);

                assertThat(consumeWon && retryOutcome == WriteGrantService.RetryGrant.RENEWED)
                        .as("round %d: a renewal after consume=%s would be a second authorization",
                                round, consumeWon)
                        .isFalse();
                // One approval, one execution: the race plus the follow-up consume authorize at
                // most one call, whatever the schedule was.
                int authorizations = consumeWon ? 1 : 0;
                if (raced.consume(RUN, "generate_report", d)) {
                    authorizations++;
                }
                assertThat(authorizations).as("round %d: authorizations", round).isEqualTo(1);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void grant_enqueuedWhileTheKeyIsBeingEmptied_isStillConsumable() throws Exception {
        // Deterministic interleaving: a consume polls this key empty and drops it in the window
        // between the grant's map lookup and its enqueue. The grant must survive the race.
        WriteGrantService raced = new WriteGrantService(clock);
        Field field = WriteGrantService.class.getDeclaredField("grants");
        field.setAccessible(true);
        ConcurrentMap<Object, ConcurrentLinkedQueue<Instant>> trapping = new ConcurrentHashMap<>() {
            @Override
            public ConcurrentLinkedQueue<Instant> computeIfAbsent(
                    Object key, Function<? super Object, ? extends ConcurrentLinkedQueue<Instant>> mappingFunction) {
                ConcurrentLinkedQueue<Instant> queue = super.computeIfAbsent(key, mappingFunction);
                queue.poll();
                super.computeIfPresent(key, (k, q) -> q.isEmpty() ? null : q);
                return queue;
            }
        };
        field.set(raced, trapping);

        raced.grant(RUN, "amend_report", "digest-a");

        assertThat(raced.consume(RUN, "amend_report", "digest-a")).isTrue();
    }

    @Test
    void argsDigest_isKeyOrderIndependent_andStable() {
        Map<String, Object> ordered = new LinkedHashMap<>();
        ordered.put("a", "x");
        ordered.put("b", 2);
        Map<String, Object> shuffled = new LinkedHashMap<>();
        shuffled.put("b", 2);
        shuffled.put("a", "x");

        String first = WriteGrantService.argsDigest(ordered);
        assertThat(WriteGrantService.argsDigest(shuffled)).isEqualTo(first);
        assertThat(first).isEqualTo(WriteGrantService.argsDigest(ordered));
        assertThat(first).matches("[0-9a-f]{64}");
    }

    @Test
    void argsDigest_matchesTheFrozenCanonicalVector() throws Exception {
        // Frozen vector: canonical JSON {"a":"x","b":2} with sorted keys and SHA-256 hex.
        String canonicalJson = "{\"a\":\"x\",\"b\":2}";
        String expected = java.util.HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(canonicalJson.getBytes(StandardCharsets.UTF_8)));

        assertThat(WriteGrantService.argsDigest(linkedMap("a", "x", "b", 2))).isEqualTo(expected);
    }

    @Test
    void argsDigest_preservesNulls_andDistinguishesAbsentFromNull() {
        Map<String, Object> withNull = new LinkedHashMap<>();
        withNull.put("a", null);
        Map<String, Object> sameContent = new LinkedHashMap<>();
        sameContent.put("a", null);
        Map<String, Object> absent = new LinkedHashMap<>();

        assertThat(WriteGrantService.argsDigest(withNull))
                .isNotEqualTo(WriteGrantService.argsDigest(absent));
        assertThat(WriteGrantService.argsDigest(withNull))
                .isEqualTo(WriteGrantService.argsDigest(sameContent));
        // A JSON null value is not the string "null".
        assertThat(WriteGrantService.argsDigest(withNull))
                .isNotEqualTo(WriteGrantService.argsDigest(Map.of("a", "null")));
        assertThat(WriteGrantService.argsDigest(null)).isEqualTo(WriteGrantService.argsDigest(absent));
    }

    @Test
    void argsDigest_normalizesNestedMapsRecursively() {
        Map<String, Object> nestedOrdinary = new LinkedHashMap<>();
        nestedOrdinary.put("outer", linkedMap("y", true, "x", "1"));
        Map<String, Object> nestedReordered = new LinkedHashMap<>();
        nestedReordered.put("outer", linkedMap("x", "1", "y", true));

        assertThat(WriteGrantService.argsDigest(nestedOrdinary))
                .isEqualTo(WriteGrantService.argsDigest(nestedReordered));
    }

    @Test
    void argsDigest_listsAreOrderSensitive_valuesAreEscaped() {
        assertThat(WriteGrantService.argsDigest(Map.of("k", List.of("a", "b"))))
                .isNotEqualTo(WriteGrantService.argsDigest(Map.of("k", List.of("b", "a"))));
        assertThat(WriteGrantService.argsDigest(Map.of("k", "a\"b\\c\n")))
                .isNotEqualTo(WriteGrantService.argsDigest(Map.of("k", "a\"b\\c")));
    }

    @Test
    void argsDigest_distinguishesTypesAndValues() {
        assertThat(WriteGrantService.argsDigest(Map.of("k", 1)))
                .isNotEqualTo(WriteGrantService.argsDigest(Map.of("k", "1")));
        assertThat(WriteGrantService.argsDigest(Map.of("k", true)))
                .isNotEqualTo(WriteGrantService.argsDigest(Map.of("k", "true")));
        assertThat(WriteGrantService.argsDigest(Map.of("k", "x")))
                .isNotEqualTo(WriteGrantService.argsDigest(Map.of("k", "y")));
    }

    @Test
    void argsDigest_rendersUuidsAsTheirStringForm() {
        UUID id = UUID.fromString("33333333-3333-3333-3333-333333333333");

        assertThat(WriteGrantService.argsDigest(Map.of("id", id)))
                .isEqualTo(WriteGrantService.argsDigest(Map.of("id", id.toString())));
    }

    // ── frozen effective-argument digest (C2/C3 binding contract) ────────────

    @Test
    void effectiveArgsDigest_dropsTopLevelNulls_soOmittedOptionalsMatchTheirNullBinding() {
        Map<String, Object> withNull = singleEntry("a", 1);
        withNull.put("b", null);

        // A worker that omits "b" binds it to null; both sides must digest {a:1}.
        assertThat(WriteGrantService.effectiveArgsDigest(withNull))
                .isEqualTo(WriteGrantService.effectiveArgsDigest(Map.of("a", 1)));
        assertThat(WriteGrantService.effectiveArgsDigest(withNull))
                .isNotEqualTo(WriteGrantService.argsDigest(withNull));

        Map<String, Object> allNull = singleEntry("a", null);
        allNull.put("b", null);
        assertThat(WriteGrantService.effectiveArgsDigest(allNull))
                .isEqualTo(WriteGrantService.effectiveArgsDigest(Map.of()));
        assertThat(WriteGrantService.effectiveArgsDigest(null))
                .isEqualTo(WriteGrantService.effectiveArgsDigest(Map.of()));
        assertThat(WriteGrantService.effectiveArgsDigest(Map.of()))
                .isEqualTo(WriteGrantService.argsDigest(Map.of()));
    }

    @Test
    void effectiveArgsDigest_keepsNestedNulls_andMatchesArgsDigestForNonNullValues() {
        // Only the top level is effective: a nested null is part of the payload.
        Map<String, Object> nestedNull = singleEntry("outer", singleEntry("x", null));
        assertThat(WriteGrantService.effectiveArgsDigest(nestedNull))
                .isEqualTo(WriteGrantService.argsDigest(nestedNull));
        assertThat(WriteGrantService.effectiveArgsDigest(nestedNull))
                .isNotEqualTo(WriteGrantService.effectiveArgsDigest(Map.of("outer", Map.of())));

        Map<String, Object> values = linkedMap("a", "x", "b", 2);
        assertThat(WriteGrantService.effectiveArgsDigest(values))
                .isEqualTo(WriteGrantService.argsDigest(values));

        // argsDigest itself is unchanged: it still preserves top-level nulls.
        assertThat(WriteGrantService.argsDigest(singleEntry("a", null)))
                .isNotEqualTo(WriteGrantService.argsDigest(Map.of()));
    }

    /**
     * Map double for the F2 atomicity pin (the trapping-map pattern of
     * {@link #grant_enqueuedWhileTheKeyIsBeingEmptied_isStillConsumable}): it records every poll
     * of a grant queue together with the per-key map operation that is active at that moment.
     * {@code consume} and {@code prepareRetryGrant} must both be operations of ONE map, so the
     * decisive observation is whether the dequeue ran inside an operation on its own key. Only
     * the fusing operations count as critical sections: a plain {@code get} is lock-free and is
     * exactly where the earlier {@code consume} dequeued from.
     */
    private static final class GrantOperationWitness {

        private final Deque<Object> activeOperations = new ArrayDeque<>();
        private final List<Boolean> pollsInsideTheirKeyOperation = new ArrayList<>();

        ConcurrentMap<Object, ConcurrentLinkedQueue<Instant>> trappingMap() {
            return new ConcurrentHashMap<>() {
                @Override
                public ConcurrentLinkedQueue<Instant> compute(Object key,
                        BiFunction<? super Object, ? super ConcurrentLinkedQueue<Instant>,
                                ? extends ConcurrentLinkedQueue<Instant>> remappingFunction) {
                    return super.compute(key, (k, queue) ->
                            inOperation(k, () -> witnessed(k, remappingFunction.apply(k, queue))));
                }

                @Override
                public ConcurrentLinkedQueue<Instant> computeIfPresent(Object key,
                        BiFunction<? super Object, ? super ConcurrentLinkedQueue<Instant>,
                                ? extends ConcurrentLinkedQueue<Instant>> remappingFunction) {
                    return super.computeIfPresent(key, (k, queue) ->
                            inOperation(k, () -> remappingFunction.apply(k, queue)));
                }
            };
        }

        /** Wraps a freshly created queue so its dequeues are witnessed (idempotent). */
        private ConcurrentLinkedQueue<Instant> witnessed(Object key, ConcurrentLinkedQueue<Instant> queue) {
            if (queue == null || queue instanceof WitnessedQueue) {
                return queue;
            }
            return new WitnessedQueue(key, queue, activeOperations, pollsInsideTheirKeyOperation);
        }

        private ConcurrentLinkedQueue<Instant> inOperation(Object key, Supplier<ConcurrentLinkedQueue<Instant>> operation) {
            activeOperations.push(key);
            try {
                return operation.get();
            } finally {
                activeOperations.pop();
            }
        }

        long dequeuesInsideTheirKeyOperation() {
            return pollsInsideTheirKeyOperation.stream().filter(Boolean.TRUE::equals).count();
        }

        long dequeuesOutsideAnyOperation() {
            return pollsInsideTheirKeyOperation.stream().filter(Boolean.FALSE::equals).count();
        }
    }

    /** A grant queue that reports to the witness whether each dequeue ran inside its key's operation. */
    private static final class WitnessedQueue extends ConcurrentLinkedQueue<Instant> {

        private final Object ownKey;
        private final Deque<Object> activeOperations;
        private final List<Boolean> pollsInsideTheirKeyOperation;

        private WitnessedQueue(Object ownKey, Collection<? extends Instant> entries,
                               Deque<Object> activeOperations,
                               List<Boolean> pollsInsideTheirKeyOperation) {
            this.ownKey = ownKey;
            this.activeOperations = activeOperations;
            this.pollsInsideTheirKeyOperation = pollsInsideTheirKeyOperation;
            addAll(entries);
        }

        @Override
        public Instant poll() {
            pollsInsideTheirKeyOperation.add(ownKey.equals(activeOperations.peek()));
            return super.poll();
        }
    }

    private static Map<String, Object> singleEntry(String key, Object value) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put(key, value);
        return map;
    }

    private static Map<String, Object> linkedMap(String k1, Object v1, String k2, Object v2) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put(k1, v1);
        map.put(k2, v2);
        return map;
    }

    /** Settable clock so TTL is exercised without sleeping. */
    private static final class MutableClock extends Clock {
        private Instant now;

        private MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
