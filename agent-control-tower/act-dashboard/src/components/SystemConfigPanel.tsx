import { useState, useCallback } from 'react';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { listConfig, updateConfig, resetConfig, type SystemConfig } from '../api/systemConfig';

/* ── category metadata ── */

interface ConfigMeta {
  key: string;
  label: string;
  min: number;
  max: number;
  step: number;
  unit?: string;
}

interface CategoryDef {
  icon: string;
  title: string;
  keys: ConfigMeta[];
}

const CATEGORIES: CategoryDef[] = [
  {
    icon: '🤖',
    title: 'LLM Settings',
    keys: [
      { key: 'llm.request.timeout.seconds', label: 'LLM Request Timeout', min: 30, max: 3600, step: 1, unit: 's' },
      { key: 'aria.sse.timeout.ms', label: 'Aria SSE Timeout', min: 30000, max: 3600000, step: 1000, unit: 'ms' },
      { key: 'llm.max.tokens.ceiling', label: 'Max Tokens Ceiling', min: 1024, max: 131072, step: 1024 },
    ],
  },
  {
    icon: '🛡️',
    title: 'Circuit Breaker',
    keys: [
      { key: 'circuit.breaker.max.tokens.per.run', label: 'Max Tokens Per Run', min: 1000, max: 10_000_000, step: 1000 },
      { key: 'circuit.breaker.max.iterations', label: 'Max Iterations', min: 1, max: 500, step: 1 },
      { key: 'circuit.breaker.error.rate.threshold', label: 'Error Rate Threshold', min: 0, max: 1, step: 0.01 },
      { key: 'circuit.breaker.max.iteration.latency.ms', label: 'Max Iteration Latency', min: 10000, max: 3_600_000, step: 1000, unit: 'ms' },
    ],
  },
  {
    icon: '⚙️',
    title: 'ADK Runtime',
    keys: [
      { key: 'adk.health.check.interval.ms', label: 'Health Check Interval', min: 5000, max: 300_000, step: 1000, unit: 'ms' },
      { key: 'adk.shutdown.timeout.ms', label: 'Shutdown Timeout', min: 1000, max: 60_000, step: 1000, unit: 'ms' },
      { key: 'adk.max.restart.backoff.ms', label: 'Max Restart Backoff', min: 1000, max: 300_000, step: 1000, unit: 'ms' },
    ],
  },
  {
    icon: '📊',
    title: 'Report Generation',
    keys: [
      { key: 'report.generate.max.tokens', label: 'Generate Max Tokens', min: 4000, max: 131072, step: 1024 },
      { key: 'report.amend.max.tokens', label: 'Amend Max Tokens', min: 4000, max: 131072, step: 1024 },
    ],
  },
];

/* ── local toast ── */

interface LocalToast {
  id: number;
  message: string;
  kind: 'success' | 'error';
}

let toastSeq = 0;

/* ── component ── */

