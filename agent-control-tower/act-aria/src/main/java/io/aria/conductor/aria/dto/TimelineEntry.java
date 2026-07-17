package io.aria.conductor.aria.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TimelineEntry {
    private String role;
    private String content;
    private Instant timestamp;
    private String runId;
}
