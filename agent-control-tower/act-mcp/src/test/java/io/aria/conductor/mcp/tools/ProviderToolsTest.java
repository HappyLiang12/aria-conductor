package io.aria.conductor.mcp.tools;

import io.aria.conductor.agent.dto.LlmProviderRequest;
import io.aria.conductor.agent.dto.LlmProviderResponse;
import io.aria.conductor.agent.service.LlmProviderService;
import io.aria.conductor.common.exception.ResourceNotFoundException;
import io.aria.conductor.common.model.LlmProviderType;
import io.aria.conductor.common.security.ActorPrincipal;
import io.aria.conductor.execution.mcp.McpProperties;
import io.aria.conductor.mcp.McpActorContext;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.mcp.McpToolUtils;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProviderToolsTest {

    private static final String RAW_API_KEY = "sk-raw-secret-1234";
    private static final UUID RUN_ID = UUID.fromString("00000000-0000-0000-0000-000000000b01");

    @Mock LlmProviderService llmProviderService;
    McpProperties mcpProperties;
    ProviderTools tools;

    /** The real reviewed policy: the five provider mutations are OPERATOR_ONLY. */
    private final ToolPolicyRegistry toolPolicies = new ToolPolicyRegistry();

    @BeforeEach
    void setUp() {
        mcpProperties = new McpProperties();
        tools = new ProviderTools(llmProviderService, toolPolicies, mcpProperties);
    }

    private ToolContext context(ActorPrincipal actor) {
        McpSyncServerExchange exchange = mock(McpSyncServerExchange.class);
        when(exchange.transportContext()).thenReturn(McpActorContext.transportContext(actor));
        return new ToolContext(Map.of(McpToolUtils.TOOL_CONTEXT_MCP_EXCHANGE_KEY, exchange));
    }

    private ToolContext workerContext() {
        return context(ActorPrincipal.worker(RUN_ID, Instant.parse("2026-11-09T12:10:00Z")));
    }

    private ToolContext operatorContext() {
        return context(ActorPrincipal.operator(null));
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
                "https://api.openai.com/v1", RAW_API_KEY, "gpt-4o-mini", 2048, operatorContext());

        ArgumentCaptor<LlmProviderRequest> captor = ArgumentCaptor.forClass(LlmProviderRequest.class);
        verify(llmProviderService).create(captor.capture());
        assertThat(captor.getValue().getType()).isEqualTo(LlmProviderType.OPENAI);
        assertThat(captor.getValue().getName()).isEqualTo("prod-openai");
        assertThat(captor.getValue().getMaxTokens()).isEqualTo(2048);
        assertThat(json).contains("\"ok\":true").contains("prod-openai");
    }

    @Test
    void createLlmProvider_invalidType_mapsValidationWithoutStack() {
        String json = tools.createLlmProvider("prod-openai", "gemini", null, null, null, null, operatorContext());

        assertThat(json).contains("\"errorType\":\"VALIDATION\"");
        assertThat(json).contains("OPENAI, ANTHROPIC, AZURE, LOCAL");
        assertThat(json).doesNotContain("stackTrace");
    }

    @Test
    void createLlmProvider_missingType_mapsValidation() {
        String json = tools.createLlmProvider("prod-openai", null, null, null, null, null, operatorContext());

        assertThat(json).contains("\"errorType\":\"VALIDATION\"");
    }

    @Test
    void updateLlmProvider_leavesOmittedFieldsUntouched() {
        UUID id = UUID.randomUUID();
        when(llmProviderService.update(eq(id), any(LlmProviderRequest.class)))
                .thenReturn(provider(id, "renamed", true));

        String json = tools.updateLlmProvider(id, "renamed", null, null, null, null, null, operatorContext());

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

        String json = tools.updateLlmProvider(id, "renamed", null, null, null, null, null, operatorContext());

        assertThat(json).contains("\"errorType\":\"NOT_FOUND\"");
    }

    @Test
    void deleteLlmProvider_wrapsResult() {
        UUID id = UUID.randomUUID();

        String json = tools.deleteLlmProvider(id, operatorContext());

        verify(llmProviderService).delete(id);
        assertThat(json).contains("\"ok\":true").contains("\"deleted\":true");
    }

    @Test
    void deleteLlmProvider_missing_mapsNotFound() {
        UUID id = UUID.randomUUID();
        doThrow(new ResourceNotFoundException("LlmProvider", id))
                .when(llmProviderService).delete(id);

        String json = tools.deleteLlmProvider(id, operatorContext());

        assertThat(json).contains("\"errorType\":\"NOT_FOUND\"");
    }

    @Test
    void activateLlmProvider_wrapsActivatedProvider() {
        UUID id = UUID.randomUUID();
        when(llmProviderService.activate(id)).thenReturn(provider(id, "prod-openai", true));

        String json = tools.activateLlmProvider(id, operatorContext());

        assertThat(json).contains("\"ok\":true").contains("\"active\":true").contains("prod-openai");
    }

    @Test
    void testLlmProvider_wrapsBooleanResult() {
        UUID id = UUID.randomUUID();
        when(llmProviderService.testConnection(id)).thenReturn(true, false);

        String reachable = tools.testLlmProvider(id, operatorContext());
        String unreachable = tools.testLlmProvider(id, operatorContext());

        assertThat(reachable).contains("\"ok\":true").contains("\"reachable\":true");
        assertThat(unreachable).contains("\"ok\":true").contains("\"reachable\":false");
    }

    @Test
    void testLlmProvider_missing_mapsNotFound() {
        UUID id = UUID.randomUUID();
        when(llmProviderService.testConnection(id)).thenThrow(new ResourceNotFoundException("LlmProvider", id));

        String json = tools.testLlmProvider(id, operatorContext());

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

    // ------------------------------------------- operator authority (spec C4/F9)

    /**
     * The five provider mutations are OPERATOR_ONLY in the reviewed catalog: they move the
     * credential the whole installation runs on. A run-scoped worker is refused with an
     * actionable message and the provider service is never reached — and no refusal may
     * echo the key the caller supplied.
     */
    @Test
    void aWorkerActorCannotCreateAProvider() {
        String json = tools.createLlmProvider("prod-openai", "openai", null, RAW_API_KEY, null, null, workerContext());

        assertThat(json).contains("\"ok\":false")
                .contains("\"errorType\":\"FORBIDDEN\"")
                .contains("\"message\":\"Operator authority required: adding an LLM provider is an operator decision. "
                        + "Add it on the Providers page.\"");
        assertThat(json).doesNotContain(RAW_API_KEY);
        verifyNoInteractions(llmProviderService);
    }

    @Test
    void aWorkerActorCannotUpdateAProvider() {
        UUID id = UUID.randomUUID();

        String json = tools.updateLlmProvider(id, "renamed", null, null, RAW_API_KEY, null, null, workerContext());

        assertThat(json).contains("\"ok\":false")
                .contains("\"errorType\":\"FORBIDDEN\"")
                .contains("\"message\":\"Operator authority required: changing an LLM provider is an operator decision. "
                        + "Edit it on the Providers page.\"");
        assertThat(json).doesNotContain(RAW_API_KEY);
        verifyNoInteractions(llmProviderService);
    }

    @Test
    void aWorkerActorCannotDeleteAProvider() {
        UUID id = UUID.randomUUID();

        String json = tools.deleteLlmProvider(id, workerContext());

        assertThat(json).contains("\"ok\":false")
                .contains("\"errorType\":\"FORBIDDEN\"")
                .contains("\"message\":\"Operator authority required: deleting an LLM provider is an operator decision. "
                        + "Delete it on the Providers page.\"");
        verifyNoInteractions(llmProviderService);
    }

    @Test
    void aWorkerActorCannotActivateAProvider() {
        UUID id = UUID.randomUUID();

        String json = tools.activateLlmProvider(id, workerContext());

        assertThat(json).contains("\"ok\":false")
                .contains("\"errorType\":\"FORBIDDEN\"")
                .contains("\"message\":\"Operator authority required: activating an LLM provider is an operator decision. "
                        + "Activate it on the Providers page.\"");
        verifyNoInteractions(llmProviderService);
    }

    @Test
    void aWorkerActorCannotTestAProvider() {
        UUID id = UUID.randomUUID();

        String json = tools.testLlmProvider(id, workerContext());

        assertThat(json).contains("\"ok\":false")
                .contains("\"errorType\":\"FORBIDDEN\"")
                .contains("\"message\":\"Operator authority required: testing an LLM provider is an operator decision. "
                        + "Test it on the Providers page.\"");
        verifyNoInteractions(llmProviderService);
    }

    /** The positive control: the operator credential still drives all five mutations. */
    @Test
    void anOperatorActorCanStillManageProviders() {
        UUID id = UUID.randomUUID();
        when(llmProviderService.create(any(LlmProviderRequest.class))).thenReturn(provider(id, "created", false));
        when(llmProviderService.update(eq(id), any(LlmProviderRequest.class)))
                .thenReturn(provider(id, "updated", false));
        when(llmProviderService.activate(id)).thenReturn(provider(id, "updated", true));
        when(llmProviderService.testConnection(id)).thenReturn(true);

        assertThat(tools.createLlmProvider("created", "openai", null, RAW_API_KEY, null, null, operatorContext()))
                .contains("\"ok\":true");
        assertThat(tools.updateLlmProvider(id, "updated", null, null, null, null, null, operatorContext()))
                .contains("\"ok\":true");
        assertThat(tools.deleteLlmProvider(id, operatorContext())).contains("\"ok\":true");
        assertThat(tools.activateLlmProvider(id, operatorContext())).contains("\"ok\":true");
        assertThat(tools.testLlmProvider(id, operatorContext())).contains("\"ok\":true");

        verify(llmProviderService).create(any(LlmProviderRequest.class));
        verify(llmProviderService).update(eq(id), any(LlmProviderRequest.class));
        verify(llmProviderService).delete(id);
        verify(llmProviderService).activate(id);
        verify(llmProviderService).testConnection(id);
    }
}
