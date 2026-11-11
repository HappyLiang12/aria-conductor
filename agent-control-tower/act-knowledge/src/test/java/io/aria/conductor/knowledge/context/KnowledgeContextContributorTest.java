package io.aria.conductor.knowledge.context;

import io.aria.conductor.common.context.ContextAdapter;
import io.aria.conductor.common.context.ContextRequest;
import io.aria.conductor.common.context.ContextSection;
import io.aria.conductor.common.model.Sensitivity;
import io.aria.conductor.knowledge.service.KnowledgeService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The knowledge contributor is the only knowledge entry point of the context
 * pipeline (A1-2 order 50). It delegates to the typed section builder with the
 * request's sensitivity ceiling; it is optional, so empty knowledge degrades
 * instead of failing the run.
 */
@ExtendWith(MockitoExtension.class)
class KnowledgeContextContributorTest {

    @Mock KnowledgeService knowledgeService;

    @Test
    void delegatesToTheServiceWithTheRequestCeiling() {
        ContextSection section = new ContextSection("knowledge:" + UUID.randomUUID(), "deploy",
                "blue-green", Sensitivity.INTERNAL, List.of(UUID.randomUUID().toString()), false, null);
        when(knowledgeService.buildKnowledgeSections(5, Sensitivity.INTERNAL))
                .thenReturn(List.of(section));

        List<ContextSection> sections = new KnowledgeContextContributor(knowledgeService)
                .contribute(request(Sensitivity.INTERNAL));

        assertThat(sections).containsExactly(section);
        verify(knowledgeService).buildKnowledgeSections(5, Sensitivity.INTERNAL);
    }

    @Test
    void isOptionalAndOrderedAfterMemory() {
        KnowledgeContextContributor contributor = new KnowledgeContextContributor(knowledgeService);

        assertThat(contributor.id()).isEqualTo("knowledge");
        assertThat(contributor.order()).isEqualTo(50);
        assertThat(contributor.required()).isFalse();
        assertThat(contributor.appliesTo(request(Sensitivity.INTERNAL))).isTrue();
    }

    @Test
    void emptyKnowledgeYieldsNoSections() {
        when(knowledgeService.buildKnowledgeSections(5, Sensitivity.PUBLIC)).thenReturn(List.of());

        assertThat(new KnowledgeContextContributor(knowledgeService)
                .contribute(request(Sensitivity.PUBLIC))).isEmpty();
    }

    private static ContextRequest request(Sensitivity ceiling) {
        return new ContextRequest(UUID.randomUUID(), "conv-1", UUID.randomUUID(), ContextAdapter.SYNC,
                1, "ship it", null, ceiling, 24_000, null, "", List.of());
    }
}
