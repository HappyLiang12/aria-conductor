package io.aria.conductor.execution.dod;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.aria.conductor.execution.dod.dto.CreateEvidenceRequest;
import io.aria.conductor.execution.dod.dto.DoDStatusResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;

/**
 * Implements the Definition of Done stage-gate workflow described in
 * {@code plan/dashboard-workflows.md} Section 8.
 *
 * <p>Stage progression is linear over the record's stage list (custom stages
 * persisted as JSON, or {@link #DEFAULT_STAGES} when unset):
 * <ul>
 *   <li>{@code passed = true} → record review, advance to next stage.</li>
 *   <li>{@code passed = false} on a required stage → record review, stay put
 *       (advancement blocked).</li>
 *   <li>{@code passed = false} on an optional stage → record review, advance
 *       (failure does not block).</li>
 *   <li>When the last stage advances, overall status flips to {@code PASSED}
 *       and {@code currentStage} is left at the terminal sentinel
 *       {@link #COMPLETED_STAGE}.</li>
 * </ul>
 */
@Slf4j
@Service
public class DoDService {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static final List<String> DEFAULT_STAGES = List.of("dev", "qa", "ba", "pm");
    public static final Set<String> REQUIRED_STAGES = Set.of("dev", "qa");

    public static final String STATUS_IN_PROGRESS = "IN_PROGRESS";
    public static final String STATUS_PASSED = "PASSED";
    public static final String STATUS_FAILED = "FAILED";

    /** Terminal value for {@code currentStage} once all stages have advanced. */
    public static final String COMPLETED_STAGE = "completed";

    /** Per-stage roll-up status values surfaced via {@link DoDStatusResponse}. */
    public static final String STAGE_PENDING = "PENDING";
    public static final String STAGE_PASSED = "PASSED";
    public static final String STAGE_FAILED = "FAILED";
    public static final String STAGE_SKIPPED = "SKIPPED";

    private final DoDRepository dodRepository;
    private final DoDStageReviewRepository reviewRepository;
    private final EvidenceItemRepository evidenceRepository;

    public DoDService(DoDRepository dodRepository,
                      DoDStageReviewRepository reviewRepository,
                      EvidenceItemRepository evidenceRepository) {
        this.dodRepository = dodRepository;
        this.reviewRepository = reviewRepository;
        this.evidenceRepository = evidenceRepository;
    }

    /** Idempotent initializer with default stages — returns the existing record if one already exists. */
    @Transactional
    public DoDRecord init(String taskId, String taskType) {
        return init(taskId, taskType, null);
    }

    /**
     * Idempotent initializer; optional custom stage list (null/empty = {@link #DEFAULT_STAGES}).
     * Returns the existing record if one already exists for the task.
     */
    @Transactional
    public DoDRecord init(String taskId, String taskType, List<String> stages) {
        Objects.requireNonNull(taskId, "taskId is required");
        return dodRepository.findByTaskId(taskId)
                .orElseGet(() -> {
                    List<String> effective = (stages == null || stages.isEmpty()) ? DEFAULT_STAGES : stages;
                    DoDRecord record = DoDRecord.builder()
                            .id(UUID.randomUUID().toString())
                            .taskId(taskId)
                            .taskType(taskType)
                            .currentStage(effective.get(0))
                            .overallStatus(STATUS_IN_PROGRESS)
                            .stagesJson((stages == null || stages.isEmpty()) ? null : toJson(stages))
                            .build();
                    DoDRecord saved = dodRepository.save(record);
                    log.info("DoD initialized: taskId={} firstStage={} stages={}",
                            taskId, saved.getCurrentStage(), effective);
                    return saved;
                });
    }

    /** Backward-compatible review (no verdict). */
    @Transactional
    public DoDRecord review(String taskId,
                            String reviewerId,
                            String reviewerName,
                            boolean passed,
                            String evidence,
                            String comment) {
        return review(taskId, reviewerId, reviewerName, passed, evidence, comment, null);
    }

