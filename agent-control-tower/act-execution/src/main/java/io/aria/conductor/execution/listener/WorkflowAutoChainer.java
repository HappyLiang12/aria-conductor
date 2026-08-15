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

import java.util.List;
import java.util.Locale;
import java.util.Optional;
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

        // Initialize DoD for SDD chains created via API/tool (createAndStart does
        // not wire DoD). DoD must exist before any SDD routing logic runs (e.g.
        // autoSubmitDevStageReviewIfAtDev, routeOnQaVerdict). Idempotent: if a
        // record already exists (YAML path or coordinator-initiated), this is a no-op.
        if (isSddKind(kind) && !hasDoDRecord(chain)) {
            dodService.init(chain.getId().toString(), "SDD", java.util.List.of("dev", "qa"));
        }

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

    /** Regex for the QA output fallback: VERDICT=PASS|DEFECT|SPEC_GAP (case-insensitive). */
    private static final Pattern VERDICT_MARKER_PATTERN =
            Pattern.compile("VERDICT\\s*=\\s*(PASS|DEFECT|SPEC_GAP)", Pattern.CASE_INSENSITIVE);

    /** Route a completed QA step on its latest recorded qa-stage verdict, or the output marker. */
    private void routeOnQaVerdict(WorkflowChain chain, int stepIndex, String finalOutput) {
        // Mark the QA step COMPLETED and persist its output BEFORE routing on verdict.
        // DEFECT and SPEC_GAP branches never advance (they reschedule instead), so without
        // this the QA step stays RUNNING indefinitely — the chain appears stuck.
        completeQaStep(chain, stepIndex, finalOutput);

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
        if (latest != null && latest.getVerdict() != null) {
            String verdict = latest.getVerdict().toUpperCase(Locale.ROOT);
            applyVerdict(chain, stepIndex, verdict, latest.getComment(), finalOutput);
            return;
        }
        // No tool verdict recorded — fall back to the VERDICT= marker in the QA output.
        Optional<String> marker = parseVerdictMarker(finalOutput);
        if (marker.isPresent()) {
            applyVerdict(chain, stepIndex, marker.get(), "verdict from output marker", finalOutput);
            return;
        }
        // F17: give the operator an actionable retry hint instead of a bare failure message.
        workflowService.markStepFailed(chain.getId(), stepIndex,
                "QA completed but no verdict submitted. The QA agent must call submit_dod_review "
                        + "with verdict=PASS|DEFECT|SPEC_GAP before finishing. "
                        + "Retry the step after fixing the QA tool configuration.");
    }

    /**
     * Apply a normalized QA verdict ({@code PASS}/{@code DEFECT}/{@code SPEC_GAP}) to the chain.
     * Shared by the tool-verdict path and the VERDICT= marker fallback so both route identically.
     */
    private void applyVerdict(WorkflowChain chain, int stepIndex, String verdict, String reason, String finalOutput) {
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
                workflowService.rescheduleStep(chain.getId(), devIdx, reason);
            }
            case "SPEC_GAP" -> {
                int baIdx = workflowService.findStepIndexByKind(chain, WorkflowStep.StepKind.BA);
                if (baIdx < 0) {
                    workflowService.markStepFailed(chain.getId(), stepIndex,
                            "SPEC_GAP verdict but chain has no BA step");
                    return;
                }
                workflowService.rescheduleStep(chain.getId(), baIdx, reason);
            }
            default -> workflowService.markStepFailed(chain.getId(), stepIndex,
                    "Unknown QA verdict: " + verdict);
        }
    }

    /**
     * Parse the VERDICT= marker from the QA run's finalOutput, or empty when absent.
     * Regex: {@code VERDICT\s*=\s*(PASS|DEFECT|SPEC_GAP)}, case-insensitive, first match wins.
     */
    private static Optional<String> parseVerdictMarker(String output) {
        if (output == null) {
            return Optional.empty();
        }
        Matcher matcher = VERDICT_MARKER_PATTERN.matcher(output);
        if (matcher.find()) {
            return Optional.of(matcher.group(1).toUpperCase(Locale.ROOT));
        }
        return Optional.empty();
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

    /**
     * @return true if the given kind is one of the SDD-specific kinds (BA, DEV, QA).
     */
    private static boolean isSddKind(WorkflowStep.StepKind kind) {
        return kind == WorkflowStep.StepKind.BA
                || kind == WorkflowStep.StepKind.DEV
                || kind == WorkflowStep.StepKind.QA;
    }

    /**
     * @return true if a DoD record already exists for this chain (idempotent check).
     */
    private boolean hasDoDRecord(WorkflowChain chain) {
        try {
            dodService.getStatus(chain.getId().toString());
            return true;
        } catch (IllegalStateException e) {
            return false;
        }
    }

    /** Mark the QA step as COMPLETED and persist its output. */
    private void completeQaStep(WorkflowChain chain, int stepIndex, String finalOutput) {
        List<WorkflowStep> steps = workflowService.deserializeSteps(chain.getStepsJson());
        if (stepIndex >= 0 && stepIndex < steps.size()) {
            WorkflowStep step = steps.get(stepIndex);
            step.setStatus(WorkflowStep.Status.COMPLETED);
            step.setOutput(finalOutput);
            chain.setStepsJson(workflowService.serializeSteps(steps));
            chainRepository.save(chain);
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
