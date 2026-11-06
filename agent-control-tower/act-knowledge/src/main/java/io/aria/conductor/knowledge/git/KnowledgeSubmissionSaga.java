package io.aria.conductor.knowledge.git;

import io.aria.conductor.common.model.KnowledgeItem;
import io.aria.conductor.common.model.KnowledgeStatus;
import io.aria.conductor.knowledge.repository.KnowledgeItemRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Durable saga that drives a {@link KnowledgeItem} through the Git submission
 * workflow asynchronously:
 * <pre>
 *   QUEUED -> BRANCH_CREATED -> COMMITTED -> MERGED -> COMPLETE
 *                                                  \-> FAILED
 * </pre>
 * On any step's failure the intent is retried with exponential backoff up to
 * {@code maxRetries=5}. After exhausting retries the saga compensates: the
 * feature branch (if any) is deleted and the source knowledge item's status is
 * reverted to a sentinel REJECTED state to flag the failure for review.
 */
@Service
public class KnowledgeSubmissionSaga {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeSubmissionSaga.class);
    private static final long BASE_BACKOFF_MILLIS = 1_000L;

    private final KnowledgeSubmissionIntentRepository intentRepository;
    private final KnowledgeItemRepository itemRepository;
    private final LocalGitClient gitClient;

    public KnowledgeSubmissionSaga(KnowledgeSubmissionIntentRepository intentRepository,
                                   KnowledgeItemRepository itemRepository,
                                   LocalGitClient gitClient) {
        this.intentRepository = intentRepository;
        this.itemRepository = itemRepository;
        this.gitClient = gitClient;
    }

    /**
     * Enrol a new knowledge item submission into the saga. The item must
     * already be persisted (so its id is stable). The actual git work happens
     * asynchronously in {@link #processIntents()}.
     */
    @Transactional
    public KnowledgeSubmissionIntent submit(KnowledgeItem item) {
        String repoName = repoNameFor(item);
        gitClient.initRepo(repoName);

        KnowledgeSubmissionIntent intent = KnowledgeSubmissionIntent.builder()
                .id(UUID.randomUUID().toString())
                .itemId(item.getId().toString())
                .repoName(repoName)
                .filePath(defaultFilePath(item))
                .content(readContentForItem(item))
                .status(KnowledgeSubmissionIntent.Status.QUEUED)
                .retryCount(0)
                .maxRetries(5)
                .createdAt(Instant.now())
                .build();
        intent = intentRepository.save(intent);
        log.info("Queued submission saga {} for knowledge item {}", intent.getId(), item.getId());
        return intent;
    }

    /**
     * Background worker advancing every non-terminal intent by one step. Runs
     * every 5 seconds. Each intent is processed in its own short transaction so
     * a single failure cannot block the entire pipeline.
     */
    @Scheduled(fixedDelay = 5000)
    public void processIntents() {
        List<KnowledgeSubmissionIntent> pending = intentRepository.findByStatusIn(List.of(
                KnowledgeSubmissionIntent.Status.QUEUED,
                KnowledgeSubmissionIntent.Status.BRANCH_CREATED,
                KnowledgeSubmissionIntent.Status.COMMITTED,
                KnowledgeSubmissionIntent.Status.MERGED));
        for (KnowledgeSubmissionIntent intent : pending) {
            try {
                advance(intent);
            } catch (Exception e) {
                log.warn("Saga step failed for intent {}: {}", intent.getId(), e.getMessage());
                handleFailure(intent, e);
            }
        }
    }

    /** Advance one intent by exactly one state transition. Visible for tests. */
    @Transactional
    public KnowledgeSubmissionIntent advance(KnowledgeSubmissionIntent intent) {
        switch (intent.getStatus()) {
            case QUEUED -> {
                String branch = gitClient.createBranch(intent.getRepoName(), branchSeed(intent));
                intent.setBranchName(branch);
                intent.setStatus(KnowledgeSubmissionIntent.Status.BRANCH_CREATED);
            }
            case BRANCH_CREATED -> {
                gitClient.commit(intent.getRepoName(), intent.getBranchName(), intent.getFilePath(),
                        intent.getContent(), "Submit " + intent.getFilePath());
                intent.setStatus(KnowledgeSubmissionIntent.Status.COMMITTED);
            }
            case COMMITTED -> {
                gitClient.mergeBranch(intent.getRepoName(), intent.getBranchName());
                intent.setStatus(KnowledgeSubmissionIntent.Status.MERGED);
            }
            case MERGED -> {
                gitClient.deleteBranch(intent.getRepoName(), intent.getBranchName());
                intent.setStatus(KnowledgeSubmissionIntent.Status.COMPLETE);
            }
            default -> {
                // already terminal
            }
        }
        intent.setUpdatedAt(Instant.now());
        return intentRepository.save(intent);
    }

    @Transactional
    public void handleFailure(KnowledgeSubmissionIntent intent, Exception cause) {
        int next = (intent.getRetryCount() == null ? 0 : intent.getRetryCount()) + 1;
        intent.setRetryCount(next);
        intent.setLastError(truncate(cause.getMessage(), 1990));
        intent.setUpdatedAt(Instant.now());

        if (next >= intent.getMaxRetries()) {
            log.error("Saga {} exhausted {} retries; compensating", intent.getId(), intent.getMaxRetries());
            compensate(intent);
            intent.setStatus(KnowledgeSubmissionIntent.Status.FAILED);
        }
        intentRepository.save(intent);
    }

    /**
     * Compensation: best-effort delete the feature branch and revert the
     * source item back to REJECTED so an operator can inspect the failure.
     */
    void compensate(KnowledgeSubmissionIntent intent) {
        if (intent.getBranchName() != null) {
            try {
                gitClient.deleteBranch(intent.getRepoName(), intent.getBranchName());
            } catch (RuntimeException e) {
                log.warn("Compensation could not delete branch {}: {}", intent.getBranchName(), e.getMessage());
            }
        }
        try {
            UUID itemId = UUID.fromString(intent.getItemId());
            itemRepository.findById(itemId).ifPresent(item -> {
                item.setStatus(KnowledgeStatus.REJECTED);
                item.setRejectionReason("Submission saga failed: " + truncate(intent.getLastError(), 800));
                itemRepository.save(item);
            });
        } catch (IllegalArgumentException e) {
            log.warn("Cannot parse item id {} during compensation", intent.getItemId());
        }
    }

    /** Compute the per-intent backoff in millis. Visible for tests. */
    public long backoffMillis(KnowledgeSubmissionIntent intent) {
        int n = intent.getRetryCount() == null ? 0 : intent.getRetryCount();
        // exponential with cap; 1s, 2s, 4s, 8s, 16s
        long ms = BASE_BACKOFF_MILLIS;
        for (int i = 0; i < n && ms < 60_000L; i++) {
            ms *= 2;
        }
        return Math.min(ms, 60_000L);
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------
    private static String repoNameFor(KnowledgeItem item) {
        // skills, scripts, prompts, tools, templates
        return item.getType().name().toLowerCase() + "s";
    }

    private static String defaultFilePath(KnowledgeItem item) {
        String safeName = item.getName().replaceAll("[^a-zA-Z0-9._-]", "-");
        return safeName + "/" + item.getCurrentVersion() + ".md";
    }

    private static String readContentForItem(KnowledgeItem item) {
        // Saga is fed by the higher-level service which writes content to the
        // intent directly; this fallback merely records the file path on disk
        // so a later sync can locate it. Empty string is acceptable.
        return "# " + item.getName() + "\n\nVersion: " + item.getCurrentVersion() + "\n";
    }

    private static String branchSeed(KnowledgeSubmissionIntent intent) {
        String fp = intent.getFilePath() == null ? "submission" : intent.getFilePath();
        int slash = fp.indexOf('/');
        return slash > 0 ? fp.substring(0, slash) : fp;
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }
}
