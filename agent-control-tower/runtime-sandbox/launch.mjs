#!/usr/bin/env node
// Aria Conductor runtime-sandbox launcher (fixed image entry launcher).
//
// This script ships INSIDE every core image (/opt/aria/launch.mjs) and is the
// only thing the backend executes to start a run-owned core. It reads the
// trusted launch manifest that the backend uploaded into the run-owned control
// directory and spawns the core with `shell: false` and an explicit argv array:
// caller-supplied argv is never interpolated into a shell string, and argv[0]
// must resolve against the image's own entrypoint allowlist.
//
// It also becomes the run's in-container supervisor: it records the child's
// identity (pid + /proc start ticks) so stop-writers.mjs can verify ownership
// before signalling anything, forwards the child's output, and exits with the
// child's code. It never opens a container-runtime socket and never reads host
// credentials: the manifest is the only input, and its environment is validated.
//
// Usage (image-internal, fixed):
//   node /opt/aria/launch.mjs --manifest <runDirectory>/launch-manifest.json
import { spawn } from 'node:child_process';
import { createConnection } from 'node:net';
import { closeSync, existsSync, mkdirSync, openSync, readFileSync, renameSync, statSync, writeSync } from 'node:fs';
import { createHash } from 'node:crypto';
import { basename, dirname, isAbsolute, join, resolve } from 'node:path';
import { pathToFileURL } from 'node:url';

export const MANIFEST_SCHEMA_VERSION = 1;
export const RECORD_FILE = 'launch-record.json';
export const DEFAULT_ENTRYPOINTS_FILE = '/opt/aria/entrypoints.json';
export const DEFAULT_WORKSPACE_ROOT = '/workspace';
const MAX_MANIFEST_BYTES = 256 * 1024;
const MAX_ARGV_ENTRIES = 16;
const MAX_ARGUMENT_LENGTH = 4096;
const MAX_ENVIRONMENT_ENTRIES = 64;
const MAX_ENVIRONMENT_VALUE_LENGTH = 8192;
const FORBIDDEN_ENVIRONMENT_KEYS = new Set([
  'PATH', 'HOME', 'SHELL', 'IFS', 'ENV', 'BASH_ENV', 'BASH_FUNC',
  'NODE_OPTIONS', 'LD_PRELOAD', 'LD_LIBRARY_PATH',
  'DYLD_INSERT_LIBRARIES', 'DYLD_LIBRARY_PATH',
  'PYTHONPATH', 'PYTHONHOME', 'GIT_CONFIG_GLOBAL', 'GIT_CONFIG_SYSTEM',
]);
const SHELLS = new Set(['sh', 'bash', 'dash', 'zsh', 'ksh', 'csh', 'tcsh', 'fish',
  'cmd', 'cmd.exe', 'powershell', 'powershell.exe', 'pwsh', 'pwsh.exe']);
const BASE_ENVIRONMENT = Object.freeze({
  PATH: '/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin',
  HOME: '/home/aria',
  LANG: 'C.UTF-8',
});

/** A fail-closed launcher fault; `exitCode` is the process code to exit with. */
export class LaunchFault extends Error {
  constructor(message, exitCode = 78) {
    super(message);
    this.name = 'LaunchFault';
    this.exitCode = exitCode;
  }
}

/** Structured diagnostics: stdout is reserved for the supervisor event stream. */
function emit(event, fields = {}) {
  process.stdout.write(`${JSON.stringify({ event, ...fields })}\n`);
}

/** Parses the fixed CLI surface (image-internal shape, no free-form flags). */
export function parseArgs(argv) {
  const flags = {};
  for (let index = 0; index < argv.length; index += 1) {
    const token = argv[index];
    if (!token.startsWith('--')) throw new LaunchFault(`Unexpected argument: ${token}`, 64);
    const value = argv[index + 1];
    if (value === undefined || value.startsWith('--')) throw new LaunchFault(`Missing value for ${token}`, 64);
    flags[token.slice(2)] = value;
    index += 1;
  }
  return flags;
}

function assertString(value, what) {
  if (typeof value !== 'string' || value.length === 0) {
    throw new LaunchFault(`Manifest ${what} must be a non-empty string`);
  }
  if (value.includes('\0') || value.includes('\n') || value.includes('\r')) {
    throw new LaunchFault(`Manifest ${what} must not contain NUL, CR or LF characters`);
  }
  return value;
}

/**
 * Validates the trusted manifest. The run id is derived from the manifest's own
 * run-owned directory, so a manifest can never be replayed into another run's
 * control directory.
 */
