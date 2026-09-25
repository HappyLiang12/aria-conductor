package io.aria.conductor.app.e2e;

import io.aria.conductor.execution.security.ActorTokenService;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Harness-only custody of the run-scoped worker credential (Task 19 wiring).
 *
 * <p>The production run lifecycle mints a run's worker token through
 * {@link ActorTokenService#issueWorker(UUID, Instant)} and hands it only to that
 * run's processes; the harness must be able to serve the exact value back for
 * the worker-boundary arms (REST 403 and MCP parity), so this component is the
 * one place that mints it in-memory and remembers it per run: the harness
 * launcher delivers the cached value to the peer process as
 * {@code ACT_ACTOR_TOKEN} and the worker-token route returns the same value.
 * No second token mechanism exists; both paths call the committed service.
 *
 * <p>The token is memory-only and never persisted or logged. Minting is
 * idempotent per run so a repeated read cannot invalidate a token the run's
 * process already holds.
 */
public final class CoreE2eWorkerTokens {

    /** Environment variable the run's core processes receive the worker token in. */
    public static final String WORKER_TOKEN_ENV = "ACT_ACTOR_TOKEN";

    /** The harness credential window: long enough for a spec's arms, shorter than a stale grant. */
    static final Duration TOKEN_WINDOW = Duration.ofHours(1);

    private final ActorTokenService actorTokens;
    private final ConcurrentMap<UUID, String> issued = new ConcurrentHashMap<>();

    CoreE2eWorkerTokens(ActorTokenService actorTokens) {
        this.actorTokens = Objects.requireNonNull(actorTokens, "actorTokens");
    }

    /** The run's worker token, minted once through the committed service and reused. */
    public String tokenFor(UUID runId) {
        Objects.requireNonNull(runId, "runId");
        return issued.computeIfAbsent(runId, id -> actorTokens.issueWorker(id, Instant.now().plus(TOKEN_WINDOW)));
    }
}
