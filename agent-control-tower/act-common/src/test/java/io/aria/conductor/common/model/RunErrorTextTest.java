package io.aria.conductor.common.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** The persisted error text must always fit {@code runs.error_message} (VARCHAR(2000)). */
class RunErrorTextTest {

    @Test
    void keepsAMessageThatFitsTheColumnUnchanged() {
        String message = "x".repeat(RunErrorText.MAX_PERSISTED_LENGTH);

        assertThat(RunErrorText.bound(message)).isEqualTo(message);
    }

    @Test
    void truncatesAnOversizedMessageWithAnExplicitMarker() {
        String bounded = RunErrorText.bound("x".repeat(5000));

        assertThat(bounded).hasSize(1887);
        assertThat(bounded).endsWith("... [truncated, 3150 more characters]");
        assertThat(bounded.length()).isLessThanOrEqualTo(RunErrorText.MAX_PERSISTED_LENGTH);
    }

    @Test
    void nullBecomesAnEmptyStringSoTheColumnNeverGetsTheLiteralNull() {
        assertThat(RunErrorText.bound(null)).isEmpty();
    }
}
