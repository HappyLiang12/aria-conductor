package io.aria.conductor.aria.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
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
    /** True when this entry marks a failed turn; the entry is synthetic, not a trajectory row. */
    private boolean error;
    /** Prompt that re-runs the failed turn; null unless {@link #error}. */
    private String retryPrompt;
    /**
     * The {@code ToolCallStatus} name of the tool call this tool row belongs to, when the
     * run has a {@code tool_calls} row for it. The raw name, not a boolean: a restored
     * tool that was still EXECUTING must not be reported as a success either. Absent when
     * the timeline genuinely does not know — an older backend, or a row with no matching
     * tool call. Never invented.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String toolStatus;
    /**
     * The raw core tool-call id this row belongs to, from {@code session_trajectory.tool_call_id}.
     * Lets the panel attribute a result to its own call instead of by position. Absent for
     * older rows that carry no id.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String toolCallId;
}
