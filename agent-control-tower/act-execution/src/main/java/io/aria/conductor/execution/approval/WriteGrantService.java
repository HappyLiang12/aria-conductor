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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicReference;

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
 *   <li><b>Retries never duplicate</b>: {@link #prepareRetryGrant(UUID, String, String)}
 *       prepares the authorization for a retried delivery of an already-approved
 *       invocation — it reuses a still-pending grant, renews one that expired
 *       unused, and refuses once the grant was consumed, so one approval always
 *       has at most one consumable authorization and a retry can never authorize
 *       a second execution.</li>
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
 * {@link #effectiveArgsDigest(Map)} — the same method the enforcement side
 * ({@code WorkerGovernanceAspect}) and the ACP approval side both use. C4 only
 * enforces consumption.
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
        grants.compute(new GrantKey(runId, toolName, argsDigest), (key, queue) -> {
            ConcurrentLinkedQueue<Instant> pending = queue == null ? new ConcurrentLinkedQueue<>() : queue;
            pending.add(expiry);
            return pending;
        });
    }

    /**
     * Atomically consumes one grant matching the tuple. Returns {@code true}
     * exactly once per granted authorization; {@code false} for unknown,
     * mismatched, already-consumed or expired grants. Never throws: any missing
     * input is a denial (fail closed).
     *
     * <p>The dequeue is part of the single atomic per-key map operation — the
     * same critical section {@link #prepareRetryGrant(UUID, String, String)}
     * probes in — so the two can never interleave per key: a retry either runs
     * before the consume (and the consume then takes the entry the retry left in
     * place) or after it (and finds no authorization left). Entries that lapsed
     * unused are skipped and dropped on the way, so a dead head can never deny a
     * live grant sitting behind it. A consume that empties the key's queue also
     * drops the key, so tracking does not grow with consumed grants.
     */
    public boolean consume(UUID runId, String toolName, String argsDigest) {
        if (runId == null || toolName == null || argsDigest == null) {
            return false;
        }
        GrantKey key = new GrantKey(runId, toolName, argsDigest);
        AtomicReference<Instant> granted = new AtomicReference<>();
        // Both consume and grant mutate the key inside a single atomic
        // ConcurrentHashMap operation, so this cleanup, a concurrent grant and a
        // concurrent retry probe serialize per key: a grant can never be enqueued
        // into a queue the map has already detached, and a retry probe can never
        // observe the queue non-empty and then renew it after this dequeue took
        // the only live entry.
        grants.computeIfPresent(key, (k, queue) -> {
            Instant expiry = queue.poll();
            while (expiry != null && !expiry.isAfter(clock.instant())) {
                // Lapsed unused: drop it and look at the next entry.
                expiry = queue.poll();
            }
            granted.set(expiry);
            return queue.isEmpty() ? null : queue;
        });
        Instant grantedExpiry = granted.get();
        return grantedExpiry != null && grantedExpiry.isAfter(clock.instant());
    }

    /** Test seam: how many grant keys are currently tracked (empty keys are dropped). */
    int trackedKeyCount() {
        return grants.size();
    }

    /** What {@link #prepareRetryGrant(UUID, String, String)} found for a retried delivery. */
    public enum RetryGrant {
        /** An unconsumed, unexpired authorization is already pending for the exact tuple; it is
         * left in place and authorizes the retried delivery — the retry mints nothing. */
        REUSED_PENDING,
        /** The tuple's previous authorization expired without ever being consumed: it is replaced
         * by exactly one fresh grant, because no execution was ever authorized by the dead one. */
        RENEWED,
        /** The tuple's authorization is gone — consumed by an execution, or dropped after a denied
         * attempt on an expired grant. A retry must not authorize a second execution. */
        ALREADY_CONSUMED
    }

    /**
     * Prepares the one-use authorization for a retried delivery of an already-approved invocation
     * (F2), so that at most one consumable grant exists for it at any time and a retry can never
     * authorize a second execution of the same approved invocation:
     * <ul>
     *   <li>an authorization still pending for the exact tuple is reused untouched — the retried
     *       delivery rides on the authorization the operator already issued;</li>
     *   <li>an authorization that expired without being consumed is renewed in place: it could
     *       never authorize an execution, so replacing it keeps the count at one and lets the
     *       operator rescue a delivery that failed after the grant's TTL;</li>
     *   <li>a tuple with no authorization left is reported {@link RetryGrant#ALREADY_CONSUMED}:
     *       an execution already happened (or a denied attempt consumed the expired grant), and
     *       minting another one would authorize that invocation a second time.</li>
     * </ul>
     * Dead entries are pruned while probing and {@link #consume} skips them as well, so a lapsed
     * entry can never deny a live grant. Like {@link #grant} and {@link #consume}, the whole
     * decision runs in one atomic per-key map operation on the queue instance the map holds —
     * including the dequeue {@code consume} authorizes with — so those operations stay serialized
     * per key: a retry can neither interleave with a consume nor mint a second authorization for an
     * execution the consume already authorized.
     */
    public RetryGrant prepareRetryGrant(UUID runId, String toolName, String argsDigest) {
        requireGrantKey(runId, toolName, argsDigest);
        GrantKey key = new GrantKey(runId, toolName, argsDigest);
        Instant now = clock.instant();
        AtomicReference<RetryGrant> outcome = new AtomicReference<>(RetryGrant.ALREADY_CONSUMED);
        grants.compute(key, (k, queue) -> {
            if (queue == null || queue.isEmpty()) {
                // No queue: the grant was consumed, or this tuple was never granted. An empty
                // queue still mapped is the transient aftermath of a consume. Either way an
                // execution cannot be ruled out, so the retry is refused (fail closed).
                return null;
            }
            if (hasLiveEntry(queue, now)) {
                queue.removeIf(expiry -> !expiry.isAfter(now));
                outcome.set(RetryGrant.REUSED_PENDING);
                return queue;
            }
            // Every entry is expired: the authorization lapsed unused, so one fresh grant
            // replaces the dead ones.
            queue.clear();
            queue.add(now.plus(GRANT_TTL));
            outcome.set(RetryGrant.RENEWED);
            return queue;
        });
        return outcome.get();
    }

    private static boolean hasLiveEntry(ConcurrentLinkedQueue<Instant> queue, Instant now) {
        for (Instant expiry : queue) {
            if (expiry.isAfter(now)) {
                return true;
            }
        }
        return false;
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

    /**
     * Frozen effective-argument digest: the value both the enforcement side
     * ({@code WorkerGovernanceAspect}) and the ACP approval side (C2/C3) must
     * compute so an approved request matches the invocation it authorizes.
     *
     * <p>Why it exists: {@link #argsDigest(Map)} digests every named argument the
     * invocation carries, including optional parameters the caller omitted (they
     * bind to Java {@code null} and appear as null-valued entries). A
     * {@code permission_request} event only carries the keys the worker actually
     * sent, so the approval side cannot reproduce that full map. Digesting the
     * effective (top-level non-null) map removes the difference without losing
     * binding information: an omitted optional parameter and an explicit JSON
     * {@code null} bind to the same Java invocation. Only the top level is
     * affected — nested values still render canonically, so a nested
     * {@code {x:null}} stays a JSON null and differs from an empty nested object.
     *
     * <p>The governance aspect and the approval side both call this method;
     * {@link #argsDigest(Map)} itself stays unchanged for callers that need the
     * literal argument map.
     */
    public static String effectiveArgsDigest(Map<String, Object> namedArgs) {
        if (namedArgs == null || namedArgs.isEmpty()) {
            return argsDigest(Map.of());
        }
        Map<String, Object> effective = new LinkedHashMap<>();
        namedArgs.forEach((key, value) -> {
            if (value != null) {
                effective.put(key, value);
            }
        });
        return argsDigest(effective);
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
