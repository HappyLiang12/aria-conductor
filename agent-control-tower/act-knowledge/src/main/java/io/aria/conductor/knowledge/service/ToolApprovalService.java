package io.aria.conductor.knowledge.service;

import io.aria.conductor.common.model.*;
import io.aria.conductor.common.repository.ToolDefinitionRepository;
import io.aria.conductor.knowledge.dto.CreateKnowledgeRequest;
import io.aria.conductor.knowledge.dto.KnowledgeItemResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class ToolApprovalService {

    private final KnowledgeService knowledgeService;
    private final ToolDefinitionRepository toolRepo;

    @Transactional
    public ToolDefinition submitToolForApproval(ToolDefinition tool, String content) {
        CreateKnowledgeRequest kiRequest = CreateKnowledgeRequest.builder()
                .name("Tool: " + tool.getName())
                .type(KnowledgeType.TOOL)
                .description(tool.getDescription())
                .content(content != null ? content : tool.getDescription())
                .sensitivity(Sensitivity.INTERNAL)
                .build();

        KnowledgeItemResponse kiResponse = knowledgeService.submitKnowledge(kiRequest);
        tool.setKnowledgeItemId(kiResponse.getId().toString());

        tool.setEnabled(false);
        tool.setId(UUID.randomUUID().toString());
        tool = toolRepo.save(tool);

        log.info("Tool {} submitted for approval, KI: {}", tool.getName(), kiResponse.getId());
        return tool;
    }

    @Transactional
    public void onKnowledgeApproved(UUID knowledgeItemId) {
        toolRepo.findAll().stream()
                .filter(t -> knowledgeItemId.toString().equals(t.getKnowledgeItemId()))
                .findFirst()
                .ifPresent(tool -> {
                    tool.setEnabled(true);
                    toolRepo.save(tool);
                    log.info("Tool {} enabled after KI {} approved", tool.getName(), knowledgeItemId);
                });
    }
}
