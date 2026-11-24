import type { ReactNode } from 'react';
import type { ChatModelAdapter, ThreadMessageLike } from '@assistant-ui/react';
import { AssistantRuntimeProvider, AuiConfig, Tools, useLocalRuntime } from '@assistant-ui/react';
import { decisionToolkit } from '../components/chat/decisionToolkit';

/**
 * The runtime boundary of the Aria page (Task 7).
 *
 * The page owns the adapter, so its stop control aborts the very SSE request the
 * adapter opened; this component only mounts that adapter as a local runtime and
 * registers the decision tool UIs once for the whole thread.
 *
 * `initialMessages` seeds the conversation the page loaded (assistant-ui reads
 * it at mount only), so the page remounts this provider when the conversation or
 * its persisted history changes.
 */
export function AriaRuntimeProvider({ adapter, initialMessages, children }: {
  adapter: ChatModelAdapter;
  initialMessages?: readonly ThreadMessageLike[];
  children: ReactNode;
}) {
  const runtime = useLocalRuntime(adapter, { initialMessages });
  const config = AuiConfig({ tools: Tools({ toolkit: decisionToolkit }) });
  return (
    <AssistantRuntimeProvider runtime={runtime} config={config}>
      {children}
    </AssistantRuntimeProvider>
  );
}

export default AriaRuntimeProvider;