    /**
     * Submit a stage review against the task's current stage.
     *
     * @param verdict optional SDD verdict (e.g. PASS/DEFECT/SPEC_GAP); purely recorded,
     *                never globally validated.
     * @throws IllegalStateException if the task has no DoD record or the workflow is already complete.
     */
    @Transactional
    public DoDRecord review(String taskId,
                            String reviewerId,
                            String reviewerName,
                            boolean passed,
                            String evidence,
                            String comment,
                            String verdict) {
        DoDRecord record = dodRepository.findByTaskId(taskId)
                .orElseThrow(() -> new IllegalStateException("No DoD record for task: " + taskId));

        if (COMPLETED_STAGE.equals(record.getCurrentStage())
                || STATUS_PASSED.equals(record.getOverallStatus())) {
            throw new IllegalStateException("DoD already completed for task: " + taskId);
        }

        String stage = record.getCurrentStage();
        DoDStageReview reviewEntity = DoDStageReview.builder()
                .id(UUID.randomUUID().toString())
                .dodId(record.getId())
                .stage(stage)
                .reviewerId(reviewerId)
                .reviewerName(reviewerName)
                .passed(passed)
                .verdict(verdict)
                .evidence(evidence)
                .comment(comment)
                .build();
        reviewRepository.save(reviewEntity);
        log.info("DoD review recorded: taskId={} stage={} passed={} reviewer={} verdict={}",
                taskId, stage, passed, reviewerId, verdict);

        boolean required = REQUIRED_STAGES.contains(stage);
        if (passed) {
            advance(record);
        } else if (!required) {
            // optional stage failure → skip past it
            advance(record);
        } else {
            // required stage failure → block advancement, record stays
            record.setUpdatedAt(Instant.now());
            log.info("DoD blocked at required stage: taskId={} stage={}", taskId, stage);
        }

        return dodRepository.save(record);
    }

    /** Engine hook: submit a stage review programmatically (DEV-step auto-submit). */
    @Transactional
    public DoDRecord submitStageReview(String taskId, String reviewerId, String reviewerName,
                                       boolean passed, String comment) {
        return review(taskId, reviewerId, reviewerName, passed, null, comment, null);
    }

    /** Resolve the effective stage list for a record (custom stages JSON or defaults). */
    private List<String> stagesOf(DoDRecord record) {
        if (record.getStagesJson() == null || record.getStagesJson().isBlank()) {
            return DEFAULT_STAGES;
        }
        return fromJson(record.getStagesJson());
    }

    private static String toJson(List<String> stages) {
        try {
            return MAPPER.writeValueAsString(stages);
        } catch (Exception e) {
            log.warn("DoD: failed to serialize stages, falling back to defaults: {}", stages, e);
            return null;
        }
    }

    private static List<String> fromJson(String json) {
        try {
            return MAPPER.readValue(json, new TypeReference<List<String>>() {});
        } catch (Exception e) {
            log.warn("DoD: failed to parse stagesJson, falling back to defaults: {}", json, e);
            return DEFAULT_STAGES;
        }
    }

    /** Advance the record to the next stage (or mark PASSED on completion). */
    private void advance(DoDRecord record) {
        List<String> stages = stagesOf(record);
        int idx = stages.indexOf(record.getCurrentStage());
        if (idx < 0 || idx >= stages.size() - 1) {
            record.setCurrentStage(COMPLETED_STAGE);
            record.setOverallStatus(STATUS_PASSED);
            log.info("DoD completed: taskId={}", record.getTaskId());
        } else {
            record.setCurrentStage(stages.get(idx + 1));
        }
        record.setUpdatedAt(Instant.now());
    }

    @Transactional(readOnly = true)
    public DoDRecord getStatus(String taskId) {
        return dodRepository.findByTaskId(taskId)
                .orElseThrow(() -> new IllegalStateException("No DoD record for task: " + taskId));
    }

