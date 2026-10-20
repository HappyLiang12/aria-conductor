// CI pipeline contract for the deterministic core harness and the real sandbox lane
// (Task 19, Step 1).
//
// These are cheap, deterministic text assertions over the committed CI surface:
// the workflows under `.github/workflows/`, the composite action under
// `.github/actions/`, the reactor POM's test tiering and the renamed
// integration test. They exist so the rewire cannot be "verified" while the
// pipeline still provisions the removed LangChain/Python toolchain, still
// demands a live-LLM key or a Qoder PAT from a required PR lane, boots a stack
// without the deterministic harness distribution, or substitutes the
// process-transport core lane for the real-container sandbox lane.
//
// The lane never skips, never needs Docker/podman, a container runtime, the
// network or a Java toolchain:
//
//   node --test e2e/agent-core/ci-contract.test.mjs
import assert from 'node:assert/strict';
import { existsSync, readFileSync, readdirSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { test } from 'node:test';

const REPO = resolve(dirname(fileURLToPath(import.meta.url)), '..', '..');
// Normalised to LF: the assertions below slice blocks by line, and the working
// tree may carry CRLF on Windows checkouts.
const read = (rel) => readFileSync(join(REPO, rel), 'utf8').replace(/\r\n/g, '\n');

const CI = read('.github/workflows/ci.yml');
const NIGHTLY = read('.github/workflows/nightly.yml');
const SDD_SMOKE = read('.github/workflows/nightly-sdd-llm-smoke.yml');
const START_STACK = read('.github/actions/start-stack/action.yml');
const SANDBOX_LANE_FILE = '.github/workflows/sandbox-lifecycle.yml';
const SANDBOX_LANE = existsSync(join(REPO, SANDBOX_LANE_FILE)) ? read(SANDBOX_LANE_FILE) : null;

/** Every YAML file under `.github/`, so a stale reference cannot hide in one of them. */
function githubYamlFiles() {
  const files = [];
  const walk = (relative) => {
    for (const entry of readdirSync(join(REPO, relative), { withFileTypes: true })) {
      const child = `${relative}/${entry.name}`;
      if (entry.isDirectory()) {
        walk(child);
      } else if (entry.name.endsWith('.yml') || entry.name.endsWith('.yaml')) {
        files.push(child);
      }
    }
  };
  walk('.github');
  return files;
}

/**
 * The block of one top-level job, sliced from its `  name:` line to the next
 * top-level job (or EOF), so `needs:`/`steps:` assertions read that job alone.
 */
function jobBlock(workflow, jobName) {
  const start = workflow.indexOf(`\n  ${jobName}:\n`);
  assert.ok(start >= 0, `workflow has no job named ${jobName}`);
  const rest = workflow.slice(start + 1);
  const next = rest.slice(1).search(/\n {2}[a-zA-Z0-9_-]+:\n/);
  return next < 0 ? rest : rest.slice(0, next + 1);
}

/**
 * The path-filter lists of the `changes` job as named include/exclude globs.
 * Indentation-driven: the list names sit deeper than the job keys and shallower
 * than their `- 'glob'` entries.
 */
function changesFilters(workflow) {
  const job = jobBlock(workflow, 'changes');
  const from = job.indexOf('filters: |');
  assert.ok(from >= 0, 'the changes job declares no filters block');
  const lines = job.slice(from).split('\n').slice(1);
  const filters = new Map();
  let nameIndent = null;
  let current = null;
  for (const rawLine of lines) {
    if (rawLine.trim() === '' || rawLine.trim().startsWith('#')) continue;
    const indent = rawLine.search(/\S/);
    if (nameIndent === null) {
      nameIndent = indent;
    } else if (indent < nameIndent) {
      break;
    }
    const line = rawLine.trim();
    if (indent === nameIndent && /^[a-zA-Z0-9_-]+:$/.test(line)) {
      current = line.slice(0, -1);
      filters.set(current, []);
      continue;
    }
    if (indent > nameIndent && line.startsWith('- ') && current !== null) {
      filters.get(current).push(line.slice(2).replace(/^['"]|['"]$/g, ''));
    }
  }
  assert.ok(filters.size > 0, 'the changes job declares no path filters');
  return filters;
}

/** GitHub-style glob match for the path patterns used by the filters. */
function matchesGlob(pattern, path) {
  const expression = pattern
    .split('**')
    .map((part) => part.split('*')
      .map((piece) => piece.replace(/[.+^${}()|[\]\\]/g, '\\$&'))
      .join('[^/]*'))
    .join('.*');
  return new RegExp(`^${expression}$`).test(path);
}

/** Whether one GitHub paths list selects a path (last matching pattern wins). */
function selectedBy(patterns, path) {
  let selected = false;
  for (const pattern of patterns) {
    const negated = pattern.startsWith('!');
    const glob = negated ? pattern.slice(1) : pattern;
    if (matchesGlob(glob, path)) selected = !negated;
  }
  return selected;
}

/** Whether any of the changes-filter lists selects the path. */
function selectedByAnyFilter(filters, path) {
  return [...filters.values()].some((patterns) => selectedBy(patterns, path));
}

/** The raw `- '...'` path entries of a YAML trigger `paths:` block. */
function triggerPaths(workflow) {
  const from = workflow.indexOf('paths:');
  assert.ok(from >= 0, 'no paths: trigger found');
  return workflow.slice(from).split('\n').slice(1)
    .map((line) => line.trim())
    .filter((line) => line.startsWith('- '))
    .map((line) => line.slice(2).replace(/^['"]|['"]$/g, ''));
}

test('no CI surface references the removed LangChain runtime or its Python toolchain', () => {
  for (const relative of githubYamlFiles()) {
    const text = read(relative);
    assert.ok(!text.includes('langchain-adk'), `${relative} still references the removed langchain-adk runtime`);
    for (const banned of ['actions/setup-python', 'pip install', 'pip-audit', 'pytest', 'uv.lock']) {
      assert.ok(!text.includes(banned),
        `${relative} still provisions the removed Python toolchain (${banned})`);
    }
  }
});

test('the changes filter covers the bridge, the peer fixtures, the scripts and the sandbox images', () => {
  const filters = changesFilters(CI);
  const required = [
    ['the Qoder ACP bridge source', 'packages/qoder-acp-bridge/src/main.ts'],
    ['the bridge build inputs', 'packages/qoder-acp-bridge/tsconfig.json'],
    ['the committed mock peers', 'agent-control-tower/act-app/src/test/resources/e2e/peers/mock-opencode.mjs'],
    ['the peer scenario manifest', 'agent-control-tower/act-app/src/test/resources/e2e/scenarios.json'],
    ['the e2e scripts', 'e2e/agent-core/sandbox-lifecycle.test.mjs'],
    ['the stack scripts', 'scripts/start-backend.sh'],
    ['the opencode sandbox image', 'agent-control-tower/opencode-sandbox/Dockerfile'],
    ['the qoder sandbox image', 'agent-control-tower/qoder-sandbox/Dockerfile'],
    ['the runtime sandbox image', 'agent-control-tower/runtime-sandbox/test.Dockerfile'],
    ['the dashboard package', 'agent-control-tower/act-dashboard/src/App.tsx'],
    ['the MCP server package', 'packages/mcp-server/src/server.ts'],
    ['the Java modules', 'agent-control-tower/act-execution/src/main/java/io/aria/conductor/execution/ExecutionModule.java'],
  ];
  for (const [what, path] of required) {
    assert.ok(selectedByAnyFilter(filters, path),
      `no changes-filter list selects ${what} (${path}); the lanes would not run for that change`);
  }
});

test('the harness artifact is built, verified with tests enabled and downloaded before the smoking lanes', () => {
  // The distribution build and the mandatory packaging IT (the profile lane runs
  // with tests enabled; the default Failsafe lane excludes that IT).
  assert.match(CI, /-Pcore-e2e-harness install -DskipTests/,
    'the harness distribution build command is missing');
  assert.match(CI, /-Pcore-e2e-harness -pl act-app verify[\s\S]{0,160}?-Dskip\.unit\.tests=true[\s\S]{0,160}?-Dit\.test=CoreE2ePackagingIntegrationTest/,
    'the packaging IT must run in the core-e2e-harness profile lane with tests enabled');
  // Both artifacts are uploaded from the build job, and the smoke/E2E jobs consume them.
  assert.match(CI, /name: backend-e2e-harness/,
    'the backend-e2e-harness artifact is never uploaded');
  assert.match(CI, /name: backend-jar/, 'the production backend-jar artifact is never uploaded');
  const uploader = jobBlock(CI, 'build');
  assert.match(uploader, /name: backend-e2e-harness/, 'the harness artifact must be built in the build job');
  for (const job of ['e2e-smoke', 'e2e-playwright']) {
    const block = jobBlock(CI, job);
    const needs = block.slice(block.indexOf('needs:'), block.indexOf('steps:'));
    assert.match(needs, /\bbuild\b/, `${job} must depend on the job that builds the harness artifact`);
  }
  // The composite action consumes the harness: it extracts the ZIP and boots the
  // deterministic launcher on the extracted ordinary classpath, then runs the
  // explicit setup.
  assert.match(START_STACK, /name: backend-e2e-harness/,
    'start-stack must download the backend-e2e-harness artifact');
  assert.match(START_STACK, /CoreE2eApplication/, 'start-stack must boot the harness launcher');
  assert.match(START_STACK, /-cp\s+"\$HARNESS\/app:\$HARNESS\/harness:\$HARNESS\/lib\/\*"/,
    'start-stack must boot the harness on the extracted app/harness/lib classpath');
  assert.match(START_STACK, /--e2e\.assets="\$HARNESS"/,
    'start-stack must point the launcher at the extracted distribution (--e2e.assets)');
  assert.match(START_STACK, /--e2e\.sandbox-transport=process/,
    'the process-transport lane must select the deterministic transport explicitly');
  for (const variable of ['ARIA_OPERATOR_BEARER_TOKEN', 'ARIA_PEER_CONTROL_TOKEN']) {
    assert.ok(START_STACK.includes(variable), `start-stack must supply ${variable}`);
  }
  assert.match(START_STACK, /CoreE2eSetup[\s\S]{0,300}?--base-url=/,
    'start-stack must run the explicit CoreE2eSetup harness setup');
  assert.match(START_STACK, /ARIA_E2E_QODER_CREDENTIAL/,
    'start-stack must hand CoreE2eSetup the harness-scoped Qoder credential');
  // Java and Node are provisioned even for backend-only stacks (the harness
  // launches node peers and, for Qoder, the ACP bridge).
  assert.match(START_STACK, /actions\/setup-java@/, 'start-stack must provision Java');
  assert.match(START_STACK, /actions\/setup-node@/,
    'start-stack must provision Node even for frontend-mode: none (the harness launches node peers)');
});

test('no required PR lane needs a live-LLM key or a Qoder personal access token', () => {
  assert.ok(!CI.includes('DEEPSEEK_API_KEY'),
    'ci.yml must not require a live-LLM key: the harness is deterministic');
  assert.ok(!/secrets\.[A-Z_]*QODER[A-Z_]*/i.test(CI), 'ci.yml must not require a Qoder credential secret');
  assert.ok(!/secrets\.[A-Z_]*PAT/i.test(CI), 'ci.yml must not require a personal access token');
  for (const variable of ['LLM_API_KEY', 'LLM_PROVIDER_API_KEY', 'OPENSANDBOX_API_KEY']) {
    assert.ok(!CI.includes(variable), `ci.yml must not require ${variable} for the PR lanes`);
  }
});

test('the PR pipeline keeps its dispatch, tiering, grouping and shard semantics', () => {
  assert.match(CI, /workflow_dispatch/,
    'ci.yml must declare an explicit workflow_dispatch for branch validation');
  // Unit vs Failsafe separation stays intact.
  const unit = jobBlock(CI, 'java-unit-tests');
  assert.ok(unit.includes('-Dskip.integration.tests=true'), 'the unit lane must keep skipping the integration tier');
  const integration = jobBlock(CI, 'java-integration-tests');
  assert.ok(integration.includes('-Dskip.unit.tests=true'), 'the integration lane must keep skipping the unit tier');
  // The reactor install precedes the module lanes (a stale installed jar must not decide a lane).
  for (const job of ['java-unit-tests', 'java-integration-tests']) {
    assert.match(jobBlock(CI, job), /install -DskipTests/,
      `${job} must be preceded by a reactor install`);
  }
  // act-mcp is part of the Java unit grouping.
  assert.match(unit, /act-mcp/, 'act-mcp must be part of the Java unit grouping');
  // The four Playwright shards, the frontend build+vitest and the MCP tests survive.
  assert.match(CI, /shard: \[1, 2, 3, 4\]/, 'the four Playwright shards must stay');
  assert.match(CI, /--shard=\$\{\{ matrix\.shard \}\}\/4 --workers=1/,
    'the sharded Playwright run must stay single-worker');
  assert.match(jobBlock(CI, 'frontend-build'), /pnpm test -- --coverage/,
    'the frontend vitest tier with coverage thresholds must stay');
  assert.match(jobBlock(CI, 'frontend-build'), /pnpm build/, 'the frontend build check must stay');
  assert.match(CI, /packages\/mcp-server && pnpm install --frozen-lockfile && pnpm test/,
    'the MCP server test lane must stay');
});

test('the sandbox lifecycle lane is a separate, requirement-triggered real-container workflow', () => {
  assert.ok(SANDBOX_LANE !== null,
    `${SANDBOX_LANE_FILE} is missing: the real OpenSandbox lane would be substituted by the process job`);
  assert.match(SANDBOX_LANE, /pull_request:/, 'the sandbox lane must trigger on pull requests');
  const patterns = triggerPaths(SANDBOX_LANE);
  for (const [what, path] of [
    ['the sandbox backend', 'agent-control-tower/act-execution/src/main/java/io/aria/conductor/execution/runtime/sandbox/SandboxExecutionBackend.java'],
    ['the workspace transfer', 'agent-control-tower/act-execution/src/main/java/io/aria/conductor/execution/runtime/RunWorkspaceService.java'],
    ['the sandbox test image', 'agent-control-tower/runtime-sandbox/test.Dockerfile'],
    ['the startup paths', 'scripts/start-backend.sh'],
    ['the sandbox lane itself', SANDBOX_LANE_FILE],
  ]) {
    assert.ok(selectedBy(patterns, path), `the sandbox lane is not requirement-triggered for ${what} (${path})`);
  }
  assert.match(SANDBOX_LANE, /node --test e2e\/agent-core\/sandbox-lifecycle\.test\.mjs/,
    'the sandbox lane must run the real-container lifecycle test');
  assert.match(SANDBOX_LANE, /agent-control-tower\/runtime-sandbox\/test\.Dockerfile/,
    'the sandbox lane must build the mock-peer test image');
  assert.match(SANDBOX_LANE, /OPEN_SANDBOX_URL/, 'the sandbox lane must address the real OpenSandbox server');
  assert.match(SANDBOX_LANE, /SANDBOX_RUNTIME/, 'the sandbox lane must name the container runtime');
  // Results are never substituted: the process lane stays in ci.yml and never
  // runs the real-container test, and no ci.yml job re-dispatches the sandbox lane.
  assert.ok(!CI.includes('sandbox-lifecycle.test.mjs'),
    'ci.yml must not run the real-container test in the process-transport lane');
  assert.ok(!/uses:\s*\.\/\.github\/workflows\/sandbox-lifecycle\.yml/.test(CI),
    'the sandbox lane must be reported by its own workflow, never re-dispatched from ci.yml');
});

test('the nightly SDD smoke runs the OpenCode core, keeps WAITING_APPROVAL and gates the live tier', () => {
  assert.match(SDD_SMOKE, /opencode/i, 'the nightly SDD smoke must be retargeted to the OpenCode core');
  assert.match(SDD_SMOKE, /WAITING_APPROVAL/,
    'the nightly SDD smoke must keep its WAITING_APPROVAL assertion');
  assert.match(SDD_SMOKE, /workflow_dispatch:[\s\S]{0,400}?\n\s+live:/,
    'the live tier must need an explicit dispatch input');
  assert.match(SDD_SMOKE, /secrets\.DEEPSEEK_API_KEY/,
    'the live tier must be gated on the credential secret');
  const liveSteps = SDD_SMOKE.split('\n').filter((line) => line.trim().startsWith('if:'));
  assert.ok(liveSteps.length > 0, 'the nightly SDD smoke must gate its live steps');
  assert.ok(liveSteps.some((line) => /steps\.live\.outputs\.enabled/.test(line)),
    'every live step must be guarded by the explicit live gate, not run unconditionally');
});

test('the nightly Java lanes no longer provision the removed Python runtime', () => {
  assert.ok(!NIGHTLY.includes('langchain'), 'nightly.yml still references the removed runtime');
  assert.ok(!NIGHTLY.includes('setup-python'), 'nightly.yml still provisions the removed Python toolchain');
});

test('the knowledge review concurrency IT is renamed into the Failsafe tier', () => {
  const stale = 'agent-control-tower/act-app/src/test/java/io/aria/conductor/app/KnowledgeReviewConcurrencyIT.java';
  const renamed = 'agent-control-tower/act-app/src/test/java/io/aria/conductor/app/KnowledgeReviewConcurrencyIntegrationTest.java';
  assert.ok(!existsSync(join(REPO, stale)),
    `${stale} matches neither Surefire's nor Failsafe's includes and never runs; rename it`);
  assert.ok(existsSync(join(REPO, renamed)), `${renamed} is missing`);
  assert.match(read(renamed), /class KnowledgeReviewConcurrencyIntegrationTest\b/,
    'the renamed file must declare the matching class');
  const pom = read('agent-control-tower/pom.xml');
  assert.match(pom, /<include>\*\*\/\*IntegrationTest\.java<\/include>/,
    'Failsafe must include **/*IntegrationTest.java so the renamed IT executes');
  assert.match(pom, /<exclude>\*\*\/\*IntegrationTest\.java<\/exclude>/,
    'Surefire must keep excluding **/*IntegrationTest.java from the unit tier');
});
