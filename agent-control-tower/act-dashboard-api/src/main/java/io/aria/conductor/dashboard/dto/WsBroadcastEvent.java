package io.aria.conductor.dashboard.dto;

import java.util.Map;

public record WsBroadcastEvent(
        String type,
        Map<String, Object> data,
        String timestamp
) {}