    /** Latest qa-stage review for an SDD chain, or null. */
    @Transactional(readOnly = true)
    public DoDStageReview latestQaReview(DoDRecord record) {
        if (record == null) return null;
        return reviewRepository.findByDodIdAndStageOrderByReviewedAtDesc(record.getId(), "qa")
                .stream().findFirst().orElse(null);
    }

    /** Build the dashboard-friendly aggregate view used by {@code GET /api/v1/dod/{taskId}}. */
    @Transactional(readOnly = true)
    public DoDStatusResponse buildStatusResponse(String taskId) {
        DoDRecord record = getStatus(taskId);
        List<DoDStageReview> reviews = reviewRepository.findByDodIdOrderByReviewedAtAsc(record.getId());
        long evidenceCount = evidenceRepository.findByDodId(record.getId()).size();
        List<DoDStatusResponse.StageStatus> stages = computeStageRollup(record, reviews);
        return DoDStatusResponse.of(record, stages, reviews, evidenceCount);
    }

    private List<DoDStatusResponse.StageStatus> computeStageRollup(DoDRecord record,
                                                                   List<DoDStageReview> reviews) {
        Map<String, List<DoDStageReview>> byStage = new HashMap<>();
        for (DoDStageReview r : reviews) {
            byStage.computeIfAbsent(r.getStage(), k -> new ArrayList<>()).add(r);
        }

        List<String> stages = stagesOf(record);
        int currentIdx = stages.indexOf(record.getCurrentStage());
        boolean completed = COMPLETED_STAGE.equals(record.getCurrentStage())
                || STATUS_PASSED.equals(record.getOverallStatus());

        List<DoDStatusResponse.StageStatus> result = new ArrayList<>();
        for (int i = 0; i < stages.size(); i++) {
            String stage = stages.get(i);
            boolean required = REQUIRED_STAGES.contains(stage);
            List<DoDStageReview> stageReviews = byStage.getOrDefault(stage, List.of());
            String status;
            Instant lastReviewedAt = null;
            if (!stageReviews.isEmpty()) {
                DoDStageReview last = stageReviews.get(stageReviews.size() - 1);
                lastReviewedAt = last.getReviewedAt();
                if (last.isPassed()) {
                    status = STAGE_PASSED;
                } else if (!required && (completed || (currentIdx > i))) {
                    // optional stage that failed but workflow advanced past it
                    status = STAGE_SKIPPED;
                } else {
                    status = STAGE_FAILED;
                }
            } else if (completed || (currentIdx > i)) {
                // shouldn't normally happen, but treat as skipped if advanced past with no review
                status = STAGE_SKIPPED;
            } else {
                status = STAGE_PENDING;
            }
            result.add(new DoDStatusResponse.StageStatus(
                    stage, required, status, stageReviews.size(), lastReviewedAt));
        }
        return result;
    }

    @Transactional(readOnly = true)
    public List<EvidenceItem> getEvidence(String taskId) {
        return evidenceRepository.findByTaskIdOrderByCreatedAtDesc(taskId);
    }

    @Transactional
    public EvidenceItem addEvidence(String taskId, CreateEvidenceRequest request) {
        DoDRecord record = dodRepository.findByTaskId(taskId)
                .orElseThrow(() -> new IllegalStateException("No DoD record for task: " + taskId));
        EvidenceItem item = EvidenceItem.builder()
                .id(UUID.randomUUID().toString())
                .dodId(record.getId())
                .taskId(taskId)
                .type(request.type())
                .title(request.title())
                .content(request.content())
                .artifactPath(request.artifactPath())
                .sourceRunId(request.sourceRunId())
                .build();
        EvidenceItem saved = evidenceRepository.save(item);
        log.info("Evidence added: taskId={} type={} id={}", taskId, request.type(), saved.getId());
        return saved;
    }
}
