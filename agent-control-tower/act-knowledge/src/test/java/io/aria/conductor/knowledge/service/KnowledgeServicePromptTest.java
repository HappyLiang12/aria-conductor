package io.aria.conductor.knowledge.service;

import io.aria.conductor.common.model.KnowledgeItem;
import io.aria.conductor.common.model.KnowledgeStatus;
import io.aria.conductor.common.model.KnowledgeType;
import io.aria.conductor.common.model.Sensitivity;
import io.aria.conductor.knowledge.repository.KnowledgeItemRepository;
import io.aria.conductor.knowledge.repository.KnowledgeVersionRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class KnowledgeServicePromptTest {

    @Mock KnowledgeItemRepository itemRepository;
    @Mock KnowledgeVersionRepository versionRepository;
    @Mock KnowledgeFileService fileService;
    @Mock ApplicationEventPublisher eventPublisher;
    @InjectMocks KnowledgeService knowledgeService;

    @Test
    void buildKnowledgeContextPromptFormatsApprovedItems() {
        KnowledgeItem ki = KnowledgeItem.builder()
                .name("deploy-proc").type(KnowledgeType.GUIDELINE)
                .description("Always blue-green deploy").status(KnowledgeStatus.APPROVED)
                .sensitivity(Sensitivity.INTERNAL).build();
        when(itemRepository.findByStatusOrderByUpdatedAtDesc(KnowledgeStatus.APPROVED))
                .thenReturn(List.of(ki));

        String prompt = knowledgeService.buildKnowledgeContextPrompt(5);

        assertThat(prompt).startsWith("## Knowledge Context");
        assertThat(prompt).contains("**deploy-proc** (GUIDELINE)");
        assertThat(prompt).contains("Always blue-green deploy");
    }

    @Test
    void buildKnowledgeContextPromptEmptyWhenNoneApproved() {
        when(itemRepository.findByStatusOrderByUpdatedAtDesc(KnowledgeStatus.APPROVED))
                .thenReturn(List.of());
        assertThat(knowledgeService.buildKnowledgeContextPrompt(5)).isEmpty();
    }
}