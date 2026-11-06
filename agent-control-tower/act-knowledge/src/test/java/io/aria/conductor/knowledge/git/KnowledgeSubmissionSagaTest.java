package io.aria.conductor.knowledge.git;

import io.aria.conductor.common.model.KnowledgeItem;
import io.aria.conductor.common.model.KnowledgeStatus;
import io.aria.conductor.common.model.KnowledgeType;
import io.aria.conductor.common.model.Sensitivity;
import io.aria.conductor.knowledge.repository.KnowledgeItemRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class KnowledgeSubmissionSagaTest {

    @Mock
    KnowledgeSubmissionIntentRepository intentRepository;

    @Mock
    KnowledgeItemRepository itemRepository;

    @Mock
    LocalGitClient gitClient;

    KnowledgeSubmissionSaga saga;

    @BeforeEach
    void setUp() {
        saga = new KnowledgeSubmissionSaga(intentRepository, itemRepository, gitClient);
        lenient().when(intentRepository.save(any(KnowledgeSubmissionIntent.class)))
                .thenAnswer(inv -> inv.getArgument(0));
    }

    private KnowledgeItem sampleItem() {
        return KnowledgeItem.builder()
                .id(UUID.randomUUID())
                .name("hello")
                .type(KnowledgeType.PROMPT)
                .currentVersion("v0.1.0")
                .status(KnowledgeStatus.PENDING)
                .sensitivity(Sensitivity.INTERNAL)
                .createdAt(Instant.now())
                .escalationCount(0)
                .build();
    }

    @Test
    void submit_initialisesRepoAndPersistsQueuedIntent() {
        KnowledgeItem item = sampleItem();

        KnowledgeSubmissionIntent intent = saga.submit(item);

        verify(gitClient).initRepo("prompts");
        ArgumentCaptor<KnowledgeSubmissionIntent> captor =
                ArgumentCaptor.forClass(KnowledgeSubmissionIntent.class);
        verify(intentRepository).save(captor.capture());
        KnowledgeSubmissionIntent saved = captor.getValue();
        assertThat(saved.getStatus()).isEqualTo(KnowledgeSubmissionIntent.Status.QUEUED);
        assertThat(saved.getItemId()).isEqualTo(item.getId().toString());
        assertThat(saved.getRepoName()).isEqualTo("prompts");
        assertThat(saved.getMaxRetries()).isEqualTo(5);
        assertThat(intent).isNotNull();
    }

    @Test
    void advance_queuedToBranchCreated() {
        KnowledgeSubmissionIntent intent = newIntent(KnowledgeSubmissionIntent.Status.QUEUED);
        when(gitClient.createBranch(eq("prompts"), anyString())).thenReturn("feature/hello-1");

        KnowledgeSubmissionIntent updated = saga.advance(intent);

        assertThat(updated.getStatus()).isEqualTo(KnowledgeSubmissionIntent.Status.BRANCH_CREATED);
        assertThat(updated.getBranchName()).isEqualTo("feature/hello-1");
    }

    @Test
    void advance_branchCreatedToCommitted() {
        KnowledgeSubmissionIntent intent = newIntent(KnowledgeSubmissionIntent.Status.BRANCH_CREATED);
        intent.setBranchName("feature/hello-1");
        when(gitClient.commit(eq("prompts"), eq("feature/hello-1"), eq("hello/v0.1.0.md"), anyString(), anyString()))
                .thenReturn("abc1234");

        KnowledgeSubmissionIntent updated = saga.advance(intent);

        assertThat(updated.getStatus()).isEqualTo(KnowledgeSubmissionIntent.Status.COMMITTED);
    }

    @Test
    void advance_committedToMerged() {
        KnowledgeSubmissionIntent intent = newIntent(KnowledgeSubmissionIntent.Status.COMMITTED);
        intent.setBranchName("feature/hello-1");

        KnowledgeSubmissionIntent updated = saga.advance(intent);

        verify(gitClient).mergeBranch("prompts", "feature/hello-1");
        assertThat(updated.getStatus()).isEqualTo(KnowledgeSubmissionIntent.Status.MERGED);
    }

    @Test
    void advance_mergedToComplete_deletesBranch() {
        KnowledgeSubmissionIntent intent = newIntent(KnowledgeSubmissionIntent.Status.MERGED);
        intent.setBranchName("feature/hello-1");

        KnowledgeSubmissionIntent updated = saga.advance(intent);

        verify(gitClient).deleteBranch("prompts", "feature/hello-1");
        assertThat(updated.getStatus()).isEqualTo(KnowledgeSubmissionIntent.Status.COMPLETE);
    }

    @Test
    void processIntents_advancesAllPendingOnes() {
        KnowledgeSubmissionIntent a = newIntent(KnowledgeSubmissionIntent.Status.QUEUED);
        KnowledgeSubmissionIntent b = newIntent(KnowledgeSubmissionIntent.Status.MERGED);
        b.setBranchName("feature/x-1");
        when(intentRepository.findByStatusIn(any())).thenReturn(List.of(a, b));
        when(gitClient.createBranch(anyString(), anyString())).thenReturn("feature/a-1");

        saga.processIntents();

        verify(gitClient).createBranch(eq("prompts"), anyString());
        verify(gitClient).deleteBranch("prompts", "feature/x-1");
    }

    @Test
    void handleFailure_incrementsRetryCountWithoutCompensatingBeforeMax() {
        KnowledgeSubmissionIntent intent = newIntent(KnowledgeSubmissionIntent.Status.QUEUED);
        intent.setRetryCount(0);

        saga.handleFailure(intent, new RuntimeException("boom"));

        assertThat(intent.getRetryCount()).isEqualTo(1);
        assertThat(intent.getStatus()).isEqualTo(KnowledgeSubmissionIntent.Status.QUEUED);
        assertThat(intent.getLastError()).contains("boom");
        verify(gitClient, never()).deleteBranch(anyString(), anyString());
    }

    @Test
    void handleFailure_atMaxRetries_compensatesAndMarksFailed() {
        KnowledgeSubmissionIntent intent = newIntent(KnowledgeSubmissionIntent.Status.BRANCH_CREATED);
        intent.setRetryCount(4); // next will be 5 == maxRetries
        intent.setBranchName("feature/hello-1");
        UUID itemUuid = UUID.fromString(intent.getItemId());
        KnowledgeItem item = sampleItem();
        item.setId(itemUuid);
        when(itemRepository.findById(itemUuid)).thenReturn(Optional.of(item));
        when(itemRepository.save(any(KnowledgeItem.class))).thenAnswer(inv -> inv.getArgument(0));

        saga.handleFailure(intent, new RuntimeException("permafail"));

        assertThat(intent.getStatus()).isEqualTo(KnowledgeSubmissionIntent.Status.FAILED);
        verify(gitClient).deleteBranch("prompts", "feature/hello-1");
        ArgumentCaptor<KnowledgeItem> capt = ArgumentCaptor.forClass(KnowledgeItem.class);
        verify(itemRepository).save(capt.capture());
        assertThat(capt.getValue().getStatus()).isEqualTo(KnowledgeStatus.REJECTED);
        assertThat(capt.getValue().getRejectionReason()).contains("Submission saga failed");
    }

    @Test
    void backoffMillis_isExponentialWithCap() {
        KnowledgeSubmissionIntent intent = newIntent(KnowledgeSubmissionIntent.Status.QUEUED);
        intent.setRetryCount(0);
        assertThat(saga.backoffMillis(intent)).isEqualTo(1_000L);
        intent.setRetryCount(1);
        assertThat(saga.backoffMillis(intent)).isEqualTo(2_000L);
        intent.setRetryCount(2);
        assertThat(saga.backoffMillis(intent)).isEqualTo(4_000L);
        intent.setRetryCount(10);
        assertThat(saga.backoffMillis(intent)).isLessThanOrEqualTo(60_000L);
    }

    @Test
    void processIntents_failureIsCaughtAndAdvancesViaHandleFailure() {
        KnowledgeSubmissionIntent intent = newIntent(KnowledgeSubmissionIntent.Status.QUEUED);
        intent.setRetryCount(0);
        when(intentRepository.findByStatusIn(any())).thenReturn(List.of(intent));
        when(gitClient.createBranch(anyString(), anyString()))
                .thenThrow(new LocalGitClient.GitOperationException("disk full"));

        saga.processIntents();

        // After failure, retry count incremented and intent stays at QUEUED
        assertThat(intent.getRetryCount()).isEqualTo(1);
        assertThat(intent.getStatus()).isEqualTo(KnowledgeSubmissionIntent.Status.QUEUED);
        verify(intentRepository, atLeastOnce()).save(intent);
    }

    @Test
    void submit_acceptsAllKnowledgeTypes() {
        for (KnowledgeType t : KnowledgeType.values()) {
            KnowledgeItem item = sampleItem();
            item.setType(t);
            saga.submit(item);
        }
        // Five distinct repo inits expected (skills, scripts, prompts, tools, templates)
        verify(gitClient, times(KnowledgeType.values().length)).initRepo(anyString());
    }

    private KnowledgeSubmissionIntent newIntent(KnowledgeSubmissionIntent.Status status) {
        KnowledgeSubmissionIntent intent = KnowledgeSubmissionIntent.builder()
                .id(UUID.randomUUID().toString())
                .itemId(UUID.randomUUID().toString())
                .repoName("prompts")
                .filePath("hello/v0.1.0.md")
                .content("# hello")
                .status(status)
                .retryCount(0)
                .maxRetries(5)
                .createdAt(Instant.now())
                .build();
        return intent;
    }
}
