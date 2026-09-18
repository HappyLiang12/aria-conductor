package io.aria.conductor.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.aria.conductor.mcp.tools.McpTool;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.server.transport.WebMvcStreamableServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.mcp.McpToolUtils;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.ServerResponse;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;

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
 * <p>C4 ruling 3 identity propagation for the streamable path: the servlet
 * filter's {@link McpCallerContext} ThreadLocal does NOT survive onto the
 * transport's async invocation thread, so the validated {@code Authorization}
 * header travels through the SDK's own transport context
 * ({@code contextExtractor} -> {@code McpSyncServerExchange.transportContext()},
 * surfaced to Spring AI tool callbacks as the {@code ToolContext} "exchange"
 * entry) and {@link IdentityBindingToolCallback} re-binds the identity around the
 * real tool invocation. The SSE autoconfigured server keeps the raw callbacks
 * (no identity binding is possible there — {@link McpTokenFilter} therefore
 * rejects worker credentials on those paths).
 */
@Configuration
@ConditionalOnProperty(prefix = "aria.mcp", name = "enabled", havingValue = "true", matchIfMissing = true)
public class McpServerConfig {

    /** Transport-context key carrying the raw Authorization header (ruling 3). */
    static final String AUTHORIZATION_CONTEXT_KEY = "aria.mcp.authorization";

    private final List<McpTool> mcpTools;
    private final ObjectMapper objectMapper;
    private final WorkerScopeResolver callerResolver;

    public McpServerConfig(List<McpTool> mcpTools, ObjectMapper objectMapper, WorkerScopeResolver callerResolver) {
        this.mcpTools = mcpTools;
        this.objectMapper = objectMapper;
        this.callerResolver = callerResolver;
    }

    /**
     * spring-ai 1.0.9's MCP server auto-configuration consumes ToolCallback /
     * ToolCallbackProvider beans only — @Tool-annotated tool beans are NOT
     * auto-discovered. Every {@link McpTool} bean is bridged into one provider,
     * so new tool modules register by implementing the marker (Task 7: knowledge
     * + approval tools) without touching this configuration.
     */
    @Bean
    public MethodToolCallbackProvider ariaToolCallbackProvider() {
        return MethodToolCallbackProvider.builder().toolObjects(mcpTools.toArray()).build();
    }

