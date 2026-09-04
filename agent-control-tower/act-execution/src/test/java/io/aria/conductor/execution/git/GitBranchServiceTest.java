package io.aria.conductor.execution.git;

import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Optional;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
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
}
