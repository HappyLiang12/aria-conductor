package io.aria.conductor.app.sdd;

import io.aria.conductor.agent.repository.AgentRepository;
import io.aria.conductor.agent.repository.RunRepository;
import io.aria.conductor.app.BaseH2IntegrationTest;
import io.aria.conductor.common.event.BaStepCompletedEvent;
import io.aria.conductor.common.model.Agent;
import io.aria.conductor.common.model.AgentType;
import io.aria.conductor.common.model.Approval;
import io.aria.conductor.common.model.HealthStatus;
import io.aria.conductor.common.model.KnowledgeItem;
import io.aria.conductor.common.model.KnowledgeVersion;
import io.aria.conductor.common.model.Run;
import io.aria.conductor.common.model.RunStatus;
import io.aria.conductor.common.runtime.ExecutionMode;
import io.aria.conductor.common.runtime.WorkspaceKind;
import io.aria.conductor.common.port.RunArtifactReaderPort;
import io.aria.conductor.execution.repository.ApprovalRepository;
import io.aria.conductor.execution.runtime.ArtifactBundle;
import io.aria.conductor.execution.runtime.ExecutionBackend;
import io.aria.conductor.execution.runtime.PreparedEnvironment;
import io.aria.conductor.execution.runtime.RunWorkspaceService;
import io.aria.conductor.execution.runtime.RuntimeHandle;
import io.aria.conductor.execution.runtime.StopProof;
import io.aria.conductor.execution.runtime.WorkspaceLease;
import io.aria.conductor.execution.runtime.WorkspaceRunArtifactReader;
import io.aria.conductor.knowledge.repository.KnowledgeItemRepository;
import io.aria.conductor.knowledge.repository.KnowledgeVersionRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.net.URI;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletionStage;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AC2 of #112: the SDD spec review reads the BA artifact from the <em>configured</em> result
 * root. The context boots with {@code aria.workspaces.result-root} relocated to a temporary
 * directory, the run's {@code spec.md} is exported there by the real capture path, and the
 * SPEC knowledge item and the SPEC_REVIEW approval must then carry the file's body rather
 * than the BA run's one-line output. A reader that resolved its own root -- which is what
 * the duplicated {@code @Value} did -- would silently fall back here and fail this test.
 */
class SddSpecReviewResultRootTest extends BaseH2IntegrationTest {

    private static final String DEFAULT_RESULT_ROOT = "./data/workspaces/results";

    @TempDir
    static Path relocatedResultRoot;

    @TempDir
    static Path baWorkspaceRoot;

    @DynamicPropertySource
    static void workspaceRoots(DynamicPropertyRegistry registry) {
        registry.add("aria.workspaces.result-root", () -> relocatedResultRoot.toString());
    }

    @Autowired private RunWorkspaceService runWorkspaceService;
    @Autowired private RunArtifactReaderPort runArtifactReaderPort;
    @Autowired private ApplicationEventPublisher eventPublisher;
    @Autowired private KnowledgeItemRepository knowledgeItems;
    @Autowired private KnowledgeVersionRepository knowledgeVersions;
    @Autowired private ApprovalRepository approvals;
    @Autowired private RunRepository runs;
    @Autowired private AgentRepository agents;

    @Test
    void specReviewApprovesTheFileTheCaptureExportedUnderTheRelocatedRoot() throws IOException {
        UUID chainId = UUID.randomUUID();
        UUID baRunId = seedBaRun();
        String fullSpec = "# Spec\n\n## Problem\nThe reviewer must read this body, not a summary.\n\n"
                + "## Acceptance\n- The approval shows the artifact.\n";
        String baRunOutput = "Spec ready. SPEC_ID=" + UUID.randomUUID();

        // Wiring: one resolution of the property feeds the writer and the reader alike.
        assertThat(relocatedResultRoot).isNotEqualTo(Path.of(DEFAULT_RESULT_ROOT).toAbsolutePath().normalize());
        assertThat(runWorkspaceService.resultRoot()).isEqualTo(relocatedResultRoot.toRealPath());
        assertThat(runArtifactReaderPort).isInstanceOf(WorkspaceRunArtifactReader.class);

        captureSpec(baRunId, fullSpec);
        assertThat(runWorkspaceService.resultRoot().resolve(baRunId.toString()).resolve("spec.md"))
                .exists();

        eventPublisher.publishEvent(new BaStepCompletedEvent(this, chainId, 0, baRunId, baRunOutput));

        assertThat(storedSpecContent(chainId)).isEqualTo(fullSpec);
        Approval approval = specReviewApproval(baRunId);
        assertThat(approval.getContent()).isEqualTo(fullSpec);
        assertThat(approval.getContent()).doesNotContain("SPEC_ID=")
                .isNotEqualTo(baRunOutput)
                .contains("The reviewer must read this body");
        assertThat(approval.getReason()).doesNotStartWith("Spec file unavailable");
        // The reader did not consult the default location: nothing was written there for this run.
        assertThat(Path.of(DEFAULT_RESULT_ROOT).toAbsolutePath().normalize().resolve(baRunId.toString()))
                .doesNotExist();
    }

