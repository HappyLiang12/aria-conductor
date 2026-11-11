package io.aria.conductor.dashboard.agent;

import io.aria.conductor.agent.dto.LlmProviderRequest;
import io.aria.conductor.agent.dto.LlmProviderResponse;
import io.aria.conductor.agent.service.LlmProviderService;
import io.aria.conductor.common.exception.ResourceNotFoundException;
import io.aria.conductor.common.model.LlmProviderType;
import io.aria.conductor.common.security.ActorPrincipal;
import io.aria.conductor.execution.security.ActorTokenService;
import io.aria.conductor.execution.security.OperatorAuthorityResolver;
import io.aria.conductor.execution.security.OperatorSessionService;
import io.aria.conductor.test.WebMvcTestBase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class LlmProviderControllerTest extends WebMvcTestBase {

    private static final Instant NOW = Instant.parse("2026-11-11T10:00:00Z");
    private static final String OPERATOR_AUTHORIZATION = "Bearer operator-bearer-secret";
    private static final String WORKER_AUTHORIZATION = "Bearer worker-token";

    private final LlmProviderService providerService = mock(LlmProviderService.class);
    private final ActorTokenService actorTokens = mock(ActorTokenService.class);
    private final OperatorAuthorityResolver operatorAuthority =
            new OperatorAuthorityResolver(
                    new OperatorSessionService("operator-bearer-secret", Duration.ofHours(8),
                            "http://localhost:5173", false, Clock.systemUTC()),
                    actorTokens, "");
    private final MockMvc mvc = mockMvcFor(
            new LlmProviderController(providerService, operatorAuthority));

    private LlmProviderResponse response(UUID id, String masked) {
        return LlmProviderResponse.builder().id(id).name("prod").type(LlmProviderType.OPENAI)
                .baseUrl("https://api.openai.com/v1").apiKeyMasked(masked)
                .defaultModel("gpt-4o").defaultMaxTokens(4096).active(false).build();
    }

    @Test
    void create_returns201WithMaskedKeyOnly() throws Exception {
        UUID id = UUID.randomUUID();
        when(providerService.create(any())).thenReturn(response(id, "****1234"));

        String body = mvc.perform(post("/api/v1/llm-providers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(LlmProviderRequest.builder()
                                .name("prod").type(LlmProviderType.OPENAI)
                                .apiKey("sk-secret1234").build())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(id.toString()))
                .andExpect(jsonPath("$.apiKeyMasked").value("****1234"))
                .andReturn().getResponse().getContentAsString();

        // The raw secret must never appear in the serialized response.
        org.assertj.core.api.Assertions.assertThat(body).doesNotContain("sk-secret1234");
        org.assertj.core.api.Assertions.assertThat(body).doesNotContain("apiKey\":");
    }

    @Test
    void create_blankName_returns400() throws Exception {
        mvc.perform(post("/api/v1/llm-providers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "", "type", "OPENAI"))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void create_missingType_returns400() throws Exception {
        mvc.perform(post("/api/v1/llm-providers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "prod"))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void listAll_returns200WithMaskedEntries() throws Exception {
        when(providerService.listAll()).thenReturn(List.of(
                response(UUID.randomUUID(), "****1111"),
                response(UUID.randomUUID(), "****2222")));

        mvc.perform(get("/api/v1/llm-providers"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].apiKeyMasked").value("****1111"))
                .andExpect(jsonPath("$[1].apiKeyMasked").value("****2222"));
    }

    @Test
    void getById_returns200() throws Exception {
        UUID id = UUID.randomUUID();
        when(providerService.getById(id)).thenReturn(response(id, "****9999"));

        mvc.perform(get("/api/v1/llm-providers/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.apiKeyMasked").value("****9999"));
    }

    @Test
    void getById_returns404WhenMissing() throws Exception {
        UUID id = UUID.randomUUID();
        when(providerService.getById(id)).thenThrow(new ResourceNotFoundException("LlmProvider", id));

        mvc.perform(get("/api/v1/llm-providers/" + id))
                .andExpect(status().isNotFound());
    }

    @Test
    void update_returns200() throws Exception {
        UUID id = UUID.randomUUID();
        when(providerService.update(eq(id), any())).thenReturn(response(id, "****5555"));

        mvc.perform(put("/api/v1/llm-providers/" + id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(LlmProviderRequest.builder()
                                .name("renamed").type(LlmProviderType.ANTHROPIC).build())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.apiKeyMasked").value("****5555"));
    }

    @Test
    void update_returns404WhenMissing() throws Exception {
        UUID id = UUID.randomUUID();
        when(providerService.update(eq(id), any()))
                .thenThrow(new ResourceNotFoundException("LlmProvider", id));

        mvc.perform(put("/api/v1/llm-providers/" + id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(LlmProviderRequest.builder()
                                .name("x").type(LlmProviderType.OPENAI).build())))
                .andExpect(status().isNotFound());
    }

    @Test
    void delete_returns204() throws Exception {
        UUID id = UUID.randomUUID();

        mvc.perform(delete("/api/v1/llm-providers/" + id))
                .andExpect(status().isNoContent());
        verify(providerService).delete(id);
    }

    @Test
    void activate_returns200WithActiveTrue() throws Exception {
        UUID id = UUID.randomUUID();
        LlmProviderResponse active = LlmProviderResponse.builder().id(id).name("p")
                .type(LlmProviderType.OPENAI).apiKeyMasked("****0000").active(true).build();
        when(providerService.activate(id)).thenReturn(active);

        mvc.perform(post("/api/v1/llm-providers/" + id + "/activate"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(true));
    }

    @Test
    void testConnection_reportsSuccess() throws Exception {
        UUID id = UUID.randomUUID();
        when(providerService.testConnection(id)).thenReturn(true);

        mvc.perform(post("/api/v1/llm-providers/" + id + "/test"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("Connection successful"));
    }

    @Test
    void testConnection_reportsFailure() throws Exception {
        UUID id = UUID.randomUUID();
        when(providerService.testConnection(id)).thenReturn(false);

        mvc.perform(post("/api/v1/llm-providers/" + id + "/test"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("Connection failed. Check the provider's base URL"
                        + " and API key in Configure > LLM Providers."));
    }

    // -------------------------------------------------- mutation authority boundary

    private static final String SURFACE = " on the LLM Providers tab in Configure.";

    /** Every guarded mutation with its exact actionable refusal (the MCP tool voice). */
    private static Stream<Arguments> guardedMutations() {
        UUID id = UUID.fromString("00000000-0000-0000-0000-000000000001");
        String body = json(Map.of("name", "prod", "type", "OPENAI"));
        return Stream.of(
                Arguments.of("POST", "/api/v1/llm-providers", body,
                        "Operator authority required: adding an LLM provider is an operator decision."
                                + " Add it" + SURFACE),
                Arguments.of("PUT", "/api/v1/llm-providers/" + id, body,
                        "Operator authority required: changing an LLM provider is an operator decision."
                                + " Edit it" + SURFACE),
                Arguments.of("DELETE", "/api/v1/llm-providers/" + id, null,
                        "Operator authority required: deleting an LLM provider is an operator decision."
                                + " Delete it" + SURFACE),
                Arguments.of("POST", "/api/v1/llm-providers/" + id + "/test", null,
                        "Operator authority required: testing an LLM provider is an operator decision."
                                + " Test it" + SURFACE),
                Arguments.of("POST", "/api/v1/llm-providers/" + id + "/activate", null,
                        "Operator authority required: activating an LLM provider is an operator decision."
                                + " Activate it" + SURFACE));
    }

    private static MockHttpServletRequestBuilder mutation(String method, String path, String body) {
        MockHttpServletRequestBuilder builder = request(HttpMethod.valueOf(method), path);
        if (body != null) {
            builder = builder.contentType(MediaType.APPLICATION_JSON).content(body);
        }
        return builder;
    }

    private static RequestPostProcessor remote() {
        return request -> {
            request.setRemoteAddr("203.0.113.7");
            return request;
        };
    }

    @ParameterizedTest(name = "{0} {1} refuses a worker credential")
    @MethodSource("guardedMutations")
    void workerBearerIsForbidden_andNeverCallsTheService(String method, String path, String body,
                                                         String refusal) throws Exception {
        when(actorTokens.resolveBearer(WORKER_AUTHORIZATION)).thenReturn(Optional.of(
                ActorPrincipal.worker(UUID.randomUUID(), NOW.plus(Duration.ofHours(1)))));

        mvc.perform(mutation(method, path, body).header("Authorization", WORKER_AUTHORIZATION))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value(refusal));

        verifyNoInteractions(providerService);
    }

    @Test
    void create_anonymousRemoteIsUnauthorized_andNeverCallsTheService() throws Exception {
        mvc.perform(post("/api/v1/llm-providers")
                        .with(remote())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "prod", "type", "OPENAI"))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value(
                        "Operator authority required: adding an LLM provider is an operator decision."
                                + " Add it" + SURFACE));

        verifyNoInteractions(providerService);
    }

    /** Positive control: the operator bearer credential is honoured from a remote address. */
    @Test
    void create_operatorBearerFromRemoteSucceeds() throws Exception {
        UUID id = UUID.randomUUID();
        when(providerService.create(any())).thenReturn(response(id, "****1234"));

        mvc.perform(post("/api/v1/llm-providers")
                        .with(remote())
                        .header("Authorization", OPERATOR_AUTHORIZATION)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "prod", "type", "OPENAI"))))
                .andExpect(status().isCreated());

        verify(providerService).create(any());
    }

    /** Reads stay open: the Configure surface lists providers without a session. */
    @Test
    void listAll_anonymousRemoteIsStillReadable() throws Exception {
        when(providerService.listAll()).thenReturn(List.of(response(UUID.randomUUID(), "****1111")));

        mvc.perform(get("/api/v1/llm-providers").with(remote()))
                .andExpect(status().isOk());
    }
}
