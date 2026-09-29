// Permission-selection contract tests (Task 8, Step 1).
//
// The recorded wire evidence (e2e/agent-core/fixtures/qoder-host-cli-1.1.61-protocol.jsonl)
// shows that the core offers, per tool kind:
//   edit    (lines 41/66/136): proceed_always / "Allow for this session"   (kind allow_always)
//                              proceed_once   / "Allow"                    (kind allow_once)
//                              cancel         / "Reject"                   (kind reject_once)
//   execute (lines 84/165):    proceed_always_and_save / "Always allow \"<program>\""
//                              proceed_once   / "Allow"
//                              cancel         / "Reject"
//
// Selecting a native option must use the offered option's kind, never its
// position and never a fallback to the first option. An ALLOW_ONCE decision may
// never be satisfied by an allow_always option.
import { expect, test } from 'vitest';

import { parseChoice, selectOption } from '../src/permissions.js';

const RECORDED_EDIT_OPTIONS = [
  { optionId: 'proceed_always', name: 'Allow for this session', kind: 'allow_always' },
  { optionId: 'proceed_once', name: 'Allow', kind: 'allow_once' },
  { optionId: 'cancel', name: 'Reject', kind: 'reject_once' },
];

const RECORDED_EXECUTE_OPTIONS = [
  { optionId: 'proceed_always_and_save', name: 'Always allow "printf"', kind: 'allow_always' },
  { optionId: 'proceed_once', name: 'Allow', kind: 'allow_once' },
  { optionId: 'cancel', name: 'Reject', kind: 'reject_once' },
];

test('allow-once never selects the first allow-always option', () => {
  const options = [
    { optionId: 'persistent', kind: 'allow_always' },
    { optionId: 'once-42', kind: 'allow_once' },
    { optionId: 'reject-42', kind: 'reject_once' },
  ];
  expect(selectOption(options, 'ALLOW_ONCE')).toBe('once-42');
  expect(selectOption(options, 'DENY')).toBe('reject-42');
});

test('the recorded edit option list selects the recorded allow-once and reject option ids', () => {
  expect(selectOption(RECORDED_EDIT_OPTIONS, 'ALLOW_ONCE')).toBe('proceed_once');
  expect(selectOption(RECORDED_EDIT_OPTIONS, 'DENY')).toBe('cancel');
});

test('the recorded execute option list never selects proceed_always_and_save for ALLOW_ONCE', () => {
  expect(selectOption(RECORDED_EXECUTE_OPTIONS, 'ALLOW_ONCE')).toBe('proceed_once');
  expect(selectOption(RECORDED_EXECUTE_OPTIONS, 'DENY')).toBe('cancel');
});

test('a list whose only allow option is allow_always is refused for ALLOW_ONCE', () => {
  const allowAlwaysOnly = [
    { optionId: 'proceed_always_and_save', kind: 'allow_always' },
    { optionId: 'cancel', kind: 'reject_once' },
  ];
  expect(() => selectOption(allowAlwaysOnly, 'ALLOW_ONCE')).toThrowError(
    new Error('Expected one allow_once option'),
  );
  // The deny decision is still answerable from the same list.
  expect(selectOption(allowAlwaysOnly, 'DENY')).toBe('cancel');
});

test('a list without a reject_once option is refused for DENY even when allow options exist', () => {
  const noReject = [
    { optionId: 'proceed_always', kind: 'allow_always' },
    { optionId: 'proceed_once', kind: 'allow_once' },
  ];
  expect(() => selectOption(noReject, 'DENY')).toThrowError(new Error('Expected one reject_once option'));
});

test('duplicate kinds are refused instead of picking one of them', () => {
  const duplicateAllowOnce = [
    { optionId: 'once-a', kind: 'allow_once' },
    { optionId: 'once-b', kind: 'allow_once' },
    { optionId: 'cancel', kind: 'reject_once' },
  ];
  expect(() => selectOption(duplicateAllowOnce, 'ALLOW_ONCE')).toThrowError(
    new Error('Expected one allow_once option'),
  );
  const duplicateReject = [
    { optionId: 'proceed_once', kind: 'allow_once' },
    { optionId: 'cancel-a', kind: 'reject_once' },
    { optionId: 'cancel-b', kind: 'reject_once' },
  ];
  expect(() => selectOption(duplicateReject, 'DENY')).toThrowError(new Error('Expected one reject_once option'));
});

test('an empty option list is refused for both choices', () => {
  expect(() => selectOption([], 'ALLOW_ONCE')).toThrowError(new Error('Expected one allow_once option'));
  expect(() => selectOption([], 'DENY')).toThrowError(new Error('Expected one reject_once option'));
});

test('the HTTP choice is validated before any option is selected', () => {
  expect(parseChoice('ALLOW_ONCE')).toBe('ALLOW_ONCE');
  expect(parseChoice('DENY')).toBe('DENY');
  expect(() => parseChoice('allow_once')).toThrowError(
    new Error('Invalid permission choice: "allow_once"; expected ALLOW_ONCE or DENY'),
  );
  expect(() => parseChoice('ALLOW_ALWAYS')).toThrowError(
    new Error('Invalid permission choice: "ALLOW_ALWAYS"; expected ALLOW_ONCE or DENY'),
  );
  expect(() => parseChoice(undefined)).toThrowError(
    new Error('Invalid permission choice: undefined; expected ALLOW_ONCE or DENY'),
  );
  expect(() => parseChoice(null)).toThrowError(
    new Error('Invalid permission choice: null; expected ALLOW_ONCE or DENY'),
  );
  expect(() => parseChoice(42)).toThrowError(
    new Error('Invalid permission choice: 42; expected ALLOW_ONCE or DENY'),
  );
  expect(() => parseChoice({ choice: 'ALLOW_ONCE' })).toThrowError(
    new Error('Invalid permission choice: {"choice":"ALLOW_ONCE"}; expected ALLOW_ONCE or DENY'),
  );
});
