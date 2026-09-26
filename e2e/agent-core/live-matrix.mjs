#!/usr/bin/env node
// Live four-combination matrix runner (Task 20).
//
// Drives the REAL application (not the deterministic harness): boots the backend through the
// repository scripts, seeds an agent with the requested core + execution mode through the real
// REST API, runs coding tasks in the operator-admitted repository/worktree and verifies the
// outcome independently (file bytes on disk, terminal run state, approval round trips, cancel,
// pause/resume, workspace behaviour, no-fallback errors) instead of trusting the agent's
// self-report.
//
// Fails closed: every required prerequisite is checked BEFORE anything is launched and a missing
// prerequisite is a named refusal, never a skip. The Qoder core is pinned to `efficient` unless
// the operator sets an explicit paid opt-in.
//
// The credential (Qoder PAT) is read from a file, kept in memory, sent only over loopback HTTP as
// a request body and never printed, logged, persisted or passed in argv.
//
// Usage (see live-matrix.sh / live-matrix.ps1 for the shell entry points):
//   node e2e/agent-core/live-matrix.mjs --core qoder --mode HOST --model efficient \
//     --workspace <dir> --repo <git-repo> --evidence <file> [--pat-file <file>]
//     [--paid-opt-in] [--scenarios s1,s2] [--timeout-ms 600000] [--base-url http://127.0.0.1:8080]
//     [--no-backend]

