package io.aria.conductor.mcp.tools;

import io.aria.conductor.execution.approval.WorkerScope;
import io.aria.conductor.execution.approval.WriteGrantService;
import io.aria.conductor.mcp.McpCallerContext;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * C4 ruling 8: server-side enforcement of the worker/operator boundary at the
 * tool invocation seam, independent of the transport and of what the client
 * believed it was allowed to do. For every WORKER request:
 *
 * <ul>
 *   <li>no usable scope → {@code INVALID_IDENTITY};</li>
 *   <li>no reviewed policy → {@code UNKNOWN_TOOL};</li>
 *   <li>OPERATOR_ONLY → {@code OPERATOR_ONLY};</li>
 *   <li>a declared scope parameter that names another run/agent → {@code SCOPE_MISMATCH};</li>
 *   <li>WORKER_WRITE → one-use grant bound to (runId, toolName, the frozen
 *       {@link WriteGrantService#effectiveArgsDigest(Map) effective-argument
 *       digest} of the live invocation) or {@code GRANT_REQUIRED}. The digest is
 *       computed from the live invocation, so a call whose arguments differ from
 *       the approved request never matches and a mismatch consumes nothing
 *       (design §6.1).</li>
 * </ul>
 *
 * <p>Identity comes from {@link McpCallerContext}, bound per request by
 * {@code McpTokenFilter}. An absent identity is operator-equivalent (legacy
 * none-mode and in-process calls); operator calls are never scope-checked and
 * never require grants.
 *
 * <p>Ordering: this aspect runs OUTSIDE {@link ToolAuditAspect}
 * ({@code HIGHEST_PRECEDENCE + 100} vs default precedence) so a denied call
 * never reaches the delegate or the audit aspect; denials are logged here with
 * the tool, code and run id only — never the arguments.
 */
@Slf4j
@Aspect
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 100)
public class WorkerGovernanceAspect {

    private final ToolPolicyRegistry registry;
    private final WriteGrantService writeGrants;

    public WorkerGovernanceAspect(ToolPolicyRegistry registry, WriteGrantService writeGrants) {
        if (registry == null) {
            throw new IllegalArgumentException("registry is required");
        }
        if (writeGrants == null) {
            throw new IllegalArgumentException("writeGrants is required");
        }
        this.registry = registry;
        this.writeGrants = writeGrants;
    }

    @Around("@annotation(tool)")
    public Object enforce(ProceedingJoinPoint joinPoint, Tool tool) throws Throwable {
        McpCallerContext.Caller caller = McpCallerContext.current().orElse(null);
        if (caller == null || caller.isOperator()) {
            return joinPoint.proceed();
        }
        WorkerScope scope = caller.scope();
        if (scope == null) {
            throw denial(WorkerGovernanceDeniedException.Code.INVALID_IDENTITY, tool.name(), null);
        }
        ToolPolicyRegistry.Policy policy = registry.lookup(tool.name())
                .orElseThrow(() -> denial(WorkerGovernanceDeniedException.Code.UNKNOWN_TOOL,
                        tool.name(), scope.runId()));
        if (policy.category() == ToolPolicyRegistry.Category.OPERATOR_ONLY) {
            throw denial(WorkerGovernanceDeniedException.Code.OPERATOR_ONLY, tool.name(), scope.runId());
        }
        enforceScope(policy, scope, joinPoint, tool.name());
        if (policy.category() == ToolPolicyRegistry.Category.WORKER_WRITE) {
            String digest = argumentDigest(joinPoint);
            // digest == null means parameter names were unavailable: unrecognizable
            // invocation, so the grant lookup fails and the call is denied.
            if (!writeGrants.consume(scope.runId(), tool.name(), digest)) {
                throw denial(WorkerGovernanceDeniedException.Code.GRANT_REQUIRED, tool.name(), scope.runId());
            }
        }
        return joinPoint.proceed();
    }

    /**
     * Cross-run impersonation guard (ruling 9): tools may declare one invocation
     * parameter carrying the run/agent id they act on. A worker may only read
     * its own ids; a declared parameter that cannot be inspected fails closed.
     */
    private static void enforceScope(ToolPolicyRegistry.Policy policy, WorkerScope scope,
                                     ProceedingJoinPoint joinPoint, String toolName) {
        String scopeParam = policy.scopeParam();
        if (scopeParam == null) {
            return;
        }
        Object[] args = joinPoint.getArgs();
        String[] names = ((MethodSignature) joinPoint.getSignature()).getParameterNames();
        if (names == null || names.length != args.length) {
            throw denial(WorkerGovernanceDeniedException.Code.SCOPE_MISMATCH, toolName, scope.runId());
        }
        for (int i = 0; i < names.length; i++) {
            if (scopeParam.equals(names[i])) {
                Object value = args[i];
                if (value == null) {
                    return;
                }
                String expected = policy.scopeKind() == ToolPolicyRegistry.ScopeKind.RUN
                        ? scope.runId().toString()
                        : scope.agentId().toString();
                if (!expected.equals(String.valueOf(value))) {
                    throw denial(WorkerGovernanceDeniedException.Code.SCOPE_MISMATCH, toolName, scope.runId());
                }
                return;
            }
        }
        throw denial(WorkerGovernanceDeniedException.Code.SCOPE_MISMATCH, toolName, scope.runId());
    }

    /**
     * Digest of the actual named arguments under the frozen effective-argument
     * contract (the approval side C2/C3 computes the same value), or null when
     * the compiled parameter names are unavailable (fail closed at the caller).
     */
    private static String argumentDigest(ProceedingJoinPoint joinPoint) {
        Object[] args = joinPoint.getArgs();
        String[] names = ((MethodSignature) joinPoint.getSignature()).getParameterNames();
        if (names == null || names.length != args.length) {
            return null;
        }
        Map<String, Object> named = new LinkedHashMap<>();
        for (int i = 0; i < args.length; i++) {
            named.put(names[i], args[i]);
        }
        return WriteGrantService.effectiveArgsDigest(named);
    }

    private static WorkerGovernanceDeniedException denial(WorkerGovernanceDeniedException.Code code,
                                                          String toolName, java.util.UUID runId) {
        log.warn("MCP worker call denied tool='{}' code={} runId={}", toolName, code, runId);
        return new WorkerGovernanceDeniedException(code, toolName, runId);
    }
}
