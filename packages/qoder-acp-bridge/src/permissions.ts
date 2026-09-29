// Native permission selection.
//
// The recorded Qoder wire evidence offers different option ids and names per
// tool kind (edit: `proceed_always` / "Allow for this session"; execute:
// `proceed_always_and_save` / "Always allow \"<program>\""), so the decision
// made by Aria is a *choice* (ALLOW_ONCE / DENY) and the bridge selects the
// offered native option whose `kind` matches. Selection never uses an option's
// position and never falls back to the first option. An allow_always option is
// never used as an allow-once substitute.

export type PermissionChoice = 'ALLOW_ONCE' | 'DENY';

export interface PermissionOption {
  optionId: string;
  kind: string;
  name?: string;
}

/** The HTTP choice is validated before any option is selected. */
export function parseChoice(value: unknown): PermissionChoice {
  if (value === 'ALLOW_ONCE' || value === 'DENY') return value;
  throw new Error(`Invalid permission choice: ${describeChoiceValue(value)}; expected ALLOW_ONCE or DENY`);
}

/**
 * Select the native option id for a choice by the offered option's `kind`.
 * Exactly one matching option must be offered; anything else fails closed
 * instead of guessing.
 */
export function selectOption(options: Array<{ optionId: string; kind: string }>, choice: PermissionChoice): string {
  const kind = choice === 'ALLOW_ONCE' ? 'allow_once' : 'reject_once';
  const matches = options.filter((option) => option.kind === kind);
  if (matches.length !== 1) throw new Error(`Expected one ${kind} option`);
  const selected: { optionId: string; kind: string } | undefined = matches[0];
  if (selected === undefined) throw new Error(`Expected one ${kind} option`);
  return selected.optionId;
}

/** Exact frame text for a native permission decision. */
export function permissionResponseFrame(requestId: number, optionId: string): string {
  return `${JSON.stringify({
    jsonrpc: '2.0',
    id: requestId,
    result: { outcome: { outcome: 'selected', optionId } },
  })}\n`;
}

function describeChoiceValue(value: unknown): string {
  if (typeof value === 'string') return JSON.stringify(value);
  if (value === undefined) return 'undefined';
  if (value === null) return 'null';
  if (typeof value === 'number' || typeof value === 'boolean' || typeof value === 'bigint') {
    return String(value);
  }
  const json = JSON.stringify(value);
  return json === undefined ? String(value) : json;
}
