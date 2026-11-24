import { useState } from 'react';
import type { ToolCallMessagePartComponent } from '@assistant-ui/react';
import { decideMemoryProposal, type MemoryProposalDecision } from '@/api/memory';
import { Button } from '@/components/ui/button';
import { Badge } from '@/components/ui/badge';

/**
 * Arguments of the reserved `__memory` tool call: a governed memory proposal
 * awaiting an operator decision. The id is `args.id` — the adapter maps
 * `MemoryProposalSignal.id` verbatim.
 */
export interface MemoryProposalArgs {
  id: string;
  subjectType?: string;
  factKey?: string;
  valueText?: string;
  sensitivity?: string;
}

export const MemoryProposalToolUI: ToolCallMessagePartComponent<MemoryProposalArgs> = ({ args }) => {
  const [state, setState] = useState<'pending' | MemoryProposalDecision | 'error'>('pending');
  const [error, setError] = useState<string | null>(null);

  async function decide(decision: MemoryProposalDecision) {
    try {
      await decideMemoryProposal(args.id, decision);
      setError(null);
      setState(decision);
    } catch (failure) {
      setError(failure instanceof Error ? failure.message : String(failure));
      setState('error');
    }
  }

  if (state === 'APPROVE' || state === 'REJECT') {
    return (
      <p className="text-sm text-muted-foreground">
        {state === 'APPROVE' ? 'Remembered' : 'Rejected'} · {args.valueText ?? args.factKey ?? args.id}
      </p>
    );
  }

  return (
    <div className="my-2 rounded-lg border border-border bg-card p-4">
      <div className="flex flex-wrap items-center gap-2">
        <Badge variant="secondary">MEMORY</Badge>
        <span className="font-medium">Remember this?</span>
        {args.subjectType && <Badge variant="outline">{args.subjectType}</Badge>}
        {args.sensitivity && <Badge variant="outline">{args.sensitivity}</Badge>}
      </div>
      {args.factKey && <p className="mt-1 text-xs text-muted-foreground">{args.factKey}</p>}
      {args.valueText && <p className="mt-1 text-sm whitespace-pre-wrap">{args.valueText}</p>}
      {error && (
        <p role="alert" className="mt-2 text-sm text-destructive">
          {error}
        </p>
      )}
      <div className="mt-3 flex gap-2">
        <Button size="sm" onClick={() => void decide('APPROVE')}>
          Approve
        </Button>
        <Button size="sm" variant="outline" onClick={() => void decide('REJECT')}>
          Reject
        </Button>
      </div>
    </div>
  );
};
