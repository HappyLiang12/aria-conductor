package io.aria.conductor.execution.runtime;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * F21: a QA scratch contained a literal {@code %SystemDrive%} directory. Workspace
 * roots are the values an operator configures (and the values the start scripts
 * export), so a Windows {@code %VAR%} token there is expanded from the environment
 * or refused by name -- a token is never taken as a literal directory name, where
 * it silently creates a directory nobody manages or cleans up.
 */
class WorkspacePathsConfiguredRootTest {

    @Test
    void aPercentVariableTokenInAConfiguredRootIsExpanded() {
        Path root = WorkspacePaths.configuredRoot("%SystemDrive%/aria/workspaces/runs",
                "aria.workspaces.runtime-root", Map.of("SystemDrive", "D:"));

        assertThat(root).isEqualTo(Path.of("D:/aria/workspaces/runs").toAbsolutePath().normalize());
    }

    /** The ambient lookup is case-insensitive on Windows, so the token is too. */
    @Test
    void aPercentVariableTokenIsMatchedCaseInsensitively() {
        Path root = WorkspacePaths.configuredRoot("%systemdrive%/aria",
                "tools.file.workspace-dir", Map.of("SystemDrive", "D:"));

        assertThat(root).isEqualTo(Path.of("D:/aria").toAbsolutePath().normalize());
    }

    @Test
    void anUnknownPercentVariableIsRefusedByNameAndByPropertyInsteadOfBecomingADirectory() {
        assertThatThrownBy(() -> WorkspacePaths.configuredRoot("%NOT_A_REAL_VARIABLE%/runs",
                "aria.workspaces.runtime-root", Map.of("SystemDrive", "D:")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("NOT_A_REAL_VARIABLE")
                .hasMessageContaining("aria.workspaces.runtime-root");
    }

    @Test
    void aRootWithoutTokensIsUnchanged() {
        Path root = WorkspacePaths.configuredRoot("./data/workspaces",
                "tools.file.workspace-dir", Map.of());

        assertThat(root).isEqualTo(Path.of("./data/workspaces").toAbsolutePath().normalize());
    }

    @Test
    void aBlankConfiguredRootIsRefusedInsteadOfResolvingToTheWorkingDirectory() {
        assertThatThrownBy(() -> WorkspacePaths.configuredRoot("  ",
                "aria.workspaces.result-root", Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("aria.workspaces.result-root");
    }
}
