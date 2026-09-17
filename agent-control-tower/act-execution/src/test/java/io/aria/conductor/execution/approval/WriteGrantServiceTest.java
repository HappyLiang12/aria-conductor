package io.aria.conductor.execution.approval;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

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
 * reuse for approval-side digests, so its exact output is pinned here.
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
