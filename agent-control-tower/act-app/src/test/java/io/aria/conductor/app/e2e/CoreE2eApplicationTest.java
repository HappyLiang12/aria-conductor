package io.aria.conductor.app.e2e;

import org.junit.jupiter.api.Test;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the harness admission defaults against the exact failure mode that
 * motivated them: {@code application.yml} defines {@code aria.runs.max-active}
 * with an {@code ${ARIA_RUNS_MAX_ACTIVE:6}} placeholder, so a plain default
 * property would lose to it, while an explicit environment/CLI value must
 * still win over the harness source.
 */
class CoreE2eApplicationTest {

    @Test
    void harnessValueBeatsTheApplicationYmlPlaceholder() {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addLast(new MapPropertySource(
                "applicationConfig: [classpath:/application.yml]",
                Map.of("aria.runs.max-active", "6", "aria.runs.aria-reserved", "3",
                        "aria.host.files", "READ_ONLY")));

        CoreE2eApplication.installHarnessDefaults(environment);

        assertThat(environment.getProperty("aria.runs.max-active")).isEqualTo("0");
        assertThat(environment.getProperty("aria.runs.aria-reserved")).isEqualTo("0");
        // The suite pins the native-ask gate (kanban HITL, core-permissions, pause),
        // so the harness keeps HOST asks operator-decidable instead of inheriting
        // the production READ_ONLY auto-deny.
        assertThat(environment.getProperty("aria.host.files")).isEqualTo("GATED");
    }

    @Test
    void explicitHigherPrecedenceValueStillWins() {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addLast(new MapPropertySource(
                "applicationConfig: [classpath:/application.yml]",
                Map.of("aria.runs.max-active", "6", "aria.host.files", "READ_ONLY")));
        environment.getPropertySources().addFirst(new MapPropertySource(
                "commandLineArgs", Map.of("aria.runs.max-active", "3", "aria.host.files", "FULL")));

        CoreE2eApplication.installHarnessDefaults(environment);

        assertThat(environment.getProperty("aria.runs.max-active")).isEqualTo("3");
        assertThat(environment.getProperty("aria.host.files")).isEqualTo("FULL");
    }
}
