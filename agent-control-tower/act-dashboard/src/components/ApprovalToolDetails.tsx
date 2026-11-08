import { formatApprovalArguments } from '../utils/approvalArguments';
import type { Approval } from '../types';

/**
 * F7 (SDD hardening): the tool correlation an approval may carry.
 *
 * The bridge/api lanes add nullable `toolName` + `arguments` to approval rows.
 * Both are optional on purpose: an approval from an older backend (or one whose
 * ask carries no tool detail) renders nothing here, and the surrounding row
 * keeps its reason/content copy. Arguments are pretty-printed and redacted by
 * `formatApprovalArguments`, so a credential inside a tool parameter never
 * reaches the DOM.
 *
 * `includeToolName` is false on surfaces that already name the tool (the Review
 * queue's row title, the native-permission facts); arguments still render.
 */
export function ApprovalToolDetails({
  ask,
  includeToolName = true,
}: {
  ask: Approval;
  includeToolName?: boolean;
}) {
  const toolName = typeof ask.toolName === 'string' ? ask.toolName.trim() : '';
  const args = formatApprovalArguments(ask.arguments ?? null);
  if (!args && (!includeToolName || !toolName)) return null;

  return (
    <div className="ask-tool" data-testid="approval-tool-details">
      {includeToolName && toolName && (
        <div className="owner cell-mono">tool {toolName}</div>
      )}
      {args && (
        <>
          <div className="col-label">Arguments (redacted)</div>
          <pre className="cell-mono ask-args" data-testid="approval-arguments">
            {args}
          </pre>
        </>
      )}
    </div>
  );
}

export default ApprovalToolDetails;
