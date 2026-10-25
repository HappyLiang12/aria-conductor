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
//   * self-exit (a core that exits on its own after its completed turn is still verified
//     stopped, and the completed turn survives as a stable export);
//   * stable export THEN kill (export twice gives identical bytes; only then destroy);
//   * unrelated sandbox preservation (destroying one run never touches another);
//   * refusal (a launch the image itself refuses is reported in the image's own words,
//     with the documented launcher exit codes; the core's readiness wait ends on that
//     refusal instead of its timeout; the plane proves the run writer-free and no
//     container is left behind).
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
const RUN_C = '00000000-0000-0000-0000-0000000000e3';
const RUN_D = '00000000-0000-0000-0000-0000000000e4';
const WRITER_LOG = '/workspace/ticks.log';
/**
 * An entrypoint the test image's allowlist does not name (`test.Dockerfile` pins node,
 * mock-opencode and mock-qoder only). The launcher resolves argv[0] against that
 * allowlist before it spawns anything, so this is the refusal class the lane owns.
 */
const REFUSED_ENTRYPOINT = 'aria-refused-core';

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
  const image = process.env.SANDBOX_TEST_IMAGE ?? 'aria-conductor/runtime-sandbox-test:1.0';
  try {
    execFileSync(state.runtime, ['image', 'inspect', image], { stdio: 'ignore' });
    state.imageBuilt = 'present';
  } catch {
    execFileSync(state.runtime, ['build', '-t', image,
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
  // One runtime value for the whole lane (image build, residue probes): the runtime the
  // harness is told to use. The hosted job runs docker; a local operator runs podman.
  state.runtime = runtimeCommand(process.env.SANDBOX_RUNTIME);
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
    C: { runId: RUN_C, runtimeRoot: join(state.root, 'C/runtime'), snapshot: join(state.root, 'C/snapshot') },
    D: { runId: RUN_D, runtimeRoot: join(state.root, 'D/runtime'), snapshot: join(state.root, 'D/snapshot') },
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

/**
 * The sandbox containers the runtime holds for one sandbox id. A leaked sandbox is a
 * container the runtime still runs, not a record the control plane still keeps, so
 * residue is read from the runtime that created it -- the one resolved once from
 * `SANDBOX_RUNTIME` at lane start (the hosted job sets `docker`; a local operator runs
 * `podman`), never a runtime this probe assumes. The server names its containers
 * `sandbox-<sandbox id>`, measured against a live server. The case that uses this
 * asserts the container is present before the refusal, so a runtime that renames its
 * containers fails there instead of letting an always-empty list prove nothing.
 */
function sandboxContainers(state, sandboxId) {
  const listed = execFileSync(state.runtime, ['ps', '-a', '--filter', `name=^sandbox-${sandboxId}$`,
    '--format', '{{.Names}}'], { encoding: 'utf8', stdio: ['ignore', 'pipe', 'ignore'] });
  return listed.split(/\r?\n/).map((name) => name.trim()).filter((name) => name.length > 0);
}

/**
 * The server's own record of one sandbox, read from the canonical route the SDK uses:
 * `/v1/sandboxes/{id}` (`docs/superpowers/plans/2026-08-16-podman-support.md:98-99`;
 * the SDK's base path appends `/v1`). The route is only worth reading because the case
 * proves it answers 200 for the alive sandbox first. Asked without the `/v1` prefix on
 * a server whose only route lives there, this probe answers a route-not-found 404 for
 * every id, and an always-404 probe proves nothing. The returned `code` separates the
 * server's own sandbox-not-found (`...::SANDBOX_NOT_FOUND`) from a route miss
 * (`{"detail":"Not Found"}`), and `id`/`state` are the alive record's identity fields
 * (`status` is the server's state object, whose `state` names the lifecycle state).
 */
async function sandboxRecord(state, sandboxId) {
  const response = await fetch(`${state.url.replace(/\/$/, '')}/v1/sandboxes/${sandboxId}`);
  const body = await response.json().catch(() => null);
  return {
    status: response.status,
    code: typeof body?.code === 'string' ? body.code : null,
    id: typeof body?.id === 'string' ? body.id : null,
    state: typeof body?.status?.state === 'string' ? body.status.state : null,
  };
}

/**
 * The image's launcher, driven directly inside the run's real container against a
 * manifest that names an entrypoint the image does not allowlist. Two facts the
 * backend's own report cannot witness are measured here, in the image itself:
 *
 *   * the refusal is a structured `supervisor.fault` carrying the image's sentence
 *     (`agent-control-tower/runtime-sandbox/launch.mjs:332`), not silence;
 *   * the exit code is the documented one for that class: `LaunchFault` defaults to 78
 *     (launch.mjs:51) and an unresolvable entrypoint is such a fault (launch.mjs:180,
 *     launch.mjs:189), while a malformed command line is 64 (launch.mjs:68, launch.mjs:70,
 *     launch.mjs:265) -- both are asserted, so the pair cannot drift apart.
 *
 * The launcher writes the fault to stderr, so the probe merges the streams and prints
 * each exit code on the same line: this channel glues a command's lines together.
 */
async function launcherRefusal(state, run) {
  const probeDirectory = `/home/aria/probe/${run.runId}`;
  const manifest = JSON.stringify({
    schemaVersion: 1,
    runId: run.runId,
    port: 4096,
    workingDirectory: '/workspace',
    argv: [REFUSED_ENTRYPOINT],
    env: {},
  });
  const shell = [
    `mkdir -p ${probeDirectory}`,
    `printf '%s' '${manifest}' > ${probeDirectory}/launch-manifest.json`,
    `node /opt/aria/launch.mjs --manifest ${probeDirectory}/launch-manifest.json 2>&1; echo " entrypoint_exit=$?"`,
    'node /opt/aria/launch.mjs 2>&1; echo " usage_exit=$?"',
  ].join('; ');
  const result = await state.harness.send({ command: 'exec', runId: run.runId, shell });
  return result.output;
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

test('run C: a core that exits on its own after its completed turn still stops, verifies and exports', async () => {
  const state = await lane();
  const runC = state.runs.C;
  await prepareRun(state, runC, { coreId: 'opencode', port: 4096, notes: 'self-exited core snapshot\n' });
  const launched = await state.harness.send({
    command: 'launch', runId: runC.runId, argv: peerArgv('mock-opencode', 'self-exit', 4096),
    env: { ARIA_PEER_CONTROL_TOKEN: PEER_TOKEN }, workingDirectory: '/workspace',
  });
  runC.endpoint = launched.endpoint;

  await waitFor(async () => {
    const reply = await fetch(endpointUrl(runC.endpoint, '/global/health')).catch(() => null);
    return reply !== null && reply.ok;
  }, { description: 'the run C core endpoint' });

  // The core serves the completed turn in full and then exits on its own: the
  // stop below therefore races no live writer, it verifies an exited one --
  // exactly the live defect (a completed turn whose stop proof could not be
  // verified, so the run failed and the output was discarded).
  const session = await peerFetch(runC.endpoint, '/session', { method: 'POST' }).then((r) => r.json());
  const message = await peerFetch(runC.endpoint, `/session/${session.id}/message`,
    { method: 'POST', body: '{}' });
  assert.equal(message.status, 200, 'the core must serve the completed turn before it exits');
  const served = await message.json();
  assert.equal(served.parts[0].text, 'fixture-complete', 'the served turn carries the fixture output');
  assert.deepEqual(served.info.tokens,
    { input: 12, output: 7, reasoning: 0, cache: { read: 0, write: 0 } },
    'the served turn carries its usage counters');

  // The recorded child pid must be gone before any stop is attempted, so the
  // stop is verified against a writer that provably exited instead of one it
  // gets to signal.
  const record = JSON.parse(await execLine(state, runC.runId,
    `cat /home/aria/run/${runC.runId}/launch-record.json`));
  assert.ok(Number.isInteger(record.childPid), 'the launch record must carry the child pid');
  await waitFor(async () => (await execLine(state, runC.runId,
    `test -e /proc/${record.childPid} && echo alive || echo gone`)) === 'gone',
  { description: 'the core process to exit on its own before the stop' });

  // A tracked writer that provably exited counts as stopped: the stop proof is
  // verified, and that verified stop is what arms the export gate below.
  const stopped = await state.harness.send({ command: 'stopWriters', runId: runC.runId });
  assert.equal(stopped.allWritersStopped, true,
    'the stop must verify the recorded child of the self-exited core as stopped');

  // The completed turn stays exportable: the sandbox survived the stop, the
  // verified stop unlocks the export, and the exported bytes are the real
  // workspace bytes.
  const destination = join(state.root, 'C/export');
  const exported = await state.harness.send({ command: 'export', runId: runC.runId, destination, proof: 'true' });
  assert.equal(exported.complete, true, 'the completed turn must survive as a stable export');
  const localSnapshot = readFileSync(join(runC.snapshot, 'notes.md'));
  const exportedSnapshot = readFileSync(join(destination, 'notes.md'));
  assert.equal(createHash('sha256').update(exportedSnapshot).digest('hex'),
    createHash('sha256').update(localSnapshot).digest('hex'),
    'the exported workspace bytes must be the run snapshot bytes, not a report');

  await state.harness.send({ command: 'destroy', runId: runC.runId });
});

/**
 * The lane's live defect, on a real container: an image that refuses the launch used to
 * say so only in its own output, which the plane discarded -- the operator waited out the
 * readiness window for a verdict already made in the first second -- and no writer could
 * be proven stopped, so the abort path refused to destroy and the sandbox leaked with its
 * workspace lease.
 *
 * Four witnesses, none of them the backend's own report:
 *
 *   1. the launcher, executed in that container, answers with a structured
 *      `supervisor.fault` naming the entrypoint it does not allowlist;
 *   2. that class exits with the launcher's documented code (78), and the malformed
 *      command line next to it with 64;
 *   3. the core's readiness wait, driven over the same live container through
 *      `OpenCodeCoreAdapter.open(handle, spec, credentials, backend.launchRefusal(runId))`,
 *      ends on the launcher's sentence -- the S-1 fail-fast -- instead of spending its
 *      90 s budget and reporting `did not become ready within`;
 *   4. the plane proves no writer of the run could ever have started, and no container
 *      is left behind for the sandbox the refused launch created.
 *
 * The first two are the image's contract. The third is the production readiness seam the
 * harness's `launch` command never touched (it calls `SandboxExecutionBackend.launch`
 * directly), and it is driven by the harness's one extra command, `readiness`. The fourth
 * is the plane's, and it is stated in the form that survives both timings the transport
 * exhibits: the launcher's fault is prompt in the image, but the SDK's stream can deliver
 * it to the Java side either inside the launch's verdict window (the launch then fails on
 * the image's sentence and cleans its own sandbox up) or after it (the launch returns a
 * handle, and the retained verdict is what a later stop must answer from -- measured
 * locally, the stream delivered it a few seconds after the command completed). Both are
 * the documented behaviour; a refusal the plane cannot prove is the defect, and it is
 * what the case fails on.
 *
 * On 9d629f9c (before the fix) `launch` returned a live handle for this manifest -- the
 * fault was swallowed by the background launcher thread -- and `stopWriters` then asked
 * the in-image script, which has no launch record, so the proof below could never be
 * reached: the sandbox stayed up. On 69135996 the launch path is repaired, but the
 * readiness wait still burned its whole budget without the reason; that half is now
 * asserted by the `readiness` witness above instead of living above this harness.
 */
test('run D: a launch the image refuses is a structured fault with the documented codes and a provable refusal', async () => {
  const state = await lane();
  const run = state.runs.D;
  try {
    const prepared = await prepareRun(state, run, {
      coreId: 'opencode', port: 4096, notes: 'refused launch snapshot\n',
    });
    run.environmentId = prepared.environmentId;

    // The container exists BEFORE the refusal: the residue assertion below is measured
    // against a container this case has seen, so a different runtime naming scheme fails
    // here, loudly, instead of an always-empty list passing for the wrong reason.
    assert.deepEqual(sandboxContainers(state, run.environmentId), [`sandbox-${run.environmentId}`],
      'the prepared run must own its own sandbox container before the launch is attempted');

    // The route's positive control, while the sandbox is alive: `/v1/sandboxes/{id}` must
    // answer 200 and name this sandbox. This is what makes the post-destroy 404 below
    // mean something -- a probe pointing at the wrong path fails HERE, inside the case,
    // instead of letting a route-not-found 404 satisfy the residue assertion for any id.
    const alive = await waitFor(async () => {
      const record = await sandboxRecord(state, run.environmentId);
      return record.status === 200 ? record : null;
    }, { timeoutMs: 30000, description: 'the alive sandbox record on the server\'s /v1 route' });
    assert.equal(alive.id, run.environmentId,
      'the alive record must be the run\'s own sandbox, not any 200 on the route');
    assert.ok(typeof alive.state === 'string' && alive.state.length > 0,
      'the alive record must carry the sandbox\'s state');

    // (1) + (2): the launcher's refusal and exit codes, read from the image in the container.
    const probe = await launcherRefusal(state, run);
    assert.match(probe, /"event":"supervisor\.fault"/,
      'a refusal must be a structured supervisor.fault, not silence and not a ready timeout');
    assert.match(probe, /"name":"LaunchFault"/, 'the fault must name its class');
    assert.match(probe, new RegExp(`not allowlisted by this image: ${REFUSED_ENTRYPOINT}`),
      'the fault must carry the image\'s own reason');
    assert.match(probe, /entrypoint_exit=78\b/,
      'the entrypoint refusal is a LaunchFault: launch.mjs:51 defaults it to 78');
    assert.match(probe, /usage_exit=64\b/,
      'a malformed command line is the other documented code: launch.mjs:265');

    // (3): the core's readiness wait over that same live sandbox. `readiness` starts the
    // launcher through SandboxLifecycle (the two production calls the backend's launch
    // makes, minus its destroy-on-refusal, which would remove the container the wait must
    // run against), then opens the core adapter with the production refusal source. A
    // regression that stops consulting the refusal cannot pass this: the wait would spend
    // its 90 s budget and fail on `did not become ready within`.
    const readiness = await state.harness.send({
      command: 'readiness', runId: run.runId, argv: [REFUSED_ENTRYPOINT], workingDirectory: '/workspace',
    });
    assert.equal(readiness.opened, false,
      'a launch the image refused can never become ready; the wait must fail, not open a session');
    const readinessError = String(readiness.error ?? '');
    assert.match(readinessError, new RegExp(`refused the launch of run ${run.runId}`),
      'the readiness wait must end on the launcher\'s refusal, not on an unrelated failure');
    assert.match(readinessError, new RegExp(`not allowlisted by this image: ${REFUSED_ENTRYPOINT}`),
      'the readiness failure must carry the launcher\'s own sentence to the operator');
    assert.doesNotMatch(readinessError, /did not become ready within/,
      'the refusal must not be replaced by the readiness wait\'s bare timeout');
    assert.ok(Number.isInteger(readiness.elapsedMs) && readiness.elapsedMs < 60000,
      `the readiness wait must end on the refusal, not spend its 90 s budget (waited ${readiness.elapsedMs} ms)`);

    // (4): the production launch path over the same run must never leave the refusal
    // unproven, and must never answer with a live core for a launch that never spawned one.
    const refused = await state.harness.sendExpectingRefusal({
      command: 'launch',
      runId: run.runId,
      argv: [REFUSED_ENTRYPOINT],
      workingDirectory: '/workspace',
    });
    if (!refused.ok) {
      // The verdict landed inside the launch's own window: the launch ends on the image's
      // sentence (never on the readiness timeout) and destroys the sandbox it created.
      assert.match(refused.error, new RegExp(`refused the launch of run ${run.runId}`),
        'the failure must be the refused launch, not an unrelated one');
      assert.match(refused.error, new RegExp(`not allowlisted by this image: ${REFUSED_ENTRYPOINT}`),
        'the failure must carry the launcher\'s own sentence to the operator');
      assert.doesNotMatch(refused.error, /did not become ready within/,
        'the refusal must not be replaced by the readiness wait\'s bare timeout');
    } else {
      // The verdict landed after the window. The run exists, and the retained verdict is
      // the only truthful answer to a stop: the in-image script has no launch record to
      // verify, so a proof the plane cannot reach is what "not verified stopped" looks
      // like, forever.
      const stopped = await waitFor(async () => {
        const proof = await state.harness.send({ command: 'stopWriters', runId: run.runId });
        return proof.allWritersStopped ? proof : null;
      }, {
        timeoutMs: 90000,
        description: 'the plane to prove the refused launch never spawned a writer',
      });
      assert.equal(stopped.allWritersStopped, true,
        'a refused launch must be provably writer-free, not unverifiable');
    }
  } finally {
    // The case's own destroy is not the only exit. A failure anywhere above -- a failed
    // readiness assertion, a probe that threw -- would otherwise leave the prepared run's
    // container and workspace lease behind, which is exactly the leak this case exists to
    // catch. `destroy` is run-keyed and idempotent, so the successful path pays a cheap
    // second call and the failure path cannot leak.
    await state.harness.send({ command: 'destroy', runId: run.runId }).catch((error) => {
      process.stderr.write(`[sandbox-lane] run D cleanup could not destroy the run: ${error.message}\n`);
    });
  }

  // Nothing is left behind, measured AFTER the destroy above (the case's own or the
  // finally's). The container first (a leaked sandbox is a running container, wherever the
  // bookkeeping stands), then the server's own record, then the harness's dropped run --
  // bounding the first so a slow runtime kill cannot make this a race.
  await waitFor(() => sandboxContainers(state, run.environmentId).length === 0,
    { timeoutMs: 30000, description: 'the refused run\'s sandbox container to be gone' });
  const gone = await waitFor(async () => {
    const record = await sandboxRecord(state, run.environmentId);
    return record.status === 404 ? record : null;
  }, { timeoutMs: 30000, description: 'the server to forget the refused run\'s sandbox' });
  assert.match(String(gone.code), /SANDBOX_NOT_FOUND/,
    `the 404 must be the server's sandbox-not-found, not a route miss (status ${gone.status}, code ${gone.code})`);
  const deadExec = await state.harness.sendExpectingRefusal({
    command: 'exec', runId: run.runId, shell: 'true',
  });
  assert.equal(deadExec.ok, false, 'a destroyed sandbox can no longer be executed in');
  assert.match(deadExec.error, /has no sandbox/,
    'the refusal is the dropped run record, not an unrelated command failure');
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
