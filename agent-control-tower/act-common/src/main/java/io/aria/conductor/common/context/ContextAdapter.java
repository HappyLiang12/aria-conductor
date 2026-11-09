package io.aria.conductor.common.context;

/**
 * The LLM entry point a context envelope is assembled for (spec §8.1).
 * Every entry point uses the same pipeline; the adapter only records which
 * one asked.
 */
public enum ContextAdapter {
    SYNC,
    STREAM,
    CORE
}
