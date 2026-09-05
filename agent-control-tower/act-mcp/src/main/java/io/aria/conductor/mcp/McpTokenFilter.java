package io.aria.conductor.mcp;

import io.aria.conductor.execution.mcp.McpProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Bearer guard for /mcp/* — registered ONLY when aria.mcp.auth-mode=token
 * (v1 default is none: auth deferred, audit logging is the safeguard).
 * Ordered after CorrelationIdFilter (HIGHEST_PRECEDENCE).
 */
@Component
@ConditionalOnProperty(prefix = "aria.mcp", name = "auth-mode", havingValue = "token")
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class McpTokenFilter extends OncePerRequestFilter {

    private final McpProperties properties;

    public McpTokenFilter(McpProperties properties) {
        this.properties = properties;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!request.getRequestURI().startsWith("/mcp")) {
            chain.doFilter(request, response);
            return;
        }
        String expected = "Bearer " + properties.getToken();
        String actual = request.getHeader("Authorization");
        if (actual == null || !MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8), actual.getBytes(StandardCharsets.UTF_8))) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            return;
        }
        chain.doFilter(request, response);
    }
}
