package io.aria.conductor.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.aria.conductor.common.security.ActorPrincipal;
import io.aria.conductor.execution.security.ActorTokenService;
import io.aria.conductor.execution.security.OperatorSessionService;
import io.modelcontextprotocol.common.McpTransportContext;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.mcp.McpToolUtils;

import java.util.Map;

/**
 * Transport-owned MCP actor identity (spec §6.2). Identity is read ONLY from the
 * transport context the SDK attached to the call — the same value the transport
 * extractor derived from the authenticated Bearer credential at session setup.
 * Tool arguments are never a source of identity: the platform's underscore
 * argument namespace is reserved, and callers supplying reserved fields are
 * rejected before the tool runs.
 */
public final class McpActorContext {

    /** Transport-context key the actor is published under. */
    public static final String ACTOR_KEY = "aria.actor";

    private static final String RESERVED_PREFIX = "_";

    private McpActorContext() {
    }

    /**
     * Resolves the authenticated actor of the invoking MCP session.
     *
     * @throws SecurityException when the call carries no framework exchange or no
     *                           transport-authenticated actor (never falls back to arguments)
     */
    public static ActorPrincipal require(ToolContext context) {
        var exchange = McpToolUtils.getMcpExchange(context)
                .orElseThrow(() -> new SecurityException("Missing MCP exchange"));
        Object value = exchange.transportContext().get(ACTOR_KEY);
        if (!(value instanceof ActorPrincipal actor)) {
            throw new SecurityException("Missing transport actor");
        }
        return actor;
    }

    /** Publishes an actor into a transport context; unauthenticated calls stay empty. */
    public static McpTransportContext transportContext(ActorPrincipal actor) {
        return actor == null
                ? McpTransportContext.EMPTY
                : McpTransportContext.create(Map.of(ACTOR_KEY, actor));
    }

    /**
     * Resolves the transport actor of a request's Bearer credential: the operator
     * credential (the same one the REST operator surfaces verify) or a run-scoped
     * worker token. Both must resolve here — the policy registry's
     * {@code OPERATOR_ONLY} class is only reachable through the operator branch, so
     * a resolver that knew only worker tokens would make that class unreachable.
     * An unverifiable credential yields no actor and the tool boundary refuses the
     * call; identity is never taken from the payload.
     */
    public static ActorPrincipal resolveBearer(String authorizationHeader,
                                               ActorTokenService actorTokens,
                                               OperatorSessionService operatorSessions) {
        if (operatorSessions != null && operatorSessions.verifyOperatorCredential(authorizationHeader)) {
            return ActorPrincipal.operator(null);
        }
        return actorTokens == null ? null : actorTokens.resolveBearer(authorizationHeader).orElse(null);
    }

    /**
     * Rejects caller-supplied identity claims. The {@code _}-prefixed argument
     * namespace is reserved for framework-injected values (e.g. {@code _runId}),
     * so an external caller supplying {@code _actor}, {@code _runId},
     * {@code _sessionId} or similar is rejected instead of being interpreted.
     */
    public static void rejectSuppliedIdentity(String rawArgumentsJson, ObjectMapper objectMapper) {
        if (rawArgumentsJson == null || rawArgumentsJson.isBlank()) {
            return;
        }
        JsonNode arguments;
        try {
            arguments = objectMapper.readTree(rawArgumentsJson);
        } catch (Exception e) {
            // Malformed arguments are the transport's validation problem, not an identity claim.
            return;
        }
        if (arguments == null || !arguments.isObject()) {
            return;
        }
        arguments.fieldNames().forEachRemaining(name -> {
            if (name.startsWith(RESERVED_PREFIX)) {
                throw new SecurityException("MCP tool argument '" + name
                        + "' is reserved: identity is transport-owned, not caller-supplied");
            }
        });
    }
}
