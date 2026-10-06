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
 *     io.aria.conductor.app.e2e.CoreE2eSetup \
 *     [--base-url=http://127.0.0.1:8080] [--health-timeout-seconds=120]
 * </pre>
 *
 * <p>Steps, in order and with no fallback:
 * <ol>
 *   <li>wait for {@code GET /actuator/health} and require the exact body
 *       {@code {"status":"UP"}};</li>
 *   <li>{@code POST /api/v1/maintenance/initialize-builtins} -- the production
 *       create-only built-in setup;</li>
 *   <li>{@code PUT /api/v1/cores/qoder/credential} -- provisions the
 *       harness-scoped synthetic Qoder runtime credential through the production
 *       operator route (Task 19 fix round 1; 2026-10-05 simplification URL), so
 *       a qoder-placed run resolves it from the managed store instead of failing
 *       admission. The value comes from {@value #QODER_CREDENTIAL_ENV} and the
 *       step refuses without it; the step also refuses a response that reports
 *       the credential unusable or echoes a value;</li>
 *   <li>print every receipt so the caller records what setup actually did.</li>
 * </ol>
 *
 * <p>The plain credential store needs no extra key material (2026-10-05
 * simplification): the route stores the supplied value as given and answers
 * masked only. No credential value is generated or defaulted here.</p>
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
 * boundary reads); it is never generated, defaulted or logged. Operational
 * failures -- unreachable application, unexpected health body, refused
 * setup response -- exit non-zero with the observed
 * response, never with a silent success.
 */
public final class CoreE2eSetup {

    /** Environment variable carrying the synthetic operator bearer credential. */
    public static final String OPERATOR_TOKEN_ENV = "ARIA_OPERATOR_BEARER_TOKEN";

    /**
     * Environment variable carrying the harness-scoped synthetic Qoder runtime
     * credential value (Task 19 fix round 1). The value is never generated,
     * defaulted, written into the repository or logged: the setup refuses
     * without it, and the production credential route stores it as given in the
     * plain core credential store (masked on read; the value is never returned).
     */
    public static final String QODER_CREDENTIAL_ENV = "ARIA_E2E_QODER_CREDENTIAL";

    static final String DEFAULT_BASE_URL = "http://127.0.0.1:8080";
    static final String HEALTH_PATH = "/actuator/health";
    static final String EXPECTED_HEALTH_BODY = "{\"status\":\"UP\"}";
    static final String BUILTINS_PATH = "/api/v1/maintenance/initialize-builtins";
    static final String CREDENTIAL_PATH = "/api/v1/cores/qoder/credential";

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

        JsonNode builtins = postJson(http, baseUrl + BUILTINS_PATH, null, operatorToken, "built-in setup");
        System.out.println("core-e2e setup: built-ins created=" + builtins.path("createdAgentIds").size()
                + " existing=" + builtins.path("existingAgentIds").size());

        provisionQoderCredential(http, baseUrl, operatorToken);

        System.out.println("core-e2e setup: OK");
    }

    /**
     * Provisions the harness-scoped synthetic Qoder runtime credential through
     * the production operator route ({@code PUT .../cores/qoder/credential})
     * and pins the production service's masked answer: the credential must be
     * stored ({@code configured=true}) bound to the recorded reference and
     * environment variable, and answered masked only -- a response echoing any
     * value fragment is refused. Every qoder-placed run resolves this stored row
     * through {@code CoreCredentialService.resolve}; nothing here bypasses or
     * replaces that resolution.
     */
    private static void provisionQoderCredential(HttpClient http, String baseUrl, String operatorToken)
            throws IOException, InterruptedException {
        String secret = System.getenv(QODER_CREDENTIAL_ENV);
        if (secret == null || secret.isBlank()) {
            throw new SetupRefusal("no harness-scoped Qoder runtime credential in the environment ("
                    + QODER_CREDENTIAL_ENV + " is unset); the qoder-placed harness runs resolve the managed"
                    + " runtime credential from the production store, and the harness never fabricates or"
                    + " defaults one");
        }
        // The route reads the body as the raw credential value: no JSON envelope.
        JsonNode provisioned = sendJson(http, "PUT", baseUrl + CREDENTIAL_PATH, secret, operatorToken,
                "runtime credential provisioning");
        String maskedSecret = provisioned.path("maskedSecret").asText("");
        boolean pinned = provisioned.path("configured").asBoolean(false)
                && "qoder:operator".equals(provisioned.path("credentialRef").asText(""))
                && "qoder".equals(provisioned.path("coreId").asText(""))
                && "QODER_PERSONAL_ACCESS_TOKEN".equals(provisioned.path("environmentVariable").asText(""))
                && maskedSecret.startsWith("****") && !maskedSecret.contains(secret);
        if (!pinned) {
            throw new SetupRefusal("the production credential service did not confirm the harness credential as"
                    + " configured (qoder:operator / QODER_PERSONAL_ACCESS_TOKEN, masked): " + provisioned);
        }
        if (provisioned.toString().contains(secret)) {
            throw new SetupRefusal("the production credential service echoed the supplied secret value in its"
                    + " response; the harness refuses to continue on a leaking credential surface");
        }
        System.out.println("core-e2e setup: qoder runtime credential provisioned through the production route"
                + " (credentialRef=" + provisioned.path("credentialRef").asText("")
                + " env=" + provisioned.path("environmentVariable").asText("")
                + " masked=" + provisioned.path("maskedSecret").asText("")
                + "); the value is supplied by " + QODER_CREDENTIAL_ENV + " and never printed or written to disk");
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
        return sendJson(http, "POST", url, body, operatorToken, step);
    }

    /** One operator-authenticated JSON request; any non-2xx is a loud failure with the observed body. */
    private static JsonNode sendJson(HttpClient http, String method, String url, String body,
            String operatorToken, String step) throws IOException, InterruptedException {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(60))
                .header("Authorization", "Bearer " + operatorToken)
                .header("Content-Type", "application/json");
        HttpRequest.BodyPublisher publisher = body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body);
        request.method(method, publisher);
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

    /** Strict {@code --key=value} parsing; unknown options are refused, never ignored. */
    private static Map<String, String> parseOptions(List<String> args) {
        Map<String, String> options = new java.util.LinkedHashMap<>();
        List<String> unknown = new ArrayList<>();
        for (String arg : args) {
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
                    + BASE_URL_ARG + "=, " + HEALTH_TIMEOUT_ARG + "=");
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
