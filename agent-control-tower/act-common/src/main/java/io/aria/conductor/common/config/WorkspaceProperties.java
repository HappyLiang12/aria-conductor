package io.aria.conductor.common.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Centralized workspace root configuration ({@code aria.workspaces.*}). Both the
 * execution module (artifact capture) and the knowledge module (spec reading)
 * consume this bean, eliminating duplicated {@code @Value} declarations and
 * ensuring a single source of truth for workspace paths.
 */
@Data
@ConfigurationProperties(prefix = "aria.workspaces")
public class WorkspaceProperties {

    /** Default runtime root for run workspaces. */
    public static final String DEFAULT_RUNTIME_ROOT = "./data/workspaces/runs";

    /** Default result root for captured artifacts. */
    public static final String DEFAULT_RESULT_ROOT = "./data/workspaces/results";

    /** Root directory for run workspace scratch/worktree/sandbox directories. */
    private String runtimeRoot = DEFAULT_RUNTIME_ROOT;

    /** Root directory for captured run artifacts (specs, manifests, changes). */
    private String resultRoot = DEFAULT_RESULT_ROOT;
}