    @Test
    void specReviewFallsBackToTheRunOutputAndMarksTheApprovalWhenNoArtifactWasExported() {
        UUID chainId = UUID.randomUUID();
        UUID baRunId = seedBaRun();
        String baRunOutput = "Spec ready. SPEC_ID=" + UUID.randomUUID();

        eventPublisher.publishEvent(new BaStepCompletedEvent(this, chainId, 0, baRunId, baRunOutput));

        Approval approval = specReviewApproval(baRunId);
        assertThat(approval.getContent()).isEqualTo("Spec ready.");
        assertThat(approval.getReason())
                .startsWith("Spec file unavailable (showing run output) - ")
                .contains("spec-" + chainId);
    }

    /**
     * The SPEC_REVIEW approval carries a {@code run_id} FK to {@code runs}, so the BA run must
     * be a real row before the event is published. Only the id matters here: the spec content
     * comes from the artifact, the fallback from the event.
     */
    private UUID seedBaRun() {
        UUID runId = UUID.randomUUID();
        Agent agent = agents.save(Agent.builder()
                .id(UUID.randomUUID()).name("sdd-result-root-" + runId.toString().substring(0, 8))
                .description("BA agent for the #112 result-root contract test")
                .agentType(AgentType.ADK).role("ba").model("gpt-4o-mini")
                .provider("openai").config("{}").healthStatus(HealthStatus.HEALTHY)
                .createdAt(Instant.now()).build());
        runs.save(Run.builder()
                .id(runId).agentId(agent.getId()).status(RunStatus.COMPLETED)
                .promptSeed("write the spec").maxIterations(0).totalTokensUsed(0)
                .iterationCount(0).createdAt(Instant.now()).build());
        return runId;
    }

    /** Exports a BA run workspace holding {@code spec.md} through the production capture path. */
    private void captureSpec(UUID baRunId, String specContent) throws IOException {
        Path workspace = Files.createDirectories(baWorkspaceRoot.resolve(baRunId.toString()));
        Files.writeString(workspace.resolve("spec.md"), specContent);
        WorkspaceLease lease = new WorkspaceLease(UUID.randomUUID(), baRunId, WorkspaceKind.SCRATCH,
                workspace, workspace, null, null);
        ArtifactBundle bundle = runWorkspaceService.capture(lease, new ExportCopyingBackend(workspace),
                new RuntimeHandle(baRunId, ExecutionMode.HOST, "env-" + baRunId, "owned:" + baRunId,
                        URI.create("http://127.0.0.1:9400/" + baRunId)),
                new StopProof(baRunId, true));
        assertThat(bundle.complete()).as("capture reported a complete bundle").isTrue();
    }

    private String storedSpecContent(UUID chainId) {
        KnowledgeItem item = knowledgeItems.findByName("spec-" + chainId).orElseThrow();
        return knowledgeVersions
                .findByKnowledgeItemIdAndVersion(item.getId(), item.getCurrentVersion())
                .map(KnowledgeVersion::getContent)
                .orElseThrow();
    }

    private Approval specReviewApproval(UUID baRunId) {
        Optional<Approval> approval = approvals.findByRunId(baRunId).stream()
                .filter(a -> a.getApprovalType() == Approval.ApprovalType.SPEC_REVIEW)
                .findFirst();
        return approval.orElseThrow(() -> new AssertionError("no SPEC_REVIEW approval for run " + baRunId));
    }

    /** The {@code HostExecutionBackend.exportWorkspace} contract: the workspace root's contents land in the destination. */
    private record ExportCopyingBackend(Path source) implements ExecutionBackend {

        @Override
        public ExecutionMode mode() {
            return ExecutionMode.HOST;
        }

        @Override
        public PreparedEnvironment prepare(io.aria.conductor.execution.runtime.ExecutionSpec spec,
                WorkspaceLease workspace) {
            throw new UnsupportedOperationException();
        }

        @Override
        public RuntimeHandle launch(PreparedEnvironment environment,
                io.aria.conductor.execution.runtime.LaunchProfile profile) {
            throw new UnsupportedOperationException();
        }

        @Override
        public CompletionStage<io.aria.conductor.execution.runtime.ControlAck> pauseWriters(
                RuntimeHandle handle, Instant deadline) {
            throw new UnsupportedOperationException();
        }

        @Override
        public CompletionStage<io.aria.conductor.execution.runtime.ControlAck> resumeWriters(
                RuntimeHandle handle, Instant deadline) {
            throw new UnsupportedOperationException();
        }

        @Override
        public StopProof stopWriters(RuntimeHandle handle, Instant deadline) {
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
