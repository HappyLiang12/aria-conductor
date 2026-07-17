package io.aria.conductor;

import io.aria.conductor.aria.config.AriaProperties;
import io.aria.conductor.dashboard.report.ReportProperties;
import io.aria.conductor.execution.circuit.CircuitBreakerProperties;
import io.aria.conductor.execution.llm.LlmProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(scanBasePackages = "io.aria.conductor")
@EnableJpaRepositories(basePackages = "io.aria.conductor")
@EntityScan(basePackages = "io.aria.conductor")
@EnableScheduling
@EnableConfigurationProperties({
        LlmProperties.class,
        CircuitBreakerProperties.class,
        AriaProperties.class,
        ReportProperties.class
})
public class ActApplication {

    public static void main(String[] args) {
        SpringApplication.run(ActApplication.class, args);
    }
}
