package io.aria.conductor.common.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * The one parser/matcher for {@code aria.operator.allowed-origins} (fleet
 * runtime governance review): the session-cookie CSRF contract, the loopback
 * auto-authority branch and the deliberately unauthenticated operator routes
 * (/answer, /finalize) must all read the SAME origin truth, or a front end one
 * layer trusts is rejected by another.
 *
 * <p>Entries are exact origins, or {@code http://host:*} patterns whose host
 * matches and whose port is numeric. A null Origin is never "allowed" — it is
 * simply absent, which the callers interpret per contract (local tools send no
 * Origin; session-cookie mutations require one).
 */
@Component
public class OperatorOriginGuard {

    private final Set<String> allowedOrigins;

    public OperatorOriginGuard(
            @Value("${aria.operator.allowed-origins:http://localhost:*,http://127.0.0.1:*}") String allowedOrigins) {
        Set<String> origins = new LinkedHashSet<>();
        if (allowedOrigins != null) {
            for (String entry : allowedOrigins.split(",")) {
                String trimmed = entry.trim();
                if (!trimmed.isEmpty()) {
                    origins.add(trimmed);
                }
            }
        }
        this.allowedOrigins = Set.copyOf(origins);
    }

    /** Whether an Origin header names an operator-trusted front end (null never does). */
    public boolean isAllowed(String origin) {
        if (origin == null) {
            return false;
        }
        String candidate = origin.trim();
        return allowedOrigins.contains(candidate)
                || allowedOrigins.stream().anyMatch(pattern -> matchesPortPattern(pattern, candidate));
    }

    /**
     * Whether an Origin header is present but foreign — the signature of a
     * browser-driven cross-site request a web page forged (CORS cannot block a
     * "simple" POST, and not reading the response is no protection).
     */
    public boolean isCrossSite(String origin) {
        return origin != null && !isAllowed(origin);
    }

    /**
     * Exact match, or a configured {@code http://host:*} entry whose host
     * matches and whose port is numeric — a suffix that merely looks like a port
     * (or a host that merely starts with the pattern's host) is not a match.
     */
    private boolean matchesPortPattern(String pattern, String origin) {
        int star = pattern.lastIndexOf(":*");
        if (star != pattern.length() - 2 || star < 0) {
            return false;
        }
        String prefix = pattern.substring(0, star + 1);
        if (!origin.startsWith(prefix)) {
            return false;
        }
        String port = origin.substring(prefix.length());
        return !port.isEmpty() && port.length() <= 5 && port.chars().allMatch(Character::isDigit);
    }
}
