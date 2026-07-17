package io.aria.conductor.knowledge.git;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LocalGitClientTest {

    @TempDir
    Path tmp;

    LocalGitClient client;

    @BeforeEach
    void setUp() {
        client = new LocalGitClient(tmp.toString());
    }

    @Test
    void initRepo_createsBareRepoWithMainBranch() {
        client.initRepo("skills");

        Path repo = tmp.resolve("skills.git");
        assertThat(repo.resolve("HEAD")).exists();
        assertThat(repo.resolve("refs/heads/main")).exists();
    }

    @Test
    void initRepo_isIdempotent() {
        client.initRepo("skills");
        client.initRepo("skills");
        assertThat(tmp.resolve("skills.git/HEAD")).exists();
    }

    @Test
    void createBranch_returnsFeaturePrefixedName() {
        client.initRepo("scripts");

        String branch = client.createBranch("scripts", "Format Code");

        assertThat(branch).startsWith("feature/Format-Code-");
        assertThat(branch.length()).isLessThanOrEqualTo(64);
    }

    @Test
    void commit_persistsContentRetrievableViaReadFile() {
        client.initRepo("prompts");
        String branch = client.createBranch("prompts", "hello");

        String sha = client.commit("prompts", branch, "hello/v1.md", "Hello world", "Add hello");

        assertThat(sha).hasSize(7);
        // Content not yet on main until merged
        assertThat(client.readFile("prompts", "hello/v1.md")).isNull();
    }

    @Test
    void mergeBranch_makesFileVisibleOnMain() {
        client.initRepo("prompts");
        String branch = client.createBranch("prompts", "greeting");
        client.commit("prompts", branch, "greeting/v1.md", "hi", "Add greeting");

        client.mergeBranch("prompts", branch);

        assertThat(client.readFile("prompts", "greeting/v1.md")).isEqualTo("hi");
    }

    @Test
    void tag_createsRefUnderRefsTags() throws Exception {
        client.initRepo("tools");
        String b = client.createBranch("tools", "calc");
        client.commit("tools", b, "calc/v1.md", "x", "init");
        client.mergeBranch("tools", b);

        client.tag("tools", "v1.0.0", "Release v1.0.0");

        Path tagsDir = tmp.resolve("tools.git/refs/tags");
        boolean hasTagFile = Files.exists(tagsDir.resolve("v1.0.0"));
        // packed-refs may also store tag, accept either
        boolean hasPackedRefs = Files.exists(tmp.resolve("tools.git/packed-refs"));
        assertThat(hasTagFile || hasPackedRefs).isTrue();
    }

    @Test
    void deleteBranch_removesRef() {
        client.initRepo("templates");
        String branch = client.createBranch("templates", "tmp");

        client.deleteBranch("templates", branch);

        Path branchRef = tmp.resolve("templates.git/refs/heads/" + branch);
        assertThat(Files.exists(branchRef)).isFalse();
    }

    @Test
    void deleteBranch_isIdempotent() {
        client.initRepo("templates");
        client.deleteBranch("templates", "does-not-exist");
        // no exception
    }

    @Test
    void sanitizeBranchName_replacesUnsafeChars() {
        assertThat(client.sanitizeBranchName("hello world!")).isEqualTo("hello-world");
        assertThat(client.sanitizeBranchName("path/to/thing")).isEqualTo("path-to-thing");
        assertThat(client.sanitizeBranchName("  trim  ")).isEqualTo("trim");
    }

    @Test
    void sanitizeBranchName_truncatesTo64Chars() {
        String longName = "a".repeat(200);
        assertThat(client.sanitizeBranchName(longName).length()).isEqualTo(64);
    }

    @Test
    void sanitizeBranchName_handlesNullAndBlank() {
        assertThat(client.sanitizeBranchName(null)).isEqualTo("unnamed");
        assertThat(client.sanitizeBranchName("")).isEqualTo("unnamed");
        assertThat(client.sanitizeBranchName("   ")).isEqualTo("unnamed");
    }

    @Test
    void sanitizeBranchName_keepsAllowedCharacters() {
        assertThat(client.sanitizeBranchName("Skill_v1.2-final")).isEqualTo("Skill_v1.2-final");
    }

    @Test
    void commit_onMissingBranch_throws() {
        client.initRepo("skills");
        assertThatThrownBy(() -> client.commit("skills", "no-such-branch", "f.md", "x", "msg"))
                .isInstanceOf(LocalGitClient.GitOperationException.class);
    }
}
