// Real-container lane: the run-owned Sandbox boundary against actual OpenSandbox.
//
// This is the boundary test the recording SDK double cannot replace. It starts a
// real OpenSandbox server's sandbox from the mock-peer image
// (agent-control-tower/runtime-sandbox/test.Dockerfile), drives the PRODUCTION
// Java backend/harness against it, and asserts independently observed container
// state:
//
//   * endpoint authentication (the peer refuses an unauthenticated control read);
//   * upload (the uploaded snapshot/manifest drive the launch);
//   * pause (verified SIGSTOP of the writer tree, and no later writes);
//   * renewal (the automatic TTL renewal really calls the server and stops for teardown);
//   * background writer termination (a descendant writer that outlives the prompt is gone);
//   * stable export THEN kill (export twice gives identical bytes; only then destroy);
//   * unrelated sandbox preservation (destroying one run never touches another).
//
// The lane FAILS CLOSED. It never skips and never substitutes a process-only
// fake: when the container runtime, the OpenSandbox server, the test image or
// the JDK/Maven toolchain is missing, every test fails with the exact missing
// prerequisite and the command that satisfies it. Run it with:
//
//   docker build -t aria-conductor/runtime-sandbox-test:1.0 \
//     -f agent-control-tower/runtime-sandbox/test.Dockerfile .
//   OPEN_SANDBOX_URL=http://127.0.0.1:8090 \
//     node --test e2e/agent-core/sandbox-lifecycle.test.mjs
//
// Requirements are read from the environment so the same file serves a local
// operator and the CI lane (Task 19): OPEN_SANDBOX_URL, SANDBOX_RUNTIME
// (docker|podman), SANDBOX_TEST_IMAGE, ARIA_OPEN_SANDBOX_API_KEY (optional).
import assert from 'node:assert/strict';
import { execFileSync, spawn, spawnSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { existsSync, mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { delimiter, dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { after, test } from 'node:test';

const REPO = resolve(dirname(fileURLToPath(import.meta.url)), '..', '..');
const HARNESS_CLASS = 'io.aria.conductor.execution.runtime.sandbox.SandboxLifecycleHarness';
const PEER_TOKEN = 'aria-sidecar-peer-token-0123456789';
const RUN_A = '00000000-0000-0000-0000-0000000000e1';
const RUN_B = '00000000-0000-0000-0000-0000000000e2';
const WRITER_LOG = '/workspace/ticks.log';

/** Everything this lane requires, as an explicit list of named prerequisites. */
export function missingPrerequisites(env = process.env) {
  const missing = [];
  if (!env.OPEN_SANDBOX_URL && !env.ARIA_OPEN_SANDBOX_URL) {
    missing.push('OPEN_SANDBOX_URL (the OpenSandbox server, e.g. http://127.0.0.1:8090)');
  }
  if (!env.SANDBOX_RUNTIME) {
    missing.push('SANDBOX_RUNTIME (the container runtime CLI that builds the test image: docker or podman)');
  }
  if (!env.ARIA_E2E_SKIP_TOOLCHAIN && !env.JAVA_HOME && !hasCommand('java')) {
    missing.push('java on PATH (the harness drives the production Java backend)');
  }
  if (!env.ARIA_E2E_SKIP_TOOLCHAIN && !hasCommand(mavenCommand())) {
    missing.push('mvn on PATH (the harness classpath is resolved from the act-execution module)');
  }
  return missing;
}

/** Maven is `mvn.cmd` on Windows; a fixed name, never caller input. */
function mavenCommand() {
  return process.platform === 'win32' ? 'mvn.cmd' : 'mvn';
}

function hasCommand(name) {
  const probe = spawnSync(name, ['--version'], { stdio: 'ignore', shell: false });
  if (probe.error === undefined) return probe.status === 0;
  // Windows batch entry points cannot be executed without a shell; the name is
  // fixed here, so resolving it through the shell is safe.
  const fallback = spawnSync(name, ['--version'], { stdio: 'ignore', shell: true });
  return fallback.status === 0;
}

/** The exact command that satisfies this lane, printed with every refusal. */
const LANE_COMMAND = [
  `  ${process.env.SANDBOX_RUNTIME ?? '<docker|podman>'} build -t ` +
  `${process.env.SANDBOX_TEST_IMAGE ?? 'aria-conductor/runtime-sandbox-test:1.0'} ` +
  '-f agent-control-tower/runtime-sandbox/test.Dockerfile .',
  '  OPEN_SANDBOX_URL=http://127.0.0.1:8090 SANDBOX_RUNTIME=<docker|podman> \\',
  '    node --test e2e/agent-core/sandbox-lifecycle.test.mjs',
].join('\n');

function requireLane() {
  const missing = missingPrerequisites();
  if (missing.length > 0) {
    throw new Error(
      `The real-container sandbox lane cannot run here; missing prerequisite(s):\n`
      + missing.map((item) => `  - ${item}`).join('\n')
      + `\nRun it on a host with a container runtime and an OpenSandbox server:\n${LANE_COMMAND}`);
  }
}

// ------------------------------------------------------------------ lane fixture

function runtimeCommand(runtime) {
  return runtime === 'podman' ? 'podman' : 'docker';
}

/** Builds the mock-peer image on first use (idempotent, and never silently skipped). */
function ensureTestImage(state) {
  const runtime = runtimeCommand(process.env.SANDBOX_RUNTIME);
  const image = process.env.SANDBOX_TEST_IMAGE ?? 'aria-conductor/runtime-sandbox-test:1.0';
  try {
    execFileSync(runtime, ['image', 'inspect', image], { stdio: 'ignore' });
    state.imageBuilt = 'present';
  } catch {
    execFileSync(runtime, ['build', '-t', image,
      '-f', join(REPO, 'agent-control-tower/runtime-sandbox/test.Dockerfile'), REPO], { stdio: 'inherit' });
    state.imageBuilt = 'built';
  }
  return image;
}

/**
 * The one Maven entry point. `mvn.cmd` cannot be executed with `shell: false`
 * (EINVAL on Windows), and the name is a fixed literal, so routing every
 * invocation through one shell-on-Windows helper is safe — the same reasoning
 * `hasCommand` already applies to its fallback probe.
 */
function runMaven(args, { timeoutMs = 900000 } = {}) {
  // Bounded: a hung Maven must fail this lane with the command, not hold the job
  // until its timeout (which is what the first CI run of this lane did).
  return execFileSync(mavenCommand(), args, {
    stdio: 'inherit', shell: process.platform === 'win32', timeout: timeoutMs,
  });
}

/**
 * Resolves the module classpath for the harness (target/classes + test-classes +
 * deps). The prerequisite step installs with `-am` instead of only
 * test-compiling: `dependency:build-classpath` resolves the sibling modules
 * (act-common, act-agent, act-test-support) as artifacts, so on a fresh runner
 * they must exist in the local repository first — building them in this reactor
 * is not enough, and without the install the lane dies on a resolution error.
 */
function ensureHarnessClasspath(state) {
  if (state.classpath !== undefined) return state.classpath;
  const module = join(REPO, 'agent-control-tower/act-execution/target');
  let dependencies;
  const provided = process.env.SANDBOX_LANE_CLASSPATH_FILE;
  if (provided !== undefined && provided.trim() !== '' && existsSync(provided)) {
    console.log(`[sandbox-lane] harness classpath provided by ${provided}`);
    dependencies = readFileSync(provided, 'utf8').trim();
  } else {
    const rootPom = join(REPO, 'agent-control-tower/pom.xml');
    console.log('[sandbox-lane] building the harness classpath with Maven (minutes)...');
    runMaven(['-f', rootPom, '-pl', 'act-execution', '-am', 'install', '-DskipTests', '-q']);
    const output = join(state.root, 'classpath.txt');
    runMaven(['-f', rootPom, '-pl', 'act-execution', 'dependency:build-classpath',
      `-Dmdep.outputFile=${output}`, '-q']);
    dependencies = readFileSync(output, 'utf8').trim();
  }
  state.classpath = [join(module, 'classes'), join(module, 'test-classes'), dependencies].join(delimiter);
  return state.classpath;
}

/**
 * Per-command ceiling. The harness's own control deadlines are 60 s, so this
 * stays above them; it exists so a hung JVM fails the lane instead of leaving
 * every waiting caller pending forever.
 */
const HARNESS_COMMAND_TIMEOUT_MS = 120000;

/** JSON-lines driver over the production backend harness. */
class Harness {
  constructor(child) {
    this.child = child;
    this.pending = [];
    this.buffer = '';
    this.failure = null;
    child.stdout.setEncoding('utf8');
    child.stdout.on('data', (chunk) => {
      this.buffer += chunk;
      for (;;) {
        const at = this.buffer.indexOf('\n');
        if (at < 0) break;
        const line = this.buffer.slice(0, at).trim();
        this.buffer = this.buffer.slice(at + 1);
        if (line.length > 0) this.accept(line);
      }
    });
    child.stderr.setEncoding('utf8');
    child.stderr.on('data', (chunk) => process.stderr.write(`[harness] ${chunk}`));
    // A dead JVM must fail the waiting commands, never leave them pending: these
    // listeners reject every waiter instead of letting the lane hang on CI.
    child.on('error', (error) => this.abortAll(`The harness process could not be started: ${error.message}`));
    child.on('exit', (code, signal) => this.abortAll(
      `The harness process exited (code ${String(code)}, signal ${String(signal)})`));
    // A write racing the death would otherwise surface as an unhandled stream
    // error; the exit listener above already reports the death.
    child.stdin.on('error', () => {});
  }

  /**
   * One line of harness stdout. Every command is answered by exactly one single-line
   * JSON object, and the harness keeps its stdout exclusive (its loggers and the
   * sandbox SDK write to stderr). A line that is not such an answer is foreign output:
   * it is forwarded verbatim and never consumed as an answer — consuming one is how
   * this lane first failed, with a log line parsed as the answer to `prepare`. An
   * ANSWER no command is waiting for is a protocol desynchronisation, and it fails
   * the lane by name instead of throwing from a stream handler.
   */
  accept(line) {
    let result = null;
    try {
      result = JSON.parse(line);
    } catch {
      result = null;
    }
    if (result === null || typeof result !== 'object' || typeof result.ok !== 'boolean') {
      process.stderr.write(`[harness stdout] ${line}\n`);
      return;
    }
    const waiter = this.pending.shift();
    if (waiter === undefined) {
      this.abortAll(`The harness answered with no command waiting for it: ${line}`);
      return;
    }
    this.settle(waiter, result);
  }

  send(command, { timeoutMs = HARNESS_COMMAND_TIMEOUT_MS } = {}) {
    return this.exchange(command, false, timeoutMs);
  }

  /** A refused command must be reported as a refusal, never as a silent success. */
  sendExpectingRefusal(command, { timeoutMs = HARNESS_COMMAND_TIMEOUT_MS } = {}) {
    return this.exchange(command, true, timeoutMs);
  }

  exchange(command, refusalAsResult, timeoutMs) {
    return new Promise((resolvePromise, reject) => {
      if (this.failure !== null) {
        reject(new Error(`${this.failure}; ${JSON.stringify(command.command)} was not sent`));
        return;
      }
      const timer = setTimeout(() => this.abortAll(
        `The harness did not answer ${JSON.stringify(command.command)} within ${timeoutMs} ms`), timeoutMs);
      this.pending.push({ command, refusalAsResult, resolve: resolvePromise, reject, timer });
      this.child.stdin.write(`${JSON.stringify(command)}\n`);
    });
  }

  settle(waiter, result) {
    clearTimeout(waiter.timer);
    if (!waiter.refusalAsResult && result.ok !== true) {
      waiter.reject(new Error(`Harness refused ${JSON.stringify(waiter.command.command)}: ${result.error}`));
      return;
    }
    waiter.resolve(result);
  }

  /**
   * Fails this driver closed: every waiting caller is rejected and later
   * commands are refused. Waiters keep their place in the FIFO (never cleared),
   * so any last output the dying process already wrote is still matched to the
   * command it answers instead of desynchronising the stream.
   */
  abortAll(reason) {
    if (this.failure === null) this.failure = reason;
    for (const waiter of this.pending) {
      clearTimeout(waiter.timer);
      waiter.reject(new Error(reason));
    }
  }
}

async function startLane() {
  requireLane();
  const url = process.env.OPEN_SANDBOX_URL ?? process.env.ARIA_OPEN_SANDBOX_URL;
  const response = await fetch(`${url.replace(/\/$/, '')}/health`).catch(() => null);
  if (response === null || !response.ok) {
    throw new Error(`The OpenSandbox server at ${url} does not answer /health; start it (docker compose up -d `
      + 'opensandbox-server) or point OPEN_SANDBOX_URL at the running server.');
  }
  const state = { root: mkdtempSync(join(tmpdir(), 'aria-sandbox-lifecycle-')) };
  const image = ensureTestImage(state);
  const classpath = ensureHarnessClasspath(state);
  const child = spawn('java', ['--enable-preview', '-cp', classpath, HARNESS_CLASS], {
    cwd: REPO,
    env: { ...process.env, ARIA_OPEN_SANDBOX_URL: url },
    stdio: ['pipe', 'pipe', 'pipe'],
  });
  state.harness = new Harness(child);
  state.runs = {
    A: { runId: RUN_A, runtimeRoot: join(state.root, 'A/runtime'), snapshot: join(state.root, 'A/snapshot') },
    B: { runId: RUN_B, runtimeRoot: join(state.root, 'B/runtime'), snapshot: join(state.root, 'B/snapshot') },
  };
  state.image = image;
  state.url = url;
  return state;
}

/**
 * The lane's own deadline. A healthy run takes a few minutes; this can only fire while
 * something is genuinely stuck — which is how the first CI run of this lane ended, with
 * the step's `timeout` killing a silent process 18 minutes in. It names the failure and
 * exits, and it is unref'd, so a lane that finished cleanly never trips it.
 */
const LANE_DEADLINE_MS = 15 * 60 * 1000;
const laneDeadline = setTimeout(() => {
  process.stderr.write(
    `[sandbox-lane] the lane exceeded its ${LANE_DEADLINE_MS / 60000}-minute deadline; failing\n`);
  process.exit(1);
}, LANE_DEADLINE_MS);
laneDeadline.unref();

let lanePromise;
function lane() {
  if (lanePromise === undefined) lanePromise = startLane();
  return lanePromise;
}

function prepareRun(state, run, { coreId, port, notes }) {
  mkdirSync(run.snapshot, { recursive: true });
  writeFileSync(join(run.snapshot, 'notes.md'), notes);
  writeFileSync(join(run.snapshot, 'opencode.json'), '{"$schema":"https://opencode.ai/config.json"}\n');
  return state.harness.send({
    command: 'prepare', runId: run.runId, coreId, image: state.image, port, runtimeRoot: run.runtimeRoot,
    snapshot: run.snapshot,
  });
}

function peerArgv(peer, scenario, port) {
  return ['node', `/opt/aria/e2e/peers/${peer}.mjs`, '--scenario', scenario,
    '--workspace', '/workspace', '--port', String(port)];
}

/** The sandbox endpoint is a proxy prefix (…/proxy/<port>), so paths are appended, never re-rooted. */
function endpointUrl(endpoint, path) {
  return `${String(endpoint).replace(/\/$/, '')}${path}`;
}

async function peerFetch(endpoint, path, { token, method = 'GET', body } = {}) {
  const headers = { ...(token === undefined ? {} : { 'x-peer-control-token': token }) };
  if (body !== undefined) headers['content-type'] = 'application/json';
  return fetch(endpointUrl(endpoint, path), { method, headers, body });
}

/**
 * One line of in-sandbox command output. The exec read-back is line-oriented (the SDK
 * reads a command's output as a line sequence), so a single-line result is the only
 * shape that survives the channel verbatim — multi-line output arrives with its line
 * breaks glued away, and a trailing newline never arrives at all. Claims about exact
 * bytes therefore go through a digest, never through transported text.
 */
async function execLine(state, runId, shell) {
  const result = await state.harness.send({ command: 'exec', runId, shell });
  return result.output.trim();
}

async function writerLog(state, runId) {
  return execLine(state, runId, `cat ${WRITER_LOG}`);
}

function delay(ms) {
  return new Promise((resolvePromise) => setTimeout(resolvePromise, ms));
}

async function waitFor(predicate, { timeoutMs = 20000, description = 'condition' } = {}) {
  const deadline = Date.now() + timeoutMs;
  for (;;) {
    const value = await predicate();
    if (value) return value;
    if (Date.now() > deadline) throw new Error(`Timed out waiting for ${description}`);
    await new Promise((resolvePromise) => setTimeout(resolvePromise, 200));
  }
}

/** Drives the peer's genuine allow-once decision flow so its writer really starts. */
async function startPeerWriter(endpoint) {
  const session = await peerFetch(endpoint, '/session', { method: 'POST' }).then((r) => r.json());
  // The gated message response is HELD until its fixture decision resolves — that hold
  // IS the gate — so awaiting it before the decision deadlocked this lane for undici's
  // full 300 s headers timeout. Fire it, drive the decision, then await the release.
  const message = peerFetch(endpoint, `/session/${session.id}/message`, {
    method: 'POST',
    body: JSON.stringify({ model: 'efficient' }),
  });
  message.catch(() => {}); // reported at the await below, never as an unhandled rejection
  const pending = await waitFor(async () => {
    const payload = await peerFetch(endpoint, '/__peer/pending', { token: PEER_TOKEN }).then((r) => r.json());
    return payload.pending.length > 0 ? payload.pending[0] : null;
  }, { description: 'the fixture write permission request' });
  const optionId = pending.options.find((option) => option.kind === 'allow_once')?.optionId;
  assert.ok(optionId, 'the peer must offer an allow-once option');
  const decision = await peerFetch(endpoint, '/__peer/decision', {
    method: 'POST',
    token: PEER_TOKEN,
    body: JSON.stringify({ optionId }),
  });
  assert.equal(decision.status, 200);
  // The grant is what releases the held message (and, with it, the writer child).
  assert.equal((await message).status, 200, 'the granted decision must release the held message');
  return waitFor(async () => {
    const stateReply = await peerFetch(endpoint, '/__peer/state', { token: PEER_TOKEN }).then((r) => r.json());
    return stateReply.writerPid ? stateReply : null;
  }, { description: 'the background writer pid' });
}

test('the lane names every missing prerequisite instead of skipping', () => {
  const missing = missingPrerequisites({});
  assert.ok(missing.some((item) => item.startsWith('OPEN_SANDBOX_URL')), 'the server is a prerequisite');
  assert.ok(missing.some((item) => item.startsWith('SANDBOX_RUNTIME')), 'the runtime is a prerequisite');
  assert.deepEqual(missingPrerequisites({
    OPEN_SANDBOX_URL: 'http://127.0.0.1:8090',
    SANDBOX_RUNTIME: 'docker',
    ARIA_E2E_SKIP_TOOLCHAIN: '1',
  }), [], 'a complete environment has no missing prerequisite');
});

test('run A: the peer starts from the uploaded manifest and its endpoint requires the per-run token', async () => {
  const state = await lane();
  const run = state.runs.A;
  await prepareRun(state, run, { coreId: 'opencode', port: 4096, notes: 'sandbox snapshot bytes\n' });
  // The renewal plan must be armed BEFORE the run launches: the production
  // lifecycle reads it when a run starts (`SandboxLifecycle.launch` ->
  // `startRenewal` reads the plan at call time), so a plan sent after the launch
  // never reaches this run. The later renewal test depends on this ordering.
  await state.harness.send({ command: 'renewalPlan', intervalMs: 1000, extensionMs: 60000 });
  const launched = await state.harness.send({
    command: 'launch', runId: run.runId, argv: peerArgv('mock-opencode', 'background-writer', 4096),
    env: { ARIA_PEER_CONTROL_TOKEN: PEER_TOKEN }, workingDirectory: '/workspace',
  });
  run.endpoint = launched.endpoint;

  const health = await waitFor(async () => {
    // The endpoint is the execd proxy prefix (…/proxy/<port>): append, never re-root.
    const reply = await fetch(endpointUrl(run.endpoint, '/global/health')).catch(() => null);
    return reply !== null && reply.ok ? reply.json() : null;
  }, { description: 'the launched core endpoint' });
  assert.equal(health.healthy, true);

  const unauthorized = await peerFetch(run.endpoint, '/__peer/state');
  assert.equal(unauthorized.status, 401, 'the endpoint must refuse an unauthenticated control read');
  const authorized = await peerFetch(run.endpoint, '/__peer/state', { token: PEER_TOKEN });
  assert.equal(authorized.status, 200);
  assert.equal((await authorized.json()).scenario, 'background-writer');

  // The snapshot's bytes must be in the sandbox exactly as written, so the claim is
  // measured inside the container (digest and byte count) instead of through the
  // line-oriented exec read-back: both numbers are exact, and a mismatch names which
  // one moved.
  const localSnapshot = readFileSync(join(run.snapshot, 'notes.md'));
  const localSha256 = createHash('sha256').update(localSnapshot).digest('hex');
  const digest = await execLine(state, run.runId, 'sha256sum /workspace/notes.md | cut -d" " -f1');
  const bytes = await execLine(state, run.runId, 'wc -c < /workspace/notes.md');
  assert.equal(`${digest} ${bytes}`, `${localSha256} ${localSnapshot.length}`,
    'the uploaded snapshot must carry the exact bytes the lane wrote');
});

test('run A: the background writer writes and the pause is verified', async () => {
  const state = await lane();
  const run = state.runs.A;
  await startPeerWriter(run.endpoint);
  const first = await writerLog(state, run.runId);
  const growing = await waitFor(async () => {
    const current = await writerLog(state, run.runId);
    return current.length > first.length ? current : null;
  }, { description: 'the background writer to append to its log' });
  assert.ok(growing.length > first.length);

  const paused = await state.harness.send({ command: 'pause', runId: run.runId });
  assert.deepEqual([paused.state, paused.verified], ['PAUSED', true],
    'a pause is only acknowledged when the whole writer tree is suspended');
  const frozenAt = await writerLog(state, run.runId);
  await new Promise((resolvePromise) => setTimeout(resolvePromise, 600));
  assert.equal(await writerLog(state, run.runId), frozenAt, 'no writer may advance while paused');

  const resumed = await state.harness.send({ command: 'resume', runId: run.runId });
  assert.deepEqual([resumed.state, resumed.verified], ['RUNNING', true]);
  await waitFor(async () => (await writerLog(state, run.runId)).length > frozenAt.length,
    { description: 'the writer to resume after the verified resume' });
});

test('run A: renewal really runs against the server', async () => {
  const state = await lane();
  const run = state.runs.A;
  // The 1 s plan is already armed: it was sent before run A launched, which is
  // the ordering the lifecycle requires (the plan is read when a run starts).
  const before = await state.harness.send({ command: 'renewals' });
  await waitFor(async () => {
    const now = await state.harness.send({ command: 'renewals' });
    return now.renewals > before.renewals;
  }, { timeoutMs: 30000, description: 'an automatic sandbox renewal' });

  const stopped = await state.harness.send({ command: 'stopWriters', runId: run.runId });
  assert.equal(stopped.allWritersStopped, true, 'the writer tree must be verifiably gone');
  const frozen = await writerLog(state, run.runId);
  await new Promise((resolvePromise) => setTimeout(resolvePromise, 600));
  assert.equal(await writerLog(state, run.runId), frozen, 'the writer log must be stable once the writers stopped');
  // A stopped writer tree is not a destroyed sandbox: run A must stay alive and
  // exportable for the next test, so the single destroy -- and with it the
  // "renewal stops for teardown" assertion, which needs a dead sandbox -- lives
  // in the export test below instead of here.
});

test('run A: the export is stable and the sandbox is destroyed only after it, stopping renewal', async () => {
  const state = await lane();
  const run = state.runs.A;
  // Writers were stopped by the previous step, and that verified stop SIGTERMs
  // the recorded child — which in this lane IS the core peer — so the core proxy
  // (…/proxy/<port>) is dead by design here and can never answer again. The
  // sandbox filesystem, however, must survive the writer stop, so probe the
  // sandbox through the execd command channel instead: read a file no writer
  // ever touched, and let the export facility below prove the other half.
  const alive = await state.harness.send({
    command: 'exec', runId: run.runId, shell: 'cat /workspace/opencode.json',
  });
  assert.match(alive.output, /opencode\.ai\/config\.json/,
    'stopping writers must not destroy the sandbox filesystem');

  const firstDestination = join(state.root, 'A/export-1');
  const secondDestination = join(state.root, 'A/export-2');
  const first = await state.harness.send({
    command: 'export', runId: run.runId, destination: firstDestination, proof: 'true',
  });
  const second = await state.harness.send({
    command: 'export', runId: run.runId, destination: secondDestination, proof: 'true',
  });
  assert.equal(first.complete, true);
  assert.equal(second.complete, true);
  assert.equal(second.manifestSha256, first.manifestSha256, 'two exports of a stopped run must be identical');
  const exported = readFileSync(join(firstDestination, 'ticks.log'));
  const inContainer = await execLine(state, run.runId, `sha256sum ${WRITER_LOG} | cut -d" " -f1`);
  assert.equal(inContainer, createHash('sha256').update(exported).digest('hex'),
    'the exported bytes must be the container bytes, not a report');

  const refused = await state.harness.sendExpectingRefusal({
    command: 'export', runId: run.runId, destination: join(state.root, 'A/export-3'), proof: 'false',
  });
  assert.equal(refused.ok, false);
  assert.match(refused.error, /Writers are not stopped/);

  // This is run A's one and only destroy, and it must come after the export:
  // destroying earlier kills the sandbox AND drops the run record the export
  // reads.
  await state.harness.send({ command: 'destroy', runId: run.runId });
  // The dead-export refusal is what witnesses the destroy. The core-proxy probe
  // that used to sit here is gone on purpose: the core already died during the
  // writer stop, so a proxy check would hold before the destroy and prove
  // nothing — and a post-destroy execd probe cannot replace it either, because
  // the harness resolves a run's sandbox id, which destroy just dropped.
  const deadExport = await state.harness.sendExpectingRefusal({
    command: 'export', runId: run.runId, destination: join(state.root, 'A/export-4'), proof: 'true',
  });
  assert.equal(deadExport.ok, false, 'a destroyed sandbox can no longer be exported');
  assert.match(deadExport.error, /is not owned by this sandbox lifecycle/,
    'the refusal is the dropped run record, not a stop-proof gate');
  // Teardown half of the renewal property, and it needs a dead sandbox. destroy
  // is what cancels the 1 s renewal, and a renewal already in flight during the
  // destroy is not a teardown failure — so the baseline is sampled only AFTER
  // destroy returns (sampling before it made this a ~1% flake via exactly such a
  // renewal), then compared against a sample one interval later: a cancelled
  // scheduler must not renew a dead sandbox.
  const renewalCount = (await state.harness.send({ command: 'renewals' })).renewals;
  await new Promise((resolvePromise) => setTimeout(resolvePromise, 2500));
  assert.equal((await state.harness.send({ command: 'renewals' })).renewals, renewalCount,
    'renewal must stop for teardown, not renew a dead sandbox');
});

test('run B: destroying run A never touches another run-owned sandbox', async () => {
  const state = await lane();
  const runB = state.runs.B;
  // Run B uses a second mock-opencode: the preservation assertion needs a peer
  // that genuinely serves the asserted surface (/global/health and the
  // token-gated /__peer state). mock-qoder is ACP-over-stdio only -- it opens no
  // HTTP listener, and launch.mjs spawns every peer with stdin at EOF, so a
  // stdio-only peer exits immediately under the launch manifest.
  await prepareRun(state, runB, { coreId: 'opencode', port: 4096, notes: 'run B snapshot\n' });
  const launched = await state.harness.send({
    command: 'launch', runId: runB.runId, argv: peerArgv('mock-opencode', 'complete', 4096),
    env: { ARIA_PEER_CONTROL_TOKEN: PEER_TOKEN }, workingDirectory: '/workspace',
  });

  // Run A was destroyed in the previous step; run B must be untouched by it.
  // Post-hoc, not contemporaneous: run A is already gone here, so this lane never
  // observes B alive *while* A is destroyed -- that isolation property is covered
  // by SandboxExecutionBackendTest#destroyingOneRunNeverTouchesAnotherRunsSandbox.
  await waitFor(async () => {
    const reply = await fetch(endpointUrl(launched.endpoint, '/global/health')).catch(() => null);
    return reply !== null && reply.ok;
  }, { description: 'run B to stay alive after run A was destroyed' });
  const unauthorized = await peerFetch(launched.endpoint, '/__peer/state');
  assert.equal(unauthorized.status, 401, 'run B has its own authenticated endpoint');
  const authorized = await peerFetch(launched.endpoint, '/__peer/state', { token: PEER_TOKEN });
  assert.equal(authorized.status, 200, 'run B is a live mock-opencode of its own');
  assert.equal((await authorized.json()).scenario, 'complete');

  const exported = await state.harness.sendExpectingRefusal({
    command: 'export', runId: runB.runId, destination: join(state.root, 'B/export'), proof: 'false',
  });
  assert.equal(exported.ok, false, 'even the untouched run refuses an export without a stop proof');

  await state.harness.send({ command: 'destroy', runId: runB.runId });
});

after(async () => {
  if (lanePromise === undefined) return;
  const state = await lanePromise.catch(() => null);
  if (state === null) return;
  const child = state.harness.child;
  const exited = child.exitCode !== null || child.signalCode !== null
    ? Promise.resolve()
    : new Promise((resolvePromise) => child.once('exit', resolvePromise));
  const exitedWithin = (ms) => Promise.race([exited.then(() => true), delay(ms).then(() => false)]);
  // Bounded teardown, whatever the lane's outcome: an orderly quit, then stdin EOF,
  // then SIGTERM and SIGKILL each with a grace period. A wedged JVM must never be
  // what keeps this process — and the CI step — alive.
  await state.harness.send({ command: 'quit' }, { timeoutMs: 10000 }).catch(() => null);
  child.stdin.end();
  if (!(await exitedWithin(5000))) child.kill();
  if (!(await exitedWithin(5000))) child.kill('SIGKILL');
  // The engine store is left intact on purpose: the E2E reports state, it never
  // sweeps someone else's sandboxes. Only the host-side temp tree is removed.
  rmSync(state.root, { recursive: true, force: true });
});
