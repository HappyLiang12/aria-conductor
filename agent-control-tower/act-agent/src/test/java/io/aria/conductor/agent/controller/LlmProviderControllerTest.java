package io.aria.conductor.agent.controller;

import io.aria.conductor.agent.dto.LlmProviderRequest;
import io.aria.conductor.agent.dto.LlmProviderResponse;
import io.aria.conductor.agent.service.LlmProviderService;
import io.aria.conductor.common.exception.ResourceNotFoundException;
import io.aria.conductor.common.model.LlmProviderType;
import io.aria.conductor.test.WebMvcTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class LlmProviderControllerTest extends WebMvcTestBase {

    private final LlmProviderService providerService = mock(LlmProviderService.class);
    private final MockMvc mvc = mockMvcFor(new LlmProviderController(providerService));

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
                .andExpect(jsonPath("$.message").value("Connection failed"));
    }
}
