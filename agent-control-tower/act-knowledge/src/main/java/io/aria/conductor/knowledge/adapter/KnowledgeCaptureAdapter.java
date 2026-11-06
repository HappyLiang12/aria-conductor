package io.aria.conductor.knowledge.adapter;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.aria.conductor.agent.repository.WorkflowChainRepository;
import io.aria.conductor.common.model.KnowledgeType;
import io.aria.conductor.common.model.Sensitivity;
import io.aria.conductor.common.model.WorkflowChain;
import io.aria.conductor.common.model.WorkflowStep;
import io.aria.conductor.common.port.KnowledgeCapturePort;
import io.aria.conductor.knowledge.converter.WorkflowTemplateConverter;
import io.aria.conductor.knowledge.dto.CreateKnowledgeRequest;
import io.aria.conductor.knowledge.dto.KnowledgeItemResponse;
import io.aria.conductor.knowledge.service.KnowledgeService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Adapter that bridges the {@link KnowledgeCapturePort} (act-common)
 * to the {@link KnowledgeService} (act-knowledge).
 * <p>
 * Consumed by act-execution's {@code WorkflowAutoCaptureListener}.
 * </p>
 */
@Component
public class KnowledgeCaptureAdapter implements KnowledgeCapturePort {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeCaptureAdapter.class);

    private final KnowledgeService knowledgeService;
    private final WorkflowTemplateConverter templateConverter;
    private final WorkflowChainRepository chainRepository;
    private final ObjectMapper objectMapper;

    public KnowledgeCaptureAdapter(KnowledgeService knowledgeService,
                                   WorkflowTemplateConverter templateConverter,
                                   WorkflowChainRepository chainRepository,
                                   ObjectMapper objectMapper) {
        this.knowledgeService = knowledgeService;
        this.templateConverter = templateConverter;
        this.chainRepository = chainRepository;
        this.objectMapper = objectMapper;
    }

    @Override
    public UUID submitKnowledge(String name,
                                KnowledgeType type,
                                String description,
                                String mdContent,
                                String yamlContent,
                                Sensitivity sensitivity) {
        CreateKnowledgeRequest request = CreateKnowledgeRequest.builder()
                .name(name)
                .type(type)
                .description(description)
                .content(mdContent)
                .sensitivity(sensitivity)
                .build();

        KnowledgeItemResponse response = knowledgeService.submitKnowledge(request, yamlContent);
        log.info("KnowledgeCaptureAdapter: submitted '{}' as knowledge item {}", name, response.getId());
        return response.getId();
    }

    @Override
    public UUID captureWorkflowChain(UUID chainId) {
        WorkflowChain chain = chainRepository.findById(chainId).orElse(null);
        if (chain == null) {
            log.warn("Auto-capture: chain not found: {}", chainId);
            return null;
        }

        // Skip if already captured
        if (chain.getKnowledgeItemId() != null) {
            log.info("Auto-capture: chain {} already captured, skipping", chainId);
            return chain.getKnowledgeItemId();
        }

        // Skip trivial single-step workflows
        List<WorkflowStep> steps = deserializeSteps(chain.getStepsJson());
        if (steps.size() < 2) {
            log.info("Auto-capture: chain {} has < 2 steps, skipping", chainId);
            return null;
        }

        try {
            // Generate MD and YAML
            String mdContent = templateConverter.workflowChainToMarkdown(chain, steps);
            String yamlContent = templateConverter.workflowChainToYaml(chain, steps, null);

            // Build knowledge item name
            String name = "wf-" + chain.getName().replaceAll("[^a-zA-Z0-9-_]", "-").toLowerCase();

            CreateKnowledgeRequest request = CreateKnowledgeRequest.builder()
                    .name(name)
                    .type(KnowledgeType.WORKFLOW)
                    .description("Auto-captured from workflow chain " + chainId)
                    .content(mdContent)
                    .sensitivity(Sensitivity.INTERNAL)
                    .build();

            KnowledgeItemResponse response = knowledgeService.submitKnowledge(request, yamlContent);

            // Link back to chain
            chain.setKnowledgeItemId(response.getId());
            chainRepository.save(chain);

            log.info("Auto-captured workflow {} as knowledge item {}", chainId, response.getId());
            return response.getId();
        } catch (Exception e) {
            log.error("Auto-capture failed for chain {}: {}", chainId, e.getMessage(), e);
            return null;
        }
    }

    private List<WorkflowStep> deserializeSteps(String json) {
        if (json == null || json.isBlank()) return new ArrayList<>();
        try {
            return objectMapper.readValue(json, new TypeReference<List<WorkflowStep>>() {});
        } catch (Exception e) {
            log.error("Failed to deserialize workflow steps: {}", e.getMessage());
            return new ArrayList<>();
        }
    }
}
