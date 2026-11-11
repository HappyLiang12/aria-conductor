package io.aria.conductor.dashboard.memory.dto;

import java.util.List;
import java.util.UUID;

/**
 * Request body of {@code PATCH /api/v1/memory/entities/{id}}. Exactly one operation is
 * allowed per call: rename ({@code displayName}), alias ({@code aliases}), merge
 * ({@code mergeInto} = surviving entity id), or retire ({@code retire=true}).
 */
public record PatchEntityRequest(
        String displayName,
        List<String> aliases,
        UUID mergeInto,
        Boolean retire) {
}
