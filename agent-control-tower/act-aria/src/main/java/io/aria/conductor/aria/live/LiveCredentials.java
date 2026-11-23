package io.aria.conductor.aria.live;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Reads the two Qwen credentials from {@code ~/.aria-conductor/qwen-live.env} (spec L2).
 *
 * <p>The file is {@code KEY=value} lines with mode 0600. A file any other local user could
 * read is refused outright, and the refusal names the path and the reason only — never a
 * value. The child receives these two keys through its environment and nothing else; they
 * never touch argv, a log line or a frame.
 *
 * <p>Read per {@code hello} rather than cached, so a rotated file is picked up by the next
 * socket without a restart.
 */
@Component
public class LiveCredentials {

    static final String API_KEY = "DASHSCOPE_API_KEY";
    static final String WORKSPACE = "DASHSCOPE_WORKSPACE";
    static final String HOME_PREFIX = "~/";

    private static final Logger log = LoggerFactory.getLogger(LiveCredentials.class);

    private final LiveProperties properties;

    public LiveCredentials(LiveProperties properties) {
        this.properties = properties;
    }

    /** @return the two keys, or empty when the file is missing, unsafe or incomplete */
    public Optional<Map<String, String>> load() {
        return load(expand(properties.credentialsFile(), Path.of(System.getProperty("user.home"))));
    }

    static Optional<Map<String, String>> load(Path file) {
        if (!Files.isRegularFile(file)) {
            log.warn("qwen live credentials unavailable: path={} reason=missing", file);
            return Optional.empty();
        }
        if (!privateToOwner(file)) {
            log.warn("qwen live credentials refused: path={} reason=group_or_other_access", file);
            return Optional.empty();
        }
        Map<String, String> values;
        try {
            values = parse(Files.readAllLines(file));
        } catch (IOException e) {
            log.warn("qwen live credentials unavailable: path={} reason=unreadable", file);
            return Optional.empty();
        }
        String apiKey = values.get(API_KEY);
        String workspace = values.get(WORKSPACE);
        if (apiKey == null || apiKey.isBlank() || workspace == null || workspace.isBlank()) {
            log.warn("qwen live credentials incomplete: path={} reason=key_or_workspace_absent", file);
            return Optional.empty();
        }
        return Optional.of(Map.of(API_KEY, apiKey, WORKSPACE, workspace));
    }

    /** {@code ~/x} means {@code <home>/x}; an absolute or plain relative path is kept. */
    static Path expand(String configured, Path home) {
        if (configured == null || configured.isBlank()) {
            return home.resolve(".aria-conductor/qwen-live.env");
        }
        String trimmed = configured.trim();
        if (trimmed.equals("~")) {
            return home;
        }
        if (trimmed.startsWith(HOME_PREFIX)) {
            return home.resolve(trimmed.substring(HOME_PREFIX.length()));
        }
        return Path.of(trimmed);
    }

    private static boolean privateToOwner(Path file) {
        Set<PosixFilePermission> permissions;
        try {
            permissions = Files.getPosixFilePermissions(file);
        } catch (UnsupportedOperationException | IOException e) {
            // A mode that cannot be verified is not a mode that was verified.
            return false;
        }
        return permissions.stream().noneMatch(permission ->
                permission.name().startsWith("GROUP_") || permission.name().startsWith("OTHERS_"));
    }

    private static Map<String, String> parse(List<String> lines) {
        Map<String, String> values = new LinkedHashMap<>();
        for (String raw : lines) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            int separator = line.indexOf('=');
            if (separator <= 0) {
                continue;
            }
            String key = line.substring(0, separator).trim();
            if (!API_KEY.equals(key) && !WORKSPACE.equals(key)) {
                continue;
            }
            values.put(key, unquote(line.substring(separator + 1).trim()));
        }
        return values;
    }

    private static String unquote(String value) {
        if (value.length() >= 2
                && ((value.startsWith("\"") && value.endsWith("\""))
                || (value.startsWith("'") && value.endsWith("'")))) {
            return value.substring(1, value.length() - 1).trim();
        }
        return value;
    }
}
