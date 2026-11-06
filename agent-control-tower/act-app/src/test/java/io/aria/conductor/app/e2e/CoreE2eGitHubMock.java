package io.aria.conductor.app.e2e;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The harness's deterministic stand-in for the GitHub REST API the SDD branch
 * handoff talks to (Task 20 wiring of the T17 obligation).
 *
 * <p>Approving a SPEC_REVIEW ask carries the production semantics of committing
 * the spec onto the chain's branch, and the production {@code GitBranchService}
 * performs that with real HTTPS calls to {@code api.github.com}. A required PR
 * lane must never reach the network or need a real credential, so
 * {@link CoreE2eConfiguration#coreE2eGitBranchService()} constructs the
 * production service UNCHANGED against this mock's base URL: the whole handoff
 * path (coordinator -> service -> HTTP contract -> base64 content) still runs,
 * only the far end is local.
 *
 * <p>Surface implemented -- exactly the five shapes the service uses, no more:
 * <ul>
 *   <li>{@code GET  /repos/{owner}/{repo}} -> {@code {"default_branch":"main"}}</li>
 *   <li>{@code GET  /repos/{owner}/{repo}/git/ref/heads/{branch}} -> known branch
 *       200 with {@code object.sha}, unknown 404 (the service's "does not exist"
 *       answer, which is what lets a fresh chain create its branch)</li>
 *   <li>{@code POST /repos/{owner}/{repo}/git/refs} -> 201, or 422 when the ref
 *       already exists (GitHub's already-exists status the service handles by
 *       re-reading the branch)</li>
 *   <li>{@code PUT  /repos/{owner}/{repo}/contents/{path}} -> 201, storing the
 *       decoded bytes for that branch</li>
 *   <li>{@code GET  /repos/{owner}/{repo}/contents/{path}?ref={branch}} -> 200
 *       with base64 content, or 404 when the branch has no such file</li>
 * </ul>
 *
 * <p>Served on {@code /__git/**}, a path the operator filter does not guard
 * (harness-only, like the peers' {@code /__peer/**}). A request without a
 * non-blank {@code Authorization: Bearer} header is refused with 401, exactly as
 * the real API refuses an anonymous caller: the path is prove-able to carry the
 * configured credential, without pinning the credential's value (a developer
 * environment may legitimately supply a real {@code GITHUB_TOKEN} while CI
 * supplies the synthetic handoff one). Shas are derived deterministically from
 * the ref/path (sha256 truncated to 40 hex), so an assertion on branch state is
 * reproducible. {@code GET /__git/state} exposes what was recorded.
 */
@RestController
@Profile("core-e2e")
@RequestMapping("/__git")
public class CoreE2eGitHubMock {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String DEFAULT_BRANCH = "main";

    /** branch (owner/repo@branch) -> head sha. */
    private final Map<String, String> branches = new ConcurrentHashMap<>();
    /** owner/repo@branch@path -> decoded file bytes. */
    private final Map<String, String> files = new ConcurrentHashMap<>();

    private final String baseUrl;

    public CoreE2eGitHubMock(org.springframework.core.env.Environment environment) {
        // The service calls back into this same process; the port is the one the
        // harness was booted with (--server.port), never a guessed default.
        this.baseUrl = "http://127.0.0.1:" + environment.getProperty("server.port", "8080") + "/__git";
    }

    /** The API base the harness wires into the production service. */
    public String apiBaseUrl() {
        return baseUrl;
    }

    static String sha(String seed) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(seed.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(40);
            for (int i = 0; i < 20; i++) {
                hex.append(String.format("%02x", digest[i]));
            }
            return hex.toString();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static boolean authorized(HttpServletRequest request) {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        return header != null && header.startsWith("Bearer ") && !header.substring(7).isBlank();
    }

    private static String branchKey(String owner, String repo, String branch) {
        return owner + "/" + repo + "@" + branch;
    }

    @GetMapping("/repos/{owner}/{repo}")
    public ResponseEntity<Object> repo(HttpServletRequest request, @PathVariable String owner,
            @PathVariable String repo) {
        if (!authorized(request)) {
            return unauthorized();
        }
        ObjectNode body = MAPPER.createObjectNode();
        body.put("default_branch", DEFAULT_BRANCH);
        return ResponseEntity.ok(body);
    }

    @GetMapping("/repos/{owner}/{repo}/git/ref/heads/{branch}")
    public ResponseEntity<Object> ref(HttpServletRequest request, @PathVariable String owner,
            @PathVariable String repo, @PathVariable String branch) {
        if (!authorized(request)) {
            return unauthorized();
        }
        String key = branchKey(owner, repo, branch);
        String sha = DEFAULT_BRANCH.equals(branch)
                ? sha(key + ":" + DEFAULT_BRANCH)
                : branches.get(key);
        if (sha == null) {
            return notFound("No commit found for the ref " + branch);
        }
        ObjectNode body = MAPPER.createObjectNode();
        ObjectNode object = body.putObject("object");
        object.put("sha", sha);
        object.put("type", "commit");
        return ResponseEntity.ok(body);
    }

    @PostMapping("/repos/{owner}/{repo}/git/refs")
    public ResponseEntity<Object> createRef(HttpServletRequest request, @PathVariable String owner,
            @PathVariable String repo, @RequestBody String rawBody) {
        if (!authorized(request)) {
            return unauthorized();
        }
        JsonNode body = parse(rawBody);
        String ref = body.path("ref").asText("");
        String sha = body.path("sha").asText("");
        if (ref.isBlank() || sha.isBlank() || !ref.startsWith("refs/heads/")) {
            return ResponseEntity.unprocessableEntity().body(Map.of("message", "Invalid ref payload"));
        }
        String branch = ref.substring("refs/heads/".length());
        String key = branchKey(owner, repo, branch);
        if (branches.putIfAbsent(key, sha(key + ":created")) != null) {
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                    .body(Map.of("message", "Reference already exists"));
        }
        ObjectNode created = MAPPER.createObjectNode();
        created.put("ref", ref);
        created.putObject("object").put("sha", branches.get(key));
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @PutMapping("/repos/{owner}/{repo}/contents/{*path}")
    public ResponseEntity<Object> putFile(HttpServletRequest request, @PathVariable String owner,
            @PathVariable String repo, @PathVariable String path, @RequestBody String rawBody) {
        if (!authorized(request)) {
            return unauthorized();
        }
        JsonNode body = parse(rawBody);
        String branch = body.path("branch").asText("");
        String content = body.path("content").asText(null);
        if (branch.isBlank() || content == null) {
            return ResponseEntity.unprocessableEntity().body(Map.of("message", "Missing branch or content"));
        }
        String decoded = new String(Base64.getMimeDecoder().decode(content), StandardCharsets.UTF_8);
        files.put(branchKey(owner, repo, branch) + "@" + path, decoded);

        ObjectNode response = MAPPER.createObjectNode();
        ObjectNode node = response.putObject("content");
        node.put("path", path);
        node.put("sha", sha(branchKey(owner, repo, branch) + "@" + path + ":" + decoded.length()));
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping("/repos/{owner}/{repo}/contents/{*path}")
    public ResponseEntity<Object> getFile(HttpServletRequest request, @PathVariable String owner,
            @PathVariable String repo, @PathVariable String path,
            @RequestParam(name = "ref", required = false) String ref) {
        if (!authorized(request)) {
            return unauthorized();
        }
        String content = ref == null ? null : files.get(branchKey(owner, repo, ref) + "@" + path);
        if (content == null) {
            return notFound("Not Found");
        }
        ObjectNode body = MAPPER.createObjectNode();
        body.put("encoding", "base64");
        body.put("content", Base64.getMimeEncoder().encodeToString(content.getBytes(StandardCharsets.UTF_8)));
        return ResponseEntity.ok(body);
    }

    /** Evidence route: what this mock recorded, for a spec's assertions and the run log. */
    @GetMapping("/state")
    public ResponseEntity<Object> state(HttpServletRequest request) {
        if (!authorized(request)) {
            return unauthorized();
        }
        Map<String, Object> recorded = new LinkedHashMap<>();
        recorded.put("defaultBranch", DEFAULT_BRANCH);
        recorded.put("branches", Map.copyOf(branches));
        recorded.put("files", Map.copyOf(files));
        return ResponseEntity.ok(recorded);
    }

    /**
     * The tail of the contents path. The mapping is declared with a wildcard
     * because a spec path contains slashes, and the servlet decodes the pattern
     * match back into the request URI.
     */
    private static JsonNode parse(String rawBody) {
        try {
            return MAPPER.readTree(rawBody == null || rawBody.isBlank() ? "{}" : rawBody);
        } catch (Exception e) {
            return MAPPER.createObjectNode();
        }
    }

    private static ResponseEntity<Object> unauthorized() {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("message", "Bad credentials"));
    }

    private static ResponseEntity<Object> notFound(String message) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("message", message));
    }
}
