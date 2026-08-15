package io.aria.conductor.knowledge.service;

import io.aria.conductor.agent.dto.CreateWorkflowRequest;
import io.aria.conductor.agent.dto.WorkflowResponse;
import io.aria.conductor.agent.repository.WorkflowChainRepository;
import io.aria.conductor.agent.service.WorkflowService;
import io.aria.conductor.common.exception.ResourceNotFoundException;
import io.aria.conductor.common.model.KnowledgeItem;
import io.aria.conductor.common.model.KnowledgeStatus;
import io.aria.conductor.common.model.KnowledgeType;
import io.aria.conductor.common.model.KnowledgeVersion;
import io.aria.conductor.common.model.WorkflowChain;
import io.aria.conductor.common.model.WorkflowStep;
import io.aria.conductor.execution.adk.opencode.OpenCodeProperties;
import io.aria.conductor.execution.dod.DoDService;
import io.aria.conductor.execution.git.GitHandoffMetadata;
import io.aria.conductor.execution.kanban.CreateKanbanItemRequest;
import io.aria.conductor.execution.kanban.KanbanService;
import io.aria.conductor.knowledge.converter.WorkflowTemplateConverter;
import io.aria.conductor.knowledge.dto.KnowledgeItemResponse;
import io.aria.conductor.knowledge.repository.KnowledgeItemRepository;
import io.aria.conductor.knowledge.repository.KnowledgeVersionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Service for discovering and instantiating APPROVED workflow templates
 * stored as knowledge items of type {@link KnowledgeType#WORKFLOW}.
 */
@Service
public class WorkflowTemplateService {

    private static final Logger log = LoggerFactory.getLogger(WorkflowTemplateService.class);

    private final KnowledgeItemRepository itemRepository;
    private final KnowledgeVersionRepository versionRepository;
    private final WorkflowTemplateConverter templateConverter;
    private final WorkflowService workflowService;
    private final WorkflowChainRepository chainRepository;
    private final KnowledgeService knowledgeService;
    private final DoDService dodService;
    private final KanbanService kanbanService;
    private final OpenCodeProperties openCodeProperties;

    public WorkflowTemplateService(KnowledgeItemRepository itemRepository,
                                   KnowledgeVersionRepository versionRepository,
                                   WorkflowTemplateConverter templateConverter,
                                   WorkflowService workflowService,
                                   WorkflowChainRepository chainRepository,
                                   KnowledgeService knowledgeService,
                                   DoDService dodService,
                                   KanbanService kanbanService,
                                   OpenCodeProperties openCodeProperties) {
        this.itemRepository = itemRepository;
        this.versionRepository = versionRepository;
        this.templateConverter = templateConverter;
        this.workflowService = workflowService;
        this.chainRepository = chainRepository;
        this.knowledgeService = knowledgeService;
        this.dodService = dodService;
        this.kanbanService = kanbanService;
        this.openCodeProperties = openCodeProperties;
    }

    /**
     * Find APPROVED workflow templates matching the given intent keywords.
     * If {@code userIntent} is {@code null} or blank, all APPROVED workflow templates are returned.
     */
    @Transactional(readOnly = true)
    public List<KnowledgeItemResponse> findMatchingTemplates(String userIntent) {
        List<KnowledgeItem> allWorkflowTemplates = itemRepository
                .findByTypeAndStatus(KnowledgeType.WORKFLOW, KnowledgeStatus.APPROVED);

        if (userIntent == null || userIntent.isBlank()) {
            return allWorkflowTemplates.stream()
                    .map(knowledgeService::toResponseWithLatestVersion)
                    .toList();
        }

        String lowerIntent = userIntent.toLowerCase();
        return allWorkflowTemplates.stream()
                .filter(item -> item.getName().toLowerCase().contains(lowerIntent)
                        || (item.getDescription() != null
                            && item.getDescription().toLowerCase().contains(lowerIntent)))
                .map(knowledgeService::toResponseWithLatestVersion)
                .toList();
    }

    /**
     * Instantiate an APPROVED workflow template with the given parameters.
     * <p>
     * Reads the YAML content from the template's current version, parses it into steps,
     * substitutes parameters, creates a new workflow chain, and links the chain back
     * to the source knowledge item.
     *
     * @param templateItemId the knowledge item ID of the APPROVED workflow template
     * @param parameters     parameter key-value pairs to substitute into prompt templates
     * @return the newly created workflow response
     */
    @Transactional
    public WorkflowResponse instantiateTemplate(UUID templateItemId, Map<String, String> parameters) {
        KnowledgeItem item = itemRepository.findById(templateItemId)
                .orElseThrow(() -> new ResourceNotFoundException("KnowledgeItem", templateItemId));

        if (item.getType() != KnowledgeType.WORKFLOW) {
            throw new IllegalArgumentException("Knowledge item is not a WORKFLOW template");
        }
        if (item.getStatus() != KnowledgeStatus.APPROVED) {
            throw new IllegalArgumentException("Template is not APPROVED");
        }

        // Read YAML from KnowledgeVersion
        KnowledgeVersion version = versionRepository
                .findByKnowledgeItemIdAndVersion(item.getId(), item.getCurrentVersion())
                .orElseThrow(() -> new ResourceNotFoundException("KnowledgeVersion", item.getId()));

        String yamlContent = version.getYamlContent();
        if (yamlContent == null || yamlContent.isBlank()) {
            throw new IllegalArgumentException("Template has no YAML content");
        }

        // Parse YAML to steps
        List<WorkflowStep> steps = templateConverter.yamlToWorkflowSteps(yamlContent);

        // Validate that all parameter keys are declared in the template and build
        // the resolved parameter map used for substitution and persistence.
        Set<String> declaredParams = templateConverter.extractParameterNames(steps);
        Map<String, String> resolvedParams = parameters;
        if (parameters != null) {
            for (String key : parameters.keySet()) {
                if (!declaredParams.contains(key)) {
                    throw new IllegalArgumentException(
                            "Unknown parameter '" + key + "': not declared in template " + templateItemId
                            + ". Declared: " + declaredParams);
                }
            }
        }

        // R8-F1: resolve repoUrl. Caller-supplied values win; otherwise fall back to
        // the system-configured default (opencode.repo-url). Fail fast when the
        // template declares {repoUrl} but neither source provides one — without this,
        // the Dev prompt keeps a literal {repoUrl} and the spec-approval coordinator
        // skips branch creation (Dev then "guesses" the repo).
        if (declaredParams.contains(GitHandoffMetadata.KEY_REPO_URL)) {
            String repoUrl = resolvedParams == null ? null : resolvedParams.get(GitHandoffMetadata.KEY_REPO_URL);
            if (repoUrl == null || repoUrl.isBlank()) {
                String sysRepoUrl = openCodeProperties.getRepoUrl();
                if (sysRepoUrl != null && !sysRepoUrl.isBlank()) {
                    resolvedParams = resolvedParams == null
                            ? new LinkedHashMap<>()
                            : new LinkedHashMap<>(resolvedParams);
                    resolvedParams.put(GitHandoffMetadata.KEY_REPO_URL, sysRepoUrl);
                } else {
                    throw new IllegalArgumentException(
                            "Template requires repoUrl parameter; pass it or set opencode.repo-url");
                }
            }
        }

        // Substitute parameters
        if (resolvedParams != null && !resolvedParams.isEmpty()) {
            for (WorkflowStep step : steps) {
                step.setPromptTemplate(
                        templateConverter.substituteParameters(step.getPromptTemplate(), resolvedParams));
            }
        }

        // Build CreateWorkflowRequest
        List<CreateWorkflowRequest.StepDef> stepDefs = steps.stream()
                .map(s -> CreateWorkflowRequest.StepDef.builder()
                        .agentId(s.getAgentId())
                        .promptTemplate(s.getPromptTemplate())
                        .maxIterations(s.getMaxIterations())
                        .kind(s.getKind())
                        .build())
                .toList();

        CreateWorkflowRequest request = CreateWorkflowRequest.builder()
                .name(item.getName() + "-instance")
                .description("Instantiated from template: " + item.getName())
                .steps(stepDefs)
                .allowSddSteps(true)
                .build();

        WorkflowResponse response = workflowService.createAndStart(request);

        // SDD wiring: templates carrying BA/DEV/QA step kinds initialise a DoD
        // record (custom stages [dev, qa], taskId = chainId) and a chain-level
        // kanban item without a linked run, so RunKanbanAutoCreator does not
        // auto-transition it.
        boolean isSdd = steps.stream().anyMatch(s ->
                s.getKind() == WorkflowStep.StepKind.BA
                        || s.getKind() == WorkflowStep.StepKind.DEV
                        || s.getKind() == WorkflowStep.StepKind.QA);
        if (isSdd) {
            dodService.init(response.getId().toString(), "SDD", List.of("dev", "qa"));
        }

        // Link source knowledge item to the newly created chain and inject the
        // system-derived branchName (sdd/<chainId>) into any {branchName} placeholders.
        // branchName is reserved (SYSTEM_PLACEHOLDERS) so callers cannot supply it.
        WorkflowChain newChain = chainRepository.findById(response.getId())
                .orElse(null);
        if (newChain != null) {
            newChain.setSourceKnowledgeItemId(templateItemId);
            // Persist the instantiation parameters (e.g. repoUrl) on the chain so the
            // spec-approval coordinator and Dev-completion fallback can resolve the
            // target repository without re-querying the template (T5 D-A).
            if (resolvedParams != null && !resolvedParams.isEmpty()) {
                newChain.setTemplateParams(GitHandoffMetadata.toJson(resolvedParams));
            }
            String branchName = "sdd/" + newChain.getId();
            List<WorkflowStep> instantiatedSteps = workflowService.deserializeSteps(newChain.getStepsJson());
            boolean branchInjected = false;
            for (WorkflowStep step : instantiatedSteps) {
                String prompt = step.getPromptTemplate();
                if (prompt != null && prompt.contains("{branchName}")) {
                    step.setPromptTemplate(prompt.replace("{branchName}", branchName));
                    branchInjected = true;
                }
            }
            if (branchInjected) {
                newChain.setStepsJson(workflowService.serializeSteps(instantiatedSteps));
            }
            chainRepository.save(newChain);
        }

        log.info("Instantiated workflow template {} as chain {}", templateItemId, response.getId());
        return response;
    }
}
