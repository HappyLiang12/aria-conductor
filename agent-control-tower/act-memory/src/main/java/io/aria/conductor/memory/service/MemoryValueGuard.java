package io.aria.conductor.memory.service;

import org.springframework.stereotype.Component;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The secret filter at the memory write boundary: memory never stores credentials.
 * <p>
 * Keys are rejected when they <em>name</em> a credential ({@code api_key}, {@code token},
 * {@code password}, {@code secret}, {@code credential}); values are rejected when they
 * <em>look</em> like one — a known credential prefix ({@code sk-}, {@code ghp_},
 * {@code xoxb-}, {@code AKIA}), or a long opaque base64-style run that is not a hex digest.
 * A 40- or 64-character hex commit SHA is ordinary memory content (the branch fact), so it
 * is explicitly not authentication material. A rejection names the field and never echoes
 * the input, so the validation error cannot become the leak it is meant to prevent.
 */
@Component
public class MemoryValueGuard {

    /** Same shape as the plan's acceptance matrix: {@code (?i).*(api[_-]?key|token|password|secret|credential).*}. */
    private static final Pattern CREDENTIAL_KEY =
            Pattern.compile("(?i).*(api[_-]?key|token|password|secret|credential).*");

    /** Known credential shapes, each anchored on a documented vendor prefix. */
    private static final Pattern CREDENTIAL_PREFIX = Pattern.compile("(?i).*(?:"
            + "sk-[a-z0-9_-]{10,}"           // OpenAI-style secret key
            + "|gh[opsru]_[a-z0-9]{10,}"     // GitHub token (ghp_, gho_, ghu_, ghs_, ghr_)
            + "|xox[abprs]-[a-z0-9-]{8,}"    // Slack token
            + "|AKIA[0-9A-Z]{16}"            // AWS access key id
            + ").*");

    /** A 40+ character run of the base64 alphabet: the blunt high-entropy rule. */
    private static final Pattern OPAQUE_RUN = Pattern.compile("[A-Za-z0-9+/]{40,}={0,2}");

    /**
     * A hex digest with at least one letter: a 40-character SHA-1 or 64-character SHA-256.
     * The letter requirement keeps a 40-digit number on the rejected side of the rule.
     */
    private static final Pattern HEX_DIGEST =
            Pattern.compile("(?i)(?=[0-9a-f]*[a-f])(?:[0-9a-f]{40}|[0-9a-f]{64})");

    private static final String REJECTION = " is rejected: memory does not store authentication material";

    /** Reject a key that names a credential. */
    public void checkKey(String field, String key) {
        if (key != null && CREDENTIAL_KEY.matcher(key).matches()) {
            throw new MemoryValidationException(field + REJECTION);
        }
    }

    /** Reject a value that looks like a credential. */
    public void checkValue(String field, String value) {
        if (value == null) {
            return;
        }
        if (CREDENTIAL_PREFIX.matcher(value).matches() || containsOpaqueSecret(value)) {
            throw new MemoryValidationException(field + REJECTION);
        }
    }

    /** True when a long high-entropy run is present that cannot be a commit SHA. */
    private static boolean containsOpaqueSecret(String value) {
        Matcher runs = OPAQUE_RUN.matcher(value);
        while (runs.find()) {
            if (!HEX_DIGEST.matcher(runs.group()).matches()) {
                return true;
            }
        }
        return false;
    }
}
