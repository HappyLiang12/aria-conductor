package io.aria.conductor.aria.live;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.socket.WebSocketSession;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * The factory decides availability before a child is ever spawned: disabled live path,
 * unusable credentials and a missing script are one answer, and a fully configured path
 * resolves the repo-relative script and starts the child.
 */
class QwenLiveBridgeFactoryTest {

    private static final Path FAKE_BRIDGE =
            Path.of("src/test/resources/live/fake-bridge.mjs").toAbsolutePath();

    @TempDir
    Path dir;

    @Test
    void aFullyConfiguredPathStartsTheChild() {
        LiveProperties properties = new LiveProperties(true, FAKE_BRIDGE.toString(),
                credentials().toString(), 0, 0);
        QwenLiveBridgeFactory factory = new QwenLiveBridgeFactory(
                properties, new LiveCredentials(properties), new ObjectMapper());

        LiveBridge bridge = factory.open(session(), LiveBridgeListener.NONE);

        try {
            assertThat(bridge).isInstanceOf(QwenLiveBridgeProcess.class);
        } finally {
            bridge.close();
        }
    }

    @Test
    void aMissingScriptIsUnavailable() {
        LiveProperties properties = new LiveProperties(true, dir.resolve("no-such-bridge.js").toString(),
                credentials().toString(), 0, 0);
        QwenLiveBridgeFactory factory = new QwenLiveBridgeFactory(
                properties, new LiveCredentials(properties), new ObjectMapper());

        assertThatThrownBy(() -> factory.open(session(), LiveBridgeListener.NONE))
                .isInstanceOf(LiveBridgeUnavailableException.class);
    }

    @Test
    void anAbsoluteScriptIsTakenAsGiven() {
        assertThat(QwenLiveBridgeFactory.resolve(FAKE_BRIDGE.toString(), dir)).isEqualTo(FAKE_BRIDGE);
        assertThat(QwenLiveBridgeFactory.resolve(dir.resolve("absent.js").toString(), dir)).isNull();
    }

    @Test
    void aRelativeScriptIsSearchedUpwardsFromTheWorkingDirectory() {
        // The default path is repo-relative while the backend runs from a module directory.
        Path nested = dir.resolve("agent-control-tower/act-app");
        Path script = dir.resolve("packages/qwen-live-bridge/dist/index.js");
        try {
            Files.createDirectories(nested);
            Files.createDirectories(script.getParent());
            Files.writeString(script, "// bridge");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }

        assertThat(QwenLiveBridgeFactory.resolve("packages/qwen-live-bridge/dist/index.js", nested))
                .isEqualTo(script);
        assertThat(QwenLiveBridgeFactory.resolve("packages/absent/index.js", nested)).isNull();
    }

    private Path credentials() {
        try {
            Path file = Files.createTempFile(dir, "qwen-live", ".env");
            Files.writeString(file, "DASHSCOPE_API_KEY=sk-live-factory-test\nDASHSCOPE_WORKSPACE=ws\n");
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
            return file;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static LiveSession session() {
        return new LiveSession("session", "conv", mock(WebSocketSession.class), null);
    }
}
