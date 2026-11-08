package io.aria.conductor.agent.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.aria.conductor.agent.dto.CreateRunRequest;
import io.aria.conductor.agent.dto.CreateWorkflowRequest;
import io.aria.conductor.agent.dto.RunResponse;
import io.aria.conductor.agent.dto.WorkflowResponse;
import io.aria.conductor.agent.repository.WorkflowChainRepository;
import io.aria.conductor.common.event.WorkflowAdvancedEvent;
import io.aria.conductor.common.event.WorkflowCancelledEvent;
import io.aria.conductor.common.exception.ResourceNotFoundException;
import io.aria.conductor.common.model.WorkflowChain;
import io.aria.conductor.common.model.WorkflowStep;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
public class WorkflowService {

    private static final int MAX_OUTPUT_PREVIEW = 200;

    /** Max re-schedule attempts per step before the SDD loop is considered exhausted. */
    private static final int DEFAULT_MAX_ATTEMPTS = 3;

    /**
     * R9: separates the step's own template from the latest round's findings. Only one
     * round is carried: startStep truncates the prompt at 10,000 chars, so appending
     * every round would push the newest findings past the cut.
     */
    static final String PREVIOUS_ROUND_FEEDBACK_MARKER = "\n\nFeedback from the previous round:\n";

    /**
     * F11: the checkout-root placeholder the SDD prompt templates carry. It is
     * resolved per step from the configured run workspace root and never hardcoded,
     * because the sandbox {@code /workspace} path does not exist in HOST mode.
     */
    static final String WORKSPACE_ROOT_PLACEHOLDER = "{workspaceRoot}";

    /**
     * F11 fallback: the checkout directory relative to the step working directory
     * when no workspace root is configured. A relative path is correct in both
     * execution modes; an absolute one is only correct in one of them.
     */
    static final String DEFAULT_WORKSPACE_ROOT = "repo";

    private final WorkflowChainRepository workflowChainRepository;
    private final RunService runService;
    private final ObjectMapper objectMapper;
    private final ApplicationEventPublisher eventPublisher;
    /** Configured run workspace root; blank means "unknown, use the relative fallback". */
    private final String workspaceRoot;

    /** Test/legacy wiring: no configured workspace root, so {@code {workspaceRoot}} resolves relatively. */
    public WorkflowService(WorkflowChainRepository workflowChainRepository,
                           RunService runService,
                           ObjectMapper objectMapper,
                           ApplicationEventPublisher eventPublisher) {
        this(workflowChainRepository, runService, objectMapper, eventPublisher, "");
    }

    /**
     * Production wiring. The class has two constructors (the 4-arg one is the
     * test/legacy seam), so Spring must be told which one to autowire; without
     * this annotation the whole context fails with "No default constructor
     * found" under act-app.
     */
    @Autowired
    public WorkflowService(WorkflowChainRepository workflowChainRepository,
                           RunService runService,
                           ObjectMapper objectMapper,
                           ApplicationEventPublisher eventPublisher,
                           @Value("${aria.workflows.workspace-root:}") String workspaceRoot) {
        this.workflowChainRepository = workflowChainRepository;
        this.runService = runService;
        this.objectMapper = objectMapper;
        this.eventPublisher = eventPublisher;
        this.workspaceRoot = workspaceRoot;
    }

