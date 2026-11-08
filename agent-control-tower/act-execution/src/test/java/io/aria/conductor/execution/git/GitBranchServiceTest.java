package io.aria.conductor.execution.git;

import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Optional;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.put;
import static com.github.tomakehurst.wiremock.client.WireMock.putRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * WireMock-backed contract tests for {@link GitBranchService} covering the
 * GitHub REST API shapes used by the SDD branch handoff:
 * create-ref from the default branch, base64 file upload, file read-back,
 * branch HEAD sha resolution, and 401 -> {@link GitBranchException} mapping.
 */
class GitBranchServiceTest {

    private static final String REPO_URL = "https://github.com/owner/repo.git";

    private WireMockServer wireMock;
    private GitBranchService service;

    @BeforeEach
    void setUp() {
        wireMock = new WireMockServer(wireMockConfig().dynamicPort());
        wireMock.start();
        service = new GitBranchService("test-gh-token", wireMock.baseUrl());
    }

    @AfterEach
    void tearDown() {
        wireMock.stop();
    }

    @Test
    void createBranch_createsRefFromDefaultBranch() {
        wireMock.stubFor(get(urlEqualTo("/repos/owner/repo"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"default_branch\":\"main\"}")));
        wireMock.stubFor(get(urlEqualTo("/repos/owner/repo/git/ref/heads/main"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"ref\":\"refs/heads/main\",\"object\":{\"sha\":\"base-sha-123\"}}")));
        wireMock.stubFor(post(urlEqualTo("/repos/owner/repo/git/refs"))
                .willReturn(aResponse().withStatus(201)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"ref\":\"refs/heads/sdd/42\",\"object\":{\"sha\":\"base-sha-123\"}}")));

        service.createBranch(REPO_URL, "sdd/42");

