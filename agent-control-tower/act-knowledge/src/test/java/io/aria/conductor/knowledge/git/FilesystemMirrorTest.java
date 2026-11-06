package io.aria.conductor.knowledge.git;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class FilesystemMirrorTest {

    @TempDir
    Path tmp;

    LocalGitClient git;
    FilesystemMirror mirror;

    @BeforeEach
    void setUp() {
        Path repos = tmp.resolve("repos");
        Path mirrorDir = tmp.resolve("mirror");
        git = new LocalGitClient(repos.toString());
        mirror = new FilesystemMirror(mirrorDir.toString(), 120L, git);
    }

    @Test
    void sync_thenReadFromSnapshotReturnsContent() {
        git.initRepo("skills");
        String b = git.createBranch("skills", "compose");
        git.commit("skills", b, "compose/v1.md", "compose body", "init");
        git.mergeBranch("skills", b);

        mirror.sync("skills");

        assertThat(mirror.readFromSnapshot("skills", "compose/v1.md")).isEqualTo("compose body");
    }

    @Test
    void readFromSnapshot_missingFile_returnsNull() {
        git.initRepo("skills");
        mirror.sync("skills");

        assertThat(mirror.readFromSnapshot("skills", "no/such.md")).isNull();
    }

    @Test
    void getStatus_freshAfterSync() {
        git.initRepo("scripts");
        mirror.sync("scripts");

        FilesystemMirror.MirrorStatus s = mirror.getStatus("scripts");

        assertThat(s.state()).isEqualTo(FilesystemMirror.State.FRESH);
        assertThat(s.lastSyncAt()).isNotNull();
        assertThat(s.isFresh()).isTrue();
    }

    @Test
    void getStatus_missingWhenNeverSynced() {
        FilesystemMirror.MirrorStatus s = mirror.getStatus("nope");
        assertThat(s.state()).isEqualTo(FilesystemMirror.State.MISSING);
        assertThat(s.lastSyncAt()).isNull();
    }

    @Test
    void getStatus_staleAfterThresholdElapses() {
        git.initRepo("prompts");
        mirror.sync("prompts");
        // Force the threshold so the just-synced repo is already stale
        mirror.setStaleThresholdSeconds(0L);
        // Wait a tick to ensure age > 0s
        try { Thread.sleep(1100); } catch (InterruptedException ignored) { }

        FilesystemMirror.MirrorStatus s = mirror.getStatus("prompts");

        assertThat(s.state()).isEqualTo(FilesystemMirror.State.STALE);
        assertThat(s.isStale()).isTrue();
    }

    @Test
    void sync_overwritesPreviousSnapshotAtomically() {
        git.initRepo("tools");
        String b1 = git.createBranch("tools", "calc");
        git.commit("tools", b1, "calc/v1.md", "v1", "init");
        git.mergeBranch("tools", b1);
        mirror.sync("tools");
        assertThat(mirror.readFromSnapshot("tools", "calc/v1.md")).isEqualTo("v1");

        String b2 = git.createBranch("tools", "calc");
        git.commit("tools", b2, "calc/v1.md", "v2", "update");
        git.mergeBranch("tools", b2);
        mirror.sync("tools");

        assertThat(mirror.readFromSnapshot("tools", "calc/v1.md")).isEqualTo("v2");
    }

    @Test
    void readFromSnapshot_doesNotInvokeGit() {
        // Sync once, then move/destroy the bare repo; reads should still work
        git.initRepo("templates");
        String b = git.createBranch("templates", "letter");
        git.commit("templates", b, "letter/v1.md", "Dear", "init");
        git.mergeBranch("templates", b);
        mirror.sync("templates");

        // Point Git base path elsewhere — reads use the materialised snapshot
        git.setBasePath(tmp.resolve("nonexistent").toString());

        assertThat(mirror.readFromSnapshot("templates", "letter/v1.md")).isEqualTo("Dear");
    }

    @Test
    void getStatus_returnsRecentTimestamp() {
        git.initRepo("skills");
        Instant before = Instant.now().minusSeconds(1);

        mirror.sync("skills");

        Instant after = Instant.now().plusSeconds(1);
        FilesystemMirror.MirrorStatus s = mirror.getStatus("skills");
        assertThat(s.lastSyncAt()).isAfter(before).isBefore(after);
    }
}