    /**
     * Creates a workflow chain and immediately starts the first step.
     */
    @Transactional
    public WorkflowResponse createAndStart(CreateWorkflowRequest request) {
        List<WorkflowStep> steps = request.getSteps().stream()
                .map(s -> WorkflowStep.builder()
                        .agentId(s.getAgentId())
                        .promptTemplate(s.getPromptTemplate())
                        .maxIterations(s.getMaxIterations() > 0 ? s.getMaxIterations() : 3)
                        .kind(s.getKind() != null ? s.getKind() : WorkflowStep.StepKind.GENERIC)
                        .status(WorkflowStep.Status.PENDING)
                        .build())
                .collect(Collectors.toList());

        // R-F4: SDD loop steps (BA/DEV/QA) must be created through the governed
        // instantiate_template path so the SPEC_REVIEW gate is enforced. This guard is the
        // single choke point for ALL direct callers (REST controller, Aria tool, execute-yaml).
        // WorkflowTemplateService sets allowSddSteps=true after validating an APPROVED template.
        if (!request.isAllowSddSteps() && steps.stream().anyMatch(s -> isSddKind(s.getKind()))) {
            throw new IllegalArgumentException("SDD workflow steps (BA/DEV/QA) must be created via "
                    + "instantiate_template to ensure the SPEC_REVIEW gate.");
        }

        WorkflowChain chain = WorkflowChain.builder()
                .name(sanitizeName(request.getName()))
                .description(request.getDescription())
                .status(WorkflowChain.Status.PENDING)
                .currentStepIndex(0)
                .stepsJson(serializeSteps(steps))
                .build();

        WorkflowChain saved = workflowChainRepository.save(chain);
        log.info("Workflow chain created: id={}, name={}, steps={}", saved.getId(), saved.getName(), steps.size());

        // Start the first step
        startStep(saved, 0, null);

        return toResponse(saved);
    }

    /**
     * Advances the workflow after a step's run has completed.
     * Called by WorkflowAutoChainer when a RunCompletedEvent fires.
     *
     * @return true if a next step was started, false if the chain is complete
     */
    @Transactional
    public boolean advanceWorkflow(UUID chainId, int completedStepIndex, String finalOutput) {
        WorkflowChain chain = workflowChainRepository.findById(chainId)
                .orElseThrow(() -> new ResourceNotFoundException("WorkflowChain", chainId));

        List<WorkflowStep> steps = deserializeSteps(chain.getStepsJson());

        // Update completed step
        if (completedStepIndex < steps.size()) {
            WorkflowStep completedStep = steps.get(completedStepIndex);
            completedStep.setStatus(WorkflowStep.Status.COMPLETED);
            completedStep.setOutput(finalOutput);
        }

        int nextIndex = completedStepIndex + 1;

        if (nextIndex >= steps.size()) {
            // All steps done
            chain.setStatus(WorkflowChain.Status.COMPLETED);
            chain.setCompletedAt(Instant.now());
            chain.setStepsJson(serializeSteps(steps));
            workflowChainRepository.save(chain);
            log.info("Workflow chain completed: id={}, name={}", chain.getId(), chain.getName());
            return false;
        }

        // Start next step with previous output
        chain.setCurrentStepIndex(nextIndex);
        chain.setStepsJson(serializeSteps(steps));
        workflowChainRepository.save(chain);

        startStep(chain, nextIndex, finalOutput);
        return true;
    }

    /**
     * Marks a step as failed and the chain as failed.
     */
    @Transactional
    public void markStepFailed(UUID chainId, int stepIndex, String errorMessage) {
        WorkflowChain chain = workflowChainRepository.findById(chainId)
                .orElse(null);
        if (chain == null) return;

        List<WorkflowStep> steps = deserializeSteps(chain.getStepsJson());
        if (stepIndex < steps.size()) {
            steps.get(stepIndex).setStatus(WorkflowStep.Status.FAILED);
            steps.get(stepIndex).setOutput("FAILED: " + errorMessage);
        }

        chain.setStatus(WorkflowChain.Status.FAILED);
        chain.setCompletedAt(Instant.now());
        chain.setStepsJson(serializeSteps(steps));
        workflowChainRepository.save(chain);
        log.info("Workflow chain failed: id={}, step={}", chain.getId(), stepIndex);
        // F24: the completion listener settles ask-less REVIEW cards out of the
        // column when a chain dies; without this event a failed chain left them
        // in REVIEW forever. The auto-capture listener ignores non-COMPLETED.
        eventPublisher.publishEvent(new WorkflowAdvancedEvent(this, chain.getId(), chain.getName(),
                stepIndex, -1, chain.getStatus()));
    }

