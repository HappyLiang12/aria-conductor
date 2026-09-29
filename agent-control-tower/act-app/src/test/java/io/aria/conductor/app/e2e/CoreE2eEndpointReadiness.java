package io.aria.conductor.app.e2e;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Bounded readiness wait of the harness core endpoints (Task 19 fix round 1):
 * the harness adapters never open a session against an endpoint the launched
 * peer or bridge has not proven it serves.
 *
 * <p>This mirrors the production host backend's launch gate
 * ({@code HostExecutionBackend.authenticateRuntimeEndpoint}): readiness is an
 * <em>authenticated</em> answer from the runtime the launch produced, not a bare
 * TCP connect and not an unauthenticated health route. Nothing bound yet (a
 * connection refusal) is retried until the window elapses; an answer that is not
 * the expected proof -- a refusal, a foreign service, a binding naming another
 * run -- is definitive and refuses the session immediately. The wait is only a
 * bounded ordering fix for the launch/session race: it adds no retry of the
 * session itself, and it never relaxes a peer or bridge check.
 *
 * <p>Two proofs exist, one per harness runtime shape:
 * <ul>
 *   <li>the mock OpenCode peer's authenticated control route
 *       ({@code GET /__peer/state} with {@code x-peer-control-token}) -- only the
 *       peer launched with this run's harness control token answers it;</li>
 *   <li>the committed Qoder ACP bridge's authenticated session binding
 *       ({@code GET /session} with {@code x-bridge-control-secret}) whose body
 *       must name this run -- exactly the production host backend's gate.</li>
 * </ul>
 */
final class CoreE2eEndpointReadiness {

    /** Header the committed bridge requires on every route but {@code /health}. */
    static final String BRIDGE_CONTROL_SECRET_HEADER = "x-bridge-control-secret";

    /** The mock OpenCode peer's authenticated control header. */
    static final String PEER_CONTROL_TOKEN_HEADER = "x-peer-control-token";

    /** How long a session waits for its runtime to answer the authenticated proof. */
    static final Duration READINESS_WINDOW = Duration.ofSeconds(30);
    private static final Duration READINESS_PAUSE = Duration.ofMillis(100);
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(5);

    private static final ObjectMapper JSON = new ObjectMapper();

    private CoreE2eEndpointReadiness() {
    }

    /** Waits for the launched mock OpenCode peer to answer its control token route. */
    static void awaitOpenCodePeer(URI endpoint, String peerControlToken) {
        awaitOpenCodePeer(endpoint, peerControlToken, READINESS_WINDOW);
    }

    /** Test/override seam: an explicit readiness window. */
    static void awaitOpenCodePeer(URI endpoint, String peerControlToken, Duration window) {
        Objects.requireNonNull(peerControlToken, "peerControlToken");
        waitFor(endpoint, window, "the launched mock OpenCode peer", (client, target) -> {
            HttpRequest request = HttpRequest.newBuilder(target.resolve("__peer/state"))
                    .header(PEER_CONTROL_TOKEN_HEADER, peerControlToken)
                    .timeout(REQUEST_TIMEOUT)
                    .GET()
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new IllegalStateException("the endpoint answered its authenticated control route with status "
                        + response.statusCode() + " (only the peer launched with this run's harness control token"
                        + " answers it)");
            }
            JsonNode state = JSON.readTree(response.body());
            if (state == null || state.path("scenario").asText("").isBlank()) {
                throw new IllegalStateException("the endpoint answered the control route without a scenario state");
            }
        });
    }

    /** Waits for the launched Qoder bridge to answer the run's authenticated session binding. */
    static void awaitQoderBridge(URI endpoint, String controlSecret, UUID runId) {
        awaitQoderBridge(endpoint, controlSecret, runId, READINESS_WINDOW);
    }

    /** Test/override seam: an explicit readiness window. */
    static void awaitQoderBridge(URI endpoint, String controlSecret, UUID runId, Duration window) {
        Objects.requireNonNull(controlSecret, "controlSecret");
        Objects.requireNonNull(runId, "runId");
        waitFor(endpoint, window, "the launched Qoder ACP bridge", (client, target) -> {
            HttpRequest request = HttpRequest.newBuilder(target.resolve("session"))
                    .header(BRIDGE_CONTROL_SECRET_HEADER, controlSecret)
                    .timeout(REQUEST_TIMEOUT)
                    .GET()
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new IllegalStateException("the endpoint refused the run's bridge control secret (status "
                        + response.statusCode() + ")");
            }
            JsonNode binding = JSON.readTree(response.body());
            if (binding == null || !runId.toString().equals(binding.path("runId").asText(""))) {
                throw new IllegalStateException("the endpoint answered with a foreign binding instead of run " + runId);
            }
        });
    }

    /**
     * The bounded poll itself: {@code probe} returns normally only for the ready
     * proof; an {@link IOException} means nothing is bound yet (retried until the
     * window elapses), an {@link IllegalStateException} means a definitive
     * refusal (thrown immediately, never retried).
     */
    private static void waitFor(URI endpoint, Duration window, String runtime, Probe probe) {
        Objects.requireNonNull(endpoint, "endpoint");
        Objects.requireNonNull(window, "window");
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .version(HttpClient.Version.HTTP_1_1)
                .build();
        Instant deadline = Instant.now().plus(window);
        String lastFailure = "no attempt was made";
        while (true) {
            try {
                probe.verify(client, endpoint);
                return;
            } catch (IllegalStateException refusal) {
                throw new IllegalStateException("The harness endpoint " + endpoint + " was reached, but "
                        + runtime + " did not prove it: " + refusal.getMessage()
                        + "; the session is not opened against an unverified endpoint");
            } catch (IOException e) {
                lastFailure = e.getClass().getSimpleName() + ": " + e.getMessage();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while waiting for the harness endpoint " + endpoint
                        + " to become ready", e);
            }
            if (!Instant.now().isBefore(deadline)) {
                throw new IllegalStateException("The harness endpoint " + endpoint + " did not become ready within "
                        + window + ": " + runtime + " never answered its authenticated readiness route"
                        + " (last: " + lastFailure + "); the session is not opened against an unbound endpoint");
            }
            try {
                Thread.sleep(READINESS_PAUSE.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while waiting for the harness endpoint " + endpoint
                        + " to become ready", e);
            }
        }
    }

    /** One readiness attempt; see {@link #waitFor}. */
    @FunctionalInterface
    interface Probe {
        void verify(HttpClient client, URI endpoint) throws IOException, InterruptedException;
    }
}
