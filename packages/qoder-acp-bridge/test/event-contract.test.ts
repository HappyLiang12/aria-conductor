// Event-payload contract tests (findings F7, F9, F17).
//
// These are the pure builders behind the bridge event types. The ACP frames are
// untrusted input: every credential-shaped key must be redacted before an
// approval payload is published, and a large payload must be capped with an
// explicit truncation marker instead of being dropped.
import { expect, test } from 'vitest';

import { REDACTED, redactCredentialFields } from '../src/events.js';
import { TOOL_PAYLOAD_LIMIT_BYTES, toolEventsFromUpdate } from '../src/tool-events.js';
import { extractTokenUsage } from '../src/usage.js';

test('redactCredentialFields replaces credential-shaped keys at any depth', () => {
  const redacted = redactCredentialFields({
    command: 'gh issue view 81',
    api_key: 'sk-live-1',
    apiKey: 'sk-live-2',
    access_token: 'tok-3',
    GH_TOKEN: 'tok-4',
    Authorization: 'Bearer tok-5',
    headers: { authorization: 'Bearer tok-6', 'x-trace-id': 'trace-1' },
    nested: [{ password: 'pw-7', description: 'keep me' }],
    note: 'visible',
    count: 3,
  });
  expect(redacted).toEqual({
    command: 'gh issue view 81',
    api_key: REDACTED,
    apiKey: REDACTED,
    access_token: REDACTED,
    GH_TOKEN: REDACTED,
    Authorization: REDACTED,
    headers: { authorization: REDACTED, 'x-trace-id': 'trace-1' },
    nested: [{ password: REDACTED, description: 'keep me' }],
    note: 'visible',
    count: 3,
  });
});

test('redactCredentialFields leaves non-credential keys and primitives untouched', () => {
  expect(redactCredentialFields('plain')).toBe('plain');
  expect(redactCredentialFields(null)).toBeNull();
  expect(redactCredentialFields([1, 'a', { file_path: 'C:/ws/a.txt' }])).toEqual([1, 'a', { file_path: 'C:/ws/a.txt' }]);
  expect(redactCredentialFields({ monkey: 'banana', tokenizer: 'nope', secret_santa: 'gift' })).toEqual({
    monkey: 'banana',
    tokenizer: 'nope',
    secret_santa: 'gift',
  });
});

// ------------------------------------------------------------------ tool events

const TOOL_NAMES = () => new Map<string, string | null>();

test('tool.call carries the ACP tool call id, name, kind, title and input', () => {
  const names = TOOL_NAMES();
  const events = toolEventsFromUpdate(
    {
      sessionUpdate: 'tool_call',
      toolCallId: 'call_abc',
      status: 'pending',
      title: 'Edit spec.md',
      kind: 'edit',
      rawInput: { file_path: 'C:/ws/spec.md', content: 'alpha', token: 'ghp-hidden' },
      _meta: { qoder: { toolName: 'Write' } },
    },
    names,
  );
  expect(events).toEqual([
    {
      type: 'tool.call',
      payload: {
        toolCallId: 'call_abc',
        toolName: 'Write',
        kind: 'edit',
        title: 'Edit spec.md',
        input: { file_path: 'C:/ws/spec.md', content: 'alpha', token: REDACTED },
        inputBytes: Buffer.byteLength(
          JSON.stringify({ file_path: 'C:/ws/spec.md', content: 'alpha', token: REDACTED }),
          'utf8',
        ),
        inputTruncated: false,
      },
    },
  ]);
  expect(names.get('call_abc')).toBe('Write');
});

test('tool.call caps an oversized input at the documented limit and marks truncation', () => {
  const rawInput = { command: `run ${'x'.repeat(TOOL_PAYLOAD_LIMIT_BYTES * 2)}` };
  const events = toolEventsFromUpdate(
    {
      sessionUpdate: 'tool_call',
      toolCallId: 'call_big',
      kind: 'execute',
      title: 'run',
      rawInput,
      _meta: { qoder: { toolName: 'Bash' } },
    },
    TOOL_NAMES(),
  );
  const payload = events[0]?.payload as Record<string, unknown>;
  expect(payload.toolCallId).toBe('call_big');
  expect(payload.inputTruncated).toBe(true);
  expect(payload.inputBytes).toBe(Buffer.byteLength(JSON.stringify(rawInput), 'utf8'));
  expect(typeof payload.input).toBe('string');
  expect(String(payload.input).endsWith('[truncated]')).toBe(true);
  expect(Buffer.byteLength(String(payload.input), 'utf8')).toBeLessThanOrEqual(TOOL_PAYLOAD_LIMIT_BYTES);
  // The event is published, never dropped.
  expect(events).toHaveLength(1);
});

test('tool.result reports the result text and isError for completed and failed calls', () => {
  const names = TOOL_NAMES();
  names.set('call_ok', 'Bash');
  names.set('call_bad', 'Bash');
  expect(
    toolEventsFromUpdate(
      {
        sessionUpdate: 'tool_call_update',
        toolCallId: 'call_ok',
        status: 'completed',
        content: [{ type: 'content', content: { type: 'text', text: 'issue #81 body' } }],
        rawOutput: 'issue #81 body',
      },
      names,
    ),
  ).toEqual([
    {
      type: 'tool.result',
      payload: {
        toolCallId: 'call_ok',
        toolName: 'Bash',
        status: 'completed',
        isError: false,
        result: 'issue #81 body',
        resultBytes: Buffer.byteLength('issue #81 body', 'utf8'),
        resultTruncated: false,
      },
    },
  ]);
  const failed = toolEventsFromUpdate(
    { sessionUpdate: 'tool_call_update', toolCallId: 'call_bad', status: 'failed', rawOutput: 'exit 2' },
    names,
  );
  expect(failed[0]?.payload).toMatchObject({ toolCallId: 'call_bad', status: 'failed', isError: true, result: 'exit 2' });
});

