package io.aria.conductor.execution.approval;

import io.aria.conductor.common.model.AcpPermissionRequest;
import io.aria.conductor.common.repository.AcpPermissionRequestRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;

/**
 * One-use write grants (spec §6.3). A grant authorizes exactly one matching
 * operation; replay, changed arguments, a different run and an expired grant are
 * all refused. The repository mock is backed by an in-memory ledger store (the
 * {@link ApprovalGateConcurrencyTest} idiom) so the assertions exercise the real
 * one-use logic instead of a stubbed return value.
 */
@ExtendWith(MockitoExtension.class)
class WriteGrantServiceTest {

    private static final Instant T0 = Instant.parse("2026-09-22T12:00:00Z");

    @Mock private AcpPermissionRequestRepository repository;

    private final Map<UUID, AcpPermissionRequest> store = new ConcurrentHashMap<>();
    private Clock clock = Clock.fixed(T0, ZoneOffset.UTC);

    @BeforeEach
    void setUp() {
        lenient().when(repository.save(any(AcpPermissionRequest.class))).thenAnswer(inv -> {
            AcpPermissionRequest row = inv.getArgument(0);
            if (row.getId() == null) {
                row.setId(UUID.randomUUID());
            }
            store.put(row.getId(), row);
            return row;
        });
        lenient().when(repository.findByRunIdAndSessionIdAndRequestId(
                        any(UUID.class), any(String.class), any(String.class)))
                .thenAnswer(inv -> {
                    UUID runId = inv.getArgument(0);
                    String sessionId = inv.getArgument(1);
                    String requestId = inv.getArgument(2);
                    return store.values().stream()
                            .filter(r -> runId.equals(r.getRunId()))
                            .filter(r -> sessionId.equals(r.getSessionId()))
                            .filter(r -> requestId.equals(r.getRequestId()))
                            .findFirst();
                });
    }

    /** The brief's Step-1 contract, verbatim. */
    @Test
    void aSecondIdenticalWriteWithoutANewApprovalIsRefused() {
        UUID runId = java.util.UUID.fromString("00000000-0000-0000-0000-000000000201");
        var grants = new WriteGrantService(repository, clock);
        grants.grant(runId, "write_file", "sha256:abc", Instant.parse("2026-09-22T12:05:00Z"));
        assertThat(grants.consume(runId, "write_file", "sha256:abc")).isEqualTo(true);
        assertThat(grants.consume(runId, "write_file", "sha256:abc")).isEqualTo(false);
        assertThat(grants.consume(runId, "write_file", "sha256:def")).isEqualTo(false);
    }

    @Test
    void anExpiredGrantIsRefusedEvenForAMatchingCall() {
        UUID runId = UUID.fromString("00000000-0000-0000-0000-000000000202");
        WriteGrantService grants = new WriteGrantService(repository, clock);
        grants.grant(runId, "write_file", "sha256:abc", T0.plus(Duration.ofMinutes(1)));

        clock = Clock.fixed(T0.plus(Duration.ofMinutes(1)), ZoneOffset.UTC);
        WriteGrantService afterExpiry = new WriteGrantService(repository, clock);

        assertThat(afterExpiry.consume(runId, "write_file", "sha256:abc")).isEqualTo(false);
        // The refused call left no reusable authorization behind.
        assertThat(afterExpiry.consume(runId, "write_file", "sha256:abc")).isEqualTo(false);
    }

    @Test
    void aGrantOfAnotherRunNeverAuthorizesThisRun() {
        UUID grantedRun = UUID.fromString("00000000-0000-0000-0000-000000000203");
        UUID otherRun = UUID.fromString("00000000-0000-0000-0000-000000000204");
        WriteGrantService grants = new WriteGrantService(repository, clock);
        grants.grant(grantedRun, "write_file", "sha256:abc", T0.plus(Duration.ofMinutes(5)));

        assertThat(grants.consume(otherRun, "write_file", "sha256:abc")).isEqualTo(false);
        assertThat(grants.consume(grantedRun, "write_file", "sha256:abc")).isEqualTo(true);
    }

    @Test
    void aNewOperatorDecisionRefreshesTheSameIdenticalCall() {
        UUID runId = UUID.fromString("00000000-0000-0000-0000-000000000205");
        WriteGrantService grants = new WriteGrantService(repository, clock);
        grants.grant(runId, "write_file", "sha256:abc", T0.plus(Duration.ofMinutes(5)));
        assertThat(grants.consume(runId, "write_file", "sha256:abc")).isEqualTo(true);

        // A second approval of the identical call authorizes one more operation.
        grants.grant(runId, "write_file", "sha256:abc", T0.plus(Duration.ofMinutes(5)));
        assertThat(grants.consume(runId, "write_file", "sha256:abc")).isEqualTo(true);
        assertThat(grants.consume(runId, "write_file", "sha256:abc")).isEqualTo(false);
        // Still one ledger row for the identical call — never a second outstanding use.
        assertThat(store).hasSize(1);
    }

    @Test
    void concurrentConsumesOfOneGrantYieldExactlyOneUse() throws Exception {
        UUID runId = UUID.fromString("00000000-0000-0000-0000-000000000206");
        WriteGrantService grants = new WriteGrantService(repository, clock);
        grants.grant(runId, "write_file", "sha256:abc", T0.plus(Duration.ofMinutes(5)));

        int racers = 16;
        CountDownLatch go = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(racers);
        AtomicInteger granted = new AtomicInteger();
        for (int i = 0; i < racers; i++) {
            Thread.ofVirtual().start(() -> {
                try {
                    go.await();
                    if (grants.consume(runId, "write_file", "sha256:abc")) {
                        granted.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        go.countDown();
        assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();

        assertThat(granted.get()).isEqualTo(1);
    }

    @Test
    void grantRequiresTheAuthenticatedOperator() {
        UUID runId = UUID.fromString("00000000-0000-0000-0000-000000000207");
        WriteGrantService grants = new WriteGrantService(repository, clock);

        assertThatThrownBy(() -> grants.grant(
                new io.aria.conductor.common.security.ActorPrincipal(
                        io.aria.conductor.common.security.ActorPrincipal.Role.WORKER, runId, T0.plusSeconds(60)),
                runId, "write_file", "sha256:abc", T0.plus(Duration.ofMinutes(5))))
                .isInstanceOf(SecurityException.class)
                .hasMessage("Operator authority required");

        assertThat(store).isEmpty();
        assertThat(grants.consume(runId, "write_file", "sha256:abc")).isEqualTo(false);
    }

    @Test
    void noGrantExistsWithoutAGrantCall() {
        UUID runId = UUID.fromString("00000000-0000-0000-0000-000000000208");
        WriteGrantService grants = new WriteGrantService(repository, clock);

        assertThat(grants.consume(runId, "write_file", "sha256:abc")).isEqualTo(false);
        assertThat(store).isEmpty();
    }
}
