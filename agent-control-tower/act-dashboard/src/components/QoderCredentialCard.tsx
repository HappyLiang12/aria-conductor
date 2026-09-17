import { useState } from 'react';
import type { CSSProperties, FormEvent, ReactNode } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  getQoderCredential,
  saveQoderCredential,
  deleteQoderCredential,
  testQoderCredential,
} from '../api/qoderCredential';
import { getAdkProviderHealth } from '../api/adk';
import { ConfirmDialog } from './ConfirmDialog';
import type { QoderCredentialTestResult } from '../types';

/**
 * Operator surface for the qoder provider (task B9).
 *
 * Renders three SEPARATE states so a configured PAT never reads as "ready":
 *  1. Credential configuration — the B8 masked status (configured + mask +
 *     updatedAt + model), the unconfigured default, or the store error (503
 *     KEY_NOT_CONFIGURED and friends).
 *  2. Sandbox service — the existing provider health API for the qoder
 *     provider (its service-level probe), including "not registered" when the
 *     backend does not expose qoder at all.
 *  3. Bridge readiness — derived from (1) and (2): ready only when the sandbox
 *     service is reachable AND a credential is configured; otherwise blocked
 *     with an explicit reason. A configured PAT alone is not healthy.
 *
 * The PAT is written one way only: it lives in the PUT body and in the password
 * editor's local state, and is dropped from that state as soon as the save
 * attempt settles (success or failure); nothing else in this component renders
 * or persists it.
 */
// Design-system palette (styles/index.css :root). The probe text below already
// uses these variables, so the badges must not reintroduce a second green/red.
const GREEN = 'var(--green)';
const RED = 'var(--red)';
const AMBER = 'var(--amber)';
// No design-system token exists for the neutral "unknown" grey; the previous
// literal is kept so that badge's appearance does not change.
const GREY = '#8b93a7';

const muted: CSSProperties = { color: 'var(--text-mute)', fontSize: 11.5 };

function StateBadge({ label, color }: { label: string; color: string }) {
  return (
    <span
      className="status-badge status-badge-md"
      style={{
        // color-mix replaces the `color + '22'` hex-alpha trick, which cannot
        // work with a var() token; 13% is the same alpha as 0x22.
        backgroundColor: `color-mix(in srgb, ${color} 13%, transparent)`,
        color,
        borderColor: color,
      }}
    >
      {label}
    </span>
  );
}

function StatusRow({ label, children }: { label: string; children: ReactNode }) {
  return (
    <div style={{ display: 'flex', alignItems: 'center', gap: 10, padding: '6px 0' }}>
      <span style={{ width: 130, flexShrink: 0, ...muted, textTransform: 'uppercase', letterSpacing: '.7px', fontWeight: 600 }}>
        {label}
      </span>
      <div style={{ display: 'flex', alignItems: 'center', gap: 8, flexWrap: 'wrap' }}>{children}</div>
    </div>
  );
}

interface ApiErrorLike {
  response?: { status?: number; data?: { message?: string; code?: string } };
}

function httpStatus(err: unknown): number | undefined {
  return (err as ApiErrorLike)?.response?.status;
}

function apiMessage(err: unknown): string | undefined {
  return (err as ApiErrorLike)?.response?.data?.message;
}

