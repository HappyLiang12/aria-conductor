// Provider token usage.
//
// Finding F9: real qoder runs reported `totalTokensUsed = 0` because the
// bridge never surfaced the counters the ACP prompt result already carried.
//
// The two recorded shapes are the ACP `usage` block
// (`{ inputTokens, outputTokens, totalTokens }`) and the qoder `_meta.quota`
// token counts (`{ input_tokens, output_tokens }`). A counter is reported only
// when the frame actually carries it: an absent counter is `null`, never a
// fabricated zero, and a carried zero stays zero. A total is never summed from
// the parts here — the owning backend can decide that; this event states what
// the core said.
export interface TokenUsage {
  inputTokens: number | null;
  outputTokens: number | null;
  totalTokens: number | null;
  /** Which frame members supplied the numbers. */
  source: 'usage' | 'quota' | 'usage+quota';
}

/** Extract reported token counts, or `null` when the frame carries none. */
export function extractTokenUsage(result: unknown): TokenUsage | null {
  if (result === null || typeof result !== 'object' || Array.isArray(result)) return null;
  const record = result as Record<string, unknown>;
  const usage = readUsageBlock(record.usage);
  const quota = readQuotaBlock(record._meta);
  const hasUsage = usage.inputTokens !== null || usage.outputTokens !== null || usage.totalTokens !== null;
  const hasQuota = quota.inputTokens !== null || quota.outputTokens !== null || quota.totalTokens !== null;
  if (!hasUsage && !hasQuota) return null;
  return {
    // The pinned CLI (1.1.61) records a placeholder `usage {0,0,0}` next to the
    // real `_meta.quota` counts. A zero in the usage block must not hide a
    // nonzero quota counter; a genuine carried zero still survives when the
    // quota block has nothing better.
    inputTokens: preferCount(usage.inputTokens, quota.inputTokens),
    outputTokens: preferCount(usage.outputTokens, quota.outputTokens),
    totalTokens: preferCount(usage.totalTokens, quota.totalTokens),
    source: hasUsage && hasQuota ? 'usage+quota' : hasUsage ? 'usage' : 'quota',
  };
}

/** The primary counter unless it is a placeholder zero and the fallback is positive. */
function preferCount(primary: number | null, fallback: number | null): number | null {
  if (primary === null) return fallback;
  if (primary === 0 && fallback !== null && fallback > 0) return fallback;
  return primary;
}

interface UsageCounts {
  inputTokens: number | null;
  outputTokens: number | null;
  totalTokens: number | null;
}

function readUsageBlock(value: unknown): UsageCounts {
  if (value === null || typeof value !== 'object' || Array.isArray(value)) return emptyCounts();
  const record = value as Record<string, unknown>;
  return {
    inputTokens: readCount(record.inputTokens),
    outputTokens: readCount(record.outputTokens),
    totalTokens: readCount(record.totalTokens),
  };
}

function readQuotaBlock(meta: unknown): UsageCounts {
  if (meta === null || typeof meta !== 'object' || Array.isArray(meta)) return emptyCounts();
  const quota = (meta as Record<string, unknown>).quota;
  if (quota === null || typeof quota !== 'object' || Array.isArray(quota)) return emptyCounts();
  const tokenCount = (quota as Record<string, unknown>).token_count;
  if (tokenCount === null || typeof tokenCount !== 'object' || Array.isArray(tokenCount)) return emptyCounts();
  const record = tokenCount as Record<string, unknown>;
  return {
    inputTokens: readCount(record.input_tokens),
    outputTokens: readCount(record.output_tokens),
    totalTokens: readCount(record.total_tokens),
  };
}

function readCount(value: unknown): number | null {
  if (typeof value !== 'number' || !Number.isInteger(value) || value < 0) return null;
  return value;
}

function emptyCounts(): UsageCounts {
  return { inputTokens: null, outputTokens: null, totalTokens: null };
}
