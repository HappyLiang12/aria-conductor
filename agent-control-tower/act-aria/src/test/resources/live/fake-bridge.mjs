#!/usr/bin/env node
// Contract fake for the Java bridge seam (io.aria.conductor.aria.live.QwenLiveBridgeProcessTest
// and act-app's LiveEndToEndIntegrationTest).
//
// It is a stdio child, exactly like the real bridge: one JSON object per line on stdin,
// one JSON object per line on stdout. It reports *facts about its own environment* so the
// Java side can prove the key arrived through the env map and never through argv, echoes
// every request as a control ack, and deliberately writes the raw key to stderr so the
// Java redactor has something real to remove.
//
// Modes, selected by FAKE_BRIDGE_MODE:
//   plain     (default) the Task 2 seam probe above and nothing else.
//   scripted  Task 6's replay of one recorded live turn. Java's `hello` starts it, and it
//             waits for the phone's `commit` before writing `speech_end` (AR-on: the
//             provider's turn end follows our explicit commit, spec D4), then the speech,
//             transcripts, one `tool_call`, a wait for Java's `tool_result`, a barge-in and
//             the turn end — the whole phone-facing order of spec X4-X6/X8.
//   fatal     a dead-provider replay: optionally the `session_expired` error a failed reopen
//             sends first, then `fatal{code}` and a non-zero exit (spec X9 close codes).
//
// Optional env:
//   FAKE_BRIDGE_EXIT_MS            exit with code 2 after that many milliseconds (plain).
//   FAKE_BRIDGE_RECEIPT            write a metadata receipt to this path (all modes): every
//                                  line received (type and counts only, never a payload) and
//                                  every line sent (type only). This is what the frame-ordering
//                                  and privacy assertions read.
//   FAKE_BRIDGE_FATAL_CODE         the fatal's close code (default 4012).
//   FAKE_BRIDGE_FATAL_PRIOR_ERROR  "0" suppresses the prior `session_expired` error (default on).

import { renameSync, writeFileSync } from 'node:fs';
import { createInterface } from 'node:readline';

const key = process.env.DASHSCOPE_API_KEY || '';
const workspace = process.env.DASHSCOPE_WORKSPACE || '';
const realtimeUrl = process.env.QWEN_REALTIME_URL || '';
const argv = process.argv.join(' ');

const mode = process.env.FAKE_BRIDGE_MODE || 'plain';
const receiptPath = process.env.FAKE_BRIDGE_RECEIPT || '';
const fatalCode = Number(process.env.FAKE_BRIDGE_FATAL_CODE || '4012');
const fatalPriorError = (process.env.FAKE_BRIDGE_FATAL_PRIOR_ERROR || '1') !== '0';
/** The exit code Java's close must see as "the child gave up", not as a phone close. */
let fatalExit = 0;

// --- receipt ------------------------------------------------------------------------------
// Metadata only: a receipt with a payload in it would be the leak the privacy scan hunts.
const receipt = { mode, received: [], sent: [] };

const receiptJson = () => JSON.stringify(receipt);

const flushReceipt = () => {
  if (receiptPath === '') {
    return;
  }
  try {
    // Written aside and renamed: the test reads this file while the child is alive, and a
    // partial read would be a flaky assertion rather than a fact.
    const aside = `${receiptPath}.tmp`;
    writeFileSync(aside, receiptJson());
    renameSync(aside, receiptPath);
  } catch {
    // A receipt is evidence, not control flow: a path that cannot be written must not
    // take the child down mid-turn.
  }
};

const chars = (value) => (typeof value === 'string' ? value.length : 0);

/** Set by the first `image` line and reported on the `commit` that follows it. */
let imageSeen = false;

const describeReceived = (message) => {
  switch (message.type) {
    case 'hello':
      return {
        type: 'hello',
        v: Number(message.v || 0),
        context: Array.isArray(message.context) ? message.context.length : 0,
      };
    case 'audio':
      return { type: 'audio', chars: chars(message.dataBase64) };
    case 'image':
      imageSeen = true;
      return { type: 'image', chars: chars(message.dataBase64) };
    case 'text':
      return { type: 'text', chars: chars(message.text) };
    case 'commit':
      return { type: 'commit', image: message.image === true, imagePreceded: imageSeen };
    case 'tool_result':
      return { type: 'tool_result', ok: message.ok === true, chars: chars(message.output) };
    case 'tower':
      return { type: 'tower', chars: chars(message.text) };
    case 'mic':
      return { type: 'mic', on: message.on === true };
    default:
      return { type: typeof message.type === 'string' ? message.type : 'unknown' };
  }
};

