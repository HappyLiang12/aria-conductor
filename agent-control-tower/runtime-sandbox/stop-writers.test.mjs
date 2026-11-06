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
  liveMembers,
  parseArgs,
  parseProcStat,
  readProcTable,
  readRecord,
  selectWriters,
  stoppableMembers,
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

/** A run-owned control directory carrying one record. */
function runDirectoryWith(root, overrides = {}) {
  const runDirectory = join(root, RUN_ID);
  mkdirSync(runDirectory, { recursive: true });
  writeFileSync(join(runDirectory, 'launch-record.json'), JSON.stringify(record(overrides)));
  return runDirectory;
}

/**
 * A synthetic procfs the tests can mutate, so the stop state machine runs
 * deterministically on any host: the injected signal substitutes the effects a
 * real kernel would produce and no real process is ever signalled.
 */
function syntheticProc(entries) {
  const root = mkdtempSync(join(tmpdir(), 'aria-proc-'));
  const write = (entry) => writeFileSync(join(root, String(entry.pid), 'stat'),
    statLine(entry.pid, entry.comm, entry.state, entry.ppid, entry.pgrp, entry.startTicks));
  const add = (entry) => {
    mkdirSync(join(root, String(entry.pid)), { recursive: true });
    write(entry);
  };
  for (const entry of entries) add(entry);
  return {
    root,
    add,
    remove: (pid) => rmSync(join(root, String(pid)), { recursive: true, force: true }),
    cleanup: () => rmSync(root, { recursive: true, force: true }),
  };
}

