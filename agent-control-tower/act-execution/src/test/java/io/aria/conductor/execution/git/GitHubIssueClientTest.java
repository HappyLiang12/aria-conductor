package io.aria.conductor.execution.git;

import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * WireMock-backed contract tests for {@link GitHubIssueClient} (R9-F2 SDD spec-task
 * issue grounding): numeric / {@code #number} / URL resolution, slug resolution via
 * the issue search API, 404/no-match -> {@link IssueReferenceException} fail-fast, and
 * the {@code GH_TOKEN} requirement.
 */
class GitHubIssueClientTest {

    private static final String ISSUE_BODY = "{"
            + "\"number\":38,\"title\":\"qoder regression\",\"body\":\"Search crashes.\","
            + "\"labels\":[{\"name\":\"bug\"},{\"name\":\"regression\"}]}";

    private WireMockServer wireMock;
    private GitHubIssueClient client;

    @BeforeEach
    void setUp() {
        wireMock = new WireMockServer(wireMockConfig().dynamicPort());
        wireMock.start();
        client = new GitHubIssueClient("test-gh-token", wireMock.baseUrl());
    }

    @AfterEach
    void tearDown() {
        wireMock.stop();
    }

    @Test
    void resolveIssue_numericKey_fetchesAndParsesIssue() {
        wireMock.stubFor(get(urlPathEqualTo("/repos/acme/repo/issues/38"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(ISSUE_BODY)));

        GitHubIssue issue = client.resolveIssue("acme/repo", "38");

        assertThat(issue.number()).isEqualTo(38);
        assertThat(issue.ownerRepo()).isEqualTo("acme/repo");
        assertThat(issue.title()).isEqualTo("qoder regression");
        assertThat(issue.body()).isEqualTo("Search crashes.");
        assertThat(issue.labels()).containsExactly("bug", "regression");
        assertThat(issue.bodySha256()).matches("[0-9a-f]{64}");
        wireMock.verify(getRequestedFor(urlPathEqualTo("/repos/acme/repo/issues/38"))
                .withHeader("Authorization", equalTo("Bearer test-gh-token")));
    }

    @Test
    void resolveIssue_hashNumericAndUrlKeys_fetchTheSameIssue() {
        wireMock.stubFor(get(urlPathEqualTo("/repos/acme/repo/issues/38"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(ISSUE_BODY)));

        assertThat(client.resolveIssue("acme/repo", "#38").number()).isEqualTo(38);
        assertThat(client.resolveIssue("acme/repo",
                "https://github.com/acme/repo/issues/38").number()).isEqualTo(38);
        wireMock.verify(2, getRequestedFor(urlPathEqualTo("/repos/acme/repo/issues/38")));
    }

    @Test
    void resolveIssue_urlRepoMismatch_isRejected() {
        assertThatThrownBy(() -> client.resolveIssue("acme/repo",
                "https://github.com/other/repo/issues/38"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("other/repo");
    }

    @Test
    void resolveIssue_unresolvableSlug_failsFastNamingKeyAndRepo() {
        // No issue anywhere in acme/repo matches "qoder-regression" -> must NOT dispatch.
        wireMock.stubFor(get(urlPathEqualTo("/search/issues"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"total_count\":0,\"items\":[]}")));

        assertThatThrownBy(() -> client.resolveIssue("acme/repo", "#qoder-regression"))
                .isInstanceOf(IssueReferenceException.class)
                .hasMessageContaining("#qoder-regression")
                .hasMessageContaining("acme/repo")
                .satisfies(e -> assertThat(((IssueReferenceException) e).getHttpStatus()).isEqualTo(422));
        wireMock.verify(getRequestedFor(urlPathEqualTo("/search/issues"))
                .withQueryParam("q", equalTo("repo:acme/repo type:issue qoder-regression")));
    }

    @Test
    void resolveIssue_slugMatch_resolvesThroughSearchThenFetches() {
        wireMock.stubFor(get(urlPathEqualTo("/search/issues"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"total_count\":1,\"items\":[{\"number\":38,"
                                + "\"title\":\"qoder regression\",\"body\":\"\"}]}")));
        wireMock.stubFor(get(urlPathEqualTo("/repos/acme/repo/issues/38"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(ISSUE_BODY)));

        GitHubIssue issue = client.resolveIssue("acme/repo", "qoder-regression");

        assertThat(issue.number()).isEqualTo(38);
        wireMock.verify(getRequestedFor(urlPathEqualTo("/repos/acme/repo/issues/38")));
    }

    @Test
    void resolveIssue_issueNotFound_throws() {
        wireMock.stubFor(get(urlPathEqualTo("/repos/acme/repo/issues/999"))
                .willReturn(aResponse().withStatus(404)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"message\":\"Not Found\"}")));

        assertThatThrownBy(() -> client.resolveIssue("acme/repo", "999"))
                .isInstanceOf(IssueReferenceException.class)
                .hasMessageContaining("999")
                .hasMessageContaining("acme/repo");
    }

    @Test
    void resolveIssue_disabledClient_failsFast() {
        GitHubIssueClient disabled = new GitHubIssueClient("  ", wireMock.baseUrl());

        assertThat(disabled.isEnabled()).isFalse();
        assertThatThrownBy(() -> disabled.resolveIssue("acme/repo", "#1"))
                .isInstanceOf(IssueReferenceException.class)
                .hasMessageContaining("GH_TOKEN");
    }

    @Test
    void parseRepository_normalizesAndRejectsPlaceholders() {
        assertThat(GitHubIssueClient.parseRepository("acme/repo")).isEqualTo("acme/repo");
        assertThat(GitHubIssueClient.parseRepository("https://github.com/acme/repo"))
                .isEqualTo("acme/repo");
        assertThat(GitHubIssueClient.parseRepository("https://github.com/acme/repo.git"))
                .isEqualTo("acme/repo");
        assertThatThrownBy(() -> GitHubIssueClient.parseRepository("  "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("issueRepo");
        assertThatThrownBy(() -> GitHubIssueClient.parseRepository("{issueRepo}"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("placeholder");
        assertThatThrownBy(() -> GitHubIssueClient.parseRepository("not-a-repo"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("owner/repo");
    }

    @Test
    void fetchIssue_emptyBodyAndNoLabels_isRepresented() {
        wireMock.stubFor(get(urlPathEqualTo("/repos/acme/repo/issues/7"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"number\":7,\"title\":\"No body\",\"body\":null,\"labels\":[]}")));

        GitHubIssue issue = client.fetchIssue("acme/repo", 7, "#7");

        assertThat(issue.body()).isEmpty();
        assertThat(issue.labels()).isEmpty();
    }
}
