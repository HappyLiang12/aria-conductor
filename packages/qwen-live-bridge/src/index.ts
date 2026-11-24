#!/usr/bin/env node
// The bridge process (spec L1, §"Bridge process"): stdin is Java's JSON lines, stdout is
// this bridge's JSON lines, stderr is metadata and nothing else. One process per phone
// socket, spawned with an environment that holds exactly the two credentials plus the
// endpoint.
//
// The process owns the phone-side state machine that the session cannot: phone audio
// that arrives before Qwen's first `session.updated` is queued (and dropped past its
// cap); a later reopen is the session's own bounded queue. Every action becomes one
// stdout line, `close` shuts the provider socket down and exits 0, and a fatal writes
// `fatal{code}` before exiting non-zero.
import { createInterface } from 'node:readline';
import { pathToFileURL } from 'node:url';
import { MODEL } from './protocol.js';
import type { FatalLine, InboundLine, OutboundLine } from './protocol.js';
import { createMeter } from './log.js';
import { startSession, type LiveSession, type SessionAction } from './session.js';

/** The provider's Singapore region; a deployment may override the whole URL instead. */
const REGION = 'ap-southeast-1';
/** Audio queued while the session configures: ~10 s of 20 ms phone chunks. */
export const MAX_QUEUED_AUDIO = 500;
/** Exit codes: 0 closed by Java, 2 unusable environment, 3 fatal (3 = failed reopen). */
const EXIT_FATAL = 1;
const EXIT_FATAL_REOPEN = 3;

export interface BridgeConfig {
  apiKey: string;
  workspace: string;
  url: string;
}

export interface ConfigResult {
  config: BridgeConfig | null;
  /** The name of the missing variable, or null when the environment is usable. */
  missing: string | null;
}

/** The endpoint the manifest in the spec names, unless the deployment set one. */
export function bridgeUrl(workspace: string, configured?: string): string {
  const override = (configured ?? '').trim();
  if (override !== '') {
    return override;
  }
  return `wss://${workspace}.${REGION}.maas.aliyuncs.com/api-ws/v1/realtime?model=${MODEL}`;
}

/** Read-only: a missing variable is named by itself, never alongside a value. */
export function readConfig(env: NodeJS.ProcessEnv): ConfigResult {
  const apiKey = (env.DASHSCOPE_API_KEY ?? '').trim();
  if (apiKey === '') {
    return { config: null, missing: 'DASHSCOPE_API_KEY' };
  }
  const workspace = (env.DASHSCOPE_WORKSPACE ?? '').trim();
  if (workspace === '') {
    return { config: null, missing: 'DASHSCOPE_WORKSPACE' };
  }
  return {
    config: { apiKey, workspace, url: bridgeUrl(workspace, env.QWEN_REALTIME_URL) },
    missing: null,
  };
}

interface Deferred {
  run: () => void;
  audio: boolean;
}

