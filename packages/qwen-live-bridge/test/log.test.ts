// Metadata-only logging (spec L6, X11): a log line may carry a kind, a count, a byte
// count and a duration — never a payload, a credential, a workspace or a URL.
// The tests are adversarial: they hand the logger payload-shaped values and assert
// none of them survive into the serialized line.
import { describe, expect, it } from 'vitest';
import { LOG_FIELDS, createMeter, formatRecord, sanitizeKind } from '../src/log.js';

const KEY = 'synthetic-dashscope-key';
const WORKSPACE = 'synthetic-workspace';
const PCM_BASE64 = 'AAECAwQFBgcICQoLDA0ODw==';
const IMAGE_BASE64 = 'aWdub3JlLW1lLWltYWdlLWJ5dGVz';

describe('log: field allowlist', () => {
  it('serializes exactly the four allowlisted fields', () => {
    const line = formatRecord({ type: 'audio_in', count: 3, bytes: 1920, ms: 40 });
    const parsed = JSON.parse(line) as Record<string, unknown>;
    expect(Object.keys(parsed)).toEqual([...LOG_FIELDS]);
    expect(parsed).toEqual({ type: 'audio_in', count: 3, bytes: 1920, ms: 40 });
  });

  it('drops every field a caller adds beyond the allowlist', () => {
    const line = formatRecord({
      type: 'turn',
      count: 1,
      bytes: 24,
      ms: 12,
      dataBase64: IMAGE_BASE64,
      apiKey: KEY,
      workspace: WORKSPACE,
      url: `wss://${WORKSPACE}.ap-southeast-1.maas.aliyuncs.com/api-ws/v1/realtime`,
    } as never);
    const parsed = JSON.parse(line) as Record<string, unknown>;
    expect(Object.keys(parsed)).toEqual([...LOG_FIELDS]);
    expect(line).not.toContain(IMAGE_BASE64);
    expect(line).not.toContain(KEY);
    expect(line).not.toContain(WORKSPACE);
    expect(line).not.toContain('maas.aliyuncs.com');
  });

  it('keeps a kind to its own characters so a payload cannot ride in it', () => {
    expect(sanitizeKind('response.audio.delta')).toBe('response.audio.delta');
    expect(sanitizeKind(`key=${KEY} workspace=${WORKSPACE}`)).toBe('other');
    expect(sanitizeKind('SK-SYNTHETIC')).toBe('other');
    expect(sanitizeKind('')).toBe('other');
    expect(sanitizeKind('x'.repeat(200))).toHaveLength(48);
  });

  it('refuses non-finite numbers instead of serializing them', () => {
    const parsed = JSON.parse(
      formatRecord({ type: 'turn', count: Number.NaN, bytes: Number.POSITIVE_INFINITY, ms: -1 }),
    ) as Record<string, number>;
    expect(parsed.count).toBe(0);
    expect(parsed.bytes).toBe(0);
    expect(parsed.ms).toBe(-1);
  });
});

describe('log: a turn with audio, an image and credentials', () => {
  it('writes counters only, and none of the payloads', () => {
    const lines: string[] = [];
    let now = 1_000;
    const meter = createMeter((line) => lines.push(line), () => now);

    // A turn as the bridge sees it: PCM in, a frame, PCM out, one tool call.
    meter.count('audio_in', PCM_BASE64.length);
    meter.count('audio_in', PCM_BASE64.length);
    meter.count('image_in', IMAGE_BASE64.length);
    meter.count('audio_out', PCM_BASE64.length);
    meter.count('tool_call', 0);
    now = 1_250;
    meter.flush('turn');

    expect(lines).toHaveLength(1);
    const parsed = JSON.parse(lines[0]!) as Record<string, unknown>;
    expect(Object.keys(parsed)).toEqual([...LOG_FIELDS]);
    expect(parsed).toEqual({
      type: 'turn',
      count: 5,
      bytes: PCM_BASE64.length * 3 + IMAGE_BASE64.length,
      ms: 250,
    });
    const joined = lines.join('\n');
    expect(joined).not.toContain(PCM_BASE64);
    expect(joined).not.toContain(IMAGE_BASE64);
    expect(joined).not.toContain(KEY);
    expect(joined).not.toContain(WORKSPACE);
  });

  it('does not write a line for a turn that recorded nothing', () => {
    const lines: string[] = [];
    const meter = createMeter((line) => lines.push(line), () => 0);
    meter.flush('turn');
    expect(lines).toEqual([]);
  });

  it('counts provider events it dropped without naming their payloads', () => {
    const lines: string[] = [];
    const meter = createMeter((line) => lines.push(line), () => 0);
    meter.dropped('mystery.delta');
    meter.dropped('mystery.delta');
    meter.dropped(`key=${KEY}`);
    meter.flush('dropped');
    expect(JSON.parse(lines[0]!)).toEqual({ type: 'dropped', count: 3, bytes: 0, ms: 0 });
    expect(lines.join('\n')).not.toContain(KEY);
  });
});
