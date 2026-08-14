package io.aria.conductor.execution.adk;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R-F3: the configured {@code server-script} path is relative and must resolve against the
 * repo root (a dir containing BOTH {@code agent-control-tower} and {@code langchain-adk}),
 * not the JVM working directory. These tests exercise {@code resolveServerScript} directly
 * with an injected working directory.
 */
class LangChainAdkServerScriptResolverTest {

    @TempDir
    Path tempRoot;

    @Test
    void relativePath_resolvesAgainstRepoRoot() throws Exception {
        // Build a fake repo root containing both marker dirs.
        Path repoRoot = tempRoot.resolve("repo");
        Files.createDirectories(repoRoot.resolve("agent-control-tower"));
        Files.createDirectories(repoRoot.resolve("langchain-adk"));
        // Simulate a CWD nested anywhere under the repo.
        Path cwd = repoRoot.resolve("agent-control-tower").resolve("act-app");
        Files.createDirectories(cwd);

        String resolved = LangChainAdkProvider.resolveServerScript("../langchain-adk/src/server.py", cwd);

        assertThat(Path.of(resolved).isAbsolute()).isTrue();
        assertThat(Path.of(resolved).normalize())
                .isEqualTo(repoRoot.resolve("langchain-adk/src/server.py").normalize());
    }

    @Test
    void absolutePath_isPassthrough() {
        String abs = tempRoot.resolve("some/absolute/server.py").toAbsolutePath().toString();

        String resolved = LangChainAdkProvider.resolveServerScript(abs, tempRoot);

        assertThat(resolved).isEqualTo(abs);
    }

    @Test
    void repoRootNotFound_fallsBackToUserDirResolution() throws Exception {
        Path cwd = tempRoot.resolve("nowhere");
        Files.createDirectories(cwd);

        String resolved = LangChainAdkProvider.resolveServerScript("scripts/server.py", cwd);

        assertThat(Path.of(resolved).isAbsolute()).isTrue();
        assertThat(Path.of(resolved).normalize())
                .isEqualTo(cwd.toAbsolutePath().normalize().resolve("scripts/server.py").normalize());
    }
}
