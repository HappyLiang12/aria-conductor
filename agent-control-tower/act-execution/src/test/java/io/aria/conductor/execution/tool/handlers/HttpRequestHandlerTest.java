package io.aria.conductor.execution.tool.handlers;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Behaviour tests for {@link HttpRequestHandler} exercised against a real in-process
 * {@link HttpServer} bound to a dynamic port (0). Verifies method dispatch, the
 * "METHOD url -> HTTP status" response envelope, body forwarding for POST, oversized-body
 * truncation, and the missing-url / connection-error paths.
 */
class HttpRequestHandlerTest {

    private HttpServer server;
    private int port;
    private final HttpRequestHandler handler = new HttpRequestHandler();

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        port = server.getAddress().getPort();
        server.start();
    }

    @AfterEach
    void stopServer() {
        if (server != null) server.stop(0);
    }

    private void respond(String path, int status, String body, AtomicReference<String> capturedMethod,
                         AtomicReference<String> capturedBody) {
        server.createContext(path, exchange -> {
            if (capturedMethod != null) capturedMethod.set(exchange.getRequestMethod());
            byte[] reqBytes = exchange.getRequestBody().readAllBytes();
            if (capturedBody != null) capturedBody.set(new String(reqBytes, StandardCharsets.UTF_8));
            byte[] out = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, out.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(out);
            }
        });
    }

    private String url(String path) {
        return "http://127.0.0.1:" + port + path;
    }

    @Test
    void execute_rejectsMissingUrl() {
        assertThat(handler.execute(new HashMap<>())).isEqualTo("Error: Missing required parameter: url");
        assertThat(handler.execute(Map.of("url", ""))).isEqualTo("Error: Missing required parameter: url");
    }

    @Test
    void execute_getReturnsStatusAndBodyEnvelope() {
        respond("/hello", 200, "world-body", null, null);

        String result = handler.execute(Map.of("url", url("/hello")));

        assertThat(result)
                .startsWith("GET " + url("/hello") + " -> HTTP 200")
                .endsWith("world-body");
    }

    @Test
    void execute_postForwardsBody_andUppercasesMethod() {
        AtomicReference<String> method = new AtomicReference<>();
        AtomicReference<String> received = new AtomicReference<>();
        respond("/submit", 201, "created", method, received);

        Map<String, Object> args = new HashMap<>();
        args.put("url", url("/submit"));
        args.put("method", "post"); // lower-case should be upper-cased
        args.put("body", "payload-123");
        String result = handler.execute(args);

        assertThat(method.get()).isEqualTo("POST");
        assertThat(received.get()).isEqualTo("payload-123");
        assertThat(result).startsWith("POST " + url("/submit") + " -> HTTP 201");
    }

    @Test
    void execute_surfacesNon2xxStatusVerbatim() {
        respond("/boom", 500, "kaboom", null, null);

        assertThat(handler.execute(Map.of("url", url("/boom"))))
                .contains("-> HTTP 500")
                .contains("kaboom");
    }

    @Test
    void execute_truncatesBodyOver5000Chars() {
        respond("/big", 200, "z".repeat(6000), null, null);

        String result = handler.execute(Map.of("url", url("/big")));

        assertThat(result).contains("... [truncated at 5000 characters]");
        assertThat(result).doesNotContain("z".repeat(5001));
    }

    @Test
    void execute_returnsErrorOnConnectionFailure() {
        // Nothing is listening on this path host:port after stop — use an unroutable port.
        String dead = "http://127.0.0.1:1/none";
        String result = handler.execute(Map.of("url", dead));

        assertThat(result).startsWith("Error executing HTTP GET request to '" + dead + "'");
    }

    /**
     * #65 Option C: without custom headers an agent can neither authenticate to a REST API
     * (no {@code Authorization}) nor declare a JSON payload (no {@code Content-Type}), so
     * creating a PR via the GitHub API is impossible even though a body is already supported.
     */
    @Test
    void execute_sendsCustomHeaders_soAgentsCanAuthenticateRestApis() {
        AtomicReference<String> auth = new AtomicReference<>();
        AtomicReference<String> contentType = new AtomicReference<>();
        server.createContext("/pulls", exchange -> {
            auth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            contentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
            exchange.getRequestBody().readAllBytes();
            byte[] out = "{\"number\":1}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(201, out.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(out);
            }
        });

        Map<String, Object> args = new HashMap<>();
        args.put("url", url("/pulls"));
        args.put("method", "POST");
        args.put("body", "{\"title\":\"my pr\"}");
        args.put("headers", Map.of(
                "Authorization", "token gh_test_token",
                "Content-Type", "application/json"));

        String result = handler.execute(args);

        assertThat(result).contains("-> HTTP 201");
        assertThat(auth.get()).isEqualTo("token gh_test_token");
        assertThat(contentType.get()).isEqualTo("application/json");
    }
}
