package io.aria.conductor.knowledge.sdd;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.aria.conductor.agent.repository.WorkflowChainRepository;
import io.aria.conductor.agent.service.WorkflowService;
import io.aria.conductor.common.artifact.RunArtifactLocator;
import io.aria.conductor.common.artifact.RunArtifactProperties;
import io.aria.conductor.common.event.BaStepCompletedEvent;
import io.aria.conductor.common.model.KnowledgeStatus;
import io.aria.conductor.common.model.KnowledgeType;
import io.aria.conductor.common.runtime.ExecutionMode;
import io.aria.conductor.common.runtime.WorkspaceKind;
import io.aria.conductor.execution.git.GitBranchService;
import io.aria.conductor.execution.repository.ApprovalRepository;
import io.aria.conductor.execution.repository.RunWorkspaceLeaseRepository;
import io.aria.conductor.execution.runtime.ArtifactBundle;
import io.aria.conductor.execution.runtime.ControlAck;
import io.aria.conductor.execution.runtime.ExecutionBackend;
import io.aria.conductor.execution.runtime.PreparedEnvironment;
import io.aria.conductor.execution.runtime.RunWorkspaceService;
import io.aria.conductor.execution.runtime.RuntimeHandle;
import io.aria.conductor.execution.runtime.ScratchWorkspaceProvisioner;
import io.aria.conductor.execution.runtime.StopProof;
import io.aria.conductor.execution.runtime.WorkspaceLease;
import io.aria.conductor.knowledge.dto.KnowledgeItemResponse;
import io.aria.conductor.knowledge.repository.KnowledgeItemRepository;
import io.aria.conductor.knowledge.repository.KnowledgeVersionRepository;
import io.aria.conductor.knowledge.service.KnowledgeService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.PlatformTransactionManager;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * The issue #112 acceptance tests: the result root is configured once and the
 * execution-side capture and the knowledge-side spec review both move with it.
 * These tests use the real configured-root path (the locator and the capture
 * service), not a mocked locator.
 */
@ExtendWith(MockitoExtension.class)
class SpecArtifactRootEndToEndTest {

    @Mock KnowledgeService knowledgeService;
    @Mock KnowledgeItemRepository itemRepository;
    @Mock KnowledgeVersionRepository versionRepository;
    @Mock ApprovalRepository approvalRepository;
    @Mock WorkflowChainRepository chainRepository;
    @Mock WorkflowService workflowService;
    @Mock ApplicationEventPublisher eventPublisher;
    @Mock PlatformTransactionManager transactionManager;
    @Mock GitBranchService gitBranchService;
    @Mock RunWorkspaceLeaseRepository leases;

    @TempDir Path temp;

    private final UUID chainId = UUID.randomUUID();
    private final UUID runId = UUID.randomUUID();
    private final UUID specItemId = UUID.randomUUID();
    private final String fullSpec = "# Spec\n\n## Requirements\n- Full captured spec body\n";
    private final String baSummary = "Spec ready. SPEC_ID=123e4567-e89b-12d3-a456-426614174000";

    private ListAppender<ILoggingEvent> coordinatorLogs;
    private Logger coordinatorLogger;

