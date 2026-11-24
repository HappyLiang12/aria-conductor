import type { ChatModelAdapter, ChatModelRunResult } from '@assistant-ui/react';
import { streamMessage, type StreamError, type StreamErrorDetail } from '@/api/aria';

type History = Array<{ role: string; content: string }>;

interface Part {
  type: 'text' | 'tool-call';
  text?: string;
  toolCallId?: string;
  toolName?: string;
  args?: unknown;
  argsText?: string;
  result?: unknown;
  isError?: boolean;
}

export interface AriaAdapter extends ChatModelAdapter {
  /** Abort the in-flight SSE request (the page's stop control). */
  cancel: () => void;
}

/**
 * Runs one Aria turn on assistant-ui's `ChatModelAdapter` contract: the turn's
 * SSE frames become cumulative message content, one chunk per event, and the
 * adapter owns the single `AbortController` behind both `cancel()` and the
 * runtime's abort signal. No second request is opened here.
 */
export function createAriaAdapter(options: {
  conversationId: string;
  getHistory: () => History;
  stream?: typeof streamMessage;
}): AriaAdapter {
  const stream = options.stream ?? streamMessage;
  let current: AbortController | null = null;
  return {
    cancel() { current?.abort(); },
    async *run({ messages, abortSignal }) {
      const controller = new AbortController();
      current = controller;
      // The runtime can hand over a signal that is already aborted; the request
      // must not open at all in that case.
      if (abortSignal.aborted) controller.abort();
      const forwardAbort = () => controller.abort();
      abortSignal.addEventListener('abort', forwardAbort);

      const lastUser = [...messages].reverse().find((m) => m.role === 'user');
      const text = lastUser?.content.filter((p) => p.type === 'text').map((p) => p.text).join('\n') ?? '';
      let output = '';
      const tools = new Map<string, Part>();
      let finished = false;
      let failure: StreamError | null = null;
      let wake: (() => void) | null = null;
      // Every callback that may change the snapshot bumps `version`; `emitted`
      // records which version the last yielded chunk was built from. Events
      // dispatched while the generator is suspended at `yield` (a whole read's
      // frames, including a terminal one) are therefore seen on the next pull.
      let version = 0;
      let emitted = 0;
      const snapshot = (): Part[] => [
        ...[...tools.values()].map((p) => ({ ...p })),
        ...(output ? [{ type: 'text' as const, text: output }] : []),
      ];
      const notify = () => { version += 1; const w = wake; wake = null; w?.(); };

      void stream(options.conversationId, text, options.getHistory(), {
        onToolCall: (name, args, toolCallId) => {
          const id = toolCallId ?? `call-${tools.size}`;
          tools.set(id, { type: 'tool-call', toolCallId: id, toolName: name, argsText: args ?? '', args: safeJson(args) });
          notify();
        },
        onToolResult: (name, result, ok, toolCallId) => {
          const id = toolCallId ?? [...tools].find(([, p]) => p.toolName === name)?.[0];
          if (!id) return;
          tools.set(id, {
            ...(tools.get(id) ?? { type: 'tool-call', toolCallId: id, toolName: name, args: {}, argsText: '' }),
            result: safeJson(result),
            isError: !ok,
          });
          notify();
        },
        onApprovalRequired: (ask) => {
          tools.set(`approval-${ask.approvalId}`, { type: 'tool-call', toolCallId: `approval-${ask.approvalId}`, toolName: '__approval', args: ask, argsText: '' });
          notify();
        },
        onWaitingInput: (ask) => {
          tools.set(`question-${ask.approvalId}`, { type: 'tool-call', toolCallId: `question-${ask.approvalId}`, toolName: '__question', args: ask, argsText: '' });
          notify();
        },
        onMemoryProposal: (proposal) => {
          tools.set(`memory-${proposal.id}`, { type: 'tool-call', toolCallId: `memory-${proposal.id}`, toolName: '__memory', args: proposal, argsText: '' });
          notify();
        },
        onMessage: (content) => { output = content; notify(); },
        onDone: () => { finished = true; notify(); },
        onError: (error) => { failure = error; finished = true; notify(); },
      }, controller.signal)
        .catch((error: unknown) => {
          if ((error as DOMException)?.name !== 'AbortError') {
            failure = error instanceof Error ? error.message : String(error);
          }
        })
        .finally(() => {
          // `streamMessage` returns (instead of throwing) when the request is
          // aborted, so a settled promise with no terminal frame must release
          // the loop too — otherwise `cancel()` leaves the run suspended.
          if (!finished) { finished = true; notify(); }
        });

      try {
        while (true) {
          if (version === emitted && !finished) {
            // Nothing new yet: park until a callback bumps the version.
            await new Promise<void>((resolve) => { wake = resolve; });
            continue;
          }
          if (version !== emitted) {
            // Consume the version even when there is nothing to show, so an
            // empty snapshot cannot spin the loop.
            emitted = version;
            // The parts mirror the wire frames; assistant-ui declares its content
            // union with literal discriminants, so the projection is asserted once.
            const content = snapshot() as unknown as NonNullable<ChatModelRunResult['content']>;
            if (content.length > 0) {
              // Re-enter the loop after the yield: frames dispatched while the
              // consumer was holding this chunk (a tool result, the answer, the
              // terminal frame) are emitted too, finished or not.
              yield { content };
            }
            continue;
          }
          // Version consumed and the run is over: nothing can change any more.
          break;
        }
      } finally {
        abortSignal.removeEventListener('abort', forwardAbort);
        if (current === controller) current = null;
      }
      if (failure) throw normalizedError(failure);
    },
  };
}

/**
 * The runtime only understands `Error`, so the enriched terminal payload
 * (`{message, turnFailed, runId, reason}`) must become one before it is thrown:
 * passing the object through renders `[object Object]` in the thread. The copy
 * keeps the operator's run id and the backend's own reason (legacy `errorCopy`,
 * `MainChatPage` before the assistant-ui rebuild).
 */
export function normalizedError(failure: unknown): Error {
  if (failure instanceof Error) return failure;
  if (typeof failure === 'string') return new Error(failure);
  const detail = (failure ?? {}) as StreamErrorDetail;
  const run = detail.runId ? ` (Run ${detail.runId})` : '';
  const human = detail.message?.trim() ?? '';
  const reason = detail.reason?.trim() ?? '';
  const summary = reason !== '' && reason !== human ? [human, reason].filter(Boolean).join(' — ') : human;
  if (detail.turnFailed === true) {
    return new Error(`This turn failed${run}: ${summary || 'the backend reported no reason'}`);
  }
  return new Error(`${summary || 'The stream ended with an error.'}${run}`);
}

function safeJson(value: string | undefined): unknown {
  if (value === undefined) return undefined;
  try { return JSON.parse(value); } catch { return value; }
}
