package io.aria.conductor.aria.live;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Spec L2: the Qwen credentials come from a 0600 env file, only two keys are read, and a
 * file any other local user could read is refused (the refusal says which path and why,
 * never a value).
 */
class LiveCredentialsTest {

    @TempDir
    Path dir;

    @Test
    void aPrivateFileYieldsBothKeys() throws Exception {
        Path file = credentials("""
                # qwen live credentials
                DASHSCOPE_API_KEY=sk-live-0123456789
                DASHSCOPE_WORKSPACE=ws-singapore-1
                """);

        Optional<Map<String, String>> loaded = LiveCredentials.load(file);

        assertThat(loaded).isPresent();
        assertThat(loaded.get())
                .containsEntry("DASHSCOPE_API_KEY", "sk-live-0123456789")
                .containsEntry("DASHSCOPE_WORKSPACE", "ws-singapore-1")
                .hasSize(2);
    }

    @Test
    void quotedValuesAreUnquotedAndOtherKeysAreIgnored() throws Exception {
        Path file = credentials("""
                DASHSCOPE_API_KEY="sk-live-quoted"
                DASHSCOPE_WORKSPACE='ws-quoted'
                UNRELATED=1
                """);

        Map<String, String> loaded = LiveCredentials.load(file).orElseThrow();

        assertThat(loaded).containsEntry("DASHSCOPE_API_KEY", "sk-live-quoted")
                .containsEntry("DASHSCOPE_WORKSPACE", "ws-quoted")
                .hasSize(2);
    }

    @Test
    void aFileAnyOtherUserCouldReadIsRefused() throws Exception {
        Path file = credentials("DASHSCOPE_API_KEY=sk-live-group-readable\nDASHSCOPE_WORKSPACE=ws\n");
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-r--r--"));

        assertThat(LiveCredentials.load(file)).isEmpty();

        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-rw----"));
        assertThat(LiveCredentials.load(file)).isEmpty();
    }

    @Test
    void aMissingFileIsUnavailable() {
        assertThat(LiveCredentials.load(dir.resolve("absent.env"))).isEmpty();
    }

    @Test
    void aMissingKeyIsUnavailable() throws Exception {
        Path file = credentials("DASHSCOPE_API_KEY=sk-live-only\n");

        assertThat(LiveCredentials.load(file)).isEmpty();
    }

    @Test
    void blankValuesAreTreatedAsMissing() throws Exception {
        Path file = credentials("DASHSCOPE_API_KEY=\nDASHSCOPE_WORKSPACE=ws\n");

        assertThat(LiveCredentials.load(file)).isEmpty();
    }

    @Test
    void theConfiguredPathIsExpandedFromTheHomeDirectory() {
        Path home = Path.of(System.getProperty("user.home"));
        LiveProperties properties = new LiveProperties(true, null, "~/.aria-conductor/qwen-live.env", 0, 0);

        assertThat(LiveCredentials.expand(properties.credentialsFile(), home))
                .isEqualTo(home.resolve(".aria-conductor/qwen-live.env"));
        assertThat(LiveCredentials.expand("/absolute/live.env", home))
                .isEqualTo(Path.of("/absolute/live.env"));
    }

    private Path credentials(String body) throws Exception {
        Path file = Files.createTempFile(dir, "qwen-live", ".env");
        Files.writeString(file, body);
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
        return file;
    }
}
