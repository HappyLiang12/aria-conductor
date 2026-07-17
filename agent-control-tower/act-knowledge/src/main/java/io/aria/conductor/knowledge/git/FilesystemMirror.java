package io.aria.conductor.knowledge.git;

import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevTree;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * Maintains a flat-file mirror of the latest committed contents of each
 * knowledge repo so the read path NEVER touches Git directly. The mirror is
 * refreshed in the background by {@link #sync(String)} and consumers read
 * via {@link #readFromSnapshot(String, String)}.
 */
@Service
public class FilesystemMirror {

    private static final Logger log = LoggerFactory.getLogger(FilesystemMirror.class);

    private String mirrorPath;
    private long staleThresholdSeconds;
    private final LocalGitClient gitClient;
    private final Map<String, Instant> lastSyncTimes = new ConcurrentHashMap<>();

    public FilesystemMirror(@Value("${knowledge.mirror.path:./data/knowledge-mirror}") String mirrorPath,
                            @Value("${knowledge.mirror.stale-threshold-seconds:120}") long staleThresholdSeconds,
                            LocalGitClient gitClient) {
        this.mirrorPath = mirrorPath;
        this.staleThresholdSeconds = staleThresholdSeconds;
        this.gitClient = gitClient;
    }

    public void setMirrorPath(String mirrorPath) {
        this.mirrorPath = mirrorPath;
    }

    public String getMirrorPath() {
        return mirrorPath;
    }

    public Path snapshotPath(String repoName) {
        return Paths.get(mirrorPath, repoName);
    }

    /**
     * Materialise the current state of {@code repoName}'s main branch into the
     * snapshot directory using an atomic rename so readers never see a partial
     * tree.
     */
    public synchronized void sync(String repoName) {
        Path snapshot = snapshotPath(repoName);
        Path working = Paths.get(mirrorPath, repoName + ".tmp-" + System.nanoTime());
        try {
            Files.createDirectories(working);
            writeMainTreeTo(repoName, working);
            atomicSwap(working, snapshot);
            lastSyncTimes.put(repoName, Instant.now());
            log.info("Synced mirror for {} to {}", repoName, snapshot);
        } catch (IOException e) {
            cleanupQuietly(working);
            throw new LocalGitClient.GitOperationException("Failed to sync mirror for " + repoName, e);
        }
    }

    /**
     * Read a file from the mirror directory. Returns {@code null} when the
     * file is absent. Never invokes Git.
     */
    public String readFromSnapshot(String repoName, String filePath) {
        Path file = snapshotPath(repoName).resolve(filePath);
        try {
            if (!Files.exists(file)) return null;
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new LocalGitClient.GitOperationException("Failed to read snapshot " + filePath, e);
        }
    }

    public MirrorStatus getStatus(String repoName) {
        Instant last = lastSyncTimes.get(repoName);
        if (last == null) {
            return new MirrorStatus(repoName, null, State.MISSING);
        }
        Duration age = Duration.between(last, Instant.now());
        State state = age.getSeconds() > staleThresholdSeconds ? State.STALE : State.FRESH;
        return new MirrorStatus(repoName, last, state);
    }

    /** Visible for tests: clear in-memory sync timestamps. */
    public void resetSyncTimes() {
        lastSyncTimes.clear();
    }

    /** Test-only knob for staleness threshold. */
    public void setStaleThresholdSeconds(long staleThresholdSeconds) {
        this.staleThresholdSeconds = staleThresholdSeconds;
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------
    private void writeMainTreeTo(String repoName, Path target) throws IOException {
        Path repoPath = gitClient.repoPath(repoName);
        try (Repository repo = new FileRepositoryBuilder()
                .setGitDir(repoPath.toFile())
                .setMustExist(true)
                .build();
             RevWalk walk = new RevWalk(repo)) {
            ObjectId head = repo.resolve("refs/heads/main");
            if (head == null) return;
            RevCommit commit = walk.parseCommit(head);
            RevTree tree = commit.getTree();
            Map<String, byte[]> files = new HashMap<>();
            try (TreeWalk tw = new TreeWalk(repo)) {
                tw.addTree(tree);
                tw.setRecursive(true);
                while (tw.next()) {
                    byte[] bytes = repo.open(tw.getObjectId(0)).getBytes();
                    files.put(tw.getPathString(), bytes);
                }
            }
            for (Map.Entry<String, byte[]> e : files.entrySet()) {
                Path out = target.resolve(e.getKey());
                Files.createDirectories(out.getParent() == null ? target : out.getParent());
                Files.write(out, e.getValue());
            }
        }
    }

    private void atomicSwap(Path working, Path snapshot) throws IOException {
        Path parent = snapshot.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        if (Files.exists(snapshot)) {
            Path backup = Paths.get(snapshot.toString() + ".bak-" + System.nanoTime());
            try {
                Files.move(snapshot, backup, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicFailed) {
                Files.move(snapshot, backup);
            }
            cleanupQuietly(backup);
        }
        try {
            Files.move(working, snapshot, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException atomicFailed) {
            Files.move(working, snapshot);
        }
    }

    private static void cleanupQuietly(Path path) {
        if (path == null || !Files.exists(path)) return;
        try (Stream<Path> walk = Files.walk(path)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try { Files.deleteIfExists(p); } catch (IOException ignored) { }
            });
        } catch (IOException ignored) {
        }
    }

    public enum State { FRESH, STALE, MISSING }

    public record MirrorStatus(String repoName, Instant lastSyncAt, State state) {
        public boolean isStale() { return state == State.STALE; }
        public boolean isFresh() { return state == State.FRESH; }
    }
}
