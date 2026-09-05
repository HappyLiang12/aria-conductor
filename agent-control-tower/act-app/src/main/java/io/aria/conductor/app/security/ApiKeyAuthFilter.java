package io.aria.conductor.app.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Shared-secret API-key gate for the {@code /api/v1/**} REST surface.
 *
 * <p>When {@code app.security.enabled=true} every request under {@code /api/v1/**} must present a
 * configured key either as {@code Authorization: Bearer <key>} or {@code X-API-Key: <key>}. The
 * comparison is constant time. Rejections are {@code 401} with a fixed JSON body and (for the
 * Bearer scheme) a {@code WWW-Authenticate: Bearer} challenge; no stack traces or internal detail
 * are ever emitted. Actuator health/info and non-{@code /api/v1} surfaces (including CORS
 * preflight {@code OPTIONS}) are never challenged. When auth is disabled the filter is inert.
 */
public class ApiKeyAuthFilter extends OncePerRequestFilter {

    public static final String AUTHENTICATED_ATTRIBUTE = "authenticated";
    public static final String AUTH_HINT_ATTRIBUTE = "authenticatedKeyHint";

    private static final String BEARER_PREFIX = "Bearer ";
    private static final String WWW_AUTHENTICATE_BEARER = "Bearer";
    private static final String API_PATH_PREFIX = "/api/v1";

    private static final Logger log = LoggerFactory.getLogger(ApiKeyAuthFilter.class);

    private final SecurityProperties properties;

    public ApiKeyAuthFilter(SecurityProperties properties) {
        this.properties = properties;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        // Disabled-auth mode: inert, zero-overhead passthrough (local dev default).
        if (!properties.isEnabled()) {
            chain.doFilter(request, response);
            return;
        }
        if (!isProtectedPath(request)) {
            chain.doFilter(request, response);
            return;
        }
        // CORS preflights carry no credentials; allow them through so browsers can reach the API.
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            chain.doFilter(request, response);
            return;
        }

        String path = request.getRequestURI();
        try {
            String credential = resolveCredential(request);
            if (credential == null) {
                logRejection(request, "missing-or-malformed-credential");
                writeJson(response, HttpServletResponse.SC_UNAUTHORIZED, "unauthorized",
                        "Authentication required: send a valid API key via Authorization: Bearer "
                                + "<key> or X-API-Key: <key>");
                return;
            }
            if (!properties.matches(credential)) {
                logRejection(request, "invalid-key");
                writeJson(response, HttpServletResponse.SC_UNAUTHORIZED, "unauthorized",
                        "Invalid API key");
                return;
            }
            request.setAttribute(AUTHENTICATED_ATTRIBUTE, Boolean.TRUE);
            request.setAttribute(AUTH_HINT_ATTRIBUTE, keyHint(credential));
        } catch (Exception ex) {
            // Any failure inside the gate must 500, never fall through and allow the request.
            log.warn("API-key auth filter failure on {} {}: {}", request.getMethod(), path,
                    ex.getMessage());
            writeJson(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "internal_error",
                    "Internal server error");
            return;
        }
        chain.doFilter(request, response);
    }

    /** True when the (context-relative) request path falls under the protected /api/v1 surface. */
    private static boolean isProtectedPath(HttpServletRequest request) {
        String uri = request.getRequestURI();
        if (uri == null) {
            return false;
        }
        String contextPath = request.getContextPath();
        if (contextPath != null && !contextPath.isEmpty() && uri.startsWith(contextPath)) {
            uri = uri.substring(contextPath.length());
        }
        return uri.equals(API_PATH_PREFIX) || uri.startsWith(API_PATH_PREFIX + "/");
    }

    private static String resolveCredential(HttpServletRequest request) {
        String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (authorization != null) {
            if (authorization.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) {
                String token = authorization.substring(BEARER_PREFIX.length()).trim();
                return token.isEmpty() ? null : token;
            }
            // Authorization header present but not a Bearer token -> malformed.
            return null;
        }
        String apiKey = request.getHeader("X-API-Key");
        return (apiKey == null || apiKey.isBlank()) ? null : apiKey.trim();
    }

    private void logRejection(HttpServletRequest request, String reason) {
        String remote = request.getRemoteAddr();
        log.warn("Rejected {} {} ({}): {}", request.getMethod(), request.getRequestURI(), reason,
                remote == null ? "unknown" : remote);
    }

    /** A non-secret hint derived from the accepted key for audit/request context (never the key). */
    private static String keyHint(String credential) {
        if (credential.length() <= 8) {
            return "<configured>";
        }
        return credential.substring(0, 4) + "...";
    }

    private static void writeJson(HttpServletResponse response, int status, String error,
                                  String message) throws IOException {
        if (response.isCommitted()) {
            return;
        }
        response.setStatus(status);
        if (status == HttpServletResponse.SC_UNAUTHORIZED) {
            response.setHeader(HttpHeaders.WWW_AUTHENTICATE, WWW_AUTHENTICATE_BEARER);
        }
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write("{\"error\":\"" + error + "\",\"message\":\""
                + escape(message) + "\"}");
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
