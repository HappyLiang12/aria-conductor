// Cutover contract for the production startup surface (Task 18, Step 1).
//
// These checks are cheap, deterministic text assertions over the scripts and
// compose file that the cutover touches. They exist so the cutover cannot be
// "verified" while the startup surface still selects the removed LangChain
// runtime, and so the Host mode (`-SkipSandbox` / `--skip-sandbox`) provably
// needs no container runtime. The lane never skips and never needs Docker,
// podman, a container runtime or the network:
//
//   node --test e2e/agent-core/cutover-contract.test.mjs
import assert from 'node:assert/strict';
import { existsSync, readFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { test } from 'node:test';

const REPO = resolve(dirname(fileURLToPath(import.meta.url)), '..', '..');
const read = (rel) => readFileSync(join(REPO, rel), 'utf8');

/** Production startup surface that must not mention the removed runtime. */
const CUTOVER_FILES = [
  'scripts/start.ps1',
  'scripts/start-backend.ps1',
  'scripts/start-backend.sh',
  'docker-compose.yml',
  '.env.example',
];

test('no production startup file references the removed LangChain runtime', () => {
  for (const rel of CUTOVER_FILES) {
    assert.ok(
      !read(rel).toLowerCase().includes('langchain'),
      `${rel} still references the removed LangChain runtime`,
    );
  }
});

test('the langchain-adk runtime tree is gone', () => {
  assert.ok(!existsSync(join(REPO, 'langchain-adk')), 'langchain-adk/ still exists');
});

test('start-backend.ps1 -SkipSandbox selects the Host-capable core qoder', () => {
  const ps1 = read('scripts/start-backend.ps1');
  assert.match(
    ps1,
    /\$SkipSandbox[\s\S]{0,600}?\$AdkProvider\s*=\s*"qoder"/,
    '-SkipSandbox must default the provider to the Host-capable core qoder',
  );
});

test('start-backend.ps1 keeps the container-runtime requirement inside the sandbox block only', () => {
  const ps1 = read('scripts/start-backend.ps1');
  const sandboxGuard = ps1.indexOf('if ($sandboxMode)');
  assert.ok(sandboxGuard >= 0, 'the OpenSandbox block must be guarded by the sandbox-mode predicate');
  const sandboxBlockEnd = ps1.indexOf('LLM credentials');
  assert.ok(sandboxBlockEnd > sandboxGuard, 'the sandbox block must end before the LLM credential section');
  const runtimeRefusals = [
    'Container runtime unavailable',
    'Neither docker nor podman is available',
    'Failed to start OpenSandbox server',
  ];
  for (const refusal of runtimeRefusals) {
    const at = ps1.indexOf(refusal);
    assert.ok(at > sandboxGuard && at < sandboxBlockEnd,
      `"${refusal}" must sit inside the sandbox-mode block`);
    // Each refusal must exit non-zero right where it is raised: the Host path
    // never reaches the block, and Sandbox mode fails loudly without a runtime.
    assert.match(ps1.slice(at, at + 400), /^\s*exit 1$/mu,
      `"${refusal}" must exit non-zero`);
  }
});

test('start-backend.sh --skip-sandbox selects the Host-capable core qoder', () => {
  const sh = read('scripts/start-backend.sh');
  assert.match(
    sh,
    /SKIP_SANDBOX" = "true"[\s\S]{0,400}?ADK_PROVIDER="qoder"/,
    '--skip-sandbox must default the provider to the Host-capable core qoder',
  );
  const sandboxGuard = sh.indexOf('if [ "$ADK_PROVIDER" = "opencode" ] && [ "$SKIP_SANDBOX" != "true" ]');
  assert.ok(sandboxGuard >= 0, 'the container-runtime checks must be guarded by the sandbox-mode predicate');
});

test('docker-compose.yml defines no langchain-adk service and no langchain provider default', () => {
  const compose = read('docker-compose.yml');
  assert.doesNotMatch(compose, /^\s{2}langchain-adk:/mu, 'the langchain-adk service must be removed');
  assert.doesNotMatch(compose, /ADK_DEFAULT_PROVIDER/, 'the compose default provider must be removed');
  assert.doesNotMatch(compose, /ADK_HOST|ADK_PORT/, 'the compose langchain host/port wiring must be removed');
});
