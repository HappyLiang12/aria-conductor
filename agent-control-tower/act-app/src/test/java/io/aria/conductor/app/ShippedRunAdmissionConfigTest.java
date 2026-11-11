package io.aria.conductor.app;

import io.aria.conductor.execution.runtime.RunAdmissionProperties;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.OptionalInt;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T43 Item 4 (Minor 1): {@code application.yml} ships {@code aria.runs.aria-reserved: ${...:3}} but only
 * {@link RunAdmissionProperties#DEFAULT_ARIA_RESERVED} was pinned. Reverting the YAML default to 1 leaves the
 * unit suite green while re-blocking every non-Aria conversation behind the Aria reservation — the shipped
 * install would be wrong and no test would say so. This reads the real resource, not a copy of its value.
 */
class ShippedRunAdmissionConfigTest {

    /** The shipped entry: key, environment override and default value. */
    private static final Pattern ARIA_RESERVED = Pattern.compile(
            "(?m)^\\s*aria-reserved:\\s*\\$\\{ARIA_RUNS_ARIA_RESERVED:(\\d+)}");

    @Test
    void theShippedYamlReservesTheSameAriaSlotsAsTheCodeDefault() throws IOException {
        OptionalInt shipped = defaultAriaReserved(shippedApplicationYml());

        assertThat(shipped)
                .as("application.yml must ship aria-reserved with a default value")
                .isPresent();
        assertThat(shipped.getAsInt())
                .as("a shipped default of 1 re-blocks every other conversation with a green suite")
                .isEqualTo(3)
                .isEqualTo(RunAdmissionProperties.DEFAULT_ARIA_RESERVED);
    }

    /**
     * Positive control: the reader reports whatever the entry says, so the assertion above cannot pass by
     * matching only the value it expects.
     */
    @Test
    void theReaderReportsTheShippedValueRatherThanOnlyTheExpectedOne() {
        assertThat(defaultAriaReserved("    aria-reserved: ${ARIA_RUNS_ARIA_RESERVED:1}\n"))
                .hasValue(1);
    }

    private static OptionalInt defaultAriaReserved(String yaml) {
        Matcher matcher = ARIA_RESERVED.matcher(yaml);
        if (!matcher.find()) {
            return OptionalInt.empty();
        }
        return OptionalInt.of(Integer.parseInt(matcher.group(1)));
    }

    private static String shippedApplicationYml() throws IOException {
        try (InputStream resource = ShippedRunAdmissionConfigTest.class.getResourceAsStream("/application.yml")) {
            assertThat(resource).as("/application.yml is on the test classpath (act-app main resources)").isNotNull();
            return new String(resource.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
