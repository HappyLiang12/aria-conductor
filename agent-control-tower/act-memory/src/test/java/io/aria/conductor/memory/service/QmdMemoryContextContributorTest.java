package io.aria.conductor.memory.service;

import io.aria.conductor.common.context.ContextAdapter;
import io.aria.conductor.common.context.ContextRequest;
import io.aria.conductor.common.context.ContextSection;
import io.aria.conductor.common.context.qmd.QmdClient;
import io.aria.conductor.common.context.qmd.QmdHit;
import io.aria.conductor.common.context.qmd.QmdQuery;
import io.aria.conductor.common.context.qmd.QmdResult;
import io.aria.conductor.common.model.Sensitivity;
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
class QmdMemoryContextContributorTest {

    @Mock
    QmdClient client;

    @Test
    void identityIsMemoryQmdAtOrderThirtyNineAndOptional() {
        QmdMemoryContextContributor contributor = contributor(true);
        assertThat(contributor.id()).isEqualTo("memory:qmd");
        assertThat(contributor.order()).isEqualTo(39);
        assertThat(contributor.required()).isFalse();
    }

    @Test
    void hitsBecomeOneSectionWithProvenance() {
        when(client.query(any())).thenReturn(new QmdResult(List.of(
                new QmdHit("t", "qmd://memory-verified/a.md", "body", 0.9, Sensitivity.INTERNAL)), null));

        List<ContextSection> sections = contributor(true).contribute(request("operator timezone"));

        assertThat(sections).singleElement().satisfies(section -> {
            assertThat(section.id()).isEqualTo("memory:qmd");
            assertThat(section.title()).isEqualTo("Verified memory");
            assertThat(section.content()).contains("t [qmd://memory-verified/a.md]: body");
            assertThat(section.sourceIds()).containsExactly("qmd://memory-verified/a.md");
            assertThat(section.sensitivity()).isEqualTo(Sensitivity.INTERNAL);
            assertThat(section.degradedReason()).isNull();
        });
    }

    @Test
    void emptyRecallProducesNoSection() {
        when(client.query(any())).thenReturn(QmdResult.empty());
        assertThat(contributor(true).contribute(request("nothing"))).isEmpty();
    }

    @Test
    void aQmdFailureDegradesInsteadOfThrowing() {
        when(client.query(any())).thenReturn(QmdResult.degraded("qmd-timeout"));
        ContextSection section = contributor(true).contribute(request("operator")).get(0);
        assertThat(section.content()).isEmpty();
        assertThat(section.degradedReason()).isEqualTo("memory-recall-degraded");
    }

    @Test
    void disabledMeansNoQuery() {
        QmdMemoryContextContributor contributor = contributor(false);
        assertThat(contributor.appliesTo(request("operator"))).isFalse();
        verifyNoInteractions(client);
    }

    @Test
    void aBlankQueryNeverCallsQmd() {
        assertThat(contributor(true).contribute(request("  "))).isEmpty();
        verifyNoInteractions(client);
    }

    @Test
    void modeAtRefUsesTheProjectOverride() {
        when(client.query(any())).thenReturn(QmdResult.empty());
        ContextRequest request = new ContextRequest(UUID.randomUUID(), "conversation-1",
                UUID.randomUUID(), ContextAdapter.CORE, 1, "operator timezone",
                null, Sensitivity.INTERNAL, 24_000, "HOST@main", "", List.of());

        contributor(true, "aria-conductor").contribute(request);

        ArgumentCaptor<QmdQuery> query = ArgumentCaptor.forClass(QmdQuery.class);
        verify(client).query(query.capture());
        assertThat(query.getValue().scope()).isEqualTo("project:aria-conductor");
    }

    private QmdMemoryContextContributor contributor(boolean enabled) {
        return contributor(enabled, "");
    }

    private QmdMemoryContextContributor contributor(boolean enabled, String projectOverride) {
        return new QmdMemoryContextContributor(client, enabled,
                "http://127.0.0.1:8181", "memory-verified", projectOverride,
                "", 1_500, 4, 0.15);
    }

    private static ContextRequest request(String goal) {
        return new ContextRequest(UUID.randomUUID(), "conversation-1", UUID.randomUUID(),
                ContextAdapter.CORE, 1, goal, null, Sensitivity.INTERNAL, 24_000,
                "D:/repo/aria-conductor", "", List.of());
    }
}
