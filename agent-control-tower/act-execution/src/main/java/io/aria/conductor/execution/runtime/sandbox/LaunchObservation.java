package io.aria.conductor.execution.runtime.sandbox;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Set;

/**
 * The per-launch record of what the fixed image launcher ({@code /opt/aria/launch.mjs})
 * actually reported about itself, as opposed to what the control plane guessed after it
 * timed out. One instance belongs to one launch attempt of one run: the lifecycle
 * creates it before the launcher command starts, the SDK feeds it as the launcher's own
 * output arrives, and the launch path and the writer-stop decision read it.
 *
 * <p>The launcher's evidence channel is its structured output: {@code supervisor.*} and
 * {@code child.*} JSON lines on stdout and stderr, plus the command's exit code. The
 * state here is monotone — a recorded fact is never forgotten — because the same bytes
 * can legitimately arrive twice (streamed, then accumulated) and a later line must not
 * erase an earlier one.
 *
 * <p>A line is read as soon as it is <em>whole</em>, not only once its newline arrives: the
 * SDK forwards the command's bytes verbatim, so a terminator is not a fact this record may
 * depend on — waiting for one would leave a successful launch's {@code supervisor.started}
 * unread until its command exited, which is never inside the verdict window.
 *
 * <p>What it can prove, and what it never claims (each bullet is pinned by a test):
 * <ul>
 *   <li><b>a spawn was reported</b> — an event that only exists after
 *       {@code launchRun(profile)} ran (launch.mjs:289, then :302, :307, :312, :320), or
 *       any output line that is not one of the launcher's own events: before the spawn
 *       the launcher writes only through {@code emit()} (launch.mjs:59-61), and a child's
 *       raw output is forwarded only after the spawn (launch.mjs:304-305). An unparseable
 *       line is therefore unreadable as an event AND is spawn evidence.</li>
 *   <li><b>a fault was reported</b> — the {@code supervisor.fault} line the launcher's
 *       top-level handler writes to <em>stderr</em> (launch.mjs:331-338) with its
 *       {@code name} and its own {@code message}. That message is the reason the image
 *       refused the launch, and it is what the operator must get to act on.</li>
 *   <li><b>the {@link FaultClass} of that fault</b> — {@link FaultClass#NEVER_SPAWNED}
 *       only when the fault is the launcher's own {@code LaunchFault} class, whose every
 *       constructor is reachable only before the spawn (see {@link FaultClass}), with no
 *       spawn evidence, no unreadable line and a known command outcome. Anything else —
 *       a post-spawn fault, an unparseable stream, a command whose outcome never arrived
 *       — is {@link FaultClass#AFTER_SPAWN} or {@link FaultClass#UNREADABLE}, and neither
 *       may be treated as "no writer could ever have started".</li>
 *   <li><b>the refusal a waiting launch path may act on</b> — {@link #refusalReason()}
 *       answers with the launcher's own sentence only when a fault was reported <em>and</em>
 *       it is {@link FaultClass#NEVER_SPAWNED}; every other state, including the unreadable
 *       ones above, answers {@code null} so a waiter keeps today's behaviour.
 *       {@link #refusalProvenAt()} stamps the instant that proof landed, which is the only
 *       measurement that separates "the transport delivered late" from "nobody looked
 *       again".</li>
 * </ul>
 *
 * <p>Failing to read the stream is never a conclusion about the writers: an absent,
 * truncated, unparseable or aborted capture leaves {@link #neverSpawnedProven()} false,
 * which keeps today's refusal behaviour.
 */
public final class LaunchObservation {

    /**
     * The class of a launcher fault, decided by where launch.mjs can still throw it.
     *
     * <ul>
     *   <li>{@link #NEVER_SPAWNED}: the {@code LaunchFault} class (launch.mjs:50-56).
     *       Every {@code new LaunchFault(...)} is constructed from {@code main()} lines
     *       263-282 — argument parsing (68, 70, 265), manifest validation (79, 82, 96-142,
     *       272), the allowlist load and entrypoint resolution (165, 170, 180, 183, 189,
     *       192) and the working-directory check (280) — all of which strictly precede the
     *       spawn at line 289. A fault of this class proves no child of this run exists,
     *       which is the only class that may justify destroying an environment without
     *       asking {@code stop-writers.mjs} about it.</li>
     *   <li>{@link #AFTER_SPAWN}: a spawn was reported, so a writer exists (or existed)
     *       whatever the fault then says. launch.mjs:301 ({@code writeRecord}, after
     *       {@code launchRun}) is exactly the fault that can hit a live child: it exits as
     *       a plain filesystem error (code 70), never as a {@code LaunchFault}.</li>
     *   <li>{@link #UNREADABLE}: a fault was reported but its class cannot be established
     *       — a foreign fault name, an unreadable line, an exit code that contradicts the
     *       fault class, or a command whose outcome never arrived. This is the fail-closed
     *       class: it behaves exactly as the launcher's silence behaved before.</li>
     * </ul>
     */
    public enum FaultClass {
        /** Proven: the launcher refused before {@code launchRun} spawned any child. */
        NEVER_SPAWNED,
        /** The launcher reported a spawn: a writer exists whatever it then faulted on. */
        AFTER_SPAWN,
        /** The evidence supports no conclusion about whether a writer exists. */
        UNREADABLE
    }

