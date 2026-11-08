import { describe, expect, it } from 'vitest';
import { formatApprovalArguments, redactApprovalValue } from '../approvalArguments';

/**
 * F7 (SDD hardening): ACP permission approvals arrive argument-blind today and
 * the bridge/api lanes add a nullable `arguments` string. The UI renders it as
 * pretty JSON — but the arguments of a shell/MCP tool routinely carry
 * credentials (a `git push` URL, a provider key), so nothing that looks like a
 * secret may reach the DOM.
 */
describe('approval arguments formatting', () => {
  it('pretty-prints a JSON object', () => {
    const formatted = formatApprovalArguments('{"command":"git status","cwd":"/repo"}');

    expect(formatted).not.toBeNull();
    expect(formatted).toContain('{\n  "command": "git status",');
    expect(formatted).toContain('"cwd": "/repo"');
  });

  it('redacts secret-looking keys at every nesting level', () => {
    const formatted = formatApprovalArguments(
      JSON.stringify({
        command: 'git push https://x-access-token:ghp_live_secret@github.com/o/r',
        token: 'ghp_live_secret',
        env: { GITHUB_TOKEN: 'ghp_live_secret', PATH: '/usr/bin' },
        headers: [{ Authorization: 'Bearer abc123', accept: 'application/json' }],
        apiKey: 'sk-live-1',
        password: 'hunter2',
      }),
    );

    expect(formatted).not.toBeNull();
    expect(formatted).not.toContain('ghp_live_secret');
    expect(formatted).not.toContain('Bearer abc123');
    expect(formatted).not.toContain('sk-live-1');
    expect(formatted).not.toContain('hunter2');
    // Non-sensitive siblings survive untouched.
    expect(formatted).toContain('"PATH": "/usr/bin"');
    expect(formatted).toContain('"accept": "application/json"');
    expect(formatted).toContain('"token": "***"');
  });

  it('keeps a numeric or boolean argument value intact', () => {
    const formatted = formatApprovalArguments('{"maxIterations":5,"dryRun":true}');

    expect(formatted).toContain('"maxIterations": 5');
    expect(formatted).toContain('"dryRun": true');
  });

  it('returns null for absent, blank or non-string input', () => {
    expect(formatApprovalArguments(null)).toBeNull();
    expect(formatApprovalArguments(undefined)).toBeNull();
    expect(formatApprovalArguments('')).toBeNull();
    expect(formatApprovalArguments('   ')).toBeNull();
  });

  it('falls back to the raw text for arguments that are not strict JSON, still masking secrets', () => {
    const formatted = formatApprovalArguments('--token=ghp_raw_secret --mode fast');

    expect(formatted).toBe('--token=*** --mode fast');
  });

  it('bounds a very large argument blob instead of flooding the approval row', () => {
    const huge = JSON.stringify({ command: 'x'.repeat(9000) });
    const formatted = formatApprovalArguments(huge);

    expect(formatted).not.toBeNull();
    expect(formatted!.length).toBeLessThanOrEqual(4001);
    expect(formatted!.endsWith('…')).toBe(true);
  });

  it('leaves non-sensitive primitive values alone', () => {
    expect(redactApprovalValue('plain')).toBe('plain');
    expect(redactApprovalValue(42)).toBe(42);
    expect(redactApprovalValue(null)).toBeNull();
  });
});
