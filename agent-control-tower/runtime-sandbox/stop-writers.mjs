#!/usr/bin/env node
// Aria Conductor runtime-sandbox writer control (fixed image control script).
//
// This script ships INSIDE every core image (/opt/aria/stop-writers.mjs) and is
// what the backend runs to suspend, resume or stop the writers one run owns --
// without destroying the sandbox. The sandbox filesystem and its export facility
// (the SDK's execd path) stay alive so the backend can export a stable tree
// after the writers stopped; only a later explicit destroy kills the sandbox.
//
// Ownership is a verified identity, never a process name: the launcher record
// carries the child pid, its /proc start ticks and its process-group id, and
// every action re-verifies identity before signalling. Nothing outside the
// recorded writer tree (execd, pid 1, the supervisor, other runs' processes) is
// ever signalled, and no shell is involved: signals go through process.kill.
//
// A stop verifies the OUTCOME, not merely the signals it sent: a recorded
// writer that has provably exited -- its /proc entry is gone (the launcher
// record proves it was our child while it existed), or its entry is a corpse
// awaiting reaping -- is a stopped writer, and after SIGKILL the script waits,
// boundedly, for the kill to take effect before computing its report. A
// recorded pid now held by a DIFFERENT process (reuse), or a record carrying no
// start-ticks evidence, stays a fail-closed fault: nothing is signalled for an
// identity that cannot be proven.
//
// Usage (image-internal, fixed):
//   node /opt/aria/stop-writers.mjs --run-directory <dir> --action stop|suspend|resume
import { readdirSync, readFileSync } from 'node:fs';
import { basename, join, resolve } from 'node:path';
import { pathToFileURL } from 'node:url';

export const RECORD_FILE = 'launch-record.json';
const DEFAULT_GRACE_MS = 3000;
/**
 * Bounded confirmation window after SIGKILL. Kill delivery is asynchronous:
 * the kernel tears a signalled process down after the signal is accepted and
 * the supervisor reaps the corpse afterwards, so the report must observe the
 * outcome instead of racing it.
 */
const DEFAULT_KILL_CONFIRM_MS = 2000;
const MAX_TREE_MEMBERS = 4096;
const POLL_INTERVAL_MS = 50;

/** A fail-closed control fault; `exitCode` is the process code to exit with. */
export class ControlFault extends Error {
  constructor(message, exitCode = 70) {
    super(message);
    this.name = 'ControlFault';
    this.exitCode = exitCode;
  }
}

function emit(payload) {
  process.stdout.write(`${JSON.stringify(payload)}\n`);
}

export function parseArgs(argv) {
  const flags = {};
  for (let index = 0; index < argv.length; index += 1) {
    const token = argv[index];
    if (!token.startsWith('--')) throw new ControlFault(`Unexpected argument: ${token}`, 64);
    const value = argv[index + 1];
    if (value === undefined || value.startsWith('--')) throw new ControlFault(`Missing value for ${token}`, 64);
    flags[token.slice(2)] = value;
    index += 1;
  }
  return flags;
}

/** `/proc/<pid>/stat` → { pid, comm, state, ppid, pgrp, startTicks }. */
export function parseProcStat(text) {
  const open = text.indexOf('(');
  const close = text.lastIndexOf(')');
  if (open < 0 || close < 0) return null;
  const fields = text.slice(close + 2).trim().split(/\s+/);
  return {
    pid: Number.parseInt(text.slice(0, open).trim(), 10),
    comm: text.slice(open + 1, close),
    state: fields[0],
    ppid: Number.parseInt(fields[1], 10),
    pgrp: Number.parseInt(fields[2], 10),
    startTicks: Number.parseInt(fields[19], 10),
  };
}

