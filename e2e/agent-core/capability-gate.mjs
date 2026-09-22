// Cross-core (Host/Sandbox) capability gate.
//
// The four core/mode combinations below are the supported delivery matrix for
// the Agent Core execution modes program. Each combination must carry OBSERVED
// evidence for every required check before adapters may be built on it. A
// combination whose checks were not observed is BLOCKED, never "skipped": the
// gate fails closed, so a missing row, a missing check, or any value other than
// the boolean `true` fails the gate.
//
// Evidence provenance lives outside this module: `probe-native.mjs` records the
// sanitized protocol fixtures, and
// `docs/reviews/2026-09-22-agent-core-capability-evidence.md` records the raw
// captured probe output. This module only enforces the invariant.
//
// Usage:
//   import { requireCapabilities } from './capability-gate.mjs';
//   requireCapabilities(matrix.rows);            // throws unless all verified
//   node capability-gate.mjs --matrix <file.json> // operator/CI entry point

import { readFileSync } from 'node:fs';
import { pathToFileURL } from 'node:url';

/** Required core/mode combinations, in report order. */
export const REQUIRED_PAIRS = Object.freeze([
  'qoder/HOST',
  'qoder/SANDBOX',
  'opencode/HOST',
  'opencode/SANDBOX',
]);

/**
 * Required checks per combination. `pauseResume` comes first so that the
 * canonical failure message names it first (it is the check most likely to be
 * unverifiable on a given OS/backend).
 */
export const REQUIRED_CHECKS = Object.freeze([
  'pauseResume',
  'handshake',
  'managedAuth',
  'isolatedConfig',
  'allowOnce',
  'denyWithoutWrite',
  'cancel',
  'writersStopped',
  'stableExport',
]);

/** A check is satisfied only by an explicit boolean `true`. */
function isVerified(row, check) {
  return row?.checks?.[check] === true;
}

/**
 * Returns the list of unmet requirements as `${pair}: ${check} not verified`
 * strings, in REQUIRED_PAIRS x REQUIRED_CHECKS order. An empty list means the
 * matrix is complete. Ambiguous input (non-array rows, duplicate pairs) is
 * reported as unmet rather than silently tolerated.
 */
export function missingCapabilities(rows) {
  if (!Array.isArray(rows)) {
    return [`matrix: rows is not an array (received ${typeof rows})`];
  }
  const missing = [];
  for (const pair of REQUIRED_PAIRS) {
    const matches = rows.filter((r) => r && `${r.core}/${r.mode}` === pair);
    if (matches.length > 1) {
      missing.push(`${pair}: ${matches.length} rows supplied (ambiguous, expected exactly one)`);
      continue;
    }
    const row = matches[0];
    for (const check of REQUIRED_CHECKS) {
      if (!isVerified(row, check)) missing.push(`${pair}: ${check} not verified`);
    }
  }
  return missing;
}

/**
 * Fails unless every required combination has observed `true` evidence for
 * every required check. The thrown message is a single line so callers can
 * match on `${pair}.*${check}`.
 */
export function requireCapabilities(rows) {
  const missing = missingCapabilities(rows);
  if (missing.length > 0) {
    throw new Error(`capability gate failed: ${missing.join('; ')}`);
  }
}

/** Loads a capability matrix JSON file; accepts `{ rows: [...] }` or a bare array. */
export function loadMatrix(filePath) {
  const parsed = JSON.parse(readFileSync(filePath, 'utf8'));
  const rows = Array.isArray(parsed) ? parsed : parsed?.rows;
  if (!Array.isArray(rows)) {
    throw new Error(`capability matrix ${filePath} has no rows array`);
  }
  return { matrix: parsed, rows };
}

/** Summarizes each required pair as verified or BLOCKED with its first unmet check. */
export function matrixStatus(rows) {
  const missing = missingCapabilities(rows);
  return REQUIRED_PAIRS.map((pair) => {
    const unmet = missing.filter((m) => m.startsWith(`${pair}:`));
    return unmet.length === 0 ? { pair, status: 'verified' } : { pair, status: 'BLOCKED', unmet };
  });
}

function main(argv) {
  const at = argv.indexOf('--matrix');
  if (at === -1 || !argv[at + 1]) {
    process.stderr.write('usage: node capability-gate.mjs --matrix <capability-matrix.json>\n');
    return 2;
  }
  const file = argv[at + 1];
  let rows;
  try {
    ({ rows } = loadMatrix(file));
  } catch (error) {
    process.stderr.write(`cannot read capability matrix: ${error.message}\n`);
    return 2;
  }
  const status = matrixStatus(rows);
  for (const entry of status) {
    const detail = entry.status === 'verified' ? '' : ` (${entry.unmet.join('; ')})`;
    process.stdout.write(`${entry.pair}: ${entry.status}${detail}\n`);
  }
  const blocked = status.filter((entry) => entry.status !== 'verified');
  if (blocked.length > 0) {
    process.stderr.write(`capability gate failed: ${blocked.length}/${status.length} combinations blocked\n`);
    return 1;
  }
  return 0;
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  process.exitCode = main(process.argv.slice(2));
}
