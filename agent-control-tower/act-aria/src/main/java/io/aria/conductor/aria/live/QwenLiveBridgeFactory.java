package io.aria.conductor.aria.live;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * The production {@link LiveBridgeFactory}: one {@link QwenLiveBridgeProcess} per socket,
 * started only when the live path is enabled and the credentials file is usable (spec L2,
 * §"Deployment").
 *
 * <p>Everything that can make the bridge unavailable is decided here and answered with
 * {@link LiveBridgeUnavailableException}, so the handler has one rule: any failure to open
 * becomes {@code bridge_internal} + 1011 after {@code ready}. A relative bridge script is
 * resolved from the working directory upwards, because the backend runs from the module
 * directory while the script's default path is repo-relative.
 */
@Component
public class QwenLiveBridgeFactory implements LiveBridgeFactory {

    private static final Logger log = LoggerFactory.getLogger(QwenLiveBridgeFactory.class);
    private static final int MAX_PARENT_LOOKUPS = 8;

    private final LiveProperties properties;
    private final LiveCredentials credentials;
    private final ObjectMapper json;

    public QwenLiveBridgeFactory(LiveProperties properties, LiveCredentials credentials, ObjectMapper json) {
        this.properties = properties;
        this.credentials = credentials;
        this.json = json;
    }

    @Override
    public LiveBridge open(LiveSession session, LiveBridgeListener listener) {
        if (!properties.enabled()) {
            throw new LiveBridgeUnavailableException("live bridge disabled");
        }
        Map<String, String> environment = credentials.load()
                .orElseThrow(() -> new LiveBridgeUnavailableException("qwen credentials unavailable"));
        Path script = resolve(properties.bridgeScript(), Path.of("").toAbsolutePath());
        if (script == null) {
            log.warn("qwen live bridge script missing: path={}", properties.bridgeScript());
            throw new LiveBridgeUnavailableException("bridge script missing");
        }
        return QwenLiveBridgeProcess.spawn(session.id(), script, environment,
                properties.realtimeUrl(), json, listener, null);
    }

    /** @return the script if it exists, else null; an absolute path is taken as given */
    static Path resolve(String configured, Path start) {
        Path given = Path.of(configured);
        if (given.isAbsolute()) {
            return Files.isRegularFile(given) ? given : null;
        }
        Path directory = start;
        for (int level = 0; level <= MAX_PARENT_LOOKUPS && directory != null; level++) {
            Path candidate = directory.resolve(given);
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
            directory = directory.getParent();
        }
        return null;
    }
}
