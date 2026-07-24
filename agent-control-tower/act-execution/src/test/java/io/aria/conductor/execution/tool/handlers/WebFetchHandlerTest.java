package io.aria.conductor.execution.tool.handlers;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Behaviour tests for {@link WebFetchHandler} against a dynamic-port in-process
 * {@link HttpServer}. Verifies the "HTTP status\n\nbody" envelope, oversized-body
 * truncation, missing-url rejection, and the fetch-error path.
 */
class WebFetchHandlerTest {

    private HttpServer server;
    private int port;
    private final WebFetchHandler handler = new WebFetchHandler();

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

    private void respond(String path, int status, String body) {
        server.createContext(path, exchange -> {
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
        assertThat(handler.execute(Map.of())).isEqualTo("Error: Missing required parameter: url");
    }

    @Test
    void execute_returnsHttpStatusAndBody() {
        respond("/page", 200, "<html>ok</html>");

        assertThat(handler.execute(Map.of("url", url("/page"))))
                .isEqualTo("HTTP 200\n\n<html>ok</html>");
    }

    @Test
    void execute_truncatesLongBody() {
        respond("/long", 200, "q".repeat(6000));

        String result = handler.execute(Map.of("url", url("/long")));

        assertThat(result).startsWith("HTTP 200\n\n");
        assertThat(result).contains("... [truncated at 5000 characters]");
        assertThat(result).doesNotContain("q".repeat(5001));
    }

    @Test
    void execute_returnsErrorOnFetchFailure() {
        String dead = "http://127.0.0.1:1/x";
        assertThat(handler.execute(Map.of("url", dead)))
                .startsWith("Error fetching URL '" + dead + "'");
    }
}
