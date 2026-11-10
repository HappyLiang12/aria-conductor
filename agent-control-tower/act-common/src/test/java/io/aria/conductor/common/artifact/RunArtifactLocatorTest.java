package io.aria.conductor.common.artifact;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RunArtifactLocatorTest {

    @TempDir
    Path temp;

    private RunArtifactLocator locator(Path root) {
        RunArtifactProperties properties = new RunArtifactProperties();
        properties.setResultRoot(root.toString());
        return new RunArtifactLocator(properties);
    }

    @Test
    void specPathIsTheCanonicalLayoutBelowTheRoot() {
        RunArtifactLocator locator = locator(temp.resolve("results"));
        String runId = "123e4567-e89b-12d3-a456-426614174000";

        assertThat(locator.specPath(runId))
                .isEqualTo(locator.resultRoot().resolve(runId).resolve("spec.md").normalize());
        assertThat(locator.resultDir(runId)).isEqualTo(locator.resultRoot().resolve(runId));
    }

    @Test
    void readSpecReturnsContentAndTheResolvedPath() throws Exception {
        RunArtifactLocator locator = locator(temp.resolve("results"));
        String runId = "run-ok";
        Path spec = locator.specPath(runId);
        Files.createDirectories(spec.getParent());
        Files.writeString(spec, "# Spec\n\nfull body");

        Optional<SpecArtifact> artifact = locator.readSpec(runId);

        assertThat(artifact).isPresent();
        assertThat(artifact.get().content()).isEqualTo("# Spec\n\nfull body");
        assertThat(artifact.get().path()).isEqualTo(spec);
    }

    @Test
    void aMissingSpecReportsMissingWithTheAbsolutePath() {
        RunArtifactLocator locator = locator(temp.resolve("results"));

        SpecReadResult result = locator.readSpecResult("run-missing");

        assertThat(result.available()).isFalse();
        assertThat(result.reason()).isEqualTo(SpecReadResult.MISSING);
        assertThat(result.attemptedPath()).isEqualTo(locator.specPath("run-missing"));
        assertThat(result.attemptedPath()).isAbsolute();
    }

    @Test
    void aWhitespaceOnlySpecReportsEmpty() throws Exception {
        RunArtifactLocator locator = locator(temp.resolve("results"));
        String runId = "run-empty";
        Path spec = locator.specPath(runId);
        Files.createDirectories(spec.getParent());
        Files.writeString(spec, "   \n\t\n");

        SpecReadResult result = locator.readSpecResult(runId);

        assertThat(result.reason()).isEqualTo(SpecReadResult.EMPTY);
        assertThat(result.attemptedPath()).isEqualTo(spec);
    }

    @Test
    void anUnreadableSpecReportsUnreadableWithoutContent() throws Exception {
        Assumptions.assumeFalse(System.getProperty("os.name").toLowerCase().contains("win"));
        Assumptions.assumeFalse("root".equals(System.getProperty("user.name")));
        RunArtifactLocator locator = locator(temp.resolve("results"));
        String runId = "run-unreadable";
        Path spec = locator.specPath(runId);
        Files.createDirectories(spec.getParent());
        Files.writeString(spec, "# Secret payload");
        Files.setPosixFilePermissions(spec, PosixFilePermissions.fromString("---------"));

        try {
            SpecReadResult result = locator.readSpecResult(runId);

            assertThat(result.reason()).isEqualTo(SpecReadResult.UNREADABLE);
            assertThat(result.attemptedPath()).isEqualTo(spec);
            assertThat(result.errorDetail()).isNotBlank();
            assertThat(result.errorDetail()).doesNotContain("Secret payload");
        } finally {
            Files.setPosixFilePermissions(spec, PosixFilePermissions.fromString("rw-------"));
        }
    }

    @Test
    void aTraversalRunIdIsRejectedWithoutAReadOutsideTheRoot() {
        RunArtifactLocator locator = locator(temp.resolve("results"));

        assertThatThrownBy(() -> locator.specPath("../escape"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid run id");
        assertThatThrownBy(() -> locator.specPath("a/b"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> locator.specPath(" "))
                .isInstanceOf(IllegalArgumentException.class);

        SpecReadResult result = locator.readSpecResult("../escape");
        assertThat(result.available()).isFalse();
        assertThat(result.reason()).isEqualTo(SpecReadResult.INVALID_RUN_ID);
        assertThat(result.attemptedPath()).isEqualTo(locator.resultRoot());
    }

    @Test
    void anAbsoluteRunIdIsRejected() {
        assertThat(RunArtifactLocator.isValidRunId(temp.toAbsolutePath().toString())).isFalse();
        assertThat(RunArtifactLocator.isValidRunId("..")).isFalse();
        assertThat(RunArtifactLocator.isValidRunId("run-1")).isTrue();
    }
}