import { spawn, spawnSync } from 'node:child_process';
import { createHash, randomBytes } from 'node:crypto';
import { existsSync, mkdirSync, readFileSync, statSync, writeFileSync, appendFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const REPO_ROOT = resolve(HERE, '..', '..');

const QODER_PINNED_MODEL = 'efficient';
const CORES = ['qoder', 'opencode'];
const MODES = ['HOST', 'SANDBOX'];
// Environment prerequisite set of the SANDBOX combinations (recorded by Task 1).
const SANDBOX_ENDPOINT_DEFAULT = 'http://127.0.0.1:8090';

// ---------------------------------------------------------------------------
// argument handling
// ---------------------------------------------------------------------------
function usage(message) {
  if (message) process.stderr.write(`live-matrix: ${message}\n`);
  process.stderr.write(
    'usage: live-matrix --core <qoder|opencode> --mode <HOST|SANDBOX> --model <id> \\\n' +
    '                   --workspace <dir> --repo <git repository> --evidence <file> \\\n' +
    '                   [--pat-file <file>] [--paid-opt-in] [--scenarios a,b] \\\n' +
    '                   [--timeout-ms <n>] [--expiry-budget-ms <n>] \\\n' +
    '                   [--backend-task-deadline-minutes <n>] [--base-url <url>] \\\n' +
    '                   [--no-backend] [--keep-backend]\n');
}

function parseArgs(argv) {
  const opts = {
    core: null, mode: null, model: null, workspace: null, repo: null, evidence: null,
    patFile: null, paidOptIn: false, scenarios: null, timeoutMs: 900000,
    expiryBudgetMs: 900000, backendTaskDeadlineMinutes: null,
    baseUrl: process.env.ARIA_LIVE_BASE_URL || 'http://127.0.0.1:8080',
    backend: true, keepBackend: false,
  };
  for (let i = 0; i < argv.length; i += 1) {
    const arg = argv[i];
    const next = () => {
      const value = argv[i + 1];
      if (value === undefined || value.startsWith('--')) throw new Error(`missing value for ${arg}`);
      i += 1;
      return value;
    };
    switch (arg) {
      case '--core': opts.core = next(); break;
      case '--mode': opts.mode = next().toUpperCase(); break;
      case '--model': opts.model = next(); break;
      case '--workspace': opts.workspace = next(); break;
      case '--repo': opts.repo = next(); break;
      case '--evidence': opts.evidence = next(); break;
      case '--pat-file': opts.patFile = next(); break;
      case '--paid-opt-in': opts.paidOptIn = true; break;
      case '--scenarios': opts.scenarios = next().split(',').map((s) => s.trim()).filter(Boolean); break;
      case '--timeout-ms': opts.timeoutMs = Number(next()); break;
      case '--expiry-budget-ms': opts.expiryBudgetMs = Number(next()); break;
      case '--backend-task-deadline-minutes': opts.backendTaskDeadlineMinutes = Number(next()); break;
      case '--base-url': opts.baseUrl = next(); break;
      case '--no-backend': opts.backend = false; break;
      case '--keep-backend': opts.keepBackend = true; break;
      case '--help': case '-h': usage(); process.exit(0); break;
      default: usage(`unknown argument: ${arg}`); process.exit(2);
    }
  }
  return opts;
}

// ---------------------------------------------------------------------------
// redaction (mirrors the Task 1 harness conventions)
// ---------------------------------------------------------------------------
const USER_PATH_RULES = [
  [/C:\\{1,8}Users\\{1,8}([^\\"'\s,]{1,32})/gi, 'C:\\Users\\<USER>'],
  [/C:\/{1,8}Users\/{1,8}([^/"'\s,]{1,32})/gi, 'C:/Users/<USER>'],
  [/(?<![A-Za-z]:)\/(?:home|Users)\/[^/\s"']+/g, '<HOME>'],
];
let SECRETS = [];
function redact(text) {
  let out = String(text);
  for (const secret of SECRETS) {
    if (secret && secret.length >= 8) out = out.split(secret).join('[REDACTED-SECRET]');
  }
  out = out.replace(/(sk-|pat-|QODER_PERSONAL_ACCESS_TOKEN["'\s:=]+)[A-Za-z0-9_\-.]{12,}/g, '$1[REDACTED-SECRET]');
  for (const [pattern, replacement] of USER_PATH_RULES) out = out.replace(pattern, replacement);
  return out;
}

// ---------------------------------------------------------------------------
// logging / evidence capture
// ---------------------------------------------------------------------------
const captured = [];
function capture(line) {
  const text = redact(line);
  captured.push(text);
  process.stdout.write(`${text}\n`);
}
const log = (...parts) => capture(parts.join(' '));

function section(title) {
  capture('');
  capture(`== ${title} ==`);
}

// ---------------------------------------------------------------------------
// process helpers
// ---------------------------------------------------------------------------
function runSync(command, args, { cwd = REPO_ROOT, env = process.env, timeoutMs = 60000, shell = false } = {}) {
  const result = spawnSync(command, args, {
    cwd, env, timeout: timeoutMs, encoding: 'utf8', windowsHide: true, shell,
  });
  return {
    status: result.status === null ? -1 : result.status,
    stdout: result.stdout || '',
    stderr: result.stderr || '',
  };
}

function sha256File(file) {
  return createHash('sha256').update(readFileSync(file)).digest('hex');
}

function fileText(file) {
  return readFileSync(file, 'utf8');
}

function exists(path) {
  try { statSync(path); return true; } catch { return false; }
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

// ---------------------------------------------------------------------------
// HTTP client (operator bearer identity)
// ---------------------------------------------------------------------------
class Api {
  constructor(baseUrl, operatorToken) {
    this.baseUrl = baseUrl.replace(/\/$/, '');
    this.operatorToken = operatorToken;
    this.trace = [];
  }

  async call(method, path, body) {
    const headers = { Authorization: `Bearer ${this.operatorToken}`, Accept: 'application/json' };
    if (body !== undefined) headers['Content-Type'] = 'application/json';
    const response = await fetch(`${this.baseUrl}${path}`, {
      method,
      headers,
      body: body === undefined ? undefined : JSON.stringify(body),
    });
    const text = await response.text();
    let json = null;
    try { json = text ? JSON.parse(text) : null; } catch { /* non-JSON payload kept raw */ }
    const record = { method, path, status: response.status, body: redact(text).slice(0, 4000) };
    this.trace.push(record);
    return { status: response.status, json, text, record };
  }

  get(path) { return this.call('GET', path); }
}

async function waitForHealth(baseUrl, timeoutMs) {
  const deadline = Date.now() + timeoutMs;
  let last = 'no response';
  while (Date.now() < deadline) {
    try {
      const response = await fetch(`${baseUrl}/actuator/health`);
      last = `HTTP ${response.status} ${(await response.text()).slice(0, 120)}`;
      if (response.status === 200) return { ok: true, detail: last };
    } catch (error) {
      last = String(error && error.message ? error.message : error);
    }
    await sleep(2000);
  }
  return { ok: false, detail: last };
}

async function pollUntil(fn, { timeoutMs, intervalMs = 1500, label = 'condition' }) {
  const deadline = Date.now() + timeoutMs;
  let last;
  while (Date.now() < deadline) {
    last = await fn();
    if (last && last.done) return last;
    await sleep(intervalMs);
  }
  return { done: false, timeout: true, label, last };
}

// ---------------------------------------------------------------------------
// prerequisites (fail closed, named refusals)
// ---------------------------------------------------------------------------
function readSecretFile(file) {
  if (!file || !existsSync(file)) return null;
  const value = readFileSync(file, 'utf8').trim();
  return value.length > 0 ? value : null;
}

function qoderCliCandidates() {
  return [
    process.env.ARIA_LIVE_QODER_CLI,
    process.env.ARIA_PROBE_QODER_CLI,
    process.env.USERPROFILE ? join(process.env.USERPROFILE, '.qoder', 'bin', 'qodercli', 'qodercli.exe') : null,
  ].filter(Boolean);
}

function openCodeBinary() {
  if (process.env.ARIA_LIVE_OPENCODE && existsSync(process.env.ARIA_LIVE_OPENCODE)) {
    return process.env.ARIA_LIVE_OPENCODE;
  }
  const which = runSync('where', ['opencode'], { timeoutMs: 20000, shell: process.platform === 'win32' });
  const first = which.stdout.split(/\r?\n/).map((s) => s.trim()).filter(Boolean)[0];
  return first || null;
}

function sandboxPrerequisites() {
  const endpoint = process.env.ARIA_LIVE_SANDBOX_URL || SANDBOX_ENDPOINT_DEFAULT;
  const unmet = [];
  let serverReachable = false;
  const health = runSync('curl', ['-s', '-o', process.platform === 'win32' ? 'NUL' : '/dev/null',
    '-w', '%{http_code}', `${endpoint}/health`], { timeoutMs: 15000 });
  serverReachable = health.status === 0 && /^2\d\d$/.test(health.stdout.trim());
  if (!serverReachable) {
    unmet.push(`OpenSandbox server not reachable at ${endpoint} (no 2xx /health response)`);
  }
  const images = [];
  for (const runtime of ['docker', 'podman']) {
    const result = runSync(runtime, ['images', '--format', '{{.Repository}}:{{.Tag}}'], { timeoutMs: 30000 });
    if (result.status === 0) {
      for (const line of result.stdout.split(/\r?\n/)) {
        if (/sandbox/i.test(line)) images.push(`${runtime}:${line.trim()}`);
      }
    }
  }
  if (images.length === 0) {
    unmet.push('no sandbox image present in the local docker/podman image stores');
  }
  return { endpoint, serverReachable, images, unmet };
}

function preflight(opts) {
  const unmet = [];
  const notes = [];

  if (!CORES.includes(opts.core)) unmet.push(`--core must be one of ${CORES.join(', ')} (got ${opts.core})`);
  if (!MODES.includes(opts.mode)) unmet.push(`--mode must be one of ${MODES.join(', ')} (got ${opts.mode})`);
  if (unmet.length > 0) return { unmet, notes, refusal: null };

  // Model pin: the Qoder core runs `efficient` unless an explicit paid opt-in was given.
  const paidOptIn = opts.paidOptIn || process.env.ARIA_LIVE_PAID_MODEL_OPT_IN === '1';
  if (opts.core === 'qoder' && opts.model && opts.model !== QODER_PINNED_MODEL && !paidOptIn) {
    return {
      unmet: [
        `model-pin refusal: the ${opts.core} core is pinned to '${QODER_PINNED_MODEL}' ` +
        `(requested '${opts.model}'); a paid model needs the explicit opt-in ` +
        '(--paid-opt-in or ARIA_LIVE_PAID_MODEL_OPT_IN=1)',
      ],
      notes,
      refusal: 'model',
    };
  }
  if (opts.core === 'qoder' && !opts.model) {
    opts.model = QODER_PINNED_MODEL;
    notes.push(`no --model given; pinned to ${QODER_PINNED_MODEL}`);
  }

  // Credential prerequisite.
  if (opts.core === 'qoder') {
    const patFile = opts.patFile || process.env.ARIA_LIVE_PAT_FILE || process.env.ARIA_PROBE_PAT_FILE || null;
    opts.patFile = patFile;
    const secret = readSecretFile(patFile);
    if (!secret) {
      unmet.push(
        `qoder runtime credential missing: no readable, non-empty PAT file ` +
        `(looked at ${patFile || 'no path: --pat-file/ARIA_LIVE_PAT_FILE unset'})`);
    }
    if (!opts.paidOptIn) opts.paidOptIn = paidOptIn;
  } else {
    // OpenCode needs a run-owned model-provider credential; the bundled free tier is not one.
    const providerKeys = ['DEEPSEEK_API_KEY', 'LLM_API_KEY', 'OPENCODE_API_KEY', 'OPENAI_API_KEY']
      .filter((key) => (process.env[key] || '').trim().length > 0);
    const envFile = join(REPO_ROOT, 'agent-control-tower', '.env');
    const repoEnv = existsSync(envFile) && readFileSync(envFile, 'utf8').trim().length > 0;
    if (providerKeys.length === 0 && !repoEnv) {
      unmet.push(
        'opencode model-provider credential missing: no run-owned credential in this environment ' +
        '(DEEPSEEK_API_KEY/LLM_API_KEY/OPENCODE_API_KEY unset and agent-control-tower/.env empty)');
    }
    notes.push(`opencode provider credential sources observed: ${providerKeys.join(',') || 'none'}`);
  }

  // CLI / binary prerequisite.
  if (opts.core === 'qoder') {
    const cli = qoderCliCandidates().find((candidate) => existsSync(candidate));
    if (!cli) {
      unmet.push('qoder CLI binary missing: no qodercli executable found (ARIA_LIVE_QODER_CLI or the operator install path)');
    } else {
      opts.cli = cli;
      const version = runSync(cli, ['--version'], { timeoutMs: 30000 });
      opts.cliVersion = (version.stdout || version.stderr).trim().split(/\r?\n/)[0] || 'unknown';
      notes.push(`qoder CLI: ${redact(cli)} (${opts.cliVersion})`);
    }
    // The qoder core launches the committed ACP bridge; a missing build refuses
    // the launch loudly, so it is a prerequisite here, not a surprise later.
    const bridge = join(REPO_ROOT, 'packages', 'qoder-acp-bridge', 'dist', 'main.js');
    if (!existsSync(bridge)) {
      unmet.push('qoder ACP bridge entry missing: ' + redact(bridge) + ' (run `pnpm build` in packages/qoder-acp-bridge)');
    } else {
      opts.bridgeEntry = bridge;
      notes.push(`qoder bridge entry: ${redact(bridge)}`);
    }
  } else {
    const binary = openCodeBinary();
    if (!binary) unmet.push('opencode binary missing: no opencode executable on PATH (ARIA_LIVE_OPENCODE)');
    else {
      opts.cli = binary;
      const version = runSync('opencode', ['--version'], { timeoutMs: 30000, shell: process.platform === 'win32' });
      opts.cliVersion = (version.stdout || version.stderr).trim().split(/\r?\n/)[0] || 'unknown';
      notes.push(`opencode CLI: ${redact(binary)} (${opts.cliVersion})`);
    }
  }

  // SANDBOX mode requires a container runtime AND an OpenSandbox server (plus an image).
  if (opts.mode === 'SANDBOX') {
    const sandbox = sandboxPrerequisites();
    opts.sandbox = sandbox;
    if (sandbox.unmet.length > 0) unmet.push(...sandbox.unmet);
    else notes.push(`OpenSandbox endpoint ${sandbox.endpoint} reachable; images ${sandbox.images.join(', ')}`);
  }

  // Base repository / workspace prerequisite.
  if (!opts.repo) {
    unmet.push('missing prerequisite: --repo (the operator-admitted git repository) is required');
  } else if (!existsSync(opts.repo)) {
    unmet.push(`missing prerequisite: --repo does not exist: ${redact(opts.repo)}`);
  } else {
    const gitDir = runSync('git', ['-C', opts.repo, 'rev-parse', '--git-dir'], { timeoutMs: 30000 });
    if (gitDir.status !== 0) unmet.push(`missing prerequisite: --repo is not a git repository: ${redact(opts.repo)}`);
    else {
      const head = runSync('git', ['-C', opts.repo, 'rev-parse', 'HEAD'], { timeoutMs: 30000 });
      opts.repoHead = head.stdout.trim();
      notes.push(`admitted repository HEAD ${opts.repoHead}`);
    }
  }
  if (!opts.workspace) {
    unmet.push('missing prerequisite: --workspace (the Direct-mode working directory) is required');
  } else if (!existsSync(opts.workspace)) {
    unmet.push(`missing prerequisite: --workspace does not exist: ${redact(opts.workspace)}`);
  }

  // Backend prerequisite.
  for (const command of ['java', 'mvn']) {
    const probe = runSync(command, ['-version'], { timeoutMs: 30000, shell: process.platform === 'win32' });
    if (probe.status !== 0) unmet.push(`missing prerequisite: ${command} is not runnable`);
  }
  if (!opts.backend && !process.env.ARIA_LIVE_OPERATOR_TOKEN) {
    unmet.push('missing prerequisite: --no-backend requires ARIA_LIVE_OPERATOR_TOKEN for the running backend');
  }

  return { unmet, notes, refusal: null };
}

// ---------------------------------------------------------------------------
// backend lifecycle
// ---------------------------------------------------------------------------
/**
 * The run-owned workspace root the backend serves (and where a WORKTREE run's
 * checkout appears). The operator may relocate it — a run whose SOURCE is this
 * repository itself must, because the production guard refuses a runtime root
 * inside the source workspace (`RunWorkspaceService.rejectRuntimeRootInsideSource`)
 * — so the runner resolves it once from the environment, boots the backend with
 * exactly that value, and asserts against the same path.
 */
function backendRuntimeRoot() {
  return process.env.ARIA_WORKSPACES_RUNTIME_ROOT
    || join(REPO_ROOT, 'agent-control-tower', 'act-app', 'data', 'workspaces', 'runs');
}

function backendResultRoot() {
  return process.env.ARIA_WORKSPACES_RESULT_ROOT
    || join(REPO_ROOT, 'agent-control-tower', 'act-app', 'data', 'workspaces', 'results');
}

/** Backend environment pins the runner applies, recorded into the evidence row. */
function backendPins(opts) {
  const pins = {};
  // Fix round 1: the shipped start path is exercised as shipped -- the run-owned
  // roots (absolute, exported by scripts/start-backend.*), the reviewed model pin
  // (aria.cores.qoder.model, default `efficient`) and the built bridge entry (the
  // script's absolute export / the adapter's resolution) are all configuration
  // defaults now, so the runner pins none of them. Only the operator's installed
  // Qoder CLI is machine-specific and still pinned.
  //
  // The workspace roots are the exception: the runner forwards the values it
  // resolves itself (environment first, the script's defaults otherwise) so the
  // backend serves exactly the root the runner then asserts against.
  pins.ARIA_WORKSPACES_RUNTIME_ROOT = backendRuntimeRoot();
  pins.ARIA_WORKSPACES_RESULT_ROOT = backendResultRoot();
  if (opts.core === 'qoder' && opts.cli) {
    pins.ARIA_CORES_QODER_EXECUTABLE = opts.cli;
  } else if (opts.core !== 'qoder') {
    pins.ARIA_CORES_OPENCODE_MODEL = opts.model;
  }
  return pins;
}

function startBackend(opts, backendLog) {
  const pins = backendPins(opts);
  const env = {
    ...process.env,
    SPRING_PROFILES_ACTIVE: 'h2',
    ARIA_OPERATOR_BEARER_TOKEN: opts.operatorToken,
    ARIA_RUNTIME_CREDENTIAL_KEY: opts.credentialKey,
    ...pins,
  };
  if (opts.backendTaskDeadlineMinutes !== null) {
    // The expiry case asserts an ask reaching EXPIRED. A run-owned core session's
    // ask expires with the RUN's deadline (the approval gate's own window is the
    // legacy path's bound), and the frozen deadline is read from aria.tasks.* at
    // run creation; a system property beats application.yml and is inherited by
    // the JVM the start script spawns. The approval window is aligned to the same
    // value so both bounds agree.
    const minutes = opts.backendTaskDeadlineMinutes;
    const existing = process.env.JAVA_TOOL_OPTIONS ? ` ${process.env.JAVA_TOOL_OPTIONS}` : '';
    env.JAVA_TOOL_OPTIONS =
      `-Daria.tasks.deadline-minutes=${minutes} -Dapprovals.timeout-ms=${minutes * 60000}${existing}`;
    pins.JAVA_TOOL_OPTIONS = env.JAVA_TOOL_OPTIONS;
  }
  opts.backendPins = pins;
  capture(`backend environment pins: ${Object.keys(pins).sort().map((k) => `${k}=${redact(pins[k])}`).join(' | ')}`);
  let child;
  if (process.platform === 'win32') {
    const script = join(REPO_ROOT, 'scripts', 'start-backend.ps1');
    // The repository script needs pwsh (it relies on PowerShell 7 native-command semantics).
    const probe = runSync('where', ['pwsh'], { timeoutMs: 15000, shell: true });
    const shell = process.env.ARIA_LIVE_PWSH
      || (probe.status === 0 && probe.stdout.trim() ? probe.stdout.split(/\r?\n/)[0].trim() : 'powershell');
    const args = ['-NoProfile', '-File', script, '-SkipSandbox'];
    if (process.env.ARIA_LIVE_SKIP_BUILD === '1') args.push('-SkipBuild');
    // The script defaults the ADK provider to qoder for Host mode; fix round 1 made
    // that default bootable (the registry consults the core catalog), so the shipped
    // default is exercised. ARIA_LIVE_BACKEND_ADK_PROVIDER remains an explicit
    // override for other experiments, never a workaround.
    if (process.env.ARIA_LIVE_BACKEND_ADK_PROVIDER) {
      args.push('-AdkProvider', process.env.ARIA_LIVE_BACKEND_ADK_PROVIDER);
    }
    capture(`$ ${shell} -NoProfile -File scripts/start-backend.ps1 -SkipSandbox${process.env.ARIA_LIVE_BACKEND_ADK_PROVIDER ? ` -AdkProvider ${process.env.ARIA_LIVE_BACKEND_ADK_PROVIDER}` : ''} (backend cwd ${redact(REPO_ROOT)})`);
    child = spawn(shell, args, { cwd: REPO_ROOT, env, windowsHide: true, stdio: ['ignore', 'pipe', 'pipe'] });
  } else {
    const script = join(REPO_ROOT, 'scripts', 'start-backend.sh');
    const args = [script, '--skip-sandbox'];
    if (process.env.ARIA_LIVE_SKIP_BUILD === '1') args.push('--skip-build');
    if (process.env.ARIA_LIVE_BACKEND_ADK_PROVIDER) args.push(`--provider=${process.env.ARIA_LIVE_BACKEND_ADK_PROVIDER}`);
    capture(`$ bash scripts/start-backend.sh --skip-sandbox${process.env.ARIA_LIVE_BACKEND_ADK_PROVIDER ? ` --provider=${process.env.ARIA_LIVE_BACKEND_ADK_PROVIDER}` : ''}`);
    child = spawn('bash', args, { cwd: REPO_ROOT, env, windowsHide: true, stdio: ['ignore', 'pipe', 'pipe'] });
  }
  const pipe = (chunk) => {
    const text = redact(chunk.toString());
    appendFileSync(backendLog, text);
  };
  child.stdout.on('data', pipe);
  child.stderr.on('data', pipe);
  child.on('exit', (code) => appendFileSync(backendLog, `\n[backend exited with code ${code}]\n`));
  return child;
}

function stopBackend(child, opts) {
  if (!child || child.exitCode !== null) return;
  capture('stopping backend process tree');
  runSync('taskkill', ['/PID', String(child.pid), '/T', '/F'], { timeoutMs: 60000 });
  if (process.platform !== 'win32') {
    try { process.kill(-child.pid, 'SIGTERM'); } catch { /* already gone */ }
  }
}

// ---------------------------------------------------------------------------
// matrix scenarios
// ---------------------------------------------------------------------------
class Matrix {
  constructor(opts, api) {
    this.opts = opts;
    this.api = api;
    this.results = [];
    this.records = {};
  }

  async createAgent({ core, mode, workspaceMode, workspacePath, name }) {
    const body = {
      name,
      description: `live matrix ${core}/${mode} ${workspaceMode}`,
      agentType: 'ADK',
      adkProvider: core,
      executionMode: mode,
      workspaceMode,
      workspacePath,
      model: this.opts.model,
      config: {},
    };
    const response = await this.api.call('POST', '/api/v1/agents', body);
    if (response.status >= 300) {
      throw new Error(`agent creation refused (HTTP ${response.status}): ${response.record.body}`);
    }
    capture(`agent created: id=${response.json.id} core=${core} mode=${mode} workspaceMode=${workspaceMode}`);
    return response.json;
  }

  async dispatch(agentId, prompt, label) {
    const response = await this.api.call('POST', '/api/v1/runs', { agentId, promptSeed: prompt });
    if (response.status >= 300) {
      throw new Error(`run dispatch refused (HTTP ${response.status}): ${response.record.body}`);
    }
    const runId = response.json.id;
    capture(`run ${label}: id=${runId} status=${response.json.status}`);
    return runId;
  }

  async runState(runId) {
    const response = await this.api.get(`/api/v1/runs/${runId}`);
    return response.json;
  }

  async approvals(runId) {
    const response = await this.api.get(`/api/v1/approvals?status=PENDING`);
    const rows = Array.isArray(response.json) ? response.json : [];
    return rows.filter((row) => row.runId === runId);
  }

  async waitForAsk(runId, timeoutMs, label) {
    return pollUntil(async () => {
      const pending = await this.approvals(runId);
      if (pending.length > 0) return { done: true, ask: pending[0], pending };
      const state = await this.runState(runId);
      return { done: false, state };
    }, { timeoutMs, intervalMs: 2000, label });
  }

  async terminal(runId, timeoutMs) {
    const terminalStates = new Set(['COMPLETED', 'FAILED', 'CANCELLED', 'ABORTED']);
    return pollUntil(async () => {
      const state = await this.runState(runId);
      return { done: terminalStates.has(state.status), state };
    }, { timeoutMs, intervalMs: 3000, label: `terminal state of run ${runId}` });
  }

  async decide(askId, approved, reason) {
    return this.api.call('POST', `/api/v1/approvals/${askId}/decide`, { approved, reason: reason || null });
  }

  async usage(runId) {
    const calls = await this.api.get(`/api/v1/prompt-calls?runId=${runId}`);
    const rows = Array.isArray(calls.json) ? calls.json : [];
    return {
      promptCalls: rows.map((row) => ({
        provider: row.provider, model: row.model, inputTokens: row.inputTokens,
        outputTokens: row.outputTokens, outcome: row.outcome,
      })),
      recordedModelAcknowledged: rows.map((row) => row.model).filter(Boolean),
    };
  }

  record(id, title, status, criteria, details) {
    this.results.push({ id, title, status, criteria, details });
    capture(`  [${status}] ${id} — ${title}`);
    for (const [name, value] of Object.entries(criteria)) capture(`      ${value ? 'PASS' : 'FAIL'} ${name}`);
  }

  // --- scenarios ---------------------------------------------------------

  async codingTask(agentId, core, mode) {
    const expected = `aria-live-${core}-${mode}`;
    const prompt =
      `Create the file aria-live-probe.txt in your current working directory with exactly this ` +
      `content and no trailing newline: ${expected}\n` +
      'Use the file-write tool. Then reply with the exact text DONE.';
    const runId = await this.dispatch(agentId, prompt, 'coding-task');
    // A WORKTREE run owns runtimeRoot/worktrees/<runId>; the file bytes are read from there.
    const dir = join(backendRuntimeRoot(), 'worktrees', runId);
    const target = join(dir, 'aria-live-probe.txt');
    const ask = await this.waitForAsk(runId, this.opts.timeoutMs, 'coding-task ask');
    let approvedOnce = false;
    if (ask.done) {
      const decision = await this.decide(ask.ask.id, true, 'live matrix: approve once');
      approvedOnce = decision.status < 300;
      capture(`coding-task: approved ask ${ask.ask.id} (HTTP ${decision.status}, askType=${ask.ask.askType})`);
    }
    const end = await this.terminal(runId, this.opts.timeoutMs);
    const state = end.state || (await this.runState(runId));
    const bytes = exists(target) ? fileText(target) : null;
    const usage = await this.usage(runId);
    this.records.codingTask = { runId, state, bytes, usage, askSeen: ask.done, dir };
    this.record('coding-task', `one real coding task in ${core}/${mode}`,
      state.status === 'COMPLETED' && bytes === expected ? 'PASS' : 'FAIL', {
        'run reached COMPLETED': state.status === 'COMPLETED',
        'file bytes on disk equal the requested content': bytes === expected,
        'approval ask observed': ask.done,
        'ask approved once': approvedOnce,
      },
      {
        runId, runStatus: state.status, observedBytes: bytes, expectedBytes: expected,
        sha256: exists(target) ? sha256File(target) : null, worktree: redact(dir),
        finalOutput: (state.finalOutput || '').slice(0, 400), usage,
      });
    return { runId, status: state.status, bytes, dir, usage };
  }

  async approveOnce(agentId) {
    const prompt =
      'Perform two separate file writes, each with its own file-write tool call. ' +
      'First write aria-live-once.txt with the exact content first-pass. ' +
      'Then, in a second tool call, overwrite aria-live-once.txt with the exact content second-pass. ' +
      'Then reply DONE.';
    const runId = await this.dispatch(agentId, prompt, 'approve-once');
    // A WORKTREE run owns runtimeRoot/worktrees/<runId>: the case reads ITS OWN run's
    // worktree, never another run's checkout.
    const dir = join(backendRuntimeRoot(), 'worktrees', runId);
    const first = join(dir, 'aria-live-once.txt');
    const firstAsk = await this.waitForAsk(runId, this.opts.timeoutMs, 'approve-once first ask');
    if (!firstAsk.done) {
      this.record('approve-once', 'a one-use grant does not cover the second write', 'NOT VERIFIED', {
        'first ask observed': false,
      }, { note: 'no approval ask appeared' });
      return;
    }
    await this.decide(firstAsk.ask.id, true, 'live matrix: approve first write only');
    capture(`approve-once: approved first ask ${firstAsk.ask.id}`);
    const second = await pollUntil(async () => {
      const pending = await this.approvals(runId);
      const state = await this.runState(runId);
      const other = pending.find((row) => row.id !== firstAsk.ask.id);
      return { done: Boolean(other), ask: other, state };
    }, { timeoutMs: 240000, intervalMs: 2000, label: 'second ask after a one-use grant' });
    const secondAskSeen = Boolean(second.ask);
    if (secondAskSeen) await this.decide(second.ask.id, true, 'live matrix: approve second write');
    const end = await this.terminal(runId, this.opts.timeoutMs);
    const state = end.state || (await this.runState(runId));
    const bytes = exists(first) ? fileText(first) : null;
    this.record('approve-once', 'a one-use grant re-asks for the second write', 
      secondAskSeen && state.status === 'COMPLETED' && bytes === 'second-pass' ? 'PASS' : 'FAIL', {
        'second approval ask observed after the one-use grant': secondAskSeen,
        'run reached COMPLETED': state.status === 'COMPLETED',
        'final bytes are the second pass': bytes === 'second-pass',
      }, { runId, firstAsk: firstAsk.ask.id, secondAsk: second.ask ? second.ask.id : null, observedBytes: bytes });
  }

  async deny(agentId) {
    const prompt = 'Create the file aria-live-denied.txt with the exact content denied-should-not-exist. Then reply DONE.';
    const runId = await this.dispatch(agentId, prompt, 'deny');
    // A WORKTREE run owns runtimeRoot/worktrees/<runId>: the case reads ITS OWN run's
    // worktree, never another run's checkout.
    const dir = join(backendRuntimeRoot(), 'worktrees', runId);
    const target = join(dir, 'aria-live-denied.txt');
    const ask = await this.waitForAsk(runId, this.opts.timeoutMs, 'deny ask');
    if (!ask.done) {
      this.record('deny', 'a denial leaves the workspace unwritten', 'NOT VERIFIED', { 'ask observed': false }, { runId });
      return;
    }
    const decision = await this.decide(ask.ask.id, false, 'live matrix: deny');
    capture(`deny: denied ask ${ask.ask.id} (HTTP ${decision.status})`);
    const end = await this.terminal(runId, this.opts.timeoutMs);
    const state = end.state || (await this.runState(runId));
    const decided = await this.api.get(`/api/v1/approvals/${ask.ask.id}`);
    const askStatus = decided.json ? decided.json.status : null;
    const terminal = ['COMPLETED', 'FAILED', 'ABORTED', 'CANCELLED'].includes(state.status);
    // A denial prevents the write; whether the CORE then ends its turn successfully
    // is the core's own business (a refused tool call is a legitimate turn outcome),
    // so the terminal status is recorded, not pinned to a failure.
    this.record('deny', 'a denial leaves the workspace unwritten',
      !exists(target) && askStatus === 'DENIED' && terminal ? 'PASS' : 'FAIL', {
        'denied file absent from disk': !exists(target),
        'ask reached DENIED': askStatus === 'DENIED',
        'run reached a terminal state': terminal,
      }, {
        runId, askStatus, runStatus: state.status,
        finalOutput: (state.finalOutput || '').slice(0, 300),
      });
  }

  async expiry(agentId) {
    const prompt = 'Create the file aria-live-expired.txt with the exact content expired-should-not-exist. Then reply DONE.';
    const runId = await this.dispatch(agentId, prompt, 'expiry');
    // A WORKTREE run owns runtimeRoot/worktrees/<runId>: the case reads ITS OWN run's
    // worktree, never another run's checkout.
    const dir = join(backendRuntimeRoot(), 'worktrees', runId);
    const target = join(dir, 'aria-live-expired.txt');
    const ask = await this.waitForAsk(runId, this.opts.timeoutMs, 'expiry ask');
    if (!ask.done) {
      this.record('expiry', 'an undecided ask expires and writes nothing', 'NOT VERIFIED', { 'ask observed': false }, { runId });
      return;
    }
    const requestedAt = Date.parse(ask.ask.requestedAt);
    const expiresAt = Date.parse(ask.ask.expiresAt);
    const declaredWindowMs = expiresAt - requestedAt;
    capture(`expiry: ask ${ask.ask.id} left undecided (requestedAt=${ask.ask.requestedAt} expiresAt=${ask.ask.expiresAt})`);
    // The ask expires by ITS OWN declared window, so the wait is the declared
    // window itself (not a guess). A window longer than the runner's budget is
    // not a failure of the product: the stack was simply booted with the
    // production window, so the case is NOT VERIFIED with the exact window
    // reported instead of a wait that outlives the runner.
    if (!Number.isFinite(declaredWindowMs) || declaredWindowMs > this.opts.expiryBudgetMs) {
      this.record('expiry', 'an undecided ask expires and writes nothing', 'NOT VERIFIED', {
        'ask observed': true,
        'declared expiry window within the runner budget': false,
      }, {
        runId, requestedAt: ask.ask.requestedAt, expiresAt: ask.ask.expiresAt, declaredWindowMs,
        runnerBudgetMs: this.opts.expiryBudgetMs,
        note: 'boot the stack with a short approvals window (aria.approvals.timeout-ms) to assert expiry',
      });
      return;
    }
    const expired = await pollUntil(async () => {
      const response = await this.api.get(`/api/v1/approvals/${ask.ask.id}`);
      return { done: response.json && response.json.status !== 'PENDING', ask: response.json };
    }, { timeoutMs: declaredWindowMs + 60000, intervalMs: 3000, label: 'ask expiry' });
    const end = await this.terminal(runId, this.opts.timeoutMs);
    const state = end.state || (await this.runState(runId));
    this.record('expiry', 'an undecided ask expires and writes nothing', 
      expired.done && expired.ask.status === 'EXPIRED' && !exists(target) ? 'PASS' : 'FAIL', {
        'ask reached a non-PENDING state': expired.done,
        'ask state is EXPIRED': Boolean(expired.ask && expired.ask.status === 'EXPIRED'),
        'expired file absent from disk': !exists(target),
      }, {
        runId, askStatus: expired.ask ? expired.ask.status : null, runStatus: state.status,
        declaredWindowMs,
      });
  }

  async cancel(agentId, core) {
    const prompt =
      'Run this exact Bash command, do not modify it: ' +
      'node -e "const fs=require(\'fs\');let i=0;const t=setInterval(()=>{fs.appendFileSync(\'aria-live-ticks.txt\',\'tick \'+(++i)+\'\\n\')},400);setTimeout(()=>{clearInterval(t)},120000)" ' +
      'Then reply DONE.';
    const runId = await this.dispatch(agentId, prompt, 'cancel');
    // A WORKTREE run owns runtimeRoot/worktrees/<runId>: the case reads ITS OWN run's
    // worktree, never another run's checkout.
    const dir = join(backendRuntimeRoot(), 'worktrees', runId);
    const ticks = join(dir, 'aria-live-ticks.txt');
    await this.waitForAsk(runId, 120000, 'cancel ask');
    const pending = await this.approvals(runId);
    for (const ask of pending) await this.decide(ask.id, true, 'live matrix: allow the writer to start');
    const started = await pollUntil(async () => {
      const state = await this.runState(runId);
      return { done: exists(ticks) && statSync(ticks).size > 0, state };
    }, { timeoutMs: 180000, intervalMs: 2000, label: 'writer started' });
    if (!started.done) {
      this.record('cancel', 'cancel stops the run and its writers', 'NOT VERIFIED', { 'writer produced bytes': false },
        { runId, note: 'no tick file bytes appeared; the writer scenario did not start' });
      return;
    }
    const beforeCancel = statSync(ticks).size;
    const cancelled = await this.api.call('POST', `/api/v1/runs/${runId}/cancel`);
    capture(`cancel: requested (HTTP ${cancelled.status}) with ${beforeCancel} bytes written`);
    const end = await this.terminal(runId, 300000);
    const state = end.state || (await this.runState(runId));
    const frozenA = exists(ticks) ? statSync(ticks).size : 0;
    await sleep(6000);
    const frozenB = exists(ticks) ? statSync(ticks).size : 0;
    this.record('cancel', 'cancel stops the run and its writers', 
      state.status === 'CANCELLED' && frozenA === frozenB ? 'PASS' : 'FAIL', {
        'run reached CANCELLED': state.status === 'CANCELLED',
        'writer bytes frozen after cancellation': frozenA === frozenB,
      }, { runId, runStatus: state.status, bytesAtCancel: beforeCancel, bytesAfter: frozenA, bytesAfterWait: frozenB });
  }

  async pauseResume(agentId) {
    const prompt =
      'Run this exact Bash command, do not modify it: ' +
      'node -e "const fs=require(\'fs\');let i=0;const t=setInterval(()=>{fs.appendFileSync(\'aria-live-pause.txt\',\'tick \'+(++i)+\'\\n\')},400);setTimeout(()=>{clearInterval(t)},120000)" ' +
      'Then reply DONE.';
    const runId = await this.dispatch(agentId, prompt, 'pause-resume');
    // A WORKTREE run owns runtimeRoot/worktrees/<runId>: the case reads ITS OWN run's
    // worktree, never another run's checkout.
    const dir = join(backendRuntimeRoot(), 'worktrees', runId);
    const ticks = join(dir, 'aria-live-pause.txt');
    await this.waitForAsk(runId, 120000, 'pause-resume ask');
    const pending = await this.approvals(runId);
    for (const ask of pending) await this.decide(ask.id, true, 'live matrix: allow the writer to start');
    const started = await pollUntil(async () => {
      const state = await this.runState(runId);
      return { done: exists(ticks) && statSync(ticks).size > 0, state };
    }, { timeoutMs: 180000, intervalMs: 2000, label: 'pause writer started' });
    if (!started.done) {
      this.record('pause-resume', 'pause freezes the writer tree and resume continues it', 'NOT VERIFIED',
        { 'writer produced bytes': false }, { runId, note: 'no tick bytes appeared' });
      return;
    }
    const paused = await this.api.call('POST', `/api/v1/runs/${runId}/pause`);
    const pausedSizeA = exists(ticks) ? statSync(ticks).size : 0;
    await sleep(5000);
    const pausedSizeB = exists(ticks) ? statSync(ticks).size : 0;
    const resumed = await this.api.call('POST', `/api/v1/runs/${runId}/resume`);
    await sleep(5000);
    const resumedSize = exists(ticks) ? statSync(ticks).size : 0;
    capture(`pause-resume: pause HTTP ${paused.status} (${pausedSizeA}->${pausedSizeB} bytes while paused), resume HTTP ${resumed.status} (${resumedSize} bytes after)`);
    const state = await this.runState(runId);
    this.record('pause-resume', 'pause freezes the writer tree and resume continues it', 
      paused.status < 300 && resumed.status < 300 && pausedSizeA === pausedSizeB && resumedSize > pausedSizeB ? 'PASS' : 'FAIL', {
        'pause accepted': paused.status < 300,
        'writer bytes frozen while paused': pausedSizeA === pausedSizeB,
        'resume accepted': resumed.status < 300,
        'writer bytes grew after resume': resumedSize > pausedSizeB,
      }, { runId, runStatus: state.status, pausedSizeA, pausedSizeB, resumedSize });
    await this.api.call('POST', `/api/v1/runs/${runId}/cancel`);
  }

  async worktree(agentId, runId, dir, repoHead) {
    const worktree = join(backendRuntimeRoot(), 'worktrees', runId);
    const diff = await this.api.get(`/api/v1/runs/${runId}/workspace-diff`);
    const repoHeadNow = runSync('git', ['-C', this.opts.repo, 'rev-parse', 'HEAD'], { timeoutMs: 30000 }).stdout.trim();
    const repoStatus = runSync('git', ['-C', this.opts.repo, 'status', '--porcelain'], { timeoutMs: 30000 }).stdout.trim();
    const repoProbe = join(this.opts.repo, 'aria-live-probe.txt');
    this.record('worktree', 'WORKTREE runs write in a run-owned worktree and leave the admitted repository untouched',
      exists(worktree) && !exists(repoProbe) && repoHeadNow === repoHead ? 'PASS' : 'FAIL', {
        'run-owned worktree exists': exists(worktree),
        'the run wrote its file inside the run worktree': exists(join(worktree, 'aria-live-probe.txt')),
        'the admitted repository has no run file': !exists(repoProbe),
        'admitted repository HEAD unchanged': repoHeadNow === repoHead,
      }, { worktree: redact(worktree), repoHeadBefore: repoHead, repoHeadAfter: repoHeadNow, repoStatus: repoStatus.slice(0, 400), diffStatus: diff.status, diff: redact(diff.text).slice(0, 1200) });
  }

  async direct(directAgentId, dir) {
    const target = join(dir, 'aria-live-direct.txt');
    const prompt = 'Create the file aria-live-direct.txt in your current working directory with the exact content direct-write. Then reply DONE.';
    const runId = await this.dispatch(directAgentId, prompt, 'direct');
    const ask = await this.waitForAsk(runId, this.opts.timeoutMs, 'direct ask');
    if (ask.done) await this.decide(ask.ask.id, true, 'live matrix: approve direct write');
    const end = await this.terminal(runId, this.opts.timeoutMs);
    const state = end.state || (await this.runState(runId));
    const bytes = exists(target) ? fileText(target) : null;
    this.record('direct', 'DIRECT runs write in the explicitly selected directory',
      state.status === 'COMPLETED' && bytes === 'direct-write' ? 'PASS' : 'FAIL', {
        'run reached COMPLETED': state.status === 'COMPLETED',
        'DIRECT file bytes on disk': bytes === 'direct-write',
      }, { runId, runStatus: state.status, observedBytes: bytes });
  }

  async noFallbackSandbox(agentId) {
    const prompt = 'Create the file aria-live-sandbox.txt with the exact content sandbox-should-not-run. Then reply DONE.';
    const runId = await this.dispatch(agentId, prompt, 'no-fallback-sandbox');
    const end = await this.terminal(runId, 300000);
    const state = end.state || (await this.runState(runId));
    const message = `${state.errorMessage || ''}`;
    const hostProbe = join(backendRuntimeRoot(), 'worktrees', runId);
    const namedPrerequisite = /sandbox|OpenSandbox|container|image/i.test(message);
    this.record('no-fallback-sandbox', 'a SANDBOX run in a blocked environment fails closed with the missing prerequisite named',
      state.status === 'FAILED' && namedPrerequisite && !exists(hostProbe) ? 'PASS' : 'FAIL', {
        'run reached FAILED (not a success, not a silent host run)': state.status === 'FAILED',
        'error names the sandbox prerequisite': namedPrerequisite,
        'no host worktree was created for the sandbox run': !exists(hostProbe),
      }, { runId, runStatus: state.status, errorMessage: message.slice(0, 400) });
  }

  async noFallbackCredential(agentId) {
    const revocation = await this.api.call('DELETE', '/api/v1/adk/providers/qoder/credential');
    capture(`no-fallback-credential: credential revoked (HTTP ${revocation.status})`);
    const prompt = 'Create the file aria-live-nocred.txt with the exact content nocred. Then reply DONE.';
    const runId = await this.dispatch(agentId, prompt, 'no-fallback-credential');
    const end = await this.terminal(runId, 300000);
    const state = end.state || (await this.runState(runId));
    const message = `${state.errorMessage || ''}`;
    const namesCredential = /credential|not configured/i.test(message);
    this.record('no-fallback-credential', 'a run without its runtime credential fails closed and never falls back to another provider',
      state.status === 'FAILED' && namesCredential ? 'PASS' : 'FAIL', {
        'run reached FAILED': state.status === 'FAILED',
        'error names the missing runtime credential': namesCredential,
      }, { runId, runStatus: state.status, errorMessage: message.slice(0, 400) });
  }
}

// ---------------------------------------------------------------------------
// main
// ---------------------------------------------------------------------------
async function main() {
  const opts = parseArgs(process.argv.slice(2));
  // The backend resolves workspace paths against its own working directory, so every path the
  // runner hands to the API is absolute.
  if (opts.repo) opts.repo = resolve(opts.repo);
  if (opts.workspace) opts.workspace = resolve(opts.workspace);
  if (opts.patFile) opts.patFile = resolve(opts.patFile);
  const evidencePath = opts.evidence ? resolve(opts.evidence) : null;
  const row = {
    core: opts.core, mode: opts.mode, os: `${process.platform} ${process.arch}`,
    node: process.version, requestedModel: opts.model, observedModels: [],
    cliVersion: null, startedAt: new Date().toISOString(), status: 'unknown',
    prerequisites: [], results: [], backendLog: null, usage: null,
  };

  // Credential intake happens first so a secret can be redacted out of every captured line.
  if (opts.core === 'qoder') {
    const secret = readSecretFile(opts.patFile || process.env.ARIA_LIVE_PAT_FILE || process.env.ARIA_PROBE_PAT_FILE);
    if (secret) SECRETS = [secret];
  }

  const pre = preflight(opts);
  row.prerequisites = pre.notes;
  if (pre.unmet.length > 0) {
    row.status = pre.refusal ? 'refused' : (opts.mode === 'SANDBOX' && pre.unmet.some((u) => /OpenSandbox|sandbox image/.test(u)) ? 'blocked' : 'refused');
    row.blockedBy = pre.unmet;
    capture('');
    for (const unmet of pre.unmet) capture(`MISSING PREREQUISITE: ${unmet}`);
    capture(`live-matrix refused before launching anything (core=${opts.core} mode=${opts.mode})`);
    writeEvidence(evidencePath, row, captured);
    process.exit(row.status === 'blocked' ? 3 : 2);
  }
  for (const note of pre.notes) capture(`prerequisite ok: ${note}`);

  opts.operatorToken = process.env.ARIA_LIVE_OPERATOR_TOKEN || `live-matrix-operator-${randomBytes(12).toString('hex')}`;
  opts.credentialKey = process.env.ARIA_RUNTIME_CREDENTIAL_KEY || randomBytes(32).toString('base64');
  const backendLog = evidencePath ? `${evidencePath}.backend.log` : join(REPO_ROOT, 'live-matrix-backend.log');
  row.backendLog = backendLog;

  let backend = null;
  const api = new Api(opts.baseUrl, opts.operatorToken);
  try {
    if (opts.backend) {
      section('backend');
      backend = startBackend(opts, backendLog);
      const health = await waitForHealth(opts.baseUrl, 600000);
      capture(`health: ${health.detail}`);
      row.backendPins = opts.backendPins ? { ...opts.backendPins } : null;
      if (!health.ok) throw new Error(`backend never became healthy: ${health.detail}`);
    }

    section(`live matrix ${opts.core}/${opts.mode}`);
    capture(`core=${opts.core} mode=${opts.mode} os=${row.os} cli=${opts.cli ? redact(opts.cli) : 'n/a'} cliVersion=${opts.cliVersion || 'n/a'} bridge=${opts.bridgeEntry ? redact(opts.bridgeEntry) : 'n/a'}`);
    capture(`requested model=${opts.model} (qoder pin ${QODER_PINNED_MODEL}); usage evidence is recorded, never asserted as zero cost`);

    if (opts.core === 'qoder') {
      const secret = readSecretFile(opts.patFile);
      if (!secret) throw new Error('qoder PAT disappeared between preflight and use');
      const stored = await api.call('PUT', '/api/v1/adk/providers/qoder/credential', { secret });
      capture(`qoder runtime credential stored (HTTP ${stored.status}); the secret is never printed`);
      if (stored.status >= 300) throw new Error(`credential store refused (HTTP ${stored.status})`);
    }

    const matrix = new Matrix(opts, api);
    const stamp = Date.now().toString(36);
    const worktreeAgent = await matrix.createAgent({
      core: opts.core, mode: opts.mode, workspaceMode: 'WORKTREE', workspacePath: opts.repo,
      name: `live-matrix-${opts.core}-${opts.mode.toLowerCase()}-worktree-${stamp}`,
    });
    const directAgent = await matrix.createAgent({
      core: opts.core, mode: opts.mode, workspaceMode: 'DIRECT', workspacePath: opts.workspace,
      name: `live-matrix-${opts.core}-${opts.mode.toLowerCase()}-direct-${stamp}`,
    });

    const wanted = opts.scenarios;
    const enabled = (id) => !wanted || wanted.includes(id);


    if (enabled('coding-task') || enabled('worktree')) {
      const rec = await matrix.codingTask(worktreeAgent.id, opts.core, opts.mode);
      row.observedModels = rec.usage.recordedModelAcknowledged;
      row.usage = rec.usage;
      const probeFile = join(rec.dir, 'aria-live-probe.txt');
      capture(`coding-task file: ${redact(probeFile)} bytes=${JSON.stringify(rec.bytes)} sha256=${exists(probeFile) ? sha256File(probeFile) : 'n/a'}`);
      await matrix.worktree(worktreeAgent.id, rec.runId, rec.dir, opts.repoHead);
    }
    if (enabled('approve-once')) await matrix.approveOnce(worktreeAgent.id);
    if (enabled('deny')) await matrix.deny(worktreeAgent.id);
    if (enabled('expiry')) await matrix.expiry(worktreeAgent.id);
    if (enabled('cancel')) await matrix.cancel(worktreeAgent.id, opts.core);
    if (enabled('pause-resume')) await matrix.pauseResume(worktreeAgent.id);
    if (enabled('direct')) await matrix.direct(directAgent.id, opts.workspace);
    if (enabled('no-fallback-sandbox')) {
      const sandboxAgent = await matrix.createAgent({
        core: opts.core, mode: opts.mode === 'SANDBOX' ? 'HOST' : 'SANDBOX',
        workspaceMode: 'WORKTREE', workspacePath: opts.repo,
        name: `live-matrix-${opts.core}-fallback-probe-${Date.now().toString(36)}`,
      });
      await matrix.noFallbackSandbox(sandboxAgent.id);
    }
    if (enabled('no-fallback-credential') && opts.core === 'qoder') {
      await matrix.noFallbackCredential(worktreeAgent.id);
    }

    const statuses = matrix.results.map((r) => r.status);
    row.results = matrix.results;
    row.status = statuses.includes('FAIL') ? 'failed'
      : statuses.includes('NOT VERIFIED') ? 'partial'
        : 'verified';
    capture('');
    capture(`live matrix ${opts.core}/${opts.mode}: ${row.status}`);
  } catch (error) {
    row.status = 'failed';
    row.error = redact(String(error && error.stack ? error.stack : error));
    capture(`live-matrix error: ${row.error}`);
  } finally {
    row.finishedAt = new Date().toISOString();
    if (backend && !opts.keepBackend) stopBackend(backend, opts);
    else if (backend) capture(`--keep-backend: backend left running with pid ${backend.pid}`);
    writeEvidence(evidencePath, row, captured);
  }
  process.exit(row.status === 'verified' ? 0 : 1);
}

function writeEvidence(evidencePath, row, lines) {
  if (!evidencePath) return;
  mkdirSync(dirname(evidencePath), { recursive: true });
  writeFileSync(evidencePath, `${lines.join('\n')}\n`, 'utf8');
  writeFileSync(`${evidencePath}.json`, `${JSON.stringify(row, null, 2)}\n`, 'utf8');
}

main().catch((error) => {
  process.stderr.write(`live-matrix: fatal: ${redact(String(error && error.stack ? error.stack : error))}\n`);
  process.exit(70);
});
