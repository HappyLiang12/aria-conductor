package io.aria.conductor;

import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * Minimal boot configuration for {@code @DataJpaTest} slices inside act-common.
 * <p>
 * Mirrors {@code act-test-support}'s {@code JpaSliceConfig} — act-common cannot
 * depend on act-test-support because that module depends on act-common (Maven
 * module cycle). Lives in {@code io.aria.conductor} so {@code @DataJpaTest}'s
 * upward {@code @SpringBootConfiguration} search finds it from any act-common
 * test package. At test runtime only the entities/repositories present on the
 * current module's classpath are picked up.
 */
@SpringBootConfiguration
@EnableAutoConfiguration
@EntityScan("io.aria.conductor")
@EnableJpaRepositories("io.aria.conductor")
public class CommonJpaSliceConfig {
}
