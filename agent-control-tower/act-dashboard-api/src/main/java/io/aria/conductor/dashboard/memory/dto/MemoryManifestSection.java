package io.aria.conductor.dashboard.memory.dto;

import java.util.List;

/** Whitelisted manifest section metadata. Deliberately has no content field. */
public record MemoryManifestSection(
        String id,
        List<String> sources,
        int chars,
        int tokens,
        boolean truncated) {
}
