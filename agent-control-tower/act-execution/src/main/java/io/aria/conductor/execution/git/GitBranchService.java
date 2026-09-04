package io.aria.conductor.execution.git;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Deterministic backend channel for the SDD branch handoff, backed purely by
 * the GitHub REST API (no local git binary). The {@code GH_TOKEN} is injected
 * through the constructor (never read from the environment inside the class)
 * so the service is testable against a WireMock server.
 *
 * <p>repoUrl parsing: {@code https://github.com/owner/repo.git} -> {@code owner/repo}.
 */
public class GitBranchService {

    static final String DEFAULT_API_BASE_URL = "https://api.github.com";
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);
    private static final int HTTP_UNPROCESSABLE_ENTITY = 422;
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Logger log = LoggerFactory.getLogger(GitBranchService.class);

    private final String ghToken;
    private final String apiBaseUrl;
    private final HttpClient httpClient;

    /** Production entry point: token supplied explicitly (injectable for tests). */
    public GitBranchService(String ghToken) {
        this(ghToken, DEFAULT_API_BASE_URL);
    }

    /** Test entry point: allow pointing the API base at a WireMock server. */
    GitBranchService(String ghToken, String apiBaseUrl) {
        this.ghToken = ghToken;
        this.apiBaseUrl = (apiBaseUrl == null || apiBaseUrl.isBlank())
                ? DEFAULT_API_BASE_URL : apiBaseUrl;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(REQUEST_TIMEOUT)
                .build();
    }

    /** Read the token from {@code GH_TOKEN} (used by Spring wiring / tests). */
    public static GitBranchService fromEnvironment() {
        return new GitBranchService(System.getenv("GH_TOKEN"));
    }

    /**
     * Create a new branch off the repository's default branch:
     * GET repo -> default_branch, GET that ref -> object.sha, POST /git/refs.
     *
     * <p>Idempotent on re-approval: when GitHub answers 422 because the ref already
     * exists (e.g. SDD re-approval of the same chain), the existing branch is verified
     * via GET and reused — it is never deleted or force-updated.
     *
     * @throws GitBranchException on API error / 401 / a 422 whose ref is genuinely absent.
     */
    public void createBranch(String repoUrl, String branchName) {
        String ownerRepo = parseOwnerRepo(repoUrl);

        JsonNode repo = getJson("/repos/" + ownerRepo);
        String defaultBranch = repo.path("default_branch").asText(null);
        if (defaultBranch == null || defaultBranch.isBlank()) {
            throw new GitBranchException(0, "GitHub repo has no default_branch: " + ownerRepo);
        }

        JsonNode ref = getJson("/repos/" + ownerRepo + "/git/ref/heads/" + defaultBranch);
        String baseSha = ref.path("object").path("sha").asText(null);
        if (baseSha == null || baseSha.isBlank()) {
            throw new GitBranchException(0, "GitHub ref missing object.sha for branch: " + defaultBranch);
        }

        ObjectNode body = MAPPER.createObjectNode();
        body.put("ref", "refs/heads/" + branchName);
        body.put("sha", baseSha);
        HttpResponse<String> response = postJson("/repos/" + ownerRepo + "/git/refs", body.toString());
        if (response.statusCode() == HTTP_UNPROCESSABLE_ENTITY) {
            // 422 may be the already-exists race — verify via GET; the existing branch
            // is authoritative and is reused as-is (no delete / no force-update).
            if (branchHeadSha(repoUrl, branchName).isPresent()) {
                log.info("Branch '{}' already exists on {} — reusing the existing branch",
                        branchName, ownerRepo);
                return;
            }
            throw new GitBranchException(HTTP_UNPROCESSABLE_ENTITY, errorMessage(response));
        }
        ensureSuccess(response);
    }

    /**
     * Upload (create or replace) a single file on the branch via the contents API.
     * The file content is base64-encoded per the GitHub contract.
     */
    public void putFile(String repoUrl, String branchName, String path, String content, String commitMessage) {
        String ownerRepo = parseOwnerRepo(repoUrl);
        String encoded = Base64.getEncoder()
                .encodeToString(content.getBytes(StandardCharsets.UTF_8));

        ObjectNode body = MAPPER.createObjectNode();
        body.put("message", commitMessage);
        body.put("content", encoded);
        body.put("branch", branchName);
        putJson("/repos/" + ownerRepo + "/contents/" + path, body.toString());
    }

    /** Read a file from the branch and decode its base64 {@code content}; empty when absent. */
    public Optional<String> getFile(String repoUrl, String branchName, String path) {
        String ownerRepo = parseOwnerRepo(repoUrl);
        HttpResponse<String> response = rawGet(
                "/repos/" + ownerRepo + "/contents/" + path + "?ref=" + branchName);
        if (response.statusCode() == 404) {
            return Optional.empty();
        }
        ensureSuccess(response);
        JsonNode node = parse(response.body());
        String encoded = node.path("content").asText(null);
        if (encoded == null || encoded.isBlank()) {
            return Optional.empty();
        }
        try {
            byte[] decoded = Base64.getMimeDecoder().decode(encoded);
            return Optional.of(new String(decoded, StandardCharsets.UTF_8));
        } catch (IllegalArgumentException e) {
            throw new GitBranchException(response.statusCode(), "Invalid base64 content from GitHub API");
        }
    }

    /** Resolve the branch HEAD sha; empty when the branch does not exist (404). */
    public Optional<String> branchHeadSha(String repoUrl, String branchName) {
        String ownerRepo = parseOwnerRepo(repoUrl);
        HttpResponse<String> response = rawGet(
                "/repos/" + ownerRepo + "/git/ref/heads/" + branchName);
        if (response.statusCode() == 404) {
            return Optional.empty();
        }
        ensureSuccess(response);
        JsonNode node = parse(response.body());
        return Optional.ofNullable(node.path("object").path("sha").asText(null));
    }

    // ---- helpers ----

    static String parseOwnerRepo(String repoUrl) {
        if (repoUrl == null || repoUrl.isBlank()) {
            throw new GitBranchException(0, "repoUrl is required");
        }
        String path = repoUrl;
        int schemeIdx = path.indexOf("://");
        if (schemeIdx >= 0) {
            path = path.substring(schemeIdx + 3);
        }
        int firstSlash = path.indexOf('/');
        if (firstSlash >= 0) {
            path = path.substring(firstSlash + 1); // drop host[:port]
        }
        int queryIdx = path.indexOf('?');
        if (queryIdx >= 0) {
            path = path.substring(0, queryIdx);
        }
        int fragmentIdx = path.indexOf('#');
        if (fragmentIdx >= 0) {
            path = path.substring(0, fragmentIdx);
        }
        if (path.endsWith(".git")) {
            path = path.substring(0, path.length() - 4);
        }
        while (path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        if (path.isBlank() || !path.contains("/")) {
            throw new GitBranchException(0, "Unable to parse owner/repo from repoUrl: " + repoUrl);
        }
        return path;
    }

    private JsonNode getJson(String path) {
        HttpResponse<String> response = rawGet(path);
        ensureSuccess(response);
        return parse(response.body());
    }

    /** POST JSON and return the raw response; the caller decides error handling. */
    private HttpResponse<String> postJson(String path, String body) {
        return send(newRequest(path)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build());
    }

    private void putJson(String path, String body) {
        HttpResponse<String> response = send(newRequest(path)
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(body))
                .build());
        ensureSuccess(response);
    }

    private HttpResponse<String> rawGet(String path) {
        return send(newRequest(path).GET().build());
    }

    private HttpRequest.Builder newRequest(String path) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(apiBaseUrl + path))
                .timeout(REQUEST_TIMEOUT)
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .header("User-Agent", "aria-conductor");
        if (ghToken != null && !ghToken.isBlank()) {
            builder.header("Authorization", "Bearer " + ghToken);
        }
        return builder;
    }

    private HttpResponse<String> send(HttpRequest request) {
        try {
            return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new GitBranchException(0, "GitHub API request failed: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new GitBranchException(0, "GitHub API request interrupted");
        }
    }

    private static void ensureSuccess(HttpResponse<String> response) {
        int status = response.statusCode();
        if (status < 200 || status >= 300) {
            throw new GitBranchException(status, errorMessage(response));
        }
    }

    private static String errorMessage(HttpResponse<String> response) {
        String detail = "";
        try {
            JsonNode node = MAPPER.readTree(response.body());
            String message = node.path("message").asText(null);
            if (message != null && !message.isBlank()) {
                detail = ": " + message;
            }
        } catch (JsonProcessingException ignored) {
            // non-JSON error body — leave the detail empty
        }
        return "GitHub API error " + response.statusCode() + detail;
    }

    private static JsonNode parse(String body) {
        try {
            return MAPPER.readTree(body);
        } catch (JsonProcessingException e) {
            throw new GitBranchException(0, "Invalid JSON from GitHub API: " + e.getMessage());
        }
    }
}
