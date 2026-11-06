package io.aria.conductor.mcp.tools;

import io.aria.conductor.agent.dto.LlmProviderRequest;
import io.aria.conductor.agent.dto.LlmProviderResponse;
import io.aria.conductor.agent.service.LlmProviderService;
import io.aria.conductor.common.exception.ResourceNotFoundException;
import io.aria.conductor.common.model.LlmProviderType;
import io.aria.conductor.execution.mcp.McpProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProviderToolsTest {

    private static final String RAW_API_KEY = "sk-raw-secret-1234";

    @Mock LlmProviderService llmProviderService;
    McpProperties mcpProperties;
    ProviderTools tools;

    @BeforeEach
    void setUp() {
        mcpProperties = new McpProperties();
        tools = new ProviderTools(llmProviderService, mcpProperties);
    }

    private static LlmProviderResponse provider(UUID id, String name, boolean active) {
        return LlmProviderResponse.builder()
                .id(id)
                .name(name)
                .type(LlmProviderType.OPENAI)
                .baseUrl("https://api.openai.com/v1")
                .apiKeyMasked("****a1b2")
                .defaultModel("gpt-4o-mini")
                .defaultMaxTokens(4096)
                .active(active)
                .createdAt(Instant.parse("2026-01-01T00:00:00Z"))
                .updatedAt(Instant.parse("2026-01-02T00:00:00Z"))
                .build();
    }

    @Test
    void listLlmProviders_wrapsMaskedProviders() {
        when(llmProviderService.listAll())
                .thenReturn(List.of(provider(UUID.randomUUID(), "prod-openai", true)));

        String json = tools.listLlmProviders();

        assertThat(json).contains("\"ok\":true").contains("prod-openai").contains("****a1b2");
    }

    @Test
    void getLlmProvider_returnsMaskedProvider() {
        UUID id = UUID.randomUUID();
        when(llmProviderService.getById(id)).thenReturn(provider(id, "prod-openai", false));

        String json = tools.getLlmProvider(id);

        assertThat(json).contains("\"ok\":true").contains(id.toString()).contains("gpt-4o-mini");
    }

    @Test
    void getLlmProvider_missing_mapsNotFound() {
        UUID id = UUID.randomUUID();
        when(llmProviderService.getById(id)).thenThrow(new ResourceNotFoundException("LlmProvider", id));

        String json = tools.getLlmProvider(id);

        assertThat(json).contains("\"errorType\":\"NOT_FOUND\"");
        assertThat(json).doesNotContain("stackTrace");
    }

    @Test
    void createLlmProvider_parsesTypeAndWraps() {
        UUID id = UUID.randomUUID();
        when(llmProviderService.create(any(LlmProviderRequest.class)))
                .thenReturn(provider(id, "prod-openai", false));

        String json = tools.createLlmProvider("prod-openai", "openai",
                "https://api.openai.com/v1", RAW_API_KEY, "gpt-4o-mini", 2048);

        ArgumentCaptor<LlmProviderRequest> captor = ArgumentCaptor.forClass(LlmProviderRequest.class);
        verify(llmProviderService).create(captor.capture());
        assertThat(captor.getValue().getType()).isEqualTo(LlmProviderType.OPENAI);
        assertThat(captor.getValue().getName()).isEqualTo("prod-openai");
        assertThat(captor.getValue().getMaxTokens()).isEqualTo(2048);
        assertThat(json).contains("\"ok\":true").contains("prod-openai");
    }

    @Test
    void createLlmProvider_invalidType_mapsValidationWithoutStack() {
        String json = tools.createLlmProvider("prod-openai", "gemini", null, null, null, null);

        assertThat(json).contains("\"errorType\":\"VALIDATION\"");
        assertThat(json).contains("OPENAI, ANTHROPIC, AZURE, LOCAL");
        assertThat(json).doesNotContain("stackTrace");
    }

    @Test
    void createLlmProvider_missingType_mapsValidation() {
        String json = tools.createLlmProvider("prod-openai", null, null, null, null, null);

        assertThat(json).contains("\"errorType\":\"VALIDATION\"");
    }

    @Test
    void updateLlmProvider_leavesOmittedFieldsUntouched() {
        UUID id = UUID.randomUUID();
        when(llmProviderService.update(eq(id), any(LlmProviderRequest.class)))
                .thenReturn(provider(id, "renamed", true));

        String json = tools.updateLlmProvider(id, "renamed", null, null, null, null, null);

        ArgumentCaptor<LlmProviderRequest> captor = ArgumentCaptor.forClass(LlmProviderRequest.class);
        verify(llmProviderService).update(eq(id), captor.capture());
        assertThat(captor.getValue().getName()).isEqualTo("renamed");
        assertThat(captor.getValue().getType()).isNull();
        assertThat(captor.getValue().getBaseUrl()).isNull();
        assertThat(captor.getValue().getApiKey()).isNull();
        assertThat(captor.getValue().getDefaultModel()).isNull();
        assertThat(captor.getValue().getMaxTokens()).isNull();
        assertThat(json).contains("\"ok\":true").contains("renamed");
    }

    @Test
    void updateLlmProvider_missing_mapsNotFound() {
        UUID id = UUID.randomUUID();
        when(llmProviderService.update(eq(id), any(LlmProviderRequest.class)))
                .thenThrow(new ResourceNotFoundException("LlmProvider", id));

        String json = tools.updateLlmProvider(id, "renamed", null, null, null, null, null);

        assertThat(json).contains("\"errorType\":\"NOT_FOUND\"");
    }

    @Test
    void deleteLlmProvider_wrapsResult() {
        UUID id = UUID.randomUUID();

        String json = tools.deleteLlmProvider(id);

        verify(llmProviderService).delete(id);
        assertThat(json).contains("\"ok\":true").contains("\"deleted\":true");
    }

    @Test
    void deleteLlmProvider_missing_mapsNotFound() {
        UUID id = UUID.randomUUID();
        doThrow(new ResourceNotFoundException("LlmProvider", id))
                .when(llmProviderService).delete(id);

        String json = tools.deleteLlmProvider(id);

        assertThat(json).contains("\"errorType\":\"NOT_FOUND\"");
    }

    @Test
    void activateLlmProvider_wrapsActivatedProvider() {
        UUID id = UUID.randomUUID();
        when(llmProviderService.activate(id)).thenReturn(provider(id, "prod-openai", true));

        String json = tools.activateLlmProvider(id);

        assertThat(json).contains("\"ok\":true").contains("\"active\":true").contains("prod-openai");
    }

    @Test
    void testLlmProvider_wrapsBooleanResult() {
        UUID id = UUID.randomUUID();
        when(llmProviderService.testConnection(id)).thenReturn(true, false);

        String reachable = tools.testLlmProvider(id);
        String unreachable = tools.testLlmProvider(id);

        assertThat(reachable).contains("\"ok\":true").contains("\"reachable\":true");
        assertThat(unreachable).contains("\"ok\":true").contains("\"reachable\":false");
    }

    @Test
    void testLlmProvider_missing_mapsNotFound() {
        UUID id = UUID.randomUUID();
        when(llmProviderService.testConnection(id)).thenThrow(new ResourceNotFoundException("LlmProvider", id));

        String json = tools.testLlmProvider(id);

        assertThat(json).contains("\"errorType\":\"NOT_FOUND\"");
    }

    @Test
    void results_neverContainRawApiKey() {
        UUID id = UUID.randomUUID();
        when(llmProviderService.getById(id)).thenReturn(provider(id, "prod-openai", true));

        String json = tools.getLlmProvider(id);

        assertThat(json).contains("****a1b2");
        assertThat(json).doesNotContain(RAW_API_KEY);
        verify(llmProviderService, never()).getActiveProvider();
    }
}