/** A signal recorder; `effects(pid, name)` simulates what the signal does to the process. */
function signalRecorder(effects = () => {}) {
  const calls = [];
  const signalPid = (pid, name) => {
    calls.push([name, pid]);
    effects(pid, name);
    return null;
  };
  signalPid.calls = calls;
  return signalPid;
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

test('the identity predicate distinguishes a live, reused and vanished child', async () => {
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

test('the stop selection admits a provably exited child and refuses a reused or evidence-less identity', () => {
  const live = tableOf([{ pid: 100, comm: 'opencode', state: 'S', ppid: 90, pgrp: 100, startTicks: 4242 }]);
  assert.deepEqual(stoppableMembers(live, record(), 777), [100]);

  // The recorded pid is absent: the child provably exited (the record was
  // written while it existed, and a process without a /proc entry cannot run).
  const absent = tableOf([{ pid: 90, comm: 'node', state: 'S', ppid: 1, pgrp: 1, startTicks: 2 }]);
  assert.deepEqual(stoppableMembers(absent, record(), 777), []);

  // The recorded pid is now another process: unverifiable, refused.
  const reused = tableOf([{ pid: 100, comm: 'other', state: 'S', ppid: 1, pgrp: 100, startTicks: 9999 }]);
  assert.throws(() => stoppableMembers(reused, record(), 777),
    (error) => error instanceof ControlFault && error.exitCode === 65
      && error.message === `Ownership identity mismatch for run ${RUN_ID}: pid 100 is gone or reused`);

  // No start-ticks evidence: the exit claim cannot be proven, even though the pid is absent.
  assert.throws(() => stoppableMembers(absent, record({ childStartTicks: null }), 777),
    (error) => error instanceof ControlFault && error.exitCode === 65
      && /no start-ticks evidence for pid 100/.test(error.message));
});

test('live members exclude the reaped, the corpse and the reused pid', () => {
  const startTicks = new Map([[100, 4242], [101, 4243], [102, 4244], [103, 4245]]);
  const table = tableOf([
    { pid: 101, comm: 'writer', state: 'S', ppid: 100, pgrp: 100, startTicks: 4243 },
    { pid: 102, comm: 'opencode', state: 'Z', ppid: 90, pgrp: 100, startTicks: 4244 },
    { pid: 103, comm: 'reused', state: 'S', ppid: 1, pgrp: 100, startTicks: 9999 },
    // pid 100 is absent: already reaped.
  ]);

  assert.deepEqual(liveMembers(table, [100, 101, 102, 103], startTicks), [101]);
});

test('a stop verifies a recorded child that provably exited before the stop ran', async () => {
  const location = syntheticProc([
    { pid: 1, comm: 'execd', state: 'S', ppid: 0, pgrp: 1, startTicks: 1 },
    { pid: 90, comm: 'node', state: 'S', ppid: 1, pgrp: 1, startTicks: 2 },
    // The recorded child (pid 100) already exited and was reaped: gone entirely.
  ]);
  try {
    const runDirectory = runDirectoryWith(location.root);
    const signalPid = signalRecorder();

    const report = await control({ runDirectory, action: 'stop', procRoot: location.root,
      graceMs: 0, killConfirmMs: 0, signalPid });

    assert.deepEqual(report, {
      action: 'stop',
      runId: RUN_ID,
      writersStopped: true,
      terminated: [],
      remaining: [],
    });
    assert.deepEqual(signalPid.calls, [], 'nothing may be signalled for an already exited child');
  } finally {
    location.cleanup();
  }
});

test('a stop verifies the recorded child corpse (exited, awaiting reaping) as a stopped writer', async () => {
  const location = syntheticProc([
    { pid: 1, comm: 'execd', state: 'S', ppid: 0, pgrp: 1, startTicks: 1 },
    { pid: 90, comm: 'node', state: 'S', ppid: 1, pgrp: 1, startTicks: 2 },
    // The recorded child exited while its parent never reaped it: a corpse.
    { pid: 100, comm: 'opencode', state: 'Z', ppid: 90, pgrp: 100, startTicks: 4242 },
  ]);
  try {
    const runDirectory = runDirectoryWith(location.root);
    const signalPid = signalRecorder();

    const report = await control({ runDirectory, action: 'stop', procRoot: location.root,
      graceMs: 0, killConfirmMs: 50, signalPid });

    assert.deepEqual(report, {
      action: 'stop',
      runId: RUN_ID,
      writersStopped: true,
      terminated: [100],
      remaining: [],
    });
    assert.deepEqual(signalPid.calls, [], 'a corpse can never be stopped again and is never escalated');
  } finally {
    location.cleanup();
  }
});

test('a stop observes the SIGKILL effect instead of racing the kernel teardown', async () => {
  const location = syntheticProc([
    { pid: 1, comm: 'execd', state: 'S', ppid: 0, pgrp: 1, startTicks: 1 },
    { pid: 90, comm: 'node', state: 'S', ppid: 1, pgrp: 1, startTicks: 2 },
    { pid: 100, comm: 'opencode', state: 'S', ppid: 90, pgrp: 100, startTicks: 4242 },
  ]);
  try {
    const runDirectory = runDirectoryWith(location.root);
    // The kernel takes a moment to tear the killed process down: the /proc
    // entry survives the immediate re-read and disappears only afterwards --
    // exactly the live race in which a verified kill was reported as a
    // surviving writer.
    const signalPid = signalRecorder((pid, name) => {
      assert.equal(pid, 100);
      if (name === 'SIGKILL') {
        setTimeout(() => location.remove(100), 120);
      }
    });

    const report = await control({ runDirectory, action: 'stop', procRoot: location.root,
      graceMs: 0, signalPid });

    assert.deepEqual(report, {
      action: 'stop',
      runId: RUN_ID,
      writersStopped: true,
      terminated: [100],
      remaining: [],
    });
    assert.deepEqual(signalPid.calls, [['SIGTERM', 100], ['SIGKILL', 100]]);
  } finally {
    location.cleanup();
  }
});

test('a writer that survives the kill confirmation keeps the stop unverified and failed closed', async () => {
  const location = syntheticProc([
    { pid: 1, comm: 'execd', state: 'S', ppid: 0, pgrp: 1, startTicks: 1 },
    { pid: 90, comm: 'node', state: 'S', ppid: 1, pgrp: 1, startTicks: 2 },
    { pid: 100, comm: 'opencode', state: 'S', ppid: 90, pgrp: 100, startTicks: 4242 },
    { pid: 101, comm: 'writer', state: 'S', ppid: 100, pgrp: 100, startTicks: 4243 },
  ]);
  try {
    const runDirectory = runDirectoryWith(location.root);
    const signalPid = signalRecorder(); // nothing dies: every member survives

    const report = await control({ runDirectory, action: 'stop', procRoot: location.root,
      graceMs: 0, killConfirmMs: 30, signalPid });

    assert.deepEqual(report, {
      action: 'stop',
      runId: RUN_ID,
      writersStopped: false,
      terminated: [],
      remaining: [101, 100],
    });
    assert.deepEqual(signalPid.calls,
      [['SIGTERM', 101], ['SIGTERM', 100], ['SIGKILL', 101], ['SIGKILL', 100]],
      'a surviving writer is escalated and then reported, never silently dropped');
  } finally {
    location.cleanup();
  }
});

test('a stop refuses a reused pid or an evidence-less record before any signal', async () => {
  const location = syntheticProc([
    { pid: 1, comm: 'execd', state: 'S', ppid: 0, pgrp: 1, startTicks: 1 },
    { pid: 90, comm: 'node', state: 'S', ppid: 1, pgrp: 1, startTicks: 2 },
    { pid: 100, comm: 'other', state: 'S', ppid: 1, pgrp: 100, startTicks: 9999 },
  ]);
  const runRoot = mkdtempSync(join(tmpdir(), 'aria-stop-test-'));
  try {
    const reusedDirectory = runDirectoryWith(runRoot);
    const signalPid = signalRecorder();
    await assert.rejects(control({ runDirectory: reusedDirectory, action: 'stop',
      procRoot: location.root, graceMs: 0, killConfirmMs: 0, signalPid }),
    (error) => error instanceof ControlFault && error.exitCode === 65
      && error.message === `Ownership identity mismatch for run ${RUN_ID}: pid 100 is gone or reused`);
    assert.deepEqual(signalPid.calls, [], 'an unverifiable identity is refused before any signal');

    // A record without start-ticks evidence proves nothing about an absent pid.
    location.remove(100);
    const evidenceLessDirectory = runDirectoryWith(join(runRoot, 'evidence-less'),
      { childStartTicks: null });
    await assert.rejects(control({ runDirectory: evidenceLessDirectory, action: 'stop',
      procRoot: location.root, graceMs: 0, killConfirmMs: 0, signalPid }),
    (error) => error instanceof ControlFault && error.exitCode === 65
      && /no start-ticks evidence for pid 100/.test(error.message));
    assert.deepEqual(signalPid.calls, []);
  } finally {
    location.cleanup();
    rmSync(runRoot, { recursive: true, force: true });
  }
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
