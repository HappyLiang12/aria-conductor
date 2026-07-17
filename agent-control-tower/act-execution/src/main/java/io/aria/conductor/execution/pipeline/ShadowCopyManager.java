package io.aria.conductor.execution.pipeline;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Manages pre-execution state snapshots ("shadow copies") for reversible actions.
 *
 * <p>This is <strong>audit-only</strong> infrastructure: it never auto-rolls
 * back. Operators inspect the persisted snapshots to manually recover from a
 * misbehaving action.
 */
@Slf4j
@Service
public class ShadowCopyManager {

    private final ShadowCopyRepository repository;

    public ShadowCopyManager(ShadowCopyRepository repository) {
        this.repository = repository;
    }

    /**
     * Persist a shadow copy of the action's pre-execution state.
     * Failures are logged but never rethrown — capture is best-effort and must
     * never block the pipeline.
     */
    public Optional<ShadowCopy> createShadowCopy(String runId, String actionId, String originalState) {
        return createShadowCopy(runId, actionId, originalState, null);
    }

    public Optional<ShadowCopy> createShadowCopy(String runId, String actionId,
                                                 String originalState, String actionType) {
        if (runId == null || actionId == null) {
            log.debug("Skipping shadow copy: runId or actionId is null");
            return Optional.empty();
        }
        try {
            ShadowCopy copy = ShadowCopy.builder()
                    .id(UUID.randomUUID().toString())
                    .runId(runId)
                    .actionId(actionId)
                    .actionType(actionType)
                    .originalState(originalState)
                    .build();
            ShadowCopy saved = repository.save(copy);
            log.debug("Shadow copy created: id={}, runId={}, actionId={}",
                    saved.getId(), runId, actionId);
            return Optional.of(saved);
        } catch (Exception e) {
            log.warn("Failed to create shadow copy (non-fatal): runId={}, actionId={}, err={}",
                    runId, actionId, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Retrieve the most recent shadow copy for a (run, action) pair.
     */
    public Optional<ShadowCopy> getShadowCopy(String runId, String actionId) {
        if (runId == null || actionId == null) return Optional.empty();
        return repository.findFirstByRunIdAndActionIdOrderByCreatedAtDesc(runId, actionId);
    }

    /**
     * List every shadow copy captured for a given run, oldest first.
     */
    public List<ShadowCopy> getShadowsForRun(String runId) {
        if (runId == null) return List.of();
        return repository.findByRunIdOrderByCreatedAtAsc(runId);
    }
}