    @Transactional(readOnly = true)
    public WorkflowResponse getWorkflow(UUID id) {
        WorkflowChain chain = workflowChainRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("WorkflowChain", id));
        return toResponse(chain);
    }

    @Transactional(readOnly = true)
    public List<WorkflowResponse> listWorkflows() {
        return workflowChainRepository.findAll().stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
    }

    /**
     * R6: every run id a chain's steps were dispatched with, in step order — the
     * superseded attempts of a step first, its current run last, nulls skipped.
     * {@code startStep} overwrites {@code step.runId} on each DEFECT/SPEC_GAP
     * reschedule, so {@link #getWorkflow(UUID)} alone cannot reach a superseded
     * attempt's card; this is the read-only view of that history.
     *
     * @throws ResourceNotFoundException when the chain does not exist
     */
    @Transactional(readOnly = true)
    public List<UUID> stepRunIds(UUID chainId) {
        WorkflowChain chain = workflowChainRepository.findById(chainId)
                .orElseThrow(() -> new ResourceNotFoundException("WorkflowChain", chainId));
        List<UUID> runIds = new ArrayList<>();
        for (WorkflowStep step : deserializeSteps(chain.getStepsJson())) {
            if (step.getPreviousRunIds() != null) {
                for (UUID previousRunId : step.getPreviousRunIds()) {
                    if (previousRunId != null) {
                        runIds.add(previousRunId);
                    }
                }
            }
            if (step.getRunId() != null) {
                runIds.add(step.getRunId());
            }
        }
        return runIds;
    }

    /**
     * Finds the workflow chain that a given run belongs to (by searching step runIds).
     * Returns null if the run is not part of any chain.
     */
    @Transactional(readOnly = true)
    public WorkflowChain findChainByRunId(UUID runId) {
        List<WorkflowChain> activeChains = workflowChainRepository.findByStatus(WorkflowChain.Status.RUNNING);
        // Also check PENDING and WAITING_APPROVAL chains
        activeChains.addAll(workflowChainRepository.findByStatus(WorkflowChain.Status.PENDING));
        activeChains.addAll(workflowChainRepository.findByStatus(WorkflowChain.Status.WAITING_APPROVAL));

        for (WorkflowChain chain : activeChains) {
            List<WorkflowStep> steps = deserializeSteps(chain.getStepsJson());
            for (WorkflowStep step : steps) {
                if (runId.equals(step.getRunId())) {
                    return chain;
                }
            }
        }
        return null;
    }

    /**
     * F18: true when the run is a step of ANY workflow chain, whatever the chain's
     * status. The auto-chainer owns a chain step's progression, so its card must not
     * be given a legacy review gate that nobody will ever decide.
     *
     * <p>Unlike {@link #findChainByRunId(UUID)} this scans every status on purpose:
     * the QA step's chain is already COMPLETED by the time its card settles to
     * REVIEW, and a completed chain still owns the step.
     */
    @Transactional(readOnly = true)
    public boolean isRunPartOfWorkflowChain(UUID runId) {
        if (runId == null) {
            return false;
        }
        for (WorkflowChain chain : workflowChainRepository.findAll()) {
            for (WorkflowStep step : deserializeSteps(chain.getStepsJson())) {
                if (runId.equals(step.getRunId())) {
                    return true;
                }
                // R6: a DEFECT/SPEC_GAP reschedule overwrites step.runId. The
                // superseded attempt is still a chain run — its card must not be
                // given a legacy review gate while the F24 settlement reaches it
                // through stepRunIds().
                if (step.getPreviousRunIds() != null && step.getPreviousRunIds().contains(runId)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Finds the step index for a given run within a chain.
     */
    public int findStepIndex(WorkflowChain chain, UUID runId) {
        List<WorkflowStep> steps = deserializeSteps(chain.getStepsJson());
        for (int i = 0; i < steps.size(); i++) {
            if (runId.equals(steps.get(i).getRunId())) {
                return i;
            }
        }
        return -1;
    }

    /** Null-safe accessor for a step by index. */
    public WorkflowStep stepAt(WorkflowChain chain, int index) {
        if (chain == null) return null;
        List<WorkflowStep> steps = deserializeSteps(chain.getStepsJson());
        return (index >= 0 && index < steps.size()) ? steps.get(index) : null;
    }

    // ==================== Lifecycle methods ====================

    /**
     * Cancels a running or pending workflow chain.
     */
    @Transactional
    public WorkflowResponse cancelWorkflow(UUID chainId) {
        WorkflowChain chain = workflowChainRepository.findById(chainId)
                .orElseThrow(() -> new ResourceNotFoundException("WorkflowChain", chainId));

        if (chain.getStatus() != WorkflowChain.Status.RUNNING
                && chain.getStatus() != WorkflowChain.Status.PENDING
                && chain.getStatus() != WorkflowChain.Status.WAITING_APPROVAL) {
            throw new IllegalArgumentException(
                    "Cannot cancel workflow in status " + chain.getStatus()
                            + "; must be RUNNING, PENDING or WAITING_APPROVAL");
        }

        List<WorkflowStep> steps = deserializeSteps(chain.getStepsJson());
        if (chain.getCurrentStepIndex() < steps.size()) {
            steps.get(chain.getCurrentStepIndex()).setStatus(WorkflowStep.Status.SKIPPED);
        }

        chain.setStatus(WorkflowChain.Status.CANCELLED);
        chain.setCompletedAt(Instant.now());
        chain.setStepsJson(serializeSteps(steps));
        workflowChainRepository.save(chain);

        log.info("Workflow chain cancelled: id={}, name={}", chain.getId(), chain.getName());
        eventPublisher.publishEvent(new WorkflowCancelledEvent(this, chain.getId(), chain.getName()));

        return toResponse(chain);
    }

    /**
     * Retries a failed step in a failed workflow chain.
     */
    @Transactional
    public WorkflowResponse retryStep(UUID chainId, int stepIndex) {
        WorkflowChain chain = workflowChainRepository.findById(chainId)
                .orElseThrow(() -> new ResourceNotFoundException("WorkflowChain", chainId));

        if (chain.getStatus() != WorkflowChain.Status.FAILED) {
            throw new IllegalArgumentException(
                    "Cannot retry step: workflow status is " + chain.getStatus() + ", must be FAILED");
        }

        List<WorkflowStep> steps = deserializeSteps(chain.getStepsJson());

        if (stepIndex < 0 || stepIndex >= steps.size()) {
            throw new IllegalArgumentException(
                    "Step index " + stepIndex + " is out of range (0.." + (steps.size() - 1) + ")");
        }

        WorkflowStep step = steps.get(stepIndex);
        if (step.getStatus() != WorkflowStep.Status.FAILED) {
            throw new IllegalArgumentException(
                    "Step " + stepIndex + " status is " + step.getStatus() + ", must be FAILED to retry");
        }

        // Reset the failed step
        step.setStatus(WorkflowStep.Status.PENDING);
        step.setRunId(null);
        step.setOutput(null);

        chain.setStatus(WorkflowChain.Status.RUNNING);
        chain.setCompletedAt(null);
        chain.setStepsJson(serializeSteps(steps));
        workflowChainRepository.save(chain);

        // Determine previous step output
        String previousOutput = (stepIndex > 0) ? steps.get(stepIndex - 1).getOutput() : "";

        startStep(chain, stepIndex, previousOutput);

        log.info("Workflow step retried: chain={}, step={}", chain.getId(), stepIndex);
        eventPublisher.publishEvent(new WorkflowAdvancedEvent(this, chain.getId(), chain.getName(),
                stepIndex, stepIndex, chain.getStatus()));

        return toResponse(chain);
    }

    /**
     * Re-runs a step within the same chain (SDD DEFECT / SPEC_GAP loop-back).
     * Increments the attempt counter, appends feedback to the prompt, starts a new run.
     * Fails the chain if maxAttempts is exceeded.
     */
    @Transactional
    public WorkflowResponse rescheduleStep(UUID chainId, int stepIndex, String feedback) {
        WorkflowChain chain = workflowChainRepository.findById(chainId)
                .orElseThrow(() -> new ResourceNotFoundException("WorkflowChain", chainId));
        if (chain.getStatus() != WorkflowChain.Status.RUNNING
                && chain.getStatus() != WorkflowChain.Status.PENDING
                && chain.getStatus() != WorkflowChain.Status.WAITING_APPROVAL) {
            throw new IllegalArgumentException(
                    "Cannot reschedule step: workflow status is " + chain.getStatus()
                            + "; must be RUNNING, PENDING or WAITING_APPROVAL");
        }
        List<WorkflowStep> steps = deserializeSteps(chain.getStepsJson());
        if (stepIndex < 0 || stepIndex >= steps.size()) {
            throw new IllegalArgumentException("Step index out of range: " + stepIndex);
        }
        WorkflowStep step = steps.get(stepIndex);
        if (step.getAttemptCount() >= DEFAULT_MAX_ATTEMPTS) {
            chain.setStatus(WorkflowChain.Status.FAILED);
            chain.setCompletedAt(Instant.now());
            chain.setStepsJson(serializeSteps(steps));
            workflowChainRepository.save(chain);
            log.info("SDD loop exhausted: chain={} step={} attempts={}", chainId, stepIndex, step.getAttemptCount());
            // R8: same contract as markStepFailed - the completion listener settles the
            // dead chain's cards and other listeners learn the chain died. Without it an
            // exhausted SDD loop left its step cards in REVIEW forever.
            eventPublisher.publishEvent(new WorkflowAdvancedEvent(this, chain.getId(), chain.getName(),
                    stepIndex, -1, chain.getStatus()));
            return toResponse(chain);
        }
        step.setAttemptCount(step.getAttemptCount() + 1);
        step.setStatus(WorkflowStep.Status.PENDING);
        // R6: startStep is about to overwrite step.runId; keep the attempt it replaces
        // so the completion listener can still settle that attempt's card.
        if (step.getRunId() != null) {
            List<UUID> history = step.getPreviousRunIds() == null
                    ? new ArrayList<>()
                    : new ArrayList<>(step.getPreviousRunIds());
            history.add(step.getRunId());
            step.setPreviousRunIds(history);
        }
        // R9: carry only the latest round's findings. The stale ones are dropped before
        // the new section is appended, because startStep truncates the prompt at 10,000
        // chars and accumulated rounds would cut the newest findings out of the head.
        String base = step.getPromptTemplate() != null ? step.getPromptTemplate() : "";
        int staleFeedback = base.indexOf(PREVIOUS_ROUND_FEEDBACK_MARKER);
        if (staleFeedback >= 0) {
            base = base.substring(0, staleFeedback);
        }
        String augmented = feedback != null && !feedback.isBlank()
                ? base + PREVIOUS_ROUND_FEEDBACK_MARKER + feedback
                : base;
        step.setPromptTemplate(augmented);
        chain.setStepsJson(serializeSteps(steps));
        workflowChainRepository.save(chain);
        startStep(chain, stepIndex, null);
        log.info("SDD step rescheduled: chain={} step={} attempt={}", chainId, stepIndex, step.getAttemptCount());
        return toResponse(chain);
    }

    /**
     * Find the index of the first step with the given kind, or -1.
     * Legacy steps deserialized from steps_json may carry a null kind; those are treated as GENERIC.
     */
    public int findStepIndexByKind(WorkflowChain chain, WorkflowStep.StepKind kind) {
        List<WorkflowStep> steps = deserializeSteps(chain.getStepsJson());
        for (int i = 0; i < steps.size(); i++) {
            WorkflowStep.StepKind stepKind = steps.get(i).getKind();
            if (stepKind == null) stepKind = WorkflowStep.StepKind.GENERIC;
            if (stepKind == kind) return i;
        }
        return -1;
    }

    /**
     * Updates a pending or failed workflow chain's metadata and/or appends new steps.
     */
    @Transactional
    public WorkflowResponse updateWorkflow(UUID chainId, String name, String description,
                                           List<CreateWorkflowRequest.StepDef> appendSteps) {
        WorkflowChain chain = workflowChainRepository.findById(chainId)
                .orElseThrow(() -> new ResourceNotFoundException("WorkflowChain", chainId));

        if (chain.getStatus() != WorkflowChain.Status.PENDING
                && chain.getStatus() != WorkflowChain.Status.FAILED) {
            throw new IllegalArgumentException(
                    "Cannot update workflow in status " + chain.getStatus() + "; must be PENDING or FAILED");
        }

        if (name != null) {
            chain.setName(name);
        }
        if (description != null) {
            chain.setDescription(description);
        }

        if (appendSteps != null && !appendSteps.isEmpty()) {
            List<WorkflowStep> steps = deserializeSteps(chain.getStepsJson());
            for (CreateWorkflowRequest.StepDef sd : appendSteps) {
                steps.add(WorkflowStep.builder()
                        .agentId(sd.getAgentId())
                        .promptTemplate(sd.getPromptTemplate())
                        .maxIterations(sd.getMaxIterations() > 0 ? sd.getMaxIterations() : 3)
                        .kind(sd.getKind() != null ? sd.getKind() : WorkflowStep.StepKind.GENERIC)
                        .status(WorkflowStep.Status.PENDING)
                        .build());
            }
            chain.setStepsJson(serializeSteps(steps));
        }

        workflowChainRepository.save(chain);
        log.info("Workflow chain updated: id={}, name={}", chain.getId(), chain.getName());

        return toResponse(chain);
    }

    /**
     * Deletes a workflow chain (cannot delete a RUNNING chain).
     */
    @Transactional
    public void deleteWorkflow(UUID chainId) {
        WorkflowChain chain = workflowChainRepository.findById(chainId)
                .orElseThrow(() -> new ResourceNotFoundException("WorkflowChain", chainId));

        if (chain.getStatus() == WorkflowChain.Status.RUNNING) {
            throw new IllegalArgumentException("Cannot delete a RUNNING workflow");
        }

        workflowChainRepository.delete(chain);
        log.info("Workflow chain deleted: id={}, name={}", chain.getId(), chain.getName());
    }

    /**
     * Merges multiple workflow chains into a single new chain by concatenating their steps.
     */
    @Transactional
    public WorkflowResponse mergeWorkflows(List<UUID> sourceIds, String name) {
        if (sourceIds == null || sourceIds.size() < 2) {
            throw new IllegalArgumentException("At least 2 source workflow IDs are required to merge");
        }

        List<WorkflowStep> mergedSteps = new ArrayList<>();
        for (UUID sourceId : sourceIds) {
            WorkflowChain source = workflowChainRepository.findById(sourceId)
                    .orElseThrow(() -> new ResourceNotFoundException("WorkflowChain", sourceId));
            List<WorkflowStep> steps = deserializeSteps(source.getStepsJson());
            for (WorkflowStep step : steps) {
                step.setStatus(WorkflowStep.Status.PENDING);
                step.setRunId(null);
                step.setOutput(null);
                mergedSteps.add(step);
            }
        }

        List<CreateWorkflowRequest.StepDef> stepDefs = mergedSteps.stream()
                .map(s -> CreateWorkflowRequest.StepDef.builder()
                        .agentId(s.getAgentId())
                        .promptTemplate(s.getPromptTemplate())
                        .maxIterations(s.getMaxIterations())
                        .kind(s.getKind())
                        .build())
                .collect(Collectors.toList());

        CreateWorkflowRequest request = CreateWorkflowRequest.builder()
                .name(name)
                .steps(stepDefs)
                .build();

        log.info("Merging {} workflows into new workflow: name={}", sourceIds.size(), name);
        return createAndStart(request);
    }

    /**
     * Creates a new workflow from a template chain, substituting parameter placeholders.
     */
    @Transactional
    public WorkflowResponse createFromTemplate(UUID templateChainId, Map<String, String> parameters) {
        WorkflowChain template = workflowChainRepository.findById(templateChainId)
                .orElseThrow(() -> new ResourceNotFoundException("WorkflowChain", templateChainId));

        if (!template.isTemplate()) {
            throw new IllegalArgumentException("WorkflowChain " + templateChainId + " is not a template");
        }

        List<WorkflowStep> templateSteps = deserializeSteps(template.getStepsJson());

        List<CreateWorkflowRequest.StepDef> stepDefs = new ArrayList<>();
        for (WorkflowStep step : templateSteps) {
            String prompt = step.getPromptTemplate();
            if (parameters != null) {
                for (Map.Entry<String, String> entry : parameters.entrySet()) {
                    prompt = prompt.replace("{" + entry.getKey() + "}", entry.getValue());
                }
            }
            stepDefs.add(CreateWorkflowRequest.StepDef.builder()
                    .agentId(step.getAgentId())
                    .promptTemplate(prompt)
                    .maxIterations(step.getMaxIterations())
                    .kind(step.getKind())
                    .build());
        }

        CreateWorkflowRequest request = CreateWorkflowRequest.builder()
                .name(template.getName())
                .steps(stepDefs)
                .build();

        WorkflowResponse response = createAndStart(request);

        // Copy knowledgeItemId from template to the new chain
        WorkflowChain newChain = workflowChainRepository.findById(response.getId())
                .orElseThrow(() -> new ResourceNotFoundException("WorkflowChain", response.getId()));
        newChain.setSourceKnowledgeItemId(template.getKnowledgeItemId());
        workflowChainRepository.save(newChain);

        log.info("Workflow created from template: templateId={}, newId={}", templateChainId, response.getId());
        return toResponse(newChain);
    }

    // ==================== Internal helpers ====================

    /** Sanitizes a chain name to printable ASCII, replacing any non-ASCII chars with "-". */
    private static String sanitizeName(String name) {
        if (name == null || name.isBlank()) return "workflow";
        return name.replaceAll("[^\\x20-\\x7E]", "-").trim();
    }

    /** @return true when the step kind is an SDD loop kind (BA/DEV/QA). */
    private static boolean isSddKind(WorkflowStep.StepKind kind) {
        return kind == WorkflowStep.StepKind.BA
                || kind == WorkflowStep.StepKind.DEV
                || kind == WorkflowStep.StepKind.QA;
    }

    private void startStep(WorkflowChain chain, int stepIndex, String previousOutput) {
        List<WorkflowStep> steps = deserializeSteps(chain.getStepsJson());
        if (stepIndex >= steps.size()) return;

        WorkflowStep step = steps.get(stepIndex);
        String prompt = step.getPromptTemplate();

        // Substitute {previousOutput} with the actual output from the previous step
        if (previousOutput != null) {
            prompt = prompt.replace("{previousOutput}", previousOutput);
        }

        // F11: resolve the checkout root for THIS run. The prompt templates must not
        // carry a fixed absolute path — /workspace exists only inside the sandbox.
        prompt = applyWorkspaceRoot(prompt);

        // Truncate very long prompts (H2 TEXT column limit)
        if (prompt.length() > 10_000) {
            prompt = prompt.substring(0, 10_000) + "\n\n[truncated]";
        }

        try {
            CreateRunRequest runReq = CreateRunRequest.builder()
                    .agentId(step.getAgentId())
                    .promptSeed(prompt)
                    .maxIterations(step.getMaxIterations())
                    .build();

            RunResponse run = runService.createRun(runReq);
            step.setRunId(run.getId());
            step.setStatus(WorkflowStep.Status.RUNNING);

            chain.setStatus(WorkflowChain.Status.RUNNING);
            chain.setCurrentStepIndex(stepIndex);
            chain.setStepsJson(serializeSteps(steps));
            workflowChainRepository.save(chain);

            log.info("Workflow step started: chain={}, step={}, runId={}",
                    chain.getId(), stepIndex, run.getId());
        } catch (RuntimeException e) {
            // A step can fail to *start* only when its run cannot be created — e.g. the
            // agentId does not resolve. createRun runs in the same transaction, so its
            // exception has already marked the tx rollback-only; catching-then-saving a
            // FAILED chain here would throw UnexpectedRollbackException at commit (HTTP 500).
            // Instead let it propagate so the transaction rolls back cleanly and the caller
            // surfaces the underlying 4xx (e.g. 404 for an unknown agent), matching the
            // execute-yaml path. Runs that fail *during execution* are still marked FAILED
            // asynchronously via WorkflowAutoChainer#markStepFailed.
            log.warn("Failed to start workflow step: chain={}, step={}: {}",
                    chain.getId(), stepIndex, e.getMessage());
            throw e;
        }
    }

    /**
     * Resolve the configured run workspace root, or the relative fallback when it is
     * unknown. Callers never see a blank value.
     */
    public String resolveWorkspaceRoot() {
        return (workspaceRoot == null || workspaceRoot.isBlank())
                ? DEFAULT_WORKSPACE_ROOT
                : workspaceRoot.trim();
    }

    /**
     * F11: replace every {@code {workspaceRoot}} with the resolved checkout root.
     * When the root is relative (the configured value is unknown), the prompt gains
     * one explicit sentence naming the relationship to the working directory — the
     * live HOST run otherwise guessed an absolute path and lost the clone to
     * {@code Permission denied}.
     */
    String applyWorkspaceRoot(String prompt) {
        if (prompt == null || !prompt.contains(WORKSPACE_ROOT_PLACEHOLDER)) {
            return prompt;
        }
        String root = resolveWorkspaceRoot();
        String resolved = prompt.replace(WORKSPACE_ROOT_PLACEHOLDER, root);
        if (isAbsolutePath(root)) {
            return resolved;
        }
        return resolved + "\n\nWorkspace note: the checkout root resolved to the relative"
                + " directory '" + root + "' under your current working directory (the"
                + " configured aria.workflows.workspace-root is unset or relative). Do not"
                + " create or use an absolute checkout directory.";
    }

    private static boolean isAbsolutePath(String path) {
        return path.startsWith("/") || path.startsWith("\\")
                || (path.length() >= 2 && path.charAt(1) == ':');
    }

    public String serializeSteps(List<WorkflowStep> steps) {
        try {
            return objectMapper.writeValueAsString(steps);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize workflow steps", e);
        }
    }

    public List<WorkflowStep> deserializeSteps(String json) {
        if (json == null || json.isBlank()) return new ArrayList<>();
        try {
            return objectMapper.readValue(json, new TypeReference<List<WorkflowStep>>() {});
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to deserialize workflow steps", e);
        }
    }

    private WorkflowResponse toResponse(WorkflowChain chain) {
        List<WorkflowStep> steps = deserializeSteps(chain.getStepsJson());
        List<WorkflowResponse.StepInfo> stepInfos = new ArrayList<>();
        for (int i = 0; i < steps.size(); i++) {
            WorkflowStep s = steps.get(i);
            String preview = s.getOutput();
            if (preview != null && preview.length() > MAX_OUTPUT_PREVIEW) {
                preview = preview.substring(0, MAX_OUTPUT_PREVIEW) + "...";
            }
            stepInfos.add(WorkflowResponse.StepInfo.builder()
                    .index(i)
                    .agentId(s.getAgentId())
                    .promptTemplate(s.getPromptTemplate().length() > 80
                            ? s.getPromptTemplate().substring(0, 80) + "..."
                            : s.getPromptTemplate())
                    .status(s.getStatus())
                    .runId(s.getRunId())
                    .outputPreview(preview)
                    .build());
        }

        return WorkflowResponse.builder()
                .id(chain.getId())
                .name(chain.getName())
                .status(chain.getStatus())
                .currentStepIndex(chain.getCurrentStepIndex())
                .totalSteps(steps.size())
                .steps(stepInfos)
                .createdAt(chain.getCreatedAt())
                .completedAt(chain.getCompletedAt())
                .isTemplate(chain.isTemplate())
                .knowledgeItemId(chain.getKnowledgeItemId())
                .description(chain.getDescription())
                .build();
    }
}
