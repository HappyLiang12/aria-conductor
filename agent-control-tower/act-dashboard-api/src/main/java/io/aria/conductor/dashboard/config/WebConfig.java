package io.aria.conductor.dashboard.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration("dashboardWebConfig")
public class WebConfig implements WebMvcConfigurer {

    private static final String CORRELATION_ID_HEADER = "X-Correlation-ID";

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        // C1: use origin patterns so any localhost dev port (5173, 5174, 5199, ...) is allowed,
        // matching act-common's WebConfig. The previous fixed allowedOrigins list blocked write
        // operations (POST/PUT/DELETE) whenever the frontend ran on a non-standard port.
        registry.addMapping("/**")
                .allowedOriginPatterns("http://localhost:*")
                .allowedMethods("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS")
                .allowedHeaders("*")
                .exposedHeaders(CORRELATION_ID_HEADER)
                .maxAge(3600)
                .allowCredentials(false);
    }
}
