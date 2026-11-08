// Launch-environment contract: the bridge forwards a configured toolchain
// PATH (`ARIA_CORE_EXTRA_PATH`, set by the trusted launcher in the *bridge*
// process) ahead of the inherited PATH it gives the qoder CLI child.
//
// Finding F13: the core's Bash PATH was a reduced PATH (no `gh`, `jq`, `mvn`).
// Finding F14: the same launch contract injects the pack credential, so the
// environment construction must stay allowlisted: the forwarding variable is
// consumed, never forwarded.
import { spawnSync } from 'node:child_process';
import { mkdtempSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { afterEach, expect, test } from 'vitest';

import { buildChildEnvironment, childSpawnOptions, composeChildPath } from '../src/acp-client.js';

const roots: string[] = [];

afterEach(() => {
  for (const root of roots.splice(0)) {
    try {
      rmSync(root, { recursive: true, force: true, maxRetries: 5 });
    } catch {
      // Best effort: the child probe may still hold a directory handle.
    }
  }
});

test('composeChildPath prepends Windows entries without dropping the inherited PATH', () => {
  expect(
    composeChildPath(
      'C:\\Windows\\System32;C:\\Windows\\System32\\WindowsPowerShell\\v1.0',
      'C:\\Program Files\\GitHub CLI;C:\\Users\\User\\tools\\apache-maven-3.9.6\\bin',
      'win32',
    ),
  ).toBe(
    'C:\\Program Files\\GitHub CLI;C:\\Users\\User\\tools\\apache-maven-3.9.6\\bin;C:\\Windows\\System32;C:\\Windows\\System32\\WindowsPowerShell\\v1.0',
  );
});

test('composeChildPath keeps POSIX-form entries verbatim on Windows', () => {
  // F13's observed core PATH is POSIX-form inside the qoder shell; the launcher
  // may name entries the same way, and a drive-letter colon is not a separator.
  expect(composeChildPath('C:\\Windows\\System32', '/c/Users/User/bin;/c/Users/User/tools/jq', 'win32')).toBe(
    '/c/Users/User/bin;/c/Users/User/tools/jq;C:\\Windows\\System32',
  );
});

test('composeChildPath joins the launcher POSIX PATH with the POSIX delimiter on Windows', () => {
  // CoreLaunchEnvironment.bridgeEnvironment() sends both values in the Windows
  // POSIX style (`:`-joined, `/c/...` entries). Joining those on `;` would merge
  // the inherited entries into one invalid list and every extra directory after
  // the first into one entry; the launcher's real shape must round-trip.
  expect(
    composeChildPath(
      '/c/Windows/system32:/c/Program Files/Git/cmd',
      '/c/Program Files/GitHub CLI:/c/Users/User/tools/apache-maven-3.9.6/bin',
      'win32',
    ),
  ).toBe(
    '/c/Program Files/GitHub CLI:/c/Users/User/tools/apache-maven-3.9.6/bin:/c/Windows/system32:/c/Program Files/Git/cmd',
  );
});

test('composeChildPath prepends POSIX entries without dropping the inherited PATH', () => {
  expect(composeChildPath('/usr/local/bin:/usr/bin:/bin', '/opt/gh/bin:/opt/maven/bin', 'linux')).toBe(
    '/opt/gh/bin:/opt/maven/bin:/usr/local/bin:/usr/bin:/bin',
  );
});

test('composeChildPath treats extra entries as a platform-delimited list and drops empty entries', () => {
  expect(composeChildPath('/usr/bin', ':/opt/a::/opt/b:', 'linux')).toBe('/opt/a:/opt/b:/usr/bin');
  expect(composeChildPath('C:\\Windows', ' ;C:\\a;;C:\\b; ', 'win32')).toBe('C:\\a;C:\\b;C:\\Windows');
  expect(composeChildPath(undefined, '/opt/a:/opt/b', 'linux')).toBe('/opt/a:/opt/b');
  expect(composeChildPath('/usr/bin', undefined, 'linux')).toBe('/usr/bin');
  expect(composeChildPath('/usr/bin', '', 'linux')).toBe('/usr/bin');
  expect(composeChildPath(undefined, undefined, 'linux')).toBeUndefined();
  expect(composeChildPath('   ', ';;', 'win32')).toBeUndefined();
});

test('buildChildEnvironment consumes ARIA_CORE_EXTRA_PATH into PATH and never forwards the variable', () => {
  const env = buildChildEnvironment({
    platform: 'linux',
    base: {
      PATH: '/usr/local/bin:/usr/bin',
      ARIA_CORE_EXTRA_PATH: '/opt/gh/bin:/opt/maven/bin',
      GH_TOKEN: 'ambient-token-must-not-be-forwarded',
    },
    childEnv: {},
  });
  expect(env).toEqual({ PATH: '/opt/gh/bin:/opt/maven/bin:/usr/local/bin:/usr/bin' });
  expect(env.ARIA_CORE_EXTRA_PATH).toBeUndefined();
  expect(env.GH_TOKEN).toBeUndefined();
});

test('buildChildEnvironment prepends the extra PATH ahead of an explicit --child-env PATH', () => {
  const env = buildChildEnvironment({
    platform: 'win32',
    base: { SystemRoot: 'C:\\Windows', PATH: 'C:\\Windows\\System32', ARIA_CORE_EXTRA_PATH: 'C:\\GitHub CLI' },
    childEnv: { PATH: 'C:\\override\\bin' },
  });
  expect(env).toEqual({ SystemRoot: 'C:\\Windows', PATH: 'C:\\GitHub CLI;C:\\override\\bin' });
});

test('the constructed PATH is what a real child process sees, extra entries first', () => {
  const root = mkdtempSync(join(tmpdir(), 'aria-extra-path-'));
  roots.push(root);
  const extra = process.platform === 'win32' ? 'C:\\aria-extra-a;C:\\aria-extra-b' : '/aria-extra-a:/aria-extra-b';
  const env = buildChildEnvironment({
    platform: process.platform,
    base: { ...process.env, ARIA_CORE_EXTRA_PATH: extra },
    childEnv: {},
  });
  const probe = spawnSync(process.execPath, ['-e', 'process.stdout.write(process.env.PATH ?? "")'], {
    ...childSpawnOptions({ executable: process.execPath, args: [], cwd: root, env }),
    encoding: 'utf8',
  });
  expect(probe.status).toBe(0);
  expect(probe.stdout.startsWith(extra)).toBe(true);
  const delimiter = process.platform === 'win32' ? ';' : ':';
  const inherited = (process.env.PATH ?? '').split(delimiter).filter((entry) => entry.length > 0);
  for (const entry of inherited) expect(probe.stdout).toContain(entry);
});

test('the pack credential is the only GH_TOKEN/GITHUB_TOKEN the child receives', () => {
  // F14: the core saw ambient token values that GitHub rejected while the pack
  // credential was valid. The launcher declares the resolved credential; the
  // ambient values stay in the bridge process.
  const env = buildChildEnvironment({
    platform: 'linux',
    base: {
      PATH: '/usr/bin',
      GH_TOKEN: 'ambient-invalid-token',
      GITHUB_TOKEN: 'ambient-invalid-token-2',
      ARIA_CORE_EXTRA_PATH: '/opt/gh/bin',
    },
    childEnv: { GH_TOKEN: 'pack-resolved-token', GITHUB_TOKEN: 'pack-resolved-token' },
  });
  expect(env).toEqual({
    PATH: '/opt/gh/bin:/usr/bin',
    GH_TOKEN: 'pack-resolved-token',
    GITHUB_TOKEN: 'pack-resolved-token',
  });
});
