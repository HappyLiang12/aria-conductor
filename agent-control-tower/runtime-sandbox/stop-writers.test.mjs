// Unit tests of the fixed writer-control script (runtime-sandbox/stop-writers.mjs).
//
// The ownership logic (identity verification, the writer tree, the exclusion of
// pid 1 / execd / the supervisor / other runs) is pure and is exercised here
// with real process tables and a synthetic one. The signalling path runs only
// on a Linux procfs sandbox: this file proves the fail-closed posture on a host
// without procfs -- the script refuses and leaves the real child alive -- and
// the container lane (e2e/agent-core/sandbox-lifecycle.test.mjs) is where the
// real SIGSTOP/SIGTERM/SIGKILL boundary is verified.
import assert from 'node:assert/strict';
import { spawn, spawnSync } from 'node:child_process';
import { mkdirSync, mkdtempSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { test } from 'node:test';
import {
  ControlFault,
  control,
  identityMatches,
  parseArgs,
  parseProcStat,
  readProcTable,
  readRecord,
  selectWriters,
} from './stop-writers.mjs';

const RUN_ID = '00000000-0000-0000-0000-0000000000t2';
const SCRIPT = resolve(fileURLToPath(new URL('./stop-writers.mjs', import.meta.url)));

function statLine(pid, comm, state, ppid, pgrp, startTicks) {
  const fields = [state, ppid, pgrp, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, startTicks];
  return `${pid} (${comm}) ${fields.join(' ')}`;
}

function tableOf(entries) {
  return new Map(entries.map((entry) => [entry.pid, entry]));
}

function record(overrides = {}) {
  return {
    schemaVersion: 1,
    runId: RUN_ID,
    supervisorPid: 90,
    childPid: 100,
    childStartTicks: 4242,
    platform: 'linux',
    ...overrides,
  };
}

test('a proc stat line is parsed into the identity fields', () => {
  const parsed = parseProcStat(statLine(100, 'opencode', 'S', 90, 100, 4242));
  assert.deepEqual(parsed, { pid: 100, comm: 'opencode', state: 'S', ppid: 90, pgrp: 100, startTicks: 4242 });
  assert.equal(parseProcStat('not a stat line'), null);
});

test('the writer tree is the recorded group plus descendants, exempt of foreign processes', () => {
  const table = tableOf([
    { pid: 1, comm: 'execd', state: 'S', ppid: 0, pgrp: 1, startTicks: 1 },
    { pid: 90, comm: 'node', state: 'S', ppid: 1, pgrp: 1, startTicks: 2 },
    { pid: 100, comm: 'opencode', state: 'S', ppid: 90, pgrp: 100, startTicks: 4242 },
    { pid: 101, comm: 'node', state: 'S', ppid: 100, pgrp: 100, startTicks: 4243 },
    { pid: 102, comm: 'spawn-writer', state: 'S', ppid: 101, pgrp: 100, startTicks: 4244 },
    { pid: 103, comm: 'escaped-writer', state: 'S', ppid: 101, pgrp: 103, startTicks: 4245 },
    { pid: 500, comm: 'unrelated', state: 'S', ppid: 1, pgrp: 500, startTicks: 9 },
  ]);

  const writers = selectWriters(table, record(), 777);

  assert.deepEqual(new Set(writers), new Set([100, 101, 102, 103]),
    'only the run-owned writers are selected');
  assert.equal(writers[0], 102, 'signalling order starts with the deepest writer');
  assert.equal(writers.includes(1), false, 'pid 1 is never signalled');
  assert.equal(writers.includes(90), false, 'the supervisor is not a writer');
  assert.equal(writers.includes(500), false, 'an unrelated process is never signalled');
});

test('the current script and its parent are never members of the writer tree', () => {
  const table = tableOf([
    { pid: 100, comm: 'opencode', state: 'S', ppid: 50, pgrp: 100, startTicks: 4242 },
    { pid: 500, comm: 'node', state: 'S', ppid: 100, pgrp: 100, startTicks: 7 },
  ]);
  assert.deepEqual(selectWriters(table, record(), 500), [100]);
});

test('an unusable recorded child pid is refused', () => {
  assert.throws(() => selectWriters(new Map(), record({ childPid: 1 }), 999), /unusable/);
  assert.throws(() => selectWriters(new Map(), record({ childPid: null }), 999), /unusable/);
});

test('the process table reader fails closed without procfs and reads a real table with one', () => {
  assert.throws(() => readProcTable(join(tmpdir(), 'aria-absent-procfs-9999')), /procfs is unavailable/);

  const root = mkdtempSync(join(tmpdir(), 'aria-proc-'));
  try {
    mkdirSync(join(root, '100'));
    writeFileSync(join(root, '100', 'stat'), statLine(100, 'opencode', 'S', 90, 100, 4242));
    writeFileSync(join(root, 'not-a-pid'), 'ignored');
    const table = readProcTable(root);
    assert.equal(table.get(100).startTicks, 4242);
    assert.equal(table.size, 1);
  } finally {
    rmSync(root, { recursive: true, force: true });
  }
});

test('a reused or missing root identity is refused before any signal', async () => {
  const table = tableOf([
    { pid: 100, comm: 'other', state: 'S', ppid: 1, pgrp: 100, startTicks: 9999 },
  ]);
  assert.equal(identityMatches(table, record()), false, 'a reused pid is not the recorded child');
  assert.equal(identityMatches(table, record({ childPid: 404 })), false, 'a vanished child is not verifiable');
  assert.equal(identityMatches(table, record({ childStartTicks: null })), false,
    'a record without identity evidence can never be verified');
  assert.equal(identityMatches(tableOf([
    { pid: 100, comm: 'opencode', state: 'S', ppid: 1, pgrp: 100, startTicks: 4242 },
  ]), record()), true);
  await assert.rejects(control({ runDirectory: 'relative', action: 'stop' }), /absolute path/);
});

test('readRecord refuses a missing, malformed or foreign record', () => {
  const root = mkdtempSync(join(tmpdir(), 'aria-stop-test-'));
  try {
    const runDirectory = join(root, RUN_ID);
    mkdirSync(runDirectory, { recursive: true });
    assert.throws(() => readRecord(runDirectory), ControlFault);

    writeFileSync(join(runDirectory, 'launch-record.json'), '{"schemaVersion":1,"runId":"someone-else"}\n');
    assert.throws(() => readRecord(runDirectory), /belongs to run/);

    writeFileSync(join(runDirectory, 'launch-record.json'), 'not json');
    assert.throws(() => readRecord(runDirectory), ControlFault);

    writeFileSync(join(runDirectory, 'launch-record.json'), JSON.stringify(record({ platform: 'win32' })));
    assert.throws(() => readRecord(runDirectory), /requires a Linux procfs sandbox/);

    writeFileSync(join(runDirectory, 'launch-record.json'), JSON.stringify(record()));
    assert.equal(readRecord(runDirectory).childPid, 100);
  } finally {
    rmSync(root, { recursive: true, force: true });
  }
});

test('an unsupported action and free-form arguments are refused', async () => {
  const root = mkdtempSync(join(tmpdir(), 'aria-stop-test-'));
  try {
    const runDirectory = join(root, RUN_ID);
    mkdirSync(runDirectory, { recursive: true });
    writeFileSync(join(runDirectory, 'launch-record.json'), JSON.stringify(record()));
    await assert.rejects(control({ runDirectory, action: 'detonate' }), /Unsupported action/);
    assert.throws(() => parseArgs(['stop']), /Unexpected argument/);
    assert.throws(() => parseArgs(['--action']), /Missing value/);
  } finally {
    rmSync(root, { recursive: true, force: true });
  }
});

test('on a host without procfs the script refuses and leaves a real child alive', async () => {
  const root = mkdtempSync(join(tmpdir(), 'aria-stop-test-'));
  const child = spawn(process.execPath, ['-e', 'setTimeout(() => process.exit(0), 30000)'], {
    stdio: 'ignore',
  });
  try {
    const runDirectory = join(root, RUN_ID);
    mkdirSync(runDirectory, { recursive: true });
    // A record from a non-Linux launcher: the control path has no procfs to
    // verify identity with, so it must refuse and must not touch the child.
    writeFileSync(join(runDirectory, 'launch-record.json'), JSON.stringify(record({
      platform: process.platform,
      childPid: child.pid,
      supervisorPid: process.pid,
      childStartTicks: null,
    })));

    await assert.rejects(control({ runDirectory, action: 'stop' }),
      (error) => error instanceof ControlFault && /try|requires|procfs|Linux|Linux procfs/i.test(error.message));

    const cli = spawnSync(process.execPath, [SCRIPT, '--run-directory', runDirectory, '--action', 'stop'],
      { encoding: 'utf8' });
    assert.notEqual(cli.status, 0, 'the CLI must fail closed when it cannot verify writer identity');
    const report = JSON.parse(cli.stdout.trim().split('\n').at(-1));
    assert.equal(report.writersStopped, false);
    assert.ok(report.error, 'the refusal is explicit');

    assert.equal(child.exitCode, null, 'the unrelated child process must still be alive');
  } finally {
    child.kill('SIGKILL');
    rmSync(root, { recursive: true, force: true });
  }
});
