package io.aria.conductor.execution.adk;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Black-box tests for {@link AdkHttpClient} backed by the JDK built-in
 * {@link com.sun.net.httpserver.HttpServer} so we don't pull in WireMock.
 */
class AdkHttpClientTest {

    private HttpServer server;
    private int port;
    private final Map<String, HttpHandler> handlers = new ConcurrentHashMap<>();
    private AdkHttpClient client;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        port = server.getAddress().getPort();
        server.createContext("/", exchange -> {
            HttpHandler h = handlers.get(exchange.getRequestURI().getPath());
            if (h == null) {
                exchange.sendResponseHeaders(404, -1);
                exchange.close();
                return;
            }
            h.handle(exchange);
        });
        server.start();
        client = new AdkHttpClient();
    }

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    private void on(String path, HttpHandler handler) {
        handlers.put(path, handler);
    }

    @Test
    void checkHealth_returnsHealthy_on200() {
        on("/health", exchange -> {
            byte[] ok = "ok".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, ok.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(ok);
            }
        });

        AdkHealthStatus status = client.checkHealth("127.0.0.1", port);

        assertThat(status).isEqualTo(AdkHealthStatus.HEALTHY);
    }

    @Test
    void checkHealth_returnsUnhealthy_on500() {
        on("/health", exchange -> {
            exchange.sendResponseHeaders(500, -1);
            exchange.close();
        });

        AdkHealthStatus status = client.checkHealth("127.0.0.1", port);

        assertThat(status).isEqualTo(AdkHealthStatus.UNHEALTHY);
    }

    @Test
    void checkHealth_returnsUnreachable_whenServerDown() {
        // Pick a port we never bound to.
        AdkHealthStatus status = client.checkHealth("127.0.0.1", 1);

        assertThat(status).isEqualTo(AdkHealthStatus.UNREACHABLE);
    }

    @Test
    void shutdown_returnsTrue_on2xx() {
        AtomicInteger calls = new AtomicInteger();
        on("/shutdown", exchange -> {
            calls.incrementAndGet();
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });

        boolean ok = client.shutdown("127.0.0.1", port);

        assertThat(ok).isTrue();
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    void shutdown_returnsFalse_onError() {
        on("/shutdown", exchange -> {
            exchange.sendResponseHeaders(500, -1);
            exchange.close();
        });

        boolean ok = client.shutdown("127.0.0.1", port);

        assertThat(ok).isFalse();
    }

    @Test
    void submitRun_parsesSseDataLines_intoEventsAndOutput() throws Exception {
        // Stream three SSE events, one of which carries a session_id.
        String body = """
                data: {"session_id":"sess-1","output":"hello "}

                data: {"output":"world"}

                data: [DONE]

                """;
        on("/run", exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });

        AdkRunResponse resp = client.submitRun("127.0.0.1", port,
                AdkRunRequest.of("agent-1", "ping")).get();

        assertThat(resp.success()).isTrue();
        assertThat(resp.sessionId()).isEqualTo("sess-1");
        assertThat(resp.output()).isEqualTo("hello world");
        assertThat(resp.events()).hasSize(2);
    }

    @Test
    void submitRun_returnsFailure_whenServerReturns500() throws Exception {
        on("/run", exchange -> {
            exchange.sendResponseHeaders(500, -1);
            exchange.close();
        });

        AdkRunResponse resp = client.submitRun("127.0.0.1", port,
                AdkRunRequest.of("agent-1", "ping")).get();

        assertThat(resp.success()).isFalse();
        assertThat(resp.errorMessage()).contains("500");
    }

    @Test
    void submitRun_capturesError_fromSseEvent() throws Exception {
        String body = "data: {\"error\":\"boom\"}\n\n";
        on("/run", exchange -> {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });

        AdkRunResponse resp = client.submitRun("127.0.0.1", port,
                AdkRunRequest.of("agent-1", "ping")).get();

        assertThat(resp.success()).isFalse();
        assertThat(resp.errorMessage()).isEqualTo("boom");
        assertThat(resp.events()).hasSize(1);
    }

    @SuppressWarnings("unused")
    private static List<String> noWarnings() { return List.of(); }
}
