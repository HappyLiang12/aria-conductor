package io.aria.conductor.execution.security;

import java.util.regex.Pattern;

/**
 * Masks credential-shaped values in tool arguments before they are persisted on
 * an approval row or returned by the API (F7).
 *
 * <p>ACP permission asks now expose the tool's argument document to the
 * operator. That document is attacker-influenced tool input, so a credential
 * the core passed to a command must never become a durable, world-readable
 * audit field. The redaction is conservative by design: it matches recognizable
 * token shapes (GitHub PATs, provider keys, AWS access key ids, bearer values)
 * and named secret JSON keys, and leaves ordinary arguments untouched.
 */
public final class CredentialRedactor {

    public static final String MASK = "[REDACTED]";

    private static final Pattern GITHUB_PAT =
            Pattern.compile("\\bgh[pousr]_[A-Za-z0-9]{20,}\\b");
    private static final Pattern GITHUB_FINE_GRAINED =
            Pattern.compile("\\bgithub_pat_[A-Za-z0-9_]{20,}\\b");
    private static final Pattern PROVIDER_KEY =
            Pattern.compile("\\bsk-[A-Za-z0-9_-]{16,}\\b");
    private static final Pattern AWS_ACCESS_KEY =
            Pattern.compile("\\bAKIA[0-9A-Z]{16}\\b");
    private static final Pattern BEARER =
            Pattern.compile("(?i)(bearer\\s+)[A-Za-z0-9._~+/=-]{8,}");
    /**
     * The value may contain escaped pairs (a Windows path, an escaped inner JSON
     * document), so it accepts {@code \\.} as well as ordinary characters; the
     * closing quote is a backreference to the opening quote so an escaped
     * ({@code \"}) value cannot run past its own string boundary into the rest of
     * an embedded document. The closing quote must be followed by a structural
     * delimiter or the end of the input, so an escaped quote inside the value is
     * not mistaken for the end of it.
     */
    private static final Pattern NAMED_SECRET_JSON_VALUE = Pattern.compile(
            "(?i)(\\\\?\"(?:token|access[_-]?token|api[_-]?key|secret|password|authorization)\\\\?\"\\s*:\\s*)(\\\\?\")((?:[^\"\\\\]|\\\\.)*?)\\2(?=[\\s,}\\]]|$)");

    private CredentialRedactor() {
    }

    /** Returns the redacted copy; {@code null} and blank input pass through unchanged. */
    public static String redact(String value) {
        if (value == null || value.isEmpty()) {
            return value;
        }
        String redacted = value;
        redacted = GITHUB_PAT.matcher(redacted).replaceAll(MASK);
        redacted = GITHUB_FINE_GRAINED.matcher(redacted).replaceAll(MASK);
        redacted = PROVIDER_KEY.matcher(redacted).replaceAll(MASK);
        redacted = AWS_ACCESS_KEY.matcher(redacted).replaceAll(MASK);
        redacted = BEARER.matcher(redacted).replaceAll("$1" + MASK);
        redacted = NAMED_SECRET_JSON_VALUE.matcher(redacted).replaceAll("$1$2" + MASK + "$2");
        return redacted;
    }
}
