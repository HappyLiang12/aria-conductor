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
import io.aria.conductor.execution.dod.DoDService;
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

import java.util.List;
import java.util.Map;
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

    public WorkflowTemplateService(KnowledgeItemRepository itemRepository,
                                   KnowledgeVersionRepository versionRepository,
                                   WorkflowTemplateConverter templateConverter,
                                   WorkflowService workflowService,
                                   WorkflowChainRepository chainRepository,
                                   KnowledgeService knowledgeService,
                                   DoDService dodService,
                                   KanbanService kanbanService) {
        this.itemRepository = itemRepository;
        this.versionRepository = versionRepository;
        this.templateConverter = templateConverter;
        this.workflowService = workflowService;
        this.chainRepository = chainRepository;
        this.knowledgeService = knowledgeService;
        this.dodService = dodService;
        this.kanbanService = kanbanService;
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

        // Substitute parameters
        if (parameters != null) {
            for (WorkflowStep step : steps) {
                step.setPromptTemplate(
                        templateConverter.substituteParameters(step.getPromptTemplate(), parameters));
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
            kanbanService.create(CreateKanbanItemRequest.builder()
                    .title(response.getName())
                    .description("SDD workflow: " + item.getName())
                    .build());
        }

        // Link source knowledge item to the newly created chain
        WorkflowChain newChain = chainRepository.findById(response.getId())
                .orElse(null);
        if (newChain != null) {
            newChain.setSourceKnowledgeItemId(templateItemId);
            chainRepository.save(newChain);
        }

        log.info("Instantiated workflow template {} as chain {}", templateItemId, response.getId());
        return response;
    }
}