    /** Events that only exist once the launcher has spawned the child (launch.mjs:302,307,312,320). */
    private static final Set<String> SPAWN_EVIDENCE_EVENTS = Set.of(
            "supervisor.started", "supervisor.child_error", "supervisor.listening",
            "supervisor.ready_timeout", "child.exit");
    /** The launcher's own fault event, written to stderr (launch.mjs:331-338). */
    private static final String FAULT_EVENT = "supervisor.fault";
    /** The fault class whose every throw site precedes the spawn, and the exit codes it uses. */
    static final String PRE_SPAWN_FAULT_NAME = "LaunchFault";
    static final int PRE_SPAWN_USAGE_EXIT = 64;
    static final int PRE_SPAWN_CONFIG_EXIT = 78;
    /** Bound on the output this record reads: a launcher talking past it is not being read. */
    static final int MAX_TRACKED_BYTES = 64 * 1024;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final StringBuilder stdoutBuffer = new StringBuilder();
    private final StringBuilder stderrBuffer = new StringBuilder();
    private int trackedBytes;

    /** The instant the launcher's pre-spawn refusal first became proven, or null while it is not. */
    private Instant refusalProvenAt;

    private boolean spawnReported;
    private boolean unreadableLineSeen;
    private boolean faultReported;
    private boolean faultNameMatched;
    private String faultName;
    private String faultMessage;
    private boolean outcomeKnown;
    private boolean outcomeUnreadable;
    private Integer exitCode;

    // ------------------------------------------------------------------ feeding

    /**
     * One stdout chunk of the launcher command (streamed or accumulated: the state is
     * monotone, so delivering the same bytes twice changes nothing).
     */
    public void recordStdout(String chunk) {
        append(true, chunk);
    }

    /** One stderr chunk of the launcher command — the fault event is written there. */
    public void recordStderr(String chunk) {
        append(false, chunk);
    }

    /**
     * The launcher command finished. A null {@code exitCode} means the SDK reported none;
     * the completion itself is the fact, and the code only corroborates the fault class.
     */
    public synchronized void commandCompleted(Integer exitCode) {
        this.exitCode = exitCode;
        if (!faultReported && !outcomeUnreadable) {
            // A line the command ended without terminating is still the launcher's verdict.
            flushRemainder(true);
            flushRemainder(false);
        }
        outcomeKnown = true;
        markRefusalProven();
        notifyAll();
    }

    /**
     * The capture of the launcher's outcome broke (the command call itself failed, the
     * stream was lost). The launcher's verdict is then unknown, which must never read as
     * "no writer exists".
     */
    public synchronized void commandAborted() {
        outcomeUnreadable = true;
        notifyAll();
    }

    // ------------------------------------------------------------------ reading

    /** True once the launcher reported a spawn, or emitted output only a child can produce. */
    public synchronized boolean spawnReported() {
        return spawnReported;
    }

    /** True when the launcher wrote its own {@code supervisor.fault} line and it was readable. */
    public synchronized boolean faultReported() {
        return faultReported && !outcomeUnreadable;
    }

    /**
     * The launcher's own verdict in its own words: the event name, the fault class name,
     * the message the image printed and its exit code when one was reported. Empty text
     * when the launcher said nothing.
     */
    public synchronized String launcherVerdict() {
        if (!faultReported) {
            return "";
        }
        StringBuilder text = new StringBuilder(FAULT_EVENT);
        if (faultName != null && !faultName.isBlank()) {
            text.append(" (").append(faultName).append(')');
        }
        if (faultMessage != null && !faultMessage.isBlank()) {
            text.append(": ").append(faultMessage);
        }
        if (exitCode != null) {
            text.append(" [exit ").append(exitCode).append(']');
        }
        return text.toString();
    }

