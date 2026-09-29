package io.aria.conductor.common.runtime;

/**
 * Admission/normalization port for the execution settings of an agent.
 *
 * <p>The production implementation (execution module) applies the documented
 * defaults and rejects explicitly unknown cores/modes; keeping the port in
 * act-common lets the agent module consume normalization without importing the
 * execution module.
 */
public interface AgentExecutionPolicy {

    /**
     * @param requested the caller-supplied selection (absent values resolved to
     *                  the documented defaults by the policy, unsupported
     *                  values rejected -- never silently substituted)
     * @return the normalized settings that are persisted and later frozen into
     *         a run's execution binding
     */
    AgentExecutionSettings normalize(AgentExecutionSettings requested);
}
