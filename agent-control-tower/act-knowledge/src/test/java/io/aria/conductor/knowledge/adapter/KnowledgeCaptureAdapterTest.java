package io.aria.conductor.knowledge.adapter;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.aria.conductor.agent.repository.WorkflowChainRepository;
import io.aria.conductor.common.model.KnowledgeType;
import io.aria.conductor.common.model.Sensitivity;
import io.aria.conductor.common.model.WorkflowChain;
import io.aria.conductor.common.model.WorkflowStep;
import io.aria.conductor.knowledge.converter.WorkflowTemplateConverter;
import io.aria.conductor.knowledge.dto.CreateKnowledgeRequest;
import io.aria.conductor.knowledge.dto.KnowledgeItemResponse;
import io.aria.conductor.knowledge.service.KnowledgeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static io.aria.conductor.test.TestDataBuilder.aWorkflowChain;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class KnowledgeCaptureAdapterTest {

    @Mock KnowledgeService knowledgeService;
    @Mock WorkflowTemplateConverter templateConverter;
    @Mock WorkflowChainRepository chainRepository;

    KnowledgeCaptureAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new KnowledgeCaptureAdapter(knowledgeService, templateConverter,
                chainRepository, new ObjectMapper());
    }

    // ---- submitKnowledge -------------------------------------------------

    @Test
    void submitKnowledge_mapsAllInputsOntoTheCreateRequest() {
        UUID kiId = UUID.randomUUID();
        when(knowledgeService.submitKnowledge(any(CreateKnowledgeRequest.class), eq("yaml: doc")))
                .thenReturn(KnowledgeItemResponse.builder().id(kiId).build());

        UUID out = adapter.submitKnowledge("wf-nightly", KnowledgeType.WORKFLOW,
                "captured nightly flow", "# markdown", "yaml: doc", Sensitivity.CONFIDENTIAL);

        assertThat(out).isEqualTo(kiId);
        ArgumentCaptor<CreateKnowledgeRequest> requestCaptor =
                ArgumentCaptor.forClass(CreateKnowledgeRequest.class);
        verify(knowledgeService).submitKnowledge(requestCaptor.capture(), eq("yaml: doc"));
        CreateKnowledgeRequest request = requestCaptor.getValue();
        assertThat(request.getName()).isEqualTo("wf-nightly");
        assertThat(request.getType()).isEqualTo(KnowledgeType.WORKFLOW);
        assertThat(request.getDescription()).isEqualTo("captured nightly flow");
        assertThat(request.getContent()).isEqualTo("# markdown");
        assertThat(request.getSensitivity()).isEqualTo(Sensitivity.CONFIDENTIAL);
    }

    // ---- captureWorkflowChain: happy path ----------------------------------

    @Test
    void captureWorkflowChain_submitsWorkflowKnowledgeAndLinksItBackToTheChain() {
        UUID chainId = UUID.randomUUID();
        UUID kiId = UUID.randomUUID();
        WorkflowChain chain = aWorkflowChain()
                .withId(chainId)
                .withName("My Flow!!")
                .withKnowledgeItemId(null)
                .withStepsJson(twoStepJson())
                .build();
        when(chainRepository.findById(chainId)).thenReturn(Optional.of(chain));
        when(templateConverter.workflowChainToMarkdown(eq(chain), anyList())).thenReturn("# md");
        when(templateConverter.workflowChainToYaml(eq(chain), anyList(), isNull())).thenReturn("yaml: x");
        when(knowledgeService.submitKnowledge(any(CreateKnowledgeRequest.class), eq("yaml: x")))
                .thenReturn(KnowledgeItemResponse.builder().id(kiId).build());
        when(chainRepository.save(any(WorkflowChain.class))).thenAnswer(inv -> inv.getArgument(0));

        UUID out = adapter.captureWorkflowChain(chainId);

        assertThat(out).isEqualTo(kiId);

        ArgumentCaptor<CreateKnowledgeRequest> requestCaptor =
                ArgumentCaptor.forClass(CreateKnowledgeRequest.class);
        verify(knowledgeService).submitKnowledge(requestCaptor.capture(), eq("yaml: x"));
        CreateKnowledgeRequest request = requestCaptor.getValue();
        // special chars replaced with '-', lowercased, "wf-" prefix
        assertThat(request.getName()).isEqualTo("wf-my-flow--");
        assertThat(request.getType()).isEqualTo(KnowledgeType.WORKFLOW);
        assertThat(request.getDescription()).contains(chainId.toString());
        assertThat(request.getContent()).isEqualTo("# md");
        assertThat(request.getSensitivity()).isEqualTo(Sensitivity.INTERNAL);

        // deserialized steps are handed to the converter
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<WorkflowStep>> stepsCaptor =
                ArgumentCaptor.forClass((Class) List.class);
        verify(templateConverter).workflowChainToMarkdown(eq(chain), stepsCaptor.capture());
        assertThat(stepsCaptor.getValue()).hasSize(2);
        assertThat(stepsCaptor.getValue().get(0).getPromptTemplate()).isEqualTo("step one");
        assertThat(stepsCaptor.getValue().get(1).getMaxIterations()).isEqualTo(5);

        // chain now carries the knowledge item id
        ArgumentCaptor<WorkflowChain> chainCaptor = ArgumentCaptor.forClass(WorkflowChain.class);
        verify(chainRepository).save(chainCaptor.capture());
        assertThat(chainCaptor.getValue().getId()).isEqualTo(chainId);
        assertThat(chainCaptor.getValue().getKnowledgeItemId()).isEqualTo(kiId);
    }

    // ---- captureWorkflowChain: skip / error paths ----------------------------

    @Test
    void captureWorkflowChain_unknownChain_returnsNullWithoutSubmitting() {
        UUID missing = UUID.randomUUID();
        when(chainRepository.findById(missing)).thenReturn(Optional.empty());

        assertThat(adapter.captureWorkflowChain(missing)).isNull();
        verifyNoInteractions(knowledgeService, templateConverter);
    }

    @Test
    void captureWorkflowChain_alreadyCaptured_returnsExistingIdAndSkipsResubmission() {
        UUID chainId = UUID.randomUUID();
        UUID existingKi = UUID.randomUUID();
        WorkflowChain chain = aWorkflowChain()
                .withId(chainId)
                .withKnowledgeItemId(existingKi)
                .withStepsJson(twoStepJson())
                .build();
        when(chainRepository.findById(chainId)).thenReturn(Optional.of(chain));

        assertThat(adapter.captureWorkflowChain(chainId)).isEqualTo(existingKi);
        verifyNoInteractions(knowledgeService, templateConverter);
        verify(chainRepository, never()).save(any());
    }

    @Test
    void captureWorkflowChain_singleStepChain_isSkippedAsTrivial() {
        UUID chainId = UUID.randomUUID();
        WorkflowChain chain = aWorkflowChain()
                .withId(chainId)
                .withKnowledgeItemId(null)
                .withStepsJson("[{\"agentId\":\"" + UUID.randomUUID() + "\",\"promptTemplate\":\"only\"}]")
                .build();
        when(chainRepository.findById(chainId)).thenReturn(Optional.of(chain));

        assertThat(adapter.captureWorkflowChain(chainId)).isNull();
        verifyNoInteractions(knowledgeService, templateConverter);
    }

    @Test
    void captureWorkflowChain_malformedStepsJson_isTreatedAsEmptyAndSkipped() {
        UUID chainId = UUID.randomUUID();
        WorkflowChain chain = aWorkflowChain()
                .withId(chainId)
                .withKnowledgeItemId(null)
                .withStepsJson("{not-json]")
                .build();
        when(chainRepository.findById(chainId)).thenReturn(Optional.of(chain));

        assertThat(adapter.captureWorkflowChain(chainId)).isNull();
        verifyNoInteractions(knowledgeService);
    }

    @Test
    void captureWorkflowChain_converterFailure_returnsNullAndLeavesChainUnlinked() {
        UUID chainId = UUID.randomUUID();
        WorkflowChain chain = aWorkflowChain()
                .withId(chainId)
                .withKnowledgeItemId(null)
                .withStepsJson(twoStepJson())
                .build();
        when(chainRepository.findById(chainId)).thenReturn(Optional.of(chain));
        when(templateConverter.workflowChainToMarkdown(eq(chain), anyList()))
                .thenThrow(new RuntimeException("yaml explosion"));

        assertThat(adapter.captureWorkflowChain(chainId)).isNull();
        verify(chainRepository, never()).save(any());
        verify(knowledgeService, never()).submitKnowledge(any(), anyString());
        assertThat(chain.getKnowledgeItemId()).isNull();
    }

    // ---- helpers --------------------------------------------------------------

    private static String twoStepJson() {
        return "[{\"agentId\":\"" + UUID.randomUUID() + "\",\"promptTemplate\":\"step one\"},"
                + "{\"agentId\":\"" + UUID.randomUUID() + "\",\"promptTemplate\":\"step two\","
                + "\"maxIterations\":5}]";
    }
}
