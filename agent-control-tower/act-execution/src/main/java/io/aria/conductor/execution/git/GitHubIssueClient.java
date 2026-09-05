package io.aria.conductor.execution.git;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministic backend channel that grounds an SDD spec-task issue reference
 * (R9-F2) against the GitHub REST API before the task is dispatched to the BA
 * agent. It is the {@code gh issue view} equivalent, running server-side so the
 * sandbox never depends on {@code gh} availability or network access.
 *
 * <p>Mirrors {@link GitBranchService}: the {@code GH_TOKEN} is injected through the
 * constructor (never read from the environment inside the class) so the client is
 * testable against a WireMock server, and the API base URL is injectable for tests.
 *
 * <p>Resolution rules:
 * <ul>
 *   <li>a numeric key ({@code 42} or {@code #42}) or a full
 *       {@code https://github.com/owner/repo/issues/42} URL is fetched directly;</li>
 *   <li>any other key (a slug such as {@code #qoder-regression}) is resolved through
 *       the repository's issue search; when nothing matches, dispatch fails fast with
 *       {@link IssueReferenceException} instead of emitting an ungrounded task.</li>
 * </ul>
 */
public class GitHubIssueClient {

    static final String DEFAULT_API_BASE_URL = "https://api.github.com";
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);
    private static final int MAX_ATTEMPTS = 3;
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Logger log = LoggerFactory.getLogger(GitHubIssueClient.class);

    private static final Pattern OWNER_REPO_PLAIN = Pattern.compile(
            "^[A-Za-z0-9](?:[A-Za-z0-9._-]*[A-Za-z0-9])?/[A-Za-z0-9_.-]+$");
    private static final Pattern GITHUB_REPO_URL = Pattern.compile(
            "^https?://(?:www\\.)?github\\.com/([A-Za-z0-9_.-]+)/([A-Za-z0-9_.-]+?)(?:\\.git)?/?$");
    private static final Pattern GITHUB_ISSUE_URL = Pattern.compile(
            "^https?://(?:www\\.)?github\\.com/([A-Za-z0-9_.-]+)/([A-Za-z0-9_.-]+?)/issues/([0-9]+)/?$");
    private static final Pattern NUMERIC = Pattern.compile("^#?([0-9]+)$");

    private final String ghToken;
    private final String apiBaseUrl;
    private final HttpClient httpClient;

    /** Production entry point: token supplied explicitly (injectable for tests). */
    public GitHubIssueClient(String ghToken) {
        this(ghToken, DEFAULT_API_BASE_URL);
    }

    /** Test entry point: allow pointing the API base at a WireMock server. */
    GitHubIssueClient(String ghToken, String apiBaseUrl) {
        this.ghToken = ghToken;
        this.apiBaseUrl = (apiBaseUrl == null || apiBaseUrl.isBlank())
                ? DEFAULT_API_BASE_URL : apiBaseUrl;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(REQUEST_TIMEOUT)
                .build();
    }

    /** Read the token from {@code GH_TOKEN} (used by Spring wiring / tests). */
    public static GitHubIssueClient fromEnvironment() {
        return new GitHubIssueClient(System.getenv("GH_TOKEN"));
    }

    /** True when a token is configured; a token-less client can never ground an issue. */
    public boolean isEnabled() {
        return ghToken != null && !ghToken.isBlank();
    }

    /**
     * Normalize a caller-supplied {@code issueRepo} value into {@code owner/repo}.
     * Accepts plain {@code owner/repo} and {@code https://github.com/owner/repo(.git)}
     * clone URLs; anything else (including blank or an un-substituted placeholder) is
     * rejected so the dispatcher never builds a task message with a bogus repository.
     */
    public static String parseRepository(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (value.isEmpty()) {
            throw new IllegalArgumentException(
                    "issueRepo is required (owner/repo); refusing to dispatch an SDD spec task "
                            + "without an authoritative repository");
        }
        if (value.startsWith("{") || value.contains("{issueRepo}")) {
            throw new IllegalArgumentException(
                    "issueRepo was not substituted; refusing to dispatch an SDD spec task with the "
                            + "literal {issueRepo} placeholder");
        }
        Matcher url = GITHUB_REPO_URL.matcher(value);
        if (url.matches()) {
            return url.group(1) + "/" + url.group(2);
        }
        if (OWNER_REPO_PLAIN.matcher(value).matches()) {
            return value;
        }
        throw new IllegalArgumentException(
                "issueRepo must be owner/repo or a github.com repository URL, got: " + value);
    }

    /**
     * Resolve {@code issueKey} inside {@code repository} and fetch the authoritative
     * issue payload. Fails fast (never returns a partial/ungrounded result).
     *
     * @param repository normalized {@code owner/repo}
     * @param issueKey   numeric number, {@code #<number>}, a full issue URL, or a slug
     * @return the resolved issue with its full title/body/labels
     * @throws IssueReferenceException when the key is unresolvable, invisible, or the
     *                                 fetch repeatedly fails; {@link IllegalArgumentException}
     *                                 when {@code repository} is not a valid {@code owner/repo}
     */
    public GitHubIssue resolveIssue(String repository, String issueKey) {
        if (!isEnabled()) {
            throw IssueReferenceException.disabled(issueKey, repository);
        }
        String repo = parseRepository(repository);
        String key = issueKey == null ? "" : issueKey.trim();

        Matcher numeric = NUMERIC.matcher(key);
        if (numeric.matches()) {
            return fetchIssue(repo, Integer.parseInt(numeric.group(1)), key);
        }

        Matcher url = GITHUB_ISSUE_URL.matcher(key);
        if (url.matches()) {
            String urlRepo = url.group(1) + "/" + url.group(2);
            if (!repo.equalsIgnoreCase(urlRepo)) {
                throw new IllegalArgumentException(
                        "issueRepo (" + repo + ") does not match the issue URL repository (" + urlRepo + ")");
            }
            return fetchIssue(repo, Integer.parseInt(url.group(3)), key);
        }

        return resolveBySearch(repo, key);
    }

    /** Resolve a non-numeric slug through the repository's issue search; fail fast when nothing matches. */
    private GitHubIssue resolveBySearch(String repo, String key) {
        String slug = key.startsWith("#") ? key.substring(1) : key;
        if (slug.isBlank()) {
            throw IssueReferenceException.notFound(key, repo);
        }
        String query = "repo:" + repo + " type:issue " + slug;
        HttpResponse<String> response = getWithRetries(
                "/search/issues?q=" + URLEncoder.encode(query, StandardCharsets.UTF_8), key, repo);
        int status = response.statusCode();
        if (status == 200) {
            JsonNode node = parse(response.body());
            JsonNode items = node.path("items");
            if (items.isArray() && !items.isEmpty()) {
                int best = bestMatch(items, slug);
                if (best > 0) {
                    return fetchIssue(repo, best, key);
                }
            }
            log.info("GitHub issue search found no match for slug '{}' in {}", slug, repo);
            throw IssueReferenceException.notFound(key, repo);
        }
        if (status == 401 || status == 403 || status == 404 || status == 422) {
            // not-found / no-permission / unsearchable slug: treat uniformly as not-found
            log.warn("GitHub issue search for '{}' in {} answered {}; treating as not found",
                    slug, repo, status);
            throw IssueReferenceException.notFound(key, repo);
        }
        throw IssueReferenceException.transientFailure(key, repo, errorMessage(response));
    }

    private int bestMatch(JsonNode items, String slug) {
        String wanted = slug.toLowerCase();
        int fallback = -1;
        for (JsonNode item : items) {
            int number = item.path("number").asInt(0);
            String title = item.path("title").asText("");
            if (number > 0 && fallback < 0) {
                fallback = number;
            }
            if (title.toLowerCase().contains(wanted)) {
                return number;
            }
        }
        return fallback;
    }

    /** GET /repos/{repo}/issues/{number}; maps 401/403/404/410 to not-found and retries transient errors. */
    public GitHubIssue fetchIssue(String repository, int number, String originalKey) {
        String repo = parseRepository(repository);
        HttpResponse<String> response = getWithRetries("/repos/" + repo + "/issues/" + number,
                originalKey, repo);
        int status = response.statusCode();
        if (status == 200) {
            JsonNode node = parse(response.body());
            List<String> labels = new ArrayList<>();
            for (JsonNode label : node.path("labels")) {
                String name = label.path("name").asText(null);
                if (name != null && !name.isBlank()) {
                    labels.add(name);
                }
            }
            return new GitHubIssue(repo, number,
                    node.path("title").asText(""),
                    node.path("body").asText(""),
                    labels);
        }
        if (status == 404 || status == 410 || status == 401 || status == 403) {
            // not-found / no-permission / gone: uniformly not-found to the caller
            if (status == 401 || status == 403) {
                log.warn("GitHub issue {} in {} answered {}; treating as not found (no permission)",
                        number, repo, status);
            }
            throw IssueReferenceException.notFound(originalKey, repo);
        }
        throw IssueReferenceException.transientFailure(originalKey, repo, errorMessage(response));
    }

    /** GET with bounded retry + exponential backoff for transient failures (5xx/429/network). */
    private HttpResponse<String> getWithRetries(String path, String key, String repo) {
        IOException lastIo = null;
        int lastStatus = 0;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            HttpResponse<String> response;
            try {
                response = send(newRequest(path).GET().build());
            } catch (IOException e) {
                lastIo = e;
                backoff(attempt, key, repo);
                continue;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw IssueReferenceException.transientFailure(key, repo, "request interrupted");
            }
            int status = response.statusCode();
            if (status < 500 && status != 429) {
                return response;
            }
            lastStatus = status;
            backoff(attempt, key, repo);
        }
        if (lastIo != null) {
            throw IssueReferenceException.transientFailure(key, repo, lastIo.getMessage());
        }
        throw IssueReferenceException.transientFailure(key, repo, "HTTP " + lastStatus);
    }

    private void backoff(int attempt, String key, String repo) {
        if (attempt >= MAX_ATTEMPTS) {
            return;
        }
        long millis = 200L * (1L << (attempt - 1));
        log.warn("GitHub issue grounding attempt {} for {} in {} failed; retrying in {}ms",
                attempt, key, repo, millis);
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw IssueReferenceException.transientFailure(key, repo, "backoff interrupted");
        }
    }

    // ---- helpers (mirrors GitBranchService) ----

    private HttpResponse<String> send(HttpRequest request)
            throws IOException, InterruptedException {
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
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
            throw new IssueReferenceException(null, null, 0, true,
                    "Invalid JSON from GitHub API: " + e.getMessage());
        }
    }
}
