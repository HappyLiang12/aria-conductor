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
    const runtime = `${agent.adkProvider ?? '?'}/${agent.executionMode ?? '?'}`;
    counts.set(runtime, (counts.get(runtime) ?? 0) + 1);
  }
  return [...counts.entries()]
    .map(([runtime, count]) => ({ runtime, count }))
    .sort((a, b) => a.runtime.localeCompare(b.runtime));
}

/** True when every non-retired agent runs the same (core, mode) pair. */
export function isUniform(agents: Agent[]): boolean {
  return composeRuntimes(agents).length <= 1;
}
