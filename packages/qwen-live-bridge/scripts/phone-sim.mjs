#!/usr/bin/env node
// Level-1 phone simulator for the Aria live socket: one operator upgrade, one
// hello, then a text turn or a PCM audio turn (and an optional image frame),
// verifying the phone-visible contract without a device. Event payloads are
// never printed; only types and metadata.
import fs from 'node:fs';
import WebSocket from 'ws';

const args = process.argv.slice(2);
const arg = (name, fallback) => {
  const at = args.indexOf(`--${name}`);
  return at >= 0 && args[at + 1] !== undefined ? args[at + 1] : fallback;
};

const host = arg('host', '127.0.0.1');
const port = arg('port', '8080');
const conversationId = arg('conversation', `live-sim-${Date.now()}`);
const text = arg('text', '');
const wavPath = arg('wav', '');
const imagePath = arg('image', '');
const token = process.env.ARIA_OPERATOR_BEARER_TOKEN || '';
const deadlineMs = Number(arg('deadline-ms', '60000'));

if (!token) {
  console.error('missing ARIA_OPERATOR_BEARER_TOKEN');
  process.exit(2);
}
if (!text && !wavPath) {
  console.error('give --text and/or --wav');
  process.exit(2);
}

const pcmFromWav = (path) => {
  const buf = fs.readFileSync(path);
  if (buf.toString('ascii', 0, 4) !== 'RIFF') throw new Error('not a RIFF wav');
  let at = 12;
  while (at + 8 <= buf.length) {
    const id = buf.toString('ascii', at, at + 4);
    const size = buf.readUInt32LE(at + 4);
    if (id === 'data') return buf.subarray(at + 8, at + 8 + size);
    at += 8 + size + (size % 2);
  }
  throw new Error('no data chunk');
};

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));
const seen = new Map();
const textEvents = [];
let audioBytes = 0;
let finalInput = false;
let finalOutput = false;
let ready = false;
let failure = null;
let closedCode = null;

const socket = new WebSocket(`ws://${host}:${port}/api/v1/aria/live`, {
  headers: { Authorization: `Bearer ${token}` },
});

const finish = (code) => {
  const counts = [...seen.entries()].map(([type, n]) => `${type}x${n}`).join(',');
  console.log(`events=${counts || 'none'}`);
  console.log(
    `checks ready=${ready} speech_end=${(seen.get('speech_end') || 0) > 0} ` +
      `input_final=${finalInput} output_final=${finalOutput} audio_bytes=${audioBytes} ` +
      `error=${failure ?? 'none'} close=${closedCode ?? 'open'}`,
  );
  try { socket.close(); } catch { /* already closed */ }
  process.exit(code);
};

const timer = setTimeout(() => {
  console.error('deadline reached');
  finish(3);
}, deadlineMs);

socket.on('open', () => {
  socket.send(JSON.stringify({
    type: 'hello',
    v: 1,
    conversationId,
    audio: { inRate: 16000, outRate: 24000, format: 'pcm16le', channels: 1 },
    mic: true,
    ar: false,
  }));
});

socket.on('message', async (data, isBinary) => {
  if (isBinary) {
    audioBytes += data.length;
    return;
  }
  let event;
  try { event = JSON.parse(data.toString()); } catch { return; }
  const type = typeof event?.type === 'string' ? event.type : 'unknown';
  seen.set(type, (seen.get(type) || 0) + 1);

  if (type === 'ready') {
    if (event.v !== 1 || event.maxSessionSec !== 7200) {
      failure = 'ready_contract';
      return finish(4);
    }
    ready = true;
    try {
      if (wavPath) {
        const pcm = pcmFromWav(wavPath);
        for (let at = 0; at < pcm.length; at += 640) {
          socket.send(pcm.subarray(at, at + 640));
          await sleep(20);
        }
      }
      const image = imagePath ? fs.readFileSync(imagePath).toString('base64') : '';
      if (image) {
        socket.send(JSON.stringify({ type: 'frame', v: 1, mime: 'image/jpeg', dataBase64: image, capturedAtMs: Date.now() }));
      }
      if (wavPath || image) {
        socket.send(JSON.stringify({ type: 'commit', v: 1, image: Boolean(image) }));
      }
      if (text) {
        socket.send(JSON.stringify({ type: 'text', v: 1, text }));
      }
    } catch (err) {
      failure = `send:${err.message}`;
      finish(5);
    }
  } else if (type === 'input_transcript') {
    finalInput = finalInput || event.final === true;
    textEvents.push(['in', event.seq, event.final, event.text]);
  } else if (type === 'output_transcript') {
    finalOutput = finalOutput || event.final === true;
    textEvents.push(['out', event.seq, event.final, event.text]);
  } else if (type === 'error') {
    failure = `${event.code}:${event.retryable}`;
  }

  if (finalOutput && (finalInput || !wavPath)) {
    clearTimeout(timer);
    for (const [dir, seq, fin, value] of textEvents) {
      if (fin) console.log(`${dir} seq=${seq} final=${fin} text=${JSON.stringify(value)}`);
    }
    finish(0);
  }
});

socket.on('close', (code) => {
  closedCode = code;
  clearTimeout(timer);
  if (!finalOutput) finish(failure ? 0 : 1);
});
