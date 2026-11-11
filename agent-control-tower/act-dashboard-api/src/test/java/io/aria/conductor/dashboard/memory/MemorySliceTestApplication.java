package io.aria.conductor.dashboard.memory;

import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Minimal boot configuration for the memory MVC slice tests. {@code act-dashboard-api}
 * has no application class of its own (the entry point lives in {@code act-app}), so the
 * {@code @WebMvcTest} bootstrap needs a {@code @SpringBootConfiguration} inside the test
 * classpath. Scanning is scoped to this package so only the memory web surface is loaded.
 */
@SpringBootApplication(scanBasePackages = "io.aria.conductor.dashboard.memory")
class MemorySliceTestApplication {
}
