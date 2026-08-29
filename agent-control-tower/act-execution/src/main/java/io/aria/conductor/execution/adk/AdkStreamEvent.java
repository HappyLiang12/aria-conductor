package io.aria.conductor.execution.adk;

/**
 * S12: intermediate streaming fragment forwarded by turn-level providers
 * (langchain SSE {@code thinking}/{@code tool_call}/{@code tool_result} events)
 * so the engine can publish {@code RunProgressEvent}s for live observability.
 *
 * @param kind     raw SSE event type (thinking | status | tool_call | tool_result | error)
 * @param content  text payload (thinking text / tool result / status message)
 * @param toolName tool name for tool_* events, else null
 */
public record AdkStreamEvent(String kind, String content, String toolName) {
}
