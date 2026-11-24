package io.aria.conductor.aria.live;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The direct DeepSeek locator (spec D6) against a local {@link HttpServer}: the request it
 * sends, the box it translates, the 1.5 s whole-call budget, every way an answer is refused,
 * and the two log rules — the key and the bodies never appear.
 */
class DeepSeekLocatorTest {

    private static final String TRANSCRIPT = "邊度係個 5mm 六角匙？";
    /** Not a real JPEG: the locator forwards the turn's bytes verbatim. */
    private static final byte[] JPEG = "jpeg-bytes".getBytes(StandardCharsets.UTF_8);
    private static final String BASE64 = Base64.getEncoder().encodeToString(JPEG);
    private static final String KEY = "sk-test-locator-123";
    private static final String VALID_ANSWER = "{\"box_2d\":[100,200,300,400],\"confidence\":0.9}";

    private final ObjectMapper json = new ObjectMapper();
    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
    }

    @Test
    void aValidAnswerBecomesTheTranslatedRender() throws Exception {
        AtomicReference<String> authorization = new AtomicReference<>();
        AtomicReference<String> path = new AtomicReference<>();
        AtomicReference<JsonNode> request = new AtomicReference<>();
        respond(exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            path.set(exchange.getRequestURI().getPath());
            request.set(json.readTree(exchange.getRequestBody().readAllBytes()));
            write(exchange, completion(VALID_ANSWER));
        });

        Optional<LiveLocator.Render> found = locator(true, KEY, () -> null).locate(TRANSCRIPT, JPEG);

        assertThat(found).isPresent();
        // box_2d is [y0,x0,y1,x1] on a 0-1000 grid; the phone wants [x,y,w,h] in [0,1].
        assertThat(found.get().bbox()).containsExactly(0.2, 0.1, 0.2, 0.2);
        assertThat(found.get().label()).isEqualTo(TRANSCRIPT);
        assertThat(found.get().text()).isEqualTo(TRANSCRIPT);

        assertThat(path.get()).isEqualTo("/chat/completions");
        assertThat(authorization.get()).isEqualTo("Bearer " + KEY);
        JsonNode body = request.get();
        assertThat(body.path("model").asText()).isEqualTo("deepseek-flash");
        JsonNode messages = body.path("messages");
        assertThat(messages.get(0).path("role").asText()).isEqualTo("system");
        assertThat(messages.get(0).path("content").asText())
                .contains("strict JSON")
                .contains("box_2d");
        JsonNode user = messages.get(1).path("content");
        assertThat(user.isArray()).isTrue();
        assertThat(user.get(0).path("type").asText()).isEqualTo("text");
        assertThat(user.get(0).path("text").asText()).isEqualTo(TRANSCRIPT);
        assertThat(user.get(1).path("type").asText()).isEqualTo("image_url");
        assertThat(user.get(1).path("image_url").path("url").asText())
                .isEqualTo("data:image/jpeg;base64," + BASE64);
    }

    @Test
    void aBoxOutsideTheGridIsClampedToTheFrame() throws Exception {
        respond(exchange -> write(exchange,
                completion("{\"box_2d\":[-40,-20,5000,4000],\"confidence\":0.6}")));

        Optional<LiveLocator.Render> found = locator(true, KEY, () -> null).locate(TRANSCRIPT, JPEG);

        assertThat(found).isPresent();
        assertThat(found.get().bbox()).containsExactly(0.0, 0.0, 1.0, 1.0);
    }

    /** The plan says to ignore low confidence: a box the model is unsure of is no answer. */
    @Test
    void anAnswerBelowTheConfidenceFloorIsRefused() throws Exception {
        AtomicReference<String> content = new AtomicReference<>();
        respond(exchange -> write(exchange, completion(content.get())));
        LiveLocator locator = locator(true, KEY, () -> null);

        content.set("{\"box_2d\":[100,200,300,400],\"confidence\":0.4}");
        assertThat(locator.locate(TRANSCRIPT, JPEG)).as("0.4 is below the 0.5 floor").isEmpty();
        content.set("{\"box_2d\":[100,200,300,400],\"confidence\":0.0}");
        assertThat(locator.locate(TRANSCRIPT, JPEG)).as("a zero-confidence box is no answer").isEmpty();
        content.set("{\"box_2d\":[100,200,300,400],\"confidence\":0.5}");
        assertThat(locator.locate(TRANSCRIPT, JPEG)).as("the floor itself still answers").isPresent();
    }

    @Test
    void aBodyThatDelaysPastTheBudgetIsAnEmptyAnswer() throws Exception {
        AtomicInteger bodies = new AtomicInteger();
        respond(exchange -> {
            // Headers first, body 2 s later: a request-level timeout on the headers alone
            // would not cover this, so the whole-call budget is what is being measured.
            byte[] response = completion(VALID_ANSWER).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            sleep(2000);
            bodies.incrementAndGet();
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(response);
            } catch (IOException ignored) {
                // The client already gave up: the stub keeps no state worth failing over.
            }
        });

        long started = System.nanoTime();
        Optional<LiveLocator.Render> found = locator(true, KEY, () -> null).locate(TRANSCRIPT, JPEG);
        long tookMs = (System.nanoTime() - started) / 1_000_000;

        assertThat(found).isEmpty();
        assertThat(tookMs).as("the 1.5 s budget is the whole call").isLessThan(1800);
    }

    @Test
    void everyUnplaceableAnswerIsEmpty() throws Exception {
        AtomicReference<String> content = new AtomicReference<>();
        AtomicInteger requests = new AtomicInteger();
        respond(exchange -> {
            requests.incrementAndGet();
            write(exchange, completion(content.get()));
        });
        LiveLocator locator = locator(true, KEY, () -> null);

        List<String> refused = List.of(
                "I cannot see anything",
                "",
                "{\"box_2d\":[300,200,100,400],\"confidence\":0.9}",   // swapped corners
                "{\"box_2d\":[100,200,100,400],\"confidence\":0.9}",   // zero height
                "{\"box_2d\":[1,2,3],\"confidence\":0.9}",             // short box
                "{\"confidence\":0.9}",                               // no box at all
                "{\"box_2d\":[\"a\",200,300,400],\"confidence\":0.9}", // not a number
                "{\"box_2d\":[100,200,300,400],\"confidence\":2}",     // confidence out of range
                "```json\n{\"box_2d\":[100,200,300,400],\"confidence\":0.9}\n```");

        for (String answer : refused) {
            content.set(answer);
            assertThat(locator.locate(TRANSCRIPT, JPEG)).as(answer).isEmpty();
        }
        // Each attempt is one call, never a retry against the provider.
        assertThat(requests.get()).isEqualTo(refused.size());
    }

    @Test
    void aMissingKeyMakesNoCall() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        respond(exchange -> {
            requests.incrementAndGet();
            write(exchange, completion(VALID_ANSWER));
        });

        assertThat(locator(true, "", () -> null).locate(TRANSCRIPT, JPEG)).isEmpty();
        assertThat(locator(true, "   ", () -> " ").locate(TRANSCRIPT, JPEG)).isEmpty();
        assertThat(requests.get()).isZero();
    }

    /**
     * The locator's only environment source is {@code DEEPSEEK_API_KEY}: the generic
     * {@code LLM_API_KEY} is not a fallback, so a deployment that never set the DeepSeek
     * key leaves the backup disabled (and says so once, as metadata) instead of charging
     * the platform's agent key.
     */
    @Test
    void onlyTheDeepseekKeyIsRead() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        respond(exchange -> {
            requests.incrementAndGet();
            write(exchange, completion(VALID_ANSWER));
        });
        assertThat(DeepSeekLocator.ENV_KEY).isEqualTo("DEEPSEEK_API_KEY");

        Logger logger = (Logger) LoggerFactory.getLogger(DeepSeekLocator.class);
        Level previous = logger.getLevel();
        ListAppender<ILoggingEvent> lines = new ListAppender<>();
        lines.start();
        logger.setLevel(Level.DEBUG);
        logger.addAppender(lines);
        try {
            // The supplier is the whole environment the locator sees, and it holds no
            // DEEPSEEK_API_KEY: the only key a deployment with LLM_API_KEY alone has.
            assertThat(locator(true, "", () -> null).locate(TRANSCRIPT, JPEG)).isEmpty();
        } finally {
            logger.detachAppender(lines);
            logger.setLevel(previous);
        }

        assertThat(requests.get()).as("no key, no call").isZero();
        List<String> logged = lines.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
        assertThat(logged).anyMatch(message -> message.contains("DEEPSEEK_API_KEY is not set"));
    }

    @Test
    void aRejectedKeySuspendsTheBackupLoudlyAndStopsCalling() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        respond(exchange -> {
            requests.incrementAndGet();
            exchange.sendResponseHeaders(401, -1);
            exchange.close();
        });

        Logger logger = (Logger) LoggerFactory.getLogger(DeepSeekLocator.class);
        Level previous = logger.getLevel();
        ListAppender<ILoggingEvent> lines = new ListAppender<>();
        lines.start();
        logger.setLevel(Level.DEBUG);
        logger.addAppender(lines);
        LiveLocator locator = locator(true, KEY, () -> null);
        try {
            // A key the platform rejects cannot start working mid-process: the first call
            // warns, suspends, and later turns cost neither a request nor the latency.
            assertThat(locator.locate(TRANSCRIPT, JPEG)).isEmpty();
            assertThat(locator.locate(TRANSCRIPT, JPEG)).isEmpty();
        } finally {
            logger.detachAppender(lines);
            logger.setLevel(previous);
        }

        assertThat(requests.get()).as("one request, then suspended").isEqualTo(1);
        List<String> logged = lines.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
        assertThat(logged).anyMatch(message -> message.contains("suspended") && message.contains("401"));
        // The key and the request body never reach a log line.
        assertThat(logged).noneMatch(message -> message.contains(KEY) || message.contains(BASE64));
    }

    @Test
    void theEnvironmentKeyIsUsedAndThePropertyWins() throws Exception {
        AtomicReference<String> authorization = new AtomicReference<>();
        respond(exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            write(exchange, completion(VALID_ANSWER));
        });

        assertThat(locator(true, "", () -> "env-key").locate(TRANSCRIPT, JPEG)).isPresent();
        assertThat(authorization.get()).isEqualTo("Bearer env-key");

        assertThat(locator(true, KEY, () -> "env-key").locate(TRANSCRIPT, JPEG)).isPresent();
        assertThat(authorization.get()).isEqualTo("Bearer " + KEY);
    }

    @Test
    void aDisabledLocatorMakesNoCall() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        respond(exchange -> {
            requests.incrementAndGet();
            write(exchange, completion(VALID_ANSWER));
        });

        assertThat(locator(false, KEY, () -> "env-key").locate(TRANSCRIPT, JPEG)).isEmpty();
        assertThat(requests.get()).isZero();
    }

    @Test
    void theKeyAndTheBodiesAreNeverLogged() throws Exception {
        respond(exchange -> {
            exchange.getRequestBody().readAllBytes();
            write(exchange, completion(VALID_ANSWER));
        });
        Logger logger = (Logger) LoggerFactory.getLogger(DeepSeekLocator.class);
        Level previous = logger.getLevel();
        ListAppender<ILoggingEvent> lines = new ListAppender<>();
        lines.start();
        logger.setLevel(Level.DEBUG);
        logger.addAppender(lines);
        try {
            assertThat(locator(true, KEY, () -> null).locate(TRANSCRIPT, JPEG)).isPresent();
        } finally {
            logger.detachAppender(lines);
            logger.setLevel(previous);
        }

        List<String> logged = lines.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
        // Metadata only: the outcome and its cost, so a silent deployment is diagnosable.
        assertThat(logged).anyMatch(message -> message.contains("outcome="));
        assertThat(logged).allSatisfy(message -> assertThat(message)
                .doesNotContain(KEY)
                .doesNotContain(TRANSCRIPT)
                .doesNotContain(BASE64)
                .doesNotContain("box_2d")
                .doesNotContain("sk-test"));
    }

    // --- harness -------------------------------------------------------------------------

    private LiveLocator locator(boolean enabled, String apiKey, java.util.function.Supplier<String> envKey) {
        LiveProperties properties = new LiveProperties(false, null, null, "", 5000, 7200,
                enabled, "http://127.0.0.1:" + server.getAddress().getPort(), null, 1500);
        return new DeepSeekLocator(properties, json, apiKey, envKey);
    }

    private void respond(com.sun.net.httpserver.HttpHandler handler) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", handler);
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
    }

    /** One OpenAI-shaped completion whose message content is the model's answer. */
    private String completion(String modelAnswer) throws IOException {
        return "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":"
                + json.writeValueAsString(modelAnswer) + "}}]}";
    }

    private static void write(HttpExchange exchange, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
