package io.aria.conductor.execution.listener;

import io.aria.conductor.agent.repository.WorkflowChainRepository;
import io.aria.conductor.agent.service.WorkflowService;
import io.aria.conductor.common.event.BaStepCompletedEvent;
import io.aria.conductor.common.event.RunCompletedEvent;
import io.aria.conductor.common.event.WorkflowAdvancedEvent;
import io.aria.conductor.common.model.RunStatus;
import io.aria.conductor.common.model.WorkflowChain;
import io.aria.conductor.common.model.WorkflowStep;
import io.aria.conductor.execution.dod.DoDRecord;
import io.aria.conductor.execution.dod.DoDService;
import io.aria.conductor.execution.dod.DoDStageReview;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Listens for {@link RunCompletedEvent} and automatically advances
 * any workflow chain that the completed run belongs to.
 *
 * <p>When a run completes:
 * <ul>
 *   <li>If the run is part of an active workflow chain, the chain is advanced
 *       to the next step (with the previous step's output injected into the prompt).</li>
 *   <li>If the run failed, the chain is marked as FAILED.</li>
 *   <li>If the run is not part of any chain, this listener is a no-op.</li>
 * </ul>
 *
 * <p>Step completion is kind-aware (see {@link #routeStepCompletion}): a BA step
 * hands off to the spec coordinator via {@link BaStepCompletedEvent} without
 * advancing, a DEV step auto-submits the dev-stage DoD review then advances,
 * a QA step routes on its recorded verdict, and GENERIC/CODE_REVIEW keep the
 * existing linear advance.
 */
@Slf4j
@Component
public class WorkflowAutoChainer {

    /** QA report id capture convention: the QA run's finalOutput ends with REPORT_ID=<uuid>. */
    private static final Pattern REPORT_ID_PATTERN = Pattern.compile("REPORT_ID=([0-9a-fA-F-]{36})");

    private final WorkflowService workflowService;
    private final DoDService dodService;
    private final WorkflowChainRepository chainRepository;
    private final ApplicationEventPublisher eventPublisher;

    public WorkflowAutoChainer(WorkflowService workflowService,
                               DoDService dodService,
                               WorkflowChainRepository chainRepository,
                               ApplicationEventPublisher eventPublisher) {
        this.workflowService = workflowService;
        this.dodService = dodService;
        this.chainRepository = chainRepository;
        this.eventPublisher = eventPublisher;
    }

    @EventListener
    public void onRunCompleted(RunCompletedEvent event) {
        try {
            WorkflowChain chain = workflowService.findChainByRunId(event.getRunId());
            if (chain == null) {
                // This run is not part of any workflow chain — no-op
                return;
            }

            int stepIndex = workflowService.findStepIndex(chain, event.getRunId());
            if (stepIndex < 0) {
                log.warn("Run {} found in chain {} but step index not found",
                        event.getRunId(), chain.getId());
                return;
            }

            log.info("Workflow auto-chain triggered: chain={}, step={}, status={}",
                    chain.getId(), stepIndex, event.getStatus());

            if (event.getStatus() == RunStatus.FAILED) {
                workflowService.markStepFailed(chain.getId(), stepIndex,
                        "Run failed" + (event.getFinalOutput() != null ? ": " + event.getFinalOutput() : ""));
                return;
            }

            if (event.getStatus() == RunStatus.CANCELLED) {
                workflowService.markStepFailed(chain.getId(), stepIndex, "Run was cancelled");
                return;
            }

            if (event.getStatus() == RunStatus.ABORTED) {
                workflowService.markStepFailed(chain.getId(), stepIndex, "Run was aborted");
                return;
            }

            routeStepCompletion(chain, stepIndex, event.getFinalOutput());
        } catch (Exception e) {
            log.error("Workflow auto-chain failed for run {}: {}",
                    event.getRunId(), e.getMessage(), e);
        }
    }

    /**
     * Kind-aware routing for a completed step. GENERIC/CODE_REVIEW/null keep the existing
     * linear advance; BA hands off to the spec coordinator; DEV advances the DoD dev stage;
     * QA routes on its recorded verdict. All branches are guarded by chain-status preconditions.
     */
    private void routeStepCompletion(WorkflowChain chain, int stepIndex, String finalOutput) {
        WorkflowStep step = workflowService.stepAt(chain, stepIndex);
        WorkflowStep.StepKind kind = step != null && step.getKind() != null
                ? step.getKind() : WorkflowStep.StepKind.GENERIC;

        switch (kind) {
            case BA -> {
                // Signal the coordinator (act-knowledge) via a domain event; do NOT advance here.
                eventPublisher.publishEvent(new BaStepCompletedEvent(
                        this, chain.getId(), stepIndex, step.getRunId(), finalOutput));
                // chain stays RUNNING until the coordinator moves it to WAITING_APPROVAL
            }
            case DEV -> {
                autoSubmitDevStageReviewIfAtDev(chain);
                boolean started = workflowService.advanceWorkflow(chain.getId(), stepIndex, finalOutput);
                publishAdvanced(chain, stepIndex, started);
            }
            case QA -> routeOnQaVerdict(chain, stepIndex, finalOutput);
            default -> {
                boolean started = workflowService.advanceWorkflow(chain.getId(), stepIndex, finalOutput);
                publishAdvanced(chain, stepIndex, started);
            }
        }
    }

    /**
     * Auto-submit the dev-stage DoD review when the record is still at {@code dev}
     * (deterministic stage progression, no model dependency). On DEFECT rework the
     * record is already at {@code qa} — nothing is re-submitted. Chains without a
     * DoD record (non-SDD chains with a DEV-kind step) skip the review silently.
     */
    private void autoSubmitDevStageReviewIfAtDev(WorkflowChain chain) {
        try {
            DoDRecord record = dodService.getStatus(chain.getId().toString());
            if (record != null && "dev".equals(record.getCurrentStage())) {
                dodService.submitStageReview(chain.getId().toString(), "engine", "SDD Engine",
                        true, "auto: dev step completed");
            }
        } catch (IllegalStateException e) {
            log.debug("No DoD record for chain {} (non-SDD chain); skipping dev review", chain.getId());
        }
    }

    /** Route a completed QA step on its latest recorded qa-stage verdict. */
    private void routeOnQaVerdict(WorkflowChain chain, int stepIndex, String finalOutput) {
        DoDRecord record;
        try {
            record = dodService.getStatus(chain.getId().toString());
        } catch (IllegalStateException e) {
            // QA-kind step without a DoD record (chain built outside instantiateTemplate).
            workflowService.markStepFailed(chain.getId(), stepIndex,
                    "QA completed but no DoD record");
            return;
        }
        DoDStageReview latest = dodService.latestQaReview(record);
        if (latest == null || latest.getVerdict() == null) {
            workflowService.markStepFailed(chain.getId(), stepIndex,
                    "QA completed but no verdict submitted");
            return;
        }
        String verdict = latest.getVerdict().toUpperCase(Locale.ROOT);
        switch (verdict) {
            case "PASS" -> {
                storeQaReportIdIfPresent(chain, finalOutput);
                boolean started = workflowService.advanceWorkflow(chain.getId(), stepIndex, finalOutput);
                publishAdvanced(chain, stepIndex, started);
            }
            case "DEFECT" -> {
                int devIdx = workflowService.findStepIndexByKind(chain, WorkflowStep.StepKind.DEV);
                if (devIdx < 0) {
                    workflowService.markStepFailed(chain.getId(), stepIndex,
                            "DEFECT verdict but chain has no DEV step");
                    return;
                }
                workflowService.rescheduleStep(chain.getId(), devIdx, latest.getComment());
            }
            case "SPEC_GAP" -> {
                int baIdx = workflowService.findStepIndexByKind(chain, WorkflowStep.StepKind.BA);
                if (baIdx < 0) {
                    workflowService.markStepFailed(chain.getId(), stepIndex,
                            "SPEC_GAP verdict but chain has no BA step");
                    return;
                }
                workflowService.rescheduleStep(chain.getId(), baIdx, latest.getComment());
            }
            default -> workflowService.markStepFailed(chain.getId(), stepIndex,
                    "Unknown QA verdict: " + latest.getVerdict());
        }
    }

    private void publishAdvanced(WorkflowChain chain, int stepIndex, boolean started) {
        if (started) {
            log.info("Workflow chain advanced: chain={}, next step started", chain.getId());
            eventPublisher.publishEvent(new WorkflowAdvancedEvent(
                    this, chain.getId(), chain.getName(), stepIndex, stepIndex + 1, WorkflowChain.Status.RUNNING));
        } else {
            log.info("Workflow chain completed: chain={}, all {} steps done",
                    chain.getId(), stepIndex + 1);
            eventPublisher.publishEvent(new WorkflowAdvancedEvent(
                    this, chain.getId(), chain.getName(), stepIndex, -1, WorkflowChain.Status.COMPLETED));
        }
    }

    /** Capture the QA report id from the QA run's finalOutput (convention: REPORT_ID=<uuid>). */
    private void storeQaReportIdIfPresent(WorkflowChain chain, String finalOutput) {
        if (finalOutput == null) return;
        Matcher m = REPORT_ID_PATTERN.matcher(finalOutput);
        if (m.find()) {
            try {
                chain.setReportArtifactId(UUID.fromString(m.group(1)));
                chainRepository.save(chain); // persist before advanceWorkflow reloads the chain
            } catch (IllegalArgumentException ignored) { /* malformed id - skip */ }
        }
    }
}
