import type { Agent } from '../types';

export interface RuntimeComposition {
  /** The `core/MODE` pair, e.g. `qoder/HOST`. */
  runtime: string;
  count: number;
}

/**
 * The fleet's runtime composition as the distinct (core, mode) pairs over
 * non-retired agents (fleet runtime governance §3): uniform fleets compose to
 * exactly one entry; a mixed fleet lists every pair with its side's count.
 */
export function composeRuntimes(agents: Agent[]): RuntimeComposition[] {
  const counts = new Map<string, number>();
  for (const agent of agents) {
    if (agent.healthStatus === 'RETIRED') continue;
    counts.set(effectiveRuntimeOf(agent), (counts.get(effectiveRuntimeOf(agent)) ?? 0) + 1);
  }
  return [...counts.entries()]
    .map(([runtime, count]) => ({ runtime, count }))
    .sort((a, b) => a.runtime.localeCompare(b.runtime));
}

/**
 * Effective-pair normalization matching the backend guard (AgentService +
 * DefaultAgentExecutionPolicy): a stored null mode means the documented default
 * pair, not an unknown state — one truth on both sides of the wire.
 */
export function effectiveRuntimeOf(agent: Agent): string {
  const core = agent.adkProvider == null || agent.adkProvider === '' ? 'opencode' : String(agent.adkProvider);
  const storedMode: string | null = agent.executionMode == null ? null : String(agent.executionMode);
  const mode = storedMode == null || storedMode === '' ? 'SANDBOX' : storedMode;
  return `${core}/${mode}`;
}

/** True when every non-retired agent runs the same (core, mode) pair. */
export function isUniform(agents: Agent[]): boolean {
  return composeRuntimes(agents).length <= 1;
}