/** Reads the whole process table; a procfs-less host is refused explicitly. */
export function readProcTable(procRoot = '/proc') {
  let names;
  try {
    names = readdirSync(procRoot);
  } catch (error) {
    throw new ControlFault(`procfs is unavailable at ${procRoot}: ${error.message}`, 69);
  }
  const table = new Map();
  for (const name of names) {
    if (!/^[0-9]+$/.test(name)) continue;
    const pid = Number.parseInt(name, 10);
    let stat;
    try {
      stat = parseProcStat(readFileSync(join(procRoot, name, 'stat'), 'utf8'));
    } catch {
      continue; // the process exited while the table was read
    }
    if (stat !== null) table.set(pid, stat);
  }
  if (table.size === 0) throw new ControlFault(`procfs is unavailable at ${procRoot}: empty table`, 69);
  return table;
}

/**
 * The writers one run owns: every process whose process group is the recorded
 * child's group, plus every descendant of that child (a writer that escaped the
 * group by calling setsid is still a descendant). pid 1, this script, the
 * supervisor and anything outside the tree are never members.
 */
export function selectWriters(table, record, selfPid = process.pid) {
  const root = record.childPid;
  if (!Number.isInteger(root) || root <= 1) {
    throw new ControlFault(`Recorded child pid is unusable: ${root}`, 65);
  }
  const members = new Set();
  if (table.has(root)) members.add(root);
  for (const stat of table.values()) {
    if (stat.pid === root || stat.pgrp === root) {
      members.add(stat.pid);
      continue;
    }
    let current = stat;
    for (let depth = 0; depth < 64; depth += 1) {
      if (current.ppid === root) {
        members.add(stat.pid);
        break;
      }
      const parent = table.get(current.ppid);
      if (parent === undefined || parent.pid === current.pid) break;
      current = parent;
    }
  }
  for (const excluded of [1, selfPid, record.supervisorPid, process.ppid]) {
    members.delete(excluded);
  }
  if (members.size > MAX_TREE_MEMBERS) {
    throw new ControlFault(`Owned writer tree exceeds ${MAX_TREE_MEMBERS} processes`, 65);
  }
  return [...members].sort((left, right) => depthFirst(table, right, root) - depthFirst(table, left, root));
}

/** Depth of a member below the recorded root (deepest-first signalling order). */
function depthFirst(table, pid, root) {
  let depth = 0;
  let current = table.get(pid);
  while (current !== undefined && current.pid !== root && depth < 64) {
    current = table.get(current.ppid);
    depth += 1;
  }
  return depth;
}

/**
 * The members of `pids` that are still LIVE writers at this table read:
 * present, still carrying the frozen start ticks, and not a corpse ('Z'/'X' =
 * the process exited and only awaits reaping, so it can never run or write
 * again). A pid that is absent was already reaped; a pid whose ticks moved was
 * reused by another process -- neither can still be one of our writers.
 */
export function liveMembers(table, pids, startTicks) {
  return pids.filter((pid) => {
    const stat = table.get(pid);
    if (stat === undefined || stat.state === 'Z' || stat.state === 'X') {
      return false;
    }
    return stat.startTicks === startTicks.get(pid);
  });
}

/** True while the recorded child identity (pid + start ticks) is still the one on the host. */
export function identityMatches(table, record) {
  const stat = table.get(record.childPid);
  if (stat === undefined) return false;
  if (record.childStartTicks === null || record.childStartTicks === undefined) return false;
  return stat.startTicks === record.childStartTicks;
}

export function readRecord(runDirectory) {
  if (typeof runDirectory !== 'string' || !/^\/|^[A-Za-z]:[\\/]/.test(runDirectory)) {
    throw new ControlFault(`--run-directory must be an absolute path, got ${runDirectory}`, 64);
  }
  const directory = resolve(runDirectory);
  let record;
  try {
    record = JSON.parse(readFileSync(join(directory, RECORD_FILE), 'utf8'));
  } catch (error) {
    throw new ControlFault(`launch record is unreadable in ${directory}: ${error.message}`, 66);
  }
  if (record?.schemaVersion !== 1 || typeof record.runId !== 'string') {
    throw new ControlFault('launch record is malformed or of an unsupported schema', 65);
  }
  if (basename(directory) !== record.runId) {
    throw new ControlFault(`launch record belongs to run ${record.runId}, not ${basename(directory)}`, 65);
  }
  if (record.platform !== undefined && record.platform !== 'linux') {
    throw new ControlFault(
      `writer control requires a Linux procfs sandbox; the record was started on ${record.platform}`, 69);
  }
  return record;
}