async function main(): Promise<void> {
  const { config, missing } = readConfig(process.env);
  if (config === null) {
    process.stderr.write(`qwen-live-bridge: missing ${missing}\n`);
    process.exitCode = 2;
    return;
  }

  const writeLine = (line: OutboundLine) => {
    process.stdout.write(`${JSON.stringify(line)}\n`);
  };
  // Metadata only, stderr only: stdout stays pure control JSON (spec L1, L6).
  const meter = createMeter((line) => process.stderr.write(`${line}\n`));

  let session: LiveSession | null = null;
  let ready = false;
  let micOff = false;
  let finishing = false;
  const queued: Deferred[] = [];
  let queuedAudio = 0;

  const shutdown = async (code: number) => {
    if (finishing) {
      return;
    }
    finishing = true;
    reader.close();
    // The parent may keep its end open; this child must still be able to exit.
    process.stdin.pause();
    process.stdin.destroy();
    // A refused send is a frame the phone lost: one metadata line, never silent.
    const refused = session?.stats().refusedSends ?? 0;
    if (refused > 0) {
      meter.record('send_refused', refused, 0, 0);
    }
    // An `ar` switch the provider refused: a count only, never the provider's text.
    const refusedAr = session?.stats().refusedArSwitches ?? 0;
    if (refusedAr > 0) {
      meter.record('ar_switch_refused', refusedAr, 0, 0);
    }
    // AR-on wishes the commit guard held on the VAD (a phone that never commits): counts only.
    const guardHolds = session?.stats().arGuardHolds ?? 0;
    if (guardHolds > 0) {
      meter.record('ar_guard_hold', guardHolds, 0, 0);
    }
    meter.flush('session');
    if (session !== null) {
      await session.close();
    }
    // Let stdout drain: the parent reads these lines before the exit is noticed.
    process.exitCode = code;
  };

  const onAction = (action: SessionAction) => {
    switch (action.kind) {
      case 'control':
        writeLine({ type: 'control', event: action.event });
        return;
      case 'audio':
        // One delta at a time, exactly as the provider sent it (spec X3).
        writeLine({ type: 'audio', dir: 'out', dataBase64: action.dataBase64 });
        meter.count('audio_out', action.dataBase64.length);
        return;
      case 'tool_call':
        writeLine({
          type: 'tool_call',
          id: action.id,
          name: action.name,
          arguments: action.arguments,
        });
        meter.count('tool_call', 0);
        return;
      case 'turn_done':
        writeLine({ type: 'turn_done' });
        meter.flush('turn');
        return;
    }
  };

  const onFatal = (code: number) => {
    const fatal: FatalLine = { type: 'fatal', code };
    writeLine(fatal);
    void shutdown(code === 4012 ? EXIT_FATAL_REOPEN : EXIT_FATAL);
  };

  const flushQueue = () => {
    while (queued.length > 0) {
      const deferred = queued.shift();
      if (deferred !== undefined) {
        deferred.run();
      }
    }
    queuedAudio = 0;
  };

  const defer = (run: () => void, audio = false) => {
    if (audio && micOff) {
      // Java stops sending binary frames while the mic is off; a chunk in flight is void.
      return;
    }
    if (ready) {
      run();
      return;
    }
    if (audio) {
      if (queuedAudio >= MAX_QUEUED_AUDIO) {
        // Absorb-and-drop is allowed while the provider configures (spec X2).
        meter.dropped('audio_queue');
        return;
      }
      queuedAudio += 1;
    }
    queued.push({ run, audio });
  };

  const dropQueuedAudio = () => {
    for (let index = queued.length - 1; index >= 0; index -= 1) {
      if (queued[index]?.audio === true) {
        queued.splice(index, 1);
      }
    }
    queuedAudio = 0;
  };

  const startBridge = (line: Extract<InboundLine, { type: 'hello' }>) => {
    if (session !== null) {
      // One hello per socket: Aria validates it; a second is a protocol slip.
      return;
    }
    session = startSession({
      url: config.url,
      apiKey: config.apiKey,
      workspace: config.workspace,
      context: line.context ?? [],
      // The mode the phone had on its last socket (spec X17): Java keeps it per socket
      // and folds an `ar` received before hello into this field.
      ar: line.ar === true,
      arCommit: line.arCommit === true,
      onAction,
      onFatal,
    });
    void session
      .ready()
      .then(() => {
        ready = true;
        flushQueue();
      })
      .catch(() => {
        // onFatal already reported it; the exit follows.
      });
  };

  const handle = (line: InboundLine) => {
    switch (line.type) {
      case 'hello':
        startBridge(line);
        return;
      case 'audio':
        if (line.dir !== 'in') {
          return;
        }
        meter.count('audio_in', line.dataBase64.length);
        defer(() => session?.appendAudio(line.dataBase64), true);
        return;
      case 'text':
        meter.count('text_in', 0);
        defer(() => session?.sendText(line.text));
        return;
      case 'image':
        meter.count('image_in', line.dataBase64.length);
        defer(() => session?.attachImage(line.dataBase64));
        return;
      case 'commit':
        defer(() => session?.commit({ image: line.image }));
        return;
      case 'cancel':
        // A phone cancel is idempotent with the provider's own VAD (contract).
        defer(() => session?.cancel());
        return;
      case 'tool_result':
        defer(() => session?.toolResult(line.id, line.ok, line.output));
        return;
      case 'tower':
        defer(() => session?.tower(line.text, line.mode));
        return;
      case 'mic':
        micOff = line.on === false;
        if (micOff) {
          dropQueuedAudio();
        } else if (ready) {
          flushQueue();
        }
        return;
      case 'ar': {
        // Java forwards only a post-hello `ar`; a pre-hello one it folds into `hello.ar`.
        const current = session;
        if (current !== null) {
          defer(() => current.setAr(line.on === true));
        }
        return;
      }
      case 'close':
        void shutdown(0);
        return;
      default:
        // A fixed label only: the meter's one string field never carries phone text.
        meter.dropped('unknown');
    }
  };

  const reader = createInterface({ input: process.stdin });
  reader.on('line', (raw) => {
    if (finishing) {
      return;
    }
    let line: InboundLine;
    try {
      line = JSON.parse(raw) as InboundLine;
    } catch {
      meter.dropped('unparseable');
      return;
    }
    if (line === null || typeof line !== 'object' || typeof line.type !== 'string') {
      meter.dropped('unparseable');
      return;
    }
    handle(line);
  });
  // The parent closing stdin is the same instruction as `close`: leave cleanly.
  reader.on('close', () => {
    if (!finishing) {
      void shutdown(0);
    }
  });
}

const invokedDirectly =
  process.argv[1] !== undefined && import.meta.url === pathToFileURL(process.argv[1]).href;

if (invokedDirectly) {
  main().catch(() => {
    process.stderr.write('qwen-live-bridge: fatal\n');
    process.exitCode = 1;
  });
}