const describeSent = (object) =>
  object.type === 'control'
    ? { type: 'control', event: object.event && typeof object.event.type === 'string' ? object.event.type : '' }
    : { type: typeof object.type === 'string' ? object.type : 'unknown' };

const write = (object) => {
  receipt.sent.push(describeSent(object));
  process.stdout.write(JSON.stringify(object) + '\n');
  flushReceipt();
};

// --- environment report (Task 2) ----------------------------------------------------------

write({
  type: 'control',
  event: {
    type: 'env',
    keyPresent: key !== '',
    workspacePresent: workspace !== '',
    keyLength: key.length,
    workspaceLength: workspace.length,
    argvLeaks: key !== '' && argv.includes(key),
    liveSessionIdPresent: (process.env.LIVE_SESSION_ID || '') !== '',
    realtimeUrlPresent: realtimeUrl !== '',
    realtimeUrlLength: realtimeUrl.length,
    // The parent environment must not be inherited: HOME is the simplest witness, and
    // the key count is the exact size of what the Java side chose to hand over.
    homePresent: (process.env.HOME || '') !== '',
    envKeyCount: Object.keys(process.env).length,
  },
});

// Deliberately raw: the Java stderr reader must redact the key and the base64 blob.
process.stderr.write('fake-bridge stderr key=' + key + ' payload=' + 'A'.repeat(120) + '\n');

// --- scripted session (Task 6) ------------------------------------------------------------

const SCRIPTED_CALL_ID = 'call-scripted-1';
const SCRIPTED_STEP_MS = 30;

const control = (event) => ({ type: 'control', event });
const audioOut = (fill, bytes) => ({
  type: 'audio',
  dir: 'out',
  dataBase64: Buffer.alloc(bytes, fill).toString('base64'),
});

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

let started = false;
let toolResultArrived = false;
let onToolResult = null;
/** Set by the phone's `commit` line (Java forwards it after the audio and the frame). */
let commitArrived = false;
let onCommit = null;

const waitForToolResult = async (timeoutMs) => {
  if (toolResultArrived) {
    return true;
  }
  return new Promise((resolve) => {
    const timer = setTimeout(() => {
      onToolResult = null;
      resolve(false);
    }, timeoutMs);
    onToolResult = () => {
      clearTimeout(timer);
      onToolResult = null;
      resolve(true);
    };
  });
};

/**
 * The provider's own turn end follows the phone's explicit `commit` when AR is on (Java
 * forwards the commit after the audio and the frame, spec D4). Waiting for it keeps the
 * scripted `speech_end` from racing ahead of that commit: the turn boundary must not land
 * between the audio and the commit it belongs to, or the audio rule refuses the commit.
 */
const waitForCommit = async (timeoutMs) => {
  if (commitArrived) {
    return true;
  }
  return new Promise((resolve) => {
    const timer = setTimeout(() => {
      onCommit = null;
      resolve(false);
    }, timeoutMs);
    onCommit = () => {
      clearTimeout(timer);
      onCommit = null;
      resolve(true);
    };
  });
};

