package io.aria.conductor.execution.runtime;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.Objects;

/**
 * HOST placement file-access policy ({@code aria.host.files}).
 *
 * <p>Only a HOST core runs with the backend user's own filesystem in reach; a
 * SANDBOX core is bounded by its run-owned container and keeps the shipped
 * policy. {@code FULL} is therefore a full-filesystem grant to the backend
 * user, with no sandbox and no undo; it is opt-in and the effective value is
 * logged at startup so the posture is visible in the operator's log.
 */
@Slf4j
@Component
@ConfigurationProperties(prefix = "aria.host")
public class HostFileAccessProperties {

    private HostFileAccess files = HostFileAccess.READ_ONLY;

    public HostFileAccess getFiles() {
        return files;
    }

    public void setFiles(HostFileAccess files) {
        this.files = Objects.requireNonNull(files, "aria.host.files");
    }

    /** True when a HOST core may use its own edit, write and shell tools. */
    public boolean fullAccess() {
        return files == HostFileAccess.FULL;
    }

    @PostConstruct
    void logEffectivePolicy() {
        log.info("HOST file access policy: aria.host.files={} (HOST placement only; SANDBOX runs are unchanged)",
                files);
    }
}