    @BeforeEach
    void setUp() {
        KnowledgeItemResponse response = KnowledgeItemResponse.builder()
                .id(specItemId)
                .name("spec-" + chainId)
                .type(KnowledgeType.SPEC)
                .status(KnowledgeStatus.PENDING)
                .build();
        org.mockito.Mockito.lenient().when(itemRepository.findByName("spec-" + chainId))
                .thenReturn(Optional.empty());
        org.mockito.Mockito.lenient().when(knowledgeService.submitKnowledge(any())).thenReturn(response);
        org.mockito.Mockito.lenient().when(approvalRepository.findByRunId(any())).thenReturn(List.of());
        org.mockito.Mockito.lenient().when(approvalRepository.save(any()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        org.mockito.Mockito.lenient().when(chainRepository.findById(chainId)).thenReturn(Optional.empty());

        coordinatorLogger = (Logger) LoggerFactory.getLogger(SpecReviewCoordinator.class);
        coordinatorLogs = new ListAppender<>();
        coordinatorLogs.start();
        coordinatorLogger.addAppender(coordinatorLogs);
    }

    @AfterEach
    void tearDown() {
        if (coordinatorLogs != null) {
            coordinatorLogger.detachAppender(coordinatorLogs);
            coordinatorLogs.stop();
        }
    }

    @Test
    void aNonDefaultRootCapturesTheSpecThatSpecReviewReads() throws Exception {
        Path root = temp.resolve("custom-results");
        RunArtifactLocator locator = locator(root);

        ArtifactBundle bundle = capture(locator, runId);

        Path capturedSpec = locator.specPath(runId.toString());
        assertThat(Files.readString(capturedSpec)).isEqualTo(fullSpec);
        assertThat(bundle.directory()).isEqualTo(capturedSpec.getParent());
        assertThat(bundle.complete()).isTrue();

        coordinator(locator).onBaStepCompleted(
                new BaStepCompletedEvent(this, chainId, 0, runId, baSummary));

        verify(knowledgeService).submitKnowledge(argThat(request ->
                request.getType() == KnowledgeType.SPEC && fullSpec.equals(request.getContent())));
    }

    @Test
    void changingOnlyTheCanonicalPropertyMovesBothCaptureAndRead() throws Exception {
        Path firstRoot = temp.resolve("results-one");
        Path secondRoot = temp.resolve("results-two");
        RunArtifactProperties properties = new RunArtifactProperties();
        properties.setResultRoot(firstRoot.toString());
        RunArtifactLocator locator = new RunArtifactLocator(properties);

        capture(locator, runId);
        assertThat(Files.exists(firstRoot.resolve(runId.toString()).resolve("spec.md"))).isTrue();

        // The only change is the canonical property value.
        properties.setResultRoot(secondRoot.toString());
        UUID secondRun = UUID.randomUUID();
        capture(locator, secondRun);

        assertThat(locator.resultRoot()).isEqualTo(secondRoot.toAbsolutePath().normalize());
        assertThat(Files.exists(firstRoot.resolve(secondRun.toString()))).isFalse();
        assertThat(Files.readString(secondRoot.resolve(secondRun.toString()).resolve("spec.md")))
                .isEqualTo(fullSpec);

        UUID thirdRun = UUID.randomUUID();
        coordinator(locator).onBaStepCompleted(
                new BaStepCompletedEvent(this, chainId, 0, thirdRun, baSummary));
        // No artifact under the second root for thirdRun: loud fallback to finalOutput.
        verify(knowledgeService).submitKnowledge(argThat(request -> SpecReviewCoordinator.cleanSpecContent(baSummary).equals(request.getContent())));
    }

    @Test
    void aMissingArtifactLogsTheResolvedPathAndRunIdThenFallsBack() {
        Path root = temp.resolve("empty-results");
        RunArtifactLocator locator = locator(root);

        coordinator(locator).onBaStepCompleted(
                new BaStepCompletedEvent(this, chainId, 0, runId, baSummary));

        Path attempted = locator.specPath(runId.toString());
        assertThat(coordinatorLogs.list)
                .anySatisfy(event -> {
                    assertThat(event.getLevel()).isEqualTo(Level.WARN);
                    assertThat(event.getFormattedMessage())
                            .contains(SpecReviewCoordinator.SPEC_UNAVAILABLE_MARKER)
                            .contains(runId.toString())
                            .contains("reason=" + "missing")
                            .contains(attempted.toAbsolutePath().normalize().toString())
                            .doesNotContain(baSummary);
                });
        verify(knowledgeService).submitKnowledge(argThat(request -> SpecReviewCoordinator.cleanSpecContent(baSummary).equals(request.getContent())));
    }

    @Test
    void anEmptyArtifactLogsTheEmptyReasonAndFallsBack() throws Exception {
        Path root = temp.resolve("blank-results");
        RunArtifactLocator locator = locator(root);
        Path spec = locator.specPath(runId.toString());
        Files.createDirectories(spec.getParent());
        Files.writeString(spec, "   \n");

        coordinator(locator).onBaStepCompleted(
                new BaStepCompletedEvent(this, chainId, 0, runId, baSummary));

        assertThat(coordinatorLogs.list)
                .anySatisfy(event -> {
                    assertThat(event.getLevel()).isEqualTo(Level.WARN);
                    assertThat(event.getFormattedMessage())
                            .contains(SpecReviewCoordinator.SPEC_UNAVAILABLE_MARKER)
                            .contains(runId.toString())
                            .contains("reason=empty")
                            .contains(spec.toString());
                });
        verify(knowledgeService).submitKnowledge(argThat(request -> SpecReviewCoordinator.cleanSpecContent(baSummary).equals(request.getContent())));
    }

    @Test
    void bothSpecAndFinalOutputUnavailableFailsLoudly() {
        Path root = temp.resolve("unusable-results");
        RunArtifactLocator locator = locator(root);

        assertThatThrownBy(() -> coordinator(locator).onBaStepCompleted(
                new BaStepCompletedEvent(this, chainId, 0, runId, "   ")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SPEC_REVIEW input is unusable");

        assertThat(coordinatorLogs.list)
                .anySatisfy(event -> {
                    assertThat(event.getLevel()).isEqualTo(Level.ERROR);
                    assertThat(event.getFormattedMessage())
                            .contains(SpecReviewCoordinator.SPEC_UNAVAILABLE_MARKER)
                            .contains(runId.toString())
                            .contains("unusable-input")
                            .contains(locator.resultRoot().toString());
                });
        verify(knowledgeService, never()).submitKnowledge(any());
    }

    @Test
    void theCaptureAndReadPathsAreTheSameCanonicalPath() throws Exception {
        Path root = temp.resolve("shared-results");
        RunArtifactLocator locator = locator(root);

        ArtifactBundle bundle = capture(locator, runId);

        Path writePath = bundle.directory().toAbsolutePath().normalize();
        Path readPath = locator.specPath(runId.toString()).getParent().toAbsolutePath().normalize();
        assertThat(writePath).isEqualTo(readPath);
    }

    private RunArtifactLocator locator(Path root) {
        RunArtifactProperties properties = new RunArtifactProperties();
        properties.setResultRoot(root.toString());
        return new RunArtifactLocator(properties);
    }

    private SpecReviewCoordinator coordinator(RunArtifactLocator locator) {
        return new SpecReviewCoordinator(knowledgeService, itemRepository, versionRepository,
                approvalRepository, chainRepository, workflowService, eventPublisher,
                transactionManager, gitBranchService, locator, 1800000L);
    }

    private ArtifactBundle capture(RunArtifactLocator locator, UUID capturedRunId) throws Exception {
        Path runtimeRoot = Files.createDirectories(temp.resolve("runtime"));
        Path workspace = Files.createDirectories(temp.resolve("workspace-" + capturedRunId));
        RunWorkspaceService service = new RunWorkspaceService(leases, runtimeRoot, locator,
                null, ScratchWorkspaceProvisioner.NONE);
        WorkspaceLease lease = new WorkspaceLease(UUID.randomUUID(), capturedRunId,
                WorkspaceKind.DIRECT, workspace, workspace, null, null);
        RuntimeHandle handle = new RuntimeHandle(capturedRunId, ExecutionMode.HOST,
                "env-" + capturedRunId, "owner-" + capturedRunId, URI.create("http://localhost"));
        return service.capture(lease, new SpecWritingBackend(fullSpec), handle,
                new StopProof(capturedRunId, true));
    }

    /** A backend whose export writes the BA's exported {@code spec.md} into the artifact directory. */
    private static final class SpecWritingBackend implements ExecutionBackend {

        private final String spec;

        private SpecWritingBackend(String spec) {
            this.spec = spec;
        }

        @Override
        public ExecutionMode mode() {
            return ExecutionMode.HOST;
        }

        @Override
        public PreparedEnvironment prepare(io.aria.conductor.execution.runtime.ExecutionSpec spec,
                WorkspaceLease workspace) {
            return null;
        }

        @Override
        public RuntimeHandle launch(PreparedEnvironment environment,
                io.aria.conductor.execution.runtime.LaunchProfile profile) {
            return null;
        }

        @Override
        public CompletionStage<ControlAck> pauseWriters(RuntimeHandle handle, Instant deadline) {
            return CompletableFuture.completedFuture(mock(ControlAck.class));
        }

        @Override
        public CompletionStage<ControlAck> resumeWriters(RuntimeHandle handle, Instant deadline) {
            return CompletableFuture.completedFuture(mock(ControlAck.class));
        }

        @Override
        public StopProof stopWriters(RuntimeHandle handle, Instant deadline) {
            return new StopProof(handle.runId(), true);
        }

        @Override
        public void exportWorkspace(RuntimeHandle handle, Path destination, StopProof proof) {
            try {
                Files.writeString(destination.resolve("spec.md"), spec);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }

        @Override
        public void destroy(RuntimeHandle handle) {
        }
    }
}
