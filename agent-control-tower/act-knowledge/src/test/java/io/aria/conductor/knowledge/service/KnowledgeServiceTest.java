package io.aria.conductor.knowledge.service;

import io.aria.conductor.common.model.KnowledgeItem;
import io.aria.conductor.common.model.KnowledgeStatus;
import io.aria.conductor.common.model.KnowledgeType;
import io.aria.conductor.common.model.KnowledgeVersion;
import io.aria.conductor.common.model.Sensitivity;
import io.aria.conductor.common.model.VersionStatus;
import io.aria.conductor.knowledge.dto.UpdateKnowledgeRequest;
import io.aria.conductor.knowledge.repository.KnowledgeItemRepository;
import io.aria.conductor.knowledge.repository.KnowledgeVersionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Verifies that {@link KnowledgeService#updateKnowledge} carries forward and
 * stores the WORKFLOW {@code yamlContent} field (F10).
 */
@ExtendWith(MockitoExtension.class)
class KnowledgeServiceTest {

    @Mock
    KnowledgeItemRepository itemRepository;

    @Mock
    KnowledgeVersionRepository versionRepository;

    @Mock
    KnowledgeFileService fileService;

    @Mock
    ApplicationEventPublisher eventPublisher;

    @InjectMocks
    KnowledgeService service;

    private KnowledgeItem workflowItem;
    private KnowledgeVersion currentVersion;

    @BeforeEach
    void seedItem() {
        UUID itemId = UUID.fromString("22222222-2222-2222-2222-222222222222");
        workflowItem = KnowledgeItem.builder()
                .id(itemId)
                .name("Deploy Workflow")
                .type(KnowledgeType.WORKFLOW)
                .status(KnowledgeStatus.APPROVED)
                .sensitivity(Sensitivity.INTERNAL)
                .currentVersion("v0.1.0")
                .createdAt(Instant.now())
                .build();
        currentVersion = KnowledgeVersion.builder()
                .id(UUID.randomUUID())
                .knowledgeItemId(itemId)
                .version("v0.1.0")
                .status(VersionStatus.APPROVED)
                .content("old body")
                .yamlContent("name: deploy\nsteps: []")
                .createdAt(Instant.now())
                .build();
    }

    @Test
    void updateKnowledge_preservesYamlContent() {
        when(itemRepository.findById(workflowItem.getId())).thenReturn(Optional.of(workflowItem));
        when(versionRepository.findByKnowledgeItemIdAndVersion(
                workflowItem.getId(), "v0.1.0")).thenReturn(Optional.of(currentVersion));
        when(fileService.storeContent(any(), any(), any(), any())).thenReturn("/tmp/new.md");
        when(versionRepository.save(any(KnowledgeVersion.class))).thenAnswer(inv -> inv.getArgument(0));
        when(itemRepository.save(any(KnowledgeItem.class))).thenAnswer(inv -> inv.getArgument(0));

        UpdateKnowledgeRequest request = UpdateKnowledgeRequest.builder()
                .content("new body")
                .build();

        service.updateKnowledge(workflowItem.getId(), request);

        ArgumentCaptor<KnowledgeVersion> captor = ArgumentCaptor.forClass(KnowledgeVersion.class);
        org.mockito.Mockito.verify(versionRepository).save(captor.capture());
        KnowledgeVersion saved = captor.getValue();
        assertThat(saved.getYamlContent()).isEqualTo("name: deploy\nsteps: []");
        assertThat(saved.getContent()).isEqualTo("new body");
    }

    @Test
    void updateKnowledge_withNewYaml_storesIt() {
        when(itemRepository.findById(workflowItem.getId())).thenReturn(Optional.of(workflowItem));
        when(fileService.storeContent(any(), any(), any(), any())).thenReturn("/tmp/new.md");
        when(versionRepository.save(any(KnowledgeVersion.class))).thenAnswer(inv -> inv.getArgument(0));
        when(itemRepository.save(any(KnowledgeItem.class))).thenAnswer(inv -> inv.getArgument(0));

        UpdateKnowledgeRequest request = UpdateKnowledgeRequest.builder()
                .content("new body")
                .yamlContent("name: deploy\nsteps:\n  - build")
                .build();

        service.updateKnowledge(workflowItem.getId(), request);

        ArgumentCaptor<KnowledgeVersion> captor = ArgumentCaptor.forClass(KnowledgeVersion.class);
        org.mockito.Mockito.verify(versionRepository).save(captor.capture());
        KnowledgeVersion saved = captor.getValue();
        assertThat(saved.getYamlContent()).isEqualTo("name: deploy\nsteps:\n  - build");
    }

    // ---- legacy WORKFLOW items: YAML derived from content (PR #74 review item 5) ----

    @Test
    void updateKnowledge_legacyWorkflowWithoutYaml_derivesYamlFromStoredContent() {
        // Legacy WORKFLOW rows predate yaml_content: their YAML lives in `content`.
        // An edit that passes no yamlContent must not produce another version with
        // null yaml (which later fails template instantiation with
        // "Template has no YAML content").
        currentVersion.setYamlContent(null);
        currentVersion.setContent("name: deploy\nsteps: []");
        when(itemRepository.findById(workflowItem.getId())).thenReturn(Optional.of(workflowItem));
        when(versionRepository.findByKnowledgeItemIdAndVersion(
                workflowItem.getId(), "v0.1.0")).thenReturn(Optional.of(currentVersion));
        when(versionRepository.save(any(KnowledgeVersion.class))).thenAnswer(inv -> inv.getArgument(0));
        when(itemRepository.save(any(KnowledgeItem.class))).thenAnswer(inv -> inv.getArgument(0));

        UpdateKnowledgeRequest request = UpdateKnowledgeRequest.builder()
                .description("refreshed description") // no content, no yamlContent
                .build();

        service.updateKnowledge(workflowItem.getId(), request);

        ArgumentCaptor<KnowledgeVersion> captor = ArgumentCaptor.forClass(KnowledgeVersion.class);
        org.mockito.Mockito.verify(versionRepository).save(captor.capture());
        assertThat(captor.getValue().getYamlContent()).isEqualTo("name: deploy\nsteps: []");
    }

    @Test
    void updateKnowledge_legacyWorkflowEditedContent_becomesYaml() {
        // When the edit supplies new content for a legacy WORKFLOW item, that new
        // content IS the edited YAML and must be carried into yamlContent.
        currentVersion.setYamlContent(null);
        currentVersion.setContent("name: deploy\nsteps: []");
        when(itemRepository.findById(workflowItem.getId())).thenReturn(Optional.of(workflowItem));
        when(versionRepository.findByKnowledgeItemIdAndVersion(
                workflowItem.getId(), "v0.1.0")).thenReturn(Optional.of(currentVersion));
        when(fileService.storeContent(any(), any(), any(), any())).thenReturn("/tmp/new.yml");
        when(versionRepository.save(any(KnowledgeVersion.class))).thenAnswer(inv -> inv.getArgument(0));
        when(itemRepository.save(any(KnowledgeItem.class))).thenAnswer(inv -> inv.getArgument(0));

        UpdateKnowledgeRequest request = UpdateKnowledgeRequest.builder()
                .content("name: deploy\nsteps:\n  - build")
                .build();

        service.updateKnowledge(workflowItem.getId(), request);

        ArgumentCaptor<KnowledgeVersion> captor = ArgumentCaptor.forClass(KnowledgeVersion.class);
        org.mockito.Mockito.verify(versionRepository).save(captor.capture());
        KnowledgeVersion saved = captor.getValue();
        assertThat(saved.getContent()).isEqualTo("name: deploy\nsteps:\n  - build");
        assertThat(saved.getYamlContent()).isEqualTo("name: deploy\nsteps:\n  - build");
    }

    @Test
    void getYamlContent_legacyWorkflow_fallsBackToStoredContent() {
        // The frontend duplicate flow depends on GET /knowledge/{id}/yaml returning the
        // template: legacy items without stored yamlContent must serve their YAML
        // (derived from content) instead of 204 No Content.
        currentVersion.setYamlContent(null);
        currentVersion.setContent("name: deploy\nsteps: []");
        when(itemRepository.findById(workflowItem.getId())).thenReturn(Optional.of(workflowItem));
        when(versionRepository.findByKnowledgeItemIdAndVersion(workflowItem.getId(), "v0.1.0"))
                .thenReturn(Optional.of(currentVersion));

        assertThat(service.getYamlContent(workflowItem.getId(), null))
                .isEqualTo("name: deploy\nsteps: []");
    }

    @Test
    void getYamlContent_nonWorkflowItem_keepsNullWithoutFallback() {
        // The content->yaml fallback is WORKFLOW-only: other item types keep returning
        // null (HTTP 204) so non-template knowledge is not misinterpreted as YAML.
        workflowItem.setType(KnowledgeType.PROMPT);
        currentVersion.setYamlContent(null);
        when(itemRepository.findById(workflowItem.getId())).thenReturn(Optional.of(workflowItem));
        when(versionRepository.findByKnowledgeItemIdAndVersion(workflowItem.getId(), "v0.1.0"))
                .thenReturn(Optional.of(currentVersion));

        assertThat(service.getYamlContent(workflowItem.getId(), null)).isNull();
    }
}
