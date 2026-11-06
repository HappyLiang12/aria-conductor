#!/usr/bin/env node
// probe-native.mjs — operator-invoked native capability probe harness.
//
// Purpose
//   Establish, with captured evidence, which core/mode combinations of the
//   Agent Core execution-modes program are actually capable today, and freeze
//   the sanitized protocol recordings plus the verified OS control technique
//   that Tasks 7-11 build adapters and mock peers on.
//
// This harness NEVER runs unattended in CI: it spends real inference credits,
// needs an operator-supplied credential file and a disposable workspace.
//
// Usage
//   node e2e/agent-core/probe-native.mjs \
//     --core qoder --mode HOST \
//     --workspace <abs disposable dir> --output <abs json file> \
//     [--matrix-out e2e/agent-core/fixtures/capability-matrix.json] \
//     [--recording-dir e2e/agent-core/fixtures] \
//     [--check handshake,managedAuth,...] \
//     [--cli <abs qodercli path>] [--pat-file <abs secret file>] \
//     [--opencode <abs opencode shim>] [--image <ref>] \
//     [--sandbox-endpoint http://127.0.0.1:8090] [--model efficient] \
//     [--run-dir <abs scratch dir>] [--timeout-ms 120000]
//
// Exit codes: 0 = row verified, 1 = row blocked/partial, 2 = usage error.
//
// Check semantics (a check is `true` only when every clause was observed)
//   handshake         qoder: newline-delimited JSON-RPC ACP `initialize` and
//                     `session/new` succeeded with the pinned model acknowledged
//                     (`models.currentModelId` and `_meta.quota.model_usage[].model`).
//                     opencode: `opencode serve` started, `GET /global/health`
//                     reported healthy, `POST /session` returned a session AND a
//                     model-bound message produced a protocol-level response.
//   managedAuth       The core authenticated using ONLY the run-owned credential
//                     injected into the child environment from --pat-file, and
//                     the same handshake failed without it (two-sided). The
//                     credential value never appears in any artifact.
//   isolatedConfig    Child environment carries none of the ambient SDK variables
//                     and no credential-shaped inherited variables; configuration
//                     comes from a run-owned --config-dir; a hostile project MCP
//                     file present in the workspace is loadable but is NOT loaded
//                     by the run-owned invocation (two-sided), and the marker
//                     server observed zero connections during the session.
//   allowOnce         A write permission request answered with the option whose
//                     `kind` is `allow_once` (never by position, never
//                     allow_always) produced the exact expected file bytes, and
//                     no permission-mode escalation was observed.
//   denyWithoutWrite  A second write request answered with the offered reject
//                     option produced no file: the target is checked once after
//                     the prompt settles (one post-completion look; there is no
//                     second delayed sample).
//   cancel            Three arms, all captured: (a) a cancel for a session that
//                     was never created is recorded verbatim as observed
//                     behaviour (the pinned core answers it with silence, not a
//                     rejection) and must leave the live owned tree untouched:
//                     the core process survives, the workspace bytes are
//                     unchanged and the live session observes no update;
//                     (b) cancel while a permission request is pending settles the
//                     prompt and writes nothing; (c) cancel right after the first
//                     in-flight tool call settles the prompt with
//                     stopReason=cancelled AND leaves the target file absent
//                     (never granted).
//   pauseResume       The OS-owned job-object technique is verified on a
//                     controlled disposable tree (byte freeze on suspend, growth
//                     on resume) and then applied to the live core process tree
//                     while a tool-spawned descendant writer is active: writer
//                     bytes freeze during suspension and resume afterwards.
//   writersStopped    A tool-spawned descendant writer keeps writing AFTER the
//                     prompt-completion event (so completion is not quiescence),
//                     and the run's termination sequence freezes its bytes and
//                     removes every tracked process (verified by byte sampling
//                     and PID liveness, not by native success text). The recorded
//                     `technique` names only the steps that actually applied;
//                     steps that failed to run are recorded as limitations.
//   stableExport      After writers stopped, two workspace snapshots taken a
//                     delay apart are byte-identical; the snapshot is recorded.
//
// Safety
//   * The workspace must be a disposable, non-git directory outside this
//     checkout; the harness refuses anything else.
//   * The credential is read from --pat-file and injected only as the child
//     environment variable QODER_PERSONAL_ACCESS_TOKEN. It is never printed,
//     never passed in argv and never written into a fixture.
//   * Ambient SDK/auth variables are deleted from the child environment.
//   * Qoder probes pin model `efficient`; any other requested model ID is a
//     usage error.

import { spawn } from 'node:child_process';
import { createHash } from 'node:crypto';
import {
  cpSync, existsSync, mkdirSync, readFileSync, readdirSync, rmSync, statSync, writeFileSync,
} from 'node:fs';
import { createServer } from 'node:http';
import { createConnection, createServer as createTcpServer } from 'node:net';
import { arch, platform, release, tmpdir } from 'node:os';
import { dirname, isAbsolute, join, relative, resolve, sep } from 'node:path';
import { createInterface } from 'node:readline';
import { fileURLToPath, pathToFileURL } from 'node:url';

// ─────────────────────────────────────────────────────────────────────────────
// Constants
// ─────────────────────────────────────────────────────────────────────────────

const HARNESS_FILE = fileURLToPath(import.meta.url);
const REPO_ROOT = resolve(dirname(HARNESS_FILE), '..', '..');

/** Variables the committed spike proved cause `sdk_invalid_args` when inherited. */
const SDK_SCRUB_KEYS = [
  'QODER_AGENT_SDK_ENTRYPOINT',
  'QODER_WORKER_RUNTIME_ASSET_ROOT',
  'QODER_AGENT_SDK_VERSION',
  'QODERCLI_RUNTIME_PACKAGING',
  'QODER_SESSION_TYPE',
  'QODER_WORKER_CWD',
  'QODER_SDK_AUTH_PAYLOAD_FILE',
];

/** Inherited variables that look like ambient credentials and must not leak in. */
const CREDENTIAL_SHAPED_ENV = /(TOKEN|PASSWORD|SECRET|CREDENTIAL|API_?KEY|_PAT$|^PAT$|_PAT_)/i;

/** Required checks, in gate order. Kept in sync with capability-gate.mjs. */
const CHECK_NAMES = [
  'pauseResume',
  'handshake',
  'managedAuth',
  'isolatedConfig',
  'allowOnce',
  'denyWithoutWrite',
  'cancel',
  'writersStopped',
  'stableExport',
];

/** The only model ID a Qoder probe may request. */
const QODER_PINNED_MODEL = 'efficient';

const DEFAULT_SANDBOX_ENDPOINT = 'http://127.0.0.1:8090';
const MARKER_MCP_PORT_HINT = 19123;

const REDACTED = '[REDACTED-SECRET]';
const SENSITIVE_KEY = /(token|secret|password|apikey|api_key|authorization|credential)/i;
const TOKEN_LIKE = /\b(pt|sk|pat|ghp|gho|github_pat|xoxb|xoxp|eyJ)[-_A-Za-z0-9]{12,}\b/g;
const EMAIL_LIKE = /[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}/g;

/**
 * Windows user-path forms at every JSON-escape level that can occur (1, 2, 4 or
 * 8 backslashes per separator - a path inside nested wire strings, e.g. a
 * tool-call argument that is itself a JSON string, carries one extra level of
 * escaping per nesting layer), applied most-escaped first.
 */
