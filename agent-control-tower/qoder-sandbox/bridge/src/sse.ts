/**
 * Per-session SSE transport for the bridge control surface (C0.2
 * `GET /sessions/{id}/events`, plan Task B3b).
 *
 * One `SessionEventStream` owns the session's sequence counter, its bounded replay ring
 * (C0.2: 1000 events) and the set of attached HTTP responses. It never interprets an
 * event: the caller (server.ts) builds the frozen `{sequence, type, ...}` payload, so the
 * transport stays contract-agnostic.
 *
 * Replay rule (recorded decision): an `after` value is satisfiable while the buffer still
 * holds the event that follows it, i.e. `after >= floorSequence - 1`; anything older is a
 * `REPLAY_GAP`. A subscriber that sends no `after` resumes at `floorSequence - 1` — "replay
 * everything you still hold" — which can never be a gap, so a client connecting right after
 * `POST /sessions` cannot miss `session_started`.
 */
import type { ServerResponse } from 'node:http';

/** C0.2: replay ring size per session. */
export const EVENT_RING_CAPACITY = 1000;

/**
 * A stalled SSE reader must not grow the bridge's memory without bound: when a response
 * has more than this much unwritten data queued, the bridge drops that stream (the host
 * reconnects with its last sequence, which the ring buffer can replay).
 */
const MAX_PENDING_RESPONSE_BYTES = 1024 * 1024;

export interface BridgeEvent {
  sequence: number;
  type: string;
  [field: string]: unknown;
}

export class SessionEventStream {
  private readonly events: Array<BridgeEvent> = [];
  private readonly listeners = new Set<ServerResponse>();
  private nextSequence = 1;

  /** Highest sequence handed out (0 before the first event). */
  get lastSequence(): number {
    return this.nextSequence - 1;
  }

  /** Oldest sequence still replayable (the next sequence when nothing was retained). */
  get floorSequence(): number {
    const oldest = this.events[0];
    return oldest === undefined ? this.nextSequence : oldest.sequence;
  }

  get listenerCount(): number {
    return this.listeners.size;
  }

  /** Assign the next sequence, retain the event (evicting the oldest) and fan it out. */
  append(type: string, fields: Record<string, unknown> = {}): BridgeEvent {
    const event: BridgeEvent = { sequence: this.nextSequence++, type, ...fields };
    this.events.push(event);
    if (this.events.length > EVENT_RING_CAPACITY) {
      this.events.shift();
    }
    const frame = `data: ${JSON.stringify(event)}\n\n`;
    for (const listener of [...this.listeners]) {
      if (listener.destroyed || listener.writableEnded) {
        this.listeners.delete(listener);
        continue;
      }
      try {
        listener.write(frame);
        if (listener.writableLength > MAX_PENDING_RESPONSE_BYTES) {
          this.listeners.delete(listener);
          listener.destroy();
        }
      } catch {
        // A dead socket must never break the event stream; the other subscribers and the
        // ring buffer (for a reconnect) are unaffected.
        this.listeners.delete(listener);
      }
    }
    return event;
  }

  /** True when the retained window cannot satisfy `after` (its following event is gone). */
  isReplayGap(after: number): boolean {
    return after < this.floorSequence - 1;
  }

  /** The `after` value for a subscriber that did not say where to resume. */
  replayFromMissingAfter(): number {
    return this.floorSequence - 1;
  }

  /** Write the replay for `after`, then keep the response attached until it closes. */
  attach(res: ServerResponse, after: number): void {
    // One write per frame, mirroring `append`: a replayed event looks exactly like a live
    // one on the wire and in the response's write granularity.
    for (const event of this.events) {
      if (event.sequence > after) {
        res.write(`data: ${JSON.stringify(event)}\n\n`);
      }
    }
    this.listeners.add(res);
    // One detach path for disconnects and for write errors: without it every reconnect
    // would leave a listener (and its response) behind for the life of the session.
    const detach = (): void => {
      this.listeners.delete(res);
    };
    res.on('close', detach);
    res.on('error', detach);
  }

  /** End every attached stream (session end, bridge shutdown). */
  endAll(): void {
    for (const listener of [...this.listeners]) {
      this.listeners.delete(listener);
      try {
        listener.end();
      } catch {
        /* already closed */
      }
    }
  }
}
