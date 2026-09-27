package io.aria.conductor.execution.runtime.host;

import io.aria.conductor.execution.runtime.LaunchProfile;
import io.aria.conductor.execution.runtime.StopProof;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real POSIX coverage of the descendant-spawning window (Task 9 review C1):
 * a root that spawns detached writer descendants in a tight loop while the stop
 * sweep is in flight. Under the buggy rule the sweep enumerated the tree once
 * per round, killed the root last, and a descendant spawned after the
 * enumeration was orphaned and never re-enumerated -- while the proof still
 * claimed all writers stopped. The rule under test is the honest one: either
 * {@code allWritersStopped} is false, or every descendant the run recorded must
 * be verified dead.
 *
 * <p>This class is Linux-only by construction: it drives the production
 * {@code /bin/sh} signal path with real SIGSTOP/SIGKILL. The Windows workstation
 * cannot execute it; the exact CI command is recorded in the Task 9 report.
 */
@EnabledOnOs(OS.LINUX)
@Timeout(300)
class PosixProcessControllerIntegrationTest {

    private final List<AutoCloseable> cleanups = new ArrayList<>();

    @AfterEach
    void tearDown() {
        for (AutoCloseable cleanup : cleanups) {
            try {
                cleanup.close();
            } catch (Exception ignored) {
                // best-effort cleanup of a failed test
            }
        }
        cleanups.clear();
    }

    @Test
    void stopVerifiesEveryDescendantSpawnedWhileTheSweepIsInFlight() throws Exception {
        Path root = Files.createTempDirectory("host-posix-window").toRealPath();
        cleanups.add(() -> deleteTree(root));
        Path workspace = Files.createDirectories(root.resolve("workspace"));
        Path registry = workspace.resolve("spawned.pids");
        Path ticks = Files.createDirectories(workspace.resolve("ticks"));
        Path writer = workspace.resolve("writer.mjs");
        Files.writeString(writer, """
                import { appendFileSync } from 'node:fs';
                appendFileSync(process.argv[2], process.pid + "\\n");
                setInterval(() => appendFileSync(process.argv[3], "tick\\n"), 10);
                """);
        Path spawner = workspace.resolve("spawner.mjs");
        // The root spawns a new detached writer every 20 ms for 15 s and then keeps
        // running idle: some spawns are guaranteed to land after the enumeration of
        // the round whose sweep kills the root, while the finite burst lets the sweep
        // converge on a loaded CI runner (an unbounded 2 ms spawner outran the
        // enumeration there and the test timed out instead of proving anything).
        Files.writeString(spawner, """
                import { spawn } from 'node:child_process';
                const registry = process.argv[2];
                const ticks = process.argv[3];
                const writer = process.argv[4];
                let index = 0;
                const burst = setInterval(() => {
                  if (index >= 750) { clearInterval(burst); return; }
                  const name = ticks + "/" + process.pid + "-" + index + ".log";
                  spawn(process.execPath, [writer, registry, name], { stdio: 'ignore', detached: true });
                  index += 1;
                }, 20);
                setInterval(() => {}, 1000);
                """);

        PosixProcessController controller = new PosixProcessController(Path.of("/bin/sh"));
        OwnedProcess owned = controller.start(UUID.randomUUID(), new LaunchProfile(List.of(nodeExecutable(),
                spawner.toString(), registry.toString(), ticks.toString(), writer.toString()), Map.of(),
                workspace.toString()));
        cleanups.add(() -> {
            owned.liveProcess().destroyForcibly();
            controller.release(owned);
            controller.close();
        });
        Instant waitUntil = Instant.now().plusSeconds(20);
        while (!Files.exists(registry) && Instant.now().isBefore(waitUntil)) {
            Thread.sleep(50);
        }
        Thread.sleep(200);

        StopProof proof = controller.stop(owned, Instant.now().plusSeconds(60));

        List<Long> recorded = Files.exists(registry)
                ? Files.readAllLines(registry).stream().filter(line -> !line.isBlank()).map(Long::parseLong).toList()
                : List.of();
        for (long pid : recorded) {
            cleanups.add(() -> ProcessHandle.of(pid).ifPresent(ProcessHandle::destroyForcibly));
        }
        assertThat(recorded).as("the fixture must have spawned real descendants").isNotEmpty();

        if (proof.allWritersStopped()) {
            Thread.sleep(300);
            List<Long> survivors = recorded.stream()
                    .filter(pid -> ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false))
                    .toList();
            assertThat(survivors)
                    .as("a stop proof must never be returned while a spawned descendant is still alive")
                    .isEmpty();
        }
    }

    private static String nodeExecutable() {
        String configured = System.getenv("NODE_BIN");
        return configured != null && !configured.isBlank() ? configured : "node";
    }

    private static void deleteTree(Path root) throws Exception {
        if (!Files.exists(root)) {
            return;
        }
        try (var stream = Files.walk(root)) {
            for (Path path : stream.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }
}
