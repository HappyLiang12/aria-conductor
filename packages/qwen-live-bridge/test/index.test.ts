// The entry point's environment contract (spec L2, §"Bridge process"): the key comes
// from `DASHSCOPE_API_KEY`, the workspace from `DASHSCOPE_WORKSPACE`, an unusable
// environment exits 2 naming only the missing variable, and the endpoint defaults to
// Singapore unless the deployment set `QWEN_REALTIME_URL`.
//
// The values below are synthetic; the real ones never leave the deployment.
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { describe, expect, it } from 'vitest';
import { bridgeUrl, readConfig } from '../src/index.js';

const KEY = 'synthetic-dashscope-key';
const WORKSPACE = 'synthetic-workspace';

describe('index: config', () => {
  it('names the first missing variable and never a value', () => {
    expect(readConfig({})).toEqual({ config: null, missing: 'DASHSCOPE_API_KEY' });
    expect(readConfig({ DASHSCOPE_API_KEY: '   ' })).toEqual({
      config: null,
      missing: 'DASHSCOPE_API_KEY',
    });
    expect(readConfig({ DASHSCOPE_API_KEY: KEY })).toEqual({
      config: null,
      missing: 'DASHSCOPE_WORKSPACE',
    });
  });

  it('derives the Singapore endpoint from the workspace', () => {
    const { config, missing } = readConfig({
      DASHSCOPE_API_KEY: KEY,
      DASHSCOPE_WORKSPACE: WORKSPACE,
    });
    expect(missing).toBeNull();
    expect(config).toEqual({
      apiKey: KEY,
      workspace: WORKSPACE,
      url: `wss://${WORKSPACE}.ap-southeast-1.maas.aliyuncs.com/api-ws/v1/realtime?model=qwen3.8-omni-flash-realtime`,
    });
  });

  it('honours a configured endpoint verbatim', () => {
    const url = 'wss://example.invalid/api-ws/v1/realtime?model=test';
    expect(bridgeUrl(WORKSPACE, url)).toBe(url);
    expect(
      readConfig({
        DASHSCOPE_API_KEY: KEY,
        DASHSCOPE_WORKSPACE: WORKSPACE,
        QWEN_REALTIME_URL: url,
      }).config?.url,
    ).toBe(url);
  });
});

describe('index: process', () => {
  const entry = fileURLToPath(new URL('../src/index.ts', import.meta.url));
  // The bridge is spawned the way Java spawns it: a cleared environment plus these keys.
  const run = (env: Record<string, string>, input = '') =>
    spawnSync(process.execPath, ['--import', 'tsx', entry], {
      env: { PATH: process.env.PATH ?? '', ...env },
      input,
      encoding: 'utf8',
      timeout: 20_000,
    });

  it('exits 2 with the variable name when the key is absent', () => {
    const result = run({});
    expect(result.status).toBe(2);
    expect(result.stderr).toContain('DASHSCOPE_API_KEY');
    expect(result.stdout).toBe('');
  });

  it('exits 2 with the variable name when the workspace is absent', () => {
    const result = run({ DASHSCOPE_API_KEY: KEY });
    expect(result.status).toBe(2);
    expect(result.stderr).toContain('DASHSCOPE_WORKSPACE');
    expect(result.stderr).not.toContain(KEY);
    expect(result.stdout).toBe('');
  });

  it('exits 0 on close without contacting the provider', () => {
    const result = run(
      { DASHSCOPE_API_KEY: KEY, DASHSCOPE_WORKSPACE: WORKSPACE },
      '{"type":"close"}\n',
    );
    expect(result.status).toBe(0);
    expect(result.stdout).toBe('');
    // Nothing but the metadata flush reaches stderr, and it carries no credential.
    expect(result.stderr).not.toContain(KEY);
    expect(result.stderr).not.toContain(WORKSPACE);
  });
});
