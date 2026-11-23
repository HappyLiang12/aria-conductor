package io.aria.conductor.aria.live;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Spec L6: the bridge's stderr is metadata, so nothing that could be a secret or a payload
 * is written through. Two rules, both structural rather than a guess at the format:
 * the known secrets are replaced verbatim, and any long base64/credential-shaped run is
 * replaced too, because a payload can reach stderr without being one of the two secrets.
 */
final class LiveLogRedactor {

    private static final String REDACTED = "[redacted]";
    private static final int MAX_CHARS = 400;
    private static final Pattern LONG_TOKEN = Pattern.compile("[A-Za-z0-9+/=_-]{32,}");

    private final List<String> secrets;

    LiveLogRedactor(Collection<String> secrets) {
        this.secrets = new ArrayList<>();
        if (secrets != null) {
            for (String secret : secrets) {
                if (secret != null && !secret.isBlank()) {
                    this.secrets.add(secret);
                }
            }
        }
    }

    String redact(String line) {
        if (line == null) {
            return "";
        }
        String redacted = line;
        for (String secret : secrets) {
            redacted = redacted.replace(secret, REDACTED);
        }
        redacted = LONG_TOKEN.matcher(redacted).replaceAll(REDACTED);
        return redacted.length() <= MAX_CHARS ? redacted : redacted.substring(0, MAX_CHARS) + "…";
    }
}
