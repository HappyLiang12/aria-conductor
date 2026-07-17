package io.aria.conductor.execution.llm;

import io.aria.conductor.agent.repository.LlmProviderRepository;
import io.aria.conductor.agent.service.SystemConfigService;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.beans.factory.annotation.Qualifier;

@Configuration
@EnableConfigurationProperties(LlmProperties.class)
public class LlmClientConfig {

    @Bean("rawLlmClient")
    public LlmClient rawLlmClient(LlmProperties properties, LlmProviderRepository providerRepository, SystemConfigService systemConfigService) {
        return new DefaultLlmClient(properties, providerRepository, systemConfigService);
    }

    @Bean
    @Primary
    public LlmClient resilientLlmClient(@Qualifier("rawLlmClient") LlmClient rawLlmClient, SystemConfigService systemConfigService) {
        return new LlmClientRetryDecorator(rawLlmClient, systemConfigService);
    }
}
