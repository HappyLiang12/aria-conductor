package io.aria.conductor.mcp.tools;

import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

import java.util.Arrays;

/**
 * Spec §6 governance safeguard: with aria.mcp.auth-mode=none the audit trail is
 * the only record of who mutated what via MCP. Every @Tool invocation logs the
 * tool name, arguments, duration and outcome — independent of the transport.
 */
@Slf4j
@Aspect
@Component
public class ToolAuditAspect {

    @Around("@annotation(tool)")
    public Object audit(ProceedingJoinPoint joinPoint, Tool tool) throws Throwable {
        long start = System.nanoTime();
        String toolName = tool.name();
        String args = Arrays.toString(joinPoint.getArgs());
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
}
