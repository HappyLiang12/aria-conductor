import client from './client';

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

export async function deleteConversation(conversationId: string): Promise<void> {
  const res = await fetch(`/api/v1/aria/conversations/${encodeURIComponent(conversationId)}`, {
    method: 'DELETE',
  });
  if (!res.ok && res.status !== 404) {
    throw new Error(`Failed to delete conversation: ${res.statusText}`);
  }
}
