package io.aria.conductor.test;

import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * Minimal boot configuration for {@code @DataJpaTest} slices in modules that
 * have no {@code @SpringBootApplication} of their own.
 * <p>
 * Scans the whole {@code io.aria.conductor} tree; at test runtime only the
 * entities/repositories present on the current module's classpath are picked
 * up, so this single config serves every module (one Spring context cache key
 * per module — never create per-test variants).
 */
@SpringBootConfiguration
@EnableAutoConfiguration
@EntityScan("io.aria.conductor")
@EnableJpaRepositories("io.aria.conductor")
public class JpaSliceConfig {
}
