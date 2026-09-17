package io.aria.conductor.execution.approval;

import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentMap;

/**
 * One-use write grants for worker tool calls (C4 ruling 6).
 *
 * <p>A grant authorizes exactly one worker write invocation, bound to the exact
 * tuple (runId, toolName, canonical argsDigest). The MCP governance aspect calls
 * {@link #consume(UUID, String, String)} on every worker write and denies the
 * call when no grant matches. Semantics:
 * <ul>
 *   <li><b>Single use</b>: the first matching consume removes one grant and
 *       returns {@code true}; an identical replay finds nothing and returns
 *       {@code false}.</li>
 *   <li><b>Exact binding</b>: a different tool, a different run or different
 *       arguments never match — a mismatched attempt consumes nothing, so the
 *       approved call still works afterwards.</li>
 *   <li><b>Exactly-one-winner</b>: two identical concurrent calls that each hold
 *       their own grant both succeed (design section 6.1); two identical
 *       concurrent calls sharing one grant produce exactly one winner because
 *       consumption is a single atomic dequeue.</li>
 *   <li><b>TTL is bounded</b>: every grant expires {@link #GRANT_TTL} after it
 *       was granted, a documented constant.</li>
 *   <li><b>Grants die with the run</b>: {@link #revoke(UUID)} drops every grant
 *       of a run and is meant to be called from the run-lifecycle path.</li>
 * </ul>
 *
 * <p>This service does not issue grants on its own and is not wired to any
 * automatic approval detection: the operator-side approval flow (a later task,
 * C2/C3) decides that a pending write request is approved and then calls
 * {@link #grant(UUID, String, String)} with the digest it computed through
 * {@link #argsDigest(Map)}. C4 only enforces consumption.
 *
 * <p>All state is process memory, so grants also do not survive a restart.
 */
@Service
public class WriteGrantService {

    /** Documented maximum time a granted write stays usable. */
    public static final Duration GRANT_TTL = Duration.ofMinutes(10);

    private final Clock clock;
    private final ConcurrentMap<GrantKey, ConcurrentLinkedQueue<Instant>> grants = new ConcurrentHashMap<>();

    public WriteGrantService() {
        this(Clock.systemUTC());
    }

    /** Deterministic-clock constructor for tests. */
    WriteGrantService(Clock clock) {
        if (clock == null) {
            throw new IllegalArgumentException("clock is required");
        }
        this.clock = clock;
    }

    /**
     * Records one approved write. A second identical grant is a second
     * independent authorization (needed when two identical calls are approved).
     *
     * @throws IllegalArgumentException when any argument is null
     */
    public void grant(UUID runId, String toolName, String argsDigest) {
        requireGrantKey(runId, toolName, argsDigest);
        Instant expiry = clock.instant().plus(GRANT_TTL);
        grants.computeIfAbsent(new GrantKey(runId, toolName, argsDigest),
                key -> new ConcurrentLinkedQueue<>()).add(expiry);
    }

    /**
     * Atomically consumes one grant matching the tuple. Returns {@code true}
     * exactly once per granted authorization; {@code false} for unknown,
     * mismatched, already-consumed or expired grants. Never throws: any missing
     * input is a denial (fail closed).
     */
    public boolean consume(UUID runId, String toolName, String argsDigest) {
        if (runId == null || toolName == null || argsDigest == null) {
            return false;
        }
        ConcurrentLinkedQueue<Instant> pending = grants.get(new GrantKey(runId, toolName, argsDigest));
        if (pending == null) {
            return false;
        }
        Instant expiry = pending.poll();
        return expiry != null && expiry.isAfter(clock.instant());
    }

    /** Drops every grant of a run. Null is a no-op. */
    public void revoke(UUID runId) {
        if (runId == null) {
            return;
        }
        grants.keySet().removeIf(key -> key.runId().equals(runId));
    }

    /**
     * Frozen canonicalizer for write-argument digests; C2/C3 reuse it so the
     * approval-side digest and the enforcement-side digest always agree.
     *
     * <p>Canonical form is JSON with object keys sorted ascending, every value
     * rendered by type ({@code null} stays a JSON null, booleans and numbers are
     * unquoted, strings are quoted with standard JSON escaping, nested maps
     * recurse with sorted keys, iterables and arrays keep their order, any other
     * object is rendered as its {@link String#valueOf(Object)} in quotes so a
     * UUID and its string form digest identically). A null map digests like an
     * empty map. The result is the lowercase SHA-256 hex of the UTF-8 bytes.
     */
    public static String argsDigest(Map<String, Object> namedArgs) {
        String canonicalJson = canonicalJson(namedArgs == null ? Map.of() : namedArgs);
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(canonicalJson.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is mandatory on every Java platform.
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private static String canonicalJson(Map<String, Object> map) {
        TreeMap<String, Object> sorted = new TreeMap<>();
        map.forEach((key, value) -> sorted.put(String.valueOf(key), value));
        StringBuilder json = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Object> entry : sorted.entrySet()) {
            if (!first) {
                json.append(',');
            }
            first = false;
            json.append(quote(entry.getKey())).append(':').append(render(entry.getValue()));
        }
        return json.append('}').toString();
    }

    private static String render(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof String string) {
            return quote(string);
        }
        if (value instanceof Boolean || value instanceof Number) {
            return String.valueOf(value);
        }
        if (value instanceof Map<?, ?> map) {
            TreeMap<String, Object> normalized = new TreeMap<>();
            map.forEach((key, nested) -> normalized.put(String.valueOf(key), nested));
            return canonicalJson(normalized);
        }
        if (value instanceof Iterable<?> iterable) {
            return renderIterable(iterable);
        }
        if (value.getClass().isArray()) {
            List<Object> elements = new ArrayList<>();
            int length = java.lang.reflect.Array.getLength(value);
            for (int i = 0; i < length; i++) {
                elements.add(java.lang.reflect.Array.get(value, i));
            }
            return renderIterable(elements);
        }
        // Any other object (UUID, enum, temporal value): its string form, quoted,
        // so the digest matches an equivalent string argument.
        return quote(String.valueOf(value));
    }

    private static String renderIterable(Iterable<?> iterable) {
        StringBuilder json = new StringBuilder("[");
        boolean first = true;
        for (Object element : iterable) {
            if (!first) {
                json.append(',');
            }
            first = false;
            json.append(render(element));
        }
        return json.append(']').toString();
    }

    private static String quote(String value) {
        StringBuilder escaped = new StringBuilder("\"");
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> escaped.append("\\\"");
                case '\\' -> escaped.append("\\\\");
                case '\b' -> escaped.append("\\b");
                case '\f' -> escaped.append("\\f");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                default -> {
                    if (c < 0x20) {
                        escaped.append(String.format("\\u%04x", (int) c));
                    } else {
                        escaped.append(c);
                    }
                }
            }
        }
        return escaped.append('"').toString();
    }

    private static void requireGrantKey(UUID runId, String toolName, String argsDigest) {
        if (runId == null) {
            throw new IllegalArgumentException("runId is required");
        }
        if (toolName == null || toolName.isBlank()) {
            throw new IllegalArgumentException("toolName is required");
        }
        if (argsDigest == null) {
            throw new IllegalArgumentException("argsDigest is required");
        }
    }

    /** Exact binding tuple: run, tool and canonical argument digest. */
    private record GrantKey(UUID runId, String toolName, String argsDigest) {
    }
}
