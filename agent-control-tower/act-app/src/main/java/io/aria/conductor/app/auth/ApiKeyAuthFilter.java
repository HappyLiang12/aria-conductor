package io.aria.conductor.app.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Lightweight HTTP auth filter enforcing a static shared operator API key.
 *
 * <p>When {@code app.auth.api-key} (env {@code ARIA_API_KEY}) is set, every request
 * under the protected prefixes ({@code /api/v1/**} and {@code /actuator/**} except
 * {@code /actuator/health}) must present the key as
 * {@code Authorization: Bearer <key>}. The key is compared in constant time and is
 * never written to logs, error bodies, or trace output. {@code OPTIONS} preflight,
 * the health endpoint, and all non-protected paths (springdoc, h2-console, websocket)
 * pass through untouched. When the key is blank the filter is inert, preserving the
 * permissive local/dev default.
 */
@Slf4j
public class ApiKeyAuthFilter extends OncePerRequestFilter {

    static final String AUTHORIZATION_HEADER = "Authorization";
    static final String BEARER_PREFIX = "Bearer ";
    static final String WWW_AUTHENTICATE_HEADER = "WWW-Authenticate";
    static final String CHALLENGE = "Bearer realm=\"aria-conductor\"";
    static final String GENERIC_UNAUTHORIZED_MESSAGE = "Invalid or missing API key";
    static final String HEALTH_PATH = "/actuator/health";
    static final String CORRELATION_ID_HEADER = "X-Correlation-ID";

    private final ApiKeyAuthProperties properties;
    private final ObjectMapper objectMapper;

    public ApiKeyAuthFilter(ApiKeyAuthProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (!properties.isEnabled()) {
            return true;
        }
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        return !isProtectedPath(request.getRequestURI());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String presented = extractBearerToken(request.getHeader(AUTHORIZATION_HEADER));
        if (presented == null || presented.isBlank()
                || !constantTimeEquals(presented, properties.getApiKey())) {
            reject(request, response);
            return;
        }
        filterChain.doFilter(request, response);
    }

    /** Returns the bearer token, or {@code null} when the header is absent/malformed/non-Bearer. */
    private String extractBearerToken(String header) {
        if (header == null) {
            return null;
        }
        if (!header.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) {
            return null;
        }
        return header.substring(BEARER_PREFIX.length());
    }

    private boolean constantTimeEquals(String presented, String configured) {
        return MessageDigest.isEqual(
                presented.getBytes(StandardCharsets.UTF_8),
                configured.getBytes(StandardCharsets.UTF_8));
    }

    private void reject(HttpServletRequest request, HttpServletResponse response) throws IOException {
        String correlationId = request.getHeader(CORRELATION_ID_HEADER);
        log.warn("Rejected unauthenticated request method={} path={} correlationId={}",
                request.getMethod(), request.getRequestURI(), correlationId);

        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setHeader(WWW_AUTHENTICATE_HEADER, CHALLENGE);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("timestamp", Instant.now().toString());
        body.put("status", HttpStatus.UNAUTHORIZED.value());
        body.put("error", HttpStatus.UNAUTHORIZED.getReasonPhrase());
        body.put("message", GENERIC_UNAUTHORIZED_MESSAGE);
        objectMapper.writeValue(response.getWriter(), body);
    }

    /**
     * A path requires a valid token iff it is under the API prefix ({@code /api/v1/**})
     * or under {@code /actuator/**} excluding the health probe. All other paths
     * (springdoc, h2-console, websocket) are exempt by construction.
     */
    private boolean isProtectedPath(String path) {
        if (path == null) {
            return false;
        }
        if (path.equals("/api/v1") || path.startsWith("/api/v1/")) {
            return true;
        }
        if (path.equals("/actuator")) {
            return true;
        }
        return path.startsWith("/actuator/")
                && !path.equals(HEALTH_PATH)
                && !path.startsWith(HEALTH_PATH + "/");
    }
}
