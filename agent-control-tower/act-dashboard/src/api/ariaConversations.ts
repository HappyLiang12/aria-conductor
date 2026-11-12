import client from './client';
import { applyOperatorHeaders } from './operatorSession';

export interface ConversationSummary {
  conversationId: string;
  lastMessageAt: string;
  runCount: number;
}

export interface TimelineEntry {
  role: string;
  content: string;
  timestamp: string;
  runId: string;
  /** True when this entry marks a failed turn; the entry is synthetic, not a trajectory row. */
  error?: boolean;
  /** Prompt that re-runs the failed turn; absent unless `error`. */
  retryPrompt?: string;
  /**
   * The `ToolCallStatus` name of the tool call a `role: 'tool'` row belongs to —
   * `COMPLETED`, `FAILED`, `DENIED`, `PENDING` or `EXECUTING`. Absent when the backend
   * cannot say (an older backend, or a row with no matching tool call); absent is not
   * a success, it is "the timeline does not say otherwise".
   */
  toolStatus?: string;
  /** The raw core tool-call id of the row, so a result can be attributed to its own call. */
  toolCallId?: string;
}

/**
 * `GET /api/v1/aria/conversations/latest`: the conversation this operator should open, or
 * `null` when none exists yet.
 *
 * A 404 means "no conversation exists yet" — the server's correct REST answer for the
 * first load against a fresh database — so it is read as the same empty answer as the 204
 * it later sends. Left throwing, a brand-new operator's first load logged a 404 plus
 * `net::ERR_ABORTED` in the console for a normal state. This is the convention the DELETE
 * below already uses (`:89`); every other non-ok status still throws, because those are
 * real faults and the panel says the history was not loaded.
 */
export async function getLatestConversation(): Promise<ConversationSummary | null> {
  const res = await fetch('/api/v1/aria/conversations/latest');
  if (res.status === 204 || res.status === 404) return null;
  if (!res.ok) throw new Error(`Failed to fetch latest conversation: ${res.statusText}`);
  return res.json();
}

export async function getConversationTimeline(conversationId: string): Promise<TimelineEntry[]> {
  const res = await fetch(`/api/v1/aria/conversations/${encodeURIComponent(conversationId)}`);
  if (!res.ok) throw new Error(`Failed to fetch conversation timeline: ${res.statusText}`);
  return res.json();
}

/** The HTTP status an axios error answered with, or undefined when it did not answer. */
function responseStatus(error: unknown): number | undefined {
  return (error as { response?: { status?: number } } | undefined)?.response?.status;
}

/**
 * Upper bound on the Clear DELETE (T49 N-T48-4). The call synchronously drives
 * `RunService.cancelRun`, whose verified stop waits on the core, so on a slow or
 * wedged backend it can hang for as long as the server does. Unbounded, the panel's
 * `Clearing…` state would be indistinguishable from a dead button, which is the worst
 * possible outcome for the control an operator uses to escape a parked turn. 20 s
 * leaves room for a legitimate verified stop and still answers.
 */
export const CLEAR_CONVERSATION_TIMEOUT_MS = 20_000;

/**
 * `DELETE /api/v1/aria/conversations/{id}`: cancel this conversation's runs, then purge its
 * tool calls, trajectories and audit events.
 *
 * Through the shared `client`, not a bare `fetch` (T48 Item 1): the route resolves operator
 * authority for a MUTATION (`AriaConversationController.java:193-203`), and a browser that
 * holds the operator session cookie has its request validated against `X-CSRF-Token` — which
 * only the axios client's defaults carry (`operatorSession.ts:99-106`). `applyOperatorHeaders()`
 * runs at call time so a reload (defaults empty, session in localStorage) cannot send the call
 * without the token. The client also adds `X-Correlation-ID`, which the raw fetch never did.
 *
 * A 404 stays a success: the conversation is already gone, which is what the caller asked for.
 */
export async function deleteConversation(conversationId: string): Promise<void> {
  applyOperatorHeaders();
  try {
    await client.delete(`/api/v1/aria/conversations/${encodeURIComponent(conversationId)}`, {
      timeout: CLEAR_CONVERSATION_TIMEOUT_MS,
    });
  } catch (error) {
    if (responseStatus(error) === 404) return;
    throw error;
  }
}

/**
 * One-click synthesis (Feature B2): composes the aggregated prompt for the
 * latest dispatch batch of the conversation. No body — an optional
 * `dispatchedByRunId` targets a specific batch, the default is the latest one.
 *
 * Through the shared `client` like the DELETE above (T48 Item 1): it was the last mutation
 * outside the one transport, so it carried no `X-CSRF-Token` and no `X-Correlation-ID`. The
 * route answers a worker or anonymous caller today (`AriaConversationController.java:245-261`
 * resolves no authority), so this cannot change an outcome — it removes the class of call that
 * breaks the day the route is gated, instead of re-deriving the header at that point.
 */
export async function composeSynthesis(conversationId: string): Promise<{ prompt: string }> {
  applyOperatorHeaders();
  const { data } = await client.post<{ prompt: string }>(
    `/api/v1/aria/conversations/${encodeURIComponent(conversationId)}/synthesize`,
  );
  return data;
}
