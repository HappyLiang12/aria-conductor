package io.aria.conductor.execution.runtime.host;

import io.aria.conductor.common.AriaConstants;
import io.aria.conductor.common.runtime.AgentExecutionSettings;
import io.aria.conductor.common.runtime.ExecutionMode;
import io.aria.conductor.execution.mcp.McpProperties;
import io.aria.conductor.execution.mcp.RunMcpWiring;
import io.aria.conductor.execution.runtime.ExecutionSpec;
import io.aria.conductor.execution.runtime.LaunchProfile;
import io.aria.conductor.execution.runtime.PreparedEnvironment;
import io.aria.conductor.execution.runtime.SecretBundle;
import io.aria.conductor.execution.runtime.core.OpenCodeCoreAdapter;
import io.aria.conductor.execution.security.ActorTokenService;
import io.aria.conductor.execution.tool.WorkspaceManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The run-owned log of a Host run's core: the pipe drain that keeps a chatty
 * core from deadlocking in {@code write()}, and the file that outlives the run
 * workspace so the core's own last words are recoverable.
 *
 * <p>Every fixture is launched by the test JVM's own {@code java}
 * ({@link CoreLogFixture}), never by {@code node}: the hosted
 * {@code Java Unit Tests} job has no {@code setup-node} step, so a suite that
 * spawned {@code node} would fail the release lane.
 */
@Timeout(120)
class RunCoreLogTest {

    private static final UUID RUN = UUID.randomUUID();
    private static final String SYNTHETIC_KEY = "synthetic-llm-key-not-a-credential-4f7a2c";
    private static final String SYNTHETIC_TOKEN = "synthetic-session-token-not-a-credential-91bd";
    private static final String WINDOWS_PATH = "C:\\Users\\operator\\AppData\\Local\\Temp";
    private static final String POSIX_PATH = "/tmp/aria-home";

    private final List<Path> directories = new ArrayList<>();

    @AfterEach
    void tearDown() {
        directories.forEach(RunCoreLogTest::deleteTree);
        directories.clear();
    }

