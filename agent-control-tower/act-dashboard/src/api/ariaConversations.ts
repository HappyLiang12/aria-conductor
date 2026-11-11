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

export async function getLatestConversation(): Promise<ConversationSummary | null> {
  const res = await fetch('/api/v1/aria/conversations/latest');
  if (res.status === 204) return null;
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
    await client.delete(`/api/v1/aria/conversations/${encodeURIComponent(conversationId)}`);
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