    /**
     * Streamable-HTTP MCP transport at /mcp (GET establishes the response stream,
     * POST carries messages) — the endpoint opencode's streamable-only remote
     * client negotiates. Registered at the same path McpTokenFilter already
     * guards. The jsonMapper mirrors the SSE autoconfiguration's ObjectMapper
     * injection so both transports serialize identically.
     *
     * <p>The context extractor captures the raw (already filter-validated)
     * Authorization header into the SDK transport context of the HTTP request, so
     * the tool invocation can re-resolve it on whatever thread it runs.
     */
    @Bean
    public WebMvcStreamableServerTransportProvider ariaStreamableTransportProvider() {
        return WebMvcStreamableServerTransportProvider.builder()
                .jsonMapper(new JacksonMcpJsonMapper(objectMapper))
                .mcpEndpoint("/mcp")
                .contextExtractor(request -> McpTransportContext.create(Map.of(
                        AUTHORIZATION_CONTEXT_KEY,
                        Optional.ofNullable(request.headers().firstHeader("Authorization")).orElse(""))))
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

    /**
     * Streamable-path MCP server, built from the SAME {@link McpTool} callbacks
     * as the auto-wired SSE server, wrapped so worker identity from the transport
     * context is bound around every tool invocation (ruling 3). Tool adaptation
     * uses {@code McpToolUtils.toSyncToolSpecification(ToolCallback)} — the exact
     * adapter Spring AI's own autoconfigure applies to ToolCallbackProviders
     * (https://github.com/spring-projects/spring-ai/blob/v1.1.8/mcp/common/src/main/java/org/springframework/ai/mcp/McpToolUtils.java;
     * identical method ships in 1.0.9's spring-ai-mcp). The SSE
     * autoconfigure's {@code mcpSyncServer} bean is NOT conditional on this bean
     * (v1.0.9 guards it only by server type=SYNC), so both servers coexist.
     */
    @Bean
    public McpSyncServer ariaStreamableMcpServer(WebMvcStreamableServerTransportProvider ariaStreamableTransportProvider,
                                                 MethodToolCallbackProvider ariaToolCallbackProvider) {
        List<ToolCallback> callbacks = Arrays.stream(ariaToolCallbackProvider.getToolCallbacks())
                .map(callback -> (ToolCallback) new IdentityBindingToolCallback(callback, callerResolver))
                .toList();
        return McpServer.sync(ariaStreamableTransportProvider)
                .serverInfo("aria-conductor", "0.1.0")
                .capabilities(McpSchema.ServerCapabilities.builder().tools(true).build())
                .tools(McpToolUtils.toSyncToolSpecification(callbacks))
                .build();
    }

    /**
     * Delegating {@link ToolCallback} that re-binds the caller identity around the
     * real invocation from the SDK transport context carried by Spring AI's
     * {@link ToolContext} (key {@link McpToolUtils#TOOL_CONTEXT_MCP_EXCHANGE_KEY},
     * read through {@link McpToolUtils#getMcpExchange(ToolContext)}).
     *
     * <p>Fail closed: a header that is present but does not resolve is bound as a
     * WORKER caller with no scope, which {@code WorkerGovernanceAspect} denies as
     * {@code INVALID_IDENTITY}. An absent header binds nothing (legacy in-process
     * and none-mode behavior — absent identity is operator-equivalent, ruling 4).
     *
     * <p>The single-argument {@link ToolCallback#call(String)} overload carries no
     * transport context (in spring-ai 1.0.9 the interface's default
     * {@code call(String, ToolContext)} delegates <em>to</em> it, and the MCP SDK
     * adapter invokes the two-argument overload), so no identity can be recovered
     * there; it fails closed rather than delegating an unbound
     * (operator-equivalent) invocation.
     */
    static final class IdentityBindingToolCallback implements ToolCallback {

        private final ToolCallback delegate;
        private final WorkerScopeResolver callerResolver;

        IdentityBindingToolCallback(ToolCallback delegate, WorkerScopeResolver callerResolver) {
            this.delegate = delegate;
            this.callerResolver = callerResolver;
        }

        @Override
        public ToolDefinition getToolDefinition() {
            return delegate.getToolDefinition();
        }

        @Override
        public ToolMetadata getToolMetadata() {
            return delegate.getToolMetadata();
        }

        /**
         * Fails closed: this overload has no {@link ToolContext}, so the caller
         * identity cannot be bound and delegating would silently run the call as
         * operator-equivalent.
         *
         * @throws IllegalStateException always; the MCP transport path invokes
         *                               {@link #call(String, ToolContext)}
         */
        @Override
        public String call(String toolInput) {
            throw new IllegalStateException("IdentityBindingToolCallback cannot bind identity without the MCP "
                    + "transport context; use call(String, ToolContext)");
        }

        @Override
        public String call(String toolInput, ToolContext toolContext) {
            String authorization = authorizationHeader(toolContext);
            if (authorization == null || authorization.isBlank()) {
                return delegate.call(toolInput, toolContext);
            }
            Optional<McpCallerContext.Caller> previous = McpCallerContext.current();
            McpCallerContext.set(callerResolver.resolveAuthorization(authorization)
                    .orElseGet(() -> new McpCallerContext.Caller(McpCallerContext.Kind.WORKER, null)));
            try {
                return delegate.call(toolInput, toolContext);
            } finally {
                if (previous.isPresent()) {
                    McpCallerContext.set(previous.get());
                } else {
                    McpCallerContext.clear();
                }
            }
        }

        private static String authorizationHeader(ToolContext toolContext) {
            return McpToolUtils.getMcpExchange(toolContext)
                    .map(McpSyncServerExchange::transportContext)
                    .map(context -> context.get(AUTHORIZATION_CONTEXT_KEY))
                    .filter(String.class::isInstance)
                    .map(String.class::cast)
                    .orElse(null);
        }
    }
}