const sleep = (ms) => new Promise((resolvePromise) => setTimeout(resolvePromise, ms));

function signal(pid, name) {
  try {
    process.kill(pid, name);
    return null;
  } catch (error) {
    return error.code ?? error.message;
  }
}

/** All owned members whose identity was re-verified at action time. */
function verifiedMembers(table, record, selfPid) {
  if (!identityMatches(table, record)) {
    throw new ControlFault(
      `Ownership identity mismatch for run ${record.runId}: pid ${record.childPid} is gone or reused`, 65);
  }
  return selectWriters(table, record, selfPid);
}

/**
 * The writer members a stop may signal. The recorded identity is judged against
 * the process table:
 * <ul>
 *   <li>present with the recorded start ticks: our child -- live, or an
 *       already-exited corpse awaiting reaping; both are ours;</li>
 *   <li>absent: the recorded child provably exited. The launcher record was
 *       written while the child existed, and a process with no /proc entry
 *       cannot run or write, so the absence itself is the evidence of the exit;
 *       whatever remains of the child's tree (its process group, its still-live
 *       descendants) is still selected and stopped.</li>
 * </ul>
 * Everything else -- a pid reused by another process, or a record carrying no
 * start-ticks evidence -- is unverifiable and refused before any signal.
 */
export function stoppableMembers(table, record, selfPid) {
  const recordedTicks = record.childStartTicks;
  if (recordedTicks === null || recordedTicks === undefined) {
    throw new ControlFault(
      `Ownership identity mismatch for run ${record.runId}: the launch record carries no start-ticks`
        + ` evidence for pid ${record.childPid}`, 65);
  }
  const stat = table.get(record.childPid);
  if (stat !== undefined && stat.startTicks !== recordedTicks) {
    throw new ControlFault(
      `Ownership identity mismatch for run ${record.runId}: pid ${record.childPid} is gone or reused`, 65);
  }
  return selectWriters(table, record, selfPid);
}

async function stopWriters(record, procRoot, graceMs, selfPid, signalPid, killConfirmMs) {
  const firstTable = readProcTable(procRoot);
  const initial = stoppableMembers(firstTable, record, selfPid);
  const startTicks = new Map(initial.map((pid) => [pid, firstTable.get(pid)?.startTicks]));
  let survivors = liveMembers(firstTable, initial, startTicks);
  for (const pid of survivors) {
    signalPid(pid, 'SIGTERM');
  }
  const deadline = Date.now() + graceMs;
  while (survivors.length > 0 && Date.now() < deadline) {
    await sleep(POLL_INTERVAL_MS);
    survivors = liveMembers(readProcTable(procRoot), survivors, startTicks);
  }
  // Re-read before escalation: a member that exited during the grace (or was
  // already a corpse) must not be signalled again.
  survivors = liveMembers(readProcTable(procRoot), survivors, startTicks);
  for (const pid of survivors) {
    signalPid(pid, 'SIGKILL');
  }
  // SIGKILL delivery is asynchronous: the kernel tears the process down after
  // the signal is accepted and the supervisor reaps the corpse afterwards, so
  // the first re-read can still see the dying member (observed live: a killed
  // core's /proc entry outlived the immediate re-read by ~80-430 ms). Wait,
  // boundedly, until every killed member is gone or a corpse; anything still
  // live after the window stays `remaining` and keeps the stop unverified.
  const killDeadline = Date.now() + killConfirmMs;
  for (;;) {
    survivors = liveMembers(readProcTable(procRoot), survivors, startTicks);
    if (survivors.length === 0 || Date.now() >= killDeadline) {
      break;
    }
    await sleep(POLL_INTERVAL_MS);
  }
  const remaining = survivors;
  const terminated = [];
  for (const pid of initial) {
    if (!remaining.includes(pid)) terminated.push(pid);
  }
  return {
    action: 'stop',
    runId: record.runId,
    writersStopped: remaining.length === 0,
    terminated,
    remaining,
  };
}

