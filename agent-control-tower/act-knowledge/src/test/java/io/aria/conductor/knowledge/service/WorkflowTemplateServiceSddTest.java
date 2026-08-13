package io.aria.conductor.knowledge.service;

import io.aria.conductor.agent.dto.CreateWorkflowRequest;
import io.aria.conductor.agent.dto.WorkflowResponse;
import io.aria.conductor.agent.repository.WorkflowChainRepository;
import io.aria.conductor.agent.service.WorkflowService;
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
import io.aria.conductor.knowledge.repository.KnowledgeItemRepository;
import io.aria.conductor.knowledge.repository.KnowledgeVersionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static io.aria.conductor.test.TestDataBuilder.aKnowledgeItem;
import static io.aria.conductor.test.TestDataBuilder.aWorkflowChain;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * SDD wiring tests for {@link WorkflowTemplateService#instantiateTemplate}:
 * templates whose steps carry BA/DEV/QA kinds initialise a DoD record with
 * custom stages {@code [dev, qa]} and create a chain-level kanban item without
 * a linked run.
 */
@ExtendWith(MockitoExtension.class)
class WorkflowTemplateServiceSddTest {

    @Mock KnowledgeItemRepository itemRepository;
    @Mock KnowledgeVersionRepository versionRepository;
    @Mock WorkflowTemplateConverter templateConverter;
    @Mock WorkflowService workflowService;
    @Mock WorkflowChainRepository chainRepository;
    @Mock KnowledgeService knowledgeService;
    @Mock DoDService dodService;
    @Mock KanbanService kanbanService;

    WorkflowTemplateService service;

    @BeforeEach
    void setUp() {
        service = new WorkflowTemplateService(itemRepository, versionRepository,
                templateConverter, workflowService, chainRepository, knowledgeService,
                dodService, kanbanService);
    }

    @Test
    void instantiateTemplate_withSddKinds_initsDoDAndKanban() {
        // seeded development-workflow template (V40): ba/dev/qa steps with kinds
        UUID templateId = UUID.randomUUID();
        KnowledgeItem item = approvedWorkflowTemplate("development-workflow", "SDD loop");
        item.setId(templateId);
        item.setCurrentVersion("v1.0.0");
        when(itemRepository.findById(templateId)).thenReturn(Optional.of(item));
        when(versionRepository.findByKnowledgeItemIdAndVersion(templateId, "v1.0.0"))
                .thenReturn(Optional.of(KnowledgeVersion.builder()
                        .knowledgeItemId(templateId)
                        .version("v1.0.0")
                        .yamlContent("steps: [ba, dev, qa]")
                        .build()));

        WorkflowStep ba = step(WorkflowStep.StepKind.BA, "Analyze issue {issueRef}");
        WorkflowStep dev = step(WorkflowStep.StepKind.DEV, "Implement per approved spec");
        WorkflowStep qa = step(WorkflowStep.StepKind.QA, "Verify against spec");
        when(templateConverter.yamlToWorkflowSteps("steps: [ba, dev, qa]"))
                .thenReturn(List.of(ba, dev, qa));
        when(templateConverter.extractParameterNames(anyList()))
                .thenReturn(Set.of("issueRef"));

        UUID chainId = UUID.randomUUID();
        WorkflowResponse response = WorkflowResponse.builder()
                .id(chainId)
                .name("development-workflow-instance")
                .build();
        when(workflowService.createAndStart(any(CreateWorkflowRequest.class))).thenReturn(response);
        WorkflowChain chain = aWorkflowChain().withId(chainId).build();
        when(chainRepository.findById(chainId)).thenReturn(Optional.of(chain));
        when(chainRepository.save(any(WorkflowChain.class))).thenAnswer(inv -> inv.getArgument(0));

        service.instantiateTemplate(templateId, Map.of("issueRef", "#1"));

        // DoD initialised with custom stages [dev, qa] for taskId = chainId
        verify(dodService).init(chainId.toString(), "SDD", List.of("dev", "qa"));
        // chain-level kanban item removed (RunKanbanAutoCreator creates per-run items)
        verify(kanbanService, never()).create(any());
        // steps carry kinds through StepDef
        ArgumentCaptor<CreateWorkflowRequest> requestCaptor =
                ArgumentCaptor.forClass(CreateWorkflowRequest.class);
        verify(workflowService).createAndStart(requestCaptor.capture());
        assertThat(requestCaptor.getValue().getSteps())
                .extracting(CreateWorkflowRequest.StepDef::getKind)
                .containsExactly(WorkflowStep.StepKind.BA, WorkflowStep.StepKind.DEV,
                        WorkflowStep.StepKind.QA);
        // chain is still linked back to the source template
        assertThat(chain.getSourceKnowledgeItemId()).isEqualTo(templateId);
    }

    @Test
    void instantiateTemplate_rejectsUnknownKeys() {
        UUID templateId = UUID.randomUUID();
        KnowledgeItem item = approvedWorkflowTemplate("dev-workflow", "SDD");
        item.setId(templateId);
        item.setCurrentVersion("v1");
        when(itemRepository.findById(templateId)).thenReturn(Optional.of(item));
        when(versionRepository.findByKnowledgeItemIdAndVersion(templateId, "v1"))
                .thenReturn(Optional.of(KnowledgeVersion.builder().yamlContent("steps: [...]").build()));
        WorkflowStep step = step(WorkflowStep.StepKind.DEV, "Implement {issueRef}");
        when(templateConverter.yamlToWorkflowSteps("steps: [...]")).thenReturn(List.of(step));
        when(templateConverter.extractParameterNames(anyList())).thenReturn(Set.of("issueRef"));

        Map<String, String> params = Map.of("issueRef", "#1", "malicious", "injected");
        assertThatThrownBy(() -> service.instantiateTemplate(templateId, params))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("malicious");
    }

    @Test
    void instantiateTemplate_withoutSddKinds_skipsWiring() {
        // generic template (no ba/dev/qa kinds)
        UUID templateId = UUID.randomUUID();
        KnowledgeItem item = approvedWorkflowTemplate("generic-flow", "no SDD");
        item.setId(templateId);
        when(itemRepository.findById(templateId)).thenReturn(Optional.of(item));
        when(versionRepository.findByKnowledgeItemIdAndVersion(templateId, item.getCurrentVersion()))
                .thenReturn(Optional.of(KnowledgeVersion.builder().yamlContent("yaml").build()));
        WorkflowStep generic = step(WorkflowStep.StepKind.GENERIC, "Do the task");
        when(templateConverter.yamlToWorkflowSteps("yaml")).thenReturn(List.of(generic));

        UUID chainId = UUID.randomUUID();
        when(workflowService.createAndStart(any(CreateWorkflowRequest.class)))
                .thenReturn(WorkflowResponse.builder().id(chainId).name("generic-flow-instance").build());
        when(chainRepository.findById(chainId)).thenReturn(Optional.empty());

        service.instantiateTemplate(templateId, Map.of());

        verify(dodService, never()).init(any(), any(), any());
        verify(kanbanService, never()).create(any());
    }

    // ---- helpers -------------------------------------------------------------

    private static WorkflowStep step(WorkflowStep.StepKind kind, String promptTemplate) {
        return WorkflowStep.builder()
                .agentId(UUID.randomUUID())
                .promptTemplate(promptTemplate)
                .kind(kind)
                .build();
    }

    private static KnowledgeItem approvedWorkflowTemplate(String name, String description) {
        return aKnowledgeItem()
                .withType(KnowledgeType.WORKFLOW)
                .withStatus(KnowledgeStatus.APPROVED)
                .withName(name)
                .withDescription(description)
                .build();
    }
}
