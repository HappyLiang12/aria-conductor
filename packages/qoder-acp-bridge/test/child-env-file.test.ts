// F14: the resolved git credential reaches the CLI child through a run-owned
// file instead of a `--child-env KEY=VALUE` argument. The process table is
// readable by the same user (including the agent's own Bash tool), so the
// credential must never be rendered into the bridge's command line.
import { mkdtempSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { afterEach, expect, test } from 'vitest';

import { parseArgs } from '../src/main.js';

const roots: string[] = [];

afterEach(() => {
  for (const root of roots.splice(0)) {
    try {
      rmSync(root, { recursive: true, force: true, maxRetries: 5 });
    } catch {
      // Best effort on Windows: the file may still be held briefly.
    }
  }
});

const BASE_ARGV = [
  '--run-id', 'run-1',
  '--workspace', '/runs/scratch/run-1',
  '--model', 'efficient',
  '--cli', '/opt/qodercli',
  '--control-secret-file', '/runs/run-1/bridge-control.secret',
];

function childEnvironmentFile(content: string): string {
  const root = mkdtempSync(join(tmpdir(), 'aria-child-env-file-'));
  roots.push(root);
  const file = join(root, 'child-env.env');
  writeFileSync(file, content, 'utf8');
  return file;
}

test('--child-env-file merges KEY=VALUE lines into the child environment', () => {
  const file = childEnvironmentFile('GH_TOKEN=ghp_file_value\nGITHUB_TOKEN=ghp_file_value\n');

  const options = parseArgs([...BASE_ARGV, '--child-env-file', file]);

  expect(options.childEnv).toEqual({
    GH_TOKEN: 'ghp_file_value',
    GITHUB_TOKEN: 'ghp_file_value',
  });
});

test('--child-env-file entries override an inline --child-env pair of the same name', () => {
  const file = childEnvironmentFile('GH_TOKEN=ghp_from_file\n');

  const options = parseArgs([
    ...BASE_ARGV,
    '--child-env', 'GH_TOKEN=ghp_from_argv',
    '--child-env', 'GIT_CEILING_DIRECTORIES=/runs/scratch',
    '--child-env-file', file,
  ]);

  expect(options.childEnv.GH_TOKEN).toBe('ghp_from_file');
  expect(options.childEnv.GIT_CEILING_DIRECTORIES).toBe('/runs/scratch');
});

test('an unreadable --child-env-file is refused', () => {
  expect(() => parseArgs([...BASE_ARGV, '--child-env-file', join(tmpdir(), 'aria-absent-child-env')]))
    .toThrow(/--child-env-file is not a readable file/);
});

test('a relative --child-env-file is refused', () => {
  expect(() => parseArgs([...BASE_ARGV, '--child-env-file', 'child-env.env']))
    .toThrow(/must be an absolute path/);
});

test('a malformed line is refused by line number without echoing the value', () => {
  const file = childEnvironmentFile('GH_TOKEN\nGITHUB_TOKEN=ghp_should_not_appear_in_the_error\n');

  expect(() => parseArgs([...BASE_ARGV, '--child-env-file', file]))
    .toThrow(/line 1 must be KEY=VALUE/);
});