test('tool.result falls back to the content text and marks an unknown tool name honestly', () => {
  const events = toolEventsFromUpdate(
    {
      sessionUpdate: 'tool_call_update',
      toolCallId: 'call_unknown',
      status: 'completed',
      content: [
        { type: 'content', content: { type: 'text', text: 'line one' } },
        { type: 'content', content: { type: 'text', text: 'line two' } },
      ],
    },
    TOOL_NAMES(),
  );
  expect(events[0]?.payload).toMatchObject({ toolName: null, result: 'line one\nline two', isError: false });
});

test('tool.result caps an oversized result and marks truncation', () => {
  const big = 'z'.repeat(TOOL_PAYLOAD_LIMIT_BYTES * 2);
  const events = toolEventsFromUpdate(
    { sessionUpdate: 'tool_call_update', toolCallId: 'call_big', status: 'completed', rawOutput: big },
    TOOL_NAMES(),
  );
  const payload = events[0]?.payload as Record<string, unknown>;
  expect(payload.resultTruncated).toBe(true);
  expect(payload.resultBytes).toBe(Buffer.byteLength(big, 'utf8'));
  expect(String(payload.result).endsWith('[truncated]')).toBe(true);
  expect(Buffer.byteLength(String(payload.result), 'utf8')).toBeLessThanOrEqual(TOOL_PAYLOAD_LIMIT_BYTES);
});

test('non-terminal and unrelated session updates produce no tool event', () => {
  expect(
    toolEventsFromUpdate({ sessionUpdate: 'tool_call_update', toolCallId: 'call_x', status: 'in_progress' }, TOOL_NAMES()),
  ).toEqual([]);
  expect(toolEventsFromUpdate({ sessionUpdate: 'agent_message_chunk', content: {} }, TOOL_NAMES())).toEqual([]);
  expect(toolEventsFromUpdate({ sessionUpdate: 'tool_call', title: 'no id' }, TOOL_NAMES())).toEqual([]);
  expect(toolEventsFromUpdate(null, TOOL_NAMES())).toEqual([]);
});

// ------------------------------------------------------------------ usage

test('extractTokenUsage reads the ACP usage block and keeps carried zeros', () => {
  expect(extractTokenUsage({ usage: { inputTokens: 12, outputTokens: 7, totalTokens: 19 } })).toEqual({
    inputTokens: 12,
    outputTokens: 7,
    totalTokens: 19,
    source: 'usage',
  });
  expect(extractTokenUsage({ usage: { inputTokens: 0, outputTokens: 0, totalTokens: 0 } })).toEqual({
    inputTokens: 0,
    outputTokens: 0,
    totalTokens: 0,
    source: 'usage',
  });
});

test('extractTokenUsage falls back to the recorded quota token counts', () => {
  expect(
    extractTokenUsage({
      _meta: { quota: { token_count: { input_tokens: 12, output_tokens: 7 } } },
    }),
  ).toEqual({ inputTokens: 12, outputTokens: 7, totalTokens: null, source: 'quota' });
});

test('extractTokenUsage prefers real quota counts over the placeholder usage zeros', () => {
  // Recorded shape of qoder 1.1.61: `usage {0,0,0}` next to real quota counts.
  expect(
    extractTokenUsage({
      usage: { inputTokens: 0, outputTokens: 0, totalTokens: 0 },
      _meta: { quota: { token_count: { input_tokens: 120, output_tokens: 30 } } },
    }),
  ).toEqual({ inputTokens: 120, outputTokens: 30, totalTokens: 0, source: 'usage+quota' });
});

test('extractTokenUsage keeps a carried zero when the quota block reports nothing better', () => {
  expect(
    extractTokenUsage({
      usage: { inputTokens: 0, outputTokens: 0, totalTokens: 0 },
      _meta: { quota: { token_count: { input_tokens: 0, output_tokens: 0 } } },
    }),
  ).toEqual({ inputTokens: 0, outputTokens: 0, totalTokens: 0, source: 'usage+quota' });
});

test('extractTokenUsage merges the two sources without fabricating a total', () => {
  expect(
    extractTokenUsage({
      usage: { inputTokens: 12 },
      _meta: { quota: { token_count: { input_tokens: 12, output_tokens: 7 } } },
    }),
  ).toEqual({ inputTokens: 12, outputTokens: 7, totalTokens: null, source: 'usage+quota' });
});

test('extractTokenUsage reports nothing when the frame carries no usage', () => {
  expect(extractTokenUsage({ stopReason: 'end_turn' })).toBeNull();
  expect(extractTokenUsage({ usage: {}, _meta: { quota: {} } })).toBeNull();
  expect(extractTokenUsage(null)).toBeNull();
  expect(extractTokenUsage({ usage: { inputTokens: '12', outputTokens: -1, totalTokens: 1.5 } })).toBeNull();
});
