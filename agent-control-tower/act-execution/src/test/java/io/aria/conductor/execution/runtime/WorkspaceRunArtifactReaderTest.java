package io.aria.conductor.execution.runtime;

import io.aria.conductor.common.port.RunArtifactReaderPort.RunArtifactText;
import io.aria.conductor.common.runtime.ExecutionMode;
import io.aria.conductor.common.runtime.WorkspaceKind;
import io.aria.conductor.execution.repository.RunWorkspaceLeaseRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.FileVisitResult;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.mock;

/**
 * #112: the artifact read side is the capture side's own root, so it reads what the writer
 * wrote -- and never reaches outside the run's directory through a link it did not create.
 */
class WorkspaceRunArtifactReaderTest {

    private static final int SPEC_BOUND = 50 * 1024;

    @TempDir
    Path temp;

    private Path resultRoot;
    private Path runtimeRoot;

    @BeforeEach
    void setUp() throws IOException {
        resultRoot = Files.createDirectories(temp.resolve("results"));
        runtimeRoot = Files.createDirectories(temp.resolve("runtime-root"));
    }

    // ------------------------------------------------------------ read contract

    @Test
    void readsTheArtifactFromTheRunDirectoryAndNamesItsPath() throws IOException {
        UUID runId = UUID.randomUUID();
        writeArtifact(resultRoot, runId, "spec.md", "# Spec\n\nbody");
        WorkspaceRunArtifactReader reader = new WorkspaceRunArtifactReader(resultRoot);

        RunArtifactText text = reader.read(runId, "spec.md", SPEC_BOUND).orElseThrow();

        assertThat(text.path()).isEqualTo(reader.resolve(runId, "spec.md"));
        assertThat(text.content()).isEqualTo("# Spec\n\nbody");
    }

    @Test
    void stopsAtTheRequestedCharacterBound() throws IOException {
        UUID runId = UUID.randomUUID();
        writeArtifact(resultRoot, runId, "spec.md", "x".repeat(1000));
        WorkspaceRunArtifactReader reader = new WorkspaceRunArtifactReader(resultRoot);

        assertThat(reader.read(runId, "spec.md", 64).orElseThrow().content()).hasSize(64);
    }

    @Test
    void reportsAnAbsentArtifactAsEmptyButStillResolvesThePathForDiagnostics() {
        UUID runId = UUID.randomUUID();
        WorkspaceRunArtifactReader reader = new WorkspaceRunArtifactReader(resultRoot);

        assertThat(reader.read(runId, "spec.md", SPEC_BOUND)).isEmpty();
        assertThat(reader.resolve(runId, "spec.md"))
                .isEqualTo(resultRoot.toAbsolutePath().normalize().resolve(runId.toString()).resolve("spec.md"));
    }

    @Test
    void reportsADirectoryInPlaceOfTheArtifactAsEmpty() throws IOException {
        UUID runId = UUID.randomUUID();
        Files.createDirectories(resultRoot.resolve(runId.toString()).resolve("spec.md"));

        assertThat(new WorkspaceRunArtifactReader(resultRoot).read(runId, "spec.md", SPEC_BOUND)).isEmpty();
    }

    @Test
    void reportsAZeroLengthArtifactAsPresentAndBlank() throws IOException {
        UUID runId = UUID.randomUUID();
        writeArtifact(resultRoot, runId, "spec.md", "");

        RunArtifactText text = new WorkspaceRunArtifactReader(resultRoot)
                .read(runId, "spec.md", SPEC_BOUND).orElseThrow();

        // The caller owns the "blank means degrade to the run output" decision; the reader only reports.
        assertThat(text.content()).isEmpty();
    }

    @Test
    void reportsAMissingRunIdAsEmptyAndResolvesTheArtifactRoot() {
        WorkspaceRunArtifactReader reader = new WorkspaceRunArtifactReader(resultRoot);

        assertThat(reader.read(null, "spec.md", SPEC_BOUND)).isEmpty();
        assertThat(reader.resolve(null, "spec.md"))
                .isEqualTo(resultRoot.toAbsolutePath().normalize().resolve("spec.md"));
    }

