import { describe, it, expect, vi, beforeEach, type Mock } from 'vitest';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { AriaApprovalCard } from '../AriaApprovalCard';
import type { PanelItem } from '../ariaPanelState';
import { decideApproval } from '../../api/approvals';
import { applyOperatorHeaders } from '../../api/operatorSession';

vi.mock('../../api/approvals', () => ({ decideApproval: vi.fn(), answerAsk: vi.fn() }));
// Keep the real rejection/error helpers; only the header application is spied so
// the card's "operator headers first" ordering is observable.
vi.mock('../../api/operatorSession', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../../api/operatorSession')>();
  return { ...actual, applyOperatorHeaders: vi.fn() };
});

type ApprovalItem = Extract<PanelItem, { kind: 'approval' }>;

const mockDecide = decideApproval as Mock;
const mockApplyHeaders = applyOperatorHeaders as Mock;

const pendingAsk: ApprovalItem = {
  kind: 'approval',
  id: 'i1',
  approvalId: 'a1',
  runId: 'r1',
  toolName: 'mcp__aria-conductor__remember_fact',
  riskTier: 'WRITE_LOCAL',
  toolPolicy: 'WORKER_WRITE',
  args: '{"key":"preferred_channel"}',
  state: 'pending',
};

/** An operator-only refusal: the backend's 403 with its own actionable detail. */
function operatorRejection() {
  return {
    response: {
      status: 403,
      data: { message: 'Operator session required for this route' },
    },
  };
}

beforeEach(() => {
  vi.clearAllMocks();
  mockDecide.mockResolvedValue({ approvalId: 'a1', approved: true, status: 'processed' });
});