    /** The class of the reported fault, or null while no fault was reported. */
    public synchronized FaultClass faultClass() {
        if (!faultReported) {
            return null;
        }
        if (spawnReported) {
            return FaultClass.AFTER_SPAWN;
        }
        if (!faultNameMatched || unreadableLineSeen || !outcomeKnown || outcomeUnreadable) {
            return FaultClass.UNREADABLE;
        }
        if (exitCode != null && exitCode != PRE_SPAWN_USAGE_EXIT && exitCode != PRE_SPAWN_CONFIG_EXIT) {
            // A code outside the LaunchFault class contradicts the name: the stream is not
            // the launcher's own, so nothing about this run's writers follows from it.
            return FaultClass.UNREADABLE;
        }
        return FaultClass.NEVER_SPAWNED;
    }

    /**
     * The destroy-permitting fact — proven, not guessed — that this launch never spawned a
     * child, so no writer of this run can exist and no {@code stop-writers} call could ever
     * find a launch record, because the launcher never reached the spawn that writes one.
     */
    public synchronized boolean neverSpawnedProven() {
        return faultClass() == FaultClass.NEVER_SPAWNED;
    }

    /**
     * The refusal this record <em>proves</em>, in the launcher's own words, or {@code null}
     * while it proves nothing. Proving it takes both halves of Lane F's rule — a readable
     * {@code supervisor.fault} <b>and</b> the {@link FaultClass#NEVER_SPAWNED} class that
     * only a fault strictly before the spawn can have — so a silent, unreadable, truncated,
     * aborted or post-spawn outcome answers {@code null} here and keeps today's behaviour
     * everywhere it is consulted.
     */
    public synchronized String refusalReason() {
        return faultReported() && neverSpawnedProven() ? launcherVerdict() : null;
    }

    /**
     * The instant {@link #refusalReason()} first became provable, or {@code null} while it
     * is not. Recorded where the proof is completed, never where it is read, so a consumer
     * can tell "the transport delivered late" from "nobody looked again".
     */
    public synchronized Instant refusalProvenAt() {
        return refusalProvenAt;
    }

    /** Stamps the first moment this record proves the launcher refused before spawning. */
    private void markRefusalProven() {
        if (refusalProvenAt == null && faultReported() && neverSpawnedProven()) {
            refusalProvenAt = Instant.now();
        }
    }

    /** Whether the launcher's own outcome (its exit, or a broken capture) was observed. */
    public synchronized boolean outcomeKnown() {
        return outcomeKnown && !outcomeUnreadable;
    }

