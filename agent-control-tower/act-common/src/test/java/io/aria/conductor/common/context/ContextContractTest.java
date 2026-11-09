package io.aria.conductor.common.context;

import io.aria.conductor.common.model.Sensitivity;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ContextContractTest {

    @Test
    void sectionDerivesEstimatesAndDefaultsSensitivity() {
        ContextSection section = new ContextSection(
                "memory:1", "Memory", "abcd", null, List.of("fact-1"), false, null);

        assertThat(section.estimatedChars()).isEqualTo(4);
        assertThat(section.estimatedTokens()).isEqualTo(2);
        assertThat(section.sensitivity()).isEqualTo(Sensitivity.INTERNAL);
    }

    @Test
    void sectionRejectsMissingIdAndNullsContent() {
        assertThatThrownBy(() -> new ContextSection(
                null, "Memory", "x", Sensitivity.INTERNAL, List.of(), false, null))
                .isInstanceOf(NullPointerException.class);

        ContextSection section = new ContextSection(
                "goal", "Goal", null, Sensitivity.INTERNAL, List.of(), false, null);
        assertThat(section.content()).isEmpty();
    }

    @Test
    void requestDefaultsAreFailClosed() {
        ContextRequest request = new ContextRequest(
                UUID.randomUUID(), null, UUID.randomUUID(), ContextAdapter.SYNC, 1,
                "hello", null, null, 0, null, null, null);

        assertThat(request.maxSensitivity()).isEqualTo(Sensitivity.INTERNAL);
        assertThat(request.maxChars()).isEqualTo(24_000);
        assertThat(request.priorHistory()).isEmpty();
        assertThat(request.clientPolicy()).isEmpty();
    }

    @Test
    void requestCanNarrowSensitivityForAContributor() {
        ContextRequest request = new ContextRequest(
                UUID.randomUUID(), "c", UUID.randomUUID(), ContextAdapter.CORE, 2,
                "goal", null, Sensitivity.RESTRICTED, 1000, "repo", "skill", List.of());

        assertThat(request.withMaxSensitivity(Sensitivity.CONFIDENTIAL).maxSensitivity())
                .isEqualTo(Sensitivity.CONFIDENTIAL);
    }
}
