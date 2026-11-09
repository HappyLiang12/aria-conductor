package io.aria.conductor.memory.service;

import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

/**
 * The secret filter at the memory write boundary: memory never stores credentials.
 * <p>
 * Keys are rejected when they <em>name</em> a credential ({@code api_key}, {@code token},
 * {@code password}, {@code secret}, {@code credential}); values are rejected when they
 * <em>look</em> like one (an {@code sk-}, {@code ghp_} or {@code xoxb-} prefixed token, or a
 * 40+ character base64 run). A rejection names the field and never echoes the input, so
 * the validation error cannot become the leak it is meant to prevent.
 */
@Component
public class MemoryValueGuard {

    /** Same shape as the plan's acceptance matrix: {@code (?i).*(api[_-]?key|token|password|secret|credential).*}. */
    private static final Pattern CREDENTIAL_KEY =
            Pattern.compile("(?i).*(api[_-]?key|token|password|secret|credential).*");

    private static final Pattern CREDENTIAL_VALUE =
            Pattern.compile("(?i).*(sk-|ghp_|xoxb-|[A-Za-z0-9+/]{40,}={0,2}).*");

    private static final String REJECTION = " is rejected: memory does not store authentication material";

    /** Reject a key that names a credential. */
    public void checkKey(String field, String key) {
        if (key != null && CREDENTIAL_KEY.matcher(key).matches()) {
            throw new MemoryValidationException(field + REJECTION);
        }
    }

    /** Reject a value that looks like a credential. */
    public void checkValue(String field, String value) {
        if (value != null && CREDENTIAL_VALUE.matcher(value).matches()) {
            throw new MemoryValidationException(field + REJECTION);
        }
    }
}
