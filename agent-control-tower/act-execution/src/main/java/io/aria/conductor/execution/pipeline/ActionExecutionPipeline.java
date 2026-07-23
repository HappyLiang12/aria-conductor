package io.aria.conductor.execution.pipeline;

import io.aria.conductor.agent.repository.RunRepository;
import io.aria.conductor.common.model.RunStatus;
import io.aria.conductor.execution.engine.RunContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Full 6-stage action execution pipeline (per spec §3.4):
 * <ol>
 *     <li>Classify — advisory risk classification.</li>
 *     <li>Rule verify — hard policy / budget / circuit breaker checks.</li>
 *     <li>AI verify — LLM-based safety review (skipped for LOW risk; PASS on LLM failure).</li>
 *     <li>Approval gate — human-in-the-loop sign-off when required.</li>
 *     <li>Execute (with shadow copy) — capture pre-state snapshot, then run the action.</li>
 *     <li>Audit — emit an audit-log event capturing every stage's verdict.</li>
 * </ol>
 */
@Slf4j
@Component
public class ActionExecutionPipeline {

    private final ActionClassifier classifier;
    private final RuleVerifier ruleVerifier;
    private final AiVerificationAgent aiVerificationAgent;
    private final io.aria.conductor.execution.approval.ApprovalGate approvalGate;
    private final ShadowCopyManager shadowCopyManager;
    private final ActionExecutor executor;
    private final AuditRecorder auditRecorder;
    private final RunRepository runRepository;

    public ActionExecutionPipeline(ActionClassifier classifier,
                                   RuleVerifier ruleVerifier,
                                   AiVerificationAgent aiVerificationAgent,
                                   io.aria.conductor.execution.approval.ApprovalGate approvalGate,
                                   ShadowCopyManager shadowCopyManager,
                                   ActionExecutor executor,
                                   AuditRecorder auditRecorder,
                                   RunRepository runRepository) {
        this.classifier = classifier;
        this.ruleVerifier = ruleVerifier;
        this.aiVerificationAgent = aiVerificationAgent;
        this.approvalGate = approvalGate;
        this.shadowCopyManager = shadowCopyManager;
        this.executor = executor;
        this.auditRecorder = auditRecorder;
        this.runRepository = runRepository;
    }

    public ActionResult execute(Action action, RunContext ctx) {
        log.info("Pipeline: processing action '{}' (type={})", action.name(), action.type());

        // Stage 1 — classify
        ActionClassification classification = classifier.classify(action);

        // Stage 2 — rule verify (hard block)
        RuleVerificationResult ruleResult = ruleVerifier.verify(action, classification, ctx);
        if (!ruleResult.isAllowed()) {
            log.warn("Pipeline: action '{}' blocked by rule verification: {}",
                    action.name(), ruleResult.reason());
            ActionResult result = ActionResult.blocked(ruleResult.reason());
            auditRecorder.record(action, classification, null, result, ctx);
            return result;
        }

        // Stage 3 — AI safety verify (fire-and-forget; PASS on any failure)
        AiVerificationResult aiResult = aiVerificationAgent.verify(action, classification, ctx);
        if (aiResult.isFail()) {
            log.warn("Pipeline: action '{}' blocked by AI safety: {}",
                    action.name(), aiResult.reasoning());
            ActionResult result = ActionResult.blocked("AI safety: " + aiResult.reasoning());
            auditRecorder.record(action, classification, aiResult, result, ctx);
            return result;
        }
        if (aiResult.outcome() == AiVerificationResult.VerificationOutcome.WARN) {
            log.info("Pipeline: action '{}' AI WARN — proceeding: {}",
                    action.name(), aiResult.reasoning());
        }

        // Stage 4 — approval gate (single-action; turn-level batching handled by callers
        // via ApprovalGate.requestTurnApproval).
        if (classification.requiresApproval()) {
            log.info("Pipeline: action '{}' requires approval", action.name());
            // Cosmetic: set Run.status=PAUSED for dashboard accuracy during blocking gate
            setRunStatus(ctx, RunStatus.PAUSED);
            try {
                io.aria.conductor.execution.approval.ApprovalDecision decision =
                        approvalGate.requestApproval(action, ctx);
                if (!ctx.isCancelled()) setRunStatus(ctx, RunStatus.RUNNING);
                if (!decision.isApproved()) {
                    log.warn("Pipeline: action '{}' denied by approval gate: {}",
                            action.name(), decision.reason());
                    ActionResult result = ActionResult.denied(decision.reason());
                    auditRecorder.record(action, classification, aiResult, result, ctx);
                    return result;
                }
                log.info("Pipeline: action '{}' approved", action.name());
            } catch (Exception e) {
                if (!ctx.isCancelled()) setRunStatus(ctx, RunStatus.RUNNING);
                log.error("Pipeline: approval gate error for action '{}': {}",
                        action.name(), e.getMessage(), e);
                ActionResult result = ActionResult.denied("Approval process failed: " + e.getMessage());
                auditRecorder.record(action, classification, aiResult, result, ctx);
                return result;
            }
        }

        // Stage 5 — execute (with shadow copy for reversible actions)
        if (action.isReversible()) {
            String runIdStr = ctx.getRunId() != null ? ctx.getRunId().toString() : null;
            shadowCopyManager.createShadowCopy(
                    runIdStr,
                    action.id(),
                    action.currentState(),
                    action.type() != null ? action.type().name() : null);
        }
        ActionResult result = executor.execute(action, ctx);

        // Stage 6 — audit
        auditRecorder.record(action, classification, aiResult, result, ctx);

        log.info("Pipeline: action '{}' completed with status {}", action.name(), result.status());
        return result;
    }

    /** Cosmetic: update Run.status in DB for dashboard accuracy (non-blocking, best-effort). */
    private void setRunStatus(RunContext ctx, RunStatus status) {
        try {
            if (ctx.getRunId() != null) {
                runRepository.findById(ctx.getRunId()).ifPresent(run -> {
                    run.setStatus(status);
                    runRepository.save(run);
                });
            }
        } catch (Exception e) {
            log.debug("Could not set run status to {} (non-fatal): {}", status, e.getMessage());
        }
    }
}
