package io.aria.conductor.app;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.aria.conductor.execution.security.ActorTokenService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.net.http.WebSocketHandshakeException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The aria live endpoint contract at the transport boundary (spec §"Endpoint and
 * handshake"): a non-upgrade GET answers 426, the upgrade is operator-only
 * (401 without a credential, 403 for a worker credential), a valid {@code hello}
 * is answered with {@code ready} immediately, the offered subprotocol is echoed
 * and only an offer the server could not honour closes 4002 after {@code ready},
 * and a socket that never says hello says why and closes 4002 at the deadline.
 * <p>
 * The harness connects over loopback, which is the local operator by design, so
 * the 401 case routes its client address through the trusted-proxy rule
 * ({@code X-Forwarded-For}, the same seam ApprovalFlowIntegrationTest uses).
 * This context leaves the live path disabled (no credentials file, no bridge
 * script), so the child is never spawned: {@code ready} is sent before the
 * factory is asked, which is exactly what the handshake must not wait for.
 */
@Import(NoopLlmTestConfig.class)
@TestPropertySource(properties = {
        "aria.operator.bearer-token=" + LiveEndpointIntegrationTest.OPERATOR_CREDENTIAL,
        "aria.operator.trusted-proxies=127.0.0.1",
        "aria.live.ready-deadline-ms=200"
})
class LiveEndpointIntegrationTest extends BaseH2IntegrationTest {

    static final String OPERATOR_CREDENTIAL = "live-endpoint-operator-credential";

    private static final String PATH = "/api/v1/aria/live";
    private static final String PROTOCOL = "aria-live.v1";
    private static final String HELLO = "{\"type\":\"hello\",\"v\":1,\"conversationId\":\"conv-live\","
            + "\"audio\":{\"inRate\":16000,\"outRate\":24000,\"format\":\"pcm16le\",\"channels\":1},"
            + "\"mic\":true,\"ar\":false}";

    @LocalServerPort
    int port;

    @Autowired
    TestRestTemplate restTemplate;

    @Autowired
    ActorTokenService actorTokens;

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void plainGetIsUpgradeRequired() {
        ResponseEntity<String> response = restTemplate.getForEntity(PATH, String.class);

        assertThat(response.getStatusCode().value()).isEqualTo(426);
    }

    @Test
    void aPostToTheLivePathIsNotAnUpgradeError() {
        // The 426 contract is for a plain GET; other methods belong to the app.
        ResponseEntity<String> response = restTemplate.postForEntity(PATH, null, String.class);

        assertThat(response.getStatusCode().value()).isNotEqualTo(426);
    }

    @Test
    void upgradeWithoutOperatorBearerIsUnauthorized() {
        Throwable failure = handshakeFailure(builder -> builder.header("X-Forwarded-For", "203.0.113.7"));

        assertThat(statusOf(failure)).isEqualTo(401);
    }

    @Test
    void upgradeWithWorkerBearerIsForbidden() {
        String workerToken = actorTokens.issueWorker(UUID.randomUUID(), Instant.now().plus(Duration.ofMinutes(5)));

        Throwable failure = handshakeFailure(builder -> builder.header("Authorization", "Bearer " + workerToken));

        assertThat(statusOf(failure)).isEqualTo(403);
    }

    @Test
    void helloIsAnsweredWithReadyWithinOneSecond() throws Exception {
        Recorder recorder = new Recorder();
        WebSocket socket = operatorSocket(recorder, PROTOCOL).get(5, TimeUnit.SECONDS);

        socket.sendText(HELLO, true).get(5, TimeUnit.SECONDS);

        JsonNode ready = mapper.readTree(recorder.texts.poll(1, TimeUnit.SECONDS));
        assertThat(ready.path("type").asText()).isEqualTo("ready");
        assertThat(ready.path("v").asInt()).isEqualTo(1);
        assertThat(ready.path("sessionId").asText()).isNotBlank();
        assertThat(ready.path("model").asText()).isEqualTo("qwen3.8-omni-flash-realtime");
        assertThat(ready.path("maxSessionSec").asInt()).isEqualTo(7200);
        assertThat(ready.path("modalities").size()).isEqualTo(2);
        socket.abort();
    }

    @Test
    void offeredSubprotocolIsEchoedInTheHandshakeResponse() throws Exception {
        List<String> responseHead = rawHandshake(PROTOCOL, null);

        assertThat(responseHead.get(0)).contains("101");
        assertThat(responseHead).anyMatch(line -> line.equalsIgnoreCase("Sec-WebSocket-Protocol: " + PROTOCOL));
    }

    @Test
    void aCrossSiteBrowserOriginIsRefusedAtTheHandshake() throws Exception {
        List<String> responseHead = rawHandshake(PROTOCOL, "http://evil.example");

        assertThat(responseHead.get(0)).contains("403");
    }

    @Test
    void aLocalDashboardOriginIsAdmitted() throws Exception {
        List<String> responseHead = rawHandshake(PROTOCOL, "http://localhost:5173");

        assertThat(responseHead.get(0)).contains("101");
    }

    @Test
    void wrongOfferedSubprotocolGetsReadyThenClose4002() throws Exception {
        Recorder recorder = new Recorder();
        WebSocket socket = operatorSocket(recorder, "aria-live.v2").get(5, TimeUnit.SECONDS);

        socket.sendText(HELLO, true).get(5, TimeUnit.SECONDS);

        JsonNode ready = mapper.readTree(recorder.texts.poll(1, TimeUnit.SECONDS));
        assertThat(ready.path("type").asText()).isEqualTo("ready");
        assertThat(recorder.closes.poll(5, TimeUnit.SECONDS)).isEqualTo(4002);
    }

    @Test
    void anOfferTheServerCanHonourIsNotAProtocolRefusal() throws Exception {
        // "aria-live.v1, x" is one offer the server accepts on aria-live.v1; only
        // the accepted protocol decides the 4002, never the raw header. This context
        // leaves the live path disabled, so the bridge closes 1011 after ready — the
        // point here is that the close is not the 4002 the protocol rule would send.
        Recorder recorder = new Recorder();
        WebSocket socket = operatorSocket(recorder, "aria-live.v1", "x").get(5, TimeUnit.SECONDS);

        socket.sendText(HELLO, true).get(5, TimeUnit.SECONDS);

        JsonNode ready = mapper.readTree(recorder.texts.poll(1, TimeUnit.SECONDS));
        assertThat(ready.path("type").asText()).isEqualTo("ready");
        assertThat(recorder.closes.poll(5, TimeUnit.SECONDS)).isEqualTo(1011);
    }

    @Test
    void aDisabledBridgeSaysWhyThenCloses1011() throws Exception {
        // Carry from the Task 1 review: when aria.live.enabled is false the socket is
        // still told ready (the handshake never waits for a provider), then the missing
        // bridge is named and close 1011 follows.
        Recorder recorder = new Recorder();
        WebSocket socket = operatorSocket(recorder, PROTOCOL).get(5, TimeUnit.SECONDS);

        socket.sendText(HELLO, true).get(5, TimeUnit.SECONDS);

        assertThat(mapper.readTree(recorder.texts.poll(1, TimeUnit.SECONDS)).path("type").asText())
                .isEqualTo("ready");
        JsonNode error = mapper.readTree(recorder.texts.poll(2, TimeUnit.SECONDS));
        assertThat(error.path("type").asText()).isEqualTo("error");
        assertThat(error.path("v").asInt()).isEqualTo(1);
        assertThat(error.path("code").asText()).isEqualTo("bridge_internal");
        assertThat(error.path("retryable").asBoolean()).isTrue();
        assertThat(error.path("message").asText()).isEqualTo("live bridge unavailable");
        assertThat(recorder.closes.poll(5, TimeUnit.SECONDS)).isEqualTo(1011);
    }

    @Test
    void socketWithoutHelloSaysWhyThenClosesAtTheDeadline() throws Exception {
        Recorder recorder = new Recorder();
        operatorSocket(recorder, PROTOCOL).get(5, TimeUnit.SECONDS);

        assertThat(recorder.closes.poll(5, TimeUnit.SECONDS)).isEqualTo(4002);
        JsonNode error = mapper.readTree(recorder.texts.poll(1, TimeUnit.SECONDS));
        assertThat(error.path("type").asText()).isEqualTo("error");
        assertThat(error.path("v").asInt()).isEqualTo(1);
        assertThat(error.path("code").asText()).isEqualTo("capability_mismatch");
        assertThat(error.path("retryable").asBoolean(true)).isFalse();
        assertThat(error.path("message").asText()).isEqualTo("hello deadline exceeded");
    }

    // --- harness ---------------------------------------------------------------------------

    private CompletableFuture<WebSocket> operatorSocket(Recorder recorder, String... protocols) {
        return client().newWebSocketBuilder()
                .header("Authorization", "Bearer " + OPERATOR_CREDENTIAL)
                .subprotocols(protocols[0], Arrays.copyOfRange(protocols, 1, protocols.length))
                .buildAsync(uri(), recorder);
    }

    private Throwable handshakeFailure(Consumer<WebSocket.Builder> customize) {
        WebSocket.Builder builder = client().newWebSocketBuilder();
        customize.accept(builder);
        try {
            builder.buildAsync(uri(), new Recorder()).get(5, TimeUnit.SECONDS);
            return null;
        } catch (Exception e) {
            return e;
        }
    }

    private static HttpClient client() {
        return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }

    private URI uri() {
        return URI.create("ws://127.0.0.1:" + port + PATH);
    }

    private static int statusOf(Throwable failure) {
        Throwable cause = failure;
        while (cause != null && !(cause instanceof WebSocketHandshakeException)) {
            cause = cause.getCause();
        }
        assertThat(cause).as("expected a WebSocket handshake failure, got %s", failure).isNotNull();
        return ((WebSocketHandshakeException) cause).getResponse().statusCode();
    }

    /**
     * A raw HTTP/1.1 upgrade so the response head can be read: {@code java.net.http.WebSocket}
     * does not expose the negotiated subprotocol header.
     */
    private List<String> rawHandshake(String offeredProtocol, String origin) throws Exception {
        try (Socket socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(5000);
            String request = "GET " + PATH + " HTTP/1.1\r\n"
                    + "Host: 127.0.0.1:" + port + "\r\n"
                    + "Upgrade: websocket\r\n"
                    + "Connection: Upgrade\r\n"
                    + "Sec-WebSocket-Key: " + Base64.getEncoder().encodeToString(new byte[16]) + "\r\n"
                    + "Sec-WebSocket-Version: 13\r\n"
                    + "Sec-WebSocket-Protocol: " + offeredProtocol + "\r\n"
                    + "Authorization: Bearer " + OPERATOR_CREDENTIAL + "\r\n"
                    + (origin == null ? "" : "Origin: " + origin + "\r\n")
                    + "\r\n";
            OutputStream out = socket.getOutputStream();
            out.write(request.getBytes(StandardCharsets.US_ASCII));
            out.flush();
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
            List<String> head = new ArrayList<>();
            String line;
            while ((line = reader.readLine()) != null && !line.isEmpty()) {
                head.add(line);
            }
            return head;
        }
    }

    /** Collects server text frames, close codes and handshake open. */
    private static final class Recorder implements WebSocket.Listener {

        final BlockingQueue<String> texts = new LinkedBlockingQueue<>();
        final BlockingQueue<Integer> closes = new LinkedBlockingQueue<>();
        final AtomicReference<Throwable> errors = new AtomicReference<>();

        @Override
        public void onOpen(WebSocket webSocket) {
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            texts.add(data.toString());
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            closes.add(statusCode);
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            errors.set(error);
        }
    }
}