    /**
     * Blocks until the launcher has said something the launch path can act on — a spawn, a
     * fault, a completed command, a broken capture, or a stream past what this record can
     * read — or the window elapses. A refusal is
     * prompt (the launcher exits as soon as it refuses); a success is not (the launcher then
     * supervises its child until it exits), so this waits on the first verdict and never on
     * the child.
     *
     * @return true when a verdict arrived within the window
     */
    public synchronized boolean awaitVerdict(Duration window) {
        long deadlineNanos = System.nanoTime() + Objects.requireNonNull(window, "window").toNanos();
        while (!verdictReady()) {
            long remainingNanos = deadlineNanos - System.nanoTime();
            if (remainingNanos <= 0) {
                return false;
            }
            try {
                wait(Math.max(1L, remainingNanos / 1_000_000L));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return true;
    }

    /** True when this observation already says everything a launch decision can use. */
    public synchronized boolean verdictReady() {
        // unreadableLineSeen counts too: a stream this record cannot read has said its last
        // word, and a launch decision is "do not destroy", which it can already make.
        return spawnReported || faultReported || outcomeKnown || outcomeUnreadable || unreadableLineSeen;
    }

    @Override
    public synchronized String toString() {
        return "LaunchObservation[spawnReported=" + spawnReported + ", verdict=" + launcherVerdict()
                + ", faultClass=" + faultClass() + ", unreadable=" + (unreadableLineSeen || outcomeUnreadable)
                + ", outcomeKnown=" + outcomeKnown() + "]";
    }

    // ------------------------------------------------------------------ internals

    private void append(boolean stdoutStream, String chunk) {
        if (chunk == null || chunk.isEmpty()) {
            return;
        }
        StringBuilder buffer;
        synchronized (this) {
            if (outcomeUnreadable || faultReported) {
                // The launch's verdict is settled: a fault is recorded, or the capture
                // broke, and no later line can change what the cleanup may conclude.
                return;
            }
            if (trackedBytes + chunk.length() > MAX_TRACKED_BYTES) {
                // Past what this record can hold. Before a spawn that is an unreadable
                // stream; after one the bytes are the child's own output and the spawn
                // evidence already decides the question. A stream this record has stopped
                // reading is itself an end to the wait: nothing more can be concluded from
                // it, so the waiter is released rather than left to sleep out its window.
                unreadableLineSeen = true;
                notifyAll();
                return;
            }
            trackedBytes += chunk.length();
            buffer = stdoutStream ? stdoutBuffer : stderrBuffer;
            buffer.append(chunk);
        }
        drain(buffer);
    }

    private void drain(StringBuilder buffer) {
        while (true) {
            String line;
            synchronized (this) {
                int newline = buffer.indexOf("\n");
                if (newline < 0) {
                    break;
                }
                line = buffer.substring(0, newline);
                buffer.delete(0, newline + 1);
            }
            observe(line);
        }
        readWholeEvent(buffer);
    }

    /**
     * Reads a buffered remainder that is already one whole launcher event, newline or no
     * newline. The SDK hands the command's bytes to the handler exactly as execd reported
     * them — {@code ExecutionEventDispatcher} passes {@code eventNode.getText()} through and
     * adds or strips nothing — so whether a chunk carries its terminator is not a fact this
     * record may depend on. Waiting for the terminator would leave a {@code supervisor.started}
     * unread until the command exits, which a successful launch never does inside the verdict
     * window, and every healthy run would pay that window.
     *
     * <p>A remainder that is not <em>wholly</em> one JSON object stays buffered: a fragment is
     * never read as a verdict, and never as raw child output either.
     */
    private void readWholeEvent(StringBuilder buffer) {
        String event;
        synchronized (this) {
            if (buffer.isEmpty()) {
                return;
            }
            event = buffer.toString();
            if (!isWholeEvent(event)) {
                return;
            }
            buffer.setLength(0);
        }
        observe(event);
    }

    /** True when {@code text} is exactly one JSON object, with nothing trailing after it. */
    private static boolean isWholeEvent(String text) {
        if (!text.startsWith("{")) {
            return false;
        }
        try (com.fasterxml.jackson.core.JsonParser parser = MAPPER.createParser(text)) {
            JsonNode node = MAPPER.readTree(parser);
            return node != null && node.isObject() && parser.nextToken() == null;
        } catch (RuntimeException | java.io.IOException e) {
            return false;
        }
    }

    /** Reads one complete launcher output line and records every fact it carries. */
    private void observe(String line) {
        if (line.isBlank()) {
            return;
        }
        JsonNode event = readEvent(line.strip());
        synchronized (this) {
            if (event == null) {
                unreadableLineSeen = true;
                // The launcher's own pre-spawn writes are all events (launch.mjs:59-61), so
                // raw text on its streams only exists once a child's output is forwarded.
                spawnReported = true;
                notifyAll();
                return;
            }
            String name = event.path("event").isTextual() ? event.path("event").asText() : null;
            if (name == null || name.isBlank()) {
                unreadableLineSeen = true;
                return;
            }
            if (SPAWN_EVIDENCE_EVENTS.contains(name)) {
                spawnReported = true;
                notifyAll();
                return;
            }
            if (FAULT_EVENT.equals(name)) {
                faultReported = true;
                faultName = text(event, "name");
                faultMessage = text(event, "message");
                faultNameMatched = PRE_SPAWN_FAULT_NAME.equals(faultName);
                markRefusalProven();
                notifyAll();
                return;
            }
            // An event outside this contract is unreadable evidence: never a "no writers"
            // conclusion, whatever it claims about itself.
            unreadableLineSeen = true;
        }
    }

    /** Treats an unterminated remainder of a finished command as one more line. */
    private void flushRemainder(boolean stdoutStream) {
        String remainder;
        synchronized (this) {
            StringBuilder buffer = stdoutStream ? stdoutBuffer : stderrBuffer;
            remainder = buffer.toString();
            buffer.setLength(0);
        }
        if (!remainder.isBlank()) {
            observe(remainder);
        }
    }

    private static JsonNode readEvent(String line) {
        if (!line.startsWith("{")) {
            return null;
        }
        try {
            JsonNode parsed = MAPPER.readTree(line);
            return parsed != null && parsed.isObject() ? parsed : null;
        } catch (RuntimeException | java.io.IOException e) {
            return null;
        }
    }

    private static String text(JsonNode node, String field) {
        return node.path(field).isTextual() ? node.path(field).asText() : null;
    }
}
