package io.aria.conductor.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.aria.conductor.common.security.ActorPrincipal;
import io.aria.conductor.execution.security.ActorTokenService;
import io.aria.conductor.execution.security.OperatorSessionService;
import io.aria.conductor.mcp.tools.McpTool;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.WebMvcSseServerTransportProvider;
import io.modelcontextprotocol.server.transport.WebMvcStreamableServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import org.springframework.ai.mcp.McpToolUtils;
import org.springframework.ai.mcp.server.autoconfigure.McpServerProperties;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.ServerRequest;
import org.springframework.web.servlet.function.ServerResponse;

import java.util.Arrays;
import java.util.List;

/**
 * Backend-embedded MCP endpoint. The starter's own auto-configuration serves the
 * SSE protocol; this configuration gates it behind aria.mcp.enabled (yml maps
 * spring.ai.mcp.server.enabled to the same placeholder), hosts the
 * tool-registration helper, and additionally wires the streamable-HTTP transport
 * at /mcp.
 * Transport note (verified from spring-ai-autoconfigure-mcp-server 1.0.9 metadata): this starter serves SSE only (sse-endpoint/sse-message-endpoint; no streamable protocol key). Spec §3 fallback: opencode's remote MCP client speaks streamable HTTP ONLY (live-verified: 404 on /mcp, no SSE fallback), so the SDK's
 * {@code WebMvcStreamableServerTransportProvider} (present in mcp-spring-webmvc
 * 0.18.3 but unwired by 1.0.9 autoconfigure) is wired manually here, following
 * Spring AI 1.1's own wiring
 * (https://github.com/spring-projects/spring-ai/blob/main/auto-configurations/mcp/spring-ai-autoconfigure-mcp-server-webmvc/src/main/java/org/springframework/ai/mcp/server/webmvc/autoconfigure/McpServerStreamableHttpWebMvcAutoConfiguration.java):
 * provider bean + {@code RouterFunction} bean from {@code getRouterFunction()} +
 * a second {@code McpSyncServer} built with {@code McpServer.sync(streamableProvider)}.
 * Both transports coexist: SSE (GET /sse + POST /mcp/message) stays auto-wired;
 * the audit aspect fires in-process either way (both paths invoke the same
 * proxied {@link McpTool} beans via {@code ToolCallback.call}).
 *
 * <p>Task 4 adds the identity boundary on BOTH transports. Because this
 * configuration declares a {@code McpServerTransportProvider} bean, the 1.0.9
 * webmvc auto-configuration (class-level {@code @ConditionalOnMissingBean
 * (McpServerTransportProvider.class)}) backs off, so the SSE provider is built
 * here explicitly with the same {@code contextExtractor} as the streamable one.
 * The actor is resolved from the authenticated Bearer credential of the request,
 * never from tool JSON, and every registered callback rejects caller-supplied
 * reserved {@code _*}-prefixed identity fields before the tool runs.
 */
@Configuration
@ConditionalOnProperty(prefix = "aria.mcp", name = "enabled", havingValue = "true", matchIfMissing = true)
public class McpServerConfig {

    private final List<McpTool> mcpTools;
    private final ObjectMapper objectMapper;
    private final ObjectProvider<ActorTokenService> actorTokens;
    private final ObjectProvider<OperatorSessionService> operatorSessions;

    public McpServerConfig(List<McpTool> mcpTools, ObjectMapper objectMapper,
                           ObjectProvider<ActorTokenService> actorTokens,
                           ObjectProvider<OperatorSessionService> operatorSessions) {
        this.mcpTools = mcpTools;
        this.objectMapper = objectMapper;
        this.actorTokens = actorTokens;
        this.operatorSessions = operatorSessions;
    }

    /**
     * spring-ai 1.0.9's MCP server auto-configuration consumes ToolCallback /
     * ToolCallbackProvider beans only — @Tool-annotated tool beans are NOT
     * auto-discovered. Every {@link McpTool} bean is bridged into one provider,
     * so new tool modules register by implementing the marker (Task 7: knowledge
     * + approval tools) without touching this configuration. Each callback is
     * wrapped so a caller cannot smuggle identity through tool arguments.
     */
    @Bean
    public ToolCallbackProvider ariaToolCallbackProvider() {
        ToolCallback[] callbacks = MethodToolCallbackProvider.builder()
                .toolObjects(mcpTools.toArray())
                .build()
                .getToolCallbacks();
        return ToolCallbackProvider.from(Arrays.stream(callbacks).map(this::identityGuarded).toList());
    }

    /**
     * Streamable-HTTP MCP transport at /mcp (GET establishes the response stream,
     * POST carries messages) — the endpoint opencode's streamable-only remote
     * client negotiates. Registered at the same path McpTokenFilter already
     * guards. The jsonMapper mirrors the SSE autoconfiguration's ObjectMapper
     * injection so both transports serialize identically; the contextExtractor
     * publishes the worker actor resolved from the request's Bearer credential.
     */
    @Bean
    public WebMvcStreamableServerTransportProvider ariaStreamableTransportProvider() {
        return WebMvcStreamableServerTransportProvider.builder()
                .jsonMapper(new JacksonMcpJsonMapper(objectMapper))
                .mcpEndpoint("/mcp")
                .contextExtractor(this::actorTransportContext)
                .build();
    }

