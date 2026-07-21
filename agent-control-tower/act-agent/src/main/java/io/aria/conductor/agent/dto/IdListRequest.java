package io.aria.conductor.agent.dto;

import lombok.Data;

import java.util.List;

/**
 * Shared request body for bulk idempotent-replace endpoints
 * (PUT /agents/{id}/tools and PUT /agents/{id}/skills).
 */
@Data
public class IdListRequest {
    private List<String> ids;
}
