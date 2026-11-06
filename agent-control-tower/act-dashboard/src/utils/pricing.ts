/** Cost per 1,000 tokens in USD. Update when pricing model changes. */
export const COST_PER_1K_TOKENS = 0.012;

export function estimateCost(tokens: number): number {
  return (tokens / 1000) * COST_PER_1K_TOKENS;
}