export function QoderCredentialCard() {
  const queryClient = useQueryClient();
  const [pat, setPat] = useState('');
  const [editing, setEditing] = useState(false);
  const [formError, setFormError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [probe, setProbe] = useState<QoderCredentialTestResult | null>(null);
  const [confirmingRemove, setConfirmingRemove] = useState(false);

  // A failing credential store is deterministic (503 KEY_NOT_CONFIGURED, or an
  // unreachable store), so retries only delay `Unavailable` by ~7 s (client
  // default: 3 retries at 1 s / 2 s / 4 s). The error must render immediately.
  const statusQuery = useQuery({
    queryKey: ['qoder-credential'],
    queryFn: getQoderCredential,
    retry: false,
  });

  // Same cache key the ProvidersPage inventory table uses for registered
  // providers: when qoder is registered both surfaces share one probe result.
  // query-core takes the retry policy from the observer that triggers the fetch
  // (`queryObserver.js` → `query.js`), so `retry:false` must be set on EVERY
  // observer of this key — here and in `ProvidersPage.tsx` — or the immediate
  // 404 classification becomes render-order dependent.
  const healthQuery = useQuery({
    queryKey: ['adk-provider-health', 'qoder'],
    queryFn: () => getAdkProviderHealth('qoder'),
    retry: false,
  });

  const saveMutation = useMutation({
    mutationFn: (value: string) => saveQoderCredential(value),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['qoder-credential'] });
      // Drop the submitted secret from the mutation state as well: it is never
      // rendered again and should not sit in the cache.
      saveMutation.reset();
      setPat('');
      setEditing(false);
      setFormError(null);
      setNotice('Credential saved. The PAT is stored encrypted and is never displayed again.');
    },
    onError: (err: unknown) => {
      setNotice(null);
      // The typed secret is dropped here too: after a failed attempt the editor
      // must not keep the token in the DOM. Retrying means retyping the PAT.
      setPat('');
      setFormError(apiMessage(err) ?? 'Saving the credential failed. Retry or check the backend logs.');
    },
  });

  const removeMutation = useMutation({
    mutationFn: deleteQoderCredential,
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['qoder-credential'] });
      setProbe(null);
      setNotice('Credential removed. New qoder runs cannot start until a PAT is saved again.');
    },
    onError: (err: unknown) => {
      setNotice(null);
      setFormError(apiMessage(err) ?? 'Removing the credential failed. Retry.');
    },
  });

  const testMutation = useMutation({
    mutationFn: testQoderCredential,
    onSuccess: (result) => {
      setNotice(null);
      setFormError(null);
      setProbe(result);
    },
    onError: (err: unknown) => {
      setNotice(null);
      setProbe(null);
      setFormError(apiMessage(err) ?? 'The credential probe could not run.');
    },
  });

  const status = statusQuery.data;
  const configured = status?.configured ?? false;
  const statusUnavailable = statusQuery.isError;

  // Sandbox-service state from the existing provider health API.
  const serviceState: 'loading' | 'healthy' | 'unhealthy' | 'not-registered' | 'unknown' =
    healthQuery.isPending
      ? 'loading'
      : healthQuery.isError
        ? httpStatus(healthQuery.error) === 404
          ? 'not-registered'
          : 'unknown'
        : healthQuery.data?.healthy
          ? 'healthy'
          : 'unhealthy';

  // Bridge readiness is derived: a configured PAT alone is not healthy. When a
  // prerequisite state is unknown (store unreachable, provider unregistered),
  // readiness stays unknown rather than claiming a blocked reason it cannot see.
  const readiness:
    | 'loading'
    | 'ready'
    | 'blocked-service'
    | 'blocked-credential'
    | 'unknown' =
    statusQuery.isPending || serviceState === 'loading'
      ? 'loading'
      : statusQuery.isError || serviceState === 'not-registered' || serviceState === 'unknown'
        ? 'unknown'
        : serviceState === 'unhealthy'
          ? 'blocked-service'
          : !configured
            ? 'blocked-credential'
            : 'ready';

  const handleSubmit = (e: FormEvent) => {
    e.preventDefault();
    const value = pat.trim();
    if (!value) return;
    saveMutation.mutate(value);
  };

  const handleRemoveConfirm = () => {
    setConfirmingRemove(false);
    removeMutation.mutate();
  };

  // `configured` defaults to false while the status GET is in flight, so the
  // editor waits for the store's answer: no first-time PAT form flashes for an
  // operator who already has a credential. The error state keeps its own Retry
  // affordance and never shows the editor.
  const showInput = statusQuery.isSuccess && (!configured || editing);

  return (
    <div className="card" style={{ marginTop: 24 }} data-testid="qoder-credential-card">
      <h3 className="form-title">Qoder Provider</h3>
      <p style={{ ...muted, marginTop: 0 }}>
        Runtime credential and service status for the qoder ADK provider. The PAT is encrypted at
        rest, sent one way, and never displayed again.
      </p>

      <div style={{ marginTop: 8 }}>
        <StatusRow label="Credential">
          {statusQuery.isPending ? (
            <span style={muted}>Checking…</span>
          ) : statusUnavailable ? (
            <StateBadge label="Unavailable" color={RED} />
          ) : configured ? (
            <>
              <StateBadge label="Configured" color={GREEN} />
              <span className="cell-mono">{status?.patMasked}</span>
              {status?.updatedAt && (
                <span style={muted}>updated {new Date(status.updatedAt).toLocaleString()}</span>
              )}
              {status?.model && <span style={muted}>model {status.model}</span>}
            </>
          ) : (
            <>
              <StateBadge label="Not configured" color={AMBER} />
              <span style={muted}>Save a Qoder personal access token (PAT) to enable qoder runs.</span>
            </>
          )}
        </StatusRow>

        <StatusRow label="Sandbox service">
          {serviceState === 'loading' && <span style={muted}>Checking…</span>}
          {serviceState === 'healthy' && <StateBadge label="Healthy" color={GREEN} />}
          {serviceState === 'unhealthy' && <StateBadge label="Unreachable" color={RED} />}
          {serviceState === 'not-registered' && (
            <>
              <StateBadge label="Not registered" color={GREY} />
              <span style={muted}>The qoder provider is not registered in this backend build.</span>
            </>
          )}
          {serviceState === 'unknown' && (
            <>
              <StateBadge label="Unknown" color={GREY} />
              <span style={muted}>The sandbox service state could not be determined.</span>
            </>
          )}
        </StatusRow>

        <StatusRow label="Bridge readiness">
          {readiness === 'loading' && <span style={muted}>Checking…</span>}
          {readiness === 'ready' && (
            <>
              <StateBadge label="Ready" color={GREEN} />
              <span style={muted}>credential configured and sandbox service reachable</span>
            </>
          )}
          {readiness === 'blocked-service' && (
            <>
              <StateBadge label="Blocked" color={RED} />
              <span style={muted}>sandbox service unreachable</span>
            </>
          )}
          {readiness === 'blocked-credential' && (
            <>
              <StateBadge label="Blocked" color={AMBER} />
              <span style={muted}>no credential saved</span>
            </>
          )}
          {readiness === 'unknown' && (
            <>
              <StateBadge label="Unknown" color={GREY} />
              <span style={muted}>readiness cannot be determined</span>
            </>
          )}
        </StatusRow>
      </div>

      <p style={{ ...muted, margin: '4px 0 0' }}>
        A configured PAT alone is not sufficient: the sandbox service must be reachable before
        qoder runs can start.
      </p>

      {statusUnavailable && (
        <div className="error-state" style={{ marginTop: 12, textAlign: 'left' }}>
          Credential status unavailable —{' '}
          {apiMessage(statusQuery.error) ?? 'the credential store did not answer.'}
          <div style={{ marginTop: 10 }}>
            <button
              type="button"
              className="btn"
              onClick={() => {
                void statusQuery.refetch();
                // The sandbox-service row shares its cache key with the
                // ProvidersPage inventory table: a stale `Not registered` /
                // `Unknown` must not survive a manual retry.
                void queryClient.invalidateQueries({ queryKey: ['adk-provider-health', 'qoder'] });
              }}
            >
              Retry
            </button>
          </div>
        </div>
      )}

      {showInput && (
        <form onSubmit={handleSubmit} style={{ marginTop: 12 }}>
          <label
            htmlFor="qoder-pat"
            style={{ display: 'block', ...muted, textTransform: 'uppercase', letterSpacing: '.7px', fontWeight: 600, marginBottom: 6 }}
          >
            {configured ? 'Replace personal access token (PAT)' : 'Personal access token (PAT)'}
          </label>
          <input
            id="qoder-pat"
            type="password"
            value={pat}
            autoComplete="off"
            spellCheck={false}
            placeholder="qcp_…"
            onChange={(e) => setPat(e.target.value)}
            style={{
              width: '100%',
              maxWidth: 360,
              padding: '9px 12px',
              borderRadius: 8,
              fontSize: 12.5,
              background: 'rgba(255,255,255,.03)',
              border: '1px solid var(--line-2)',
              color: 'var(--text)',
            }}
          />
          <div className="form-actions">
            <button type="submit" className="btn primary" disabled={!pat.trim() || saveMutation.isPending}>
              {saveMutation.isPending ? 'Saving…' : 'Save'}
            </button>
            {editing && (
              <button
                type="button"
                className="btn"
                onClick={() => {
                  setEditing(false);
                  setPat('');
                  setFormError(null);
                }}
              >
                Cancel
              </button>
            )}
          </div>
        </form>
      )}

      {!statusUnavailable && configured && !editing && (
        <div className="form-actions">
          <button
            type="button"
            className="btn"
            onClick={() => {
              setEditing(true);
              setPat('');
              setFormError(null);
              setNotice(null);
            }}
          >
            Replace
          </button>
          <button
            type="button"
            className="btn"
            onClick={() => testMutation.mutate()}
            disabled={testMutation.isPending}
          >
            {testMutation.isPending ? 'Testing…' : 'Test credential'}
          </button>
          <button
            type="button"
            className="btn danger"
            onClick={() => setConfirmingRemove(true)}
            disabled={removeMutation.isPending}
          >
            {removeMutation.isPending ? 'Removing…' : 'Remove…'}
          </button>
        </div>
      )}

      {probe && (
        <div
          style={{ marginTop: 10, fontSize: 12, color: probe.success ? 'var(--green)' : 'var(--red)' }}
          role="status"
        >
          {probe.success
            ? `Probe passed — the stored credential is configured, decrypts and is non-blank (model ${probe.model}).`
            : probe.message ?? `Probe failed (${probe.reason ?? 'unknown reason'}).`}
          {probe.success && <span style={{ ...muted, display: 'block' }}>{probe.costNote}</span>}
        </div>
      )}

      {formError && (
        <div style={{ marginTop: 10, color: 'var(--red)', fontSize: 11.5 }}>{formError}</div>
      )}
      {notice && <div style={{ marginTop: 10, ...muted }}>{notice}</div>}

      <ConfirmDialog
        open={confirmingRemove}
        title="Remove Qoder credential?"
        message={
          <>
            Remove the stored Qoder PAT? New qoder runs cannot start until a credential is saved
            again. Already-running sessions are not cancelled by this action.
          </>
        }
        danger
        onConfirm={handleRemoveConfirm}
        onCancel={() => setConfirmingRemove(false)}
      />
    </div>
  );
}
