package io.aria.conductor.execution.approval;

import io.aria.conductor.agent.repository.RunRepository;
import io.aria.conductor.common.model.Run;
import io.aria.conductor.common.model.RunStatus;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * C4 ruling 5: run-scoped worker credentials. Tokens are opaque SecureRandom
 * material (>= 32 bytes entropy), TTL is capped at a documented constant, a
 * token stops resolving once its run reaches a terminal status, disappears or is
 * malformed (no agentId), or the token is revoked, and restart invalidation is
 * by construction (in-memory store).
 *
 * <p>All tokens here are synthetic ({@code wcp_test_...}); no real credential
 * is ever created or logged.
 */
class RunScopedCredentialServiceTest {

    private static final Instant T0 = Instant.parse("2026-09-18T12:00:00Z");

    private final RunRepository runRepository = mock(RunRepository.class);
    private final MutableClock clock = new MutableClock(T0);
    private final RunScopedCredentialService service =
            new RunScopedCredentialService(runRepository, Duration.ofMinutes(30), clock);

    private UUID activeRun(RunStatus status) {
        UUID runId = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();
        when(runRepository.findById(runId)).thenReturn(Optional.of(Run.builder()
                .id(runId).agentId(agentId).status(status).build()));
        return runId;
    }

    private WorkerScope scopeFor(UUID runId) {
        return service.resolve(service.issue(runId, T0.plus(Duration.ofMinutes(5)))).orElseThrow();
    }

    @Test
    void issue_returnsOpaqueTokenWithAtLeast32BytesEntropy() {
        UUID runId = activeRun(RunStatus.RUNNING);

        String token = service.issue(runId, T0.plus(Duration.ofMinutes(5)));

        assertThat(token).startsWith("wcp_");
        byte[] material = Base64.getUrlDecoder().decode(token.substring("wcp_".length()));
        assertThat(material.length).isGreaterThanOrEqualTo(32);
        // Opaque: no embedded claims — the token is random material, not a signed payload.
        assertThat(new String(material, StandardCharsets.ISO_8859_1)).doesNotContain(runId.toString());
    }

    @Test
    void issue_producesUniqueTokens_andReissueInvalidatesThePreviousOne() {
        UUID runId = activeRun(RunStatus.RUNNING);

        String first = service.issue(runId, T0.plus(Duration.ofMinutes(5)));
        String second = service.issue(runId, T0.plus(Duration.ofMinutes(5)));

        assertThat(second).isNotEqualTo(first);
        assertThat(service.resolve(first)).isEmpty();
        assertThat(service.resolve(second)).isPresent();
    }

    @Test
    void issue_capsTtlAtTheDocumentedMaximum() {
        UUID runId = activeRun(RunStatus.RUNNING);

        String token = service.issue(runId, T0.plus(Duration.ofHours(8)));

        assertThat(service.resolve(token).orElseThrow().expiresAt())
                .isEqualTo(T0.plus(RunScopedCredentialService.MAX_TTL));
    }

    @Test
    void issue_keepsConfiguredExpiry_whenSoonerThanTheCap() {
        UUID runId = activeRun(RunStatus.RUNNING);
        Instant sooner = T0.plus(Duration.ofMinutes(2));

        String token = service.issue(runId, sooner);

        assertThat(service.resolve(token).orElseThrow().expiresAt()).isEqualTo(sooner);
    }