    @Test
    void refusesToAddressAnythingButOnePlainFileBelowTheRunDirectory() {
        WorkspaceRunArtifactReader reader = new WorkspaceRunArtifactReader(resultRoot);

        for (String escaping : new String[] {"../other-run/spec.md", "nested/spec.md",
                "..\\other-run\\spec.md", "/absolute/spec.md", "", "  "}) {
            assertThatThrownBy(() -> reader.resolve(UUID.randomUUID(), escaping))
                    .as("artifact name %s", escaping)
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    // -------------------------------------------------------------- link safety

    @Test
    void neverFollowsAFileLinkPlantedInPlaceOfTheArtifact() throws IOException {
        UUID runId = UUID.randomUUID();
        Path outside = Files.createDirectories(temp.resolve("outside"));
        writeArtifact(outside, runId, "secret.md", "content outside the artifact root");
        Path runDir = Files.createDirectories(resultRoot.resolve(runId.toString()));
        Path alias = runDir.resolve("spec.md");
        assumeTrue(createFileAlias(alias, outside.resolve(runId.toString()).resolve("secret.md")),
                "platform forbids creating file links");
        try {
            assertThat(new WorkspaceRunArtifactReader(resultRoot).read(runId, "spec.md", SPEC_BOUND)).isEmpty();
        } finally {
            removeAlias(alias);
        }
    }

    @Test
    void neverFollowsALinkedRunDirectoryOutOfTheArtifactRoot() throws IOException {
        UUID runId = UUID.randomUUID();
        Path elsewhere = Files.createDirectories(temp.resolve("elsewhere"));
        writeArtifact(elsewhere, runId, "spec.md", "written outside the result root");
        Path alias = resultRoot.resolve(runId.toString());
        assumeTrue(createDirectoryAlias(alias, elsewhere), "platform forbids creating directory links");
        try {
            assertThat(new WorkspaceRunArtifactReader(resultRoot).read(runId, "spec.md", SPEC_BOUND)).isEmpty();
        } finally {
            removeAlias(alias);
        }
    }

    /**
     * AC6: a root the operator spelled through a link alias resolves to the canonical
     * directory, so the reader reads the tree the writer wrote instead of a second copy.
     */
    @Test
    void readsThroughADirectoryAliasOfTheResultRootItself() throws IOException {
        UUID runId = UUID.randomUUID();
        Path canonical = Files.createDirectories(temp.resolve("canonical-results"));
        writeArtifact(canonical, runId, "spec.md", "# Spec\n\nwritten through the alias");
        Path alias = temp.resolve("alias-results");
        assumeTrue(createDirectoryAlias(alias, canonical), "platform forbids creating directory links");
        try {
            WorkspaceRunArtifactReader reader = new WorkspaceRunArtifactReader(alias);

            assertThat(reader.resolve(runId, "spec.md")).startsWith(canonical.toRealPath());
            assertThat(reader.read(runId, "spec.md", SPEC_BOUND).orElseThrow().content())
                    .isEqualTo("# Spec\n\nwritten through the alias");
        } finally {
            removeAlias(alias);
        }
    }

    // -------------------------------------------------- parity with the capture side

    /**
     * The parity this issue exists to create: one file, exported by the real
     * {@code capture}, read back through the port built from the service's own root.
     */
    @Test
    void readsWhatRunWorkspaceServiceCaptureActuallyWrote() throws IOException {
        UUID runId = UUID.randomUUID();
        Path workspace = Files.createDirectories(temp.resolve("ba-workspace"));
        Files.writeString(workspace.resolve("spec.md"), "# Spec\n\nthe full artifact");

        RunWorkspaceService service = new RunWorkspaceService(mock(RunWorkspaceLeaseRepository.class),
                runtimeRoot, resultRoot);
        WorkspaceLease lease = new WorkspaceLease(UUID.randomUUID(), runId, WorkspaceKind.SCRATCH,
                workspace, workspace, runtimeRoot.toString(), null);
        WorkspaceRunArtifactReader reader = new WorkspaceRunArtifactReader(service.resultRoot());

        service.capture(lease, new ExportCopyingBackend(workspace), handle(runId), new StopProof(runId, true));

        assertThat(reader.read(runId, "spec.md", SPEC_BOUND).orElseThrow().content())
                .isEqualTo("# Spec\n\nthe full artifact");
        assertThat(reader.resolve(runId, "spec.md").getParent())
                .isEqualTo(service.resultRoot().resolve(runId.toString()));
    }

    @Test
    void requiresAnExistingDirectorySoItCanOnlyBeBuiltFromAProvisionedRoot() {
        assertThatThrownBy(() -> new WorkspaceRunArtifactReader(temp.resolve("never-created")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Result root is not a canonical directory");
        assertThatThrownBy(() -> new WorkspaceRunArtifactReader(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("Result root is required");
    }

    // --------------------------------------------------- shared root resolution (F21/AC6)

    /**
     * The read side inherits the write side's refusal: a token that expands to nothing, and
     * a blank value, are refused by the one resolver naming the property, so a
     * {@code WorkspaceRunArtifactReader} is never built over a literal {@code %NAME%}
     * directory the capture side refused to create either.
     */
    @Test
    void anUnexpandedTokenOrBlankRootIsRefusedBeforeAnyReaderIsBuilt() {
        String property = "aria.workspaces.result-root";

        assertThatThrownBy(() -> WorkspacePaths.configuredRoot("%AriaNoSuchVariable112%\\results", property))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(property)
                .hasMessageContaining("AriaNoSuchVariable112");
        assertThatThrownBy(() -> WorkspacePaths.configuredRoot("   ", property))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(property);
    }

    // ------------------------------------------------------------------- helpers

    private static void writeArtifact(Path root, UUID runId, String name, String content) throws IOException {
        Path dir = Files.createDirectories(root.resolve(runId.toString()));
        Files.writeString(dir.resolve(name), content, StandardCharsets.UTF_8);
    }

    private static RuntimeHandle handle(UUID runId) {
        return new RuntimeHandle(runId, ExecutionMode.HOST, "env-" + runId, "owned:" + runId,
                URI.create("http://127.0.0.1:9400/" + runId));
    }

    /** True when the platform let the link be created; false means the caller skips, never passes. */
    private static boolean createDirectoryAlias(Path link, Path target) throws IOException {
        if (isWindows()) {
            return run("cmd", "/c", "mklink", "/J", link.toString(), target.toString()) == 0;
        }
        return quietLink(link, target, Files::createSymbolicLink);
    }

    /** True when the platform let the link be created; a file link needs privileges on Windows. */
    private static boolean createFileAlias(Path link, Path target) throws IOException {
        if (isWindows()) {
            return run("cmd", "/c", "mklink", link.toString(), target.toString()) == 0;
        }
        return quietLink(link, target, Files::createSymbolicLink);
    }

    private static boolean quietLink(Path link, Path target, LinkFactory factory) {
        try {
            factory.create(link, target);
            return true;
        } catch (IOException | UnsupportedOperationException e) {
            return false;
        }
    }

    private interface LinkFactory {
        void create(Path link, Path target) throws IOException;
    }

    private static boolean isWindows() {
        return System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win");
    }

    /**
     * Removes a link created by this test as the entry it is. Left in place, a junction under
     * a {@code @TempDir} is walked as if it were the target's own tree when JUnit clears the
     * directory, which fails the cleanup of an otherwise passing test.
     */
    private static void removeAlias(Path alias) throws IOException {
        if (Files.exists(alias, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            Files.delete(alias);
        }
    }

    private static int run(String... command) throws IOException {
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        process.getInputStream().readAllBytes();
        try {
            return process.waitFor(20, TimeUnit.SECONDS) ? process.exitValue() : 1;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return 1;
        }
    }

    /** Test double for the export contract: the workspace root's contents land in the destination. */
    private record ExportCopyingBackend(Path source) implements ExecutionBackend {

        @Override
        public ExecutionMode mode() {
            return ExecutionMode.HOST;
        }

        @Override
        public PreparedEnvironment prepare(ExecutionSpec spec, WorkspaceLease workspace) {
            throw new UnsupportedOperationException();
        }

        @Override
        public RuntimeHandle launch(PreparedEnvironment environment, LaunchProfile profile) {
            throw new UnsupportedOperationException();
        }

        @Override
        public java.util.concurrent.CompletionStage<ControlAck> pauseWriters(RuntimeHandle handle,
                java.time.Instant deadline) {
            throw new UnsupportedOperationException();
        }

        @Override
        public java.util.concurrent.CompletionStage<ControlAck> resumeWriters(RuntimeHandle handle,
                java.time.Instant deadline) {
            throw new UnsupportedOperationException();
        }

        @Override
        public StopProof stopWriters(RuntimeHandle handle, java.time.Instant deadline) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void exportWorkspace(RuntimeHandle handle, Path destination, StopProof proof) {
            try {
                Files.walkFileTree(source, new SimpleFileVisitor<>() {
                    @Override
                    public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs)
                            throws IOException {
                        Files.createDirectories(destination.resolve(source.relativize(dir)));
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                        Files.copy(file, destination.resolve(source.relativize(file)),
                                StandardCopyOption.REPLACE_EXISTING);
                        return FileVisitResult.CONTINUE;
                    }
                });
            } catch (IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        }

        @Override
        public void destroy(RuntimeHandle handle) {
        }
    }
}
