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
}
