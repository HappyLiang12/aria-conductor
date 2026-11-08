package io.aria.conductor.execution.listener;

import com.github.tomakehurst.wiremock.WireMockServer;
import io.aria.conductor.execution.git.GitBranchService;
import io.aria.conductor.execution.listener.ReportStepTreeGuard.ChangeType;
import io.aria.conductor.execution.listener.ReportStepTreeGuard.ChangedFile;
import io.aria.conductor.execution.listener.ReportStepTreeGuard.TreeDiff;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * F19 wiring: {@link GitTreeDiffSupplier} turns the branch HEAD and its commit read
 * into the {@link TreeDiff} the guard judges. The observed bad commit 984df552
 * ("sdd qa report", parent 451c4867) removed 300 files; a diff like that must reach
 * the guard as 300 REMOVED entries, while a rename or a copy must never be reported
 * as a removal.
 */
class GitTreeDiffSupplierTest {

    private static final String REPO_URL = "https://github.com/owner/repo.git";
    private static final String BRANCH = "sdd/chain-1";

    private WireMockServer wireMock;
    private GitTreeDiffSupplier supplier;

    @BeforeEach
    void setUp() {
        wireMock = new WireMockServer(wireMockConfig().dynamicPort());
        wireMock.start();
        supplier = new GitTreeDiffSupplier(new GitBranchService("test-gh-token", wireMock.baseUrl()));
    }

    @AfterEach
    void tearDown() {
        wireMock.stop();
    }

    @Test
    void mapsTheBranchHeadCommitToATreeDiff() {
        stubHead("head-1");
        stubCommit("head-1", """
                {"sha":"head-1","parents":[{"sha":"parent-1"}],
                 "files":[{"filename":"qa_report.md","status":"added"},
                          {"filename":"spec/spec.md","status":"modified"}]}
                """);

        Optional<TreeDiff> diff = supplier.diffFor(REPO_URL, BRANCH);

        assertThat(diff).contains(new TreeDiff(BRANCH, "parent-1", "head-1", java.util.List.of(
                new ChangedFile("qa_report.md", ChangeType.ADDED),
                new ChangedFile("spec/spec.md", ChangeType.MODIFIED))));
    }

    @Test
    void theObserved300FileRemovalCommit_isAViolation() {
        stubHead("984df552");
        stubCommit("984df552", removalCommitJson(300));

        Optional<TreeDiff> diff = supplier.diffFor(REPO_URL, BRANCH);

        assertThat(diff).isPresent();
        assertThat(diff.get().parentSha()).isEqualTo("451c4867");
        Optional<String> violation = new ReportStepTreeGuard().violation(diff.get());
        assertThat(violation).isPresent();
        assertThat(violation.get()).contains("removed 300");
    }

    @Test
    void aRenamedOrCopiedFile_isNotARemoval() {
        stubHead("head-2");
        stubCommit("head-2", """
                {"sha":"head-2","parents":[{"sha":"parent-1"}],
                 "files":[{"filename":"docs/report.md","previous_filename":"report.md","status":"renamed"},
                          {"filename":"docs/copy.md","previous_filename":"spec/spec.md","status":"copied"},
                          {"filename":"scripts/run.sh","status":"changed"},
                          {"filename":"docs/future.md","status":"unmerged"}]}
                """);

        Optional<TreeDiff> diff = supplier.diffFor(REPO_URL, BRANCH);

        assertThat(diff).isPresent();
        assertThat(diff.get().files()).extracting(ChangedFile::type).doesNotContain(ChangeType.REMOVED);
        // An undocumented status degrades to MODIFIED: never guessed into a removal.
        assertThat(diff.get().files()).containsExactly(
                new ChangedFile("docs/report.md", ChangeType.MODIFIED),
                new ChangedFile("docs/copy.md", ChangeType.ADDED),
                new ChangedFile("scripts/run.sh", ChangeType.MODIFIED),
                new ChangedFile("docs/future.md", ChangeType.MODIFIED));
        assertThat(new ReportStepTreeGuard().violation(diff.get())).isEmpty();
    }

    @Test
    void missingBranchHead_returnsEmptyWithoutReadingACommit() {
        wireMock.stubFor(get(urlEqualTo("/repos/owner/repo/git/ref/heads/sdd/chain-1"))
                .willReturn(aResponse().withStatus(404)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"message\":\"Not Found\"}")));

        assertThat(supplier.diffFor(REPO_URL, BRANCH)).isEmpty();
        wireMock.verify(0, getRequestedFor(urlEqualTo("/repos/owner/repo/commits/head-1")));
    }

    /**
     * The guard must never fail a step for an infrastructure error: an unreadable
     * commit leaves the step's previous (unguarded) behaviour intact.
     */
    @Test
    void unreadableCommit_returnsEmpty() {
        stubHead("head-1");
        wireMock.stubFor(get(urlEqualTo("/repos/owner/repo/commits/head-1"))
                .willReturn(aResponse().withStatus(500)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"message\":\"Server Error\"}")));

        assertThat(supplier.diffFor(REPO_URL, BRANCH)).isEmpty();
    }

    @Test
    void unreadableBranchHead_returnsEmpty() {
        wireMock.stubFor(get(urlEqualTo("/repos/owner/repo/git/ref/heads/sdd/chain-1"))
                .willReturn(aResponse().withStatus(401)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"message\":\"Bad credentials\"}")));

        assertThat(supplier.diffFor(REPO_URL, BRANCH)).isEmpty();
    }

    /**
     * No git credential: the branch read refuses before any request. The supplier
     * treats that as "no diff" at debug, not as a failed QA step.
     */
    @Test
    void missingCredential_returnsEmptyWithoutAnyRequest() {
        GitTreeDiffSupplier noCredential = new GitTreeDiffSupplier(
                new GitBranchService(() -> null, wireMock.baseUrl()));

        assertThat(noCredential.diffFor(REPO_URL, BRANCH)).isEmpty();
        wireMock.verify(0, getRequestedFor(urlEqualTo("/repos/owner/repo/git/ref/heads/sdd/chain-1")));
    }

    /** A root commit has no parent, so there is no tree move to judge. */
    @Test
    void rootCommitWithoutParent_returnsEmpty() {
        stubHead("root-1");
        stubCommit("root-1", """
                {"sha":"root-1","parents":[],
                 "files":[{"filename":"README.md","status":"added"}]}
                """);

        assertThat(supplier.diffFor(REPO_URL, BRANCH)).isEmpty();
    }

    @Test
    void missingRepoOrBranch_returnsEmptyWithoutAnyRequest() {
        assertThat(supplier.diffFor(null, BRANCH)).isEmpty();
        assertThat(supplier.diffFor(REPO_URL, " ")).isEmpty();
        wireMock.verify(0, getRequestedFor(urlEqualTo("/repos/owner/repo/git/ref/heads/sdd/chain-1")));
    }

    // ---- helpers ----

    private void stubHead(String sha) {
        wireMock.stubFor(get(urlEqualTo("/repos/owner/repo/git/ref/heads/sdd/chain-1"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"ref\":\"refs/heads/sdd/chain-1\",\"object\":{\"sha\":\"" + sha + "\"}}")));
    }

    private void stubCommit(String sha, String body) {
        wireMock.stubFor(get(urlEqualTo("/repos/owner/repo/commits/" + sha))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(body)));
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
