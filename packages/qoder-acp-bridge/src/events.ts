// Bounded, sequenced bridge event log.
//
// Every bridge event carries a monotonically increasing `seq` that is never
// reused. The log is bounded: when it is full the oldest events are evicted and
// `retainedFrom` advances. A consumer that asks for events older than
// `retainedFrom - 1` gets an explicit replay gap instead of a silent partial
// answer, so an unrecoverable gap can never be mistaken for "nothing happened"
// and tools are never re-executed to reconstruct lost events.

export const REDACTED = '[REDACTED-SECRET]';

export interface BridgeEvent {
  seq: number;
  at: string;
  type: string;
  [key: string]: unknown;
}

export interface EventBufferOptions {
  capacity: number;
  /** Approximate retained-bytes ceiling; the oldest events are evicted first. */
  maxBytes?: number;
  secrets?: readonly string[];
  now?: () => string;
}

export type SinceResult =
  | { ok: true; events: BridgeEvent[]; lastSeq: number; retainedFrom: number }
  | { ok: false; reason: 'gap'; requestedAfter: number; retainedFrom: number; lastSeq: number }
  | { ok: false; reason: 'ahead'; requestedAfter: number; lastSeq: number; retainedFrom: number }
  | { ok: false; reason: 'invalid'; requestedAfter: number; lastSeq: number; retainedFrom: number };

export class EventBuffer {
  private readonly capacity: number;
  private readonly maxBytes: number;
  private readonly secrets: readonly string[];
  private readonly now: () => string;
  private readonly entries: BridgeEvent[] = [];
  private readonly listeners = new Set<(event: BridgeEvent) => void>();
  private byteSize = 0;
  private nextSeq = 1;
  private evictedThrough = 0;

  constructor(options: EventBufferOptions) {
    if (!Number.isInteger(options.capacity) || options.capacity < 1) {
      throw new Error(`Event buffer capacity must be a positive integer, got ${options.capacity}`);
    }
    this.capacity = options.capacity;
    this.maxBytes = options.maxBytes ?? Number.MAX_SAFE_INTEGER;
    this.secrets = options.secrets ?? [];
    this.now = options.now ?? (() => new Date().toISOString());
  }

  push(type: string, payload: Record<string, unknown> = {}): BridgeEvent {
    const event: BridgeEvent = {
      seq: this.nextSeq,
      at: this.now(),
      type,
      ...(redactValue(payload, this.secrets) as Record<string, unknown>),
    };
    this.nextSeq += 1;
    this.entries.push(event);
    this.byteSize += Buffer.byteLength(JSON.stringify(event), 'utf8');
    // Always keep the newest event so a consumer can never be stranded without a
    // recovery point, even when one event alone exceeds the byte budget.
    while (
      this.entries.length > 1 &&
      (this.entries.length > this.capacity || this.byteSize > this.maxBytes)
    ) {
      const dropped = this.entries.shift();
      if (!dropped) break;
      this.byteSize -= Buffer.byteLength(JSON.stringify(dropped), 'utf8');
      this.evictedThrough = dropped.seq;
    }
    for (const listener of [...this.listeners]) {
      try {
        listener(event);
      } catch {
        // A listener must never break the protocol path; its own failure is its own problem.
      }
    }
    return event;
  }

  lastSeq(): number {
    return this.nextSeq - 1;
  }

  retainedFrom(): number {
    return this.evictedThrough + 1;
  }

  size(): number {
    return this.entries.length;
  }

  /** Events with `seq > after`, or an explicit gap/ahead/invalid refusal. */
  since(after: number): SinceResult {
    const lastSeq = this.lastSeq();
    const retainedFrom = this.retainedFrom();
    if (!Number.isInteger(after) || after < 0) {
      return { ok: false, reason: 'invalid', requestedAfter: after, lastSeq, retainedFrom };
    }
    if (after > lastSeq) {
      return { ok: false, reason: 'ahead', requestedAfter: after, lastSeq, retainedFrom };
    }
    if (after < retainedFrom - 1) {
      return { ok: false, reason: 'gap', requestedAfter: after, retainedFrom, lastSeq };
    }
    return {
      ok: true,
      events: this.entries.filter((event) => event.seq > after),
      lastSeq,
      retainedFrom,
    };
  }

  subscribe(listener: (event: BridgeEvent) => void): () => void {
    this.listeners.add(listener);
    return () => {
      this.listeners.delete(listener);
    };
  }
}

/**
 * Control delivery ledger. A control request is keyed by its natural identity
 * (for example `cancel`, or `permission:<requestId>`); a repeated delivery of
 * the same key returns the recorded outcome marked as deduplicated and never
 * performs the side effect a second time. The owner forgets a key when the epoch
 * it belongs to ends (a new prompt owns the `cancel` key), so a recorded outcome
 * can never be replayed onto an unrelated, still-running request.
 */
export class ControlLedger<T> {
  private readonly entries = new Map<string, T>();

  record(key: string, value: T): { value: T; deduplicated: boolean } {
    const existing = this.entries.get(key);
    if (existing !== undefined) return { value: existing, deduplicated: true };
    this.entries.set(key, value);
    return { value, deduplicated: false };
  }

  get(key: string): T | undefined {
    return this.entries.get(key);
  }

  /** Forget one recorded entry; a later delivery of the key is a fresh request. */
  delete(key: string): void {
    this.entries.delete(key);
  }

  keys(): string[] {
    return [...this.entries.keys()];
  }
}

/** Replace every known secret value in a string. Longer secrets win first. */
export function redactText(text: string, secrets: readonly string[]): string {
  let result = text;
  for (const secret of orderedSecrets(secrets)) {
    if (result.includes(secret)) result = result.split(secret).join(REDACTED);
  }
  return result;
}

/** Recursively redact known secret values inside an event payload. */
export function redactValue(value: unknown, secrets: readonly string[]): unknown {
  if (typeof value === 'string') return redactText(value, secrets);
  if (Array.isArray(value)) return value.map((entry) => redactValue(entry, secrets));
  if (value !== null && typeof value === 'object') {
    const result: Record<string, unknown> = {};
    for (const [key, entry] of Object.entries(value as Record<string, unknown>)) {
      result[key] = redactValue(entry, secrets);
    }
    return result;
  }
  return value;
}

function orderedSecrets(secrets: readonly string[]): string[] {
  return secrets.filter((secret) => typeof secret === 'string' && secret.length > 0).sort((a, b) => b.length - a.length);
}
