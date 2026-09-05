package io.aria.conductor.app.security;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

/**
 * Configuration for the built-in API-key authentication layer ({@code app.security} prefix).
 *
 * <ul>
 *   <li>{@code app.security.enabled} (env {@code APP_SECURITY_ENABLED}): whether every
 *       {@code /api/v1/**} request must present a valid API key. Defaults to {@code false} for
 *       the local dev profiles and {@code true} for the {@code mariadb} (production) profile.</li>
 *   <li>{@code app.security.api-keys} (env {@code AUTH_API_KEYS}): a comma/space separated list
 *       of accepted keys. Entries may be plaintext or hex SHA-256 hashes prefixed with
 *       {@code sha256:}. Prefer hashes at rest; plaintext is accepted for operability but never
 *       logged.</li>
 * </ul>
 *
 * <p>Keys and hashes are never written to logs and never returned by any endpoint. Comparisons are
 * performed in constant time to avoid timing side-channels.
 */
@Component
@ConfigurationProperties(prefix = "app.security")
public class SecurityProperties {

    /** Whether the API-key gate is active for all {@code /api/v1/**} requests. */
    private boolean enabled = false;

    /** Raw comma/space separated list of accepted API keys or {@code sha256:} hashes. */
    private String apiKeys = "";

    private List<ApiKey> parsedKeys;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getApiKeys() {
        return apiKeys;
    }

    public void setApiKeys(String apiKeys) {
        this.apiKeys = apiKeys == null ? "" : apiKeys.trim();
        this.parsedKeys = null;
    }

    /** Parsed credentials (lazily computed from {@link #getApiKeys()}). */
    public synchronized List<ApiKey> keys() {
        if (parsedKeys == null) {
            parsedKeys = parse(apiKeys);
        }
        return parsedKeys;
    }

    /** True when at least one key or hash is configured. */
    public boolean hasConfiguredKeys() {
        return !keys().isEmpty();
    }

    /**
     * Constant-time check that the presented credential matches one of the configured keys/hashes.
     */
    public boolean matches(String candidate) {
        if (candidate == null || candidate.isEmpty()) {
            return false;
        }
        for (ApiKey key : keys()) {
            if (key.matches(candidate)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The first plaintext key, when one is configured, used to provision sandboxed agents so they
     * can call the API authenticated. Never returns a value for hash-only configuration because a
     * hash cannot be reversed into credential material.
     */
    public Optional<String> provisionablePlaintextKey() {
        for (ApiKey key : keys()) {
            if (!key.isHashed()) {
                return Optional.of(key.plaintextValue());
            }
        }
        return Optional.empty();
    }

    private static List<ApiKey> parse(String raw) {
        List<ApiKey> out = new ArrayList<>();
        if (raw == null || raw.isBlank()) {
            return out;
        }
        for (String entry : raw.split("[,\\s]+")) {
            String trimmed = entry.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            if (trimmed.regionMatches(true, 0, "sha256:", 0, "sha256:".length())) {
                String hex = trimmed.substring("sha256:".length()).trim();
                if (hex.length() != 64 || !hex.matches("[0-9a-fA-F]{64}")) {
                    throw new IllegalArgumentException(
                            "Invalid app.security.api-keys entry '" + hint(trimmed)
                                    + "': expected a sha256: hash of exactly 64 hex characters");
                }
                byte[] digest = HexFormat.of().parseHex(hex.toLowerCase());
                out.add(ApiKey.hashed(digest));
            } else {
                out.add(ApiKey.plaintext(trimmed));
            }
        }
        return out;
    }

    private static String hint(String value) {
        if (value.length() <= 12) {
            return value;
        }
        return value.substring(0, 6) + "..." + value.substring(value.length() - 4);
    }

    /**
     * A single accepted credential: either a plaintext shared secret or a hex SHA-256 digest of
     * one. Comparison is constant time via {@link MessageDigest#isEqual(byte[], byte[])}.
     */
    public static final class ApiKey {
        private final boolean hashed;
        private final byte[] expected;
        private final String plaintextValue;

        private ApiKey(boolean hashed, byte[] expected, String plaintextValue) {
            this.hashed = hashed;
            this.expected = expected;
            this.plaintextValue = plaintextValue;
        }

        static ApiKey plaintext(String value) {
            return new ApiKey(false, value.getBytes(StandardCharsets.UTF_8), value);
        }

        static ApiKey hashed(byte[] digest) {
            return new ApiKey(true, digest, null);
        }

        boolean isHashed() {
            return hashed;
        }

        /** The original plaintext key, only present for non-hashed entries. */
        String plaintextValue() {
            return plaintextValue;
        }

        boolean matches(String candidate) {
            byte[] computed;
            if (hashed) {
                computed = sha256(candidate);
            } else {
                computed = candidate.getBytes(StandardCharsets.UTF_8);
            }
            return MessageDigest.isEqual(computed, expected);
        }

        private static byte[] sha256(String value) {
            try {
                return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            } catch (NoSuchAlgorithmException e) {
                throw new IllegalStateException("SHA-256 not available", e);
            }
        }
    }
}