async function suspendWriters(record, procRoot, selfPid) {
  const table = readProcTable(procRoot);
  const members = verifiedMembers(table, record, selfPid);
  for (const pid of members) signal(pid, 'SIGSTOP');
  await sleep(POLL_INTERVAL_MS);
  const after = readProcTable(procRoot);
  const suspended = members.filter((pid) => ['T', 't'].includes(after.get(pid)?.state ?? ''));
  return {
    action: 'suspend',
    runId: record.runId,
    suspended: members.length > 0 && suspended.length === members.length,
    members,
    suspendedPids: suspended,
  };
}

async function resumeWriters(record, procRoot, selfPid) {
  const table = readProcTable(procRoot);
  const members = verifiedMembers(table, record, selfPid);
  for (const pid of members) signal(pid, 'SIGCONT');
  await sleep(POLL_INTERVAL_MS);
  const after = readProcTable(procRoot);
  const running = members.filter((pid) => !['T', 't'].includes(after.get(pid)?.state ?? 'T'));
  return {
    action: 'resume',
    runId: record.runId,
    resumed: members.length > 0 && running.length === members.length,
    members,
    runningPids: running,
  };
}

export async function control({ runDirectory, action, graceMs = DEFAULT_GRACE_MS,
  killConfirmMs = DEFAULT_KILL_CONFIRM_MS, procRoot = '/proc', selfPid = process.pid,
  signalPid = signal } = {}) {
  if (!['stop', 'suspend', 'resume'].includes(action)) {
    throw new ControlFault(`Unsupported action: ${action}`, 64);
  }
  const record = readRecord(runDirectory);
  if (action === 'stop') {
    return stopWriters(record, procRoot, graceMs, selfPid, signalPid, killConfirmMs);
  }
  if (action === 'suspend') return suspendWriters(record, procRoot, selfPid);
  return resumeWriters(record, procRoot, selfPid);
}

export async function main(argv = process.argv.slice(2)) {
  const flags = parseArgs(argv);
  const runDirectory = flags['run-directory'];
  const action = flags.action;
  if (runDirectory === undefined || action === undefined) {
    throw new ControlFault('Usage: stop-writers.mjs --run-directory <dir> --action stop|suspend|resume', 64);
  }
  const graceMs = flags['grace-ms'] !== undefined ? Number.parseInt(flags['grace-ms'], 10) : DEFAULT_GRACE_MS;
  if (!Number.isInteger(graceMs) || graceMs < 0 || graceMs > 60000) {
    throw new ControlFault(`--grace-ms must be 0-60000, got ${flags['grace-ms']}`, 64);
  }
  const report = await control({ runDirectory, action, graceMs });
  emit(report);
  const succeeded = action === 'stop' ? report.writersStopped
    : action === 'suspend' ? report.suspended : report.resumed;
  return succeeded ? 0 : 5;
}

const invokedDirectly = process.argv[1] !== undefined
  && import.meta.url === pathToFileURL(process.argv[1]).href;
if (invokedDirectly) {
  main()
    .then((code) => process.exit(code))
    .catch((error) => {
      emit({
        action: process.argv.includes('--action') ? process.argv[process.argv.indexOf('--action') + 1] : null,
        writersStopped: false,
        error: error.name,
        message: error.message,
      });
      process.exit(error instanceof ControlFault ? error.exitCode : 70);
    });
}
