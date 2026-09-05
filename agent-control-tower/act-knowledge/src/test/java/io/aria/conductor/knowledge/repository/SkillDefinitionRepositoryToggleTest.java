package io.aria.conductor.knowledge.repository;

import io.aria.conductor.knowledge.selfimprove.SkillDefinition;
import io.aria.conductor.knowledge.selfimprove.SkillDefinitionRepository;
import io.aria.conductor.test.DataJpaTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins ATOMIC flip semantics for {@link SkillDefinitionRepository#toggleEnabled}.
 * <p>
 * The controller previously toggled via read-modify-write
 * ({@code findById → setEnabled(!enabled) → save}), which loses updates when
 * two requests read the same initial state: both write the same flipped value,
 * so one toggle is swallowed. E2E {@code skill-lifecycle} test D fires 10
 * concurrent toggles and asserts the final state equals the initial state — a
 * premise that only holds if every toggle flips exactly once. CI failed on
 * that assertion twice consecutively on PR #77 (run 33965452850 + rerun).
 * <p>
 * These tests run against real H2 via the shared {@link DataJpaTestBase} slice.
 */
class SkillDefinitionRepositoryToggleTest extends DataJpaTestBase {

    private static final Instant STALE_STAMP = Instant.parse("2020-01-01T00:00:00Z");

    @Autowired
    SkillDefinitionRepository repository;

    @Autowired
    PlatformTransactionManager transactionManager;

    private SkillDefinition.SkillDefinitionBuilder skill(boolean enabled) {
        return SkillDefinition.builder()
                .id(UUID.randomUUID().toString())
                .name("toggle-" + UUID.randomUUID())
                .stage("SKILL")
                .enabled(enabled)
                .usageCount(0)
                .updatedAt(STALE_STAMP);
    }

    // ==================== flip semantics ====================

    @Test
    void toggleEnabled_flipsExactlyOncePerCall_bothDirections() {
        SkillDefinition skill = repository.saveAndFlush(skill(true).build());
        flushAndClear();

        assertThat(repository.toggleEnabled(skill.getId(), Instant.now())).isEqualTo(1);
        flushAndClear();
        assertThat(repository.findById(skill.getId()).orElseThrow().isEnabled()).isFalse();

        assertThat(repository.toggleEnabled(skill.getId(), Instant.now())).isEqualTo(1);
        flushAndClear();
        assertThat(repository.findById(skill.getId()).orElseThrow().isEnabled()).isTrue();
    }

    @Test
    void toggleEnabled_returnsZero_whenSkillMissing() {
        assertThat(repository.toggleEnabled("no-such-skill", Instant.now())).isZero();
    }

    @Test
    void toggleEnabled_refreshesUpdatedAt_likeTheOldPreUpdateContract() {
        // The old RMW path refreshed updatedAt via @PreUpdate on save(); bulk
        // JPQL bypasses lifecycle callbacks, so the query must SET it itself.
        SkillDefinition skill = repository.saveAndFlush(skill(true).build());
        flushAndClear();

        assertThat(repository.toggleEnabled(skill.getId(), Instant.now())).isEqualTo(1);
        flushAndClear();
        assertThat(repository.findById(skill.getId()).orElseThrow().getUpdatedAt())
                .isAfter(STALE_STAMP);
    }

    // ==================== interleaved read (deterministic lost-update pin) ====================

    @Test
    void toggleEnabled_afterStaleRead_stillFlips() {
        SkillDefinition skill = repository.saveAndFlush(skill(true).build());
        flushAndClear();

        // Snapshot taken BEFORE another actor toggles — exactly the stale copy
        // the old read-modify-write controller held across its two statements.
        SkillDefinition stale = repository.findById(skill.getId()).orElseThrow();
        assertThat(stale.isEnabled()).isTrue();
        flushAndClear();

        assertThat(repository.toggleEnabled(skill.getId(), Instant.now())).isEqualTo(1);
        flushAndClear();
        assertThat(repository.findById(skill.getId()).orElseThrow().isEnabled()).isFalse();

        // A toggle after the interleaved stale read still flips exactly once.
        assertThat(repository.toggleEnabled(skill.getId(), Instant.now())).isEqualTo(1);
        flushAndClear();
        assertThat(repository.findById(skill.getId()).orElseThrow().isEnabled()).isTrue();
    }

    // ==================== real concurrency ====================

    @Test
    void toggleEnabled_concurrentToggles_convergeToInitialXorFlipParity() throws Exception {
        // Seed a COMMITTED row: workers toggle on their own connections, so the
        // row must be visible outside this test's (rolled-back) transaction.
        TransactionTemplate committedTx = new TransactionTemplate(transactionManager);
        committedTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        String id = committedTx.execute(status -> repository.saveAndFlush(skill(true).build()).getId());
        boolean initialEnabled = true;
        int threads = 8; // even count → final state must equal the initial state

        TransactionTemplate workerTx = new TransactionTemplate(transactionManager);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        try {
            List<Future<Integer>> flips = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                flips.add(pool.submit(() -> {
                    ready.countDown();
                    if (!go.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("start latch timed out");
                    }
                    return workerTx.execute(s -> repository.toggleEnabled(id, Instant.now()));
                }));
            }
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            for (Future<Integer> flip : flips) {
                // every concurrent toggle must flip exactly once — no lost updates
                assertThat(flip.get(5, TimeUnit.SECONDS)).isEqualTo(1);
            }
        } finally {
            pool.shutdownNow();
        }

        entityManager.clear();
        SkillDefinition after = repository.findById(id).orElseThrow();
        assertThat(after.isEnabled())
                .as("an even number of serialized atomic flips must return to the initial state")
                .isEqualTo(initialEnabled);
    }

    // ==================== semantics pin (guards against RMW regression) ====================

    @Test
    void toggleEnabled_isAtomicModifyingQuery() throws Exception {
        Method method = SkillDefinitionRepository.class
                .getMethod("toggleEnabled", String.class, Instant.class);
        assertThat(method.getReturnType()).isEqualTo(int.class);

        Modifying modifying = method.getAnnotation(Modifying.class);
        assertThat(modifying).as("toggleEnabled must be a @Modifying bulk update").isNotNull();
        assertThat(modifying.clearAutomatically())
                .as("persistence context must be cleared so the controller's re-fetch sees the flipped row")
                .isTrue();

        Query query = method.getAnnotation(Query.class);
        assertThat(query).as("toggleEnabled must carry an explicit atomic JPQL update").isNotNull();
        assertThat(query.value())
                .contains("CASE s.enabled WHEN true THEN false ELSE true END")
                .contains("WHERE s.id = :id");
    }
}
