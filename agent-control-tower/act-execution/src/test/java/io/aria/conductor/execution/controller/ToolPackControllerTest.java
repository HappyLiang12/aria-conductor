package io.aria.conductor.execution.controller;

import io.aria.conductor.execution.credential.PackCredentialService;
import io.aria.conductor.test.WebMvcTestBase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ToolPackControllerTest extends WebMvcTestBase {

    private final PackCredentialService credentialService = mock(PackCredentialService.class);
    private final MockMvc mvc = mockMvcFor(new ToolPackController(credentialService));

    @Test
    void storeCredential_agentScoped_delegatesAllFieldsToService() throws Exception {
        mvc.perform(post("/api/v1/packs/pack-1/credentials")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("key", "API_TOKEN", "value", "s3cret", "agentId", "agent-9"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("stored"))
                .andExpect(jsonPath("$.key").value("API_TOKEN"))
                // F8: the git handoff now resolves the token per request, so a
                // stored credential applies without restarting the backend.
                .andExpect(jsonPath("$.restartRequired").value(false));

        verify(credentialService).store("pack-1", "agent-9", "API_TOKEN", "s3cret");
    }

    @Test
    void storeCredential_withoutAgentId_storesGlobally() throws Exception {
        mvc.perform(post("/api/v1/packs/pack-1/credentials")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("key", "API_TOKEN", "value", "s3cret"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.key").value("API_TOKEN"));

        ArgumentCaptor<String> agentIdCaptor = ArgumentCaptor.forClass(String.class);
        verify(credentialService).store(
                org.mockito.ArgumentMatchers.eq("pack-1"), agentIdCaptor.capture(),
                org.mockito.ArgumentMatchers.eq("API_TOKEN"), org.mockito.ArgumentMatchers.eq("s3cret"));
        assertThat(agentIdCaptor.getValue()).isNull();
    }

    @ParameterizedTest
    @MethodSource("incompleteCredentialBodies")
    void storeCredential_missingKeyOrValue_returns400WithoutStoring(Map<String, String> body)
            throws Exception {
        mvc.perform(post("/api/v1/packs/pack-1/credentials")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(body)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("key and value are required"));
        verifyNoInteractions(credentialService);
    }

    static Stream<Map<String, String>> incompleteCredentialBodies() {
        return Stream.of(
                Map.of("value", "s3cret"),          // key missing
                Map.of("key", "API_TOKEN"),         // value missing
                new HashMap<>());                    // both missing
    }
}
