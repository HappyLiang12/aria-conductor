package io.aria.conductor.execution.runtime;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The launch object's own guard on the values it declares secret: the run-owned
 * core log masks by line, so a value that spans lines can only ever be masked in
 * part. Such a profile is refused where it is built instead of being launched
 * with a redaction claim it cannot keep.
 */
class LaunchProfileTest {

    private static final List<String> ARGV = List.of("synthetic-runtime");

    @Test
    void aDeclaredSecretContainingALineBreakIsRefused() {
        String singleLine = "single-line-synthetic-secret";
        for (String multiLine : List.of("first-line\nsecond-line", "first-line\rsecond-line",
                "value-with-a-trailing-newline\n")) {
            assertThatThrownBy(() -> new LaunchProfile(ARGV, Map.of(), "workspace", null,
                    Set.of(singleLine, multiLine)))
                    .as("a declared secret the line-scoped log cannot mask whole must not be launched")
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("CR or LF")
                    .hasMessageContaining("line")
                    .satisfies(error -> assertThat(error.getMessage())
                            .as("the refusal must never echo the secret it refuses")
                            .doesNotContain(multiLine)
                            .doesNotContain(singleLine));
        }
    }

    @Test
    void aSingleLineSecretIsAcceptedAndCarriedAsDeclared() {
        String secret = "single-line-synthetic-secret";

        LaunchProfile profile = new LaunchProfile(ARGV, Map.of(), "workspace", null, Set.of(secret));

        assertThat(profile.secretValues()).containsExactly(secret);
    }
}
