package io.aria.conductor.knowledge.context;

import io.aria.conductor.common.context.ContextAdapter;
import io.aria.conductor.common.context.ContextRequest;
import io.aria.conductor.common.context.ContextSection;
import io.aria.conductor.common.context.qmd.QmdClient;
import io.aria.conductor.common.context.qmd.QmdHit;
import io.aria.conductor.common.context.qmd.QmdQuery;
import io.aria.conductor.common.context.qmd.QmdResult;
import io.aria.conductor.common.model.Sensitivity;
import io.aria.conductor.knowledge.service.KnowledgeService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class KnowledgeContextContributorTest {

    @Mock
    KnowledgeService knowledgeService;

    @Mock
    QmdClient qmd;

    @Test
    void isOptionalAndOrderedAfterMemory() {
        KnowledgeContextContributor contributor = contributor(false);
        assertThat(contributor.id()).isEqualTo("knowledge");
        assertThat(contributor.order()).isEqualTo(50);
        assertThat(contributor.required()).isFalse();
    }

    @Test
    void disabledFallsBackToTheExistingNewestFive() {
        ContextSection fallback = new ContextSection("knowledge:1", "deploy", "blue-green",
                Sensitivity.INTERNAL, List.of(UUID.randomUUID().toString()), false, null);
        when(knowledgeService.buildKnowledgeSections(5, Sensitivity.INTERNAL))
                .thenReturn(List.of(fallback));

        assertThat(contributor(false).contribute(request())).containsExactly(fallback);
        verifyNoInteractions(qmd);
    }

    @Test
    void qmdHitsReplaceTheNewestFive() {
        when(qmd.query(any())).thenReturn(new QmdResult(List.of(
                new QmdHit("t", "qmd://knowledge-verified/a.md", "body", 0.9, Sensitivity.INTERNAL)), null));

        List<ContextSection> sections = contributor(true).contribute(request());

        assertThat(sections).singleElement().satisfies(section -> {
            assertThat(section.id()).isEqualTo("knowledge:qmd");
            assertThat(section.content()).contains("t [qmd://knowledge-verified/a.md]: body");
        });
        verifyNoInteractions(knowledgeService);
    }

    @Test
    void cleanEmptyQmdDoesNotInjectIrrelevantKnowledge() {
        when(qmd.query(any())).thenReturn(QmdResult.empty());
        assertThat(contributor(true).contribute(request())).isEmpty();
        verifyNoInteractions(knowledgeService);
    }

    @Test
    void qmdFailureFallsBackAndReportsTheDegradation() {
        ContextSection fallback = new ContextSection("knowledge:1", "deploy", "blue-green",
                Sensitivity.INTERNAL, List.of(UUID.randomUUID().toString()), false, null);
        when(qmd.query(any())).thenReturn(QmdResult.degraded("qmd-timeout"));
        when(knowledgeService.buildKnowledgeSections(5, Sensitivity.INTERNAL))
                .thenReturn(List.of(fallback));

        List<ContextSection> sections = contributor(true).contribute(request());

        assertThat(sections).contains(fallback);
        assertThat(sections).anySatisfy(section -> {
            assertThat(section.id()).isEqualTo("knowledge:qmd");
            assertThat(section.degradedReason()).isEqualTo("qmd-timeout");
        });
    }

    @Test
    void modeAtRefUsesTheProjectOverride() {
        when(qmd.query(any())).thenReturn(QmdResult.empty());
        ContextRequest request = new ContextRequest(UUID.randomUUID(), "conv-1",
                UUID.randomUUID(), ContextAdapter.SYNC, 1, "ship it",
                null, Sensitivity.INTERNAL, 24_000, "HOST@main", "", List.of());

        contributor(true, "aria-conductor").contribute(request);

        ArgumentCaptor<QmdQuery> query = ArgumentCaptor.forClass(QmdQuery.class);
        verify(qmd).query(query.capture());
        assertThat(query.getValue().scope()).isEqualTo("project:aria-conductor");
    }

    private KnowledgeContextContributor contributor(boolean enabled) {
        return contributor(enabled, "");
    }

    private KnowledgeContextContributor contributor(boolean enabled, String projectOverride) {
        return new KnowledgeContextContributor(knowledgeService, qmd, enabled,
                "http://127.0.0.1:8181", "knowledge-verified", projectOverride,
                "", 1_500, 4, 0.15);
    }

    private static ContextRequest request() {
        return new ContextRequest(UUID.randomUUID(), "conv-1", UUID.randomUUID(), ContextAdapter.SYNC,
                1, "ship it", null, Sensitivity.INTERNAL, 24_000,
                "D:/repo/aria-conductor", "", List.of());
    }
}
