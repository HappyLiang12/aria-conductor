package io.aria.conductor.app.e2e;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Explicit setup of the deterministic core E2E harness (Task 16).
 *
 * <p>Runs <em>after</em> the harness application is reachable, as a separate
 * step of the stack start (it talks HTTP, it is not part of the application
 * context):
 *
 * <pre>
 * ARIA_OPERATOR_BEARER_TOKEN=&lt;synthetic operator credential&gt; \
 * java -cp "&lt;harness&gt;/app:&lt;harness&gt;/harness:&lt;harness&gt;/lib/*" \
 *     io.aria.conductor.app.e2e.CoreE2eSetup --confirm-retire-historical-seeds \
 *     [--base-url=http://127.0.0.1:8080] [--health-timeout-seconds=120]
 * </pre>
 *
 * <p>Steps, in order and with no fallback:
 * <ol>
 *   <li>wait for {@code GET /actuator/health} and require the exact body
 *       {@code {"status":"UP"}};</li>
 *   <li>{@code POST /api/v1/maintenance/langchain/preview} and
 *       {@code POST /api/v1/maintenance/langchain/execute} with the verified
 *       digest -- the production preview-first retirement, never a database
 *       shortcut;</li>
 *   <li>{@code POST /api/v1/maintenance/initialize-builtins} -- the production
 *       create-only built-in setup;</li>
 *   <li>print every receipt so the caller records what setup actually did.</li>
 * </ol>
 *
 * <p><b>Target URL resolution, in precedence order:</b> the
 * {@code --base-url=<url>} flag wins when given; with the flag absent the
 * environment variable {@code E2E_BASE_URL} is read; and only when both are
 * absent does {@link #DEFAULT_BASE_URL} apply. The resolved target is echoed on
 * the first receipt line ({@code health OK <url>}), so a run log always shows
 * which base URL was used.
 *
 * <p>The synthetic operator credential is supplied by the environment
 * ({@value #OPERATOR_TOKEN_ENV}, the same variable the production operator
 * boundary reads); it is never generated, defaulted or logged. The retirement
 * confirmation flag is required: without it the setup refuses loudly
 * (exit code 2) instead of retiring historical seeds implicitly. Operational
 * failures -- unreachable application, unexpected health body, refused
 * retirement, unexpected setup response -- exit non-zero with the observed
 * response, never with a silent success.
 */
public final class CoreE2eSetup {

    /** Environment variable carrying the synthetic operator bearer credential. */
    public static final String OPERATOR_TOKEN_ENV = "ARIA_OPERATOR_BEARER_TOKEN";

    /** Explicit confirmation required before historical seeds are retired. */
    public static final String CONFIRM_RETIRE_FLAG = "--confirm-retire-historical-seeds";

    static final String DEFAULT_BASE_URL = "http://127.0.0.1:8080";
    static final String HEALTH_PATH = "/actuator/health";
    static final String EXPECTED_HEALTH_BODY = "{\"status\":\"UP\"}";
    static final String PREVIEW_PATH = "/api/v1/maintenance/langchain/preview";
    static final String EXECUTE_PATH = "/api/v1/maintenance/langchain/execute";
    static final String BUILTINS_PATH = "/api/v1/maintenance/initialize-builtins";

    static final String BASE_URL_ARG = "--base-url";
    static final String HEALTH_TIMEOUT_ARG = "--health-timeout-seconds";
    static final int DEFAULT_HEALTH_TIMEOUT_SECONDS = 120;

    private static final ObjectMapper JSON = new ObjectMapper();

    private CoreE2eSetup() {
    }

    public static void main(String[] args) {
        try {
            run(List.of(args));
        } catch (SetupRefusal refusal) {
            System.err.println("core-e2e setup refused: " + refusal.getMessage());
            System.exit(2);
        } catch (Exception failure) {
            System.err.println("core-e2e setup failed: " + failure.getMessage());
            System.exit(1);
        }
    }

    /** The whole setup; every refusal and failure aborts with no later step attempted. */
    static void run(List<String> args) throws IOException, InterruptedException {
        Map<String, String> options = parseOptions(args);
        if (!args.contains(CONFIRM_RETIRE_FLAG)) {
            throw new SetupRefusal("refusing to retire historical seeds without " + CONFIRM_RETIRE_FLAG
                    + "; the harness setup never bypasses preview-first retirement and never"
                    + " executes a retirement the operator did not explicitly request");
        }
        String operatorToken = System.getenv(OPERATOR_TOKEN_ENV);
        if (operatorToken == null || operatorToken.isBlank()) {
            throw new SetupRefusal("no synthetic operator credential in the environment ("
                    + OPERATOR_TOKEN_ENV + " is unset); refusing to call the operator-only"
                    + " maintenance endpoints without it");
        }
        // Precedence (see the class javadoc): --base-url, then E2E_BASE_URL, then the default.
        String baseUrl = stripTrailingSlash(options.getOrDefault(BASE_URL_ARG,
                System.getenv().getOrDefault("E2E_BASE_URL", DEFAULT_BASE_URL)));
        int healthTimeoutSeconds = parseIntOption(options, HEALTH_TIMEOUT_ARG, DEFAULT_HEALTH_TIMEOUT_SECONDS);

        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        awaitHealthy(http, baseUrl, Duration.ofSeconds(healthTimeoutSeconds));

        JsonNode preview = postJson(http, baseUrl + PREVIEW_PATH, null, operatorToken, "retirement preview");
        String previewId = requiredText(preview, "previewId", "retirement preview");
        String digest = requiredText(preview, "digest", "retirement preview");
        System.out.println("core-e2e setup: retirement preview previewId=" + previewId + " digest=" + digest
                + " agents=" + preview.path("agentIds").size() + " runs=" + preview.path("runIds").size());

        JsonNode receipt = postJson(http, baseUrl + EXECUTE_PATH,
                JSON.writeValueAsString(Map.of("previewId", previewId, "expectedDigest", digest)),
                operatorToken, "retirement execute");
        // The receipt is the only record of what the destructive step did: print it whole.
        System.out.println("core-e2e setup: retirement receipt " + receipt);

        JsonNode builtins = postJson(http, baseUrl + BUILTINS_PATH, null, operatorToken, "built-in setup");
        System.out.println("core-e2e setup: built-ins created=" + builtins.path("createdAgentIds").size()
                + " existing=" + builtins.path("existingAgentIds").size());

        System.out.println("core-e2e setup: OK");
    }

    /** Polls the health endpoint until the exact UP body arrives; never a partial acceptance. */
    private static void awaitHealthy(HttpClient http, String baseUrl, Duration timeout)
            throws InterruptedException {
        URI healthUri = URI.create(baseUrl + HEALTH_PATH);
        long deadline = System.nanoTime() + timeout.toNanos();
        String lastObservation = "not attempted";
        while (System.nanoTime() < deadline) {
            try {
                HttpResponse<String> response = http.send(
                        HttpRequest.newBuilder(healthUri).timeout(Duration.ofSeconds(5)).GET().build(),
                        HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() == 200) {
                    String body = response.body() == null ? "" : response.body().trim();
                    if (!EXPECTED_HEALTH_BODY.equals(body)) {
                        throw new SetupRefusal("unexpected health body from " + healthUri + ": " + body
                                + " (expected exactly " + EXPECTED_HEALTH_BODY + ")");
                    }
                    System.out.println("core-e2e setup: health OK " + healthUri + " body=" + body);
                    return;
                }
                lastObservation = "HTTP " + response.statusCode() + " " + response.body();
            } catch (IOException e) {
                lastObservation = e.getClass().getSimpleName() + ": " + e.getMessage();
            }
            Thread.sleep(1000);
        }
        throw new SetupRefusal("the harness application did not become healthy within " + timeout.toSeconds()
                + "s at " + healthUri + " (last observation: " + lastObservation + ")");
    }

    /** One operator-authenticated POST; any non-2xx is a loud failure with the observed body. */
    private static JsonNode postJson(HttpClient http, String url, String body, String operatorToken, String step)
            throws IOException, InterruptedException {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(60))
                .header("Authorization", "Bearer " + operatorToken)
                .header("Content-Type", "application/json");
        request.POST(body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body));
        HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() / 100 != 2) {
            throw new SetupRefusal(step + " refused by " + url + ": HTTP " + response.statusCode()
                    + " " + response.body());
        }
        try {
            return JSON.readTree(response.body());
        } catch (IOException e) {
            throw new SetupRefusal(step + " returned a non-JSON body from " + url + ": "
                    + response.body());
        }
    }

    private static String requiredText(JsonNode node, String field, String step) {
        String value = node.path(field).asText("");
        if (value.isBlank()) {
            throw new SetupRefusal(step + " response carries no " + field + ": " + node);
        }
        return value;
    }

    /** Strict {@code --key=value} parsing; unknown options are refused, never ignored. */
    private static Map<String, String> parseOptions(List<String> args) {
        Map<String, String> options = new java.util.LinkedHashMap<>();
        List<String> unknown = new ArrayList<>();
        for (String arg : args) {
            if (arg.equals(CONFIRM_RETIRE_FLAG)) {
                continue;
            }
            if (arg.startsWith(BASE_URL_ARG + "=")) {
                options.put(BASE_URL_ARG, arg.substring((BASE_URL_ARG + "=").length()));
            } else if (arg.startsWith(HEALTH_TIMEOUT_ARG + "=")) {
                options.put(HEALTH_TIMEOUT_ARG, arg.substring((HEALTH_TIMEOUT_ARG + "=").length()));
            } else {
                unknown.add(arg);
            }
        }
        if (!unknown.isEmpty()) {
            throw new SetupRefusal("unknown argument(s) " + unknown + "; supported: "
                    + CONFIRM_RETIRE_FLAG + ", " + BASE_URL_ARG + "=, " + HEALTH_TIMEOUT_ARG + "=");
        }
        return options;
    }

    private static int parseIntOption(Map<String, String> options, String name, int defaultValue) {
        String value = options.get(name);
        if (value == null) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new SetupRefusal(name + " must be an integer but was: " + value);
        }
    }

    private static String stripTrailingSlash(String baseUrl) {
        return baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    /** An explicit refusal: nothing was bypassed, the operator must fix the environment. */
    static final class SetupRefusal extends RuntimeException {
        SetupRefusal(String message) {
            super(message);
        }
    }
}
