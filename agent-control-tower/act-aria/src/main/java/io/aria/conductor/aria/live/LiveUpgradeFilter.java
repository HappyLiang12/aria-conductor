package io.aria.conductor.aria.live;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Locale;

/**
 * Answers a plain {@code GET} to the live path with 426 Upgrade Required (spec X1).
 *
 * <p>It is a filter and not an MVC mapping on purpose: the WebSocket handler
 * mapping already owns this exact path, and an MVC mapping would shadow it and
 * take the upgrade away. The filter only rejects GETs that carry no
 * {@code Upgrade: websocket}, so handshakes reach the handler untouched and a
 * non-GET method is left to the application.
 */
@Component
public class LiveUpgradeFilter extends OncePerRequestFilter {

    /** The phone's one live socket path (spec X1). */
    public static final String PATH = "/api/v1/aria/live";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (isPlainGet(request)) {
            // Only the contract's plain GET is answered here; any other method is
            // the application's to answer (404/405), never this filter's.
            response.setStatus(HttpStatus.UPGRADE_REQUIRED.value());
            response.setHeader(HttpHeaders.UPGRADE, "websocket");
            return;
        }
        chain.doFilter(request, response);
    }

    private static boolean isPlainGet(HttpServletRequest request) {
        return "GET".equalsIgnoreCase(request.getMethod())
                && isLivePath(request)
                && !isWebSocketUpgrade(request);
    }

    private static boolean isLivePath(HttpServletRequest request) {
        String uri = request.getRequestURI();
        return uri != null && uri.equals(request.getContextPath() + PATH);
    }

    private static boolean isWebSocketUpgrade(HttpServletRequest request) {
        String upgrade = request.getHeader(HttpHeaders.UPGRADE);
        if (upgrade == null || !"websocket".equals(upgrade.trim().toLowerCase(Locale.ROOT))) {
            return false;
        }
        String connection = request.getHeader(HttpHeaders.CONNECTION);
        return connection != null && connection.toLowerCase(Locale.ROOT).contains("upgrade");
    }
}
