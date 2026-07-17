package io.aria.conductor.execution.adk;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.HashSet;
import java.util.Set;

/**
 * Tracks ADK subprocess PIDs on disk so orphans (left over after a JVM crash)
 * can be discovered and killed on the next boot or every 10s.
 *
 * <p>Each running subprocess writes one file: {@code <pid-dir>/<agentId>.pid}
 * containing its OS-level PID. The reaper scans the directory, calls
 * {@link ProcessHandle#of(long)} for each PID, and destroys handles whose
 * matching agent is no longer registered with {@link LangChainAdkProvider}.
 */
@Slf4j
@Component
public class AdkProcessReaper {

    /** Default location — overridden via {@link LangChainAdkProperties#getPidDir()}. */
    public static final String DEFAULT_PID_DIR = "./data/adk-pids";

    private final LangChainAdkProperties properties;
    private final Set<String> liveAgentIds = new HashSet<>();

    public AdkProcessReaper(LangChainAdkProperties properties) {
        this.properties = properties;
    }

    @PostConstruct
    public void init() {
        try {
            Files.createDirectories(pidDir());
        } catch (IOException e) {
            log.warn("Could not create PID dir {}: {}", pidDir(), e.getMessage());
        }
    }

    /**
     * Mark an agent as live so the reaper does NOT kill its PID file. Should be
     * called by {@link LangChainAdkProvider} on every successful start.
     */
    public synchronized void registerLiveAgent(String agentId) {
        liveAgentIds.add(agentId);
    }

    public synchronized void unregisterAgent(String agentId) {
        liveAgentIds.remove(agentId);
    }

    /**
     * Persist {@code pid} for {@code agentId} so a crashed JVM can find it later.
     */
    public void writePidFile(String agentId, long pid) {
        Path file = pidDir().resolve(agentId + ".pid");
        try {
            Files.createDirectories(pidDir());
            Files.writeString(file, Long.toString(pid),
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE);
            registerLiveAgent(agentId);
            log.debug("Wrote PID file {} (pid={})", file, pid);
        } catch (IOException e) {
            log.warn("Could not write PID file {}: {}", file, e.getMessage());
        }
    }

    public void removePidFile(String agentId) {
        Path file = pidDir().resolve(agentId + ".pid");
        try {
            Files.deleteIfExists(file);
            unregisterAgent(agentId);
            log.debug("Removed PID file {}", file);
        } catch (IOException e) {
            log.warn("Could not remove PID file {}: {}", file, e.getMessage());
        }
    }

    /**
     * Read a previously persisted PID. Returns -1 if the file is missing or
     * unparseable.
     */
    public long readPidFile(String agentId) {
        Path file = pidDir().resolve(agentId + ".pid");
        if (!Files.exists(file)) {
            return -1L;
        }
        try {
            return Long.parseLong(Files.readString(file).trim());
        } catch (Exception e) {
            log.warn("Could not read PID file {}: {}", file, e.getMessage());
            return -1L;
        }
    }

    /**
     * Scan the PID dir every 10s. For each file:
     * <ul>
     *   <li>If the agent is registered live, leave it alone.
     *   <li>If the OS process is alive but the agent is unknown, kill it
     *       (it was orphaned by a previous JVM run).
     *   <li>If the OS process is dead, remove the stale file.
     * </ul>
     */
    @Scheduled(fixedRate = 10_000L)
    public void reapOrphans() {
        Path dir = pidDir();
        if (!Files.isDirectory(dir)) {
            return;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*.pid")) {
            for (Path file : stream) {
                handlePidFile(file);
            }
        } catch (IOException e) {
            log.warn("Failed scanning PID dir {}: {}", dir, e.getMessage());
        }
    }

    private void handlePidFile(Path file) {
        String agentId = stripExtension(file.getFileName().toString());
        long pid;
        try {
            pid = Long.parseLong(Files.readString(file).trim());
        } catch (Exception e) {
            log.warn("Removing unparseable PID file {}", file);
            silentDelete(file);
            return;
        }

        boolean live;
        synchronized (this) {
            live = liveAgentIds.contains(agentId);
        }

        ProcessHandle.of(pid).ifPresentOrElse(handle -> {
            if (!handle.isAlive()) {
                log.info("PID {} for agent {} is dead — cleaning up file", pid, agentId);
                silentDelete(file);
                return;
            }
            if (!live) {
                log.warn("Found orphan ADK process pid={} agent={} — destroying", pid, agentId);
                handle.destroy();
                if (!waitForExit(handle, 2_000L)) {
                    log.warn("Orphan pid={} did not exit, forcing", pid);
                    handle.destroyForcibly();
                }
                silentDelete(file);
            }
        }, () -> {
            // No process with this PID exists — file is stale.
            log.debug("PID {} not found on system, removing stale file {}", pid, file);
            silentDelete(file);
        });
    }

    private boolean waitForExit(ProcessHandle handle, long timeoutMs) {
        try {
            return handle.onExit().orTimeout(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
                    .handle((h, ex) -> ex == null)
                    .get();
        } catch (Exception e) {
            return !handle.isAlive();
        }
    }

    private void silentDelete(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException ignored) {
            // best effort
        }
    }

    private static String stripExtension(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? name : name.substring(0, dot);
    }

    Path pidDir() {
        String dir = properties != null && properties.getPidDir() != null
                ? properties.getPidDir() : DEFAULT_PID_DIR;
        return Paths.get(dir);
    }
}