        wireMock.verify(getRequestedFor(urlEqualTo("/repos/owner/repo")));
        wireMock.verify(getRequestedFor(urlEqualTo("/repos/owner/repo/git/ref/heads/main")));
        wireMock.verify(postRequestedFor(urlEqualTo("/repos/owner/repo/git/refs"))
                .withRequestBody(equalToJson("{\"ref\":\"refs/heads/sdd/42\",\"sha\":\"base-sha-123\"}")));
    }

    @Test
    void putFile_base64EncodesContent() {
        wireMock.stubFor(put(urlEqualTo("/repos/owner/repo/contents/spec/spec.md"))
                .willReturn(aResponse().withStatus(201)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"content\":{}}")));

        service.putFile(REPO_URL, "sdd/42", "spec/spec.md", "hello spec", "sdd: approve spec");

        String expectedB64 = Base64.getEncoder()
                .encodeToString("hello spec".getBytes(StandardCharsets.UTF_8));
        wireMock.verify(putRequestedFor(urlEqualTo("/repos/owner/repo/contents/spec/spec.md"))
                .withRequestBody(equalToJson(
                        "{\"message\":\"sdd: approve spec\",\"content\":\"" + expectedB64
                                + "\",\"branch\":\"sdd/42\"}")));
    }

    @Test
    void getFile_decodesBase64Content() {
        String content = "spec body\nline2";
        String b64 = Base64.getEncoder().encodeToString(content.getBytes(StandardCharsets.UTF_8));
        wireMock.stubFor(get(urlEqualTo("/repos/owner/repo/contents/spec/spec.md?ref=sdd/42"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"content\":\"" + b64 + "\"}")));

        Optional<String> result = service.getFile(REPO_URL, "sdd/42", "spec/spec.md");

        assertThat(result).contains(content);
    }

    @Test
    void branchHeadSha_returnsHeadSha() {
        wireMock.stubFor(get(urlEqualTo("/repos/owner/repo/git/ref/heads/sdd/42"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"ref\":\"refs/heads/sdd/42\",\"object\":{\"sha\":\"head-sha-999\"}}")));

        Optional<String> sha = service.branchHeadSha(REPO_URL, "sdd/42");

        assertThat(sha).contains("head-sha-999");
    }

    @Test
    void branchHeadSha_missingBranch_returnsEmpty() {
        wireMock.stubFor(get(urlEqualTo("/repos/owner/repo/git/ref/heads/sdd/42"))
                .willReturn(aResponse().withStatus(404)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"message\":\"Not Found\"}")));

        Optional<String> sha = service.branchHeadSha(REPO_URL, "sdd/42");

        assertThat(sha).isEmpty();
    }

    @Test
    void createBranch_refAlreadyExists_isIdempotentAndReusesExistingBranch() {
        // Live incident (chain bb543e7d): on SDD re-approval the flow calls create-ref
        // for sdd/{chainId} which already exists -> GitHub answers 422 "Reference
        // already exists" and the decide endpoint 500s. The existing branch must be
        // verified (GET) and reused — never deleted or force-updated.
        wireMock.stubFor(get(urlEqualTo("/repos/owner/repo"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"default_branch\":\"main\"}")));
        wireMock.stubFor(get(urlEqualTo("/repos/owner/repo/git/ref/heads/main"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"ref\":\"refs/heads/main\",\"object\":{\"sha\":\"base-sha-123\"}}")));
        wireMock.stubFor(post(urlEqualTo("/repos/owner/repo/git/refs"))
                .willReturn(aResponse().withStatus(422)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"message\":\"Reference already exists\"}")));
        wireMock.stubFor(get(urlEqualTo("/repos/owner/repo/git/ref/heads/sdd/42"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"ref\":\"refs/heads/sdd/42\",\"object\":{\"sha\":\"existing-head-sha\"}}")));

        assertThatCode(() -> service.createBranch(REPO_URL, "sdd/42")).doesNotThrowAnyException();

        // the existing ref was verified before treating the 422 as benign
        wireMock.verify(getRequestedFor(urlEqualTo("/repos/owner/repo/git/ref/heads/sdd/42")));
    }

    @Test
    void createBranch_unprocessableWhenRefGenuinelyAbsent_stillThrows() {
        // A 422 that is NOT an already-exists race (verified via the ref GET returning
        // 404) is a genuine error and must still surface as GitBranchException.
        wireMock.stubFor(get(urlEqualTo("/repos/owner/repo"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"default_branch\":\"main\"}")));
        wireMock.stubFor(get(urlEqualTo("/repos/owner/repo/git/ref/heads/main"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"ref\":\"refs/heads/main\",\"object\":{\"sha\":\"base-sha-123\"}}")));
        wireMock.stubFor(post(urlEqualTo("/repos/owner/repo/git/refs"))
                .willReturn(aResponse().withStatus(422)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"message\":\"Malformed ref name\"}")));
        wireMock.stubFor(get(urlEqualTo("/repos/owner/repo/git/ref/heads/sdd/42"))
                .willReturn(aResponse().withStatus(404)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"message\":\"Not Found\"}")));

        assertThatThrownBy(() -> service.createBranch(REPO_URL, "sdd/42"))
                .isInstanceOf(GitBranchException.class)
                .satisfies(e -> assertThat(((GitBranchException) e).getStatus()).isEqualTo(422));
    }

    @Test
    void putFile_maps401toDomainException() {
        wireMock.stubFor(put(urlEqualTo("/repos/owner/repo/contents/spec/spec.md"))
                .willReturn(aResponse().withStatus(401)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"message\":\"Bad credentials\"}")));

        assertThatThrownBy(() -> service.putFile(REPO_URL, "sdd/42", "spec/spec.md", "content", "msg"))
                .isInstanceOf(GitBranchException.class)
                .satisfies(e -> {
                    GitBranchException ex = (GitBranchException) e;
                    assertThat(ex.getStatus()).isEqualTo(401);
                    assertThat(ex.getMessage()).contains("401").contains("Bad credentials");
                });
    }

    @Test
    void isAvailable_falseWhenTokenIsBlank() {
        assertThat(new GitBranchService("").isAvailable()).isFalse();
        assertThat(new GitBranchService((String) null).isAvailable()).isFalse();
        assertThat(new GitBranchService("   ").isAvailable()).isFalse();
    }

    @Test
    void isAvailable_trueWhenTokenIsPresent() {
        assertThat(new GitBranchService("ghp_dummy_token").isAvailable()).isTrue();
    }

    // ---- F19: commit read for the report-step tree guard ----

    /**
     * F19: the observed QA commit 984df552 (parent 451c4867) removed 300 files. The
     * commit read must expose the parent, the head and every file status so the tree
     * guard can judge what the step pushed.
     */
    @Test
    void commitFileChanges_mapsThe300FileRemovalCommit() {
        wireMock.stubFor(get(urlEqualTo("/repos/owner/repo/commits/984df552"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(removalCommitJson(300))));

        Optional<GitBranchService.CommitFileChanges> commit =
                service.commitFileChanges(REPO_URL, "984df552");

        assertThat(commit).isPresent();
        assertThat(commit.get().sha()).isEqualTo("984df552");
        assertThat(commit.get().parentShas()).containsExactly("451c4867");
        assertThat(commit.get().files()).hasSize(301);
        assertThat(commit.get().files())
                .filteredOn(file -> file.status() == GitBranchService.CommitFile.Status.REMOVED)
                .hasSize(300)
                .allSatisfy(file -> assertThat(file.path()).endsWith(".java"));
        assertThat(commit.get().files())
                .filteredOn(file -> file.path().equals("qa_report.md"))
                .singleElement()
                .satisfies(file -> assertThat(file.status())
                        .isEqualTo(GitBranchService.CommitFile.Status.ADDED));
    }

    @Test
    void commitFileChanges_mapsACleanReportCommit() {
        wireMock.stubFor(get(urlEqualTo("/repos/owner/repo/commits/abc1234"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"sha":"abc1234",
                                 "parents":[{"sha":"parent-1"}],
                                 "files":[{"filename":"qa_report.md","status":"added"},
                                          {"filename":"spec/spec.md","status":"modified"}]}
                                """)));

        Optional<GitBranchService.CommitFileChanges> commit =
                service.commitFileChanges(REPO_URL, "abc1234");

        assertThat(commit).isPresent();
        assertThat(commit.get().parentShas()).containsExactly("parent-1");
        assertThat(commit.get().files()).containsExactly(
                new GitBranchService.CommitFile("qa_report.md",
                        GitBranchService.CommitFile.Status.ADDED),
                new GitBranchService.CommitFile("spec/spec.md",
                        GitBranchService.CommitFile.Status.MODIFIED));
    }

    /**
     * GitHub reports a rename as one {@code renamed} entry carrying the new
     * {@code filename} (plus {@code previous_filename}), and a mode-only change as
     * {@code changed}; none of them is a removal and the raw status must survive the
     * read so the guard can map it.
     */
    @Test
    void commitFileChanges_keepsRenameCopyAndChangedStatuses() {
        wireMock.stubFor(get(urlEqualTo("/repos/owner/repo/commits/rename987"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"sha":"rename987",
                                 "parents":[{"sha":"parent-1"}],
                                 "files":[
                                   {"filename":"docs/report.md","previous_filename":"report.md","status":"renamed"},
                                   {"filename":"docs/copy.md","previous_filename":"spec/spec.md","status":"copied"},
                                   {"filename":"scripts/run.sh","status":"changed"}]}
                                """)));

        Optional<GitBranchService.CommitFileChanges> commit =
                service.commitFileChanges(REPO_URL, "rename987");

        assertThat(commit).isPresent();
        assertThat(commit.get().files()).extracting(GitBranchService.CommitFile::status)
                .containsExactly(GitBranchService.CommitFile.Status.RENAMED,
                        GitBranchService.CommitFile.Status.COPIED,
                        GitBranchService.CommitFile.Status.CHANGED);
    }

    @Test
    void commitFileChanges_missingCommit_returnsEmpty() {
        wireMock.stubFor(get(urlEqualTo("/repos/owner/repo/commits/gone"))
                .willReturn(aResponse().withStatus(404)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"message\":\"Not Found\"}")));

        assertThat(service.commitFileChanges(REPO_URL, "gone")).isEmpty();
    }

    /**
     * F19 wiring: the guard must never fail a step for an infrastructure error, so a
     * non-2xx commit read (or a blank sha) is empty, not an exception.
     */
    @Test
    void commitFileChanges_apiFailure_returnsEmptyWithoutThrowing() {
        wireMock.stubFor(get(urlEqualTo("/repos/owner/repo/commits/broken"))
                .willReturn(aResponse().withStatus(500)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"message\":\"Server Error\"}")));

        assertThatCode(() -> assertThat(service.commitFileChanges(REPO_URL, "broken")).isEmpty())
                .doesNotThrowAnyException();
        assertThat(service.commitFileChanges(REPO_URL, "  ")).isEmpty();
    }

    @Test
    void commitFileChanges_withoutACredential_sendsNothingAndReturnsEmpty() {
        GitBranchService none = new GitBranchService(() -> null, wireMock.baseUrl());

        assertThat(none.commitFileChanges(REPO_URL, "abc1234")).isEmpty();
        wireMock.verify(0, getRequestedFor(urlEqualTo("/repos/owner/repo/commits/abc1234")));
    }

    /**
     * F8: the credential is resolved per operation, so a credential stored after
     * the backend started is used by the next GitHub call. Resolving it once when
     * the bean was created made every stored credential require a restart.
     */
    @Test
    void aCredentialStoredAfterStartupIsUsedByTheNextOperationWithoutRestart() {
        java.util.concurrent.atomic.AtomicReference<String> stored = new java.util.concurrent.atomic.AtomicReference<>();
        GitBranchService live = new GitBranchService(stored::get, wireMock.baseUrl());

        assertThat(live.isAvailable())
                .as("positive control: nothing is stored yet, so the service reports unavailable")
                .isFalse();
        wireMock.stubFor(get(urlEqualTo("/repos/owner/repo/git/ref/heads/sdd/9"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"object\":{\"sha\":\"abc123\"}}")));

        stored.set("ghp_stored_at_runtime");

        assertThat(live.isAvailable()).as("the store is visible without rebuilding the service").isTrue();
        assertThat(live.branchHeadSha(REPO_URL, "sdd/9")).contains("abc123");
        wireMock.verify(getRequestedFor(urlEqualTo("/repos/owner/repo/git/ref/heads/sdd/9"))
                .withHeader("Authorization", equalTo("Bearer ghp_stored_at_runtime")));
    }

    /**
     * F8: a credential row that cannot be decrypted is an operator problem, not a
     * crash. The service reports unavailable and every operation refuses with the
     * documented guidance instead of propagating an arbitrary runtime failure.
     */
    @Test
    void anUnresolvableCredentialDisablesTheServiceWithoutCrashing() {
        GitBranchService degraded = new GitBranchService(() -> {
            throw new IllegalStateException("Credential decryption failed");
        }, wireMock.baseUrl());

        assertThat(degraded.isAvailable()).isFalse();
        assertThatThrownBy(() -> degraded.createBranch(REPO_URL, "b"))
                .isInstanceOf(GitBranchException.class)
                .hasMessageContaining("GITHUB_TOKEN");
    }

    /** No credential means no request: the operation refuses with guidance and sends nothing. */
    @Test
    void anUnresolvedCredentialRefusesTheOperationBeforeAnyRequestIsSent() {
        GitBranchService none = new GitBranchService(() -> null, wireMock.baseUrl());
        wireMock.stubFor(get(urlEqualTo("/repos/owner/repo/git/ref/heads/b"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"object\":{\"sha\":\"abc123\"}}")));

        assertThatThrownBy(() -> none.branchHeadSha(REPO_URL, "b"))
                .isInstanceOf(GitBranchException.class)
                .hasMessageContaining("GITHUB_TOKEN");
        wireMock.verify(0, getRequestedFor(urlEqualTo("/repos/owner/repo/git/ref/heads/b")));
    }

    /** The shape of the observed QA commit 984df552: N removed sources plus the report. */
    private static String removalCommitJson(int removals) {
        StringBuilder body = new StringBuilder("{\"sha\":\"984df552\",\"parents\":[{\"sha\":\"451c4867\"}],");
        body.append("\"files\":[{\"filename\":\"qa_report.md\",\"status\":\"added\"}");
        for (int i = 0; i < removals; i++) {
            body.append(",{\"filename\":\"agent-control-tower/src/main/File").append(i)
                    .append(".java\",\"status\":\"removed\"}");
        }
        body.append("]}");
        return body.toString();
    }
}
