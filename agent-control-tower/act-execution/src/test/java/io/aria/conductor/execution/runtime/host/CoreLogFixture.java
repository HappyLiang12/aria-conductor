package io.aria.conductor.execution.runtime.host;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * A tiny stand-in for a run-owned core, launched by the <em>test JVM's own</em>
 * {@code java} ({@link System#getProperty(String) java.home}) instead of
 * {@code node}. The hosted {@code Java Unit Tests} CI job has no
 * {@code setup-node} step, so anything this suite spawned through {@code node}
 * would fail the release lane on a machine that is otherwise complete.
 *
 * <p>Every write is synchronous and flushed before exit, so a mode that writes
 * more than an OS pipe buffer (4-64 KB) blocks in {@code write()} exactly like a
 * real chatty core unless a drain is consuming the pipes. That is the property
 * the deadlock test needs from it.
 */
public final class CoreLogFixture {

    private CoreLogFixture() {
    }

    public static void main(String[] args) throws Exception {
        OutputStream out = new FileOutputStream(FileDescriptor.out);
        OutputStream err = new FileOutputStream(FileDescriptor.err);
        String mode = args.length == 0 ? "normal" : args[0];
        switch (mode) {
            case "chatty" -> {
                byte[] line = utf8("pipe-filler-".repeat(64) + "\n");
                for (int i = 0; i < 2048; i++) {
                    out.write(line);
                }
                byte[] stderrLine = utf8("stderr-filler\n");
                for (int i = 0; i < 512; i++) {
                    err.write(stderrLine);
                }
            }
            case "ordered" -> {
                for (int i = 0; i < 3; i++) {
                    writeLine(out, "out-" + i);
                    writeLine(err, "err-" + i);
                }
                writeLine(out, "stdout-done");
            }
            case "env" -> {
                // args[1] is echoed on stdout, args[2] on stderr, any further name
                // on stdout, so a test can see which side each value lands on.
                for (int i = 1; i < args.length; i++) {
                    boolean onStderr = i == 2;
                    writeLine(onStderr ? err : out,
                            (onStderr ? "err-" : "out-") + args[i] + "=" + System.getenv(args[i]));
                }
                writeLine(out, "visible-line");
            }
            case "oneline" -> writeLine(err, args[1]);
            case "tail" -> {
                writeLine(err, args[1]);
                Thread.sleep(Long.MAX_VALUE);
            }
            case "huge-line" -> {
                byte[] huge = new byte[1_500_000];
                java.util.Arrays.fill(huge, (byte) 'x');
                out.write(huge);
                out.write('\n');
            }
            case "partial" -> {
                // No newline at all, and more bytes than the line bound: the head
                // must still reach the file, cut and marked, when the stream ends.
                out.write(utf8(args[1]));
                byte[] tail = new byte[100_000];
                java.util.Arrays.fill(tail, (byte) 'y');
                out.write(tail);
            }
            case "normal" -> {
                writeLine(out, "alpha");
                writeLine(out, "beta");
                writeLine(err, "gamma");
            }
            case "cap-cross" -> {
                // Enough stdout to cross a small cap, a pause so the stdout reader
                // certainly reached it first, then stderr into the same shared cap.
                for (int i = 0; i < 128; i++) {
                    writeLine(out, "stdout-before-cap-" + i);
                }
                out.flush();
                Thread.sleep(500);
                for (int i = 0; i < 64; i++) {
                    writeLine(err, "stderr-after-cap-" + i);
                }
            }
            case "mirror-overflow" -> {
                // More than the mirror's backlog, with nobody reading the mirror.
                byte[] line = utf8("mirror-filler-".repeat(48) + "\n");
                for (int i = 0; i < 2048; i++) {
                    out.write(line);
                }
            }
            case "cut-multibyte" -> {
                // A 3-byte character straddling the line bound: with args[1] =
                // bound - 1 its first byte is the last retained one, so a cut on
                // a raw byte boundary decodes to U+FFFD instead of dropping it.
                out.write(filler(Integer.parseInt(args[1])));
                out.write(utf8("\u20ac"));
                writeLine(out, "");
                writeLine(out, "after-the-cut");
            }
            case "chatty-undrained" -> {
                // The anti-vacuity fixture: it says it reached main() before the
                // big synchronous write, so a control test can time its assertion
                // from a known point instead of from JVM startup.
                writeLine(out, "fixture-started");
                out.flush();
                byte[] line = utf8("pipe-filler-".repeat(64) + "\n");
                for (int i = 0; i < 2048; i++) {
                    out.write(line);
                }
            }
            default -> throw new IllegalArgumentException("unknown fixture mode: " + mode);
        }
        out.flush();
        err.flush();
    }

    private static void writeLine(OutputStream stream, String line) throws Exception {
        stream.write(utf8(line + "\n"));
    }

    private static byte[] filler(int count) {
        byte[] bytes = new byte[count];
        java.util.Arrays.fill(bytes, (byte) 'a');
        return bytes;
    }

    private static byte[] utf8(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
