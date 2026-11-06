// Shared fixture actions for the deterministic protocol peers (Task 7).
//
// This module is a test/harness artifact. It is never packaged into a production
// image, it opens no database connection and it never records a run outcome: the
// only filesystem effect it can produce is a fixture write inside the admitted
// temporary workspace, and only through applyDecision() after a genuine
// allow-once decision.
import { spawn, spawnSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { existsSync, readFileSync, readdirSync, statSync } from 'node:fs';
import { readFile, writeFile } from 'node:fs/promises';
import { dirname, isAbsolute, join, resolve, sep } from 'node:path';
import { fileURLToPath } from 'node:url';

const PEER_DIR = dirname(fileURLToPath(import.meta.url));
const MANIFEST_PATH = join(PEER_DIR, '..', 'scenarios.json');

/** Process exit codes shared by every peer. */
export const EXIT_CODES = Object.freeze({
  ok: 0,
  scripted: 3,
  disconnect: 7,
  usage: 64,
  config: 78,
});

/** A fail-closed peer fault; `exitCode` is the process code the peer must use. */
export class PeerFault extends Error {
  constructor(exitCode, message) {
    super(message);
    this.name = 'PeerFault';
    this.exitCode = exitCode;
  }
}

// ------------------------------------------------------------------ fixture writes

/**
 * Apply one offered permission decision. Only a genuine allow-once decision may
 * write; every other kind (including allow_always) is refused without a write.
 *
 * The admitted workspace is a required argument and confinement is re-checked
 * here, so the helper itself refuses a target outside the admitted workspace
 * even when a caller forgets assertInsideWorkspace().
 */
export async function applyDecision(optionId, options, target, contents, workspace) {
  const selected = options.find((option) => option.optionId === optionId);
  if (selected?.kind === 'reject_once') return { status: 'denied', writes: 0 };
  if (selected?.kind !== 'allow_once') throw new Error('Unsupported fixture decision');
  if (typeof target !== 'string' || !isAbsolute(target)) {
    throw new Error('Fixture write target must be an absolute path inside the admitted workspace');
  }
  if (typeof workspace !== 'string' || !isAbsolute(workspace)) {
    throw new Error('Fixture write requires the admitted workspace');
  }
  await writeFile(assertInsideWorkspace(workspace, target), contents, 'utf8');
  return { status: 'written', writes: 1 };
}

/** Resolve a fixture path and require it to stay inside the admitted workspace. */
export function assertInsideWorkspace(workspace, target) {
  const root = resolve(workspace);
  const resolved = resolve(target);
  if (resolved === root) {
    throw new PeerFault(EXIT_CODES.config, `Fixture path equals the workspace root: ${target}`);
  }
  if (!resolved.startsWith(root.endsWith(sep) ? root : `${root}${sep}`)) {
    throw new PeerFault(EXIT_CODES.config, `Fixture path escapes the admitted workspace: ${target}`);
  }
  return resolved;
}

// ------------------------------------------------------------------ git push fixture

/**
 * The branch/remote the run prompt names, as the recorded push scenario requires
 * ("Push branch <branch> to origin."). The branch is the exact token after
 * `branch `; the remote is the token after ` to ` before the trailing period.
 * Anything else fails closed with the usage exit code instead of pushing to an
 * invented target.
 */
export function gitPushTargetFromPrompt(promptText) {
  const match = /Push branch (\S+) to ([^\s.]+)/.exec(typeof promptText === 'string' ? promptText : '');
  if (!match) {
    throw new PeerFault(EXIT_CODES.usage,
      'The git-push scenario requires a prompt of the form "Push branch <branch> to <remote>."');
  }
  return { branch: match[1], remote: match[2] };
}

/**
 * Perform the governed git push fixture: only a genuine allow-once decision
 * pushes, and the push lands the admitted workspace's current HEAD on
 * `refs/heads/<branch>` of the named remote (the disposable bare remote the
 * spec owns; the worktree's `origin` points at it). A denial pushes nothing.
 */
export function pushGitBranch({ optionId, options, workspace, branch, remote }) {
  const selected = options.find((option) => option.optionId === optionId);
  if (selected?.kind === 'reject_once') return { status: 'denied', writes: 0 };
  if (selected?.kind !== 'allow_once') throw new Error('Unsupported fixture decision');
  if (typeof workspace !== 'string' || !isAbsolute(workspace)) {
    throw new Error('The git push fixture requires the admitted workspace');
  }
  if (typeof branch !== 'string' || branch.length === 0 || typeof remote !== 'string' || remote.length === 0) {
    throw new Error('The git push fixture requires the branch and the remote the prompt named');
  }
  const result = spawnSync('git', ['push', remote, `HEAD:refs/heads/${branch}`], {
    cwd: workspace,
    encoding: 'utf8',
    windowsHide: true,
  });
  if (result.error) {
    throw new Error(`fixture git push failed to start: ${result.error.message}`);
  }
  if (result.status !== 0) {
    throw new Error(`fixture git push failed (exit ${result.status}): ${result.stderr ?? ''}`);
  }
  return { status: 'written', writes: 0, pushed: `refs/heads/${branch}` };
}

/** Read one fixture file; `sha256` is null when the file does not exist. */
export async function readFixtureFile(path) {
  try {
    const buffer = await readFile(path);
    return { exists: true, size: buffer.length, sha256: createHash('sha256').update(buffer).digest('hex') };
  } catch {
    return { exists: false, size: 0, sha256: null };
  }
}

/** Deterministic, sorted snapshot of every file under a directory. */
export async function snapshotDirectory(dir) {
  const entries = [];
  const walk = (current, prefix) => {
    for (const name of readdirSync(current).sort()) {
      const full = join(current, name);
      const info = statSync(full);
      const rel = prefix ? `${prefix}/${name}` : name;
      if (info.isDirectory()) walk(full, rel);
      else if (info.isFile()) {
        const buffer = readFileSync(full);
        entries.push({ path: rel, size: buffer.length, sha256: createHash('sha256').update(buffer).digest('hex') });
      }
    }
  };
  if (existsSync(dir)) walk(dir, '');
  return entries;
}

// ------------------------------------------------------------------ harness gate

/** Load the committed scenario manifest. */
export function loadScenarioManifest() {
  return JSON.parse(readFileSync(MANIFEST_PATH, 'utf8'));
}

/** Scenario ids declared for one peer, in manifest order. */
export function implementedScenarios(peerId) {
  return loadScenarioManifest()
    .scenarios.filter((scenario) => scenario.peers.includes(peerId))
    .map((scenario) => scenario.id);
}

/** One scenario definition; throws when the manifest does not declare it. */
export function scenarioDefinition(scenarioId) {
  const scenario = loadScenarioManifest().scenarios.find((entry) => entry.id === scenarioId);
  if (!scenario) throw new PeerFault(EXIT_CODES.usage, `Unknown scenario: ${scenarioId}`);
  return scenario;
}

/** First whitespace-delimited token of a fixture command (`printf`, `node`, ...). */
export function commandProgram(command) {
  if (typeof command !== 'string') return '';
  return command.trim().split(/\s+/)[0] ?? '';
}

/**
 * The recorded option list for one tool kind (qoder-host-cli-1.1.61-protocol.jsonl
 * lines 41/66/136 for edit, 84/165 for execute): edit requests offer
 * `proceed_always`/"Allow for this session", execute requests offer
 * `proceed_always_and_save`/"Always allow \"<program>\"" with the program being
 * the command's first token.
 */
export function recordedOptions(kind, { command = null } = {}) {
  const { options } = loadScenarioManifest().fixtures;
  const list = options[kind] ?? options.edit;
  return list.map((option) =>
    option.nameTemplate === undefined
      ? { optionId: option.optionId, name: option.name, kind: option.kind }
      : {
          optionId: option.optionId,
          name: option.nameTemplate.replace('${program}', commandProgram(command)),
          kind: option.kind,
        },
  );
}

/** Parse `--name value` flags plus the positional command. */
export function parsePeerFlags(argv) {
  const flags = {};
  const positional = [];
  for (let index = 0; index < argv.length; index += 1) {
    const arg = argv[index];
    if (arg.startsWith('--')) {
      flags[arg.slice(2)] = argv[index + 1];
      index += 1;
    } else {
      positional.push(arg);
    }
  }
  return { flags, positional };
}

/**
 * Require a numeric TCP port for `--port` (the OpenCode `serve` argv). An absent
 * flag means an ephemeral port; anything non-numeric or out of range fails
 * closed with the shared usage exit code instead of yielding `NaN` and an
 * unhandled listen throw.
 */
export function requirePortFlag(flags) {
  const raw = flags.port;
  if (raw === undefined) return 0;
  const port = Number(raw);
  if (!Number.isInteger(port) || port < 0 || port > 65535) {
    process.stderr.write(`${JSON.stringify({ type: 'peer.invalid_port', flag: '--port', value: raw })}\n`);
    process.exit(EXIT_CODES.usage);
  }
  return port;
}

/**
 * Harness-only bootstrap. Fails closed with an exact stderr record when the
 * control token, the scenario or the admitted workspace is missing or invalid, so
 * a production caller can neither select a scenario nor reach a peer.
 */
export function bootPeer({ peerId, argv, env = process.env }) {
  const fail = (record, exitCode) => {
    process.stderr.write(`${JSON.stringify(record)}\n`);
    process.exit(exitCode);
  };
  const token = env.ARIA_PEER_CONTROL_TOKEN;
  if (typeof token !== 'string' || token.length < 16) {
    fail({ type: 'peer.control_token_missing', variable: 'ARIA_PEER_CONTROL_TOKEN' }, EXIT_CODES.config);
  }
  const { flags, positional } = parsePeerFlags(argv);
  const scenario = flags.scenario ?? env.ARIA_PEER_SCENARIO ?? null;
  if (scenario === null || !implementedScenarios(peerId).includes(scenario)) {
    fail({ type: 'peer.unknown_scenario', peer: peerId, scenario }, EXIT_CODES.usage);
  }
  const workspace = flags.workspace ?? env.ARIA_PEER_WORKSPACE ?? null;
  if (workspace === null || !existsSync(workspace) || !statSync(workspace).isDirectory()) {
    fail({ type: 'peer.workspace_missing', variable: '--workspace', value: workspace }, EXIT_CODES.config);
  }
  return {
    token,
    scenario,
    definition: scenarioDefinition(scenario),
    workspace: resolve(workspace),
    flags,
    positional,
  };
}

/** JSON diagnostics go to stderr only; stdout is reserved for the protocol. */
export function record(type, fields = {}) {
  process.stderr.write(`${JSON.stringify({ type, ...fields })}\n`);
}

// ------------------------------------------------------------------ framing

/** One newline-delimited JSON-RPC frame. */
export function encodeFrame(frame) {
  return `${JSON.stringify(frame)}\n`;
}

/** Incremental line framer; tolerates partial writes and rejects bad JSON upstream. */
export function createLineReader(onLine) {
  let buffer = '';
  return {
    push(chunk) {
      buffer += chunk;
      for (;;) {
        const at = buffer.indexOf('\n');
        if (at < 0) break;
        const line = buffer.slice(0, at);
        buffer = buffer.slice(at + 1);
        onLine(line);
      }
    },
    flush() {
      if (buffer.length > 0) {
        const rest = buffer;
        buffer = '';
        onLine(rest);
      }
    },
  };
}

export function parsedChunk(chunk) {
  try {
    return JSON.parse(chunk);
  } catch {
    return null;
  }
}

// ------------------------------------------------------------------ owned writer

/**
 * Deterministic writer program materialized inside the workspace. The recorded
 * workspace contains exactly these two fixture files (`spawn-writer.mjs` and
 * `writer.mjs`), so the peer reproduces the recorded tool command shape.
 */
export const WRITER_PROGRAM = [
  '// fixture writer: appends one deterministic line every 50 ms',
  "import { appendFileSync } from 'node:fs';",
  'const target = process.argv[2];',
  'let tick = 0;',
  'setInterval(() => {',
  '  tick += 1;',
  "  appendFileSync(target, `tick ${tick}\\n`, 'utf8');",
  '}, 50);',
  '',
].join('\n');

export const SPAWN_WRITER_PROGRAM = [
  '// fixture launcher: spawns the owned writer and reports its pid on stdout',
  "import { spawn } from 'node:child_process';",
  "import { dirname, join } from 'node:path';",
  "import { fileURLToPath } from 'node:url';",
  'const here = dirname(fileURLToPath(import.meta.url));',
  "const child = spawn(process.execPath, [join(here, 'writer.mjs'), process.argv[2] ?? 'ticks.log'], {",
  '  cwd: here,',
  "  stdio: 'ignore',",
  '});',
  "process.stdout.write(`${JSON.stringify({ type: 'writer.child', pid: child.pid })}\\n`);",
  '',
].join('\n');

export const WRITER_FILES = Object.freeze({
  'writer.mjs': WRITER_PROGRAM,
  'spawn-writer.mjs': SPAWN_WRITER_PROGRAM,
});

/**
 * Perform one granted writer fixture: the declared payload write plus the two
 * writer programs, every file written through applyDecision() with the genuine
 * allow-once decision. A denial writes nothing at all.
 */
export async function grantWriterFixture({ optionId, options, workspace, logName, initialContents }) {
  const payload = await applyDecision(
    optionId,
    options,
    assertInsideWorkspace(workspace, join(workspace, logName)),
    initialContents,
    workspace,
  );
  if (payload.status !== 'written') return { status: 'denied', writes: 0, files: [] };
  const files = [logName];
  for (const [name, body] of Object.entries(WRITER_FILES)) {
    await applyDecision(optionId, options, assertInsideWorkspace(workspace, join(workspace, name)), body, workspace);
    files.push(name);
  }
  return { status: 'written', writes: 1, files };
}

/**
 * Start the owned background writer and wait for its reported pid. The writer is
 * a real descendant of the peer: it keeps writing until its tree is terminated.
 */
export function startBackgroundWriter({ workspace, logName = 'ticks.log', timeoutMs = 5000 }) {
  const launcher = spawn(process.execPath, [join(workspace, 'spawn-writer.mjs'), logName], {
    cwd: workspace,
    stdio: ['ignore', 'pipe', 'pipe'],
    windowsHide: true,
  });
  return new Promise((resolvePromise, reject) => {
    let buffer = '';
    const timer = setTimeout(() => {
      launcher.kill();
      reject(new Error('fixture writer did not report its pid in time'));
    }, timeoutMs);
    launcher.stdout.setEncoding('utf8');
    launcher.stdout.on('data', (chunk) => {
      buffer += chunk;
      const at = buffer.indexOf('\n');
      if (at < 0) return;
      const parsed = parsedChunk(buffer.slice(0, at));
      clearTimeout(timer);
      if (!parsed || !Number.isInteger(parsed.pid)) {
        reject(new Error(`fixture writer reported an unusable pid line: ${buffer}`));
        return;
      }
      resolvePromise({ launcher, writerPid: parsed.pid });
    });
    launcher.on('error', (error) => {
      clearTimeout(timer);
      reject(error);
    });
  });
}

// ------------------------------------------------------------------ process control

const NATIVE_SOURCE = [
  'using System;',
  'using System.Runtime.InteropServices;',
  'public static class AriaPeerNative {',
  '  [DllImport("ntdll.dll")] public static extern uint NtSuspendProcess(IntPtr handle);',
  '  [DllImport("ntdll.dll")] public static extern uint NtResumeProcess(IntPtr handle);',
  '}',
].join('\n');

function runPowerShell(body) {
  const script = [
    "$ErrorActionPreference = 'Stop'",
    "$source = @'",
    NATIVE_SOURCE,
    "'@",
    'Add-Type -TypeDefinition $source | Out-Null',
    body,
  ].join('\n');
  for (const executable of ['powershell.exe', 'pwsh.exe']) {
    const result = spawnSync(
      executable,
      ['-NoProfile', '-NonInteractive', '-ExecutionPolicy', 'Bypass', '-Command', script],
      { encoding: 'utf8', windowsHide: true },
    );
    if (result.error && result.error.code === 'ENOENT') continue;
    if (result.status !== 0) {
      throw new Error(`${executable} ${body}: status=${result.status} stderr=${result.stderr}`);
    }
    return result.stdout;
  }
  throw new Error('no PowerShell host available for native process control');
}

function nativeControl(pid, operation) {
  const body = [
    `$p = Get-Process -Id ${pid}`,
    `$rc = [AriaPeerNative]::${operation}($p.Handle)`,
    "if ($rc -ne 0) { Write-Error \"native call returned $rc\"; exit 2 }",
    "Write-Output 'peer.native ok'",
  ].join('\n');
  const stdout = runPowerShell(body);
  if (!stdout.includes('peer.native ok')) {
    throw new Error(`native ${operation} did not confirm for pid ${pid}: ${stdout}`);
  }
  return true;
}

/** Suspend a live process exactly as the verified Task 1 control technique does. */
export async function suspendProcess(pid) {
  if (process.platform === 'win32') return nativeControl(pid, 'NtSuspendProcess');
  process.kill(pid, 'SIGSTOP');
  return true;
}

/** Resume a previously suspended process. */
export async function resumeProcess(pid) {
  if (process.platform === 'win32') return nativeControl(pid, 'NtResumeProcess');
  process.kill(pid, 'SIGCONT');
  return true;
}

/** Terminate a process and its descendants (escalation for non-cooperative peers). */
export async function terminateTree(pid) {
  if (!pid) return false;
  if (process.platform === 'win32') {
    const result = spawnSync('taskkill', ['/PID', String(pid), '/T', '/F'], { encoding: 'utf8', windowsHide: true });
    return result.status === 0;
  }
  try {
    process.kill(-pid, 'SIGKILL');
  } catch {
    try {
      process.kill(pid, 'SIGKILL');
    } catch {
      return false;
    }
  }
  return true;
}
