package io.aria.conductor.execution.git;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.aria.conductor.common.git.GitCredentialGuidance;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Deterministic backend channel for the SDD branch handoff, backed purely by
 * the GitHub REST API (no local git binary). The GitHub token is injected
 * through the constructor (never read from the environment inside the class)
 * so the service is testable against a WireMock server.
 *
 * <p>The token is read from its {@link GitTokenSource} on every request (F8), not
 * captured once: a credential stored through the git pack endpoint after startup
 * is authoritative for the next operation. Resolving it per request is what
 * removes the restart step the operator used to need. A source that resolves
 * nothing (or throws, e.g. on an undecryptable stored row) makes the operation
 * refuse with the documented {@link GitCredentialGuidance} and sends no request
 * at all, so a missing credential is never mistaken for an unauthenticated call.
 *
 * <p>repoUrl parsing: {@code https://github.com/owner/repo.git} -> {@code owner/repo}.
 */
public class GitBranchService {

    static final String DEFAULT_API_BASE_URL = "https://api.github.com";
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);
    private static final int HTTP_UNPROCESSABLE_ENTITY = 422;
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Logger log = LoggerFactory.getLogger(GitBranchService.class);

    private final GitTokenSource tokenSource;
    private final String apiBaseUrl;
    private final HttpClient httpClient;

    /** Production entry point: token supplied explicitly (injectable for tests). */
    public GitBranchService(String ghToken) {
        this(() -> ghToken, DEFAULT_API_BASE_URL);
    }

    /** Test entry point: allow pointing the API base at a WireMock server. */
    public GitBranchService(String ghToken, String apiBaseUrl) {
        this(() -> ghToken, apiBaseUrl);
    }

    /**
     * Production entry point: the token is resolved from {@code tokenSource} on
     * every request, so a store made after startup takes effect immediately.
     */
    public GitBranchService(GitTokenSource tokenSource) {
        this(tokenSource, DEFAULT_API_BASE_URL);
    }

    /** The per-request resolution with an explicit API base (test/WireMock seam). */
    public GitBranchService(GitTokenSource tokenSource, String apiBaseUrl) {
        this.tokenSource = java.util.Objects.requireNonNull(tokenSource, "tokenSource");
        this.apiBaseUrl = (apiBaseUrl == null || apiBaseUrl.isBlank())
                ? DEFAULT_API_BASE_URL : apiBaseUrl;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(REQUEST_TIMEOUT)
                .build();
    }

    /**
     * True when a non-blank token resolves right now, i.e. branch operations can
     * be attempted. Callers use this to fail fast instead of catching
     * {@link GitBranchException} from the first operation. A source whose stored
     * row cannot be read reports unavailable rather than throwing here.
     */
    public boolean isAvailable() {
        try {
            String token = tokenSource.token();
            return token != null && !token.isBlank();
        } catch (RuntimeException e) {
            log.warn("Could not resolve the GitHub credential — {}. Git branch operations will refuse"
                    + " until a readable credential is stored", e.getMessage());
            return false;
        }
    }

    /**
     * The credential this request carries. A blank answer refuses the operation
     * before any request is built: an unauthenticated call would be reported as a
     * 401/404 from GitHub instead of the operator guidance that explains what to
     * configure.
     */
    private String requireToken() {
        String token;
        try {
            token = tokenSource.token();
        } catch (RuntimeException e) {
            throw new GitBranchException(0, GitCredentialGuidance.REQUIRED_MESSAGE + " (" + e.getMessage() + ")");
        }
        if (token == null || token.isBlank()) {
            throw new GitBranchException(0, GitCredentialGuidance.REQUIRED_MESSAGE);
        }
        return token;
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

    // ---- commit read (F19 report-step tree guard) ----

    /**
     * One {@code files[]} entry of a GitHub commit: the new path plus GitHub's raw
     * status. The raw status is preserved rather than pre-judged so the caller can map
     * a {@code renamed} to something that is not a removal of the new path.
     */
    public record CommitFile(String path, Status status) {

        /** The {@code files[].status} values the commits API documents. */
        public enum Status { ADDED, MODIFIED, REMOVED, RENAMED, COPIED, CHANGED, UNKNOWN }

        public CommitFile {
            if (path == null || path.isBlank()) {
                throw new IllegalArgumentException("Commit file path is required");
            }
            status = status == null ? Status.UNKNOWN : status;
        }

        static Status fromGitHub(String raw) {
            if (raw == null || raw.isBlank()) {
                return Status.UNKNOWN;
            }
            return switch (raw.toLowerCase(java.util.Locale.ROOT)) {
                case "added" -> Status.ADDED;
                case "modified" -> Status.MODIFIED;
                case "removed" -> Status.REMOVED;
                case "renamed" -> Status.RENAMED;
                case "copied" -> Status.COPIED;
                case "changed" -> Status.CHANGED;
                default -> Status.UNKNOWN;
            };
        }
    }

    /** The parents and file changes of one commit, as the commits API reports them. */
    public record CommitFileChanges(String sha, List<String> parentShas, List<CommitFile> files) {
        public CommitFileChanges {
            parentShas = parentShas == null ? List.of() : List.copyOf(parentShas);
            files = files == null ? List.of() : List.copyOf(files);
        }
    }

    /**
     * Read one commit's parents and file-level changes:
     * {@code GET /repos/{owner}/{repo}/commits/{sha}} (F19).
     *
     * <p>Unlike the branch/file operations this never throws for an API failure. Its
     * caller is the report-step tree guard, and an infrastructure error (missing
     * credential, 404, 5xx, invalid payload) must leave the step's existing behaviour
     * intact instead of failing it — every such case is {@link Optional#empty()} plus a
     * log line that never contains the token.
     */
    public Optional<CommitFileChanges> commitFileChanges(String repoUrl, String commitSha) {
        if (commitSha == null || commitSha.isBlank()) {
            return Optional.empty();
        }
        String ownerRepo;
        try {
            ownerRepo = parseOwnerRepo(repoUrl);
        } catch (RuntimeException e) {
            // A missing or malformed URL is an infrastructure condition for this
            // read, not a step failure. The URL itself is not logged (it may carry
            // userinfo).
            log.warn("Could not parse the repository URL for commit {} — the tree guard"
                    + " degrades to no guard", commitSha);
            return Optional.empty();
        }
        HttpResponse<String> response;
        try {
            response = rawGet("/repos/" + ownerRepo + "/commits/" + commitSha);
        } catch (GitBranchException e) {
            log.warn("Could not read commit {} of {} — the tree guard degrades to no guard: {}",
                    commitSha, ownerRepo, e.getMessage());
            return Optional.empty();
        }
        int status = response.statusCode();
        if (status == 404) {
            log.debug("Commit {} not found on {}", commitSha, ownerRepo);
            return Optional.empty();
        }
        if (status < 200 || status >= 300) {
            log.warn("Could not read commit {} of {} — the tree guard degrades to no guard: {}",
                    commitSha, ownerRepo, errorMessage(response));
            return Optional.empty();
        }
        try {
            JsonNode node = parse(response.body());
            List<String> parents = new java.util.ArrayList<>();
            node.path("parents").forEach(parent -> {
                String sha = parent.path("sha").asText(null);
                if (sha != null && !sha.isBlank()) {
                    parents.add(sha);
                }
            });
            List<CommitFile> files = new java.util.ArrayList<>();
            node.path("files").forEach(file -> {
                String path = file.path("filename").asText(null);
                if (path != null && !path.isBlank()) {
                    files.add(new CommitFile(path, CommitFile.fromGitHub(file.path("status").asText(null))));
                }
            });
            return Optional.of(new CommitFileChanges(node.path("sha").asText(commitSha), parents, files));
        } catch (GitBranchException e) {
            log.warn("Invalid commit payload for {} of {} — the tree guard degrades to no guard: {}",
                    commitSha, ownerRepo, e.getMessage());
            return Optional.empty();
        }
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
            // Never echo the URL: a clone URL may carry userinfo (a token).
            throw new GitBranchException(0, "Unable to parse owner/repo from the repository URL");
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
        String token = requireToken();
        return HttpRequest.newBuilder(URI.create(apiBaseUrl + path))
                .timeout(REQUEST_TIMEOUT)
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .header("User-Agent", "aria-conductor")
                .header("Authorization", "Bearer " + token);
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
