package io.aria.conductor.aria.live;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * The direct DeepSeek locator (spec D6): one stateless question about one frame, asked only
 * as the backup for a missed {@code highlight_target} render.
 *
 * <p>It posts the turn's JPEG as a data URL with the wearer's question and asks for a
 * strict-JSON {@code {"box_2d":[y0,x0,y1,x1],"confidence":0..1}} on a 0–1000 grid, then
 * translates the box the way the bridge does (spec D3): both corners clamped to the grid,
 * normalized {@code [x,y,w,h]}, and any box with no positive area refused. The whole call —
 * connect, request, body — is bounded by {@code aria.live.direct-timeout-ms} (1.5 s by
 * default) and is never retried: the backup exists to be quick or to be absent.
 *
 * <p>The key is the Aria environment's {@code DEEPSEEK_API_KEY} (with
 * {@code aria.live.direct.api-key} as an optional override) and it never leaves this class:
 * no log line carries the key, the question, the image or the provider's answer, and the
 * only things written down are the outcome and its cost. The generic {@code LLM_API_KEY} is
 * deliberately not read: the backup must not spend the platform's agent key, and a
 * deployment that sets only that key leaves the locator disabled.
 */
@Component
public class DeepSeekLocator implements LiveLocator {

    /** The Aria environment variable holding the DeepSeek key; never logged. */
    static final String ENV_KEY = "DEEPSEEK_API_KEY";
    static final String PATH = "/chat/completions";

    /** The plan's floor: a box the model is not half sure of is no answer at all. */
    static final double MIN_CONFIDENCE = 0.5;

    /** The one answer shape the prompt allows: JSON, no prose, no markdown. */
    static final String SYSTEM_PROMPT = """
            You are the AR locator of a smart-glasses assistant. The user asks about one \
            object in the photo.
            Answer with strict JSON and nothing else: {"box_2d":[y0,x0,y1,x1],"confidence":0.0}
            The box surrounds that object on a 0-1000 grid over the image: y0/y1 are the top \
            and bottom edges, x0/x1 the left and right edges, y0 < y1 and x0 < x1.
            confidence is 0..1. If the object is not visible, answer {"box_2d":[],"confidence":0}.
            No prose, no explanation, no markdown, no code fence.""";

    private static final int GRID = 1000;
    private static final Logger log = LoggerFactory.getLogger(DeepSeekLocator.class);

    private final String baseUrl;
    private final String model;
    private final long timeoutMs;
    private final boolean enabled;
    private final String apiKey;
    private final Supplier<String> envKey;
    private final ObjectMapper json;
    private final HttpClient client;
    private final URI endpoint;
    /** One metadata line for a missing key, however many turns ask. */
    private final AtomicBoolean missingKeyLogged = new AtomicBoolean();

