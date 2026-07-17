package io.aria.conductor.execution.adk;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class AdkProcessReaperTest {

    @TempDir Path tempDir;

    LangChainAdkProperties properties;
    AdkProcessReaper reaper;

    @BeforeEach
    void setUp() {
        properties = new LangChainAdkProperties();
        properties.setPidDir(tempDir.toString());
        reaper = new AdkProcessReaper(properties);
        reaper.init();
    }

    @AfterEach
    void cleanup() throws IOException {
        if (Files.isDirectory(tempDir)) {
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(tempDir)) {
                for (Path p : ds) {
                    Files.deleteIfExists(p);
                }
            }
        }
    }

    @Test
    void writePidFile_createsFileWithPidContents() throws IOException {
        String agentId = UUID.randomUUID().toString();

        reaper.writePidFile(agentId, 12345L);

        Path file = tempDir.resolve(agentId + ".pid");
        assertThat(Files.exists(file)).isTrue();
        assertThat(Files.readString(file).trim()).isEqualTo("12345");
    }

    @Test
    void readPidFile_returnsMinusOne_whenMissing() {
        long pid = reaper.readPidFile(UUID.randomUUID().toString());

        assertThat(pid).isEqualTo(-1L);
    }

    @Test
    void readPidFile_roundTripsWrittenPid() {
        String agentId = UUID.randomUUID().toString();
        reaper.writePidFile(agentId, 4242L);

        long pid = reaper.readPidFile(agentId);

        assertThat(pid).isEqualTo(4242L);
    }

    @Test
    void removePidFile_deletesTheFile() {
        String agentId = UUID.randomUUID().toString();
        reaper.writePidFile(agentId, 1L);

        reaper.removePidFile(agentId);

        assertThat(Files.exists(tempDir.resolve(agentId + ".pid"))).isFalse();
    }

    @Test
    void reapOrphans_cleansUpStaleFile_forNonExistentPid() throws IOException {
        // Pick a PID that is virtually guaranteed to not exist (very large).
        // Even if it does exist, the reaper would treat us as orphan and try to kill —
        // we register the agent as live to be safe.
        String agentId = "stale-" + UUID.randomUUID();
        Path file = tempDir.resolve(agentId + ".pid");
        Files.writeString(file, "999999999"); // not registered live, no such pid

        reaper.reapOrphans();

        assertThat(Files.exists(file)).isFalse();
    }

    @Test
    void reapOrphans_keepsFile_whenAgentIsLive_andProcessAlive() throws IOException {
        // Use the running JVM's own PID — guaranteed alive.
        long selfPid = ProcessHandle.current().pid();
        String agentId = "live-" + UUID.randomUUID();
        reaper.writePidFile(agentId, selfPid); // also registers as live

        reaper.reapOrphans();

        assertThat(Files.exists(tempDir.resolve(agentId + ".pid"))).isTrue();
        assertThat(ProcessHandle.current().isAlive()).isTrue();
    }

    @Test
    void reapOrphans_removesUnparseableFile() throws IOException {
        Path file = tempDir.resolve("garbage.pid");
        Files.writeString(file, "not-a-number");

        reaper.reapOrphans();

        assertThat(Files.exists(file)).isFalse();
    }

    @Test
    void init_createsPidDirectoryIfMissing() throws IOException {
        Path nested = tempDir.resolve("nested-pid-dir");
        properties.setPidDir(nested.toString());
        AdkProcessReaper fresh = new AdkProcessReaper(properties);

        fresh.init();

        assertThat(Files.isDirectory(nested)).isTrue();
    }

    @Test
    void reapOrphans_isNoOp_whenDirectoryDoesNotExist() {
        properties.setPidDir(tempDir.resolve("does-not-exist").toString());
        AdkProcessReaper fresh = new AdkProcessReaper(properties);

        // Should not throw.
        fresh.reapOrphans();
    }

    @SuppressWarnings("unused")
    private static long countPidFiles(Path dir) throws IOException {
        try (Stream<Path> s = Files.list(dir)) {
            return s.count();
        }
    }
}