    @Test
    void issue_rejectsNullRunId() {
        assertThatThrownBy(() -> service.issue(null, T0.plus(Duration.ofMinutes(5))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void resolve_returnsRunAndAgentScope_forALiveRun() {
        UUID runId = activeRun(RunStatus.RUNNING);

        WorkerScope scope = scopeFor(runId);

        assertThat(scope.runId()).isEqualTo(runId);
        assertThat(scope.agentId()).isEqualTo(runRepository.findById(runId).orElseThrow().getAgentId());
        assertThat(scope.expiresAt()).isEqualTo(T0.plus(Duration.ofMinutes(5)));
    }

    @Test
    void resolve_unknownOrMalformedToken_isEmpty() {
        assertThat(service.resolve("wcp_test_unknown")).isEmpty();
        assertThat(service.resolve("")).isEmpty();
        assertThat(service.resolve(null)).isEmpty();
        assertThat(service.resolve("not-a-worker-token")).isEmpty();
    }

    @Test
    void resolve_expiredToken_isEmpty_andTheTokenIsDropped() {
        UUID runId = activeRun(RunStatus.RUNNING);
        String token = service.issue(runId, T0.plus(Duration.ofMinutes(1)));

        clock.advance(Duration.ofMinutes(2));

        assertThat(service.resolve(token)).isEmpty();
        // The expired token cannot come back even if the clock is rewound.
        clock.set(T0);
        assertThat(service.resolve(token)).isEmpty();
    }

    @Test
    void resolve_terminalRunStatus_isEmpty_forEveryTerminalMember() {
        for (RunStatus terminal : new RunStatus[]{RunStatus.COMPLETED, RunStatus.FAILED,
                RunStatus.CANCELLED, RunStatus.ABORTED}) {
            UUID runId = activeRun(terminal);
            String token = service.issue(runId, T0.plus(Duration.ofMinutes(5)));

            assertThat(service.resolve(token))
                    .as("terminal run status %s must not resolve", terminal)
                    .isEmpty();
        }
    }

    @Test
    void resolve_nonTerminalRunStatuses_stillResolve() {
        for (RunStatus active : new RunStatus[]{RunStatus.PENDING, RunStatus.INITIALIZING,
                RunStatus.RUNNING, RunStatus.PAUSED}) {
            UUID runId = activeRun(active);
            String token = service.issue(runId, T0.plus(Duration.ofMinutes(5)));

            assertThat(service.resolve(token))
                    .as("active run status %s must resolve", active)
                    .isPresent();
        }
    }

    @Test
    void resolve_deletedRun_isEmpty() {
        UUID runId = activeRun(RunStatus.RUNNING);
        String token = service.issue(runId, T0.plus(Duration.ofMinutes(5)));
        when(runRepository.findById(runId)).thenReturn(Optional.empty());

        assertThat(service.resolve(token)).isEmpty();
    }

    @Test
    void resolve_runWithoutAgentId_isEmpty_insteadOfThrowing() {
        UUID runId = UUID.randomUUID();
        when(runRepository.findById(runId)).thenReturn(Optional.of(Run.builder()
                .id(runId).agentId(null).status(RunStatus.RUNNING).build()));
        String token = service.issue(runId, T0.plus(Duration.ofMinutes(5)));

        // A malformed run row must deny the auth check, not surface an IllegalArgumentException/500.
        assertThat(service.resolve(token)).isEmpty();
        // The unusable credential is dropped like the other invalid token states.
        assertThat(service.resolve(token)).isEmpty();
    }

    @Test
    void revoke_makesTheRunTokenUnresolvable_andReissueWorksAfterwards() {
        UUID runId = activeRun(RunStatus.RUNNING);
        String token = service.issue(runId, T0.plus(Duration.ofMinutes(5)));

        service.revoke(runId);

        assertThat(service.resolve(token)).isEmpty();
        String reissued = service.issue(runId, T0.plus(Duration.ofMinutes(5)));
        assertThat(service.resolve(reissued)).isPresent();
    }

    @Test
    void restartInvalidation_aFreshInstanceCannotResolveTokensFromAnotherInstance() {
        UUID runId = activeRun(RunStatus.RUNNING);
        String token = new RunScopedCredentialService(runRepository, Duration.ofMinutes(30), clock)
                .issue(runId, T0.plus(Duration.ofMinutes(5)));

        // In-memory store: a new process (new instance) has never seen that token.
        assertThat(service.resolve(token)).isEmpty();
    }

    /** Settable clock so TTL and expiry are exercised without sleeping. */
    private static final class MutableClock extends Clock {
        private Instant now;

        private MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        void set(Instant instant) {
            now = instant;
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