export function validateManifest(raw, { expectRunId, workspaceRoot }) {
  if (raw === null || typeof raw !== 'object' || Array.isArray(raw)) {
    throw new LaunchFault('Manifest must be a JSON object');
  }
  if (raw.schemaVersion !== MANIFEST_SCHEMA_VERSION) {
    throw new LaunchFault(`Unsupported manifest schemaVersion: ${raw.schemaVersion}`);
  }
  const runId = assertString(raw.runId, 'runId');
  if (expectRunId !== undefined && runId !== expectRunId) {
    throw new LaunchFault(`Manifest belongs to run ${runId}, not ${expectRunId}`);
  }
  if (!Number.isInteger(raw.port) || raw.port < 1 || raw.port > 65535) {
    throw new LaunchFault(`Manifest port must be 1-65535, got ${raw.port}`);
  }
  const workingDirectory = assertString(raw.workingDirectory, 'workingDirectory');
  if (!isAbsolute(workingDirectory) || (!workingDirectory.startsWith(`${workspaceRoot}/`) && workingDirectory !== workspaceRoot)) {
    throw new LaunchFault(`Manifest workingDirectory must stay inside ${workspaceRoot}: ${workingDirectory}`);
  }
  if (!Array.isArray(raw.argv) || raw.argv.length === 0 || raw.argv.length > MAX_ARGV_ENTRIES) {
    throw new LaunchFault(`Manifest argv must carry 1-${MAX_ARGV_ENTRIES} entries`);
  }
  const argv = raw.argv.map((argument, index) => {
    const value = assertString(argument, `argv[${index}]`);
    if (value.length > MAX_ARGUMENT_LENGTH) throw new LaunchFault(`Manifest argv[${index}] is oversized`);
    return value;
  });
  const shell = basename(argv[0]).toLowerCase();
  if (SHELLS.has(shell)) {
    throw new LaunchFault(`Manifest argv[0] must name the core entrypoint, not a shell: ${shell}`);
  }
  let env = {};
  if (raw.env !== undefined) {
    if (raw.env === null || typeof raw.env !== 'object' || Array.isArray(raw.env)) {
      throw new LaunchFault('Manifest env must be a JSON object');
    }
    const names = Object.keys(raw.env);
    if (names.length > MAX_ENVIRONMENT_ENTRIES) {
      throw new LaunchFault(`Manifest env carries more than ${MAX_ENVIRONMENT_ENTRIES} entries`);
    }
    for (const name of names) {
      if (!/^[A-Za-z_][A-Za-z0-9_]*$/.test(name)) {
        throw new LaunchFault(`Manifest env name is not a plain variable name: ${name}`);
      }
      if (FORBIDDEN_ENVIRONMENT_KEYS.has(name.toUpperCase())) {
        throw new LaunchFault(`Manifest env must not carry the process-injection variable ${name}`);
      }
      const value = raw.env[name];
      if (typeof value !== 'string' || value.length > MAX_ENVIRONMENT_VALUE_LENGTH) {
        throw new LaunchFault(`Manifest env value for ${name} is invalid or oversized`);
      }
      assertString(value, `env ${name}`);
    }
    env = { ...raw.env };
  }
  return {
    schemaVersion: MANIFEST_SCHEMA_VERSION,
    runId,
    port: raw.port,
    workingDirectory,
    argv,
    env,
    readyTimeoutMs: Number.isInteger(raw.readyTimeoutMs) && raw.readyTimeoutMs > 0 && raw.readyTimeoutMs <= 300000
      ? raw.readyTimeoutMs
      : 30000,
  };
}

/** Reads and validates the image's own entrypoint allowlist (never manifest-supplied). */
export function loadEntrypoints(path) {
  let parsed;
  try {
    parsed = JSON.parse(readFileSync(path, 'utf8'));
  } catch (error) {
    throw new LaunchFault(`Entrypoint allowlist ${path} is unreadable: ${error.message}`);
  }
  const names = parsed?.names ?? {};
  const paths = Array.isArray(parsed?.paths) ? parsed.paths : [];
  if (Object.keys(names).length === 0 && paths.length === 0) {
    throw new LaunchFault(`Entrypoint allowlist ${path} is empty`);
  }
  return { names, paths: new Set(paths) };
}

/** Resolves argv[0] against the allowlist: a bare name maps to its pinned path. */
export function resolveEntrypoint(program, allowlist) {
  const value = assertString(program, 'argv[0]');
  if (isAbsolute(value)) {
    if (!allowlist.paths.has(value)) {
      throw new LaunchFault(`Entrypoint is not allowlisted by this image: ${value}`);
    }
    if (!existsSync(value)) {
      throw new LaunchFault(`Allowlisted entrypoint is missing from the image: ${value}`);
    }
    return value;
  }
  const resolved = allowlist.names[value];
  if (resolved === undefined) {
    throw new LaunchFault(`Entrypoint name is not allowlisted by this image: ${value}`);
  }
  if (!isAbsolute(resolved) || !existsSync(resolved)) {
    throw new LaunchFault(`Allowlisted entrypoint ${value} resolves to a missing path: ${resolved}`);
  }
  return resolved;
}

/** The child environment: a fixed base plus the validated manifest entries. */
export function buildEnvironment(manifestEnv) {
  return { ...BASE_ENVIRONMENT, ...manifestEnv };
}

