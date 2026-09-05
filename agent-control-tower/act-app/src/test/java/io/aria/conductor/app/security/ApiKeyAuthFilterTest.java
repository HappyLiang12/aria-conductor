package io.aria.conductor.app.security;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import static io.aria.conductor.app.security.SecurityPropertiesTest.sha256Hex;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Filter-level MockMvc tests for {@link ApiKeyAuthFilter} (AC1 mock-MVC coverage, AC3, AC4, AC6).
 */
class ApiKeyAuthFilterTest {

    private final SecurityProperties props = new SecurityProperties();

    private MockMvc mockMvc() {
        return MockMvcBuilders.standaloneSetup(new ProbeController())
                .addFilters(new ApiKeyAuthFilter(props))
                .build();
    }

    @Test
    void disabledAuthPassesEverythingThrough() throws Exception {
        props.setEnabled(false);
        props.setApiKeys("");
        mockMvc().perform(get("/api/v1/agents"))
                .andExpect(status().isOk());
        mockMvc().perform(post("/api/v1/runs/{id}/resume", "11111111-1111-1111-1111-111111111111"))
                .andExpect(status().isOk());
    }

    @Test
    void protectedRoutesRequireCredential() throws Exception {
        props.setEnabled(true);
        props.setApiKeys("secret-key");
        for (String path : new String[]{
                "/api/v1/agents",
                "/api/v1/workflows",
                "/api/v1/runs",
                "/api/v1/tools",
                "/api/v1/skills",
                "/api/v1/knowledge",
                "/api/v1/llm-providers",
                "/api/v1/dashboard/summary",
                "/api/v1/agents/" + "22222222-2222-2222-2222-222222222222",
        }) {
            mockMvc().perform(get(path))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.error").value("unauthorized"))
                    .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer"));
        }
        mockMvc().perform(post("/api/v1/runs/{id}/resume", "22222222-2222-2222-2222-222222222222"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void validKeyViaBearerReachesController() throws Exception {
        props.setEnabled(true);
        props.setApiKeys("secret-key");
        mockMvc().perform(get("/api/v1/agents").header(HttpHeaders.AUTHORIZATION, "Bearer secret-key"))
                .andExpect(status().isOk());
    }

    @Test
    void validKeyViaXApiKeyHeaderReachesController() throws Exception {
        props.setEnabled(true);
        props.setApiKeys("secret-key");
        mockMvc().perform(get("/api/v1/workflows").header("X-API-Key", "secret-key"))
                .andExpect(status().isOk());
    }

    @Test
    void hashedConfiguredKeyAcceptsPreimageOverEitherHeader() throws Exception {
        props.setEnabled(true);
        props.setApiKeys("sha256:" + sha256Hex("hash-secret"));
        mockMvc().perform(get("/api/v1/agents").header(HttpHeaders.AUTHORIZATION, "Bearer hash-secret"))
                .andExpect(status().isOk());
        mockMvc().perform(get("/api/v1/agents").header("X-API-Key", "hash-secret"))
                .andExpect(status().isOk());
    }

    @Test
    void wrongUnknownOrMalformedCredentialsAreRejectedWith401() throws Exception {
        props.setEnabled(true);
        props.setApiKeys("secret-key");
        // Unknown key.
        mockMvc().perform(get("/api/v1/agents").header(HttpHeaders.AUTHORIZATION, "Bearer nope"))
                .andExpect(status().isUnauthorized());
        // Wrong key via X-API-Key.
        mockMvc().perform(get("/api/v1/agents").header("X-API-Key", "nope"))
                .andExpect(status().isUnauthorized());
        // Missing header.
        mockMvc().perform(get("/api/v1/agents"))
                .andExpect(status().isUnauthorized());
        // Bare "Bearer" with no token.
        mockMvc().perform(get("/api/v1/agents").header(HttpHeaders.AUTHORIZATION, "Bearer"))
                .andExpect(status().isUnauthorized());
        mockMvc().perform(get("/api/v1/agents").header(HttpHeaders.AUTHORIZATION, "Bearer "))
                .andExpect(status().isUnauthorized());
        // Authorization header with a non-Bearer scheme.
        mockMvc().perform(get("/api/v1/agents").header(HttpHeaders.AUTHORIZATION, "Basic abc"))
                .andExpect(status().isUnauthorized());
        // Empty X-API-Key.
        mockMvc().perform(get("/api/v1/agents").header("X-API-Key", ""))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void rejectedResponseBodyNeverLeaksDetail() throws Exception {
        props.setEnabled(true);
        props.setApiKeys("secret-key");
        mockMvc().perform(get("/api/v1/agents").header(HttpHeaders.AUTHORIZATION, "Bearer wrong"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("unauthorized"))
                .andExpect(jsonPath("$.message").value("Invalid API key"))
                .andExpect(jsonPath("$.timestamp").doesNotExist())
                .andExpect(jsonPath("$.stackTrace").doesNotExist());
    }

    @Test
    void nonApiPathsAndPreflightAreNeverChallenged() throws Exception {
        props.setEnabled(true);
        props.setApiKeys("secret-key");
        // Actuator health/info stay anonymous.
        mockMvc().perform(get("/actuator/health")).andExpect(status().isOk());
        mockMvc().perform(get("/actuator/info")).andExpect(status().isOk());
        // Other non-API surfaces are not challenged either.
        mockMvc().perform(get("/some-other-surface")).andExpect(status().isOk());
        // OPTIONS to an API route carries no credentials and must pass the gate (CORS preflight).
        mockMvc().perform(options("/api/v1/agents"))
                .andExpect(status().isOk());
    }

    @RestController
    static class ProbeController {
        @GetMapping({"/api/v1/agents", "/api/v1/agents/{id}", "/api/v1/workflows", "/api/v1/runs",
                "/api/v1/tools", "/api/v1/skills", "/api/v1/knowledge", "/api/v1/llm-providers",
                "/api/v1/dashboard/summary"})
        public String apiGet() {
            return "ok";
        }

        @PostMapping("/api/v1/runs/{id}/resume")
        public String apiPost() {
            return "ok";
        }

        @RequestMapping(value = "/api/v1/agents", method = RequestMethod.OPTIONS)
        public String apiOptions() {
            return "ok";
        }

        @GetMapping({"/actuator/health", "/actuator/info", "/some-other-surface"})
        public String other() {
            return "ok";
        }
    }
}
