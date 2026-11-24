package io.aria.conductor.common.context.qmd;

import java.util.Locale;

/** Maps a workspace reference to the project scope used by the shared vault. */
public final class QmdScope {

    private QmdScope() {
    }

    public static String projectOf(String workspaceRef) {
        if (workspaceRef == null || workspaceRef.isBlank()) {
            return null;
        }
        String normalized = workspaceRef.replace('\\', '/');
        String base = normalized.substring(normalized.lastIndexOf('/') + 1).toLowerCase(Locale.ROOT);
        if (base.isBlank()) {
            return null;
        }
        if (base.contains("eqm-root") || base.contains("eqm_root")) {
            return "project:eqm-root";
        }
        if (base.contains("clcm-root")) {
            return "project:clcm-root";
        }
        if (base.contains("aria-conductor")) {
            return "project:aria-conductor";
        }
        if (base.contains("harness-poc")) {
            return "project:harness-poc";
        }
        if (base.contains("agent-control-tower")) {
            return "project:agent-control-tower";
        }
        return "project:" + base.replaceAll("-[0-9a-f]{6}$", "");
    }
}