describe('AriaApprovalCard', () => {
  it('approves once with operator headers and reports the settled state', async () => {
    const onSettled = vi.fn();
    render(<AriaApprovalCard item={pendingAsk} onSettled={onSettled} />);

    expect(screen.getByText('WRITE_LOCAL')).toBeInTheDocument();
    expect(screen.getByText('WORKER_WRITE')).toBeInTheDocument();
    expect(screen.getByText('mcp__aria-conductor__remember_fact')).toBeInTheDocument();

    fireEvent.click(screen.getByRole('button', { name: /allow once/i }));

    await waitFor(() => expect(mockDecide).toHaveBeenCalledWith('a1', expect.objectContaining({ approved: true })));
    expect(mockApplyHeaders).toHaveBeenCalled();
    // The operator CSRF header must be applied before the mutation is issued.
    expect(mockApplyHeaders.mock.invocationCallOrder[0])
      .toBeLessThan(mockDecide.mock.invocationCallOrder[0]);
    await waitFor(() => expect(onSettled).toHaveBeenCalledWith('approved'));
  });

  it('shows the refusal instead of retrying when the operator session is rejected', async () => {
    const onSettled = vi.fn();
    mockDecide.mockRejectedValueOnce(operatorRejection());
    render(<AriaApprovalCard item={pendingAsk} onSettled={onSettled} />);

    fireEvent.click(screen.getByRole('button', { name: /allow once/i }));

    expect(await screen.findByText(/operator session/i)).toBeInTheDocument();
    // Task 35: the card keeps its own sentence structure ("nothing was
    // decided") but composes the one remedy that can work, not a reload.
    const refusal = screen.getByRole('alert');
    expect(refusal).toHaveTextContent(/open the dashboard on the machine that runs the backend/i);
    expect(refusal).toHaveTextContent(/nothing was decided by the refused call/i);
    expect(refusal).not.toHaveTextContent(/reload the page and retry/i);
    expect(refusal).not.toHaveTextContent(/reload and try again/i);
    expect(onSettled).not.toHaveBeenCalled();
    // The ask is still the operator's to decide: the control stays available.
    expect(screen.getByRole('button', { name: /allow once/i })).toBeEnabled();
  });

  // Task 37 / Item 2: the same 403 also serves the cross-site rejection, and its
  // remedy is not "grant access" — the request came from a page the backend does
  // not trust. Positive control for the test above: that copy must be untouched.
  // Task 41: the body is the one ApprovalController.decideApproval actually
  // serves — `{ error }`, not `{ message }`.
  it('gives the cross-site remedy, not the operator-grant one, for a cross-origin 403', async () => {
    const onSettled = vi.fn();
    mockDecide.mockRejectedValueOnce({
      response: { status: 403, data: { error: 'Cross-origin operator mutation rejected' } },
    });
    render(<AriaApprovalCard item={pendingAsk} onSettled={onSettled} />);

    fireEvent.click(screen.getByRole('button', { name: /allow once/i }));

    const refusal = await screen.findByRole('alert');
    expect(refusal).toHaveTextContent(/cross-site request/i);
    expect(refusal).toHaveTextContent(/origin the backend trusts/i);
    expect(refusal).not.toHaveTextContent(/must grant access/i);
    expect(refusal).toHaveTextContent(/nothing was decided by the refused call/i);
    expect(onSettled).not.toHaveBeenCalled();
  });

  it('denies with an explicit refusal and reports it', async () => {
    const onSettled = vi.fn();
    render(<AriaApprovalCard item={pendingAsk} onSettled={onSettled} />);

    fireEvent.click(screen.getByRole('button', { name: /^deny$/i }));

    await waitFor(() => expect(mockDecide).toHaveBeenCalledWith('a1', expect.objectContaining({ approved: false })));
    await waitFor(() => expect(onSettled).toHaveBeenCalledWith('denied'));
  });

  it('issues one decision while the first is in flight', async () => {
    const onSettled = vi.fn();
    let resolve: (value: unknown) => void = () => {};
    mockDecide.mockReturnValueOnce(new Promise((r) => { resolve = r; }));
    render(<AriaApprovalCard item={pendingAsk} onSettled={onSettled} />);

    const allow = screen.getByRole('button', { name: /allow once/i });
    fireEvent.click(allow);
    fireEvent.click(allow);

    expect(mockDecide).toHaveBeenCalledTimes(1);
    resolve({ approvalId: 'a1', approved: true, status: 'processed' });
    await waitFor(() => expect(onSettled).toHaveBeenCalledTimes(1));
  });

  // T45 Item 3 (spec A3): the operator approves a payload, so the card shows a
  // compact summary of it — one line per JSON key, verbatim for raw text. The
  // summary is derived from the arguments themselves, never paraphrased.
  it('summarises the arguments it is asking the operator to approve', () => {
    render(
      <AriaApprovalCard
        item={{ ...pendingAsk, args: '{"command":"npm test","cwd":"/w/repo"}' }}
        onSettled={vi.fn()}
      />,
    );

    const summary = screen.getByText(/command: npm test/);
    expect(summary.textContent).toBe('command: npm test\ncwd: /w/repo');
    // The full payload stays reachable on the element, punctuation included.
    expect(summary).toHaveAttribute('title', '{"command":"npm test","cwd":"/w/repo"}');
  });

  // F-B1 (T54): an ACP ask stores the whole `permission.request` envelope in
  // `arguments` (live evidence B-approvals-T1.json). Only the envelope's
  // `input` is what the tool will do; the rest is protocol plumbing the
  // operator cannot act on. Positive control above: a plain object keeps the
  // one-line-per-key summary.
  it('reduces an ACP permission envelope to the tool input it carries', () => {
    const envelope = {
      seq: 248,
      at: '2026-11-11T23:26:57.051Z',
      type: 'permission.request',
      requestId: 7,
      sessionId: '5708a4ad-6ef0-4605-861d-0fc7c05cc799',
      toolCallId: 'call_76afe3236bab41868add80af',
      toolName: 'mcp__aria-conductor__remember_fact',
      toolKind: 'other',
      toolCallTitle: 'remember_fact (aria-conductor)',
      input: { canonicalKey: 'person:operator', factKey: 'morning_briefing_content' },
      inputBytes: 295,
      inputTruncated: false,
      argumentsDigest: 'a790e991fa9d6983f46dcade8c5dcb13c5079e20419538e44094c878f5c60f7f',
      options: [{ optionId: 'proceed_once', kind: 'allow_once', name: 'Allow' }],
    };
    render(
      <AriaApprovalCard item={{ ...pendingAsk, args: JSON.stringify(envelope) }} onSettled={vi.fn()} />,
    );

    const summary = screen.getByText(/canonicalKey: person:operator/);
    expect(summary.textContent).toBe(
      'canonicalKey: person:operator\nfactKey: morning_briefing_content',
    );
    for (const leaked of [
      'permission.request', 'seq:', 'sessionId', 'requestId', 'toolCallId',
      'inputBytes', 'argumentsDigest', 'inputTruncated', 'proceed_once',
    ]) {
      expect(summary.textContent).not.toContain(leaked);
    }
  });

  it('shows a non-JSON argument verbatim instead of guessing its structure', () => {
    render(<AriaApprovalCard item={{ ...pendingAsk, args: 'git push --force origin main' }} onSettled={vi.fn()} />);

    expect(screen.getByText('git push --force origin main')).toBeInTheDocument();
  });

  it('says the arguments are being read, then that they could not be read — never "no arguments"', () => {
    const { rerender } = render(
      <AriaApprovalCard item={{ ...pendingAsk, args: undefined, argsStatus: 'reading' }} onSettled={vi.fn()} />,
    );
    expect(screen.getByText(/reading the arguments/i)).toBeInTheDocument();
    expect(screen.queryByText(/could not be read/i)).not.toBeInTheDocument();
    // The decision controls never wait for the summary: the ask is decidable.
    expect(screen.getByRole('button', { name: /allow once/i })).toBeEnabled();

    rerender(
      <AriaApprovalCard item={{ ...pendingAsk, args: undefined, argsStatus: 'unavailable' }} onSettled={vi.fn()} />,
    );
    expect(screen.getByText(/arguments could not be read/i)).toBeInTheDocument();
    expect(screen.queryByText(/reading the arguments/i)).not.toBeInTheDocument();
  });

  // Spec :504-505: an unclassified tool still resolves to READ for backward
  // compatibility, but a bare tier must not imply the tool was reviewed. The
  // backend serves `toolPolicy: null` for it (ApprovalDetail), not an absent key.
  it('labels a tool the catalog does not classify as unclassified', () => {
    render(<AriaApprovalCard item={{ ...pendingAsk, toolPolicy: null as unknown as undefined }} onSettled={vi.fn()} />);

    expect(screen.getByText('unclassified')).toBeInTheDocument();
    expect(screen.queryByText('WORKER_WRITE')).not.toBeInTheDocument();
  });

  it('does not label a classified tool unclassified, and keeps its policy name', () => {
    render(<AriaApprovalCard item={pendingAsk} onSettled={vi.fn()} />);

    expect(screen.getByText('WORKER_WRITE')).toBeInTheDocument();
    expect(screen.queryByText('unclassified')).not.toBeInTheDocument();
  });

  it('renders a settled card without decision controls', () => {
    render(<AriaApprovalCard item={{ ...pendingAsk, state: 'denied', reason: 'wrong tenant' }} onSettled={vi.fn()} />);

    expect(document.querySelector('.ai-decision-settled')).toHaveTextContent('Denied — wrong tenant');
    expect(screen.queryByRole('button', { name: /allow once/i })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /^deny$/i })).not.toBeInTheDocument();
  });

  // F-LC1 (T54): a settled card already HAS its decision, so it must not ask for
  // one. The live bar showed "ALLOWED ONCE" above "this tool needs your decision
  // before it runs" — the card asking for what it had just recorded.
  it.each(['approved', 'denied', 'expired'] as const)(
    'a settled (%s) card does not ask for a decision it already has',
    (state) => {
      render(<AriaApprovalCard item={{ ...pendingAsk, state }} onSettled={vi.fn()} />);

      const card = screen.getByText('mcp__aria-conductor__remember_fact')
        .closest('.ai-decision-card') as HTMLElement;
      expect(card).not.toBeNull();
      expect(card).not.toHaveTextContent(/needs your decision/i);
      // …it still says what happened, per state.
      expect(card).toHaveTextContent(
        state === 'approved' ? 'Allowed once'
          : state === 'denied' ? 'Denied'
            : 'Expired before a decision',
      );
    },
  );

  // Positive control: a genuinely pending ask keeps the instruction.
  it('a pending card still asks for the decision', () => {
    render(<AriaApprovalCard item={pendingAsk} onSettled={vi.fn()} />);

    expect(screen.getByText(/this tool needs your decision before it runs/i)).toBeInTheDocument();
  });

  // A 409 is the backend saying the ask is no longer PENDING (expired, the run
  // was cancelled, or another window already decided it). Reporting that as
  // "still pending; retry" is a false claim, and the card would stay actionable
  // forever behind the composer gate.
  it('reports a state conflict as settled, not as "still pending"', async () => {
    const onSettled = vi.fn();
    mockDecide.mockRejectedValueOnce({
      response: { status: 409, data: { message: 'Approval is no longer PENDING' } },
    });
    render(<AriaApprovalCard item={pendingAsk} onSettled={onSettled} />);

    fireEvent.click(screen.getByRole('button', { name: /allow once/i }));

    await waitFor(() =>
      expect(onSettled).toHaveBeenCalledWith('expired', expect.stringMatching(/no longer PENDING/)),
    );
    expect(screen.queryByText(/still pending; retry/i)).not.toBeInTheDocument();
  });

  it('keeps the ask actionable when the decision fails for another reason', async () => {
    // Positive control for the 409 mapping: a 500 proves nothing about the
    // ask's state, so the card reports the failure and stays the operator's.
    const onSettled = vi.fn();
    mockDecide.mockRejectedValueOnce({ response: { status: 500, data: { message: 'relay dead' } } });
    render(<AriaApprovalCard item={pendingAsk} onSettled={onSettled} />);

    fireEvent.click(screen.getByRole('button', { name: /allow once/i }));

    expect(await screen.findByText(/relay dead/)).toBeInTheDocument();
    expect(onSettled).not.toHaveBeenCalled();
    expect(screen.getByRole('button', { name: /allow once/i })).toBeEnabled();
  });
});
