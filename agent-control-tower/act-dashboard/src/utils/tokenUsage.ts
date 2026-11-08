/**
 * F9 (SDD hardening): honest token-usage rendering.
 *
 * Real qoder runs reported `totalTokensUsed = 0` for LLM work the core never
 * measured, and the Runs surfaces painted that as a real zero. The api lane
 * sends null when a core does not report usage; the UI renders an explicit
 * "not reported" state then. A value that IS reported — including a genuine 0 —
 * is shown as it is.
 */

/** The explicit absence label (never a fabricated zero). */
export const TOKEN_USAGE_NOT_REPORTED = 'usage not reported';

/** Compact absence label for a table cell whose column header already says "Tokens". */
export const TOKEN_COUNT_NOT_REPORTED = 'not reported';

/**
 * The reported count with grouping, or null when the backend did not report a
 * finite number (absent field, null, NaN).
 */
export function formatTokenCount(value: number | null | undefined): string | null {
  if (typeof value !== 'number' || !Number.isFinite(value)) return null;
  return value.toLocaleString();
}

/** Full sentence fragment for run rails: "1,234 tokens" / "usage not reported". */
export function tokenUsageText(value: number | null | undefined): string {
  const formatted = formatTokenCount(value);
  return formatted === null ? TOKEN_USAGE_NOT_REPORTED : `${formatted} tokens`;
}

/** Table-cell text: the grouped count, or the compact absence label. */
export function tokenUsageCell(value: number | null | undefined): string {
  return formatTokenCount(value) ?? TOKEN_COUNT_NOT_REPORTED;
}
