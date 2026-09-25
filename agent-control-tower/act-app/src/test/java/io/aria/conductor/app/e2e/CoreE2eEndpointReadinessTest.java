package io.aria.conductor.app.e2e;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Contract of the harness endpoint readiness wait ({@link CoreE2eEndpointReadiness},
 * Task 19 fix round 1): the launch/session race is closed by a bounded,
 * authenticated readiness proof, with a clear refusal on timeout and an
 * immediate refusal for a listener that does not hold the run's proof.
 *
 * <p>Every case drives a real {@code HttpServer} (or a real unbound port) --
 * the readiness seam is exercised against actual sockets, including a
 * slow-binding peer that only starts listening after the wait has begun.
 */
class CoreE2eEndpointReadinessTest {

    private static final String CONTROL_TOKEN = "fixture-control-token-0123456789abcdef";
    private static final Duration GENEROUS_WINDOW = Duration.ofSeconds(30);

    /** A free loopback port that nothing is listening on (the wait's retry case). */
    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket()) {
            socket.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0));
            return socket.getLocalPort();
        }
    }

    /** Starts a one-route HTTP server on an explicit loopback port; the caller stops it. */
    private static HttpServer startServer(int port, String path, Consumer<HttpExchange> handler)
            throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), 0);
        server.createContext(path, exchange -> {
            try {
                handler.accept(exchange);
            } catch (RuntimeException e) {
                respond(exchange, 500, "{}");
            }
        });
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.start();
        return server;
    }

    private static void respond(HttpExchange exchange, int status, String body) {
        try (exchange) {
            byte[] payload = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("content-type", "application/json");
            exchange.sendResponseHeaders(status, payload.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(payload);
            }
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * A peer that only binds after the wait has started is waited for: the peer
     * starts listening 400 ms into a window that begins immediately, so the
     * readiness poll must survive at least one connection-refused attempt.
     *
     * <p>The same case captures the RED state the wait closes: the immediate
     * session open (the first HTTP call, before the peer binds) is exactly the
     * connection refusal asserted first.
     */
    @Test
    void aSlowBindingPeerIsWaitedForUntilItAnswersTheControlRoute() throws Exception {
        int port = freePort();
        URI endpoint = URI.create("http://127.0.0.1:" + port + "/");
        // RED: with no readiness wait, a session opened right after the launch
        // hits exactly this refusal -- the fixture peer has not bound yet.
        assertThatThrownBy(() -> HttpClient.newHttpClient()
                .send(HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(2)).GET().build(),
                        HttpResponse.BodyHandlers.discarding()))
                .isInstanceOf(ConnectException.class)
                .as("nothing is bound yet, which is the race the readiness wait closes");
        AtomicReference<HttpServer> started = new AtomicReference<>();
        Thread lateBinder = new Thread(() -> {
            try {
                Thread.sleep(400);
                started.set(startServer(port, "/__peer/state", exchange -> {
                    if (!CONTROL_TOKEN.equals(exchange.getRequestHeaders().getFirst("x-peer-control-token"))) {
                        respond(exchange, 401, "{\"error\":\"unauthorized\"}");
                        return;
                    }
                    respond(exchange, 200, "{\"scenario\":\"slow-bind\"}");
                }));
            } catch (IOException | InterruptedException e) {
                throw new IllegalStateException("the slow-binding fixture peer failed to start", e);
            }
        });
        lateBinder.start();
        long began = System.nanoTime();
        try {
            CoreE2eEndpointReadiness.awaitOpenCodePeer(endpoint, CONTROL_TOKEN, Duration.ofSeconds(10));
        } finally {
            HttpServer server = started.get();
            if (server != null) {
                server.stop(0);
            }
            lateBinder.join(5_000);
        }
        long elapsedMillis = (System.nanoTime() - began) / 1_000_000;
        assertThat(elapsedMillis)
                .as("the wait must have retried until the peer bound its endpoint, not returned before it")
                .isGreaterThanOrEqualTo(300);
    }

    /**
     * An endpoint that never binds is refused after the window with a clear
     * message naming the endpoint and the runtime that never answered -- never a
     * silent pass and never an unbounded wait.
     */
    @Test
    void anEndpointThatNeverBindsIsRefusedAfterTheWindow() throws Exception {
        URI endpoint = URI.create("http://127.0.0.1:" + freePort() + "/");
        long began = System.nanoTime();
        assertThatThrownBy(() -> CoreE2eEndpointReadiness.awaitOpenCodePeer(endpoint, CONTROL_TOKEN,
                Duration.ofMillis(700)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("did not become ready within")
                .hasMessageContaining(endpoint.toString())
                .hasMessageContaining("mock OpenCode peer")
                .hasMessageContaining("last:");
        long elapsedMillis = (System.nanoTime() - began) / 1_000_000;
        assertThat(elapsedMillis).as("the refusal honours the bounded window").isGreaterThanOrEqualTo(600);
    }

    /**
     * A listener that answers the route without the run's token is a definitive
     * refusal, refused immediately: the window is generous, so waiting for it
     * would prove the refusal was not immediate.
     */
    @Test
    void aControlRouteAnswerWithTheWrongTokenIsRefusedImmediately() throws Exception {
        int port = freePort();
        HttpServer server = startServer(port, "/__peer/state",
                exchange -> respond(exchange, 401, "{\"error\":\"unauthorized\"}"));
        try {
            long began = System.nanoTime();
            assertThatThrownBy(() -> CoreE2eEndpointReadiness.awaitOpenCodePeer(
                    URI.create("http://127.0.0.1:" + port + "/"), CONTROL_TOKEN, GENEROUS_WINDOW))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("did not prove it")
                    .hasMessageContaining("status 401");
            assertThat((System.nanoTime() - began) / 1_000_000)
                    .as("a definitive refusal is never stretched over the window")
                    .isLessThan(5_000);
        } finally {
            server.stop(0);
        }
    }

    /** The bridge path: the authenticated session route must answer with this run's binding. */
    @Test
    void theBridgeBindingMustNameTheRun() throws Exception {
        UUID bindingRun = UUID.randomUUID();
        int port = freePort();
        HttpServer server = startServer(port, "/session", exchange -> {
            if (!"bridge-control-secret-fixture".equals(
                    exchange.getRequestHeaders().getFirst(CoreE2eEndpointReadiness.BRIDGE_CONTROL_SECRET_HEADER))) {
                respond(exchange, 401, "{\"error\":\"unauthorized\"}");
                return;
            }
            respond(exchange, 200, "{\"runId\":\"" + bindingRun + "\",\"state\":\"ready\"}");
        });
        try {
            // The matching binding is ready, so the generous window returns immediately.
            CoreE2eEndpointReadiness.awaitQoderBridge(URI.create("http://127.0.0.1:" + port + "/"),
                    "bridge-control-secret-fixture", bindingRun, GENEROUS_WINDOW);

            // A listener holding the secret but bound to another run is refused as foreign.
            UUID otherRun = UUID.randomUUID();
            assertThatThrownBy(() -> CoreE2eEndpointReadiness.awaitQoderBridge(
                    URI.create("http://127.0.0.1:" + port + "/"), "bridge-control-secret-fixture",
                    otherRun, GENEROUS_WINDOW))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("foreign binding")
                    .hasMessageContaining(otherRun.toString());
        } finally {
            server.stop(0);
        }
    }

    /** A bridge listener answering with a wrong secret is a definitive refusal too. */
    @Test
    void aBridgeAnswerWithoutTheRunSecretIsRefusedImmediately() throws Exception {
        int port = freePort();
        HttpServer server = startServer(port, "/session",
                exchange -> respond(exchange, 401, "{\"error\":\"unauthorized\"}"));
        try {
            assertThatThrownBy(() -> CoreE2eEndpointReadiness.awaitQoderBridge(
                    URI.create("http://127.0.0.1:" + port + "/"), "wrong-secret", UUID.randomUUID(), GENEROUS_WINDOW))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("refused the run's bridge control secret");
        } finally {
            server.stop(0);
        }
    }
}