    /**
     * Finding 5 of the review: the fixtures are the test JVM's own {@code java}.
     * This asserts the launch actually resolves that binary (no {@code node} on
     * the PATH is required for any of them) and that the chatty core still fills
     * its pipes synchronously.
     */
    @Test
    void aChattyRuntimeThatFillsItsPipesDoesNotBlockTheLaunch() throws Exception {
        Path working = workingDirectory();
        LaunchProfile profile = chattyProfile(working);

        Process process = OwnedProcessController.launch(profile);

        try {
            assertThat(process.waitFor(20, TimeUnit.SECONDS))
                    .as("a core that writes more than the OS pipe buffer must not block in write()"
                            + " while nobody drains its stdout/stderr; nothing ever read the pipes")
                    .isTrue();
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
                process.waitFor(10, TimeUnit.SECONDS);
            }
        }
    }

    /**
     * The deadlock test above is only as strong as this: the fixture must write
     * far more than an OS pipe buffer, synchronously, before it exits. With no
     * drain at all it has to block -- that is the pre-fix failure the drain
     * removes, and it is asserted here so shrinking the fixture cannot quietly
     * weaken it.
     *
     * <p>P3-5 of the re-review: waiting two seconds from {@code start()} was a
     * startup-timing control, so a loaded host could satisfy it before the
     * fixture ever reached its write. The fixture now announces that it reached
     * {@code main()} first; the interval only starts once that marker has been
     * read, so what it measures is the blocked write and nothing else.
     */
    @Test
    void theChattyFixtureWouldBlockWithoutADrain() throws Exception {
        Path working = workingDirectory();
        LaunchProfile profile = profile(working, Map.of(), "chatty-undrained");
        Process undrained = new ProcessBuilder(profile.argv()).directory(working.toFile()).start();
        try (var stdout = new BufferedReader(
                new InputStreamReader(undrained.getInputStream(), StandardCharsets.UTF_8))) {
            assertThat(stdout.readLine())
                    .as("the fixture must prove it reached its main method before anything is timed; a"
                            + " green here without this marker only proved that a JVM can start")
                    .isEqualTo("fixture-started");
            assertThat(undrained.waitFor(2, TimeUnit.SECONDS))
                    .as("the fixture is past startup and in its synchronous 1.5 MB write, so two seconds"
                            + " without exiting means it blocked in write(); exit means it proved nothing")
                    .isFalse();
        } finally {
            undrained.destroyForcibly();
            undrained.waitFor(10, TimeUnit.SECONDS);
        }
    }

    /**
     * Acceptance: the drain is not enough on its own -- the bytes must land in a
     * run-owned file, bounded by the cap, so the core's own log is recoverable
     * after the workspace (and its XDG data root) is gone.
     */
    @Test
    void theChattyOutputLandsInTheRunOwnedLog() throws Exception {
        Path working = workingDirectory();
        Path runRoot = workingDirectory();
        RunCoreLog coreLog = RunCoreLog.open(runRoot, RUN, Map.of(), Set.of());

        Process process = OwnedProcessController.launch(chattyProfile(working), coreLog);
        try {
            assertThat(process.waitFor(20, TimeUnit.SECONDS))
                    .as("the drain must keep the pipes empty for the whole life of the runtime")
                    .isTrue();
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
                process.waitFor(10, TimeUnit.SECONDS);
            }
            coreLog.close();
        }

        assertThat(coreLog.path()).exists();
        assertThat(coreLog.path()).isEqualTo(RunCoreLog.pathIn(runRoot, RUN));
        String content = Files.readString(coreLog.path());
        assertThat(content).contains("[out] pipe-filler-");
        assertThat(content).contains("[err] stderr-filler");
        assertThat(Files.size(coreLog.path()))
                .as("the log must stay bounded by its cap plus the one truncation marker")
                .isLessThanOrEqualTo(RunCoreLog.DEFAULT_MAX_BYTES + 512);
    }

    /**
     * The drain consumes the process pipes, but the live handle must keep the
     * stdio-transport contract a core behind the host backend can rely on: the
     * raw bytes are mirrored to the process handle while the redacted copy goes
     * to the file.
     */
    @Test
    void theLiveHandleStillServesTheCoreStreamsWhileTheLogRecordsThem() throws Exception {
        Path working = workingDirectory();
        Path runRoot = workingDirectory();
        RunCoreLog coreLog = RunCoreLog.open(runRoot, RUN, Map.of(), Set.of());
        Process process = OwnedProcessController.launch(oneLineProfile(working, "mirror-line"), coreLog);
        try {
            String stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            String stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
            assertThat(process.waitFor(20, TimeUnit.SECONDS)).isTrue();
            assertThat(stderr)
                    .as("a stdio transport must still read the core's own stderr")
                    .contains("mirror-line");
            assertThat(stdout).isEmpty();
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
                process.waitFor(10, TimeUnit.SECONDS);
            }
            coreLog.close();
        }

        assertThat(Files.readString(coreLog.path())).contains("[err] mirror-line");
    }

    @Test
    void bothStreamsAreCapturedWithTheirTagsAndInPerStreamOrder() throws Exception {
        Path working = workingDirectory();
        Path runRoot = workingDirectory();
        RunCoreLog coreLog = RunCoreLog.open(runRoot, RUN, Map.of(), Set.of());
        OwnedProcessController controller = OwnedProcessController.forCurrentPlatform();
        OwnedProcess owned = null;
        try {
            owned = controller.start(RUN, orderedProfile(working), coreLog);
            Process live = owned.liveProcess();
            assertThat(live.waitFor(20, TimeUnit.SECONDS))
                    .as("the controller's start must drain both pipes for the life of the runtime")
                    .isTrue();
        } finally {
            if (owned != null) {
                controller.release(owned);
            }
            controller.close();
            coreLog.close();
        }

        List<String> lines = Files.readAllLines(coreLog.path());
        assertThat(stdoutLines(lines))
                .containsExactly("out-0", "out-1", "out-2", "stdout-done");
        assertThat(stderrLines(lines))
                .containsExactly("err-0", "err-1", "err-2");
    }

    /**
     * Finding 1, third direction: an ordinary line stream is still written line
     * by line, unchanged by the bounded assembler.
     */
    @Test
    void anOrdinaryLineStreamIsWrittenLineByLine() throws Exception {
        Path working = workingDirectory();
        Path runRoot = workingDirectory();
        RunCoreLog coreLog = RunCoreLog.open(runRoot, RUN, Map.of(), Set.of());

        Process process = OwnedProcessController.launch(profile(working, Map.of(), "normal"), coreLog);
        try {
            assertThat(process.waitFor(20, TimeUnit.SECONDS)).isTrue();
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
                process.waitFor(10, TimeUnit.SECONDS);
            }
            coreLog.close();
        }

        List<String> lines = Files.readAllLines(coreLog.path());
        assertThat(stdoutLines(lines)).containsExactly("alpha", "beta");
        assertThat(stderrLines(lines)).containsExactly("gamma");
    }

    /**
     * Finding 1: {@code BufferedReader.readLine()} buffers a logical line with no
     * bound, so one huge line is held in memory, never hits the cap check and is
     * lost entirely if the core dies before the newline. The assembler cuts the
     * line at a documented byte bound and says so.
     */
    @Test
    void oneHugeLogicalLineIsCutAtTheDocumentedBoundInsteadOfBuffered() throws Exception {
        Path working = workingDirectory();
        RunCoreLog coreLog = RunCoreLog.open(working.resolve("huge.log"), "huge-" + RUN, Map.of(),
                Set.of(), RunCoreLog.DEFAULT_MAX_BYTES);

        Process process = OwnedProcessController.launch(profile(working, Map.of(), "huge-line"), coreLog);
        try {
            assertThat(process.waitFor(20, TimeUnit.SECONDS))
                    .as("a core writing one 1.5 MB line must not stall the drain")
                    .isTrue();
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
                process.waitFor(10, TimeUnit.SECONDS);
            }
            coreLog.close();
        }

        String content = Files.readString(coreLog.path());
        assertThat(content)
                .as("a line past the documented bound must be marked, not silently buffered or dropped")
                .contains("[aria] line truncated");
        assertThat(Files.size(coreLog.path()))
                .as("the file must hold the bound plus our tag and marker, never the whole 1.5 MB line")
                .isLessThanOrEqualTo(RunCoreLog.MAX_LINE_BYTES + 512L);
    }

    /**
     * Finding 1: the tail guarantee. A partial line written just before the core
     * exits has no newline, so a line reader holds all of it in memory and only
     * hands it over at EOF -- with nothing bounding it. The head must be on disk,
     * cut at the documented bound and marked; the bytes must not be lost because
     * the newline never came.
     */
    @Test
    void thePartialLineWrittenBeforeTheCoreExitsReachesTheFile() throws Exception {
        Path working = workingDirectory();
        Path runRoot = workingDirectory();
        RunCoreLog coreLog = RunCoreLog.open(runRoot, RUN, Map.of(), Set.of());

        Process process = OwnedProcessController.launch(
                profile(working, Map.of(), "partial", "the-last-words-before-exit"), coreLog);
        try {
            assertThat(process.waitFor(20, TimeUnit.SECONDS)).isTrue();
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
                process.waitFor(10, TimeUnit.SECONDS);
            }
            coreLog.close();
        }

        String content = Files.readString(coreLog.path());
        assertThat(content)
                .as("the bytes a core wrote before exiting must be on disk even without a newline")
                .contains("the-last-words-before-exit");
        assertThat(content)
                .as("a partial line is still bounded and marked at the documented line bound")
                .contains("[aria] line truncated");
        assertThat(Files.size(coreLog.path()))
                .isLessThanOrEqualTo(RunCoreLog.MAX_LINE_BYTES + 512L);
    }

    /**
     * P3-C of the third review: this case used to end below the assembly capacity
     * (65,530 filler + 30 characters = 65,560 &lt; 65,565), so the drain's truncation
     * path never ran and the marker the test saw came from the ordinary line's own
     * bound. The probe shape stays the same -- the value starts 6 bytes before the
     * documented cut and ends past it -- but the line now continues past the
     * assembly window, so the truncation path is the one that writes the head and
     * the rest of the logical line is discarded.
     */
    @Test
    void aCredentialStraddlingTheLineCutLeavesNoPrefixInTheFile() throws Exception {
        Path working = workingDirectory();
        String secret = "~straddling-secret-" + "a".repeat(11); // 30 characters, as in the probe
        assertThat(secret).hasSize(30);
        String tail = "TAIL-past-the-assembly-window-must-not-be-written";
        RunCoreLog coreLog = RunCoreLog.open(working.resolve("cut-straddle.log"), "cut-" + RUN,
                Map.of("LLM_API_KEY", secret), Set.of(secret), RunCoreLog.DEFAULT_MAX_BYTES);

        // The probe's offset: 6 characters of the value sit under the bound, 24 past it.
        int fillerBytes = RunCoreLog.MAX_LINE_BYTES - 6;
        int capacity = RunCoreLog.MAX_LINE_BYTES + secret.length() - 1;
        byte[] line = concat(filler(fillerBytes), utf8(secret), utf8(tail), utf8("\n"),
                utf8("line-after-the-cut\n"));
        try {
            // 8 x 8192 reaches the bound; the next read fills the window and crosses it
            // with no newline in it -- the drain's truncation path, not the newline one.
            coreLog.startReaderForTest("stdout", new ChunkedStream(line, 8192, 8192, 8192, 8192,
                    8192, 8192, 8192, 8192, capacity - 8 * 8192 + 1));
        } finally {
            coreLog.close();
        }

        List<String> stdout = stdoutLines(Files.readAllLines(coreLog.path()));
        assertThat(stdout)
                .as("the line after the bound-crossing one must still be written")
                .contains("line-after-the-cut");
        String firstLine = stdout.get(0);
        assertThat(firstLine)
                .as("a line that continues past the assembly window must be cut and marked")
                .contains("[aria] line truncated");
        String content = Files.readString(coreLog.path());
        assertThat(content)
                .as("the rest of a line that crossed the assembly window is discarded")
                .doesNotContain(tail);
        String leaked = leakedPrefix(content, secret, secret.length());
        assertThat(leaked)
                .as("a credential the cut splits must be redacted whole; a surviving prefix of it"
                        + " ([%s] of [%s]) is a written credential", leaked, secret)
                .isNull();
        assertThat(headOf(firstLine).getBytes(StandardCharsets.UTF_8).length)
                .as("the retained head must still end at the documented bound")
                .isLessThanOrEqualTo(RunCoreLog.MAX_LINE_BYTES);
    }

    /**
     * P3-1, second direction: the cut also split a multi-byte character, so a
     * two- or three-byte character on the bound reached the file as U+FFFD. The
     * retained head must end on a code point boundary.
     */
    @Test
    void aMultiByteCharacterOnTheLineCutIsDroppedWholeInsteadOfBecomingAReplacement() throws Exception {
        Path working = workingDirectory();
        Path runRoot = workingDirectory();
        // One byte of the 3-byte euro sign is the last retained byte of the head.
        int filler = RunCoreLog.MAX_LINE_BYTES - 1;
        RunCoreLog coreLog = RunCoreLog.open(runRoot, RUN, Map.of(), Set.of());

        Process process = OwnedProcessController.launch(
                profile(working, Map.of(), "cut-multibyte", String.valueOf(filler)), coreLog);
        try {
            assertThat(process.waitFor(20, TimeUnit.SECONDS)).isTrue();
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
                process.waitFor(10, TimeUnit.SECONDS);
            }
            coreLog.close();
        }

        // Read as UTF-8: a split character decodes to U+FFFD, a whole one to itself.
        String content = new String(Files.readAllBytes(coreLog.path()), StandardCharsets.UTF_8);
        int replacementAt = content.indexOf('\uFFFD');
        assertThat(replacementAt)
                .as("a character the cut splits must be dropped, never decoded as U+FFFD"
                        + " (replacement character at byte %s of the log)", replacementAt)
                .isNegative();
        assertThat(content).contains("[aria] line truncated");
        assertThat(content)
                .as("the drain must survive the cut and keep the rest of the stream")
                .contains("after-the-cut");
    }

    /**
     * P3-4 of the re-review, pinned on the newline branch specifically: the old
     * fixture let the OS decide whether the crossing byte and its newline arrived in
     * one read, so when they arrived separately the marker came from the truncation
     * branch and this test could pass without exercising the branch it names. The
     * stream here hands out exactly the bound in the first eight reads and the
     * crossing byte with its newline and the next line in the ninth, so only the
     * newline branch can produce the marker -- and the stream must continue past it.
     */
    @Test
    void aLineWhoseNewlineArrivesInTheCrossingChunkIsStillCutAndMarked() throws Exception {
        Path working = workingDirectory();
        RunCoreLog coreLog = RunCoreLog.open(working.resolve("newline-at-bound.log"),
                "newline-" + RUN, Map.of(), Set.of(), RunCoreLog.DEFAULT_MAX_BYTES);

        byte[] line = concat(filler(RunCoreLog.MAX_LINE_BYTES), utf8("x\n"),
                utf8("after-the-long-line\n"));
        try {
            coreLog.startReaderForTest("stdout", new ChunkedStream(line, 8192, 8192, 8192, 8192,
                    8192, 8192, 8192, 8192, line.length - 8 * 8192));
        } finally {
            coreLog.close();
        }

        List<String> stdout = stdoutLines(Files.readAllLines(coreLog.path()));
        assertThat(stdout)
                .as("the crossing line is one line, and the stream continues after it")
                .hasSize(2);
        assertThat(stdout.get(1)).isEqualTo("after-the-long-line");
        String firstLine = stdout.get(0);
        assertThat(firstLine)
                .as("a line of %s bytes whose newline arrives in the crossing chunk must be cut at"
                        + " the documented %s and marked, not written whole (first line bytes: %s)",
                        RunCoreLog.MAX_LINE_BYTES + 1, RunCoreLog.MAX_LINE_BYTES,
                        firstLine.getBytes(StandardCharsets.UTF_8).length)
                .contains("[aria] line truncated");
        assertThat(headOf(firstLine).getBytes(StandardCharsets.UTF_8).length)
                .as("the retained head must end at the documented bound")
                .isLessThanOrEqualTo(RunCoreLog.MAX_LINE_BYTES);
    }

    /**
     * P3-3 of the re-review: the chunked drain replaced a
     * {@code BufferedReader} try-with-resources, and unlike it never closed the
     * process's stream -- so every run left two OS pipe handles to the GC.
     */
    @Test
    void theDrainedProcessStreamIsClosedWhenItReachesEof() throws Exception {
        Path working = workingDirectory();
        RunCoreLog coreLog = RunCoreLog.open(working.resolve("close.log"), "close-" + RUN,
                Map.of(), Set.of(), RunCoreLog.DEFAULT_MAX_BYTES);
        CloseRecordingStream pipe = new CloseRecordingStream("the-last-line-before-eof\n");
        try {
            coreLog.startReaderForTest("stdout", pipe);
        } finally {
            coreLog.close(); // joins the reader
        }

        assertThat(pipe.wasClosed())
                .as("the drain consumed the process pipe, so it must close it: the line reader it"
                        + " replaced did (try-with-resources), and without this every run leaks two"
                        + " OS pipe handles until GC")
                .isTrue();
        assertThat(Files.readString(coreLog.path())).contains("[out] the-last-line-before-eof");
    }

    /**
     * P3-1, third direction: the reserve alone does not make "no prefix" true. A
     * redaction earlier in the window <em>shrinks</em> the text (30 characters
     * become 10), so the retained head reaches past the raw cut and can include
     * the beginning of a <em>second</em> credential that is not complete inside
     * the window -- redaction cannot have replaced it, and it must not be written
     * either.
     *
     * <p>Deterministic by construction: the stream hands out the window in
     * prescribed reads, so no child process and no OS pipe decides the boundary.
     */
    @Test
    void theStartOfASecondCredentialPulledInByRedactionIsNotWritten() throws Exception {
        Path working = workingDirectory();
        String first = "~1-" + "a".repeat(27);
        String second = "~2-" + "b".repeat(27);
        assertThat(first).hasSize(30);
        assertThat(second).hasSize(30);
        RunCoreLog coreLog = RunCoreLog.open(working.resolve("two-secrets.log"), "two-" + RUN,
                Map.of("LLM_API_KEY", first, "ARIA_MCP_TOKEN", second), Set.of(first, second),
                RunCoreLog.DEFAULT_MAX_BYTES);

        // MAX-30 filler, the first value ending exactly on the bound, then the whole
        // second value: the window is MAX + 29, so the second value never fits.
        byte[] window = concat(filler(RunCoreLog.MAX_LINE_BYTES - 30), utf8(first), utf8(second));
        int capacity = RunCoreLog.MAX_LINE_BYTES + second.length() - 1;
        assertThat(window.length).isEqualTo(capacity + 1);
        byte[] line = concat(window, utf8("c".repeat(40) + "\n"), utf8("line-after-the-cut\n"));
        // 8 x 8192 reaches MAX, 29 more fills the window, one more byte crosses it
        // with no newline in the chunk -- the drain's own truncation path.
        ChunkedStream pipe = new ChunkedStream(line,
                8192, 8192, 8192, 8192, 8192, 8192, 8192, 8192, capacity - 8 * 8192, 1);
        try {
            coreLog.startReaderForTest("stdout", pipe);
        } finally {
            coreLog.close();
        }

        String content = Files.readString(coreLog.path());
        assertThat(content).contains("[aria] line truncated");
        String leaked = leakedPrefix(content, second, second.length());
        assertThat(leaked)
                .as("the start of a credential pulled into the head by an earlier redaction is not"
                        + " evidence: [%s] of [%s] was written", leaked, second)
                .isNull();
        assertThat(content)
                .as("the drain must survive the cut and keep the rest of the stream")
                .contains("line-after-the-cut");
    }

    /**
     * P2 of the third review: the partial-value trim lived only on the truncated
     * path, so a line that ends at EOF after an earlier complete value could still
     * write the start of a second one. Probe shape: 65,506 filler + a 30-character
     * first value + the first 20 characters of a second. Redaction shrinks the first
     * value by 20, so the text lands exactly on the bound and is written whole --
     * prefix included (the reviewer saw {@code leakedPrefix=[s2-bbb]}).
     */
    @Test
    void aLineEndingAtEofDoesNotWriteTheStartOfASecondSecret() throws Exception {
        Path working = workingDirectory();
        String first = "~1-" + "a".repeat(27);
        String second = "~2-" + "b".repeat(27);
        RunCoreLog coreLog = RunCoreLog.open(working.resolve("eof-prefix.log"), "eof-" + RUN,
                Map.of("LLM_API_KEY", first, "ARIA_MCP_TOKEN", second), Set.of(first, second),
                RunCoreLog.DEFAULT_MAX_BYTES);

        byte[] line = concat(filler(RunCoreLog.MAX_LINE_BYTES - 30), utf8(first),
                utf8(second.substring(0, 20)));
        try {
            coreLog.startReaderForTest("stdout", new ChunkedStream(line, 8192, 8192, 8192, 8192,
                    8192, 8192, 8192, 8192, line.length - 8 * 8192));
        } finally {
            coreLog.close();
        }

        String content = Files.readString(coreLog.path());
        assertThat(content)
                .as("the earlier complete value must still be redacted; otherwise this proves nothing")
                .doesNotContain(first);
        assertThat(leakedPrefix("prefix " + second.substring(0, 1), second, second.length()))
                .as("positive control: the probe can see a one-character prefix when one was written")
                .isEqualTo(second.substring(0, 1));
        assertThat(leakedPrefix(content, second, 20))
                .as("a line that ends at EOF after redaction shrank an earlier value must not write"
                        + " the start of a second one: [%s]", leakedPrefix(content, second, 20))
                .isNull();
        assertThat(content)
                .as("the line never crossed the bound, so the truncation path is not the one under test")
                .doesNotContain("[aria] line truncated");
    }

    /**
     * P2, second direction: the same bytes with the newline present take the normal
     * line path, which skipped the very same trim. Both paths must be closed.
     */
    @Test
    void aNewlineTerminatedLineDoesNotWriteTheStartOfASecondSecret() throws Exception {
        Path working = workingDirectory();
        String first = "~1-" + "a".repeat(27);
        String second = "~2-" + "b".repeat(27);
        RunCoreLog coreLog = RunCoreLog.open(working.resolve("newline-prefix.log"), "nl-" + RUN,
                Map.of("LLM_API_KEY", first, "ARIA_MCP_TOKEN", second), Set.of(first, second),
                RunCoreLog.DEFAULT_MAX_BYTES);

        byte[] line = concat(filler(RunCoreLog.MAX_LINE_BYTES - 30), utf8(first),
                utf8(second.substring(0, 20)), utf8("\n"));
        try {
            coreLog.startReaderForTest("stdout", new ChunkedStream(line, 8192, 8192, 8192, 8192,
                    8192, 8192, 8192, 8192, line.length - 8 * 8192));
        } finally {
            coreLog.close();
        }

        String content = Files.readString(coreLog.path());
        assertThat(content)
                .as("the earlier complete value must still be redacted; otherwise this proves nothing")
                .doesNotContain(first);
        assertThat(leakedPrefix(content, second, 20))
                .as("a newline-terminated line that ends at the bound after an earlier redaction must"
                        + " not write the start of a second value: [%s]",
                        leakedPrefix(content, second, 20))
                .isNull();
    }

    /**
     * The core rule: the values the profile declares secret -- the credential
     * bundle the adapter injected plus {@code ARIA_MCP_TOKEN} -- are masked by
     * exact value, whatever their name looks like. The names below are
     * deliberately <em>not</em> credential-shaped, so the name rule cannot account
     * for the masking: only the declared values can. The log reads them from the
     * launch profile, never from a parallel list.
     */
    @Test
    void everyDeclaredSecretValueTheChildEchoesIsRedacted() throws Exception {
        Path working = workingDirectory();
        Path runRoot = workingDirectory();
        LaunchProfile profile = withSecrets(echoProfile(working,
                Map.of("SYNTHETIC_LLM_ACCESS", SYNTHETIC_KEY,
                        "SYNTHETIC_SESSION_VALUE", SYNTHETIC_TOKEN),
                "SYNTHETIC_LLM_ACCESS", "SYNTHETIC_SESSION_VALUE"),
                Set.of(SYNTHETIC_KEY, SYNTHETIC_TOKEN));
        RunCoreLog coreLog = RunCoreLog.open(runRoot, RUN, profile);

        Process process = OwnedProcessController.launch(profile, coreLog);
        try {
            assertThat(process.waitFor(20, TimeUnit.SECONDS)).isTrue();
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
                process.waitFor(10, TimeUnit.SECONDS);
            }
            coreLog.close();
        }

        String content = Files.readString(coreLog.path());
        assertThat(content)
                .as("a credential the profile handed the core must never be written verbatim")
                .doesNotContain(SYNTHETIC_KEY)
                .doesNotContain(SYNTHETIC_TOKEN)
                .contains("out-SYNTHETIC_LLM_ACCESS=[redacted]")
                .contains("err-SYNTHETIC_SESSION_VALUE=[redacted]")
                .contains("visible-line");
    }

    /**
     * The defence-in-depth rule: a value under a credential-shaped name is masked
     * whatever its length, even when the log was not told it is a secret. And the
     * rest of the environment is written as the core wrote it -- a path is
     * evidence, not a credential.
     */
    @Test
    void aCredentialShapedNameIsRedactedWhateverItsLengthWhileSystemPathsSurviveVerbatim() throws Exception {
        Path working = workingDirectory();
        Path runRoot = workingDirectory();
        LaunchProfile profile = echoProfile(working, Map.of(
                "LLM_API_KEY", "short-9f2",
                "TEMP", WINDOWS_PATH,
                "HOME", POSIX_PATH,
                "PATH", "C:\\Windows;C:\\Tools\\bin",
                "NUMBER_OF_PROCESSORS", "16"), "LLM_API_KEY", "TEMP", "HOME", "PATH");
        RunCoreLog coreLog = RunCoreLog.open(runRoot, RUN, profile);

        Process process = OwnedProcessController.launch(profile, coreLog);
        try {
            assertThat(process.waitFor(20, TimeUnit.SECONDS)).isTrue();
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
                process.waitFor(10, TimeUnit.SECONDS);
            }
            coreLog.close();
        }

        String content = Files.readString(coreLog.path());
        assertThat(content)
                .as("a credential-shaped name is redacted whatever its length")
                .doesNotContain("short-9f2")
                .contains("out-LLM_API_KEY=[redacted]");
        assertThat(content)
                .as("a Windows path is evidence, not a credential: redacting it hides the failure")
                .contains("err-TEMP=" + WINDOWS_PATH);
        assertThat(content)
                .as("a POSIX path is evidence, not a credential")
                .contains("out-HOME=" + POSIX_PATH);
        assertThat(content)
                .as("PATH is evidence of which runtime ran; it is not credential-shaped")
                .contains("out-PATH=C:\\Windows;C:\\Tools\\bin");
    }

    /**
     * Everything the launcher writes that is not a declared secret and not
     * credential-shaped is evidence: the run-owned roots and an operator's
     * runtime variable survive verbatim, because the log exists to show which
     * environment the core actually ran with.
     */
    @Test
    void theLaunchersOwnRootsAndOrdinaryNamesSurviveVerbatim() throws Exception {
        Path working = workingDirectory();
        Path runRoot = workingDirectory();
        LaunchProfile profile = echoProfile(working, Map.of(
                "XDG_CONFIG_HOME", "C:\\Aria\\runs\\run-1\\host\\config",
                "XDG_DATA_HOME", "C:\\Aria\\runs\\run-1\\host\\data",
                "XDG_CACHE_HOME", "C:\\Aria\\runs\\run-1\\host\\cache",
                "JAVA_HOME", "C:\\Program Files\\Eclipse Adoptium\\jdk-21.0.11-hotspot",
                "LLM_API_KEY", SYNTHETIC_KEY),
                "XDG_CONFIG_HOME", "XDG_DATA_HOME", "XDG_CACHE_HOME", "JAVA_HOME", "LLM_API_KEY");
        RunCoreLog coreLog = RunCoreLog.open(runRoot, RUN, profile);

        Process process = OwnedProcessController.launch(profile, coreLog);
        try {
            assertThat(process.waitFor(20, TimeUnit.SECONDS)).isTrue();
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
                process.waitFor(10, TimeUnit.SECONDS);
            }
            coreLog.close();
        }

        String content = Files.readString(coreLog.path());
        assertThat(content)
                .as("the launcher's run-owned XDG roots are paths, not credentials: the log must show"
                        + " which roots the core used")
                .contains("out-XDG_CONFIG_HOME=C:\\Aria\\runs\\run-1\\host\\config")
                .contains("err-XDG_DATA_HOME=C:\\Aria\\runs\\run-1\\host\\data")
                .contains("out-XDG_CACHE_HOME=C:\\Aria\\runs\\run-1\\host\\cache");
        assertThat(content)
                .as("a name outside the credential shape is written as the core wrote it")
                .contains("out-JAVA_HOME=C:\\Program Files\\Eclipse Adoptium\\jdk-21.0.11-hotspot");
        assertThat(content)
                .as("a credential-shaped name is masked by defence in depth")
                .contains("out-LLM_API_KEY=[redacted]");
    }

    /**
     * Defence in depth: a credential-shaped name is masked regardless of length,
     * even when the launcher never declared its value secret. The earlier rule's
     * length gate wrote {@code SOME_PASSWORD=tiny} and the 8-character
     * {@code DEEPSEEK_API_KEY=test-key} verbatim, and the reviewer's
     * {@code DEEPSEEK_AUTH=sk-live-...} probe was written in full.
     */
    @Test
    void credentialShapedNamesAreRedactedWhateverTheirLength() throws Exception {
        Path working = workingDirectory();
        Path runRoot = workingDirectory();
        LaunchProfile profile = echoProfile(working, Map.of(
                "SOME_PASSWORD", "tiny",
                "DEEPSEEK_API_KEY", "test-key",
                "DEEPSEEK_AUTH", "sk-live-synthetic-not-a-real-key"),
                "SOME_PASSWORD", "DEEPSEEK_API_KEY", "DEEPSEEK_AUTH");
        RunCoreLog coreLog = RunCoreLog.open(runRoot, RUN, profile);

        Process process = OwnedProcessController.launch(profile, coreLog);
        try {
            assertThat(process.waitFor(20, TimeUnit.SECONDS)).isTrue();
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
                process.waitFor(10, TimeUnit.SECONDS);
            }
            coreLog.close();
        }

        String content = Files.readString(coreLog.path());
        assertThat(content)
                .as("a credential-shaped name shorter than the old length gate is still a credential")
                .doesNotContain("tiny")
                .contains("out-SOME_PASSWORD=[redacted]");
        assertThat(content)
                .as("the operator's model-provider key is a credential whatever its length")
                .doesNotContain("test-key")
                .contains("err-DEEPSEEK_API_KEY=[redacted]");
        assertThat(content)
                .as("a provider auth key is credential-shaped and must not be written")
                .doesNotContain("sk-live-synthetic-not-a-real-key")
                .contains("out-DEEPSEEK_AUTH=[redacted]");
    }

    /**
     * The primary rule is by value, so a secret whose name carries no credential
     * shape at all is still masked when the profile says the launcher injected
     * it. {@code DEEPSEEK_ACCESS} matches none of the name patterns, so only the
     * value rule can keep it out of the file.
     */
    @Test
    void aDeclaredSecretIsMaskedEvenWhenItsNameIsNotCredentialShaped() throws Exception {
        Path working = workingDirectory();
        Path runRoot = workingDirectory();
        String auth = "sk-live-synthetic-credential-not-a-real-key";
        LaunchProfile profile = withSecrets(echoProfile(working,
                Map.of("DEEPSEEK_ACCESS", auth), "DEEPSEEK_ACCESS", "DEEPSEEK_ACCESS"), Set.of(auth));
        RunCoreLog coreLog = RunCoreLog.open(runRoot, RUN, profile);

        Process process = OwnedProcessController.launch(profile, coreLog);
        try {
            assertThat(process.waitFor(20, TimeUnit.SECONDS)).isTrue();
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
                process.waitFor(10, TimeUnit.SECONDS);
            }
            coreLog.close();
        }

        String content = Files.readString(coreLog.path());
        assertThat(content)
                .as("the profile declared this value secret, so its name shape cannot matter")
                .doesNotContain(auth)
                .contains("out-DEEPSEEK_ACCESS=[redacted]");
    }

    /**
     * The documented accepted cost, pinned so it cannot be mistaken for an
     * oversight: a value the launcher does not know it injected -- here an
     * operator's {@code opencode.sandbox-env} entry under a name that is neither
     * launcher-owned nor credential-shaped -- is written as the core wrote it.
     * Masking what is not known would require guessing from the text, and that is
     * the heuristic this rule refuses.
     */
    @Test
    void anOperatorValueTheLauncherDidNotInjectIsWrittenAsTheCoreWroteIt() throws Exception {
        Path working = workingDirectory();
        Path runRoot = workingDirectory();
        String operatorValue = "operator-supplied-synthetic-not-a-real-credential";
        LaunchProfile profile = echoProfile(working,
                Map.of("DEEPSEEK_ACCESS", operatorValue), "DEEPSEEK_ACCESS", "DEEPSEEK_ACCESS");
        RunCoreLog coreLog = RunCoreLog.open(runRoot, RUN, profile);

        Process process = OwnedProcessController.launch(profile, coreLog);
        try {
            assertThat(process.waitFor(20, TimeUnit.SECONDS)).isTrue();
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
                process.waitFor(10, TimeUnit.SECONDS);
            }
            coreLog.close();
        }

        String content = Files.readString(coreLog.path());
        assertThat(content)
                .as("the accepted failure mode: a secret the launcher does not know it injected is"
                        + " written; the log stays useful for everything it does know")
                .contains("out-DEEPSEEK_ACCESS=" + operatorValue);
    }

    /**
     * The input comes from the launch object, not a parallel list: the real
     * OpenCode adapter builds the profile, and the log opened from that very
     * profile masks exactly the credential-bundle value and the minted
     * {@code ARIA_MCP_TOKEN} it carries, while the run-owned XDG root and the
     * credential-shaped provider key are handled by their own rules.
     */
    @Test
    void theRealAdapterProfileSuppliesExactlyTheSecretsItsLogMasks() throws Exception {
        Path root = workingDirectory();
        Path workspace = Files.createDirectories(root.resolve("workspace"));
        Path configuration = Files.createDirectories(root.resolve("runtime").resolve("host"));
        UUID runId = UUID.randomUUID();
        ExecutionSpec spec = new ExecutionSpec(runId, AriaConstants.ARIA_AGENT_ID, "opencode",
                ExecutionMode.HOST,
                new AgentExecutionSettings("opencode", ExecutionMode.HOST, null, null, null),
                "opencode:provider-key", "rev-1", Instant.now().plusSeconds(2700));
        PreparedEnvironment environment = new PreparedEnvironment(runId, ExecutionMode.HOST,
                "host-" + runId, workspace.toString(), configuration.toString(),
                URI.create("http://127.0.0.1:46321/"));
        McpProperties mcp = new McpProperties();
        mcp.setAuthMode("actor");
        mcp.setPort(4815);
        RunMcpWiring wiring = new RunMcpWiring(mcp, new ActorTokenService());
        OpenCodeCoreAdapter adapter = new OpenCodeCoreAdapter(
                new OpenCodeCoreAdapter.OpenCodeProfile("opencode", List.of(),
                        Map.of("LLM_API_KEY", "test-key"), "1.14.31", "efficient"), wiring);
        String providerAuth = "sk-live-synthetic-credential-not-a-real-key";

        LaunchProfile profile = adapter.launchProfile(spec, environment,
                new SecretBundle("opencode:provider-key", Map.of("DEEPSEEK_ACCESS", providerAuth)));
        String token = profile.env().get("ARIA_MCP_TOKEN");
        assertThat(token).as("the wired Aria run must carry its run bearer").isNotBlank();
        assertThat(profile.secretValues())
                .as("the profile declares the credential value and the run bearer; the operator's"
                        + " provider environment value is part of the launch's secrets too")
                .containsExactlyInAnyOrder(providerAuth, token, "test-key");

        Path runRoot = workingDirectory();
        RunCoreLog coreLog = RunCoreLog.open(runRoot, runId, profile);
        String xdg = profile.env().get("XDG_CONFIG_HOME");
        byte[] line = ("out-DEEPSEEK_ACCESS=" + providerAuth
                + " out-ARIA_MCP_TOKEN=" + token
                + " out-LLM_API_KEY=test-key"
                // The ~end sentinel cannot be the start of any secret (base64url, sk-,
                // test-key), so the documented tail trim cannot eat into the path.
                + " out-XDG_CONFIG_HOME=" + xdg + " ~end\n").getBytes(StandardCharsets.UTF_8);
        try {
            coreLog.startReaderForTest("stdout", new ByteArrayInputStream(line));
        } finally {
            coreLog.close();
        }

        String content = Files.readString(coreLog.path());
        assertThat(content)
                .as("the credential-bundle value and the run bearer are not evidence")
                .doesNotContain(providerAuth)
                .doesNotContain(token)
                .contains("out-DEEPSEEK_ACCESS=[redacted]")
                .contains("out-ARIA_MCP_TOKEN=[redacted]");
        assertThat(content)
                .as("the provider key is credential-shaped and masked by name")
                .contains("out-LLM_API_KEY=[redacted]");
        assertThat(content)
                .as("the launcher's own XDG root is evidence and must survive verbatim")
                .contains("out-XDG_CONFIG_HOME=" + xdg);
    }

    @Test
    void aStreamBeyondTheCapLeavesABoundedFileAndATruncationMarker() throws Exception {
        Path working = workingDirectory();
        long cap = 2_048;
        RunCoreLog coreLog = RunCoreLog.open(working.resolve("capped.log"), "capped-" + RUN,
                Map.of(), Set.of(), cap);

        Process process = OwnedProcessController.launch(chattyProfile(working), coreLog);
        try {
            assertThat(process.waitFor(20, TimeUnit.SECONDS))
                    .as("discarding past the cap must not stop the drain")
                    .isTrue();
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
                process.waitFor(10, TimeUnit.SECONDS);
            }
            coreLog.close();
        }

        String content = Files.readString(coreLog.path());
        assertThat(content).contains("pipe-filler-");
        assertThat(content).contains("capped at " + cap + " bytes");
        assertThat(Files.size(coreLog.path()))
                .as("a chatty core must not be able to fill the disk")
                .isLessThanOrEqualTo(cap + 512);
    }

    /**
     * Finding 4: the cap is shared by both streams, so the marker must say so,
     * and it must be unmistakably ours. A core-authored line always carries an
     * {@code [out] }/{@code [err] } tag, so a line beginning with {@code [aria] }
     * at column 0 cannot have come from the core. One marker only, even after
     * stderr writes into the already-crossed cap.
     */
    @Test
    void theCapMarkerIsOneUnambiguousMarkerNamingBothStreams() throws Exception {
        Path working = workingDirectory();
        long cap = 2_048;
        RunCoreLog coreLog = RunCoreLog.open(working.resolve("shared-cap.log"), "shared-" + RUN,
                Map.of(), Set.of(), cap);

        Process process = OwnedProcessController.launch(profile(working, Map.of(), "cap-cross"), coreLog);
        try {
            assertThat(process.waitFor(20, TimeUnit.SECONDS))
                    .as("discarding past the cap must not stop the drain")
                    .isTrue();
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
                process.waitFor(10, TimeUnit.SECONDS);
            }
            coreLog.close();
        }

        String content = Files.readString(coreLog.path());
        List<String> markers = content.lines()
                .filter(line -> line.startsWith("[aria] "))
                .filter(line -> line.contains("capped at"))
                .toList();
        assertThat(content).contains("[out] stdout-before-cap-0");
        assertThat(markers)
                .as("one marker for the shared cap, wearing the prefix the core cannot author")
                .hasSize(1);
        assertThat(markers.get(0))
                .as("the cap is shared, so the marker must name both streams")
                .contains("capped at " + cap + " bytes")
                .contains("stdout")
                .contains("stderr");
        assertThat(content)
                .as("stderr output past the already-crossed cap is discarded, not marked again")
                .doesNotContain("stderr-after-cap-");
    }

    /**
     * Finding 6: the stdio mirror drops bytes when nobody reads it. Silence there
     * is a truncated JSON-RPC stream; the drop is reported once, by the stream
     * that lost the bytes, instead of vanishing.
     */
    @Test
    void aDroppedStdioMirrorChunkIsReportedOnceInsteadOfVanishing() throws Exception {
        Path working = workingDirectory();
        Path runRoot = workingDirectory();
        RunCoreLog coreLog = RunCoreLog.open(runRoot, RUN, Map.of(), Set.of());

        // Nothing reads the process handle's streams: the mirror's backlog overflows.
        Process process = OwnedProcessController.launch(profile(working, Map.of(), "mirror-overflow"), coreLog);
        try {
            assertThat(process.waitFor(20, TimeUnit.SECONDS)).isTrue();
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
                process.waitFor(10, TimeUnit.SECONDS);
            }
            coreLog.close();
        }

        String content = Files.readString(coreLog.path());
        List<String> notices = content.lines()
                .filter(line -> line.startsWith("[aria] "))
                .filter(line -> line.contains("mirror"))
                .toList();
        assertThat(content).contains("[out] mirror-filler-");
        assertThat(notices)
                .as("a dropped stdio mirror chunk must be reported once, not silently truncated")
                .hasSize(1);
        assertThat(notices.get(0)).contains("stdout").contains("dropped");
    }

    /**
     * The log lives below the run's retained runtime root. The cleanup path the
     * coordinator calls at the end of a run deletes the run's workspace, not
     * that root -- asserted here by making the cleanup actually delete something.
     */
    @Test
    void theLogSurvivesTheWorkspaceCleanup() throws Exception {
        Path workspaceRoot = workingDirectory();
        Path runtimeRoot = workingDirectory();
        WorkspaceManager workspaceManager = new WorkspaceManager(workspaceRoot.toString());
        UUID runId = UUID.randomUUID();
        Path workspace = Path.of(workspaceManager.getOrProvision(runId));
        Files.writeString(workspace.resolve("agent-scratch.txt"), "work");

        Path working = workingDirectory();
        RunCoreLog coreLog = RunCoreLog.open(runtimeRoot.resolve(runId.toString()), runId, Map.of(),
                Set.of());
        Process process = OwnedProcessController.launch(oneLineProfile(working, "survives-cleanup"), coreLog);
        try {
            assertThat(process.waitFor(20, TimeUnit.SECONDS)).isTrue();
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
                process.waitFor(10, TimeUnit.SECONDS);
            }
            coreLog.close();
        }

        workspaceManager.cleanup(runId);

        assertThat(workspace)
                .as("the cleanup entry point must have actually run to make this test meaningful")
                .doesNotExist();
        assertThat(coreLog.path()).exists();
        assertThat(Files.readString(coreLog.path())).contains("survives-cleanup");
    }

    /**
     * The tail before a forced stop is usually the reason for the failure: the
     * log must have it on disk before the stop path returns.
     */
    @Test
    void theTailWrittenBeforeAForcedStopIsKept() throws Exception {
        Path working = workingDirectory();
        Path runRoot = workingDirectory();
        RunCoreLog coreLog = RunCoreLog.open(runRoot, RUN, Map.of(), Set.of());

        Process process = OwnedProcessController.launch(
                tailProfile(working, "fatal: the core refused the session"), coreLog);
        try {
            awaitContent(coreLog.path(), "fatal: the core refused the session");
            process.destroyForcibly();
            assertThat(process.waitFor(20, TimeUnit.SECONDS)).isTrue();
        } finally {
            coreLog.close();
        }

        assertThat(Files.readString(coreLog.path()))
                .contains("[err] fatal: the core refused the session");
    }

    // ------------------------------------------------------------------ fixtures

    private Path workingDirectory() throws IOException {
        Path directory = Files.createTempDirectory("run-core-log");
        directories.add(directory);
        return directory;
    }

    /** The same profile, declaring which of its values the launcher knows it injected. */
    private static LaunchProfile withSecrets(LaunchProfile profile, Set<String> secretValues) {
        return new LaunchProfile(profile.argv(), profile.env(), profile.workingDirectory(),
                profile.controlSecretFile(), secretValues);
    }

    /**
     * A synthetic core that writes far more than any OS pipe buffer: ~1.5 MB to
     * stdout and ~7 KB to stderr, synchronously, then exits. Nothing about it
     * needs a real credential, and nothing about it needs {@code node}.
     */
    private static LaunchProfile chattyProfile(Path working) {
        return profile(working, Map.of(), "chatty");
    }

    private static LaunchProfile orderedProfile(Path working) {
        return profile(working, Map.of(), "ordered");
    }

    private static LaunchProfile echoProfile(Path working, Map<String, String> environment,
            String stdoutName, String stderrName, String... moreStdoutNames) {
        List<String> args = new ArrayList<>(List.of("env", stdoutName, stderrName));
        args.addAll(List.of(moreStdoutNames));
        return profile(working, environment, args.toArray(String[]::new));
    }

    /** Writes one stderr line and then stays alive until the stop path kills it. */
    private static LaunchProfile tailProfile(Path working, String line) {
        return profile(working, Map.of(), "tail", line);
    }

    /** Writes one stderr line and exits. */
    private static LaunchProfile oneLineProfile(Path working, String line) {
        return profile(working, Map.of(), "oneline", line);
    }

    /**
     * The fixture is launched by this test JVM's own {@code java} on a classpath
     * that only has to hold {@link CoreLogFixture}: it deliberately does not
     * spawn {@code node}, because the hosted unit lane installs no node.
     */
    private static LaunchProfile profile(Path working, Map<String, String> environment, String... args) {
        List<String> argv = new ArrayList<>();
        argv.add(javaExecutable());
        argv.add("-cp");
        argv.add(fixtureClassPath());
        argv.add(CoreLogFixture.class.getName());
        argv.addAll(List.of(args));
        return new LaunchProfile(argv, environment, working.toString());
    }

    private static String javaExecutable() {
        boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        return Path.of(System.getProperty("java.home"), "bin", windows ? "java.exe" : "java").toString();
    }

    /**
     * The fixture needs nothing but its own compiled test classes, so the child
     * classpath is that directory alone -- independent of how the test runner
     * spelled this JVM's classpath.
     */
    private static String fixtureClassPath() {
        var source = CoreLogFixture.class.getProtectionDomain().getCodeSource();
        if (source != null && source.getLocation() != null) {
            try {
                return Path.of(source.getLocation().toURI()).toString();
            } catch (Exception ignored) {
                // fall through to the runner's own classpath
            }
        }
        return System.getProperty("java.class.path");
    }

    private static List<String> stdoutLines(List<String> lines) {
        return lines.stream().filter(line -> line.startsWith("[out] "))
                .map(line -> line.substring("[out] ".length())).toList();
    }

    private static List<String> stderrLines(List<String> lines) {
        return lines.stream().filter(line -> line.startsWith("[err] "))
                .map(line -> line.substring("[err] ".length())).toList();
    }

    /** The retained text of a marked line: everything before our marker and its separator. */
    private static String headOf(String markedLine) {
        int markerAt = markedLine.indexOf("[aria] line truncated");
        String head = markerAt < 0 ? markedLine : markedLine.substring(0, markerAt);
        return head.endsWith(" ") ? head.substring(0, head.length() - 1) : head;
    }

    private static byte[] filler(int count) {
        byte[] bytes = new byte[count];
        java.util.Arrays.fill(bytes, (byte) 'a');
        return bytes;
    }

    private static byte[] utf8(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] concat(byte[]... parts) {
        int length = 0;
        for (byte[] part : parts) {
            length += part.length;
        }
        byte[] joined = new byte[length];
        int at = 0;
        for (byte[] part : parts) {
            System.arraycopy(part, 0, joined, at, part.length);
            at += part.length;
        }
        return joined;
    }

    /**
     * The longest prefix of {@code secret} the content still holds, searching down
     * to one character. The probes use a secret whose first character never occurs
     * in the log's own text (the marker, paths, filler), so even a one-character
     * survivorship is evidence of a written credential rather than a collision --
     * the earlier six-character floor let a 1-5 character leak pass unproven.
     */
    private static String leakedPrefix(String content, String secret, int maxLength) {
        for (int length = Math.min(maxLength, secret.length()); length >= 1; length--) {
            String prefix = secret.substring(0, length);
            if (content.contains(prefix)) {
                return prefix;
            }
        }
        return null;
    }

    /**
     * A stream that hands its bytes out in prescribed read sizes, so a test can
     * put an exact byte at the drain's cut without an OS pipe deciding where the
     * read boundaries fall.
     */
    private static final class ChunkedStream extends java.io.InputStream {

        private final byte[] bytes;
        private final int[] chunks;
        private int offset;
        private int chunk;
        private int servedOfChunk;

        private ChunkedStream(byte[] bytes, int... chunks) {
            this.bytes = bytes;
            this.chunks = chunks;
        }

        @Override
        public void close() {
            // nothing to release: the bytes are in memory
        }

        @Override
        public int read() {
            byte[] single = new byte[1];
            int read = read(single, 0, 1);
            return read < 0 ? -1 : single[0] & 0xff;
        }

        @Override
        public int read(byte[] buffer, int bufferOffset, int length) {
            if (offset >= bytes.length) {
                return -1;
            }
            int allowed = chunk < chunks.length ? chunks[chunk] - servedOfChunk : length;
            int read = Math.min(Math.min(allowed, length), bytes.length - offset);
            System.arraycopy(bytes, offset, buffer, bufferOffset, read);
            offset += read;
            servedOfChunk += read;
            if (chunk < chunks.length && servedOfChunk == chunks[chunk]) {
                chunk++;
                servedOfChunk = 0;
            }
            return read;
        }
    }

    /** A process pipe stand-in that records whether the drain closed it. */
    private static final class CloseRecordingStream extends ByteArrayInputStream {

        private volatile boolean closed;

        private CloseRecordingStream(String content) {
            super(content.getBytes(StandardCharsets.UTF_8));
        }

        @Override
        public void close() throws IOException {
            closed = true;
            super.close();
        }

        private boolean wasClosed() {
            return closed;
        }
    }

    private static void awaitContent(Path file, String expected) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (System.nanoTime() < deadline) {
            try {
                if (Files.exists(file) && Files.readString(file).contains(expected)) {
                    return;
                }
            } catch (IOException ignored) {
                // the reader may be mid-write; retry inside the window
            }
            Thread.sleep(25);
        }
        throw new AssertionError("The core log never received: " + expected);
    }

    private static void deleteTree(Path root) {
        if (!Files.exists(root)) {
            return;
        }
        try (var walk = Files.walk(root)) {
            for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        } catch (IOException ignored) {
            // best-effort cleanup of a failed test
        }
    }
}
