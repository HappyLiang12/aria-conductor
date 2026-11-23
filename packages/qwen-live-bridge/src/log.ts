// Metadata-only logging (spec L6, X11): a log line carries a kind, a count, a byte
// count and a duration. Nothing else can be written, because nothing else is ever
// copied into the record — callers hand over numbers, and this module re-builds the
// object field by field from the allowlist.
//
// Lines go to stderr. Stdout belongs to control JSON (spec L1), so a log line can
// never be mistaken for, or interleaved with, a frame the Java reader dispatches.

/** The only fields a log line may contain, in the order they are written. */
export const LOG_FIELDS = ['type', 'count', 'bytes', 'ms'] as const;

export interface LogRecord {
  type: string;
  count: number;
  bytes: number;
  ms: number;
}

/** Where a log line goes; `process.stderr.write` in production, an array in tests. */
export type LogSink = (line: string) => void;

const MAX_KIND_LENGTH = 48;
/** Identifiers only: lowercase, digits, dots and underscores (provider event names). */
const KIND_PATTERN = /^[A-Za-z0-9_.]{1,200}$/;

/**
 * A safe kind. Anything that is not a plain identifier — including a value that carries
 * `=`, spaces, a dash or a key-shaped string — collapses to `other`, so a caller cannot
 * smuggle a payload into the one string field of a log line.
 */
export function sanitizeKind(kind: string): string {
  const trimmed = (kind ?? '').trim();
  if (!KIND_PATTERN.test(trimmed)) {
    return 'other';
  }
  return trimmed.slice(0, MAX_KIND_LENGTH).toLowerCase();
}

const finiteInteger = (value: number): number =>
  typeof value === 'number' && Number.isFinite(value) ? Math.trunc(value) : 0;

/**
 * One JSON line with exactly {@link LOG_FIELDS}. Extra properties on the argument are
 * dropped rather than serialized: the record is rebuilt, never spread.
 */
export function formatRecord(record: LogRecord): string {
  const line: LogRecord = {
    type: sanitizeKind(record.type),
    count: finiteInteger(record.count),
    bytes: finiteInteger(record.bytes),
    ms: finiteInteger(record.ms),
  };
  return JSON.stringify(line);
}

export interface Meter {
  /** One unit of `kind`, carrying `bytes` of whatever it measured (0 when not applicable). */
  count(kind: string, bytes?: number): void;
  /** One provider event the bridge dropped (spec X4: everything off the allowlist). */
  dropped(kind: string): void;
  /** Write what accumulated since the last flush; nothing recorded writes nothing. */
  flush(kind: string): void;
  /** Write one already-measured line (a single event with its own duration). */
  record(type: string, count: number, bytes: number, ms: number): void;
}

/**
 * A per-turn accumulator. A turn's counters are flushed once (at `response.done` or at
 * close) instead of once per 20 ms audio chunk, so a long turn costs one log line.
 */
export function createMeter(sink: LogSink, clock: () => number = Date.now): Meter {
  let count = 0;
  let bytes = 0;
  let startedAt = clock();

  const reset = (at: number) => {
    count = 0;
    bytes = 0;
    startedAt = at;
  };

  return {
    count(kind, size = 0) {
      count += 1;
      bytes += finiteInteger(size);
    },
    dropped() {
      count += 1;
    },
    flush(kind) {
      const at = clock();
      if (count === 0) {
        startedAt = at;
        return;
      }
      sink(formatRecord({ type: kind, count, bytes, ms: at - startedAt }));
      reset(at);
    },
    record(type, recordCount, recordBytes, ms) {
      sink(formatRecord({ type, count: recordCount, bytes: recordBytes, ms }));
    },
  };
}