const scripted = async () => {
  // Realistic for AR-on: the utterance's commit reaches the bridge before the provider
  // ends the turn. A scripted turn with no commit still proceeds after the timeout.
  await waitForCommit(10_000);
  await sleep(SCRIPTED_STEP_MS);
  write(control({ type: 'speech_end', v: 1, tMs: Date.now() }));
  await sleep(SCRIPTED_STEP_MS);
  write(control({ type: 'input_transcript', v: 1, seq: 1, text: 'what is the time', final: false }));
  write(control({ type: 'input_transcript', v: 1, seq: 2, text: 'what is the time?', final: true }));
  await sleep(SCRIPTED_STEP_MS);
  write(control({ type: 'output_transcript', v: 1, seq: 1, text: 'Let me check', final: false }));
  write(control({ type: 'output_transcript', v: 1, seq: 2, text: 'Let me check the clock.', final: true }));
  await sleep(SCRIPTED_STEP_MS);
  write({ type: 'tool_call', id: SCRIPTED_CALL_ID, name: 'get_time', arguments: {} });
  // The function call is answered once, by Java; the receipt already holds that exchange
  // (the TOWER half of the seam a live turn uses), so the turn can continue.
  await waitForToolResult(10_000);
  write(control({ type: 'output_transcript', v: 1, seq: 3, text: 'It is a quarter past ten.', final: true }));
  await sleep(SCRIPTED_STEP_MS);
  write(audioOut(0x11, 128));
  write(audioOut(0x22, 128));
  await sleep(SCRIPTED_STEP_MS);
  write(control({ type: 'interrupted', v: 1, reason: 'user_speech' }));
  // Deliberately after the barge-in. Spec X6 puts `interrupted` before any further delta;
  // the phone drops what follows it, so the order is the assertion, not the delivery.
  write(audioOut(0x33, 128));
  await sleep(SCRIPTED_STEP_MS);
  write({ type: 'turn_done' });
};

const fatal = async () => {
  await sleep(SCRIPTED_STEP_MS);
  if (fatalPriorError) {
    // Exactly what the real bridge sends when its 120-minute reopen fails (spec X9):
    // the phone hears `session_expired` once, then the close code that follows it.
    write(control({
      type: 'error',
      v: 1,
      code: 'session_expired',
      retryable: true,
      message: 'Live session expired',
    }));
    await sleep(SCRIPTED_STEP_MS);
  }
  write({ type: 'fatal', code: fatalCode });
  fatalExit = fatalCode === 4012 ? 3 : 1;
  // Give the line time to leave the pipe, then exit the way the real bridge does.
  setTimeout(() => process.exit(fatalExit), SCRIPTED_STEP_MS);
};

// --- the loop -----------------------------------------------------------------------------

const lines = createInterface({ input: process.stdin });
lines.on('line', (line) => {
  let message;
  try {
    message = JSON.parse(line);
  } catch {
    write({ type: 'control', event: { type: 'bad_line' } });
    return;
  }
  if (message === null || typeof message !== 'object' || Array.isArray(message)) {
    write({ type: 'control', event: { type: 'bad_line' } });
    return;
  }
  receipt.received.push(describeReceived(message));
  write({ type: 'control', event: { type: 'ack', of: message.type } });

  if (message.type === 'hello' && !started) {
    started = true;
    if (mode === 'scripted') {
      void scripted();
    } else if (mode === 'fatal') {
      void fatal();
    }
  }
  if (message.type === 'tool_result') {
    toolResultArrived = true;
    if (onToolResult !== null) {
      onToolResult();
    }
  }
  if (message.type === 'commit') {
    commitArrived = true;
    if (onCommit !== null) {
      onCommit();
    }
  }
  if (message.type === 'audio' && message.dir === 'in') {
    write({ type: 'audio', dir: 'out', dataBase64: message.dataBase64 });
  }
  if (message.type === 'text') {
    write({ type: 'log', event: 'audio_delta', count: 1, bytes: 640, ms: 12 });
  }
  if (message.type === 'malformed') {
    // Everything the Java reader must drop without losing the child: a line that is not
    // JSON, a JSON array, an unknown type, a control without an event, and three audio
    // lines that are not outbound PCM.
    process.stdout.write('not json\n');
    process.stdout.write('[1,2,3]\n');
    write({ type: 'mystery' });
    write({ type: 'control' });
    write({ type: 'audio', dir: 'in', dataBase64: 'AAAA' });
    write({ type: 'audio', dir: 'out', dataBase64: '' });
    write({ type: 'audio', dir: 'out', dataBase64: '!!!!' });
  }
  if (message.type === 'turn') {
    // The turn-end literal Java consumes (Task 4).
    write({ type: 'turn_done' });
  }
  if (message.type === 'close') {
    process.exit(fatalExit);
  }
});
lines.on('close', () => process.exit(fatalExit));

const exitAfter = Number(process.env.FAKE_BRIDGE_EXIT_MS || '0');
if (exitAfter > 0) {
  setTimeout(() => process.exit(2), exitAfter);
}
