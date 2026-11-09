package io.aria.conductor.knowledge.service;

import io.aria.conductor.common.context.ContextSection;
import io.aria.conductor.common.model.KnowledgeItem;
import io.aria.conductor.common.model.KnowledgeStatus;
import io.aria.conductor.common.model.KnowledgeType;
import io.aria.conductor.common.model.Sensitivity;
import io.aria.conductor.knowledge.repository.KnowledgeItemRepository;
import io.aria.conductor.knowledge.repository.KnowledgeVersionRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P3/P4/P6: approved knowledge becomes typed, per-item context sections, the
 * query is bounded by the caller's limit, and a section over the provider's
 * sensitivity ceiling never leaves the service.
 */
@ExtendWith(MockitoExtension.class)
class KnowledgeServiceSectionsTest {

    @Mock KnowledgeItemRepository itemRepository;
    @Mock KnowledgeVersionRepository versionRepository;
    @Mock KnowledgeFileService fileService;
    @Mock ApplicationEventPublisher eventPublisher;
    @InjectMocks KnowledgeService knowledgeService;

    @Test
    void restrictedKnowledgeNeverReachesAnInternalCeiling() {
        KnowledgeItem publicItem = item("public", Sensitivity.PUBLIC);
        KnowledgeItem restricted = item("restricted", Sensitivity.RESTRICTED);
        when(itemRepository.findByStatusOrderByUpdatedAtDesc(eq(KnowledgeStatus.APPROVED), any()))
                .thenReturn(List.of(publicItem, restricted));

        List<ContextSection> sections = knowledgeService.buildKnowledgeSections(5, Sensitivity.INTERNAL);

        assertThat(sections).extracting(ContextSection::id)
                .containsExactly("knowledge:" + publicItem.getId());
    }

    @Test
    void eachApprovedItemBecomesItsOwnSection() {
        KnowledgeItem a = item("a", Sensitivity.INTERNAL);
        KnowledgeItem b = item("b", Sensitivity.INTERNAL);
        when(itemRepository.findByStatusOrderByUpdatedAtDesc(eq(KnowledgeStatus.APPROVED), any()))
                .thenReturn(List.of(a, b));

        List<ContextSection> sections = knowledgeService.buildKnowledgeSections(5, Sensitivity.INTERNAL);

        assertThat(sections).hasSize(2);
        assertThat(sections).extracting(ContextSection::id)
                .containsExactly("knowledge:" + a.getId(), "knowledge:" + b.getId());
        assertThat(sections).extracting(ContextSection::title).containsExactly("a", "b");
        assertThat(sections).allSatisfy(section -> {
            assertThat(section.sensitivity()).isEqualTo(Sensitivity.INTERNAL);
            assertThat(section.sourceIds()).hasSize(1);
        });
        assertThat(sections).extracting(section -> section.sourceIds().get(0))
                .containsExactly(a.getId().toString(), b.getId().toString());
        verify(itemRepository).findByStatusOrderByUpdatedAtDesc(eq(KnowledgeStatus.APPROVED), any());
    }

    @Test
    void passesTheBoundToTheQueryAsTheFirstPage() {
        when(itemRepository.findByStatusOrderByUpdatedAtDesc(eq(KnowledgeStatus.APPROVED), any()))
                .thenReturn(List.of());

        knowledgeService.buildKnowledgeSections(3, Sensitivity.INTERNAL);

        ArgumentCaptor<Pageable> page = ArgumentCaptor.forClass(Pageable.class);
        verify(itemRepository).findByStatusOrderByUpdatedAtDesc(eq(KnowledgeStatus.APPROVED), page.capture());
        assertThat(page.getValue().getPageNumber()).isZero();
        assertThat(page.getValue().getPageSize()).isEqualTo(3);
    }

    @Test
    void truncatesTheDescriptionTo500CharsWithAnEllipsis() {
        KnowledgeItem longItem = item("verbose", Sensitivity.PUBLIC);
        longItem.setDescription("X".repeat(600));
        when(itemRepository.findByStatusOrderByUpdatedAtDesc(eq(KnowledgeStatus.APPROVED), any()))
                .thenReturn(List.of(longItem));

        ContextSection section = knowledgeService.buildKnowledgeSections(5, Sensitivity.PUBLIC).get(0);

        assertThat(section.content()).isEqualTo("X".repeat(497) + "...");
        assertThat(section.content()).hasSize(500);
    }

    @Test
    void aMissingSensitivityFailsClosedToInternal() {
        KnowledgeItem unclassified = item("legacy", null);
        when(itemRepository.findByStatusOrderByUpdatedAtDesc(eq(KnowledgeStatus.APPROVED), any()))
                .thenReturn(List.of(unclassified));

        assertThat(knowledgeService.buildKnowledgeSections(5, Sensitivity.INTERNAL)).hasSize(1);
        assertThat(knowledgeService.buildKnowledgeSections(5, Sensitivity.INTERNAL).get(0).sensitivity())
                .isEqualTo(Sensitivity.INTERNAL);
        assertThat(knowledgeService.buildKnowledgeSections(5, Sensitivity.PUBLIC)).isEmpty();
    }

    private static KnowledgeItem item(String name, Sensitivity sensitivity) {
        return KnowledgeItem.builder()
                .id(UUID.randomUUID())
                .name(name)
                .type(KnowledgeType.GUIDELINE)
                .description("description of " + name)
                .status(KnowledgeStatus.APPROVED)
                .sensitivity(sensitivity)
                .build();
    }
}
