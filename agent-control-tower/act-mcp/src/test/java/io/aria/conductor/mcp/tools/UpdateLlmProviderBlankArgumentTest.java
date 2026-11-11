package io.aria.conductor.mcp.tools;

import io.aria.conductor.agent.repository.LlmProviderRepository;
import io.aria.conductor.agent.service.LlmProviderService;
import io.aria.conductor.common.model.LlmProvider;
import io.aria.conductor.common.model.LlmProviderType;
import io.aria.conductor.common.security.ActorPrincipal;
import io.aria.conductor.execution.mcp.McpProperties;
import io.aria.conductor.mcp.McpActorContext;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.mcp.McpToolUtils;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * T43 Item 1 (Critical): {@code update_llm_provider}'s description promises that an
 * "Omitted (null/blank) argument keeps its current value", and that promise is the contract a
 * model reads. It was false for four of the five optional string fields: only {@code type} was
 * blank-normalized, so a model that sent {@code ""} to mean "keep" blanked the provider's name,
 * base URL, API key or default model — configuration loss driven by a false model-facing promise.
 *
 * <p>These tests drive the tool through the <em>real</em> {@link LlmProviderService} over a
 * mocked repository, so "leaves the stored value unchanged" is asserted on the stored entity the
 * service persists rather than on a request object a hand-written fake interprets. The stored
 * entity is the state the next run authenticates against.
 */
@ExtendWith(MockitoExtension.class)
class UpdateLlmProviderBlankArgumentTest {

    private static final UUID PROVIDER_ID = UUID.fromString("00000000-0000-0000-0000-00000000c143");
    private static final UUID RUN_ID = UUID.fromString("00000000-0000-0000-0000-00000000d143");

    /** Synthetic credential-shaped values only; no real key appears in this fixture. */
    private static final String STORED_KEY = "sk-stored-placeholder-0000";
    private static final String NEW_KEY = "sk-replaced-placeholder-1111";

    private static final String STORED_NAME = "prod-openai";
    private static final String STORED_BASE_URL = "https://api.openai.com/v1";
    private static final String STORED_MODEL = "gpt-4o-mini";

    @Mock LlmProviderRepository providerRepository;

    private ProviderTools tools;
    private LlmProvider stored;

    @BeforeEach
    void setUp() {
        McpProperties mcpProperties = new McpProperties();
        tools = new ProviderTools(new LlmProviderService(providerRepository),
                new ToolPolicyRegistry(), mcpProperties);
        stored = LlmProvider.builder()
                .id(PROVIDER_ID)
                .name(STORED_NAME)
                .type(LlmProviderType.OPENAI)
                .baseUrl(STORED_BASE_URL)
                .apiKey(STORED_KEY)
                .defaultModel(STORED_MODEL)
                .defaultMaxTokens(4096)
                .active(true)
                .build();
    }

    private void stubStoredProvider() {
        when(providerRepository.findById(PROVIDER_ID)).thenReturn(Optional.of(stored));
        when(providerRepository.save(any(LlmProvider.class))).thenAnswer(invocation -> invocation.getArgument(0));
    }

