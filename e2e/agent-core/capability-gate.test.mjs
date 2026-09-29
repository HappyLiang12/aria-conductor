// Gate logic tests for the cross-core capability matrix.
//
// These tests exercise ONLY the gate's decision logic: they must pass with no
// Qoder/OpenCode CLI, no credentials, no container runtime and no network.
// The gate is fail-closed: a required core/mode pair whose row is missing, or
// whose row does not carry an explicit `true` for every required check, is a
// failure. Anything other than the boolean `true` (undefined, false, "true",
// 1, null) counts as not verified.
import assert from 'node:assert/strict';
import { mkdtempSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { test } from 'node:test';
import {
  REQUIRED_CHECKS,
  REQUIRED_PAIRS,
  loadMatrix,
  matrixStatus,
  missingCapabilities,
  requireCapabilities,
} from './capability-gate.mjs';

/** Builds a row with every required check observed, optionally overriding one. */
function verifiedRow(core, mode, overrides = {}) {
  const checks = Object.fromEntries(REQUIRED_CHECKS.map((check) => [check, true]));
  return { core, mode, status: 'verified', checks: { ...checks, ...overrides } };
}

/** Builds a complete, fully verified matrix. */
function verifiedMatrix() {
  return REQUIRED_PAIRS.map((pair) => {
    const [core, mode] = pair.split('/');
    return verifiedRow(core, mode);
  });
}

test('missing pause evidence blocks a claimed combination', () => {
  const rows = [{ core: 'qoder', mode: 'HOST', checks: { pauseResume: false } }];
  assert.throws(() => requireCapabilities(rows), /qoder\/HOST.*pauseResume/);
});

test('a complete verified matrix passes the gate', () => {
  assert.deepEqual(missingCapabilities(verifiedMatrix()), []);
  assert.doesNotThrow(() => requireCapabilities(verifiedMatrix()));
});

test('every required check is enforced for every required pair', () => {
  for (const pair of REQUIRED_PAIRS) {
    const [core, mode] = pair.split('/');
    for (const check of REQUIRED_CHECKS) {
      const rows = verifiedMatrix().map((row) =>
        `${row.core}/${row.mode}` === pair ? { ...row, checks: { ...row.checks, [check]: false } } : row,
      );
      assert.throws(
        () => requireCapabilities(rows),
        new RegExp(`${pair.replace('/', '\\/')}.*${check}`),
        `${pair} with ${check}=false must fail the gate`,
      );
    }
  }
});

test('a missing row for a required pair fails the gate', () => {
  const rows = verifiedMatrix().filter((row) => `${row.core}/${row.mode}` !== 'opencode/SANDBOX');
  assert.throws(() => requireCapabilities(rows), /opencode\/SANDBOX.*pauseResume/);
});

test('only the boolean true counts as verified', () => {
  for (const value of ['true', 1, 0, {}, [], null, undefined, 'yes']) {
    const rows = verifiedMatrix().map((row) =>
      `${row.core}/${row.mode}` === 'qoder/HOST'
        ? { ...row, checks: { ...row.checks, handshake: value } }
        : row,
    );
    assert.throws(
      () => requireCapabilities(rows),
      /qoder\/HOST.*handshake/,
      `handshake=${JSON.stringify(value)} must not count as verified`,
    );
  }
});

test('unrelated extra rows and checks cannot satisfy or relax a requirement', () => {
  const rows = [
    ...verifiedMatrix(),
    { core: 'qoder', mode: 'SANDBOX', checks: { handshake: true } },
    { core: 'extra', mode: 'HOST', checks: Object.fromEntries(REQUIRED_CHECKS.map((c) => [c, true])) },
  ];
  // A second qoder/SANDBOX row is ambiguous, not a substitute for evidence.
  assert.throws(() => requireCapabilities(rows), /qoder\/SANDBOX.*ambiguous/);
});

test('extra unmeasured checks on a row do not weaken the required set', () => {
  const rows = verifiedMatrix().map((row) => ({ ...row, checks: { ...row.checks, bonusCheck: true } }));
  assert.doesNotThrow(() => requireCapabilities(rows));
});

test('fail closed on malformed input', () => {
  assert.throws(() => requireCapabilities(undefined), /rows is not an array/);
  assert.throws(() => requireCapabilities({ rows: verifiedMatrix() }), /rows is not an array/);
  assert.throws(() => requireCapabilities([null, 'qoder/HOST']), /qoder\/HOST/);
});

test('core/mode matching is exact and case-sensitive', () => {
  const rows = verifiedMatrix().map((row) =>
    `${row.core}/${row.mode}` === 'opencode/HOST' ? { core: 'opencode', mode: 'host', checks: row.checks } : row,
  );
  assert.throws(() => requireCapabilities(rows), /opencode\/HOST.*pauseResume/);
});

test('matrixStatus reports verified and BLOCKED pairs with their unmet checks', () => {
  const rows = [{ core: 'qoder', mode: 'HOST', checks: { pauseResume: true, handshake: true } }];
  const status = matrixStatus(rows);
  assert.deepEqual(
    status.map((entry) => entry.pair),
    [...REQUIRED_PAIRS],
  );
  assert.equal(status[0].status, 'BLOCKED');
  assert.ok(status[0].unmet.includes('qoder/HOST: managedAuth not verified'));
  assert.equal(status[1].status, 'BLOCKED');
});

test('matrixStatus reports every pair verified for a complete matrix', () => {
  assert.deepEqual(
    matrixStatus(verifiedMatrix()).map((entry) => entry.status),
    REQUIRED_PAIRS.map(() => 'verified'),
  );
});

test('loadMatrix accepts { rows: [...] } and a bare array, and rejects a rowless file', () => {
  const dir = mkdtempSync(join(tmpdir(), 'aria-gate-test-'));
  try {
    const withRows = join(dir, 'rows.json');
    writeFileSync(withRows, JSON.stringify({ schemaVersion: 1, rows: verifiedMatrix() }));
    assert.equal(loadMatrix(withRows).rows.length, REQUIRED_PAIRS.length);

    const bare = join(dir, 'bare.json');
    writeFileSync(bare, JSON.stringify(verifiedMatrix()));
    assert.doesNotThrow(() => requireCapabilities(loadMatrix(bare).rows));

    const empty = join(dir, 'empty.json');
    writeFileSync(empty, JSON.stringify({ schemaVersion: 1 }));
    assert.throws(() => loadMatrix(empty), /has no rows array/);
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});