const USER_PATH_RULES = [
  ...[8, 4, 2, 1].map((level) => [
    new RegExp(`[A-Za-z]:\\\\{${level}}Users\\\\{${level}}[^\\\\\\s"']+`, 'g'),
    `C:${'\\'.repeat(level)}Users${'\\'.repeat(level)}<USER>`,
  ]),
  [/[A-Za-z]:\/Users\/[^/\s"']+/g, 'C:/Users/<USER>'], // forward-slash drive paths
];

/**
 * POSIX home paths. The lookbehind keeps Windows drive paths out of this rule:
 * without it `C:/Users/<name>/...` would be cut down to `C:<HOME>/...`, leaving
 * the rest of the path (including temp sub-directories) in the output.
 */
const HOME_PATH_RULE = [/(?<![A-Za-z]:)\/(?:home|Users)\/[^/\s"']+/g, '<HOME>'];

// ─────────────────────────────────────────────────────────────────────────────
// Small utilities
// ─────────────────────────────────────────────────────────────────────────────

const sleep = (ms) => new Promise((done) => setTimeout(done, ms));

function sha256File(file) {
  return createHash('sha256').update(readFileSync(file)).digest('hex');
}

function sha256Text(text) {
  return createHash('sha256').update(text, 'utf8').digest('hex');
}

function relFromRoot(file) {
  const rel = relative(REPO_ROOT, file);
  return rel.startsWith('..') ? file : rel.split(sep).join('/');
}

function nowIso() {
  return new Date().toISOString();
}

/**
 * Deterministic snapshot of every file under `dir` (path + size + sha256),
 * sorted by path. Used as independent evidence whenever "nothing changed"
 * must be proven from bytes rather than from native success text.
 */
function snapshotTree(dir) {
  const entries = [];
  const walk = (current, prefix) => {
    for (const name of readdirSync(current, { withFileTypes: true })) {
      if (name.name === 'node_modules') continue;
      const full = join(current, name.name);
      const rel = prefix ? `${prefix}/${name.name}` : name.name;
      if (name.isDirectory()) walk(full, rel);
      else {
        const bytes = readFileSync(full);
        entries.push({ path: rel, size: bytes.length, sha256: createHash('sha256').update(bytes).digest('hex') });
      }
    }
  };
  walk(dir, '');
  return entries.sort((a, b) => a.path.localeCompare(b.path));
}

function usage() {
  return `usage: node ${relFromRoot(HARNESS_FILE)} --core <qoder|opencode> --mode <HOST|SANDBOX> ` +
    `--workspace <abs disposable dir> --output <abs json file> [options]\n` +
    `  --matrix-out <file>        upsert this row into a capability matrix JSON\n` +
    `  --recording-dir <dir>      where sanitized recordings are frozen\n` +
    `  --check <a,b,...>          run only these checks\n` +
    `  --cli <abs path>           qodercli executable\n` +
    `  --pat-file <abs path>      file holding the Qoder PAT (never printed)\n` +
    `  --opencode <abs path>      opencode executable or npm shim\n` +
    `  --image <ref>              sandbox image reference under test\n` +
    `  --sandbox-endpoint <url>   OpenSandbox server endpoint\n` +
    `  --overwrite-verified       allow a partial --check run to overwrite an\n` +
    `                             existing verified matrix row (refused by default)\n` +
    `  --model <id>               pinned model (qoder requires '${QODER_PINNED_MODEL}')\n` +
    `  --profile-seed <abs dir>   explicit input copied into the run-owned HOME (e.g. a\n` +
    `                             dir holding .qoder/.models so the pinned model catalog\n` +
    `                             is available without inheriting the whole profile)\n` +
    `  --run-dir <abs dir>        scratch dir for control/recording staging\n` +
    `  --timeout-ms <n>           per-request protocol timeout\n`;
}

function parseArgs(argv) {
  const opts = {
    checks: null,
    model: QODER_PINNED_MODEL,
    sandboxEndpoint: DEFAULT_SANDBOX_ENDPOINT,
    image: null,
    timeoutMs: 120000,
    overwriteVerified: false,
  };
  const flags = new Map([
    ['--core', 'core'], ['--mode', 'mode'], ['--workspace', 'workspace'], ['--output', 'output'],
    ['--matrix-out', 'matrixOut'], ['--recording-dir', 'recordingDir'], ['--check', 'checksRaw'],
    ['--cli', 'cli'], ['--pat-file', 'patFile'], ['--opencode', 'opencode'], ['--image', 'image'],
    ['--sandbox-endpoint', 'sandboxEndpoint'], ['--model', 'model'], ['--run-dir', 'runDir'],
    ['--profile-seed', 'profileSeed'], ['--timeout-ms', 'timeoutMs'],
    ['--overwrite-verified', 'overwriteVerified'],
  ]);
  const booleanFlags = new Set(['--overwrite-verified']);
  for (let i = 0; i < argv.length; i += 1) {
    const key = flags.get(argv[i]);
    if (!key) throw new UsageError(`unknown argument ${argv[i]}`);
    if (booleanFlags.has(argv[i])) { opts[key] = true; continue; }
    const value = argv[i + 1];
    if (value === undefined || value.startsWith('--')) throw new UsageError(`${argv[i]} needs a value`);
    opts[key] = key === 'timeoutMs' ? Number(value) : value;
    i += 1;
  }
  if (opts.checksRaw) {
    opts.checks = opts.checksRaw.split(',').map((c) => c.trim()).filter(Boolean);
    for (const check of opts.checks) {
      if (!CHECK_NAMES.includes(check)) throw new UsageError(`unknown check ${check}`);
    }
  }
  return opts;
}

class UsageError extends Error {}

// ─────────────────────────────────────────────────────────────────────────────
// Sanitization
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Every literal form a path can take in an output stream: raw, with forward
 * slashes, and JSON-escaped at 2x, 4x and 8x backslashes for the nesting levels
 * of string-in-string wire payloads. Longest first so a form is never partially
 * consumed by a shorter one.
 */
function pathForms(from) {
  const forms = new Set([from, from.split('\\').join('/')]);
  let escaped = from;
  for (let level = 0; level < 3; level += 1) {
    escaped = escaped.split('\\').join('\\\\');
    forms.add(escaped);
  }
  return [...forms].filter(Boolean).sort((a, b) => b.length - a.length);
}

/** Exported for the sanitization self-test (see the fix-round report). */
export function buildRedactor({ secret, extraExact = [], replacements = [] }) {
  const rules = [...replacements];
  return function redact(input) {
    if (input === undefined || input === null) return input;
    let text = String(input);
    if (secret) text = text.split(secret).join(REDACTED);
    for (const exact of extraExact) {
      if (exact) text = text.split(exact).join(REDACTED);
    }
    for (const [from, to] of rules) {
      for (const form of pathForms(from)) text = text.split(form).join(to);
    }
    text = text.replace(TOKEN_LIKE, REDACTED);
    text = text.replace(EMAIL_LIKE, '<EMAIL>');
    for (const [pattern, replacement] of USER_PATH_RULES) text = text.replace(pattern, replacement);
    text = text.replace(HOME_PATH_RULE[0], HOME_PATH_RULE[1]);
    return text;
  };
}

/**
 * Redacts sensitive string values (token/secret/password/...) inside parsed
 * JSON. Every other string still goes through the full text redactor, so
 * operator paths, identities and e-mail addresses cannot reach a fixture just
 * because they happened to sit under an innocuous key.
 */
function redactJsonValue(value, key, redact) {
  if (typeof value === 'string') {
    if (key && SENSITIVE_KEY.test(key)) return value === '' ? value : REDACTED;
    return redact ? redact(value) : value;
  }
  if (Array.isArray(value)) return value.map((item) => redactJsonValue(item, null, redact));
  if (value && typeof value === 'object') {
    return Object.fromEntries(Object.entries(value).map(([k, v]) => [k, redactJsonValue(v, k, redact)]));
  }
  return value;
}

function redactStructured(input, redact) {
  if (typeof input === 'string') return redact(input);
  return redactJsonValue(input, null, redact);
}

// ─────────────────────────────────────────────────────────────────────────────
// Workspace guard
// ─────────────────────────────────────────────────────────────────────────────

function isGitRepoRoot(dir) {
  return existsSync(join(dir, '.git'));
}

function ancestorsContainGit(dir) {
  let current = resolve(dir);
  for (;;) {
    if (isGitRepoRoot(current)) return current;
    const parent = dirname(current);
    if (parent === current) return null;
    current = parent;
  }
}

/**
 * Refuses any workspace that is not a disposable directory: it must be absolute,
 * must not be inside this checkout, must not contain this checkout, must not be
 * a git working tree (or inside one), and must not be a filesystem root or the
 * user's home directory.
 */
function assertDisposableWorkspace(dir) {
  if (!isAbsolute(dir)) throw new UsageError('--workspace must be an absolute path');
  const resolved = resolve(dir);
  const parsed = resolved.split(sep).filter(Boolean);
  if (parsed.length <= 1) throw new UsageError('--workspace must not be a filesystem root');
  const inRepo = resolved === REPO_ROOT || resolved.startsWith(REPO_ROOT + sep);
  const containsRepo = REPO_ROOT.startsWith(resolved + sep);
  if (inRepo || containsRepo) {
    throw new UsageError('--workspace must be outside the operator checkout (refusing ' + resolved + ')');
  }
  const home = process.env.USERPROFILE || process.env.HOME;
  if (home && resolve(home) === resolved) throw new UsageError('--workspace must not be the user home directory');
  if (!existsSync(resolved)) mkdirSync(resolved, { recursive: true });
  if (!statSync(resolved).isDirectory()) throw new UsageError('--workspace is not a directory');
  const gitRoot = ancestorsContainGit(resolved);
  if (gitRoot) {
    throw new UsageError(`--workspace is inside a git working tree (${gitRoot}); probes never run against a real repository`);
  }
  return resolved;
}

// ─────────────────────────────────────────────────────────────────────────────
// Process helpers
// ─────────────────────────────────────────────────────────────────────────────

function runProcess(exe, args, { cwd, env, timeoutMs = 60000, input } = {}) {
  return new Promise((done) => {
    const child = spawn(exe, args, { cwd, env, windowsHide: true, stdio: ['pipe', 'pipe', 'pipe'] });
    let stdout = '';
    let stderr = '';
    let timedOut = false;
    const timer = setTimeout(() => { timedOut = true; child.kill(); }, timeoutMs);
    child.stdout.on('data', (d) => { stdout += d; });
    child.stderr.on('data', (d) => { stderr += d; });
    child.on('error', (error) => { clearTimeout(timer); done({ code: -1, stdout, stderr: String(error), timedOut }); });
    child.on('close', (code) => { clearTimeout(timer); done({ code, stdout, stderr, timedOut }); });
    if (input !== undefined) child.stdin.end(input);
    else child.stdin.end();
  });
}

function processAlive(pid) {
  try {
    process.kill(pid, 0);
    return true;
  } catch {
    return false;
  }
}

function reservePort() {
  return new Promise((done) => {
    const server = createTcpServer();
    server.listen(0, '127.0.0.1', () => {
      const { port } = server.address();
      server.close(() => done(port));
    });
  });
}

function tcpProbe(host, port, timeoutMs = 4000) {
  return new Promise((done) => {
    const socket = createConnection({ host, port });
    const finish = (result) => { socket.destroy(); done(result); };
    const timer = setTimeout(() => finish({ reachable: false, reason: 'timeout' }), timeoutMs);
    socket.on('connect', () => { clearTimeout(timer); finish({ reachable: true }); });
    socket.on('error', (error) => { clearTimeout(timer); finish({ reachable: false, reason: error.code || error.message }); });
  });
}

function httpJson(method, url, body, timeoutMs = 15000) {
  return new Promise((done) => {
    const target = new URL(url);
    const payload = body === undefined ? null : JSON.stringify(body);
    const client = target.protocol === 'https:' ? import('node:https') : import('node:http');
    client.then((mod) => {
      const req = mod.request({
        method,
        hostname: target.hostname,
        port: target.port,
        path: target.pathname + target.search,
        headers: payload ? { 'Content-Type': 'application/json', 'Content-Length': Buffer.byteLength(payload) } : {},
      }, (res) => {
        let text = '';
        res.on('data', (chunk) => { text += chunk; });
        res.on('end', () => {
          let json = null;
          try { json = JSON.parse(text); } catch { /* keep raw text */ }
          done({ status: res.statusCode, headers: res.headers, json, text: text.slice(0, 4000) });
        });
      });
      const timer = setTimeout(() => { req.destroy(); done({ status: 0, error: 'timeout' }); }, timeoutMs);
      req.on('error', (error) => { clearTimeout(timer); done({ status: 0, error: error.code || error.message }); });
      req.on('close', () => clearTimeout(timer));
      if (payload) req.write(payload);
      req.end();
    });
  });
}

// ─────────────────────────────────────────────────────────────────────────────
// OS-owned process control (Windows job object + suspend/resume)
// ─────────────────────────────────────────────────────────────────────────────

/** Exported for the standalone supervisor smoke test. */
export const SUPERVISOR_PS1 = String.raw`param()
$ErrorActionPreference = 'Stop'
$src = @'
using System;
using System.Runtime.InteropServices;
public static class AriaProc {
  [DllImport("kernel32.dll", SetLastError=true, CharSet=CharSet.Unicode)]
  public static extern IntPtr CreateJobObject(IntPtr a, string n);
  [DllImport("kernel32.dll", SetLastError=true)]
  public static extern bool AssignProcessToJobObject(IntPtr j, IntPtr p);
  [DllImport("kernel32.dll", SetLastError=true)]
  public static extern bool TerminateJobObject(IntPtr j, uint code);
  [DllImport("kernel32.dll", SetLastError=true)]
  public static extern bool SetInformationJobObject(IntPtr j, int cls, IntPtr info, uint len);
  [DllImport("kernel32.dll", SetLastError=true)]
  public static extern bool QueryInformationJobObject(IntPtr j, int cls, IntPtr info, uint len, IntPtr ret);
  [DllImport("kernel32.dll", SetLastError=true)]
  public static extern IntPtr OpenProcess(uint access, bool inherit, int pid);
  [DllImport("kernel32.dll", SetLastError=true)]
  public static extern bool CloseHandle(IntPtr h);
  [DllImport("kernel32.dll", SetLastError=true)]
  public static extern bool IsProcessInJob(IntPtr p, IntPtr j, out bool inJob);
  [DllImport("kernel32.dll", SetLastError=true)]
  public static extern bool TerminateProcess(IntPtr h, uint code);
  [DllImport("ntdll.dll")] public static extern int NtSuspendProcess(IntPtr h);
  [DllImport("ntdll.dll")] public static extern int NtResumeProcess(IntPtr h);

  const int ExtLimit = 9;
  const int BasicPidList = 3;
  const uint KillOnClose = 0x2000;
  const uint Access = 0x0001 | 0x0100 | 0x0400 | 0x0800 | 0x1000;

  public static IntPtr CreateJob() {
    IntPtr h = CreateJobObject(IntPtr.Zero, null);
    if (h == IntPtr.Zero) throw new Exception("CreateJobObject failed " + Marshal.GetLastWin32Error());
    int len = Marshal.SizeOf(typeof(INFO));
    IntPtr p = Marshal.AllocHGlobal(len);
    try {
      INFO info = new INFO();
      info.Basic.LimitFlags = KillOnClose;
      Marshal.StructureToPtr(info, p, false);
      if (!SetInformationJobObject(h, ExtLimit, p, (uint)len))
        throw new Exception("SetInformationJobObject failed " + Marshal.GetLastWin32Error());
    } finally { Marshal.FreeHGlobal(p); }
    return h;
  }

  public static string Assign(IntPtr j, int pid) {
    IntPtr hp = OpenProcess(Access, false, pid);
    if (hp == IntPtr.Zero) return "open_failed:" + Marshal.GetLastWin32Error();
    try {
      return AssignProcessToJobObject(j, hp) ? "ok" : "assign_failed:" + Marshal.GetLastWin32Error();
    } finally { CloseHandle(hp); }
  }

  public static int[] JobPids(IntPtr j) {
    int len = 64 * 1024;
    IntPtr p = Marshal.AllocHGlobal(len);
    try {
      if (!QueryInformationJobObject(j, BasicPidList, p, (uint)len, IntPtr.Zero)) return new int[0];
      int count = Marshal.ReadInt32(p, 0);
      int[] pids = new int[count];
      for (int i = 0; i < count; i++) pids[i] = Marshal.ReadInt32(p, 8 + i * IntPtr.Size);
      return pids;
    } finally { Marshal.FreeHGlobal(p); }
  }

  public static string InJob(int pid) {
    IntPtr hp = OpenProcess(Access, false, pid);
    if (hp == IntPtr.Zero) return "open_failed:" + Marshal.GetLastWin32Error();
    try {
      bool inJob;
      if (!IsProcessInJob(hp, IntPtr.Zero, out inJob)) return "query_failed:" + Marshal.GetLastWin32Error();
      return inJob ? "in_job" : "not_in_job";
    } finally { CloseHandle(hp); }
  }

  public static string Susp(int pid) {
    IntPtr hp = OpenProcess(Access, false, pid);
    if (hp == IntPtr.Zero) return "open_failed:" + Marshal.GetLastWin32Error();
    try { return NtSuspendProcess(hp) == 0 ? "ok" : "nt_failed"; } finally { CloseHandle(hp); }
  }
  public static string Resm(int pid) {
    IntPtr hp = OpenProcess(Access, false, pid);
    if (hp == IntPtr.Zero) return "open_failed:" + Marshal.GetLastWin32Error();
    try { return NtResumeProcess(hp) == 0 ? "ok" : "nt_failed"; } finally { CloseHandle(hp); }
  }

  public static string Term(int pid) {
    IntPtr hp = OpenProcess(Access, false, pid);
    if (hp == IntPtr.Zero) return "open_failed:" + Marshal.GetLastWin32Error();
    try { return TerminateProcess(hp, 1) ? "ok" : "terminate_failed:" + Marshal.GetLastWin32Error(); } finally { CloseHandle(hp); }
  }

  [StructLayout(LayoutKind.Sequential)]
  public struct BASIC { public long A; public long B; public uint LimitFlags; public UIntPtr C; public UIntPtr D;
    public uint E; public UIntPtr F; public uint G; public uint H; }
  [StructLayout(LayoutKind.Sequential)]
  public struct IO { public ulong A, B, C, D, E, F; }
  [StructLayout(LayoutKind.Sequential)]
  public struct INFO { public BASIC Basic; public IO Io; public UIntPtr A; public UIntPtr B; public UIntPtr C; public UIntPtr D; }
}
'@
Add-Type -TypeDefinition $src -Language CSharp
$job = [AriaProc]::CreateJob()

function Get-TreePids([int]$root) {
  $pids = @()
  foreach ($item in (Get-TreeInfo $root)) { $pids += [int]$item.ProcessId }
  # ,$pids keeps the empty case an array instead of $null (a $null pid list
  # broke [array]::Reverse with "Value cannot be null" in killtree).
  return ,$pids
}

function To-JsonList([string[]]$items) {
  if ($items.Count -eq 0) { return '[]' }
  return '["' + ($items -join '","') + '"]'
}

function Get-TreeInfo([int]$root) {
  $procs = Get-CimInstance Win32_Process | Select-Object ProcessId, ParentProcessId, CreationDate
  $children = @{}
  foreach ($p in $procs) {
    $pp = [int]$p.ParentProcessId
    if (-not $children.ContainsKey($pp)) { $children[$pp] = @() }
    $children[$pp] += [int]$p.ProcessId
  }
  $out = @()
  $queue = New-Object System.Collections.Queue
  $queue.Enqueue($root)
  $seen = @{}
  while ($queue.Count -gt 0) {
    $cur = [int]$queue.Dequeue()
    if ($seen.ContainsKey($cur)) { continue }
    $seen[$cur] = $true
    $match = $procs | Where-Object { [int]$_.ProcessId -eq $cur }
    if ($match) {
      $out += [pscustomobject]@{ ProcessId = $cur; Created = ([datetime]$match.CreationDate).ToString('yyyyMMddHHmmssfff') }
    }
    if ($children.ContainsKey($cur)) { foreach ($c in $children[$cur]) { $queue.Enqueue($c) } }
  }
  return $out
}

function Test-Record([string]$record) {
  $parts = $record.Split('@')
  $targetPid = [int]$parts[0]
  $created = if ($parts.Count -gt 1) { $parts[1] } else { '' }
  $p = Get-CimInstance Win32_Process -Filter "ProcessId=$targetPid" -ErrorAction SilentlyContinue
  if ($null -eq $p) { return 'gone' }
  $now = ([datetime]$p.CreationDate).ToString('yyyyMMddHHmmssfff')
  if ($created -eq '' -or $now -eq $created) { return 'match' }
  return ('mismatch:' + $now)
}

function Invoke-List([string]$records, [string]$action) {
  $results = @()
  foreach ($record in ($records.Split(',') | Where-Object { $_ -ne '' })) {
    $verdict = Test-Record $record
    if ($verdict -ne 'match') { $results += ('{0}:{1}' -f $record, $verdict); continue }
    $targetPid = [int]($record.Split('@')[0])
    $applied = if ($action -eq 'suspend') { [AriaProc]::Susp($targetPid) }
      elseif ($action -eq 'resume') { [AriaProc]::Resm($targetPid) }
      else { [AriaProc]::Term($targetPid) }
    $results += ('{0}:{1}' -f $record, $applied)
  }
  return $results
}

[Console]::Out.WriteLine('{"event":"ready"}')
[Console]::Out.Flush()
while ($true) {
  $line = [Console]::In.ReadLine()
  if ($null -eq $line) { break }
  $line = $line.Trim()
  if ($line -eq '') { continue }
  try {
    $parts = $line.Split(' ', 2)
    $cmd = $parts[0]
    $arg = if ($parts.Count -gt 1) { $parts[1] } else { '' }
    switch ($cmd) {
      'assign'     { $r = '{"ok":true,"result":"' + [AriaProc]::Assign($job, [int]$arg) + '"}' }
      'assigntree' {
        $root = [int]$arg
        $procs = Get-CimInstance Win32_Process | Select-Object ProcessId, ParentProcessId
        $map = @{}
        foreach ($p in $procs) {
          $pp = [int]$p.ParentProcessId
          if (-not $map.ContainsKey($pp)) { $map[$pp] = @() }
          $map[$pp] += [int]$p.ProcessId
        }
        $queue = New-Object System.Collections.Queue
        $queue.Enqueue($root)
        $results = @()
        $seen = @{}
        while ($queue.Count -gt 0) {
          $cur = [int]$queue.Dequeue()
          if ($seen.ContainsKey($cur)) { continue }
          $seen[$cur] = $true
          $results += ('{0}:{1}' -f $cur, [AriaProc]::Assign($job, $cur))
          if ($map.ContainsKey($cur)) { foreach ($c in $map[$cur]) { $queue.Enqueue($c) } }
        }
        $r = '{"ok":true,"results":["' + ($results -join '","') + '"]}'
      }
      'pids'    { $p = [AriaProc]::JobPids($job); $r = '{"ok":true,"pids":[' + ($p -join ',') + ']}' }
      'injob'   { $r = '{"ok":true,"status":"' + [AriaProc]::InJob([int]$arg) + '"}' }
      'tree'    { $p = Get-TreePids ([int]$arg); $r = '{"ok":true,"pids":[' + ($p -join ',') + ']}' }
      'treeinfo' {
        $items = @()
        foreach ($p in (Get-TreeInfo ([int]$arg))) { $items += ('{"pid":' + $p.ProcessId + ',"created":"' + $p.Created + '"}') }
        $r = '{"ok":true,"procs":[' + ($items -join ',') + ']}'
      }
      'suspendlist' { $res = Invoke-List $arg 'suspend'; $r = '{"ok":true,"results":["' + ($res -join '","') + '"]}' }
      'resumelist'  { $res = Invoke-List $arg 'resume'; $r = '{"ok":true,"results":["' + ($res -join '","') + '"]}' }
      'killlist'    { $res = Invoke-List $arg 'kill'; $r = '{"ok":true,"results":["' + ($res -join '","') + '"]}' }
      'suspendtree' {
        $res = @()
        foreach ($p in (Get-TreePids ([int]$arg))) { $res += ('{0}:{1}' -f $p, [AriaProc]::Susp($p)) }
        $r = '{"ok":true,"results":["' + ($res -join '","') + '"]}'
      }
      'resumetree' {
        $res = @()
        foreach ($p in (Get-TreePids ([int]$arg))) { $res += ('{0}:{1}' -f $p, [AriaProc]::Resm($p)) }
        $r = '{"ok":true,"results":["' + ($res -join '","') + '"]}'
      }
      'killtree' {
        # Direct assignment keeps the array from Get-TreePids (the ,$return
        # idiom would be nested by @()); the null guard covers an empty tree,
        # which previously reached [array]::Reverse as $null.
        $pids = Get-TreePids ([int]$arg)
        if ($null -eq $pids) { $pids = @() }
        if ($pids.Count -gt 1) { [array]::Reverse($pids) }
        $res = @()
        foreach ($p in $pids) { $res += ('{0}:{1}' -f $p, [AriaProc]::Term($p)) }
        $after = Get-TreePids ([int]$arg)
        if ($null -eq $after) { $after = @() }
        $res2 = @()
        foreach ($p in $after) { $res2 += ('{0}:{1}' -f $p, [AriaProc]::Term($p)) }
        $r = '{"ok":true,"terminated":' + (To-JsonList $res) + ',"resweep":' + (To-JsonList $res2) + '}'
      }
      'suspend' { $res = @(); foreach ($p in [AriaProc]::JobPids($job)) { $res += [AriaProc]::Susp($p) }; $r = '{"ok":true,"results":["' + ($res -join '","') + '"]}' }
      'resume'  { $res = @(); foreach ($p in [AriaProc]::JobPids($job)) { $res += [AriaProc]::Resm($p) }; $r = '{"ok":true,"results":["' + ($res -join '","') + '"]}' }
      'kill'    { [AriaProc]::TerminateJobObject($job, 1) | Out-Null; $r = '{"ok":true}' }
      'exit'    { break }
      default   { $r = '{"ok":false,"error":"unknown command"}' }
    }
  } catch { $r = '{"ok":false,"error":"' + (($_.Exception.Message) -replace '"','') + '"}' }
  [Console]::Out.WriteLine($r)
  [Console]::Out.Flush()
}
`;

/** Windows job-object supervision: ownership, suspend/resume and bounded tree termination. */
export class JobSupervisor {
  constructor(runDir, record) {
    this.runDir = runDir;
    this.record = record;
    this.pending = [];
    this.child = null;
  }

  static powershellPath() {
    const candidates = [
      join(process.env.SystemRoot || 'C:\\Windows', 'System32', 'WindowsPowerShell', 'v1.0', 'powershell.exe'),
      'powershell.exe',
    ];
    return candidates.find((c) => c === 'powershell.exe' || existsSync(c)) || null;
  }

  static supported() {
    return platform() === 'win32' && JobSupervisor.powershellPath() !== null;
  }

  async start() {
    const script = join(this.runDir, 'control', 'proc-supervisor.ps1');
    mkdirSync(dirname(script), { recursive: true });
    writeFileSync(script, SUPERVISOR_PS1, 'utf8');
    this.child = spawn(
      JobSupervisor.powershellPath(),
      ['-NoProfile', '-NonInteractive', '-ExecutionPolicy', 'Bypass', '-File', script],
      { stdio: ['pipe', 'pipe', 'pipe'], windowsHide: true },
    );
    createInterface({ input: this.child.stdout }).on('line', (line) => {
      const waiter = this.pending.shift();
      if (waiter) waiter(line);
    });
    this.stderr = '';
    this.child.stderr.on('data', (d) => { this.stderr += d; });
    const ready = await this.command('__noop__').catch(() => null);
    void ready;
    this.record.event('control.supervisor_started', { technique: 'windows-job-object + NtSuspendProcess/NtResumeProcess' });
  }

  command(line, timeoutMs = 20000) {
    return new Promise((done, fail) => {
      const timer = setTimeout(() => fail(new Error(`supervisor timeout on: ${line}`)), timeoutMs);
      this.pending.push((response) => { clearTimeout(timer); done(response); });
      this.child.stdin.write(line + '\n');
    });
  }

  async json(line) {
    const response = await this.command(line);
    try { return JSON.parse(response); } catch { return { ok: false, error: `unparseable: ${response}` }; }
  }

  inJob(pid) { return this.json(`injob ${pid}`); }

  /** All descendants of `pid` (inclusive) from the OS parent map. */
  tree(pid) { return this.json(`tree ${pid}`); }

  /** Descendants with creation timestamps, for identity-validated control. */
  treeInfo(pid) { return this.json(`treeinfo ${pid}`, 40000); }

  /** Suspends only processes whose creation time still matches the recorded one. */
  suspendList(records) {
    if (!records || records.length === 0) return { ok: true, results: [] };
    return this.json(`suspendlist ${records.join(',')}`, 40000);
  }

  resumeList(records) {
    if (!records || records.length === 0) return { ok: true, results: [] };
    return this.json(`resumelist ${records.join(',')}`, 40000);
  }

  killList(records) {
    if (!records || records.length === 0) return { ok: true, results: [] };
    return this.json(`killlist ${records.join(',')}`, 40000);
  }

  suspendTree(pid) { return this.json(`suspendtree ${pid}`); }

  resumeTree(pid) { return this.json(`resumetree ${pid}`); }

  killTree(pid) { return this.json(`killtree ${pid}`, 40000); }

  async assign(pid) { return this.json(`assign ${pid}`); }

  /**
   * Assigns a process and its current descendants, retrying the root: a freshly
   * spawned process can transiently refuse assignment while it initialises, and
   * the root MUST be in the job or later descendants escape supervision.
   */
  async assignRootWithRetry(pid, { attempts = 10, delayMs = 400 } = {}) {
    const tried = [];
    let rootAssigned = false;
    const membershipBefore = { harness: await this.inJob(process.pid), target: await this.inJob(pid) };
    for (let attempt = 1; attempt <= attempts && !rootAssigned; attempt += 1) {
      const result = await this.assign(pid);
      tried.push({ attempt, result: result.result ?? result.error ?? result });
      rootAssigned = result.result === 'ok';
      if (!rootAssigned) await sleep(delayMs);
    }
    const tree = await this.assignTree(pid);
    const pids = (await this.pids()).pids ?? [];
    return { rootAssigned, membershipBefore, attempts: tried, tree, pids, rootInJob: pids.includes(pid) };
  }

  assignTree(pid) { return this.json(`assigntree ${pid}`); }

  pids() { return this.json('pids'); }

  suspend() { return this.json('suspend'); }

  resume() { return this.json('resume'); }

  kill() { return this.json('kill'); }

  async stop() {
    if (!this.child) return;
    try { await this.json('exit', 5000); } catch { /* supervisor already gone */ }
    this.child.kill();
  }
}

// ─────────────────────────────────────────────────────────────────────────────
// Recording
// ─────────────────────────────────────────────────────────────────────────────

class Recording {
  constructor({ name, redact, log }) {
    this.name = name;
    this.redact = redact;
    this.log = log;
    this.messages = [];
    this.events = [];
    this.startedAt = nowIso();
  }

  message(direction, raw, meta = {}) {
    this.messages.push({
      seq: this.messages.length + 1,
      at: nowIso(),
      direction,
      ...(meta.method ? { method: meta.method } : {}),
      ...(meta.id !== undefined ? { id: meta.id } : {}),
      raw: this.redact(raw),
    });
  }

  event(type, data = {}) {
    const entry = { at: nowIso(), type, data: redactStructured(data, this.redact) };
    this.events.push(entry);
    this.log(`  · ${type} ${JSON.stringify(entry.data).slice(0, 220)}`);
  }

  toJsonl() {
    return this.messages.map((m) => JSON.stringify(m)).join('\n') + '\n';
  }

  toJson() {
    return JSON.stringify({
      schemaVersion: 1,
      probe: 'e2e/agent-core/probe-native.mjs',
      name: this.name,
      startedAt: this.startedAt,
      finishedAt: nowIso(),
      messages: this.messages,
      events: this.events,
    }, null, 2) + '\n';
  }
}

// ─────────────────────────────────────────────────────────────────────────────
// ACP client (Qoder)
// ─────────────────────────────────────────────────────────────────────────────

class AcpClient {
  constructor({ exe, args, cwd, env, record, timeoutMs, log }) {
    this.exe = exe;
    this.args = args;
    this.cwd = cwd;
    this.env = env;
    this.record = record;
    this.timeoutMs = timeoutMs;
    this.log = log;
    this.pending = new Map();
    this.waiters = [];
    this.updates = [];
    this.permissionRequests = [];
    this.nextId = 1;
    this.child = null;
    this.stderr = '';
    this.permissionHandler = null;
    this.closed = false;
  }

  async start() {
    this.child = spawn(this.exe, this.args, {
      cwd: this.cwd, env: this.env, stdio: ['pipe', 'pipe', 'pipe'], windowsHide: true,
    });
    this.record.event('acp.spawn', { exe: this.exe, args: this.args, cwd: this.cwd, pid: this.child.pid });
    this.child.stderr.on('data', (d) => {
      this.stderr += d;
      if (this.stderr.length > 20000) this.stderr = this.stderr.slice(-20000);
    });
    createInterface({ input: this.child.stdout }).on('line', (line) => this.onLine(line));
    this.child.on('close', (code) => {
      this.closed = true;
      this.record.event('acp.exit', { code });
      for (const [, { reject, timer }] of this.pending) { clearTimeout(timer); reject(new Error('process exited')); }
      this.pending.clear();
    });
  }

  onLine(line) {
    let message;
    try { message = JSON.parse(line); } catch {
      this.record.message('in', line, {});
      this.record.event('acp.unparsed_line', { line: line.slice(0, 200) });
      return;
    }
    this.record.message('in', line, { method: message.method, id: message.id });
    if (message.id !== undefined && (message.result !== undefined || message.error !== undefined)) {
      const waiter = this.pending.get(message.id);
      if (waiter) {
        this.pending.delete(message.id);
        clearTimeout(waiter.timer);
        if (message.error) waiter.reject(new Error(JSON.stringify(message.error)));
        else waiter.resolve(message.result);
      }
      return;
    }
    if (message.method === 'session/update') {
      const update = message.params?.update ?? {};
      this.updates.push({ at: nowIso(), update, raw: message });
      this.emit({ kind: 'update', update });
      return;
    }
    if (message.method === 'session/request_permission') {
      const entry = { at: nowIso(), params: message.params, id: message.id, answered: false, reply: null };
      this.permissionRequests.push(entry);
      this.emit({ kind: 'permission', request: entry });
      return;
    }
    this.emit({ kind: 'other', message });
  }

  emit(event) {
    for (const waiter of [...this.waiters]) waiter(event);
  }

  waitFor(predicate, timeoutMs) {
    return new Promise((done, fail) => {
      const waiter = (event) => {
        if (predicate(event)) {
          this.waiters = this.waiters.filter((w) => w !== waiter);
          clearTimeout(timer);
          done(event);
        }
      };
      const timer = setTimeout(() => {
        this.waiters = this.waiters.filter((w) => w !== waiter);
        fail(new Error('waitFor timeout'));
      }, timeoutMs);
      this.waiters.push(waiter);
    });
  }

  send(message) {
    const line = JSON.stringify(message);
    this.record.message('out', line, { method: message.method, id: message.id });
    this.child.stdin.write(line + '\n');
  }

  request(method, params, timeoutMs = this.timeoutMs) {
    const id = this.nextId++;
    return new Promise((done, fail) => {
      const timer = setTimeout(() => {
        this.pending.delete(id);
        fail(new Error(`request timeout: ${method}`));
      }, timeoutMs);
      this.pending.set(id, { resolve: done, reject: fail, timer, method });
      this.send({ jsonrpc: '2.0', id, method, params });
    });
  }

  notify(method, params) {
    this.send({ jsonrpc: '2.0', method, params });
  }

  answerPermission(entry, optionId) {
    entry.answered = true;
    const result = optionId
      ? { outcome: { outcome: 'selected', optionId } }
      : { outcome: { outcome: 'cancelled' } };
    entry.reply = result;
    this.send({ jsonrpc: '2.0', id: entry.id, result });
  }

  async stop({ killAfterMs = 5000 } = {}) {
    if (!this.child || this.child.exitCode !== null) return;
    const exited = new Promise((done) => this.child.once('close', done));
    try { this.child.stdin.end(); } catch { /* already closed */ }
    const timedOut = await Promise.race([exited.then(() => false), sleep(killAfterMs).then(() => true)]);
    if (timedOut) {
      this.record.event('acp.terminate', { reason: 'did not exit after stdin close' });
      this.child.kill();
    }
  }
}

// ─────────────────────────────────────────────────────────────────────────────
// Probe context
// ─────────────────────────────────────────────────────────────────────────────

class ProbeContext {
  constructor({ opts, row, record, log, runDir, sanitize, redact }) {
    this.opts = opts;
    this.row = row;
    this.record = record;
    this.log = log;
    this.runDir = runDir;
    this.sanitize = sanitize;
    this.redact = redact;
    this.observations = {};
    this.limitations = [];
    this.prerequisites = {};
    this.checks = Object.fromEntries(CHECK_NAMES.map((name) => [name, false]));
    this.checkEvidence = {};
    this.artifacts = [];
    this.cleanups = [];
  }

  wants(check) {
    return !this.opts.checks || this.opts.checks.includes(check);
  }

  /**
   * Single redaction gate for everything this context stores: check evidence,
   * observations and blocked prerequisites all go through the run's redactor
   * before they can reach the row or the matrix, so no call site can leak an
   * operator path, identity or credential-shaped string by forgetting to
   * sanitize by hand.
   */
  store(value) {
    return redactStructured(value, this.redact);
  }

  ok(check, evidence) {
    this.checks[check] = true;
    this.checkEvidence[check] = this.store({ observed: true, ...evidence });
    this.log(`  ✔ ${check}`);
  }

  fail(check, evidence) {
    this.checks[check] = false;
    const stored = this.store({ observed: false, ...evidence });
    this.checkEvidence[check] = stored;
    this.log(`  ✖ ${check} — ${stored.reason || 'not observed'}`);
  }

  block(check, reason) {
    this.fail(check, { reason });
    this.prerequisites[check] = this.store(reason);
  }

  observe(key, value) {
    this.observations[key] = this.store(value);
  }
}

// ─────────────────────────────────────────────────────────────────────────────
// Qoder probes
// ─────────────────────────────────────────────────────────────────────────────

const WRITER_SCRIPT = `import { appendFileSync } from 'node:fs';
const out = process.argv[2];
const deadline = Date.now() + Number(process.argv[3] || 120000);
const timer = setInterval(() => {
  appendFileSync(out, 'tick ' + Date.now() + '\\n');
  if (Date.now() > deadline) { clearInterval(timer); process.exit(0); }
}, 200);
`;

const SPAWN_WRITER_SCRIPT = `import { spawn } from 'node:child_process';
const child = spawn(process.execPath, ['writer.mjs', process.argv[2], process.argv[3] || '180000'],
  { cwd: process.cwd(), detached: true, stdio: 'ignore' });
child.unref();
console.log(JSON.stringify({ writerPid: child.pid }));
// Stay alive for a bounded window so the spawned writer remains reachable from
// the core's owned process tree while the tool call is still in flight.
await new Promise((done) => setTimeout(done, Number(process.argv[4] || 25000)));
`;

function buildChildEnv({ base, pat, runDir, extra = {}, profileSeed = null }) {
  const env = { ...base };
  const scrubbed = [];
  for (const key of Object.keys(env)) {
    if (SDK_SCRUB_KEYS.includes(key) || CREDENTIAL_SHAPED_ENV.test(key)) {
      delete env[key];
      scrubbed.push(key);
    }
  }
  env.HOME = join(runDir, 'home');
  env.USERPROFILE = join(runDir, 'home');
  env.TMP = join(runDir, 'tmp');
  env.TEMP = join(runDir, 'tmp');
  mkdirSync(env.HOME, { recursive: true });
  mkdirSync(env.TMP, { recursive: true });
  if (profileSeed && existsSync(profileSeed)) {
    cpSync(profileSeed, env.HOME, { recursive: true });
  }
  if (pat) env.QODER_PERSONAL_ACCESS_TOKEN = pat;
  for (const [key, value] of Object.entries(extra)) env[key] = value;
  return { env, scrubbed };
}

async function startMarkerMcpServer(record) {
  const server = createServer((req, res) => {
    let body = '';
    req.on('data', (c) => { body += c; });
    req.on('end', () => {
      record.event('isolated.hostile_mcp_connection', { method: req.method, url: req.url, body: body.slice(0, 200) });
      let id = null;
      try { id = JSON.parse(body).id; } catch { /* notification */ }
      res.writeHead(id === null ? 202 : 200, { 'Content-Type': 'application/json' });
      res.end(id === null ? '' : JSON.stringify({ jsonrpc: '2.0', id, result: { protocolVersion: '2024-11-05', capabilities: {}, serverInfo: { name: 'hostile-marker', version: '1' } } }));
    });
  });
  await new Promise((done) => server.listen(0, '127.0.0.1', done));
  const { port } = server.address();
  return { server, port };
}

function qoderAcpArgs({ configDir, mcpConfig }) {
  return [
    '--acp',
    '--config-dir', configDir,
    '--setting-sources', 'project',
    '--strict-mcp-config',
    '--mcp-config', JSON.stringify(mcpConfig),
  ];
}

async function probeQoderHost(ctx, { cliPath, pat, patSource }) {
  const { opts, record, log } = ctx;
  const workspace = opts.workspace;
  const configDir = join(ctx.runDir, 'config');
  const mcpConfig = { mcpServers: {} };
  mkdirSync(configDir, { recursive: true });
  // Run-owned HOME for every child; an explicit --profile-seed may add required
  // read-only inputs such as the pinned model catalog (the CLI resolves its
  // catalog from the profile root, not from --config-dir).
  const childEnv = (patValue) => buildChildEnv({
    base: process.env, pat: patValue, runDir: ctx.runDir, profileSeed: opts.profileSeed,
  });
  if (opts.profileSeed) {
    ctx.observe('profileSeed', {
      source: ctx.sanitize(resolve(opts.profileSeed)),
      purpose: 'explicit input copied into the run-owned HOME (pinned model catalog availability)',
    });
  }

  // Runtime identity -------------------------------------------------------
  const version = await runProcess(cliPath, ['--version'], { timeoutMs: 30000 });
  const runtime = {
    core: 'qoder',
    binary: ctx.sanitize(cliPath),
    sha256: sha256File(cliPath),
    version: version.stdout.trim() || version.stderr.trim(),
    versionSource: 'qodercli --version',
    licenseSource: 'vendor CLI installed by the operator (see progress ledger); no local license file observed',
    credentialSource: ctx.sanitize(patSource),
  };
  ctx.row.runtime = runtime;
  ctx.observe('qoderCliVersion', runtime.version);
  ctx.observe('qoderCliSha256', runtime.sha256);
  log(`  runtime: qodercli ${runtime.version} sha256=${runtime.sha256.slice(0, 12)}…`);

  const supervisorReady = JobSupervisor.supported();
  if (!supervisorReady) {
    ctx.observe('controlTechnique', { supported: false, reason: `platform ${platform()} has no verified job-control implementation in this harness` });
    ctx.block('pauseResume', `no verified OS-owned process control for platform ${platform()}`);
    ctx.block('writersStopped', `no verified OS-level writer termination for platform ${platform()}`);
    ctx.block('stableExport', 'writer quiescence cannot be established without a verified termination technique');
  }

  // handshake --------------------------------------------------------------
  if (ctx.wants('handshake')) {
    const { env, scrubbed } = childEnv(pat);
    ctx.observe('scrubbedEnvKeys', scrubbed.sort());
    const client = new AcpClient({
      exe: cliPath,
      args: qoderAcpArgs({ configDir, mcpConfig }),
      cwd: workspace,
      env,
      record,
      timeoutMs: opts.timeoutMs,
      log,
    });
    await client.start();
    try {
      const init = await client.request('initialize', { protocolVersion: 1 }, 60000);
      const session = await client.request('session/new', { cwd: workspace, mcpServers: [] }, 90000);
      const setModel = await client.request('session/set_model', { sessionId: session.sessionId, modelId: QODER_PINNED_MODEL }, 30000)
        .catch((error) => ({ error: error.message }));
      // A model-bound prompt proves the session is usable, not merely listed.
      let promptResult = null;
      let promptError = null;
      try {
        promptResult = await client.request('session/prompt', {
          sessionId: session.sessionId,
          prompt: [{ type: 'text', text: 'Reply with exactly the single word: pong' }],
        }, opts.timeoutMs);
      } catch (error) { promptError = error.message; }
      const observedModel = promptResult?._meta?.quota?.model_usage?.[0]?.model ?? null;
      const acknowledged = session.models?.currentModelId === QODER_PINNED_MODEL
        && observedModel === QODER_PINNED_MODEL
        && promptError === null;
      const evidence = {
        protocol: {
          transport: 'newline-delimited JSON-RPC over stdio (--acp)',
          argv: qoderAcpArgs({ configDir, mcpConfig }).map((arg) => ctx.sanitize(arg)),
          initialize: redactStructured(init, ctx.sanitize),
          sessionNew: { sessionId: '<SESSION_ID>', modes: session.modes?.currentModeId, models: session.models?.currentModelId, availableModels: (session.models?.availableModels ?? []).map((m) => m.modelId) },
          setModel,
          prompt: { stopReason: promptResult?.stopReason ?? null, error: promptError, acknowledgedModel: observedModel },
          authMethods: init.authMethods,
          agentCapabilities: init.agentCapabilities,
        },
        pid: client.child.pid,
      };
      if (acknowledged) ctx.ok('handshake', evidence);
      else ctx.fail('handshake', { ...evidence, reason: `pinned model ${QODER_PINNED_MODEL} not acknowledged (session=${session.models?.currentModelId}, prompt=${observedModel}${promptError ? `, error=${promptError}` : ''})` });
      ctx.sessionForLater = { sessionId: session.sessionId, env };
      await client.stop();
    } catch (error) {
      ctx.fail('handshake', { reason: `ACP handshake failed: ${error.message}` });
      await client.stop();
    }
  }

  // managedAuth ------------------------------------------------------------
  if (ctx.wants('managedAuth')) {
    const { env, scrubbed } = childEnv(null);
    const client = new AcpClient({
      exe: cliPath, args: qoderAcpArgs({ configDir, mcpConfig }), cwd: workspace, env, record,
      timeoutMs: Math.min(opts.timeoutMs, 60000), log,
    });
    await client.start();
    let unauthenticatedError = null;
    try {
      await client.request('initialize', { protocolVersion: 1 }, 45000);
      await client.request('session/new', { cwd: workspace, mcpServers: [] }, 45000);
    } catch (error) { unauthenticatedError = error.message; }
    await client.stop();

    // The two-sided claim needs an authenticated handshake as its positive arm;
    // run a minimal one when the handshake check itself was not requested.
    let authenticated = ctx.checks.handshake === true;
    if (!opts.checks?.includes('handshake')) {
      const positive = new AcpClient({
        exe: cliPath, args: qoderAcpArgs({ configDir, mcpConfig }), cwd: workspace,
        env: childEnv(pat).env, record,
        timeoutMs: Math.min(opts.timeoutMs, 60000), log,
      });
      await positive.start();
      try {
        await positive.request('initialize', { protocolVersion: 1 }, 45000);
        await positive.request('session/new', { cwd: workspace, mcpServers: [] }, 45000);
        authenticated = true;
      } catch (error) { record.event('managedAuth.positive_arm_error', { error: error.message }); }
      await positive.stop();
    }

    const evidence = {
      // Not named `credentialVariable`: the central redactor replaces string
      // values under credential-shaped keys, and this value is the name of the
      // injected variable, not a secret.
      injectedEnvironmentVariable: 'QODER_PERSONAL_ACCESS_TOKEN',
      credentialInjectedFromFileOnly: true,
      credentialValueRedacted: true,
      scrubbedEnvKeys: scrubbed.sort(),
      unauthenticatedSessionError: unauthenticatedError,
      authenticatedArmObserved: authenticated,
    };
    const failReason = !authenticated
      ? 'authenticated handshake was not observed, so managed auth cannot be claimed'
      : (unauthenticatedError === null
        ? 'session/new succeeded without the injected credential: managed auth is not enforced'
        : null);
    if (failReason) ctx.fail('managedAuth', { ...evidence, reason: failReason });
    else ctx.ok('managedAuth', evidence);
  }

  // isolatedConfig ---------------------------------------------------------
  if (ctx.wants('isolatedConfig')) {
    const marker = await startMarkerMcpServer(record);
    const hostilePath = join(workspace, '.mcp.json');
    writeFileSync(hostilePath, JSON.stringify({
      mcpServers: { 'hostile-marker': { type: 'http', url: `http://127.0.0.1:${marker.port}/mcp` } },
    }, null, 2));
    ctx.cleanups.push(() => new Promise((done) => marker.server.close(done)));

    const withoutStrict = await runProcess(cliPath, ['mcp', 'list'], {
      cwd: workspace, env: childEnv(pat).env, timeoutMs: 45000,
    });
    const withStrict = await runProcess(cliPath, [
      'mcp', 'list', '--config-dir', configDir, '--setting-sources', 'project',
      '--strict-mcp-config', '--mcp-config', JSON.stringify(mcpConfig),
    ], { cwd: workspace, env: childEnv(pat).env, timeoutMs: 45000 });
    const hostileLoadsWithoutStrict = /hostile-marker|127\.0\.0\.1/.test(withoutStrict.stdout);
    const hostileLoadsWithStrict = /hostile-marker/.test(withStrict.stdout);

    record.event('isolated.mcp_source_control', {
      hostileMarkerPort: marker.port,
      withoutStrictStdout: withoutStrict.stdout.slice(0, 600),
      withStrictStdout: withStrict.stdout.slice(0, 600),
    });

    // ACP session under the controlled invocation: the marker server must see nothing.
    const connectionsBeforeSession = record.events.filter((e) => e.type === 'isolated.hostile_mcp_connection').length;
    const { env, scrubbed } = childEnv(pat);
    const client = new AcpClient({
      exe: cliPath, args: qoderAcpArgs({ configDir, mcpConfig }), cwd: workspace, env, record,
      timeoutMs: Math.min(opts.timeoutMs, 60000), log,
    });
    await client.start();
    let sessionId = null;
    try {
      await client.request('initialize', { protocolVersion: 1 }, 45000);
      const session = await client.request('session/new', { cwd: workspace, mcpServers: [] }, 45000);
      sessionId = session.sessionId;
      await sleep(3000);
    } catch (error) {
      record.event('isolated.session_error', { error: error.message });
    }
    await client.stop();

    const markerConnectionsTotal = record.events.filter((e) => e.type === 'isolated.hostile_mcp_connection').length;
    // `mcp list` probes configured servers, so only the ACP-session delta counts here.
    const markerConnections = markerConnectionsTotal - connectionsBeforeSession;
    const configDirEntries = existsSync(configDir) ? readdirSync(configDir) : [];
    const evidence = {
      scrubbedEnvKeys: scrubbed.sort(),
      runOwnedHome: '<RUN>/home',
      runOwnedConfigDir: '<RUN>/config',
      configDirEntriesBeforeRun: configDirEntries,
      settingSources: 'project',
      strictMcpConfig: true,
      hostileProjectMcpFile: '.mcp.json (hostile-marker -> 127.0.0.1 marker server)',
      hostileLoadsWithoutStrict,
      hostileLoadsWithStrict,
      hostileMarkerConnectionsFromMcpList: connectionsBeforeSession,
      hostileMarkerConnectionsDuringAcpSession: markerConnections,
      sessionCreatedUnderControlledInvocation: sessionId !== null,
      ambientUserAuthInherited: false,
    };
    const reasons = [];
    if (!hostileLoadsWithoutStrict) reasons.push('hostile project MCP file was not loadable, so the exclusion check proves nothing');
    if (hostileLoadsWithStrict) reasons.push('hostile project MCP server loaded despite --strict-mcp-config');
    if (markerConnections > 0) reasons.push(`marker server saw ${markerConnections} connection(s) during the ACP session`);
    if (sessionId === null) reasons.push('no session was created under the controlled invocation');
    if (reasons.length) ctx.fail('isolatedConfig', { ...evidence, reason: reasons.join('; ') });
    else ctx.ok('isolatedConfig', evidence);
    ctx.limitations.push('plugin/skill provenance, user-level settings precedence and hostile .qoder/settings.json schema were not exhaustively tested');
  }

  // permissions: allowOnce + denyWithoutWrite ------------------------------
  const allowTarget = 'probe-allow-once.txt';
  const denyTarget = 'probe-deny.txt';
  const allowContent = 'alpha-allow-once';
  const denyContent = 'beta-should-not-exist';

  async function runWriteScenario({ check, target, content, decision, expectFile }) {
    const { env } = childEnv(pat);
    const client = new AcpClient({
      exe: cliPath, args: qoderAcpArgs({ configDir, mcpConfig }), cwd: workspace, env, record,
      timeoutMs: opts.timeoutMs, log,
    });
    await client.start();
    let promptResult = null;
    let promptError = null;
    const offeredKinds = [];
    let chosenOption = null;
    const modeUpdates = [];
    let scenarioDone = false;
    // A write grant is answered per request (never persistently): repeated asks
    // are answered individually with the offered single-use option.
    const answer = (async () => {
      while (!scenarioDone) {
        let event;
        try {
          event = await client.waitFor((e) => e.kind === 'permission', 5000);
        } catch {
          continue;
        }
        const options = event.request.params?.options ?? [];
        offeredKinds.push(options.map((o) => ({ optionId: o.optionId, name: o.name, kind: o.kind })));
        const wantedKind = decision === 'allow' ? 'allow_once' : 'reject_once';
        const candidate = options.find((o) => o.kind === wantedKind) ?? null;
        if (candidate) chosenOption = candidate;
        else record.event('permission.no_matching_option', { wantedKind, options });
        client.answerPermission(event.request, candidate?.optionId ?? null);
      }
    })();
    // Track permission-mode updates as escalation evidence.
    client.waiters.push((event) => {
      if (event.kind === 'update' && event.update.sessionUpdate === 'current_mode_update') modeUpdates.push(event.update);
      return false;
    });
    try {
      await client.request('initialize', { protocolVersion: 1 }, 45000);
      const session = await client.request('session/new', { cwd: workspace, mcpServers: [] }, 45000);
      promptResult = await client.request('session/prompt', {
        sessionId: session.sessionId,
        prompt: [{ type: 'text', text: `Create a file named ${target} in the current working directory containing exactly the text: ${content}` }],
      }, opts.timeoutMs);
    } catch (error) { promptError = error.message; }
    scenarioDone = true;
    await answer;
    const filePath = join(workspace, target);
    await sleep(1500);
    const fileExists = existsSync(filePath);
    const fileText = fileExists ? readFileSync(filePath, 'utf8') : null;
    const evidence = {
      offeredPermissionOptions: offeredKinds,
      chosenOption,
      promptStopReason: promptResult?.stopReason ?? null,
      promptError,
      modeUpdates,
      target: `<WORKSPACE>/${target}`,
      fileExistsAfterPrompt: fileExists,
      fileSha256: fileExists ? sha256Text(fileText) : null,
      expectedContentSha256: sha256Text(content),
    };
    await client.stop();
    const matches = fileExists && fileText === content;
    if (expectFile) {
      const reasons = [];
      if (!matches) reasons.push(`expected ${target} with exact content, saw ${fileExists ? 'different bytes' : 'no file'}`);
      if (chosenOption?.kind !== 'allow_once') reasons.push(`no allow_once option was selected (${JSON.stringify(chosenOption)})`);
      if (modeUpdates.some((u) => ['acceptEdits', 'yolo', 'auto'].includes(u.currentModeId))) reasons.push('permission mode escalated after the allow-once reply');
      if (reasons.length) ctx.fail(check, { ...evidence, reason: reasons.join('; ') });
      else ctx.ok(check, evidence);
    } else {
      const reasons = [];
      if (fileExists) reasons.push(`denied write still produced ${target}`);
      if (chosenOption?.kind !== 'reject_once') reasons.push(`no reject_once option was selected (${JSON.stringify(chosenOption)})`);
      if (reasons.length) ctx.fail(check, { ...evidence, reason: reasons.join('; ') });
      else ctx.ok(check, evidence);
    }
    return filePath;
  }

  if (ctx.wants('allowOnce')) {
    await runWriteScenario({ check: 'allowOnce', target: allowTarget, content: allowContent, decision: 'allow', expectFile: true });
  }
  if (ctx.wants('denyWithoutWrite')) {
    await runWriteScenario({ check: 'denyWithoutWrite', target: denyTarget, content: denyContent, decision: 'deny', expectFile: false });
  }

  // cancel -----------------------------------------------------------------
  if (ctx.wants('cancel')) {
    const arms = {};
    const { env } = childEnv(pat);

    // arm (a): cancel for a session that was never created.
    // The arm does not demand a rejection: in the recorded run the pinned core
    // produced no client-visible rejection of the unknown-session cancel. It
    // asserts what this instrument can actually falsify: the bogus cancel
    // leaves the live owned tree untouched (the core process still answers, the
    // workspace bytes are identical and the live session observes no update).
    // Instrument limitation: the arm sees only frames the client classifies as
    // session updates, permission requests or unclassified messages. Response
    // frames are never surfaced to waiters: AcpClient.onLine resolves a
    // response into its pending request or drops it when no request matches,
    // so a frame carrying a result or error with any id - including an id-null
    // error response to the cancel notification - would be invisible here:
    // the arm cannot distinguish core silence from a response this client
    // discarded.
    // A live session is opened first, then the workspace bytes are sampled,
    // the bogus cancel is sent, and afterwards the core process must still
    // answer, the workspace bytes must be identical and the live session must
    // have observed no update.
    {
      const client = new AcpClient({
        exe: cliPath, args: qoderAcpArgs({ configDir, mcpConfig }), cwd: workspace, env, record,
        timeoutMs: 45000, log,
      });
      await client.start();
      let error = null;
      try {
        await client.request('initialize', { protocolVersion: 1 }, 30000);
        await client.request('session/new', { cwd: workspace, mcpServers: [] }, 45000);
        // The core emits its own session-startup update (e.g.
        // available_commands_update) right after session/new; let it arrive
        // first so the "no update during the cancel" window measures the bogus
        // cancel and not session creation.
        const startupUpdate = await client.waitFor((e) => e.kind === 'update', 4000)
          .then((e) => e.update?.sessionUpdate ?? 'unknown').catch(() => null);
        await sleep(500);
        const bogus = '00000000-0000-4000-8000-000000000000';
        const beforeSnapshot = snapshotTree(workspace);
        const updatesBefore = client.updates.length;
        let responseSeen = null;
        const waiter = client.waitFor((e) => e.kind === 'other', 5000).catch(() => null);
        client.notify('session/cancel', { sessionId: bogus });
        responseSeen = await waiter;
        await sleep(2000);
        const afterSnapshot = snapshotTree(workspace);
        const bytesUnchanged = JSON.stringify(beforeSnapshot) === JSON.stringify(afterSnapshot);
        const updatesDuring = client.updates.length - updatesBefore;
        const processSurvived = processAlive(client.child.pid)
          && await client.request('initialize', { protocolVersion: 1 }, 20000).then(() => true).catch(() => false);
        arms.beforeSession = {
          sent: 'session/cancel notification carrying an uncreated session id while a live session was open',
          liveSessionOpen: true,
          liveSessionStartupUpdate: startupUpdate,
          observedResponse: responseSeen ? redactStructured(responseSeen.message, ctx.sanitize) : null,
          observedResponseNote: responseSeen
            ? 'the core emitted the recorded message in response to the unknown-session cancel'
            : 'arm limitation: within the 5000 ms window no client-visible frame (session update, permission request or unclassified message) followed the unknown-session cancel; this client never surfaces response frames to waiters (AcpClient.onLine resolves them into a pending request or drops them when none matches), so an id-null error response to this notification would be invisible - this records what the arm could not observe, not the wire behaviour of the core',
          processSurvived,
          workspaceSnapshotSha256: {
            before: sha256Text(JSON.stringify(beforeSnapshot)),
            after: sha256Text(JSON.stringify(afterSnapshot)),
          },
          workspaceBytesUnchanged: bytesUnchanged,
          sessionUpdatesDuringCancel: updatesDuring,
          pass: processSurvived && bytesUnchanged && updatesDuring === 0,
        };
      } catch (caughtError) { error = caughtError.message; arms.beforeSession = { pass: false, error }; }
      await client.stop();
      if (arms.beforeSession?.pass !== true) {
        ctx.limitations.push('cancel arm (a) could not prove the unknown-session cancel was a no-op for the live owned tree');
      } else if (arms.beforeSession.observedResponse === null) {
        ctx.limitations.push('cancel arm (a) observed no client-visible frame within 5000 ms of the unknown-session cancel, and this instrument never surfaces response frames to waiters (AcpClient.onLine resolves them into their pending request or drops them), so an id-null error response to the notification would be invisible and the arm asserts only that the live owned tree is untouched, not the wire behaviour of the core');
      }
    }

    // arm (b): cancel while a permission request is pending
    {
      const target = 'probe-cancel-pending.txt';
      const client = new AcpClient({
        exe: cliPath, args: qoderAcpArgs({ configDir, mcpConfig }), cwd: workspace, env, record,
        timeoutMs: opts.timeoutMs, log,
      });
      await client.start();
      let stopReason = null;
      let promptError = null;
      let sawPermission = false;
      try {
        await client.request('initialize', { protocolVersion: 1 }, 45000);
        const session = await client.request('session/new', { cwd: workspace, mcpServers: [] }, 45000);
        const promptPromise = client.request('session/prompt', {
          sessionId: session.sessionId,
          prompt: [{ type: 'text', text: `Create a file named ${target} containing exactly the text: gamma-cancel-pending` }],
        }, 30000).then((r) => { stopReason = r?.stopReason ?? null; }).catch((e) => { promptError = e.message; });
        const permission = await client.waitFor((e) => e.kind === 'permission', 60000).then(() => true).catch(() => false);
        sawPermission = permission;
        record.event('cancel.pending_approval', { permissionObserved: permission });
        client.notify('session/cancel', { sessionId: session.sessionId });
        await promptPromise;
        await sleep(1500);
      } catch (error) { promptError = promptError ?? error.message; }
      const fileExists = existsSync(join(workspace, target));
      arms.pendingApproval = {
        permissionObserved: sawPermission,
        cancelMethod: 'session/cancel notification while a session/request_permission was unanswered',
        promptStopReason: stopReason,
        promptError,
        fileExists,
        pass: sawPermission && fileExists === false && promptError === null,
        note: stopReason === 'cancelled' ? 'core reported stopReason=cancelled' : `core reported stopReason=${stopReason}`,
      };
      await client.stop();
    }

    // arm (c): cancel right after the first in-flight tool call
    {
      const target = 'probe-cancel-inflight.txt';
      const client = new AcpClient({
        exe: cliPath, args: qoderAcpArgs({ configDir, mcpConfig }), cwd: workspace, env, record,
        timeoutMs: opts.timeoutMs, log,
      });
      await client.start();
      let stopReason = null;
      let promptError = null;
      let sawToolCall = false;
      try {
        await client.request('initialize', { protocolVersion: 1 }, 45000);
        const session = await client.request('session/new', { cwd: workspace, mcpServers: [] }, 45000);
        const promptPromise = client.request('session/prompt', {
          sessionId: session.sessionId,
          prompt: [{ type: 'text', text: `Create a file named ${target} containing exactly the text: delta-cancel-inflight` }],
        }, 40000).then((r) => { stopReason = r?.stopReason ?? null; }).catch((e) => { promptError = e.message; });
        const toolCall = await client.waitFor(
          (e) => e.kind === 'update' && e.update.sessionUpdate === 'tool_call', 60000,
        ).then(() => true).catch(() => false);
        sawToolCall = toolCall;
        client.notify('session/cancel', { sessionId: session.sessionId });
        await promptPromise;
        await sleep(1000);
      } catch (error) { promptError = promptError ?? error.message; }
      const cancelTargetExists = existsSync(join(workspace, target));
      arms.inFlightTool = {
        toolCallObserved: sawToolCall,
        cancelMethod: 'session/cancel notification immediately after the first tool_call update',
        promptStopReason: stopReason,
        promptError,
        fileExists: cancelTargetExists,
        pass: sawToolCall && promptError === null && stopReason === 'cancelled' && cancelTargetExists === false,
        note: `cancel without granting requires stopReason=cancelled and no ${target} on disk`,
      };
      await client.stop();
    }

    record.event('cancel.arms', arms);
    const failed = Object.entries(arms).filter(([, value]) => !value.pass).map(([name]) => name);
    if (failed.length) ctx.fail('cancel', { arms, reason: `unmet cancel arms: ${failed.join(', ')}` });
    else ctx.ok('cancel', { arms, cancelMethod: 'session/cancel notification (ACP), confirmed by prompt terminal state and filesystem checks' });
  }

  // writer control: pauseResume + writersStopped + stableExport ------------
  const wantsWriter = ctx.wants('pauseResume') || ctx.wants('writersStopped') || ctx.wants('stableExport');
  if (wantsWriter && supervisorReady) {
    const control = new JobSupervisor(ctx.runDir, record);
    ctx.cleanups.push(() => control.stop());
    await control.start();

    // (1) technique verification on controlled disposable trees.
    //     Two OS-owned techniques are proven here, each with independent byte
    //     sampling: (a) Windows job-object membership and (b) descendant-tree
    //     enumeration. Whichever is actually usable on the core process is used
    //     for the live probe, and the recorded technique names which was used.
    writeFileSync(join(workspace, 'writer.mjs'), WRITER_SCRIPT);
    writeFileSync(join(workspace, 'spawn-writer.mjs'), SPAWN_WRITER_SCRIPT);

    async function spawnControlledWriter(label) {
      const ticks = join(ctx.runDir, 'control', `${label}-ticks.log`);
      mkdirSync(dirname(ticks), { recursive: true });
      rmSync(ticks, { force: true });
      const host = spawn(process.execPath, [join(workspace, 'spawn-writer.mjs'), ticks, '45000'],
        { cwd: workspace, stdio: ['ignore', 'pipe', 'ignore'], windowsHide: true });
      const pid = await new Promise((done) => {
        let buffer = '';
        host.stdout.on('data', (d) => {
          buffer += d;
          const match = buffer.match(/"writerPid":(\d+)/);
          if (match) done(Number(match[1]));
        });
        setTimeout(() => done(null), 10000);
      });
      ctx.cleanups.push(() => { try { if (pid) process.kill(pid, 'SIGKILL'); } catch { /* gone */ } host.kill(); });
      return { pid, ticks, host };
    }

    const sizeOf = (file) => (existsSync(file) ? statSync(file).size : -1);

    async function verifySuspendResume({ label, pid, ticks, prepare, suspend, resume }) {
      const prepareResult = prepare ? await prepare() : null;
      await sleep(1500);
      const before = sizeOf(ticks);
      const suspendResult = await suspend();
      await sleep(1300);
      const frozenA = sizeOf(ticks);
      await sleep(1300);
      const frozenB = sizeOf(ticks);
      const resumeResult = await resume();
      await sleep(1500);
      const after = sizeOf(ticks);
      const result = {
        label,
        pid,
        prepare: prepareResult,
        suspend: suspendResult,
        resume: resumeResult,
        byteSamples: { beforeSuspend: before, duringSuspendA: frozenA, duringSuspendB: frozenB, afterResume: after },
        frozenWhileSuspended: frozenA > 0 && frozenA === frozenB,
        grewAfterResume: after > frozenB,
      };
      result.pass = result.frozenWhileSuspended && result.grewAfterResume;
      return result;
    }

    const jobWriter = await spawnControlledWriter('job');
    const jobTechnique = await verifySuspendResume({
      label: 'job-object-membership',
      pid: jobWriter.pid,
      ticks: jobWriter.ticks,
      prepare: () => control.assignTree(jobWriter.pid),
      suspend: () => control.suspend(),
      resume: () => control.resume(),
    });
    record.event('control.job_object_technique', jobTechnique);

    const treeWriter = await spawnControlledWriter('tree');
    const treeTechnique = await verifySuspendResume({
      label: 'descendant-tree-enumeration',
      pid: treeWriter.pid,
      ticks: treeWriter.ticks,
      prepare: () => control.tree(treeWriter.pid),
      // The second call re-enumerates the tree so a child spawned between
      // enumeration and suspension is still caught. NtSuspendProcess increments
      // a per-process suspend count, so each suspend needs a matching resume:
      // the resume must be issued twice as well or the tree stays suspended.
      suspend: async () => ({
        first: await control.suspendTree(treeWriter.pid),
        second: await control.suspendTree(treeWriter.pid),
      }),
      resume: async () => ({
        first: await control.resumeTree(treeWriter.pid),
        second: await control.resumeTree(treeWriter.pid),
      }),
    });
    const treeKill = await control.killTree(treeWriter.pid);
    await sleep(1300);
    const treeKillA = sizeOf(treeWriter.ticks);
    await sleep(1300);
    const treeKillB = sizeOf(treeWriter.ticks);
    const treePidsAfterKill = (await control.tree(treeWriter.pid)).pids ?? [];
    treeTechnique.kill = treeKill;
    treeTechnique.killByteSamples = { afterKill: treeKillA, later: treeKillB };
    treeTechnique.frozenAfterKill = treeKillA > 0 && treeKillA === treeKillB;
    treeTechnique.treeAfterKill = treePidsAfterKill;
    record.event('control.tree_technique', treeTechnique);

    const jobTechniquePass = jobTechnique.pass === true;
    const treeTechniquePass = treeTechnique.pass === true && treeTechnique.frozenAfterKill === true;
    const controlTechnique = {
      jobObjectMembership: { verifiedOnControlledTree: jobTechniquePass, evidence: jobTechnique },
      descendantTreeEnumeration: { verifiedOnControlledTree: treeTechniquePass, evidence: treeTechnique },
      techniqueSelectedForCore: treeTechniquePass ? 'descendant-tree enumeration (suspend/resume/terminate)' : null,
      // Pending until the live assignment attempt below decides it: a pre-filled
      // refusal text would also be carried by a run whose live assignment
      // succeeded (an unobserved claim). Only the observed failure branch sets
      // the refusal text; a successful assignment leaves this null.
      jobObjectSelectionBlockedBy: treeTechniquePass
        ? null
        : 'no OS-owned process-control technique was verified on a controlled tree',
    };
    ctx.observe('controlTechnique', controlTechnique);
    if (!treeTechniquePass) {
      ctx.block('pauseResume', 'no OS-owned suspend/resume technique was verified on a controlled tree');
      ctx.block('writersStopped', 'no OS-owned termination technique was verified on a controlled tree');
      ctx.block('stableExport', 'writer quiescence cannot be established without a verified termination technique');
    }

    // (2) live core: a tool-spawned descendant writer must survive prompt completion
    const ticks = join(workspace, 'ticks.log');
    rmSync(ticks, { force: true });
    const { env } = childEnv(pat);
    const client = new AcpClient({
      exe: cliPath, args: qoderAcpArgs({ configDir, mcpConfig }), cwd: workspace, env, record,
      timeoutMs: opts.timeoutMs, log,
    });
    await client.start();
    const assign = await control.assignRootWithRetry(client.child.pid);
    const jobPids = await control.pids();
    ctx.observe('jobAssignment', { assign, jobPids });
    if (!assign.rootInJob) {
      controlTechnique.jobObjectSelectionBlockedBy =
        `the job-object assignment did not take effect on the live core process ${client.child.pid} ` +
        `(every AssignProcessToJobObject attempt was refused: ${JSON.stringify(assign.attempts)}); ` +
        'job-object suspension/termination is therefore unavailable for the live core and the live probe relies on ' +
        'identity-validated descendant-tree enumeration, which only reaches already-created descendants';
      ctx.observe('controlTechnique', controlTechnique);
      ctx.limitations.push(`the job-object assignment did not take effect on the live core process ${client.child.pid} (${JSON.stringify(assign.attempts)}); job-object membership was verified on a controlled tree only and the live core is supervised by identity-validated descendant-tree enumeration`);
    }
    const permissionLog = [];
    let writerDone = false;
    const answerAll = (async () => {
      while (!writerDone) {
        let event;
        try {
          event = await client.waitFor((e) => e.kind === 'permission', 5000);
        } catch {
          continue;
        }
        const options = event.request.params?.options ?? [];
        const chosen = options.find((o) => o.kind === 'allow_once');
        permissionLog.push({ title: event.request.params?.toolCall?.title, kinds: options.map((o) => o.kind), chosen: chosen?.kind ?? null });
        client.answerPermission(event.request, chosen?.optionId ?? null);
      }
    })();
    const ticksSize = () => (existsSync(ticks) ? statSync(ticks).size : -1);
    const captureOwnedSet = async () => {
      const info = await control.treeInfo(client.child.pid);
      return (info.procs ?? []).map((p) => `${p.pid}@${p.created}`);
    };
    let promptResult = null;
    let promptError = null;
    const writerPrompt = 'Run this exact command with the Bash tool, do not modify it: node spawn-writer.mjs ticks.log. Then reply with the exact text DONE.';
    try {
      await client.request('initialize', { protocolVersion: 1 }, 45000);
      const session = await client.request('session/new', { cwd: workspace, mcpServers: [] }, 45000);
      const promptPromise = client.request('session/prompt', {
        sessionId: session.sessionId, prompt: [{ type: 'text', text: writerPrompt }],
      }, opts.timeoutMs).then((r) => { promptResult = r; }).catch((e) => { promptError = e.message; });

      // Capture the owned tree with creation timestamps WHILE the tool call is
      // still in flight: the shell chain is intact then, and identity-validated
      // records (pid + creation time) are what later control acts on.
      let toolCallSeen = false;
      try {
        await client.waitFor((e) => e.kind === 'update' && e.update.sessionUpdate === 'tool_call', 90000);
        toolCallSeen = true;
      } catch { /* recorded below */ }
      let ownedSet = [];
      let inFlightCapture = null;
      for (let attempt = 0; attempt < 8 && ownedSet.length === 0; attempt += 1) {
        await sleep(1500);
        inFlightCapture = await control.treeInfo(client.child.pid);
        if (ticksSize() > 0) ownedSet = (inFlightCapture.procs ?? []).map((p) => `${p.pid}@${p.created}`);
      }
      record.event('writer.owned_set_captured', { toolCallSeen, tickBytes: ticksSize(), ownedSet });
      ctx.observe('ownedTreeCapture', { inFlightToolCall: toolCallSeen, records: ownedSet, procs: inFlightCapture?.procs ?? [] });

      // Pause/resume during active work: suspend the identity-validated owned
      // set plus a fresh sweep, then resume and require byte growth again.
      let pauseEvidence = null;
      if (ownedSet.length > 0 && ticksSize() > 0) {
        const beforeSuspend = ticksSize();
        const suspend = await control.suspendList(ownedSet);
        const sweepRecords = await captureOwnedSet();
        const sweep = await control.suspendList(sweepRecords);
        await sleep(1500);
        const duringA = ticksSize();
        await sleep(1500);
        const duringB = ticksSize();
        const resume = await control.resumeList(ownedSet);
        const resumeSweep = await control.resumeList(sweepRecords);
        await sleep(2000);
        const afterResume = ticksSize();
        pauseEvidence = {
          appliedTo: 'live core process tree (qodercli + descendants) during an in-flight tool call',
          technique: 'identity-validated descendant-tree enumeration (pid + creation time) + NtSuspendProcess/NtResumeProcess',
          ownedRecords: ownedSet,
          suspend,
          sweep,
          resume,
          resumeSweep,
          byteSamples: { beforeSuspend, duringSuspendA: duringA, duringSuspendB: duringB, afterResume },
          frozenWhileSuspended: duringA > 0 && duringA === duringB,
          grewAfterResume: afterResume > duringB,
          nativePauseRpcUsed: false,
          remoteInferenceLimit: 'process suspension freezes local execution; remote inference already billed server-side is not recalled',
          raceLimit: 'a child spawned between enumeration and suspension is caught by the re-sweep; a process that re-parents away from the enumerated tree would not be',
        };
        pauseEvidence.pass = pauseEvidence.frozenWhileSuspended && pauseEvidence.grewAfterResume;
        if (ctx.wants('pauseResume') && !ctx.checks.pauseResume) {
          if (pauseEvidence.pass) ctx.ok('pauseResume', pauseEvidence);
          else ctx.fail('pauseResume', { ...pauseEvidence, reason: 'suspended writer bytes did not freeze or did not resume' });
        }
      }

      await promptPromise;
      await sleep(2500);
      const sizeAtCompletion = ticksSize();
      const writerSpawned = sizeAtCompletion > 0;
      // Prompt completion must not be treated as writer quiescence.
      await sleep(2500);
      const sizeAfterWait = ticksSize();
      const survivedCompletion = writerSpawned && sizeAfterWait > sizeAtCompletion;
      const jobPidsDuringWriter = await control.pids();

      let terminationEvidence = null;
      let exportEvidence = null;

      if (pauseEvidence && !pauseEvidence.pass && ctx.wants('pauseResume') && !ctx.checks.pauseResume) {
        ctx.fail('pauseResume', { ...pauseEvidence, reason: 'suspended writer bytes did not freeze or did not resume' });
      }

      if (survivedCompletion) {
        // writersStopped: terminate the identity-validated owned set plus fresh
        // sweeps; the independent evidence is the byte freeze, never a PID list.
        const recaptured = await captureOwnedSet();
        const killOwned = await control.killList(ownedSet);
        const killSweep = await control.killTree(client.child.pid);
        const killRecaptured = await control.killList(recaptured);
        await sleep(1500);
        const afterKillA = ticksSize();
        await sleep(2000);
        const afterKillB = ticksSize();
        const remaining = (await control.treeInfo(client.child.pid)).procs ?? [];
        const tracked = [...new Set([...ownedSet, ...recaptured].map((record) => Number(record.split('@')[0])))];
        const survivors = tracked.filter((pid) => processAlive(pid));
        // The recorded technique must name exactly the steps that applied to
        // processes; a step that failed to run is named as a limitation below,
        // never as part of the technique.
        const appliedSteps = [
          (killOwned?.results?.length ? `identity-validated owned records (pid + creation time) terminated via TerminateProcess (${killOwned.results.length} record(s))` : null),
          (killSweep?.ok === true ? `deepest-first descendant-tree TerminateProcess sweep (${(killSweep.terminated ?? []).length} process(es) still present)` : null),
          (killRecaptured?.results?.length ? `re-enumerated live-tree sweep via TerminateProcess (${killRecaptured.results.length} record(s))` : null),
        ].filter(Boolean);
        terminationEvidence = {
          premiseObserved: 'writer kept writing after the prompt-completion event',
          preCompletionBytes: sizeAtCompletion,
          postCompletionBytes: sizeAfterWait,
          technique: appliedSteps.length > 0 ? appliedSteps.join(' + ') : 'no termination step applied',
          techniqueBasis: 'every listed step returned a parseable supervisor response; steps that did not run appear in the run limitations instead',
          jobObjectBackstop: {
            executedBy: 'TerminateJobObject on the run-owned job (control.kill), after the byte-freeze samples were taken',
            jobMembersDuringWriter: jobPidsDuringWriter.pids ?? [],
            liveCoreWasJobMember: assign.rootInJob === true,
          },
          ownedRecords: ownedSet,
          killOwned,
          killSweep,
          killRecaptured,
          remainingTree: remaining,
          trackedProcesses: tracked,
          liveSurvivors: survivors,
          byteSamples: { afterKill: afterKillA, later: afterKillB },
          bytesFrozenAfterTermination: afterKillA > 0 && afterKillA === afterKillB,
        };
        if (killSweep?.ok !== true) {
          ctx.limitations.push(`the deepest-first descendant-tree termination sweep did not run (${JSON.stringify(killSweep)}); termination evidence relies on the identity-validated owned-record kill and the re-enumerated sweep`);
        }
        terminationEvidence.pass = terminationEvidence.bytesFrozenAfterTermination && survivors.length === 0;
        if (ctx.wants('writersStopped') && !ctx.checks.writersStopped) {
          if (terminationEvidence.pass) ctx.ok('writersStopped', terminationEvidence);
          else ctx.fail('writersStopped', { ...terminationEvidence, reason: `bytes still changing or ${survivors.length} process(es) survived tree termination` });
        }

        // stableExport: two snapshots after quiescence must be identical
        const firstExport = snapshotTree(workspace);
        await sleep(2000);
        const secondExport = snapshotTree(workspace);
        const identical = JSON.stringify(firstExport) === JSON.stringify(secondExport);
        exportEvidence = {
          snapshotsTakenAfterWriterTermination: 2,
          identical,
          entryCount: firstExport.length,
          entries: firstExport,
          exportFile: '<FIXTURES>/' + `qoder-host-stable-export-${(ctx.row.runtime.version || 'unknown').replace(/[^0-9A-Za-z._-]/g, '_')}.json`,
        };
        if (ctx.wants('stableExport') && !ctx.checks.stableExport) {
          if (identical) ctx.ok('stableExport', exportEvidence);
          else ctx.fail('stableExport', { ...exportEvidence, reason: 'workspace snapshot changed between samples after writer termination' });
        }
        writeFileSync(join(ctx.runDir, 'stable-export.json'), JSON.stringify({
          schemaVersion: 1, workspace: '<WORKSPACE>', entries: firstExport,
        }, null, 2));
        ctx.stableExportSnapshot = firstExport;
      } else {
        const reason = writerSpawned
          ? 'the descendant writer stopped on its own at prompt completion, so no surviving writer proved termination ordering'
          : `no descendant writer was observed (promptError=${promptError ?? 'none'})`;
        for (const check of ['pauseResume', 'writersStopped', 'stableExport']) {
          if (ctx.wants(check) && !ctx.checks[check]) ctx.fail(check, { reason });
        }
      }

      record.event('writer.scenario', {
        prompt: writerPrompt,
        promptStopReason: promptResult?.stopReason ?? null,
        promptError,
        permissionLog,
        pauseEvidence,
        terminationEvidence,
        exportEvidence,
      });
      ctx.observe('writerScenario', { permissionLog, promptStopReason: promptResult?.stopReason ?? null, promptError });
    } catch (error) {
      ctx.observe('writerScenarioError', { error: error.message });
      for (const check of ['pauseResume', 'writersStopped', 'stableExport']) {
        if (ctx.wants(check) && !ctx.checks[check]) ctx.fail(check, { reason: `writer scenario failed: ${error.message}` });
      }
    }
    writerDone = true;
    await answerAll;
    await client.stop();
    await control.kill().catch(() => null);
  }
}

// ─────────────────────────────────────────────────────────────────────────────
// OpenCode / sandbox environment probes
// ─────────────────────────────────────────────────────────────────────────────

async function probeSandboxPrerequisites(ctx, { core }) {
  const { opts, record } = ctx;
  const endpoint = new URL(opts.sandboxEndpoint);
  const reach = await tcpProbe(endpoint.hostname, Number(endpoint.port || 80), 4000);
  let health = null;
  if (reach.reachable) health = await httpJson('GET', new URL('/health', opts.sandboxEndpoint).toString(), undefined, 5000);
  const images = {};
  for (const runtime of ['docker', 'podman']) {
    const list = await runProcess(runtime, ['images', '--format', '{{.Repository}}:{{.Tag}}'], { timeoutMs: 30000 })
      .catch((error) => ({ code: -1, stdout: '', stderr: String(error) }));
    const all = (list.stdout || '').split('\n').map((s) => s.trim()).filter(Boolean);
    images[runtime] = { exitCode: list.code, imageCount: all.length, sandboxLike: all.filter((i) => /sandbox|aria|opencode|qoder/i.test(i)) };
  }
  const evidence = {
    sandboxEndpoint: opts.sandboxEndpoint,
    sandboxServerReachable: reach.reachable,
    sandboxServerProbe: reach,
    sandboxHealth: health ? { status: health.status, text: health.text?.slice(0, 300), error: health.error } : null,
    requestedImage: opts.image ?? null,
    containerImages: images,
  };
  record.event('sandbox.prerequisites', evidence);
  ctx.observe('sandboxPrerequisites', evidence);
  return evidence;
}

async function probeOpenCodeHost(ctx, { opencodeExe }) {
  const { opts, record, log } = ctx;
  const workspace = opts.workspace;
  // Node refuses to spawn a Windows `.cmd` shim directly (spawn EINVAL), so an
  // npm-installed opencode is unwrapped to its package entry and launched with
  // the Node binary. The operator-facing path stays available for the record.
  const opencode = unwrapWindowsShim(opencodeExe);
  const ocArgs = (args) => [...opencode.prefixArgs, ...args];
  const xdg = {
    XDG_DATA_HOME: join(ctx.runDir, 'xdg', 'data'),
    XDG_CONFIG_HOME: join(ctx.runDir, 'xdg', 'config'),
    XDG_CACHE_HOME: join(ctx.runDir, 'xdg', 'cache'),
  };
  for (const dir of Object.values(xdg)) mkdirSync(dir, { recursive: true });

  const version = await runProcess(opencode.exe, ocArgs(['--version']), { timeoutMs: 30000 });
  const runtime = {
    core: 'opencode',
    binary: ctx.sanitize(opencodeExe),
    version: version.stdout.trim(),
    versionSource: 'opencode --version',
    licenseSource: 'npm package opencode-ai (MIT) resolved from the global npm root',
    credentialSource: 'not configured for this environment',
  };
  if (opencode.prefixArgs.length > 0) {
    runtime.invocation = `${ctx.sanitize(opencode.exe)} ${opencode.prefixArgs.map((a) => ctx.sanitize(a)).join(' ')}`;
    runtime.binaryNote = 'npm .cmd shim unwrapped to the package entry (Node cannot spawn .cmd directly on Windows)';
  }
  ctx.row.runtime = runtime;
  ctx.observe('openCodeVersion', runtime.version);

  const port = await reservePort();
  const { env, scrubbed } = buildChildEnv({ base: process.env, pat: null, runDir: ctx.runDir, extra: xdg });
  const server = spawn(opencode.exe, ocArgs(['serve', '--port', String(port), '--hostname', '127.0.0.1']), {
    cwd: workspace, env, stdio: ['ignore', 'pipe', 'pipe'], windowsHide: true,
  });
  ctx.cleanups.push(() => { try { server.kill(); } catch { /* gone */ } });
  let serverLog = '';
  server.stdout.on('data', (d) => { serverLog += d; });
  server.stderr.on('data', (d) => { serverLog += d; });
  await sleep(6000);
  const health = await httpJson('GET', `http://127.0.0.1:${port}/global/health`, undefined, 8000);
  const session = await httpJson('POST', `http://127.0.0.1:${port}/session`, {}, 15000);
  const sessionId = session.json?.id ?? null;

  // Credential isolation: the operator's personal provider credential must not be used.
  const authList = await runProcess(opencode.exe, ocArgs(['auth', 'list']), { env, cwd: workspace, timeoutMs: 45000 });
  const authCount = /(\d+)\s+credentials?/.exec(authList.stdout)?.[1] ?? null;
  // A credential COUNT is not a secret; keep it numeric so the redactor's
  // sensitive-key rule (which turns string values under credential-shaped keys
  // into [REDACTED-SECRET]) does not erase the observed value.
  const authCountNumber = authCount === null ? null : Number(authCount);

  // A model-bound message is the only proof of a usable host core.
  let messageAttempt = null;
  if (sessionId) {
    messageAttempt = await httpJson('POST', `http://127.0.0.1:${port}/session/${sessionId}/message`, {
      parts: [{ type: 'text', text: 'Reply with exactly the single word: pong' }],
    }, 30000);
  }

  record.event('opencode.host_probe', {
    argv: ['serve', '--port', String(port), '--hostname', '127.0.0.1'],
    sanitizedXdgRoots: Object.fromEntries(Object.entries(xdg).map(([k, v]) => [k, ctx.sanitize(v)])),
    scrubbedEnvKeys: scrubbed.sort(),
    serverLog: serverLog.slice(0, 800),
    health: { status: health.status, body: health.json ?? health.text, error: health.error },
    session: { status: session.status, id: sessionId ? '<SESSION_ID>' : null, error: session.error },
    authList: { credentialCount: authCountNumber, raw: authList.stdout.slice(0, 400) },
    messageAttempt: messageAttempt ? { status: messageAttempt.status, error: messageAttempt.error, body: (messageAttempt.text ?? '').slice(0, 600) } : null,
  });

  ctx.observe('openCodeHostStartup', {
    healthHealthy: health.json?.healthy === true,
    healthVersion: health.json?.version ?? null,
    sessionCreated: sessionId !== null,
    runOwnedCredentialCount: authCountNumber,
  });

  // A protocol-level response is not a model run: an assistant message carrying
  // a provider error (e.g. HTTP 426 from a free tier) is still a failure, so the
  // payload must carry a model identity and no error before handshake verifies.
  const messageInfo = messageAttempt?.json?.info ?? null;
  const modelError = messageInfo?.error ?? null;
  const modelBoundResponse = Boolean(
    messageAttempt && messageAttempt.status && messageAttempt.status < 400
      && messageInfo?.modelID && !modelError,
  );
  if (modelBoundResponse) {
    ctx.ok('handshake', { health: health.json, sessionCreated: true, modelBoundMessageStatus: messageAttempt.status, model: messageInfo.modelID, provider: messageInfo.providerID ?? null });
  } else {
    const reason = messageAttempt?.status === 0
      ? `no model-bound response: ${messageAttempt.error}`
      : `no model-bound response (HTTP ${messageAttempt?.status}${modelError ? `, provider error ${JSON.stringify(modelError).slice(0, 220)}` : `: ${(messageAttempt?.text ?? '').slice(0, 200)}`})`;
    ctx.fail('handshake', {
      health: health.json,
      sessionCreated: sessionId !== null,
      reason,
    });
  }
  const credentialPrerequisite = 'no run-owned model-provider credential in this environment (DEEPSEEK_API_KEY/LLM key unset; the operator personal provider credential is deliberately excluded by run-owned XDG roots)';
  ctx.block('managedAuth', credentialPrerequisite);
  ctx.block('allowOnce', `no model run is possible without a credential; nothing to observe (${credentialPrerequisite})`);
  ctx.block('denyWithoutWrite', `no model run is possible without a credential; nothing to observe (${credentialPrerequisite})`);
  ctx.block('cancel', `no model run is possible without a credential; nothing to observe (${credentialPrerequisite})`);
  ctx.limitations.push('OpenCode Host startup, /global/health and session creation were observed; the combination remains BLOCKED for every behavior that needs a model run');
}

// ─────────────────────────────────────────────────────────────────────────────
// Main
// ─────────────────────────────────────────────────────────────────────────────

function resolveQoderCli(opts) {
  const candidates = [
    opts.cli,
    process.env.ARIA_PROBE_QODER_CLI,
    process.env.USERPROFILE ? join(process.env.USERPROFILE, '.qoder', 'bin', 'qodercli', 'qodercli.exe') : null,
  ].filter(Boolean);
  for (const candidate of candidates) if (existsSync(candidate)) return resolve(candidate);
  return null;
}

function resolveOpenCode(opts) {
  const candidates = [
    opts.opencode,
    process.env.ARIA_PROBE_OPENCODE,
    process.env.APPDATA ? join(process.env.APPDATA, 'npm', 'opencode.cmd') : null,
    process.env.APPDATA ? join(process.env.APPDATA, 'npm', 'opencode') : null,
  ].filter(Boolean);
  for (const candidate of candidates) if (existsSync(candidate)) return resolve(candidate);
  return 'opencode';
}

/**
 * Windows npm installs expose a `.cmd` shim that Node refuses to spawn directly
 * (`spawn EINVAL` since the CVE-2024-27980 fix). The shim is a stable npm
 * template that invokes `"%dp0%\node_modules\<pkg>\..."`, so the package entry
 * is recovered from it and launched with the current Node binary. Anything that
 * is not a readable shim is returned unchanged.
 */
function unwrapWindowsShim(exe) {
  if (platform() !== 'win32' || !/\.(cmd|bat)$/i.test(exe) || !existsSync(exe)) {
    return { exe, prefixArgs: [] };
  }
  const shim = readFileSync(exe, 'utf8');
  const quoted = shim.match(/"([^"]*node_modules\\[^"]*)"/);
  const bare = shim.match(/(?:%dp0%\\)?node_modules\\[^\s"%]+/);
  const raw = quoted?.[1] ?? bare?.[0] ?? null;
  if (!raw) return { exe, prefixArgs: [] };
  const expanded = raw.replace(/%dp0%\\?/gi, dirname(exe) + sep);
  const entry = resolve(expanded);
  if (!existsSync(entry)) return { exe, prefixArgs: [] };
  return { exe: process.execPath, prefixArgs: [entry] };
}

function upsertRow(matrixPath, row) {
  let matrix = { schemaVersion: 1, generatedAt: nowIso(), rows: [] };
  if (existsSync(matrixPath)) {
    try { matrix = JSON.parse(readFileSync(matrixPath, 'utf8')); } catch { /* overwrite corrupt file */ }
  }
  if (!Array.isArray(matrix.rows)) matrix.rows = [];
  matrix.rows = matrix.rows.filter((r) => !(r.core === row.core && r.mode === row.mode));
  matrix.rows.push(row);
  matrix.rows.sort((a, b) => `${a.core}/${a.mode}`.localeCompare(`${b.core}/${b.mode}`));
  matrix.generatedAt = nowIso();
  mkdirSync(dirname(matrixPath), { recursive: true });
  writeFileSync(matrixPath, JSON.stringify(matrix, null, 2) + '\n');
}

/** Reads the existing row for a core/mode out of a matrix file, or null. */
function existingMatrixRow(matrixPath, core, mode) {
  if (!existsSync(matrixPath)) return null;
  try {
    const parsed = JSON.parse(readFileSync(matrixPath, 'utf8'));
    const rows = Array.isArray(parsed) ? parsed : parsed?.rows;
    if (!Array.isArray(rows)) return null;
    return rows.find((r) => r && r.core === core && r.mode === mode) ?? null;
  } catch {
    return null;
  }
}

/**
 * A partial `--check` run produces a row in which every check it did not
 * re-observe is false, so writing it over a verified row would silently
 * downgrade verified evidence. Such a write is refused unless the operator
 * passes --overwrite-verified; a full run may always update the row.
 */
export function matrixWriteDecision({ existingRow, partialRun, overwriteVerified }) {
  if (!partialRun) return { write: true, reason: 'full run may update the row' };
  if (overwriteVerified) return { write: true, reason: 'partial run with --overwrite-verified' };
  if (existingRow?.status === 'verified') {
    return {
      write: false,
      reason: `refusing to overwrite the verified ${existingRow.core}/${existingRow.mode} row with a partial --check run (pass --overwrite-verified to allow)`,
    };
  }
  return { write: true, reason: 'partial run on a row that is not verified' };
}

async function main() {
  const opts = parseArgs(process.argv.slice(2));
  if (!opts.core || !['qoder', 'opencode'].includes(opts.core)) throw new UsageError('--core must be qoder or opencode');
  if (!opts.mode || !['HOST', 'SANDBOX'].includes(opts.mode)) throw new UsageError('--mode must be HOST or SANDBOX');
  if (!opts.workspace) throw new UsageError('--workspace is required');
  if (!opts.output) throw new UsageError('--output is required');
  if (opts.core === 'qoder' && opts.model !== QODER_PINNED_MODEL) {
    throw new UsageError(`qoder probes pin model ${QODER_PINNED_MODEL}; refusing requested model ${opts.model}`);
  }
  const workspace = assertDisposableWorkspace(opts.workspace);
  opts.workspace = workspace;
  const runDir = resolve(opts.runDir || join(tmpdir(), `aria-probe-run-${Date.now()}`));
  mkdirSync(runDir, { recursive: true });

  const log = (line) => process.stdout.write(line + '\n');
  log(`probe ${opts.core}/${opts.mode}`);
  log(`  workspace  ${workspace}`);
  log(`  scratch    ${runDir}`);
  log(`  model      ${opts.model}`);

  let pat = null;
  let patSource = null;
  if (opts.core === 'qoder') {
    patSource = opts.patFile || process.env.ARIA_PROBE_PAT_FILE || null;
    if (!patSource || !existsSync(patSource)) throw new UsageError('--pat-file is required for qoder probes (or set ARIA_PROBE_PAT_FILE)');
    pat = readFileSync(patSource, 'utf8').trim();
    if (!pat) throw new UsageError('credential file is empty');
  }

  // Operator-specific absolute inputs become placeholders in every artifact;
  // pathForms() also covers their escaped and forward-slash renderings.
  const replacements = [[runDir, '<RUN>'], [workspace, '<WORKSPACE>']];
  if (opts.recordingDir) replacements.push([resolve(opts.recordingDir), '<FIXTURES>']);
  if (opts.profileSeed) replacements.push([resolve(opts.profileSeed), '<PROFILE-SEED>']);
  if (patSource) replacements.push([resolve(patSource), '<CREDENTIAL-FILE>']);
  const sanitize = buildRedactor({ secret: pat, replacements });
  const record = new Recording({ name: `${opts.core}-${opts.mode}`, redact: sanitize, log });

  const row = {
    core: opts.core,
    mode: opts.mode,
    status: 'blocked',
    checks: Object.fromEntries(CHECK_NAMES.map((name) => [name, false])),
    environment: {
      os: `${platform()} ${release()}`,
      platform: platform(),
      arch: arch(),
      node: process.version,
    },
    probe: { file: relFromRoot(HARNESS_FILE), startedAt: nowIso(), workspace: '<WORKSPACE>' },
  };
  const ctx = new ProbeContext({ opts, row, record, log, runDir, sanitize, redact: sanitize });

  try {
    if (opts.core === 'qoder') {
      if (opts.mode === 'SANDBOX') {
        const sandbox = await probeSandboxPrerequisites(ctx, { core: opts.core });
        const reason = sandbox.sandboxServerReachable
          ? 'sandbox server reachable but no verified sandbox image/endpoint workflow in this environment'
          : `OpenSandbox server not reachable at ${opts.sandboxEndpoint} (nothing listens) and no sandbox image present in docker/podman`;
        for (const check of CHECK_NAMES) ctx.block(check, reason);
        ctx.row.runtime = {
          core: 'qoder',
          binary: 'not launched (sandbox mode)',
          version: null,
          licenseSource: 'vendor CLI installed by the operator; Linux image packaging not verified',
          credentialSource: ctx.sanitize(patSource),
        };
      } else {
        const cliPath = resolveQoderCli(opts);
        if (!cliPath) throw new UsageError('qodercli executable not found; pass --cli <abs path>');
        await probeQoderHost(ctx, { cliPath, pat, patSource });
      }
    } else {
      const sandbox = await probeSandboxPrerequisites(ctx, { core: opts.core });
      const credentialPrerequisite = 'no run-owned model-provider credential in this environment (DEEPSEEK_API_KEY/LLM key unset)';
      const sandboxPrerequisite = sandbox.sandboxServerReachable
        ? 'sandbox server reachable but no verified lifecycle workflow in this environment'
        : `OpenSandbox server not reachable at ${opts.sandboxEndpoint} (nothing listens) and no sandbox image present in docker/podman`;
      if (opts.mode === 'HOST') {
        const opencodeExe = resolveOpenCode(opts);
        await probeOpenCodeHost(ctx, { opencodeExe });
        ctx.block('isolatedConfig', `OpenCode host configuration isolation (run-owned XDG roots) was applied but hostile-project control was not exercised (${credentialPrerequisite})`);
        ctx.block('pauseResume', `no model run and no verified OpenCode process-control workflow (${credentialPrerequisite})`);
        ctx.block('writersStopped', `no writer scenario can run without a model result (${credentialPrerequisite})`);
        ctx.block('stableExport', `no writer scenario can run without a model result (${credentialPrerequisite})`);
      } else {
        ctx.row.runtime = {
          core: 'opencode',
          binary: 'not launched (sandbox mode)',
          version: null,
          licenseSource: 'npm package opencode-ai (MIT); sandbox image contents not verified',
          credentialSource: 'not configured for this environment',
        };
        for (const check of CHECK_NAMES) ctx.block(check, `${credentialPrerequisite}; ${sandboxPrerequisite}`);
      }
    }
  } finally {
    for (const cleanup of ctx.cleanups.reverse()) {
      try { await cleanup(); } catch { /* cleanup best effort */ }
    }
  }

  row.checks = { ...ctx.checks };
  row.evidence = ctx.checkEvidence;
  row.observations = ctx.observations;
  row.limitations = [...new Set(ctx.limitations)];
  const unmet = Object.entries(row.checks).filter(([, value]) => value !== true);
  row.blockedPrerequisite = unmet.length
    ? [...new Set(unmet.map(([check]) => ctx.prerequisites[check] ?? 'not verified in this environment'))].join(' | ')
    : null;
  row.status = unmet.length === 0 ? 'verified' : (unmet.length === CHECK_NAMES.length ? 'blocked' : 'partial');
  row.probe.finishedAt = nowIso();

  // Sanitized artifacts ----------------------------------------------------
  const versionTag = (row.runtime?.version || 'unknown').replace(/[^0-9A-Za-z._-]/g, '_');
  const recordingDir = resolve(opts.recordingDir || join(runDir, 'recordings'));
  mkdirSync(recordingDir, { recursive: true });
  const jsonlPath = join(recordingDir, `${opts.core}-${opts.mode.toLowerCase()}-cli-${versionTag}-protocol.jsonl`);
  const jsonPath = join(recordingDir, `${opts.core}-${opts.mode.toLowerCase()}-cli-${versionTag}-events.json`);
  writeFileSync(jsonlPath, record.toJsonl());
  writeFileSync(jsonPath, record.toJson());
  row.artifacts = [relFromRoot(jsonlPath), relFromRoot(jsonPath)];
  if (ctx.stableExportSnapshot) {
    const exportPath = join(recordingDir, `${opts.core}-${opts.mode.toLowerCase()}-stable-export-${versionTag}.json`);
    writeFileSync(exportPath, JSON.stringify({ schemaVersion: 1, producedBy: 'probe-native.mjs', entries: ctx.stableExportSnapshot }, null, 2) + '\n');
    row.artifacts.push(relFromRoot(exportPath));
  }

  mkdirSync(dirname(resolve(opts.output)), { recursive: true });
  writeFileSync(resolve(opts.output), JSON.stringify(row, null, 2) + '\n');
  if (opts.matrixOut) {
    const matrixPath = resolve(opts.matrixOut);
    const decision = matrixWriteDecision({
      existingRow: existingMatrixRow(matrixPath, opts.core, opts.mode),
      partialRun: Boolean(opts.checks),
      overwriteVerified: opts.overwriteVerified === true,
    });
    if (decision.write) upsertRow(matrixPath, row);
    else log(`  matrix: ${decision.reason}`);
  }

  log('');
  for (const check of CHECK_NAMES) log(`  ${row.checks[check] ? '✔' : '✖'} ${check}`);
  log(`  status: ${row.status}`);
  if (row.blockedPrerequisite) log(`  blocked: ${row.blockedPrerequisite.slice(0, 400)}`);
  log(`  row: ${relFromRoot(resolve(opts.output))}`);
  log(`  artifacts: ${row.artifacts.join(', ')}`);
  return row.status === 'verified' ? 0 : 1;
}

// Only run the probe when executed as the entry module; importing the file (for
// `matrixWriteDecision` and friends) must not start a probe.
if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  try {
    process.exitCode = await main();
  } catch (error) {
    if (error instanceof UsageError) {
      process.stderr.write(`probe-native: ${error.message}\n\n${usage()}`);
      process.exitCode = 2;
    } else {
      process.stderr.write(`probe-native: ${error.stack || error.message}\n`);
      process.exitCode = 2;
    }
  }
}