export function SystemConfigPanel() {
  const queryClient = useQueryClient();
  const [drafts, setDrafts] = useState<Record<string, string>>({});
  const [toasts, setToasts] = useState<LocalToast[]>([]);

  const pushToast = useCallback((message: string, kind: 'success' | 'error') => {
    const id = ++toastSeq;
    setToasts((prev) => [...prev.slice(-3), { id, message, kind }]);
    setTimeout(() => setToasts((prev) => prev.filter((t) => t.id !== id)), 3500);
  }, []);

  /* queries */
  const { data: configs, isLoading, error } = useQuery<SystemConfig[]>({
    queryKey: ['system-config'],
    queryFn: listConfig,
    refetchOnWindowFocus: false,
  });

  const configMap: Record<string, SystemConfig> = {};
  (configs ?? []).forEach((c) => {
    configMap[c.configKey] = c;
  });

  /* mutations */
  const saveMutation = useMutation({
    mutationFn: ({ key, value }: { key: string; value: string }) => updateConfig(key, value),
    onSuccess: (updated) => {
      queryClient.setQueryData<SystemConfig[]>(['system-config'], (old) =>
        old?.map((c) => (c.configKey === updated.configKey ? updated : c)) ?? []
      );
      setDrafts((prev) => {
        const next = { ...prev };
        delete next[updated.configKey];
        return next;
      });
      pushToast(`Saved ${updated.configKey}`, 'success');
    },
    onError: () => pushToast('Failed to save value', 'error'),
  });

  const resetMutation = useMutation({
    mutationFn: (key: string) => resetConfig(key),
    onSuccess: (updated) => {
      queryClient.setQueryData<SystemConfig[]>(['system-config'], (old) =>
        old?.map((c) => (c.configKey === updated.configKey ? updated : c)) ?? []
      );
      setDrafts((prev) => {
        const next = { ...prev };
        delete next[updated.configKey];
        return next;
      });
      pushToast(`Reset ${updated.configKey}`, 'success');
    },
    onError: () => pushToast('Failed to reset value', 'error'),
  });

  const currentVal = (key: string): string =>
    drafts[key] ?? configMap[key]?.configValue ?? '';

  const isDirty = (key: string): boolean =>
    key in drafts && drafts[key] !== configMap[key]?.configValue;

  const handleChange = (key: string, raw: string) => {
    setDrafts((prev) => ({ ...prev, [key]: raw }));
  };

  const handleSave = (key: string) => {
    const val = currentVal(key);
    if (val !== '') saveMutation.mutate({ key, value: val });
  };

  const handleReset = (key: string) => {
    resetMutation.mutate(key);
  };

  if (isLoading) {
    return (
      <div style={{ padding: 24, textAlign: 'center', color: 'var(--text-dim)' }}>
        Loading configuration…
      </div>
    );
  }

  if (error) {
    return (
      <div style={{ padding: 24, textAlign: 'center', color: '#ff8d99' }}>
        Failed to load configuration. Is the backend running?
      </div>
    );
  }

  return (
    <div style={{ position: 'relative' }}>
      <p style={{ color: 'var(--text-dim)', fontSize: 12.5, margin: '0 0 14px' }}>
        Runtime-configurable settings stored in the database. Changes take effect on the next
        request or restart — no rebuild required.
      </p>

      {CATEGORIES.map((cat) => (
        <div key={cat.title} style={{ marginBottom: 20 }}>
          <h4
            style={{
              fontSize: 12,
              fontWeight: 700,
              letterSpacing: '.6px',
              textTransform: 'uppercase',
              color: 'var(--text-dim)',
              margin: '0 0 8px',
              display: 'flex',
              alignItems: 'center',
              gap: 6,
            }}
          >
            <span>{cat.icon}</span> {cat.title}
          </h4>

          <div
            style={{
              display: 'flex',
              flexDirection: 'column',
              gap: 4,
            }}
          >
            {cat.keys.map((meta) => {
              const val = currentVal(meta.key);
              const dirty = isDirty(meta.key);
              const desc = configMap[meta.key]?.description ?? '';
              const numVal = Number(val);
              const outOfRange =
                val !== '' &&
                (!isNaN(numVal) && (numVal < meta.min || numVal > meta.max));

              return (
                <div
                  key={meta.key}
                  style={{
                    display: 'grid',
                    gridTemplateColumns: '1fr 140px 90px',
                    gap: 10,
                    alignItems: 'center',
                    padding: '8px 12px',
                    borderRadius: 8,
                    background: dirty
                      ? 'rgba(91,140,255,.06)'
                      : 'rgba(255,255,255,.02)',
                    border: `1px solid ${
                      outOfRange
                        ? 'rgba(255,107,122,.5)'
                        : dirty
                        ? 'rgba(91,140,255,.35)'
                        : 'var(--line)'
                    }`,
                    transition: 'border-color .2s, background .2s',
                  }}
                >
                  {/* label + description */}
                  <div>
                    <div style={{ fontSize: 12.5, fontWeight: 600 }}>
                      {meta.label}
                      {dirty && (
                        <span
                          style={{
                            marginLeft: 6,
                            fontSize: 9,
                            color: 'var(--brand)',
                            fontWeight: 700,
                            letterSpacing: '.5px',
                            textTransform: 'uppercase',
                          }}
                        >
                          modified
                        </span>
                      )}
                    </div>
                    <div
                      style={{
                        fontSize: 10.5,
                        color: 'var(--text-dim)',
                        marginTop: 1,
                      }}
                    >
                      {desc}
                      {meta.unit && (
                        <span style={{ marginLeft: 4 }}>
                          Range: {meta.min}–{meta.max} {meta.unit}
                        </span>
                      )}
                      {!meta.unit && (
                        <span style={{ marginLeft: 4 }}>
                          Range: {meta.min}–{meta.max}
                        </span>
                      )}
                    </div>
                  </div>

                  {/* input */}
                  <div style={{ display: 'flex', alignItems: 'center', gap: 4 }}>
                    <input
                      type="number"
                      min={meta.min}
                      max={meta.max}
                      step={meta.step}
                      value={val}
                      onChange={(e) => handleChange(meta.key, e.target.value)}
                      onKeyDown={(e) => {
                        if (e.key === 'Enter') handleSave(meta.key);
                      }}
                      style={{
                        width: '100%',
                        padding: '6px 8px',
                        borderRadius: 7,
                        background: 'rgba(0,0,0,.25)',
                        color: 'var(--text)',
                        border: '1px solid var(--line-2)',
                        font: 'inherit',
                        fontSize: 12,
                        outline: 'none',
                      }}
                    />
                    {meta.unit && (
                      <span
                        style={{
                          fontSize: 10,
                          color: 'var(--text-mute)',
                          whiteSpace: 'nowrap',
                        }}
                      >
                        {meta.unit}
                      </span>
                    )}
                  </div>

                  {/* actions */}
                  <div style={{ display: 'flex', gap: 4, justifyContent: 'flex-end' }}>
                    <button
                      type="button"
                      className="btn"
                      title="Save"
                      disabled={!dirty || saveMutation.isPending}
                      onClick={() => handleSave(meta.key)}
                      style={{
                        padding: '5px 8px',
                        fontSize: 12,
                        opacity: dirty ? 1 : 0.35,
                      }}
                    >
                      💾
                    </button>
                    <button
                      type="button"
                      className="btn"
                      title="Reset to default"
                      disabled={resetMutation.isPending}
                      onClick={() => handleReset(meta.key)}
                      style={{ padding: '5px 8px', fontSize: 12 }}
                    >
                      ↩
                    </button>
                  </div>
                </div>
              );
            })}
          </div>
        </div>
      ))}

      {/* local toast stack */}
      {toasts.length > 0 && (
        <div
          style={{
            position: 'absolute',
            bottom: 8,
            left: '50%',
            transform: 'translateX(-50%)',
            display: 'flex',
            flexDirection: 'column',
            gap: 6,
            zIndex: 10,
          }}
        >
          {toasts.map((t) => (
            <div
              key={t.id}
              style={{
                padding: '8px 14px',
                borderRadius: 8,
                background:
                  t.kind === 'success'
                    ? 'linear-gradient(180deg, #1a2342, #131b32)'
                    : 'linear-gradient(180deg, #2a1520, #1a0e14)',
                border: `1px solid ${
                  t.kind === 'success'
                    ? 'rgba(91,140,255,.45)'
                    : 'rgba(255,107,122,.45)'
                }`,
                fontSize: 12,
                color: 'var(--text)',
                whiteSpace: 'nowrap',
                animation: 'toastIn .3s ease',
              }}
            >
              {t.kind === 'success' ? '✓' : '✗'} {t.message}
            </div>
          ))}
        </div>
      )}
    </div>
  );
}

export default SystemConfigPanel;