    /**
     * Explicit SSE transport with the same actor extractor. Declaring it (a
     * {@code McpServerTransportProvider}) makes the 1.0.9 webmvc auto-configuration
     * back off entirely, so its RouterFunction is re-declared below; the
     * auto-configured {@code mcpSyncServer} consumes this provider, keeping the
     * SSE tool surface identical.
     */
    @Bean
    public WebMvcSseServerTransportProvider ariaSseTransportProvider(McpServerProperties serverProperties) {
        return WebMvcSseServerTransportProvider.builder()
                .jsonMapper(new JacksonMcpJsonMapper(objectMapper))
                .baseUrl(serverProperties.getBaseUrl())
                .sseEndpoint(serverProperties.getSseEndpoint())
                .messageEndpoint(serverProperties.getSseMessageEndpoint())
                .contextExtractor(this::actorTransportContext)
                .build();
    }

    /**
     * Exposes the streamable transport's routes to Spring MVC (RouterFunction
     * mapping) — same registration pattern as the SSE router the 1.0.9
     * autoconfigure declares.
     */
    @Bean
    public RouterFunction<ServerResponse> ariaStreamableMcpRouterFunction(
            WebMvcStreamableServerTransportProvider ariaStreamableTransportProvider) {
        return ariaStreamableTransportProvider.getRouterFunction();
    }

    /** SSE routes (GET /sse + POST /mcp/message) for the explicit SSE provider. */
    @Bean
    public RouterFunction<ServerResponse> ariaSseMcpRouterFunction(
            WebMvcSseServerTransportProvider ariaSseTransportProvider) {
        return ariaSseTransportProvider.getRouterFunction();
    }

    /**
     * Streamable-path MCP server, built from the SAME {@link McpTool} callbacks
     * as the auto-wired SSE server. Tool adaptation uses
     * {@code McpToolUtils.toSyncToolSpecification(ToolCallback)} — the exact
     * adapter Spring AI's own autoconfigure applies to ToolCallbackProviders
     * (https://github.com/spring-projects/spring-ai/blob/v1.1.8/mcp/common/src/main/java/org/springframework/ai/mcp/McpToolUtils.java;
     * identical method ships in 1.0.9's spring-ai-mcp). The SSE
     * autoconfigure's {@code mcpSyncServer} bean is NOT conditional on this bean
     * (v1.0.9 guards it only by server type=SYNC), so both servers coexist.
     */
    @Bean
    public McpSyncServer ariaStreamableMcpServer(WebMvcStreamableServerTransportProvider ariaStreamableTransportProvider,
                                                 ToolCallbackProvider ariaToolCallbackProvider) {
        List<ToolCallback> callbacks = List.of(ariaToolCallbackProvider.getToolCallbacks());
        return McpServer.sync(ariaStreamableTransportProvider)
                .serverInfo("aria-conductor", "0.1.0")
                .capabilities(McpSchema.ServerCapabilities.builder().tools(true).build())
                .tools(McpToolUtils.toSyncToolSpecification(callbacks))
                .build();
    }

    /**
     * Transport-context extractor for both MCP transports: the actor comes from
     * the request's authenticated Bearer credential — the operator credential or a
     * run-scoped worker token, never from the payload. The operator branch is what
     * makes the policy registry's {@code OPERATOR_ONLY} class reachable.
     * Unauthenticated or unverifiable requests publish no actor, so
     * {@code McpActorContext.require} fails rather than guessing.
     */
    private McpTransportContext actorTransportContext(ServerRequest request) {
        ActorPrincipal actor = McpActorContext.resolveBearer(request.headers().firstHeader("Authorization"),
                actorTokens.getIfAvailable(), operatorSessions.getIfAvailable());
        return McpActorContext.transportContext(actor);
    }

    /**
     * Rejects caller-supplied identity claims before delegating: the reserved
     * {@code _}-prefixed argument namespace is framework-owned.
     */
    private ToolCallback identityGuarded(ToolCallback delegate) {
        return new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return delegate.getToolDefinition();
            }

            @Override
            public ToolMetadata getToolMetadata() {
                return delegate.getToolMetadata();
            }

            @Override
            public String call(String toolInput) {
                McpActorContext.rejectSuppliedIdentity(toolInput, objectMapper);
                return delegate.call(toolInput);
            }

            @Override
            public String call(String toolInput, org.springframework.ai.chat.model.ToolContext toolContext) {
                McpActorContext.rejectSuppliedIdentity(toolInput, objectMapper);
                return delegate.call(toolInput, toolContext);
            }
        };
    }
}
