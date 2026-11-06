import { test, expect } from '@playwright/test';
import * as fs from 'fs';
import * as path from 'path';
import {
  collectMetrics,
  runBounded,
  timedApiCall,
  uniqueName,
  type Metrics,
  type TimedResult,
} from '../fixtures';

/**
 * API-layer LOAD / STABILITY tier (LLM-free by design — the statistical body of
 * the concurrency effort). Drives read bursts, a mixed read/write load, and a
 * pool-saturation probe (concurrency >> HikariCP maximum-pool-size = 8), then
 * asserts the stack stays healthy and error-free at moderate load and degrades
 * gracefully (no crash) under saturation. Metrics + actuator snapshots are
 * written to EVIDENCE_DIR for the evidence pipeline.
 */
const BASE = process.env.API_URL || 'http://localhost:8080';
const EVIDENCE_DIR = process.env.EVIDENCE_DIR || 'test-results';

function writeEvidence(name: string, payload: unknown): void {
  try {
    fs.mkdirSync(EVIDENCE_DIR, { recursive: true });
    fs.writeFileSync(path.join(EVIDENCE_DIR, name), JSON.stringify(payload, null, 2));
  } catch (e) {
    console.log(`[load] could not write evidence ${name}: ${String(e)}`);
  }
}

/** Reads a Micrometer gauge via /actuator/metrics/{name} (0 if unavailable). */
async function metric(request: any, name: string): Promise<number> {
  try {
    const r = await request.get(`${BASE}/actuator/metrics/${name}`);
    if (!r.ok()) return 0;
    const j = await r.json();
    return j?.measurements?.[0]?.value ?? 0;
  } catch {
    return 0;
  }
}

async function poolSnapshot(request: any): Promise<Record<string, number>> {
  const [active, pending, idle, threads] = await Promise.all([
    metric(request, 'hikaricp.connections.active'),
    metric(request, 'hikaricp.connections.pending'),
    metric(request, 'hikaricp.connections.idle'),
    metric(request, 'jvm.threads.live'),
  ]);
  return { active, pending, idle, threads };
}

test.describe('Load & stability (LLM-free)', () => {
  test('A. read burst: 100 concurrent reads stay 2xx', async ({ request }) => {
    const paths = ['/knowledge', '/workflows', '/skills', '/agents'];
    const N = 100;
    const results: TimedResult[] = await runBounded(
      Array.from({ length: N }, (_, i) => paths[i % paths.length]),
      32,
      (p) => timedApiCall(request, 'GET', p),
    );
    const metrics = collectMetrics(results);
    writeEvidence('metrics-read-burst.json', metrics);
    console.log(`[load-read] ${JSON.stringify(metrics)}`);

    expect(results.every((r) => r.status >= 200 && r.status < 300)).toBe(true);
    expect(metrics.errorRate).toBe(0);
  });

  test('B. mixed load @16: no 5xx, stable', async ({ request }) => {
    const before = await poolSnapshot(request);
    const rounds = 3;
    const perRound = 16;
    const all: TimedResult[] = [];
    for (let r = 0; r < rounds; r++) {
      const batch = await runBounded(
        Array.from({ length: perRound }, (_, i) => i),
        perRound,
        (i) => {
          const kind = i % 4;
          if (kind === 0) return timedApiCall(request, 'GET', '/agents');
          if (kind === 1) {
            return timedApiCall(request, 'POST', '/knowledge', {
              name: uniqueName('e2e-load-k'),
              type: 'GUIDELINE',
              content: 'load content',
            });
          }
          if (kind === 2) return timedApiCall(request, 'GET', '/knowledge');
          return timedApiCall(request, 'GET', '/workflows');
        },
      );
      all.push(...batch);
    }
    const after = await poolSnapshot(request);
    const metrics = collectMetrics(all);
    writeEvidence('metrics-mixed-load.json', { metrics, pool: { before, after } });
    console.log(`[load-mixed] ${JSON.stringify(metrics)} pool=${JSON.stringify({ before, after })}`);

    // Moderate concurrency (16 vs pool 8) must not produce server errors.
    const serverErrors = all.filter((r) => r.status >= 500 || r.status === 0);
    expect(serverErrors, `5xx/transport errors under moderate load: ${JSON.stringify(serverErrors.slice(0, 5))}`).toHaveLength(0);
  });

  test('C. saturation probe: 48 concurrent writes degrade gracefully (no crash)', async ({ request }) => {
    const N = 48; // 6x the HikariCP pool; exercises connection queueing + Git mirror sync
    const before = await poolSnapshot(request);

    // Sample the pool WHILE the burst is in flight to capture real saturation
    // (pending > 0 / active up to the pool cap), not just the quiescent before/after.
    const peak = { active: 0, pending: 0, idle: 0, threads: 0 };
    let sampling = true;
    const sampler = (async () => {
      while (sampling) {
        const s = await poolSnapshot(request);
        peak.active = Math.max(peak.active, s.active);
        peak.pending = Math.max(peak.pending, s.pending);
        peak.idle = Math.max(peak.idle, s.idle);
        peak.threads = Math.max(peak.threads, s.threads);
        await new Promise((r) => setTimeout(r, 40));
      }
    })();

    const results: TimedResult[] = await runBounded(
      Array.from({ length: N }, (_, i) => i),
      N,
      (i) =>
        timedApiCall(request, 'POST', '/knowledge', {
          name: uniqueName('e2e-sat-k'),
          type: 'GUIDELINE',
          content: `saturation ${i}`,
        }),
    );
    sampling = false;
    await sampler;
    const after = await poolSnapshot(request);
    const metrics: Metrics = collectMetrics(results);
    const serverErrors = results.filter((r) => r.status >= 500 || r.status === 0).length;
    writeEvidence('metrics-saturation.json', { metrics, serverErrors, pool: { before, peak, after } });
    console.log(`[load-saturation] ${JSON.stringify(metrics)} serverErrors=${serverErrors} peak=${JSON.stringify(peak)}`);

    // The point is graceful degradation: after the burst the stack is still UP,
    // and no requests were silently lost (each call returned SOME status).
    const health = await request.get(`${BASE}/actuator/health`);
    expect(health.ok()).toBe(true);
    expect(results.length).toBe(N);
    expect(results.every((r) => r.status !== 0), 'transport-level failures (dropped connections)').toBe(true);
  });
});
