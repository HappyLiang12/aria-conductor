package io.aria.conductor.dashboard.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration("dashboardWebConfig")
public class WebConfig implements WebMvcConfigurer {

    private static final String CORRELATION_ID_HEADER = "X-Correlation-ID";

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**")
                .allowedOriginPatterns("http://localhost:*")
                .allowedMethods("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS")
                .allowedHeaders("*")
                .exposedHeaders(CORRELATION_ID_HEADER)
                .allowCredentials(false)
                .maxAge(3600);
    }
}
