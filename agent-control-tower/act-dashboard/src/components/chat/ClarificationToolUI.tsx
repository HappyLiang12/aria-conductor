import { useRef, useState } from 'react';
import type { ToolCallMessagePartComponent } from '@assistant-ui/react';
import { answerAsk } from '@/api/approvals';
import { Button } from '@/components/ui/button';
import { Textarea } from '@/components/ui/textarea';

/**
 * Arguments of the reserved `__question` tool call: the run is parked in
 * WAITING_INPUT on the ask `approvalId`, and the answer resumes it.
 */
export interface ClarificationArgs {
  approvalId: string;
  question?: string;
  contextMd?: string;
}

export const ClarificationToolUI: ToolCallMessagePartComponent<ClarificationArgs> = ({ args }) => {
  const [answer, setAnswer] = useState('');
  const [state, setState] = useState<'pending' | 'submitting' | 'answered' | 'error'>('pending');
  const [error, setError] = useState<string | null>(null);
  /**
   * Synchronous in-flight guard: two clicks can land before React re-renders,
   * and the backend rejects the second overlapping answer with a 409 — which
   * would replace the landed receipt with an error card. The ref settles that
   * race; `state === 'submitting'` keeps Send disabled for the user.
   */
  const inFlight = useRef(false);

  const submit = async () => {
    const text = answer.trim();
    if (text === '' || inFlight.current) return;
    inFlight.current = true;
    setState('submitting');
    try {
      await answerAsk(args.approvalId, { approved: true, answer: text });
      setError(null);
      setState('answered');
    } catch (failure) {
      setError(failure instanceof Error ? failure.message : String(failure));
      setState('error');
    } finally {
      inFlight.current = false;
    }
  };

  if (state === 'answered') {
    return <p className="text-sm text-muted-foreground">Answered · {answer.trim()}</p>;
  }

  return (
    <div className="my-2 rounded-lg border border-border bg-card p-4">
      <p className="font-medium">{args.question ?? 'The run needs an answer to continue.'}</p>
      {args.contextMd && (
        <p className="mt-1 text-xs whitespace-pre-wrap text-muted-foreground">{args.contextMd}</p>
      )}
      {error && (
        <p role="alert" className="mt-2 text-sm text-destructive">
          {error}
        </p>
      )}
      <Textarea
        className="mt-3"
        rows={3}
        aria-label="Your answer"
        value={answer}
        disabled={state === 'submitting'}
        onChange={(event) => setAnswer(event.target.value)}
      />
      <div className="mt-3">
        <Button
          size="sm"
          disabled={state === 'submitting' || answer.trim() === ''}
          onClick={() => void submit()}
        >
          Send
        </Button>
      </div>
    </div>
  );
};
