package io.aria.conductor.aria.live;

/**
 * Where a bridge {@code tool_call} goes before Task 3/4 own it. Task 2 only proves the
 * line reaches Java intact; the governed tool executor and the live run arrive next, so
 * this seam exists now and defaults to nothing.
 */
@FunctionalInterface
public interface LiveToolCallSink {

    /** No tool execution wired yet. */
    LiveToolCallSink NONE = (session, json) -> {
    };

    /**
     * @param session the socket that owns the call
     * @param json    the bridge line, {@code {"type":"tool_call","id":…,"name":…,"arguments":{…}}}
     */
    void onToolCall(LiveSession session, String json);
}
