// Unit tests of the fixed image launcher (runtime-sandbox/launch.mjs).
//
// These tests run on any OS: the manifest/allowlist validation is pure, and the
// real-spawn test proves the shell-free contract on this machine (a real child
// process receives the metacharacter argument verbatim and the run-owned record
// carries the child's real identity). The container boundary -- the child tree
// inside a sandbox, its suspension and termination -- is covered by
// e2e/agent-core/sandbox-lifecycle.test.mjs against a real container runtime.
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { existsSync, mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { test } from 'node:test';
import {
  LaunchFault,
  buildEnvironment,
  loadEntrypoints,
  main,
  parseArgs,
  resolveEntrypoint,
  validateManifest,
} from './launch.mjs';

const RUN_ID = '00000000-0000-0000-0000-0000000000t1';

function tempRoot() {
  return mkdtempSync(join(tmpdir(), 'aria-launch-test-'));
}

function validManifest(overrides = {}) {
  return {
    schemaVersion: 1,
    runId: RUN_ID,
    port: 4096,
    workingDirectory: '/workspace',
    argv: ['opencode', 'serve', '--hostname', '0.0.0.0', '--port', '4096'],
    env: { OPENCODE_MODEL: 'efficient' },
    ...overrides,
  };
}

test('a valid manifest is accepted and normalized', () => {
  const manifest = validateManifest(validManifest(), { expectRunId: RUN_ID, workspaceRoot: '/workspace' });
  assert.equal(manifest.runId, RUN_ID);
  assert.equal(manifest.readyTimeoutMs, 30000);
  assert.deepEqual(manifest.argv, ['opencode', 'serve', '--hostname', '0.0.0.0', '--port', '4096']);
});

test('the manifest schema, run id and port are enforced', () => {
  const options = { expectRunId: RUN_ID, workspaceRoot: '/workspace' };
  assert.throws(() => validateManifest(validManifest({ schemaVersion: 2 }), options), /schemaVersion/);
  assert.throws(() => validateManifest(validManifest({ runId: 'other-run' }), options), /belongs to run/);
  assert.throws(() => validateManifest(validManifest({ port: 0 }), options), /port/);
  assert.throws(() => validateManifest(validManifest({ port: 70000 }), options), /port/);
  assert.throws(() => validateManifest(validManifest({ workingDirectory: '/etc' }), options),
    /must stay inside \/workspace/);
  assert.throws(() => validateManifest(validManifest({ workingDirectory: 'workspace' }), options), LaunchFault);
});

test('argv entries that could break out of an argv element are refused', () => {
  const options = { expectRunId: RUN_ID, workspaceRoot: '/workspace' };
  assert.throws(() => validateManifest(validManifest({ argv: ['opencode', 'serve\necho injected'] }), options),
    /NUL, CR or LF/);
  assert.throws(() => validateManifest(validManifest({ argv: ['opencode', 'serve\0truncated'] }), options),
    /NUL, CR or LF/);
  assert.throws(() => validateManifest(validManifest({ argv: [] }), options), /1-16 entries/);
  assert.throws(() => validateManifest(validManifest({ argv: ['x'.repeat(5000)] }), options), /oversized/);
  assert.throws(() => validateManifest(validManifest({ argv: ['sh', '-c', 'opencode serve'] }), options),
    /not a shell/);
  assert.throws(() => validateManifest(validManifest({ argv: ['bash', '-lc', 'opencode serve'] }), options),
    /not a shell/);
});

test('injection-shaped manifest environment names and values are refused', () => {
  const options = { expectRunId: RUN_ID, workspaceRoot: '/workspace' };
  for (const name of ['PATH', 'NODE_OPTIONS', 'LD_PRELOAD', 'LD_LIBRARY_PATH', 'BASH_ENV', 'DYLD_INSERT_LIBRARIES']) {
    assert.throws(() => validateManifest(validManifest({ env: { [name]: '/tmp/hijack' } }), options),
      /process-injection variable/, `${name} must be refused`);
  }
  assert.throws(() => validateManifest(validManifest({ env: { 'OPENCODE-MODEL': 'x' } }), options),
    /plain variable name/);
  assert.throws(() => validateManifest(validManifest({ env: { MODEL: 'a\nb' } }), options), /NUL, CR or LF/);
  assert.throws(() => validateManifest(validManifest({ env: { MODEL: 'x'.repeat(9000) } }), options), /oversized/);
  assert.throws(() => validateManifest(validManifest({ env: [] }), options), /JSON object/);
});

test('the entrypoint must be allowlisted by the image, never by the manifest', () => {
  const root = tempRoot();
  try {
    const existing = join(root, 'fake-opencode');
    writeFileSync(existing, '#!/bin/sh\nexit 0\n');
    const allowlistFile = join(root, 'entrypoints.json');
    writeFileSync(allowlistFile, JSON.stringify({ names: { opencode: existing }, paths: [existing] }));
    const allowlist = loadEntrypoints(allowlistFile);

    assert.equal(resolveEntrypoint('opencode', allowlist), existing);
    assert.equal(resolveEntrypoint(existing, allowlist), existing);
    assert.throws(() => resolveEntrypoint('/usr/bin/curl', allowlist), /not allowlisted/);
    assert.throws(() => resolveEntrypoint('curl', allowlist), /not allowlisted/);
    assert.throws(() => resolveEntrypoint('/opt/aria/missing', { names: {}, paths: new Set(['/opt/aria/missing']) }),
      /missing from the image/);
    assert.throws(() => loadEntrypoints(join(root, 'absent.json')), /unreadable/);
    writeFileSync(join(root, 'empty.json'), JSON.stringify({ names: {}, paths: [] }));
    assert.throws(() => loadEntrypoints(join(root, 'empty.json')), /empty/);
  } finally {
    rmSync(root, { recursive: true, force: true });
  }
});

test('the fixed CLI surface refuses free-form arguments', () => {
  assert.deepEqual(parseArgs(['--manifest', '/run/x/manifest.json']), { manifest: '/run/x/manifest.json' });
  assert.throws(() => parseArgs(['-m', '/run/x']), /Unexpected argument/);
  assert.throws(() => parseArgs(['--manifest']), /Missing value/);
});

test('the child environment is the fixed base plus validated manifest entries', () => {
  const environment = buildEnvironment({ OPENCODE_MODEL: 'efficient' });
  assert.equal(environment.PATH, '/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin');
  assert.equal(environment.HOME, '/home/aria');
  assert.equal(environment.OPENCODE_MODEL, 'efficient');
});

test('a real child is spawned shell-free and receives metacharacter argv verbatim', async () => {
  const root = tempRoot();
  const runDirectory = join(root, 'run', RUN_ID);
  const output = join(root, 'child-output.json');
  const childScript = join(root, 'child.mjs');
  const metacharacter = 'a;b|c&d $(touch /tmp/aria-pwned) `id` > redirected';
  writeFileSync(childScript, [
    "import { writeFileSync } from 'node:fs';",
    'const payload = {',
    '  argv: process.argv.slice(2),',
    '  marker: process.env.ARIA_TEST_MARKER ?? null,',
    '  cwd: process.cwd(),',
    '  pid: process.pid,',
    '};',
    'writeFileSync(process.env.ARIA_TEST_OUT, JSON.stringify(payload));',
    'setTimeout(() => process.exit(0), 200);',
  ].join('\n'));
  mkdirSync(runDirectory, { recursive: true });
  const manifestPath = join(runDirectory, 'launch-manifest.json');
  writeFileSync(manifestPath, JSON.stringify(validManifest({
    workingDirectory: root,
    argv: [process.execPath, childScript, metacharacter, 'space arg'],
    env: { ARIA_TEST_MARKER: 'marker-1', ARIA_TEST_OUT: output },
    readyTimeoutMs: 300,
    port: 65000,
  })));
  const entrypointsFile = join(root, 'entrypoints.json');
  writeFileSync(entrypointsFile, JSON.stringify({ names: { node: process.execPath }, paths: [process.execPath] }));

  const code = await main(['--manifest', manifestPath, '--workspace-root', root, '--entrypoints', entrypointsFile]);
  assert.equal(code, 0);

  const child = JSON.parse(readFileSync(output, 'utf8'));
  assert.deepEqual(child.argv, [metacharacter, 'space arg'],
    'argv elements must reach the child verbatim: no shell expanded them');
  assert.equal(child.marker, 'marker-1', 'the validated manifest env reaches the child');
  assert.equal(child.cwd, root, 'the child runs in the manifest working directory');
  assert.equal(existsSync('/tmp/aria-pwned'), false, 'the metacharacter payload must never execute');

  const record = JSON.parse(readFileSync(join(runDirectory, 'launch-record.json'), 'utf8'));
  assert.equal(record.schemaVersion, 1);
  assert.equal(record.runId, RUN_ID);
  assert.equal(record.childPid, child.pid);
  assert.equal(record.supervisorPid, process.pid);
  assert.equal(record.entrypoint, process.execPath);
  assert.equal(record.argvDigest,
    createHash('sha256').update(JSON.stringify([process.execPath, childScript, metacharacter, 'space arg'])).digest('hex'));
  assert.equal(record.platform, process.platform);
  rmSync(root, { recursive: true, force: true });
});

test('a manifest from another run directory is refused before any spawn', async () => {
  const root = tempRoot();
  try {
    const runDirectory = join(root, 'run', '00000000-0000-0000-0000-0000000000t9');
    mkdirSync(runDirectory, { recursive: true });
    const manifestPath = join(runDirectory, 'launch-manifest.json');
    writeFileSync(manifestPath, JSON.stringify(validManifest({ workingDirectory: root })));
    await assert.rejects(
      main(['--manifest', manifestPath, '--workspace-root', root, '--entrypoints', join(root, 'absent.json')]),
      /belongs to run/);
    await assert.rejects(
      main(['--manifest', join(root, 'missing.json'), '--workspace-root', root]),
      (error) => error instanceof LaunchFault || error.code === 'ENOENT');
  } finally {
    rmSync(root, { recursive: true, force: true });
  }
});
