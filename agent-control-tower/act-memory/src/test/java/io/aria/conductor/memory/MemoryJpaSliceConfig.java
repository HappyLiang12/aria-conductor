package io.aria.conductor.memory;

import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * Minimal boot configuration for {@code @DataJpaTest} slices inside act-memory.
 * <p>
 * Mirrors {@code act-common}'s {@code CommonJpaSliceConfig}: act-memory deliberately
 * has no compile or test dependency on act-test-support (only act-common, JPA,
 * validation, H2), so the module carries its own slice bootstrap. At test runtime
 * only the entities/repositories present on this module's classpath are picked up.
 */
@SpringBootConfiguration
@EnableAutoConfiguration
@EntityScan("io.aria.conductor")
@EnableJpaRepositories("io.aria.conductor")
public class MemoryJpaSliceConfig {
}