    private void update(String name, String type, String baseUrl, String apiKey, String defaultModel) {
        String json = tools.updateLlmProvider(PROVIDER_ID, name, type, baseUrl, apiKey, defaultModel, null,
                operatorContext());
        assertThat(json).contains("\"ok\":true");
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

    // ---------------------------------------------------------------- name

    @Test
    void aBlankNameKeepsTheStoredName() {
        stubStoredProvider();

        update("", null, null, null, null);

        assertThat(stored.getName()).isEqualTo(STORED_NAME);
    }

    @Test
    void aWhitespaceOnlyNameKeepsTheStoredName() {
        stubStoredProvider();

        update("   ", null, null, null, null);

        assertThat(stored.getName()).isEqualTo(STORED_NAME);
    }

    @Test
    void aNullNameKeepsTheStoredName() {
        stubStoredProvider();

        update(null, null, null, null, null);

        assertThat(stored.getName()).isEqualTo(STORED_NAME);
    }

    @Test
    void aRealNameReplacesTheStoredName() {
        stubStoredProvider();

        update("renamed", null, null, null, null);

        assertThat(stored.getName()).isEqualTo("renamed");
    }

    // ------------------------------------------------------------- baseUrl

    @Test
    void aBlankBaseUrlKeepsTheStoredBaseUrl() {
        stubStoredProvider();

        update(null, null, "", null, null);

        assertThat(stored.getBaseUrl()).isEqualTo(STORED_BASE_URL);
    }

    @Test
    void aNullBaseUrlKeepsTheStoredBaseUrl() {
        stubStoredProvider();

        update(null, null, null, null, null);

        assertThat(stored.getBaseUrl()).isEqualTo(STORED_BASE_URL);
    }

    @Test
    void aRealBaseUrlReplacesTheStoredBaseUrl() {
        stubStoredProvider();

        update(null, null, "https://gateway.internal/v1", null, null);

        assertThat(stored.getBaseUrl()).isEqualTo("https://gateway.internal/v1");
    }

    // -------------------------------------------------------------- apiKey

    @Test
    void aBlankApiKeyKeepsTheStoredKey() {
        stubStoredProvider();

        update(null, null, null, "", null);

        assertThat(stored.getApiKey()).isEqualTo(STORED_KEY);
    }

    @Test
    void aNullApiKeyKeepsTheStoredKey() {
        stubStoredProvider();

        update(null, null, null, null, null);

        assertThat(stored.getApiKey()).isEqualTo(STORED_KEY);
    }

    @Test
    void aRealApiKeyReplacesTheStoredKey() {
        stubStoredProvider();

        update(null, null, null, NEW_KEY, null);

        assertThat(stored.getApiKey()).isEqualTo(NEW_KEY);
    }

    // -------------------------------------------------------- defaultModel

    @Test
    void aBlankDefaultModelKeepsTheStoredModel() {
        stubStoredProvider();

        update(null, null, null, null, "");

        assertThat(stored.getDefaultModel()).isEqualTo(STORED_MODEL);
    }

    @Test
    void aNullDefaultModelKeepsTheStoredModel() {
        stubStoredProvider();

        update(null, null, null, null, null);

        assertThat(stored.getDefaultModel()).isEqualTo(STORED_MODEL);
    }

    @Test
    void aRealDefaultModelReplacesTheStoredModel() {
        stubStoredProvider();

        update(null, null, null, null, "gpt-4o");

        assertThat(stored.getDefaultModel()).isEqualTo("gpt-4o");
    }

    // ------------------------------------------- positive control for `type`

    @Test
    void aBlankTypeKeepsTheStoredType() {
        stubStoredProvider();

        update(null, "", null, null, null);

        assertThat(stored.getType()).isEqualTo(LlmProviderType.OPENAI);
    }

    // ------------------------------------------------------------ authority

    /**
     * The worker refusal is the pin that keeps the blank-argument contract from passing
     * vacuously: a run-scoped credential must not reach the service at all, so no field — blank
     * or not — can be rewritten by a worker.
     */
    @Test
    void aWorkerActorCannotBlankTheStoredConfiguration() {
        String json = tools.updateLlmProvider(PROVIDER_ID, "", "", "", "", "", null, workerContext());

        assertThat(json).contains("\"ok\":false").contains("\"errorType\":\"FORBIDDEN\"");
        assertThat(stored.getName()).isEqualTo(STORED_NAME);
        assertThat(stored.getBaseUrl()).isEqualTo(STORED_BASE_URL);
        assertThat(stored.getApiKey()).isEqualTo(STORED_KEY);
        assertThat(stored.getDefaultModel()).isEqualTo(STORED_MODEL);
        assertThat(stored.getDefaultMaxTokens()).isEqualTo(4096);
        assertThat(stored.getType()).isEqualTo(LlmProviderType.OPENAI);
        verifyNoInteractions(providerRepository);
    }
}
