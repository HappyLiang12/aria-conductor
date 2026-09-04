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
import io.aria.conductor.execution.kanban.KanbanService;
import io.aria.conductor.knowledge.converter.WorkflowTemplateConverter;
import io.aria.conductor.knowledge.dto.KnowledgeItemResponse;
import io.aria.conductor.knowledge.repository.KnowledgeItemRepository;
import io.aria.conductor.knowledge.repository.KnowledgeVersionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
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
import static io.aria.conductor.test.TestDataBuilder.aWorkflowStep;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WorkflowTemplateServiceTest {

    @Mock KnowledgeItemRepository itemRepository;
    @Mock KnowledgeVersionRepository versionRepository;
    @Mock WorkflowTemplateConverter templateConverter;
    @Mock WorkflowService workflowService;
    @Mock WorkflowChainRepository chainRepository;
    @Mock KnowledgeService knowledgeService;
    @Mock DoDService dodService;
    @Mock KanbanService kanbanService;
    @Mock OpenCodeProperties openCodeProperties;

    WorkflowTemplateService service;

    @BeforeEach
    void setUp() {
        service = new WorkflowTemplateService(itemRepository, versionRepository,
                templateConverter, workflowService, chainRepository, knowledgeService,
                dodService, kanbanService, openCodeProperties);
    }

    // ---- findMatchingTemplates -------------------------------------------

    @ParameterizedTest(name = "intent=<{0}> returns all approved templates")
    @NullSource
    @ValueSource(strings = {"", "   "})
    void findMatchingTemplates_blankIntent_returnsAllApprovedTemplates(String intent) {
        KnowledgeItem t1 = approvedWorkflowTemplate("deploy-flow", "Deploys the app");
        KnowledgeItem t2 = approvedWorkflowTemplate("report-flow", "Weekly report");
        when(itemRepository.findByTypeAndStatus(KnowledgeType.WORKFLOW, KnowledgeStatus.APPROVED))
                .thenReturn(List.of(t1, t2));
        KnowledgeItemResponse r1 = responseFor(t1);
        KnowledgeItemResponse r2 = responseFor(t2);
        when(knowledgeService.toResponseWithLatestVersion(t1)).thenReturn(r1);
        when(knowledgeService.toResponseWithLatestVersion(t2)).thenReturn(r2);

        List<KnowledgeItemResponse> out = service.findMatchingTemplates(intent);

        assertThat(out).containsExactly(r1, r2);
    }

    @Test
    void findMatchingTemplates_matchesNameCaseInsensitively() {
        KnowledgeItem deploy = approvedWorkflowTemplate("Deploy-Flow", "ship it");
        KnowledgeItem report = approvedWorkflowTemplate("report-flow", "weekly numbers");
        when(itemRepository.findByTypeAndStatus(KnowledgeType.WORKFLOW, KnowledgeStatus.APPROVED))
                .thenReturn(List.of(deploy, report));
        KnowledgeItemResponse deployResponse = responseFor(deploy);
        when(knowledgeService.toResponseWithLatestVersion(deploy)).thenReturn(deployResponse);

        List<KnowledgeItemResponse> out = service.findMatchingTemplates("DEPLOY");

        assertThat(out).containsExactly(deployResponse);
        verify(knowledgeService, never()).toResponseWithLatestVersion(report);
    }

    @Test
    void findMatchingTemplates_matchesDescriptionAndHandlesNullDescription() {
        KnowledgeItem withDesc = approvedWorkflowTemplate("flow-a", "Generates the invoice batch");
        KnowledgeItem nullDesc = approvedWorkflowTemplate("flow-b", null);
        when(itemRepository.findByTypeAndStatus(KnowledgeType.WORKFLOW, KnowledgeStatus.APPROVED))
                .thenReturn(List.of(withDesc, nullDesc));
        KnowledgeItemResponse matched = responseFor(withDesc);
        when(knowledgeService.toResponseWithLatestVersion(withDesc)).thenReturn(matched);

        List<KnowledgeItemResponse> out = service.findMatchingTemplates("invoice");

        assertThat(out).containsExactly(matched);
    }

    @Test
    void findMatchingTemplates_noMatch_returnsEmptyList() {
        when(itemRepository.findByTypeAndStatus(KnowledgeType.WORKFLOW, KnowledgeStatus.APPROVED))
                .thenReturn(List.of(approvedWorkflowTemplate("deploy-flow", "ship")));

        assertThat(service.findMatchingTemplates("nonexistent-intent")).isEmpty();
        verifyNoInteractions(knowledgeService);
    }

    // ---- instantiateTemplate: happy path -----------------------------------

    @Test
    void instantiateTemplate_buildsWorkflowFromYamlWithParameterSubstitution() {
        UUID templateId = UUID.randomUUID();
        UUID agent1 = UUID.randomUUID();
        UUID agent2 = UUID.randomUUID();
        KnowledgeItem item = approvedWorkflowTemplate("deploy-flow", "ship");
        item.setId(templateId);
        item.setCurrentVersion("v1.0.0");
        when(itemRepository.findById(templateId)).thenReturn(Optional.of(item));
        when(versionRepository.findByKnowledgeItemIdAndVersion(templateId, "v1.0.0"))
                .thenReturn(Optional.of(KnowledgeVersion.builder()
                        .knowledgeItemId(templateId)
                        .version("v1.0.0")
                        .yamlContent("steps: [...]")
                        .build()));
        WorkflowStep step1 = aWorkflowStep().withAgentId(agent1)
                .withPromptTemplate("Build {env}").withMaxIterations(4).build();
        WorkflowStep step2 = aWorkflowStep().withAgentId(agent2)
                .withPromptTemplate("Deploy {env} using {previousOutput}").withMaxIterations(7).build();
        when(templateConverter.yamlToWorkflowSteps("steps: [...]")).thenReturn(List.of(step1, step2));
        Map<String, String> params = Map.of("env", "prod");
        when(templateConverter.extractParameterNames(anyList())).thenReturn(Set.of("env"));
        when(templateConverter.substituteParameters("Build {env}", params))
                .thenReturn("Build prod");
        when(templateConverter.substituteParameters("Deploy {env} using {previousOutput}", params))
                .thenReturn("Deploy prod using {previousOutput}");

        UUID chainId = UUID.randomUUID();
        when(workflowService.createAndStart(any(CreateWorkflowRequest.class)))
                .thenReturn(WorkflowResponse.builder().id(chainId).name("deploy-flow-instance").build());
        WorkflowChain chain = aWorkflowChain().withId(chainId).build();
        when(chainRepository.findById(chainId)).thenReturn(Optional.of(chain));
        when(chainRepository.save(any(WorkflowChain.class))).thenAnswer(inv -> inv.getArgument(0));

        WorkflowResponse response = service.instantiateTemplate(templateId, params);

        assertThat(response.getId()).isEqualTo(chainId);

        ArgumentCaptor<CreateWorkflowRequest> requestCaptor =
                ArgumentCaptor.forClass(CreateWorkflowRequest.class);
        verify(workflowService).createAndStart(requestCaptor.capture());
        CreateWorkflowRequest request = requestCaptor.getValue();
        assertThat(request.getName()).isEqualTo("deploy-flow-instance");
        assertThat(request.getDescription()).contains("deploy-flow");
        assertThat(request.getSteps()).hasSize(2);
        assertThat(request.getSteps().get(0).getAgentId()).isEqualTo(agent1);
        assertThat(request.getSteps().get(0).getPromptTemplate()).isEqualTo("Build prod");
        assertThat(request.getSteps().get(0).getMaxIterations()).isEqualTo(4);
        assertThat(request.getSteps().get(1).getAgentId()).isEqualTo(agent2);
        assertThat(request.getSteps().get(1).getPromptTemplate())
                .isEqualTo("Deploy prod using {previousOutput}");
        assertThat(request.getSteps().get(1).getMaxIterations()).isEqualTo(7);

        // chain is linked back to the source template
        ArgumentCaptor<WorkflowChain> chainCaptor = ArgumentCaptor.forClass(WorkflowChain.class);
        verify(chainRepository).save(chainCaptor.capture());
        assertThat(chainCaptor.getValue().getSourceKnowledgeItemId()).isEqualTo(templateId);
    }

    @Test
    void instantiateTemplate_nullParameters_skipsSubstitution() {
        UUID templateId = UUID.randomUUID();
        KnowledgeItem item = approvedWorkflowTemplate("flow", "d");
        item.setId(templateId);
        when(itemRepository.findById(templateId)).thenReturn(Optional.of(item));
        when(versionRepository.findByKnowledgeItemIdAndVersion(templateId, item.getCurrentVersion()))
                .thenReturn(Optional.of(KnowledgeVersion.builder().yamlContent("y").build()));
        WorkflowStep step = aWorkflowStep().withPromptTemplate("Raw {param}").build();
        when(templateConverter.yamlToWorkflowSteps("y")).thenReturn(List.of(step));
        UUID chainId = UUID.randomUUID();
        when(workflowService.createAndStart(any(CreateWorkflowRequest.class)))
                .thenReturn(WorkflowResponse.builder().id(chainId).build());
        when(chainRepository.findById(chainId)).thenReturn(Optional.empty());

        service.instantiateTemplate(templateId, null);

        verify(templateConverter, never()).substituteParameters(anyString(), anyMap());
        ArgumentCaptor<CreateWorkflowRequest> requestCaptor =
                ArgumentCaptor.forClass(CreateWorkflowRequest.class);
        verify(workflowService).createAndStart(requestCaptor.capture());
        assertThat(requestCaptor.getValue().getSteps().get(0).getPromptTemplate())
                .isEqualTo("Raw {param}");
        // chain lookup missed -> no link written, but instantiation still succeeds
        verify(chainRepository, never()).save(any());
    }

    // ---- instantiateTemplate: guard rails -----------------------------------

    @Test
    void instantiateTemplate_unknownTemplateId_throwsNotFound() {
        UUID unknown = UUID.randomUUID();
        when(itemRepository.findById(unknown)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.instantiateTemplate(unknown, Map.of()))
                .isInstanceOf(ResourceNotFoundException.class);
        verifyNoInteractions(workflowService, chainRepository);
    }

    @Test
    void instantiateTemplate_nonWorkflowItem_isRejected() {
        UUID id = UUID.randomUUID();
        KnowledgeItem prompt = aKnowledgeItem().withId(id)
                .withType(KnowledgeType.PROMPT).withStatus(KnowledgeStatus.APPROVED).build();
        when(itemRepository.findById(id)).thenReturn(Optional.of(prompt));

        assertThatThrownBy(() -> service.instantiateTemplate(id, Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not a WORKFLOW");
        verifyNoInteractions(workflowService);
    }

    @Test
    void instantiateTemplate_pendingTemplate_isRejected() {
        UUID id = UUID.randomUUID();
        KnowledgeItem pending = aKnowledgeItem().withId(id)
                .withType(KnowledgeType.WORKFLOW).withStatus(KnowledgeStatus.PENDING).build();
        when(itemRepository.findById(id)).thenReturn(Optional.of(pending));

        assertThatThrownBy(() -> service.instantiateTemplate(id, Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not APPROVED");
        verifyNoInteractions(workflowService);
    }

    @Test
    void instantiateTemplate_missingCurrentVersion_throwsNotFound() {
        UUID id = UUID.randomUUID();
        KnowledgeItem item = approvedWorkflowTemplate("flow", "d");
        item.setId(id);
        when(itemRepository.findById(id)).thenReturn(Optional.of(item));
        when(versionRepository.findByKnowledgeItemIdAndVersion(id, item.getCurrentVersion()))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.instantiateTemplate(id, Map.of()))
                .isInstanceOf(ResourceNotFoundException.class);
        verifyNoInteractions(workflowService);
    }

    @ParameterizedTest(name = "yamlContent=<{0}> is rejected")
    @NullSource
    @ValueSource(strings = {"", "  "})
    void instantiateTemplate_blankYaml_isRejected(String yamlContent) {
        UUID id = UUID.randomUUID();
        KnowledgeItem item = approvedWorkflowTemplate("flow", "d");
        item.setId(id);
        when(itemRepository.findById(id)).thenReturn(Optional.of(item));
        when(versionRepository.findByKnowledgeItemIdAndVersion(id, item.getCurrentVersion()))
                .thenReturn(Optional.of(KnowledgeVersion.builder().yamlContent(yamlContent).build()));

        assertThatThrownBy(() -> service.instantiateTemplate(id, Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no YAML content");
        verifyNoInteractions(workflowService);
    }

    @Test
    void instantiateTemplate_legacyTemplateYamlInContent_derivesYaml() {
        // Legacy WORKFLOW items (created before yaml_content existed) store the YAML as
        // the version content — instantiation must derive it instead of failing with
        // "Template has no YAML content".
        UUID templateId = UUID.randomUUID();
        UUID agent1 = UUID.randomUUID();
        KnowledgeItem item = approvedWorkflowTemplate("legacy-flow", "old");
        item.setId(templateId);
        item.setCurrentVersion("v0.1.0");
        when(itemRepository.findById(templateId)).thenReturn(Optional.of(item));
        when(versionRepository.findByKnowledgeItemIdAndVersion(templateId, "v0.1.0"))
                .thenReturn(Optional.of(KnowledgeVersion.builder()
                        .knowledgeItemId(templateId)
                        .version("v0.1.0")
                        .content("steps: [...]")
                        .build()));
        WorkflowStep step = aWorkflowStep().withAgentId(agent1)
                .withPromptTemplate("Build").build();
        when(templateConverter.yamlToWorkflowSteps("steps: [...]")).thenReturn(List.of(step));
        when(templateConverter.extractParameterNames(anyList())).thenReturn(Set.of());
        UUID chainId = UUID.randomUUID();
        when(workflowService.createAndStart(any(CreateWorkflowRequest.class)))
                .thenReturn(WorkflowResponse.builder().id(chainId).build());
        when(chainRepository.findById(chainId)).thenReturn(Optional.empty());

        WorkflowResponse response = service.instantiateTemplate(templateId, Map.of());

        assertThat(response.getId()).isEqualTo(chainId);
        verify(templateConverter).yamlToWorkflowSteps("steps: [...]");
        verifyNoInteractions(dodService);
    }

    // ---- helpers -------------------------------------------------------------

    private static KnowledgeItem approvedWorkflowTemplate(String name, String description) {
        return aKnowledgeItem()
                .withType(KnowledgeType.WORKFLOW)
                .withStatus(KnowledgeStatus.APPROVED)
                .withName(name)
                .withDescription(description)
                .build();
    }

    private static KnowledgeItemResponse responseFor(KnowledgeItem item) {
        return KnowledgeItemResponse.builder()
                .id(item.getId())
                .name(item.getName())
                .type(item.getType())
                .status(item.getStatus())
                .build();
    }
}
