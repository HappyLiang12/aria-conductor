package io.aria.conductor.execution.runtime.host;

import io.aria.conductor.execution.runtime.CoreConfigurationService;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * The run-owned log of one Host run's core: both stdio streams of the runtime
 * this process launched are drained for the life of the process into one file
 * that outlives the run workspace.
 *
 * <p><b>Why a drain at all.</b> {@code ProcessBuilder}'s default
 * {@link ProcessBuilder.Redirect#PIPE} leaves a bounded OS pipe between the
 * core and this JVM. A core that writes more than that buffer (4-64 KB) blocks
 * in {@code write()} until someone reads; with no reader that is a permanent
 * deadlock and the run hangs with no evidence. Two daemon readers -- one per
 * stream, started immediately after {@code start()} -- keep both pipes empty,
 * so a chatty core can never stall on its own output.
 *
 * <p><b>Where the file lives.</b> The log is written below the run's
 * <em>runtime</em> root ({@code <runtime-root>/runs/<runId>}, the lease's
 * {@code runtimeRoot}, created by {@code RunWorkspaceService.configRoot}), in
 * {@code logs/core-<runId>.log}. That root is the one that survives cleanup:
 * {@code WorkspaceManager.cleanup(runId)} deletes the lease's
 * {@code localRoot} tree ({@code <workspace-dir>/<runId>}) and never the
 * platform runtime root, while {@code HostExecutionBackend.destroy} deletes
 * only the generated configuration directory ({@code <runRoot>/host}) and never
 * its parent. The <em>result</em> root is not usable here: it does not exist
 * until capture, which happens only after the stop, so the last words before an
 * aborted launch or a dead process would have no file to reach.
 *
 * <p><b>Redaction.</b> The log holds diagnostics, not credentials. The exempt
 * names are a <em>positive allowlist</em> handed in by the launcher: exactly the
 * names it sets itself as non-secret system evidence plus the operator's
 * configured {@code aria.cores.launch-system-variables}
 * ({@link CoreConfigurationService#launchNonSecretNames()}). <b>Every other name
 * in the launch environment is redacted by exact value, whatever its length and
 * whatever it looks like</b> -- a name the log was not told is non-secret is
 * treated as one (fail-closed), so a credential can never be written because it
 * is short or because its name does not match a pattern. The alternative --
 * replacing every value of the launch environment -- turned every {@code C:} in a
 * path, plus {@code PATH}, {@code SystemDrive}, {@code NUMBER_OF_PROCESSORS} and
 * {@code TZ}, into {@code [redacted]} -- destroying exactly the evidence the log
 * exists to hold. Replacement is still exact-value, never a "secret-looking"
 * heuristic on the text: a value the profile did not pass is never guessed at.
 *
 * <p><b>Bound.</b> One file is capped at {@value #DEFAULT_MAX_BYTES} bytes
 * (8 MiB) shared by both streams; once the next line would cross the cap, a
 * single marker is written and the remaining output of <em>both</em> streams is
 * discarded. A chatty core can therefore never fill the disk. The marker is a
 * line of ours at column 0, prefixed {@code [aria] }: every core-authored line
 * carries an {@code [out] }/{@code [err] } tag, so the marker cannot be
 * mistaken for -- or forged by -- the core. Each line is flushed as it is
 * written, so the tail -- usually the reason for the failure -- is on disk
 * before a stop or a destroy.
 *
 * <p><b>Line bound.</b> Streams are read in chunks, not through a line reader:
 * a logical line longer than {@value #MAX_LINE_BYTES} bytes is written up to
 * that bound plus an explicit {@code [aria] line truncated} marker and the rest
 * of the line is discarded as it arrives, so one huge line can neither exhaust
 * memory nor postpone the flush of everything before it. A line whose newline
 * arrives in the chunk that crosses the bound is cut by the same rule. The head
 * is assembled with a reserve of the longest secret minus one byte, redacted as
 * a whole, and only then cut on a UTF-8 code point boundary -- so a credential
 * sitting on the cut is removed rather than half-written, and a character the
 * cut splits is dropped whole instead of becoming U+FFFD. A trailing partial
 * line is flushed when the stream ends. Every write path -- the
 * newline-terminated line, the line cut at the bound and the partial line
 * flushed at EOF -- drops a trailing fragment that is only the start of a secret
 * value, because a redaction earlier in the window shrinks the text and can pull
 * that start into the retained range.
 *
 * <p><b>Ownership and the mirrored streams.</b> The drain consumes the
 * process's pipes, so a caller that speaks to the core over stdio would have
 * nothing left to read. {@link #attach(Process)} therefore returns a
 * {@link Process} whose {@code getInputStream()} / {@code getErrorStream()}
 * mirror the raw bytes the drain is reading (best effort: an unread mirror is
 * bounded and drops bytes rather than ever blocking the drain). A dropped byte
 * is never silent: the first overflow of a stream is reported once, in the file
 * and in the log, naming the stream that lost it -- a truncated JSON-RPC stream
 * must not look like a complete one. The launching JVM's live handle keeps
 * exactly the stream-access contract it had before the log existed, and the log
 * still sees every byte.
 *
 * <p>The launcher ({@code HostExecutionBackend}) creates the log, hands it to
 * the controller and {@link #close() closes} it after the process ended;
 * {@link #close()} waits a bounded time for the readers to see EOF, then closes
 * the file and discards any late line, so a stop path can never hang on a pipe.
 * The two reader threads are daemons.
 */
public final class RunCoreLog implements AutoCloseable {

    /** The per-run file cap, shared by stdout and stderr. */
    public static final long DEFAULT_MAX_BYTES = 8L * 1024 * 1024;

    /**
     * The most bytes of one logical line the file keeps; a longer line is written
     * up to this bound plus a truncation marker and the rest of the line is
     * discarded as it is read, so a core that never emits a newline cannot make
     * the drain buffer (or lose) an unbounded line.
     */
    public static final int MAX_LINE_BYTES = 64 * 1024;

    /** Replaces every occurrence of an exact value from the launch profile's environment. */
    static final String REDACTED = "[redacted]";

    private static final System.Logger LOG = System.getLogger(RunCoreLog.class.getName());

    /** Our own lines start at column 0; a core-authored line always carries a stream tag. */
    private static final String ARIA_PREFIX = "[aria] ";

    private static final String LINE_TRUNCATED = ARIA_PREFIX + "line truncated at "
            + MAX_LINE_BYTES + " bytes; the rest of the line was discarded";

    private static final String STDOUT_TAG = "[out] ";
    private static final String STDERR_TAG = "[err] ";
    private static final String LOG_DIRECTORY = "logs";
    /** Bound on waiting for the readers at close: a stop path must never hang on a pipe. */
    private static final Duration CLOSE_TIMEOUT = Duration.ofSeconds(5);
    private static final int STREAM_BUFFER = 8 * 1024;
    /** A mirror nobody reads never grows past this; a live stdio transport is far below it. */
    private static final long MIRROR_BACKLOG_BYTES = 1024 * 1024;

    private final String label;
    private final Path path;
    private final OutputStream sink;
    private final long maxBytes;
    private final List<String> environmentValues;
    /**
     * The longest secret in UTF-8 bytes minus one: the head is assembled this much
     * past the bound so a value the cut splits is still redacted as a whole.
     */
    private final int truncationReserve;
    private final TeeInputStream stdout = new TeeInputStream("stdout", this::reportMirrorOverflow);
    private final TeeInputStream stderr = new TeeInputStream("stderr", this::reportMirrorOverflow);
    private final Object writeLock = new Object();
    private final List<Thread> readers = new ArrayList<>();
    private long written;
    private boolean truncated;
    private boolean closed;
    private int activeReaders;

    private RunCoreLog(String label, Path path, OutputStream sink, long maxBytes,
            Map<String, String> environment, Set<String> nonSecretNames) {
        this.label = label;
        this.path = path;
        this.sink = sink;
        this.maxBytes = maxBytes;
        this.environmentValues = secretsOf(environment, nonSecretNames);
        this.truncationReserve = reserveFor(this.environmentValues);
    }

    // ------------------------------------------------------------------ factories

    /**
     * The one path decision: the run's retained runtime root, its
     * {@code logs} directory, named by run id. Also the path the coordinator
     * names in a run's failure text, so an operator and the writer cannot
     * disagree about where the log is.
     */
    public static Path pathIn(Path runRoot, UUID runId) {
        Objects.requireNonNull(runRoot, "runRoot");
        Objects.requireNonNull(runId, "runId");
        return runRoot.toAbsolutePath().normalize().resolve(LOG_DIRECTORY)
                .resolve("core-" + runId + ".log");
    }

    /**
     * Opens the run's log below its retained runtime root; the previous file of
     * the same run (a retried launch) is replaced. A file that cannot be opened
     * fails the launch: a run whose core output has nowhere to go is not
     * launched with the deadlock this class exists to remove.
     *
     * @param nonSecretNames the launcher's positive allowlist: the names it sets
     *        itself as non-secret system evidence plus the operator's configured
     *        {@code aria.cores.launch-system-variables}
     *        ({@link CoreConfigurationService#launchNonSecretNames()}). Every other
     *        name of {@code environment} is redacted, whatever its value looks like.
     */
    public static RunCoreLog open(Path runRoot, UUID runId, Map<String, String> environment,
            Set<String> nonSecretNames) {
        return open(pathIn(runRoot, runId), runId.toString(), environment, nonSecretNames,
                DEFAULT_MAX_BYTES);
    }

    /** A log that drains both streams and keeps nothing; for a launch with no run-owned file. */
    static RunCoreLog discard() {
        return new RunCoreLog("discarded", null, null, DEFAULT_MAX_BYTES, Map.of(), Set.of());
    }

    /** Test seam: the same writer with an explicit cap so the truncation path is cheap to prove. */
    static RunCoreLog open(Path file, String label, Map<String, String> environment,
            Set<String> nonSecretNames, long maxBytes) {
        Objects.requireNonNull(file, "file");
        if (maxBytes <= 0) {
            throw new IllegalArgumentException("A core log cap must be positive, got " + maxBytes);
        }
        Path directory = file.toAbsolutePath().normalize().getParent();
        try {
            if (directory != null) {
                Files.createDirectories(directory);
            }
            OutputStream sink = new BufferedOutputStream(Files.newOutputStream(file,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE), STREAM_BUFFER);
            return new RunCoreLog(label, file.toAbsolutePath().normalize(), sink, maxBytes,
                    environment, nonSecretNames);
        } catch (IOException e) {
            throw new UncheckedIOException("Unable to open the run-owned core log " + file, e);
        }
    }

    // ------------------------------------------------------------------ drain

    /** The file this log writes, or {@code null} for a discarded log. */
    public Path path() {
        return path;
    }

    /**
     * Starts one daemon reader per stream and returns the handle a caller should
     * use as the process: it delegates every process operation to
     * {@code process} but serves its stdio streams from the drain's mirror.
     * Called immediately after the process was created -- before any further OS
     * verification -- so neither pipe can fill up while the launcher is still
     * busy with the process.
     */
    Process attach(Process process) {
        Objects.requireNonNull(process, "process");
        startReader(STDOUT_TAG, process.getInputStream(), stdout, "out");
        startReader(STDERR_TAG, process.getErrorStream(), stderr, "err");
        return new MirrorProcess(process, stdout, stderr);
    }

    private void startReader(String tag, InputStream stream, TeeInputStream mirror, String streamName) {
        Thread reader = new Thread(() -> drain(tag, stream, mirror),
                "run-core-" + streamName + "-" + label);
        reader.setDaemon(true);
        synchronized (writeLock) {
            readers.add(reader);
            activeReaders++;
        }
        reader.start();
    }

    /**
     * Test seam: one reader over a caller-supplied stream, started exactly as
     * {@link #attach} starts a process's. Lets a test observe what the drain does
     * to the stream it consumed without a child process in the way.
     */
    void startReaderForTest(String streamName, InputStream stream) {
        startReader(STDOUT_TAG, stream, new TeeInputStream(streamName, this::reportMirrorOverflow),
                streamName);
    }

    /**
     * Reads one stream in chunks and assembles logical lines in a buffer bounded
     * by {@link #MAX_LINE_BYTES} plus the straddle reserve. A line that grows past
     * the bound is written up to it plus the explicit truncation marker and the
     * rest of that line is dropped as it arrives; a trailing partial line is
     * flushed when the stream ends. The stream this reader consumed is closed
     * here, in its own {@code finally}: the process pipe is this reader's, and
     * nothing else will release it. Nothing here waits for a newline to consume --
     * or to flush -- what was already read, and nothing grows with a line that
     * never ends.
     */
    private void drain(String tag, InputStream stream, TeeInputStream mirror) {
        byte[] chunk = new byte[STREAM_BUFFER];
        ByteArrayOutputStream line = new ByteArrayOutputStream(256);
        boolean lineDiscarded = false;
        try {
            int read;
            while ((read = stream.read(chunk)) != -1) {
                // The mirror carries the raw bytes: a stdio transport must see exactly
                // what the core wrote, while the file only ever sees redacted text.
                mirror.offer(Arrays.copyOf(chunk, read));
                int start = 0;
                for (int i = 0; i < read; i++) {
                    if (chunk[i] != '\n') {
                        continue;
                    }
                    if (!lineDiscarded) {
                        line.write(chunk, start, i - start);
                        appendLine(tag, line.toByteArray());
                    }
                    line.reset();
                    lineDiscarded = false;
                    start = i + 1;
                }
                int remaining = read - start;
                if (remaining > 0 && !lineDiscarded) {
                    int room = lineCapacity() - line.size();
                    if (remaining > room) {
                        line.write(chunk, start, Math.max(0, room));
                        appendTruncatedLine(tag, line.toByteArray());
                        line.reset();
                        lineDiscarded = true;
                    } else {
                        line.write(chunk, start, remaining);
                    }
                }
            }
        } catch (IOException e) {
            // The process ended or the pipe was closed: what was read is already on disk.
        } finally {
            if (!lineDiscarded && line.size() > 0) {
                appendLine(tag, line.toByteArray());
            }
            closeQuietly(stream);
            mirror.complete();
            boolean last;
            synchronized (writeLock) {
                last = --activeReaders == 0;
            }
            if (last) {
                closeSink();
            }
        }
    }

    /** The head the assembler may hold: the bound, plus room for a secret the cut splits. */
    private int lineCapacity() {
        return MAX_LINE_BYTES + truncationReserve;
    }

    /** Nothing downstream of the drain needs the process pipe, and its OS handle must go. */
    private static void closeQuietly(InputStream stream) {
        try {
            stream.close();
        } catch (IOException ignored) {
            // the reader is done with it either way
        }
    }

    /**
     * One completed line: our tag, the redacted text, one flush. The bound applies
     * here as well, because the newline can arrive in the very chunk that crosses
     * it -- a 65,537-byte line whose last byte and newline were read together used
     * to be written whole and unmarked. The partial-value trim runs here too (this
     * is also the EOF flush path): a line that ends after redaction shrank an
     * earlier value can otherwise end with the start of a second one.
     */
    private void appendLine(String tag, byte[] lineBytes) {
        if (path == null) {
            return; // discarded: draining is the point, keeping is not
        }
        byte[] body = stripCarriageReturn(lineBytes);
        String text = withoutPartialValue(redact(new String(body, 0,
                completeCharacters(body, body.length), StandardCharsets.UTF_8)));
        if (text.getBytes(StandardCharsets.UTF_8).length <= MAX_LINE_BYTES) {
            writeTagged(tag, text);
            return;
        }
        writeTagged(tag, bounded(text) + " " + LINE_TRUNCATED);
    }

    /**
     * One line cut at the documented bound. The head is decoded up to its last
     * complete character and redacted as a whole -- the reserve guarantees that a
     * credential the cut splits is entirely inside it -- and only the redacted
     * text is cut, on a code point boundary, before our own marker is appended
     * (so the marker itself is never a value from the environment).
     */
    private void appendTruncatedLine(String tag, byte[] head) {
        if (path == null) {
            return;
        }
        byte[] body = stripCarriageReturn(head);
        String text = redact(new String(body, 0, completeCharacters(body, body.length),
                StandardCharsets.UTF_8));
        writeTagged(tag, bounded(withoutPartialValue(text)) + " " + LINE_TRUNCATED);
    }

    /**
     * Drops a trailing fragment that is only the beginning of a secret value. It
     * is needed because redaction can <em>shrink</em> the window: a value that
     * ends on the cut loses twenty characters of its own, and the text after it
     * moves into the retained range -- including the start of a second value that
     * never fitted the window whole, which redaction therefore cannot have
     * replaced. Such a start is not core output and must not be written.
     */
    private String withoutPartialValue(String text) {
        String retained = text;
        boolean trimmed = true;
        while (trimmed && !retained.isEmpty()) {
            trimmed = false;
            for (String secret : environmentValues) {
                int longest = Math.min(secret.length() - 1, retained.length());
                for (int length = longest; length > 0; length--) {
                    if (retained.regionMatches(retained.length() - length, secret, 0, length)) {
                        retained = retained.substring(0, retained.length() - length);
                        trimmed = true;
                        break;
                    }
                }
                if (trimmed) {
                    break;
                }
            }
        }
        return retained;
    }

    /** {@code text} cut to {@link #MAX_LINE_BYTES} of UTF-8, never splitting a character. */
    private static String bounded(String text) {
        byte[] utf8 = text.getBytes(StandardCharsets.UTF_8);
        if (utf8.length <= MAX_LINE_BYTES) {
            return text;
        }
        return new String(utf8, 0, completeCharacters(utf8, MAX_LINE_BYTES),
                StandardCharsets.UTF_8);
    }

    /**
     * The end of the last complete UTF-8 character at or before {@code limit}: a
     * cut that splits a character must drop it whole, never decode half of it.
     */
    private static int completeCharacters(byte[] bytes, int limit) {
        int end = Math.min(limit, bytes.length);
        if (end == 0) {
            return 0;
        }
        int start = end - 1;
        while (start > 0 && (bytes[start] & 0xC0) == 0x80) {
            start--; // a continuation byte: walk back to the character's first byte
        }
        int expected = sequenceLength(bytes[start] & 0xFF);
        return expected > 1 && start + expected > end ? start : end;
    }

    private static int sequenceLength(int leadByte) {
        if ((leadByte & 0x80) == 0) {
            return 1;
        }
        if ((leadByte & 0xE0) == 0xC0) {
            return 2;
        }
        if ((leadByte & 0xF0) == 0xE0) {
            return 3;
        }
        if ((leadByte & 0xF8) == 0xF0) {
            return 4;
        }
        return 1; // an invalid lead byte is the decoder's business, not the cut's
    }

    /** The bytes of a completed line, without the {@code \r} of a CRLF ending. */
    private static byte[] stripCarriageReturn(byte[] lineBytes) {
        if (lineBytes.length > 0 && lineBytes[lineBytes.length - 1] == '\r') {
            return Arrays.copyOf(lineBytes, lineBytes.length - 1);
        }
        return lineBytes;
    }

    private void writeTagged(String tag, String text) {
        if (path == null) {
            return;
        }
        writeRendered(tag + text + System.lineSeparator(), streamName(tag));
    }

    /**
     * A line of ours at column 0, prefixed {@code [aria] }: a core-authored line
     * always carries an {@code [out] }/{@code [err] } tag, so this prefix is one
     * the core cannot place at the start of a log line.
     */
    private void appendNotice(String notice) {
        if (path == null) {
            return;
        }
        writeRendered(ARIA_PREFIX + notice + System.lineSeparator(), null);
    }

    private void writeRendered(String rendered, String crossingStream) {
        if (path == null) {
            return;
        }
        byte[] bytes = rendered.getBytes(StandardCharsets.UTF_8);
        synchronized (writeLock) {
            if (closed || sink == null) {
                return;
            }
            if (written + bytes.length > maxBytes) {
                writeCapMarkerLocked(crossingStream);
                return;
            }
            writeLocked(bytes);
            written += bytes.length;
        }
    }

    /**
     * The one marker of the shared cap. It is written once, names both streams --
     * the cap is not per stream -- and says which stream crossed it first, so an
     * operator can tell a chatty stdout from a chatty stderr without guessing.
     */
    private void writeCapMarkerLocked(String crossingStream) {
        if (truncated) {
            return;
        }
        truncated = true;
        writeLocked((ARIA_PREFIX + "core-log capped at " + maxBytes
                + " bytes, shared by stdout and stderr"
                + (crossingStream == null ? "" : " (first reached by " + crossingStream + ")")
                + "; the rest of both streams is discarded" + System.lineSeparator())
                .getBytes(StandardCharsets.UTF_8));
    }

    private static String streamName(String tag) {
        return STDOUT_TAG.equals(tag) ? "stdout" : "stderr";
    }

    /**
     * The mirror of one stream could not keep up with the drain. The drain must
     * never wait for a reader, so the bytes are dropped -- but once, visibly: the
     * file gets a marker naming the stream, and the process log says the same. A
     * silently truncated JSON-RPC stream is indistinguishable from a short one.
     */
    private void reportMirrorOverflow(String stream) {
        String notice = "stdio mirror backlog exceeded on " + stream
                + "; unread raw output is dropped from the live handle (the core log keeps every byte)";
        LOG.log(System.Logger.Level.WARNING, () -> label + ": " + notice);
        appendNotice(notice);
    }

    /** Writes and flushes one line; a disk failure stops the log but never the drain. */
    private void writeLocked(byte[] bytes) {
        try {
            sink.write(bytes);
            sink.flush();
        } catch (IOException e) {
            closed = true;
        }
    }

    private String redact(String line) {
        String redacted = line;
        for (String value : environmentValues) {
            if (redacted.contains(value)) {
                redacted = redacted.replace(value, REDACTED);
            }
        }
        return redacted;
    }

    /**
     * Waits -- boundedly -- for both readers to see EOF, then flushes and closes
     * the file and ends the mirrors. A reader still blocked on a pipe after the
     * bound is not interrupted: the file is closed, its later lines are
     * discarded, and the daemon exits with the process. This is what keeps a
     * stop path from hanging on the drain it started.
     */
    @Override
    public void close() {
        long deadline = System.nanoTime() + CLOSE_TIMEOUT.toNanos();
        List<Thread> snapshot;
        synchronized (writeLock) {
            snapshot = List.copyOf(readers);
        }
        for (Thread reader : snapshot) {
            if (reader == Thread.currentThread()) {
                continue;
            }
            long remainingNanos = deadline - System.nanoTime();
            if (remainingNanos <= 0) {
                break;
            }
            try {
                reader.join(Math.max(1, remainingNanos / 1_000_000));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        closeSink();
        stdout.close();
        stderr.close();
    }

    private void closeSink() {
        synchronized (writeLock) {
            closed = true;
            if (sink == null) {
                return;
            }
            try {
                sink.flush();
                sink.close();
            } catch (IOException ignored) {
                // the log is best effort from here on; the process state is not
            }
        }
    }

    // ------------------------------------------------------------------ redaction

    /**
     * The exact environment values to redact, longest first so a value that
     * contains another value is replaced as a whole before its prefix can split
     * it. The rule is a positive allowlist and fails closed: a value is exempt
     * only when its name is in {@code nonSecretNames} -- the launcher's own fixed
     * names plus the operator's configured {@code aria.cores.launch-system-variables}
     * ({@link CoreConfigurationService#launchNonSecretNames()}). Every other name
     * is redacted whatever its value looks like, so a short credential or a
     * provider name this class has never heard of cannot be written. The
     * alternative -- every value of the launch environment -- replaces the drive
     * letters of every path on Windows and hides the failure the log is for.
     */
    private static List<String> secretsOf(Map<String, String> environment,
            Set<String> nonSecretNames) {
        Set<String> exempt = nonSecretNames == null ? Set.of() : nonSecretNames;
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        environment.forEach((name, value) -> {
            if (name != null && value != null && !value.isEmpty() && !exempt.contains(name)) {
                unique.add(value);
            }
        });
        List<String> sorted = new ArrayList<>(unique);
        sorted.sort(Comparator.comparingInt(String::length).reversed());
        return List.copyOf(sorted);
    }

    /**
     * The longest secret in UTF-8 bytes, minus one. Redaction sees this much past
     * the bound, so every value the cut can split is present in the window whole
     * (a value overlapping the cut starts no later than the cut, and ends at most
     * one byte short of this reserve beyond it).
     */
    private static int reserveFor(List<String> secrets) {
        int longest = 0;
        for (String secret : secrets) {
            longest = Math.max(longest, secret.getBytes(StandardCharsets.UTF_8).length);
        }
        return Math.max(0, longest - 1);
    }

    // ------------------------------------------------------------------ mirror

    /**
     * The raw output of one drained stream, for a caller that speaks to the core
     * over stdio. {@link #offer} never blocks: an unread mirror keeps at most
     * {@link #MIRROR_BACKLOG_BYTES} and drops the rest, because the drain's job
     * is to consume the process pipe, never to wait for a reader. The first drop
     * of a stream is reported once through the constructor's consumer -- a
     * truncated JSON-RPC stream must not stay silent.
     */
    private static final class TeeInputStream extends InputStream {

        private final String streamName;
        private final Consumer<String> overflowReporter;
        private final Deque<byte[]> queued = new ArrayDeque<>();
        private byte[] current;
        private int offset;
        private long queuedBytes;
        private boolean complete;
        private boolean closed;
        private boolean overflowReported;

        private TeeInputStream(String streamName, Consumer<String> overflowReporter) {
            this.streamName = streamName;
            this.overflowReporter = overflowReporter;
        }

        void offer(byte[] chunk) {
            boolean report = false;
            synchronized (this) {
                if (closed || complete) {
                    return;
                }
                if (queuedBytes + chunk.length > MIRROR_BACKLOG_BYTES) {
                    report = !overflowReported;
                    overflowReported = true;
                } else {
                    queued.addLast(chunk);
                    queuedBytes += chunk.length;
                    notifyAll();
                }
            }
            if (report) {
                overflowReporter.accept(streamName);
            }
        }

        synchronized void complete() {
            complete = true;
            notifyAll();
        }

        @Override
        public synchronized int read() throws IOException {
            byte[] single = new byte[1];
            int read = read(single, 0, 1);
            return read < 0 ? -1 : single[0] & 0xff;
        }

        @Override
        public synchronized int read(byte[] buffer, int offset, int length) throws IOException {
            Objects.checkFromIndexSize(offset, length, buffer.length);
            if (length == 0) {
                return 0;
            }
            while (true) {
                if (closed) {
                    return -1;
                }
                if (current != null) {
                    int copied = Math.min(length, current.length - this.offset);
                    System.arraycopy(current, this.offset, buffer, offset, copied);
                    this.offset += copied;
                    queuedBytes -= copied;
                    if (this.offset == current.length) {
                        current = null;
                    }
                    return copied;
                }
                if (!queued.isEmpty()) {
                    current = queued.removeFirst();
                    this.offset = 0;
                    continue;
                }
                if (complete) {
                    return -1;
                }
                try {
                    wait();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new InterruptedIOException("interrupted while reading the mirrored core stream");
                }
            }
        }

        @Override
        public synchronized int available() {
            return (int) Math.min(Integer.MAX_VALUE, queuedBytes);
        }

        @Override
        public synchronized void close() {
            closed = true;
            queued.clear();
            current = null;
            queuedBytes = 0;
            notifyAll();
        }
    }

    /**
     * The live handle the launching JVM keeps: every process operation is the
     * real process, only the stdio read side is the drain's mirror. The write
     * side (the core's stdin) stays the real pipe, so a stdio transport still
     * works end to end.
     */
    private static final class MirrorProcess extends Process {

        private final Process delegate;
        private final InputStream stdout;
        private final InputStream stderr;

        private MirrorProcess(Process delegate, InputStream stdout, InputStream stderr) {
            this.delegate = delegate;
            this.stdout = stdout;
            this.stderr = stderr;
        }

        @Override
        public OutputStream getOutputStream() {
            return delegate.getOutputStream();
        }

        @Override
        public InputStream getInputStream() {
            return stdout;
        }

        @Override
        public InputStream getErrorStream() {
            return stderr;
        }

        @Override
        public int waitFor() throws InterruptedException {
            return delegate.waitFor();
        }

        @Override
        public boolean waitFor(long timeout, TimeUnit unit) throws InterruptedException {
            return delegate.waitFor(timeout, unit);
        }

        @Override
        public int exitValue() {
            return delegate.exitValue();
        }

        @Override
        public void destroy() {
            delegate.destroy();
        }

        @Override
        public Process destroyForcibly() {
            delegate.destroyForcibly();
            return this;
        }

        @Override
        public boolean isAlive() {
            return delegate.isAlive();
        }

        @Override
        public long pid() {
            return delegate.pid();
        }

        @Override
        public ProcessHandle toHandle() {
            return delegate.toHandle();
        }

        @Override
        public ProcessHandle.Info info() {
            return delegate.info();
        }

        @Override
        public CompletableFuture<Process> onExit() {
            return delegate.onExit();
        }

        @Override
        public boolean supportsNormalTermination() {
            return delegate.supportsNormalTermination();
        }

        @Override
        public Stream<ProcessHandle> children() {
            return delegate.children();
        }

        @Override
        public Stream<ProcessHandle> descendants() {
            return delegate.descendants();
        }

        @Override
        public String toString() {
            return delegate.toString();
        }
    }
}