/** `/proc/<pid>/stat` start ticks (field 22) — the durable identity component. */
export function readStartTicks(pid, procRoot = '/proc') {
  try {
    const stat = readFileSync(join(procRoot, String(pid), 'stat'), 'utf8');
    const close = stat.lastIndexOf(')');
    return Number.parseInt(stat.slice(close + 2).split(' ')[19], 10);
  } catch {
    return null;
  }
}

/** Spawns the validated core. `shell: false` is the whole point of this file. */
export function launchRun(profile, { platform = process.platform } = {}) {
  return spawn(profile.argv[0], profile.argv.slice(1), {
    cwd: profile.workingDirectory,
    env: profile.environment,
    shell: false,
    stdio: ['ignore', 'pipe', 'pipe'],
    // POSIX: the child becomes its own process-group leader, which is the
    // writer-ownership boundary stop-writers.mjs verifies and signals.
    detached: platform !== 'win32',
  });
}

/** Writes the run-owned supervisor record atomically (mode 600). */
export function writeRecord(runDirectory, record) {
  const target = join(runDirectory, RECORD_FILE);
  const temporary = `${target}.tmp`;
  const fd = openSync(temporary, 'w', 0o600);
  try {
    writeSync(fd, `${JSON.stringify(record)}\n`);
  } finally {
    closeSync(fd);
  }
  renameSync(temporary, target);
  return target;
}

function probeListening(port, timeoutMs) {
  const deadline = Date.now() + timeoutMs;
  return new Promise((resolvePromise) => {
    const attempt = () => {
      const socket = createConnection({ host: '127.0.0.1', port });
      socket.once('connect', () => {
        socket.destroy();
        resolvePromise(true);
      });
      socket.once('error', () => {
        socket.destroy();
        if (Date.now() >= deadline) {
          resolvePromise(false);
        } else {
          setTimeout(attempt, 200);
        }
      });
    };
    attempt();
  });
}

export async function main(argv = process.argv.slice(2)) {
  const flags = parseArgs(argv);
  const manifestPath = flags.manifest;
  if (manifestPath === undefined) throw new LaunchFault('Usage: launch.mjs --manifest <path>', 64);
  const workspaceRoot = flags['workspace-root'] ?? DEFAULT_WORKSPACE_ROOT;
  const entrypointsFile = flags.entrypoints ?? process.env.ARIA_ENTRYPOINTS_FILE ?? DEFAULT_ENTRYPOINTS_FILE;
  const runDirectory = dirname(resolve(manifestPath));
  const runId = basename(runDirectory);

  if (statSync(manifestPath).size > MAX_MANIFEST_BYTES) {
    throw new LaunchFault(`Manifest is oversized: ${manifestPath}`);
  }
  const manifest = validateManifest(JSON.parse(readFileSync(manifestPath, 'utf8')), {
    expectRunId: runId,
    workspaceRoot,
  });
  const entrypoint = resolveEntrypoint(manifest.argv[0], loadEntrypoints(entrypointsFile));
  if (!existsSync(manifest.workingDirectory)) {
    throw new LaunchFault(`Manifest workingDirectory does not exist in the image: ${manifest.workingDirectory}`);
  }
  mkdirSync(runDirectory, { recursive: true });

  const profile = {
    argv: [entrypoint, ...manifest.argv.slice(1)],
    environment: buildEnvironment(manifest.env),
    workingDirectory: manifest.workingDirectory,
  };
  const child = launchRun(profile);
  const record = {
    schemaVersion: 1,
    runId: manifest.runId,
    entrypoint,
    argvDigest: createHash('sha256').update(JSON.stringify(manifest.argv)).digest('hex'),
    supervisorPid: process.pid,
    childPid: child.pid,
    childStartTicks: readStartTicks(child.pid),
    platform: process.platform,
    startedAt: new Date().toISOString(),
  };
  const recordPath = writeRecord(runDirectory, record);
  emit('supervisor.started', { runId: manifest.runId, childPid: child.pid, recordPath });

  child.stdout.on('data', (chunk) => process.stdout.write(chunk));
  child.stderr.on('data', (chunk) => process.stderr.write(chunk));
  child.on('error', (error) => {
    emit('supervisor.child_error', { runId: manifest.runId, message: error.message });
    process.exitCode = 70;
  });

  void probeListening(manifest.port, manifest.readyTimeoutMs).then((listening) => {
    emit(listening ? 'supervisor.listening' : 'supervisor.ready_timeout', {
      runId: manifest.runId,
      port: manifest.port,
    });
  });

  return new Promise((resolvePromise) => {
    child.on('exit', (code, signal) => {
      emit('child.exit', { runId: manifest.runId, code, signal });
      resolvePromise(code ?? (signal ? 128 : 0));
    });
  });
}

const invokedDirectly = process.argv[1] !== undefined
  && import.meta.url === pathToFileURL(process.argv[1]).href;
if (invokedDirectly) {
  main()
    .then((code) => process.exit(code))
    .catch((error) => {
      process.stderr.write(`${JSON.stringify({
        event: 'supervisor.fault',
        name: error.name,
        message: error.message,
      })}\n`);
      process.exit(error instanceof LaunchFault ? error.exitCode : 70);
    });
}
