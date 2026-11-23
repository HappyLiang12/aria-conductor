package io.aria.conductor.aria.live;

/**
 * Where a bridge {@code tool_call} goes. Task 2 only proved the line reaches Java intact;
 * Task 4 wires {@link LiveToolCallHandler}, which owns the governed execution and the live
 * run. The two lifecycle signals a turn has — its end and its socket's close — are
 * defaults so a test can hand in nothing but the tool-call lambda.
 */
@FunctionalInterface
public interface LiveToolCallSink {

    /** No tool execution wired: the bridge's call is dropped. */
    LiveToolCallSink NONE = (session, json) -> {
    };

    /**
     * @param session the socket that owns the call
     * @param json    the bridge line, {@code {"type":"tool_call","id":…,"name":…,"arguments":{…}}}
     */
    void onToolCall(LiveSession session, String json);

    /**
     * The bridge says the turn is over: its run, if any, is settled now. The bridge must
     * send this only after every {@code tool_result} of the turn, because a tool call is
     * executed off the reader thread and its answer is what makes the turn's row final.
     */
    default void onTurnDone(LiveSession session) {
    }

    /** The socket closed: no park may outlive the only socket able to answer it. */
    default void onClosed(LiveSession session) {
    }
}