    @Autowired
    public DeepSeekLocator(LiveProperties properties, ObjectMapper json,
            @Value("${aria.live.direct.api-key:}") String apiKey) {
        this(properties, json, apiKey, () -> System.getenv(ENV_KEY), HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(properties.directTimeoutMs()))
                .build());
    }

    DeepSeekLocator(LiveProperties properties, ObjectMapper json, String apiKey,
            Supplier<String> envKey) {
        this(properties, json, apiKey, envKey, HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(properties.directTimeoutMs()))
                .build());
    }

    DeepSeekLocator(LiveProperties properties, ObjectMapper json, String apiKey,
            Supplier<String> envKey, HttpClient client) {
        this.enabled = properties.directLocatorEnabled();
        this.baseUrl = properties.directBaseUrl();
        this.model = properties.directModel();
        this.timeoutMs = properties.directTimeoutMs();
        this.apiKey = apiKey;
        this.envKey = envKey;
        this.json = json;
        this.client = client;
        this.endpoint = URI.create(trimSlash(baseUrl) + PATH);
    }

    @Override
    public Optional<Render> locate(String transcript, byte[] jpeg) {
        if (!enabled) {
            return Optional.empty();
        }
        String key = key();
        if (key == null) {
            // No DeepSeek key: the backup is off, said once as metadata (never the key) so a
            // silent deployment is diagnosable. No request is made.
            if (missingKeyLogged.compareAndSet(false, true)) {
                log.debug("deepseek locator outcome=disabled reason=missing_key");
            }
            return Optional.empty();
        }
        if (transcript == null || transcript.isBlank() || jpeg == null || jpeg.length == 0) {
            return Optional.empty();
        }
        long started = System.nanoTime();
        String request;
        try {
            request = json.writeValueAsString(question(transcript, jpeg));
        } catch (Exception e) {
            // The body is plain strings and a base64 string; this cannot happen. Silent.
            log.debug("deepseek locator outcome=unbuildable reason={}", e.getClass().getSimpleName());
            return Optional.empty();
        }
        CompletableFuture<HttpResponse<String>> pending = null;
        int requestBytes = request.getBytes(StandardCharsets.UTF_8).length;
        try {
            HttpRequest http = HttpRequest.newBuilder(endpoint)
                    .timeout(Duration.ofMillis(timeoutMs))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + key)
                    .POST(HttpRequest.BodyPublishers.ofString(request, StandardCharsets.UTF_8))
                    .build();
            pending = client.sendAsync(http, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            // The budget covers the body as well as the headers, so the wait is bounded here
            // rather than by the request's own timeout alone.
            HttpResponse<String> response = pending.get(timeoutMs, TimeUnit.MILLISECONDS);
            long ms = elapsedMs(started);
            int responseBytes = bytes(response.body());
            if (response.statusCode() != 200) {
                log.debug("deepseek locator outcome=status status={} ms={} requestBytes={} responseBytes={}",
                        response.statusCode(), ms, requestBytes, responseBytes);
                return Optional.empty();
            }
            Optional<Render> render = parse(response.body(), transcript);
            log.debug("deepseek locator outcome={} ms={} requestBytes={} responseBytes={}",
                    render.isPresent() ? "render" : "no_render", ms, requestBytes, responseBytes);
            return render;
        } catch (TimeoutException e) {
            if (pending != null) {
                pending.cancel(true);
            }
            log.debug("deepseek locator outcome=timeout ms={} requestBytes={}", elapsedMs(started), requestBytes);
            return Optional.empty();
        } catch (InterruptedException e) {
            // A shutdown between the send and its answer: nothing is owed to the phone.
            Thread.currentThread().interrupt();
            return Optional.empty();
        } catch (ExecutionException e) {
            log.debug("deepseek locator outcome=failed reason={} ms={}",
                    e.getCause() == null ? e.getClass().getSimpleName() : e.getCause().getClass().getSimpleName(),
                    elapsedMs(started));
            return Optional.empty();
        } catch (RuntimeException e) {
            log.debug("deepseek locator outcome=failed reason={} ms={}", e.getClass().getSimpleName(), elapsedMs(started));
            return Optional.empty();
        }
    }

    /** The provider key: the property override first, the environment second, never logged. */
    private String key() {
        if (apiKey != null && !apiKey.isBlank()) {
            return apiKey.trim();
        }
        String fromEnv = envKey == null ? null : envKey.get();
        return fromEnv == null || fromEnv.isBlank() ? null : fromEnv.trim();
    }

    /** The OpenAI-shaped chat request: one system rule, one question with the JPEG data URL. */
    private Map<String, Object> question(String transcript, byte[] jpeg) {
        String dataUrl = "data:image/jpeg;base64," + Base64.getEncoder().encodeToString(jpeg);
        Map<String, Object> user = new LinkedHashMap<>();
        user.put("role", "user");
        user.put("content", List.of(
                Map.of("type", "text", "text", transcript),
                Map.of("type", "image_url", "image_url", Map.of("url", dataUrl))));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("temperature", 0);
        body.put("max_tokens", 200);
        body.put("messages", List.of(Map.of("role", "system", "content", SYSTEM_PROMPT), user));
        return body;
    }

    /**
     * The model's answer, translated (spec D3): {@code choices[0].message.content} is JSON
     * with a {@code box_2d} on the grid; anything unreadable, unplaceable or absurd is no
     * answer at all rather than a guess.
     */
    private Optional<Render> parse(String responseBody, String transcript) {
        JsonNode content = content(responseBody);
        if (content == null || !content.isTextual() || content.asText().isBlank()) {
            return Optional.empty();
        }
        JsonNode answer;
        try {
            answer = json.readTree(content.asText().trim());
        } catch (Exception e) {
            return Optional.empty();
        }
        if (answer == null || !answer.isObject()) {
            return Optional.empty();
        }
        JsonNode box = answer.get("box_2d");
        if (box == null || !box.isArray() || box.size() != 4) {
            return Optional.empty();
        }
        double[] grid = new double[4];
        for (int i = 0; i < 4; i++) {
            JsonNode corner = box.get(i);
            if (corner == null || !corner.isNumber()) {
                return Optional.empty();
            }
            grid[i] = corner.asDouble();
            if (!Double.isFinite(grid[i])) {
                return Optional.empty();
            }
        }
        JsonNode confidence = answer.get("confidence");
        if (confidence != null && (!confidence.isNumber()
                || confidence.asDouble() < 0 || confidence.asDouble() > 1)) {
            return Optional.empty();
        }
        if (confidence != null && confidence.asDouble() < MIN_CONFIDENCE) {
            // The plan says to ignore a low-confidence answer: a box the model is not half
            // sure of would put the overlay on the wrong object, which is worse than none.
            return Optional.empty();
        }
        double y0 = clamp(grid[0]);
        double x0 = clamp(grid[1]);
        double y1 = clamp(grid[2]);
        double x1 = clamp(grid[3]);
        if (y1 <= y0 || x1 <= x0) {
            // No area inside the frame: the corners are swapped or the box collapsed.
            return Optional.empty();
        }
        String text = transcript.trim();
        return Optional.of(new Render(text, text, new double[] {
                x0 / GRID, y0 / GRID, (x1 - x0) / GRID, (y1 - y0) / GRID}));
    }

    /** {@code choices[0].message.content} of the completion, or null when it is not there. */
    private JsonNode content(String responseBody) {
        if (responseBody == null || responseBody.isBlank()) {
            return null;
        }
        try {
            JsonNode root = json.readTree(responseBody);
            JsonNode choices = root == null ? null : root.path("choices");
            if (choices == null || !choices.isArray() || choices.isEmpty()) {
                return null;
            }
            return choices.get(0).path("message").path("content");
        } catch (Exception e) {
            return null;
        }
    }

    private static double clamp(double value) {
        return Math.min(GRID, Math.max(0, value));
    }

    private static long elapsedMs(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000;
    }

    private static int bytes(String value) {
        return value == null ? 0 : value.getBytes(StandardCharsets.UTF_8).length;
    }

    private static String trimSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }
}
