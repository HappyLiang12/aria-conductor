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
import io.aria.conductor.execution.git.GitHandoffMetadata;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
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
 *
 * <p>A QA PASS carries two gates before any PASS side effect: F22 refuses it while the
 * run's own output records an acceptance criterion as NOT VERIFIED, and F19 refuses it
 * when the commit it pushed on {@code sdd/<chainId>} removed files from the branch tree
 * (the live QA commit 984df552 removed 300). Both fail the step instead of advancing.
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
    /** F19: supplies the QA step's branch tree move; null disables the guard. */
    private final ReportStepTreeGuard.TreeDiffSupplier treeDiffSupplier;
    private final ReportStepTreeGuard treeGuard = new ReportStepTreeGuard();

    /**
     * Production wiring. The tree-diff supplier is optional so the listener still works
     * in a context without the GitHub-backed supplier (and in the existing tests).
     */
    @Autowired
    public WorkflowAutoChainer(WorkflowService workflowService,
                               DoDService dodService,
                               WorkflowChainRepository chainRepository,
                               ApplicationEventPublisher eventPublisher,
                               ObjectProvider<ReportStepTreeGuard.TreeDiffSupplier> treeDiffSupplier) {
        this(workflowService, dodService, chainRepository, eventPublisher,
                treeDiffSupplier.getIfAvailable());
    }

    /** Wiring without the F19 tree guard (non-Git chains and the pre-guard tests). */
    public WorkflowAutoChainer(WorkflowService workflowService,
                               DoDService dodService,
                               WorkflowChainRepository chainRepository,
                               ApplicationEventPublisher eventPublisher) {
        this(workflowService, dodService, chainRepository, eventPublisher,
                (ReportStepTreeGuard.TreeDiffSupplier) null);
    }

    /** Test seam: the tree diff is supplied explicitly (a fake, or null to disable). */
    public WorkflowAutoChainer(WorkflowService workflowService,
                               DoDService dodService,
                               WorkflowChainRepository chainRepository,
                               ApplicationEventPublisher eventPublisher,
                               ReportStepTreeGuard.TreeDiffSupplier treeDiffSupplier) {
        this.workflowService = workflowService;
        this.dodService = dodService;
        this.chainRepository = chainRepository;
        this.eventPublisher = eventPublisher;
        this.treeDiffSupplier = treeDiffSupplier;
    }

    /** F24 ordering: runs after the kanban mirror (order 0) queued the card move. */
    @org.springframework.core.annotation.Order(100)
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
        // The VERDICT= marker in the QA run's finalOutput is the fresh, authoritative
        // signal for THIS run, so it must be routed first. R9-F5 records the marker verdict
        // onto the DoD qa review for auditability, which leaves a non-null (stale) verdict on
        // the record; reading the DoD review first would re-route a previous DEFECT/SPEC_GAP
        // verdict and ignore this run's fresh PASS marker (reschedule never completes).
        Optional<String> marker = parseVerdictMarker(finalOutput);
        DoDStageReview latest = dodService.latestQaReview(record);
        if (marker.isPresent()) {
            // F22: the marker is the authoritative verdict, but a review submitted by
            // this same run can still record a criterion as NOT VERIFIED. Scan its
            // comment/evidence only when it agrees with the marker, so a stale review
            // from an earlier DEFECT attempt cannot block a fresh PASS.
            String gateComment = null;
            String gateEvidence = null;
            if (latest != null && latest.getVerdict() != null
                    && latest.getVerdict().equalsIgnoreCase(marker.get())) {
                gateComment = latest.getComment();
                gateEvidence = latest.getEvidence();
            }
            applyVerdict(chain, stepIndex, marker.get(), markerFeedback(marker.get(), finalOutput),
                    finalOutput, gateComment, gateEvidence);
            return;
        }
        // No marker — fall back to the tool-submitted qa-stage verdict (submit_dod_review).
        // The tool path can carry the criterion status in its comment or evidence, so
        // the F22 gate reads those too (a PASS submitted through the tool is not a
        // second-class verdict with a weaker gate).
        if (latest != null && latest.getVerdict() != null) {
            String verdict = latest.getVerdict().toUpperCase(Locale.ROOT);
            applyVerdict(chain, stepIndex, verdict, latest.getComment(), finalOutput,
                    latest.getEvidence());
            return;
        }
        // F17: give the operator an actionable retry hint instead of a bare failure message.
        workflowService.markStepFailed(chain.getId(), stepIndex,
                "QA completed but no verdict submitted. The QA agent must call submit_dod_review "
                        + "with verdict=PASS|DEFECT|SPEC_GAP before finishing. "
                        + "Retry the step after fixing the QA tool configuration.");
    }

    /**
     * F22 gate: a PASS verdict may not stand while the QA run's output or its
     * submitted review records an acceptance criterion as NOT VERIFIED — the live
     * run 8c51fa8f reported AC4 "NOT VERIFIED (environment limitation)" next to
     * `VERDICT=PASS` and the chain completed. The matcher below needs an AC context,
     * so a PASS that merely mentions the phrase elsewhere is not rejected.
     */
    /** An acceptance-criterion reference: AC4, AC 4, ACs 1-4, Acceptance Criteria. */
    private static final Pattern AC_CONTEXT = Pattern.compile(
            "(?i)(?:\\bACs?\\s*\\d+(?:\\s*[-–]\\s*\\d+)?\\b|\\bAC[-\\s]?\\d+\\b"
                    + "|\\bAcceptance\\s+Criteri(?:on|a)\\b)");
    /** The unverified phrase, including the common "not yet verified" variant. */
    private static final Pattern NOT_VERIFIED = Pattern.compile(
            "(?i)\\bnot\\s+(?:yet\\s+)?verified\\b");
    /**
     * A line that records a criterion as later resolved ("... was NOT VERIFIED on the
     * first attempt but passed on re-run") is a historical note, not a live gap.
     */
    private static final Pattern RESOLVED_AFTER = Pattern.compile(
            "(?i)\\b(?:first\\s+attempt|initially|earlier|previous(?:ly)?|re-?run|retry)\\b"
                    + "[^\\n]{0,80}?\\b(?:but|however|then)\\b[^\\n]{0,60}?\\b(?:pass(?:ed)?|verified|now)\\b");

    /**
     * Apply a normalized QA verdict ({@code PASS}/{@code DEFECT}/{@code SPEC_GAP}) to the chain.
     * Shared by the tool-verdict path and the VERDICT= marker fallback so both route identically.
     */
    private void applyVerdict(WorkflowChain chain, int stepIndex, String verdict, String reason,
            String finalOutput, String... extraTexts) {
        switch (verdict) {
            case "PASS" -> {
                // F22: checked before any PASS side effect (DoD review, report capture, advance).
                Optional<String> unverified = findUnverifiedAcceptanceCriterion(
                        concatGateTexts(finalOutput, reason, extraTexts));
                if (unverified.isPresent()) {
                    workflowService.markStepFailed(chain.getId(), stepIndex,
                            "QA reported VERDICT=PASS but the acceptance criterion '"
                                    + unverified.get() + "' is not verified. A PASS requires every"
                                    + " criterion to be verified: run the missing check, or report"
                                    + " VERDICT=DEFECT. Retry the QA step after fixing the environment.");
                    return;
                }
                // F19: the step must not have destroyed the branch tree it reported on.
                Optional<String> treeViolation = reportTreeViolation(chain);
                if (treeViolation.isPresent()) {
                    workflowService.markStepFailed(chain.getId(), stepIndex, treeViolation.get());
                    return;
                }
                recordQaVerdictReview(chain, true, verdict);
                storeQaReportIdIfPresent(chain, finalOutput);
                boolean started = workflowService.advanceWorkflow(chain.getId(), stepIndex, finalOutput);
                publishAdvanced(chain, stepIndex, started);
            }
            case "DEFECT" -> {
                recordQaVerdictReview(chain, false, verdict);
                int devIdx = workflowService.findStepIndexByKind(chain, WorkflowStep.StepKind.DEV);
                if (devIdx < 0) {
                    workflowService.markStepFailed(chain.getId(), stepIndex,
                            "DEFECT verdict but chain has no DEV step");
                    return;
                }
                workflowService.rescheduleStep(chain.getId(), devIdx, reason);
            }
            case "SPEC_GAP" -> {
                recordQaVerdictReview(chain, false, verdict);
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
     * Record the qa-stage DoD review for a VERDICT= marker routing (R8-F2).
     * Mirrors the tool path (submit_dod_review), which records a qa review for
     * every verdict — PASS maps to {@code passed=true}, DEFECT/SPEC_GAP to false.
     * R9-F5: the verdict STRING is now populated too (auditability), matching the
     * tool path which writes the normalized verdict on the DoDStageReview.
     * Guarded like {@link #autoSubmitDevStageReviewIfAtDev}: a missing or already
     * completed DoD record must never crash the chain.
     */
    private void recordQaVerdictReview(WorkflowChain chain, boolean approved, String verdict) {
        try {
            dodService.review(chain.getId().toString(), "engine", "SDD Engine",
                    approved, null, "verdict from output marker", verdict);
        } catch (IllegalStateException e) {
            log.debug("Cannot record qa verdict review for chain {}: {}", chain.getId(), e.getMessage());
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

    /**
     * F22: the first acceptance criterion recorded as NOT VERIFIED in any of the QA
     * verdict texts (run output, the submitted review's comment, its evidence), as a
     * short quotable excerpt, or empty when every criterion is accounted for.
     *
     * <p>The AC context may precede or follow the phrase and may be split over two
     * lines ({@code AC4} / {@code Status: NOT VERIFIED}); the phrase may be
     * {@code NOT VERIFIED} or {@code not yet verified}. A report that merely
     * discusses the phrase without tying it to a criterion is not blocked, and a
     * criterion recorded as later resolved ("... but passed on re-run") is a
     * historical note rather than a live gap.
     */
    static Optional<String> findUnverifiedAcceptanceCriterion(String... texts) {
        if (texts == null) {
            return Optional.empty();
        }
        for (String text : texts) {
            if (text == null || text.isBlank()) {
                continue;
            }
            String[] lines = text.split("\\R");
            for (int index = 0; index < lines.length; index++) {
                String line = lines[index];
                if (isUnverifiedCriterion(line)) {
                    return excerpt(line);
                }
                if (index + 1 < lines.length) {
                    String next = lines[index + 1];
                    // A status line under the criterion: "AC4" / "Status: NOT VERIFIED".
                    // A generic next-line mention ("Note: ... NOT VERIFIED, out of
                    // scope") is not a criterion status and must not block a PASS.
                    if (AC_CONTEXT.matcher(line).find() && isCriterionStatusLine(next)) {
                        return excerpt(line + " " + next);
                    }
                    if (NOT_VERIFIED.matcher(line).find()
                            && AC_CONTEXT.matcher(next).find()
                            && startsWithAcceptanceContext(next)) {
                        return excerpt(line + " " + next);
                    }
                }
            }
        }
        return Optional.empty();
    }

    private static String[] concatGateTexts(String finalOutput, String reason, String[] extraTexts) {
        java.util.List<String> texts = new java.util.ArrayList<>(2 + (extraTexts == null ? 0 : extraTexts.length));
        texts.add(finalOutput);
        texts.add(reason);
        if (extraTexts != null) {
            java.util.Collections.addAll(texts, extraTexts);
        }
        return texts.toArray(String[]::new);
    }

    /** Bound of the QA output carried into a DEFECT/SPEC_GAP reschedule feedback. */
    static final int FEEDBACK_MAX_CHARS = 6_000;

    /**
     * F26: a DEFECT/SPEC_GAP reschedule hands its reason to the next DEV/BA run as
     * the retry prompt's feedback. The marker path has no review comment, so the
     * reason must carry the QA run's own report; the previous literal
     * "verdict from output marker" told the retry nothing it could act on.
     */
    private static String markerFeedback(String verdict, String finalOutput) {
        String text = finalOutput == null ? "" : finalOutput.trim();
        if (text.length() > FEEDBACK_MAX_CHARS) {
            text = text.substring(0, FEEDBACK_MAX_CHARS) + "\n[... truncated]";
        }
        return text.isEmpty()
                ? "VERDICT=" + verdict
                : "VERDICT=" + verdict + " from the QA run. The QA report says:\n" + text;
    }

    private static boolean isUnverifiedCriterion(String line) {
        return AC_CONTEXT.matcher(line).find()
                && NOT_VERIFIED.matcher(line).find()
                && !RESOLVED_AFTER.matcher(line).find();
    }

    /** A continuation line that states the criterion's status ("Status: NOT VERIFIED"). */
    private static boolean isCriterionStatusLine(String line) {
        String trimmed = stripListMarker(line);
        if (!NOT_VERIFIED.matcher(trimmed).find()) {
            return false;
        }
        return trimmed.regionMatches(true, 0, "NOT VERIFIED", 0, "NOT VERIFIED".length())
                || trimmed.matches("(?i)^(?:status|result|verification|outcome)\\b.*");
    }

    /** A line whose first token is the criterion reference (not prose mentioning it). */
    private static boolean startsWithAcceptanceContext(String line) {
        String trimmed = stripListMarker(line);
        Matcher matcher = AC_CONTEXT.matcher(trimmed);
        return matcher.find() && matcher.start() == 0;
    }

    private static String stripListMarker(String line) {
        return line.replaceFirst("^\\s*[-*\\u2022]\\s*", "").trim();
    }

    private static Optional<String> excerpt(String text) {
        String excerpt = text.replaceAll("\\s+", " ").trim();
        return Optional.of(excerpt.length() > 80 ? excerpt.substring(0, 80) + "..." : excerpt);
    }

    /**
     * F19: judge the tree move the QA step pushed on its {@code sdd/<chainId>} branch.
     *
     * <p>Empty when the chain carries no {@code repoUrl} (a non-Git SDD flow keeps its
     * previous behaviour), when no supplier is wired, or when the diff cannot be read —
     * an infrastructure error must not fail a step. A supplier that throws is treated
     * the same way, so a broken GitHub read can never block a legitimate PASS.
     */
    private Optional<String> reportTreeViolation(WorkflowChain chain) {
        if (treeDiffSupplier == null) {
            return Optional.empty();
        }
        String repoUrl = GitHandoffMetadata.parse(chain.getTemplateParams())
                .get(GitHandoffMetadata.KEY_REPO_URL);
        if (repoUrl == null || repoUrl.isBlank()) {
            log.debug("Chain {} has no repoUrl; skipping the QA report tree guard", chain.getId());
            return Optional.empty();
        }
        String branchName = GitHandoffMetadata.branchName(chain.getId());
        try {
            return treeDiffSupplier.diffFor(repoUrl, branchName).flatMap(treeGuard::violation);
        } catch (RuntimeException e) {
            log.warn("Could not read the tree diff of branch {} for chain {} — the QA step continues"
                    + " unguarded: {}", branchName, chain.getId(), e.getMessage());
            return Optional.empty();
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
