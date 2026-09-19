package io.aria.conductor.mcp.tools;

import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Locale;
import java.util.Set;

/**
 * Spec §6 governance safeguard: with aria.mcp.auth-mode=none the audit trail is
 * the only record of who mutated what via MCP. Every @Tool invocation logs the
 * tool name, arguments, duration and outcome — independent of the transport.
 *
 * <p>Credential-bearing arguments must never reach the audit log: parameter names
 * matching {@link #SENSITIVE_PARAMETER_NAMES} (case-insensitive, e.g. {@code apiKey},
 * {@code token}, {@code pat}) are rendered as {@code [redacted]}. When parameter names are
 * unavailable the whole argument list is hidden (fail closed) rather than logged raw.
 */
@Slf4j
@Aspect
@Component
public class ToolAuditAspect {

    /**
     * Explicit, case-insensitive list of parameter names whose values are credential material.
     * Deliberately not heuristic: an unknown name keeps its ordinary audit visibility.
     */
    private static final Set<String> SENSITIVE_PARAMETER_NAMES = Set.of(
            "apikey", "api_key", "token", "secret", "pat", "password", "credential", "authorization");

    /** Fail-closed rendering when parameter names cannot be resolved. */
    private static final String ARGS_HIDDEN = "[args hidden: parameter names unavailable]";

    private static final String REDACTED = "[redacted]";

    @Around("@annotation(tool)")
    public Object audit(ProceedingJoinPoint joinPoint, Tool tool) throws Throwable {
        long start = System.nanoTime();
        String toolName = tool.name();
        String args = renderArgs(joinPoint);
        try {
            Object result = joinPoint.proceed();
            log.info("MCP tool '{}' args={} durationMs={} outcome=ok", toolName, args,
                    (System.nanoTime() - start) / 1_000_000);
            return result;
        } catch (Throwable t) {
            log.warn("MCP tool '{}' args={} durationMs={} outcome=error error={}", toolName, args,
                    (System.nanoTime() - start) / 1_000_000, t.getMessage());
            throw t;
        }
    }

    /**
     * Renders the invocation arguments, replacing sensitive values with {@code [redacted]}.
     * Parameter names come from the compiled method ({@code -parameters}); if they are
     * unavailable (null or misaligned with the argument list) there is no way to tell which
     * value is a secret, so the whole list is hidden instead of logged raw.
     */
    private static String renderArgs(ProceedingJoinPoint joinPoint) {
        Object[] args = joinPoint.getArgs();
        String[] names = ((MethodSignature) joinPoint.getSignature()).getParameterNames();
        if (names == null || names.length != args.length) {
            return ARGS_HIDDEN;
        }
        Object[] rendered = new Object[args.length];
        for (int i = 0; i < args.length; i++) {
            rendered[i] = isSensitive(names[i]) ? REDACTED : args[i];
        }
        return Arrays.toString(rendered);
    }

    private static boolean isSensitive(String parameterName) {
        return parameterName != null
                && SENSITIVE_PARAMETER_NAMES.contains(parameterName.toLowerCase(Locale.ROOT));
    }
}
