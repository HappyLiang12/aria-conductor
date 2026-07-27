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

    private final WorkflowChainRepository workflowChainRepository;
    private final RunService runService;
    private final ObjectMapper objectMapper;
    private final ApplicationEventPublisher eventPublisher;

    public WorkflowService(WorkflowChainRepository workflowChainRepository,
                           RunService runService,
                           ObjectMapper objectMapper,
                           ApplicationEventPublisher eventPublisher) {
        this.workflowChainRepository = workflowChainRepository;
        this.runService = runService;
        this.objectMapper = objectMapper;
        this.eventPublisher = eventPublisher;
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
                        .status(WorkflowStep.Status.PENDING)
                        .build())
                .collect(Collectors.toList());

        WorkflowChain chain = WorkflowChain.builder()
                .name(request.getName())
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
     * Finds the workflow chain that a given run belongs to (by searching step runIds).
     * Returns null if the run is not part of any chain.
     */
    @Transactional(readOnly = true)
    public WorkflowChain findChainByRunId(UUID runId) {
        List<WorkflowChain> activeChains = workflowChainRepository.findByStatus(WorkflowChain.Status.RUNNING);
        // Also check PENDING chains
        activeChains.addAll(workflowChainRepository.findByStatus(WorkflowChain.Status.PENDING));

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

    // ==================== Lifecycle methods ====================

    /**
     * Cancels a running or pending workflow chain.
     */
    @Transactional
    public WorkflowResponse cancelWorkflow(UUID chainId) {
        WorkflowChain chain = workflowChainRepository.findById(chainId)
                .orElseThrow(() -> new ResourceNotFoundException("WorkflowChain", chainId));

        if (chain.getStatus() != WorkflowChain.Status.RUNNING
                && chain.getStatus() != WorkflowChain.Status.PENDING) {
            throw new IllegalArgumentException(
                    "Cannot cancel workflow in status " + chain.getStatus() + "; must be RUNNING or PENDING");
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

    private void startStep(WorkflowChain chain, int stepIndex, String previousOutput) {
        List<WorkflowStep> steps = deserializeSteps(chain.getStepsJson());
        if (stepIndex >= steps.size()) return;

        WorkflowStep step = steps.get(stepIndex);
        String prompt = step.getPromptTemplate();

        // Substitute {previousOutput} with the actual output from the previous step
        if (previousOutput != null) {
            prompt = prompt.replace("{previousOutput}", previousOutput);
        }

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

    private String serializeSteps(List<WorkflowStep> steps) {
        try {
            return objectMapper.writeValueAsString(steps);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize workflow steps", e);
        }
    }

    private List<WorkflowStep> deserializeSteps(String json) {
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
