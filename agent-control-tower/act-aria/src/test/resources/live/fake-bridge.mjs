#!/usr/bin/env node
// Contract fake for the Java bridge seam (io.aria.conductor.aria.live.QwenLiveBridgeProcessTest).
//
// It is a stdio child, exactly like the real bridge: one JSON object per line on stdin,
// one JSON object per line on stdout. It reports *facts about its own environment* so the
// Java side can prove the key arrived through the env map and never through argv, echoes
// every request as a control ack, and deliberately writes the raw key to stderr so the
// Java redactor has something real to remove.
//
// Optional env: FAKE_BRIDGE_EXIT_MS exits with code 2 after that many milliseconds, which
// is how the unexpected-exit path is driven.

import { createInterface } from 'node:readline';

const key = process.env.DASHSCOPE_API_KEY || '';
const workspace = process.env.DASHSCOPE_WORKSPACE || '';
const realtimeUrl = process.env.QWEN_REALTIME_URL || '';
const argv = process.argv.join(' ');

const write = (object) => {
  process.stdout.write(JSON.stringify(object) + '\n');
};

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

const lines = createInterface({ input: process.stdin });
lines.on('line', (line) => {
  let message;
  try {
    message = JSON.parse(line);
  } catch {
    write({ type: 'control', event: { type: 'bad_line' } });
    return;
  }
  write({ type: 'control', event: { type: 'ack', of: message.type } });
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
  if (message.type === 'close') {
    process.exit(0);
  }
});
lines.on('close', () => process.exit(0));

const exitAfter = Number(process.env.FAKE_BRIDGE_EXIT_MS || '0');
if (exitAfter > 0) {
  setTimeout(() => process.exit(2), exitAfter);
}
