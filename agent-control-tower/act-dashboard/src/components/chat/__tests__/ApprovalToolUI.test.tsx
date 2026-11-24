import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import type { ComponentProps } from 'react';
import { beforeEach, describe, expect, it, vi, type Mock } from 'vitest';
import type { ApprovalDecisionReceipt } from '../../../types';
import { applyOperatorHeaders } from '../../../api/operatorSession';
import { ApprovalToolUI } from '../ApprovalToolUI';

vi.mock('../../../api/approvals', () => ({
  decideApproval: vi.fn().mockResolvedValue({}),
  getApproval: vi.fn().mockResolvedValue({}),
}));
// Keep the real session helpers; only the header application is spied so the
// card's "operator headers before the decision" ordering is observable.
vi.mock('../../../api/operatorSession', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../../../api/operatorSession')>();
  return { ...actual, applyOperatorHeaders: vi.fn() };
});

const mockApplyHeaders = applyOperatorHeaders as Mock;

describe('ApprovalToolUI', () => {
  const props = {
    args: { approvalId: 'a1', toolName: 'bash', riskTier: 'DESTRUCTIVE', expiresAt: new Date(Date.now() + 60_000).toISOString() },
    toolName: '__approval', toolCallId: 'approval-a1', argsText: '', status: { type: 'complete' as const },
    addResult: vi.fn(), resume: vi.fn(), respondToApproval: vi.fn(),
  } as unknown as ComponentProps<typeof ApprovalToolUI>;

  /** The renderer only reads `args`; the rest of the part shape is inert here. */
  const withArgs = (args: Record<string, unknown>) =>
    ({
      args, toolName: '__approval', toolCallId: 'approval-a1', argsText: '',
      status: { type: 'complete' as const }, addResult: vi.fn(), resume: vi.fn(), respondToApproval: vi.fn(),
    }) as unknown as ComponentProps<typeof ApprovalToolUI>;

  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('posts an approval decision and shows the receipt', async () => {
    const { decideApproval } = await import('../../../api/approvals');
    render(<ApprovalToolUI {...props} />);
    await userEvent.click(screen.getByRole('button', { name: /allow/i }));
    await waitFor(() => expect(decideApproval).toHaveBeenCalledWith('a1', { approved: true }));
    expect(screen.getByText(/allowed/i)).toBeInTheDocument();
  });

  it('applies the operator session headers before deciding', async () => {
    const { decideApproval } = await import('../../../api/approvals');
    render(<ApprovalToolUI {...props} />);
    await userEvent.click(screen.getByRole('button', { name: /allow/i }));
    await waitFor(() => expect(decideApproval).toHaveBeenCalledWith('a1', { approved: true }));
    expect(mockApplyHeaders).toHaveBeenCalledTimes(1);
    expect(mockApplyHeaders.mock.invocationCallOrder[0]).toBeLessThan(
      vi.mocked(decideApproval).mock.invocationCallOrder[0],
    );
  });

  it('posts a rejection', async () => {
    const { decideApproval } = await import('../../../api/approvals');
    render(<ApprovalToolUI {...props} />);
    await userEvent.click(screen.getByRole('button', { name: /deny/i }));
    await waitFor(() => expect(decideApproval).toHaveBeenCalledWith('a1', { approved: false }));
  });

  it('posts one decision when Allow is double-clicked, and still shows the receipt', async () => {
    const { decideApproval } = await import('../../../api/approvals');
    // Hold the request open so the second click lands while the card is submitting.
    let settle!: (receipt: ApprovalDecisionReceipt) => void;
    vi.mocked(decideApproval).mockImplementationOnce(
      () =>
        new Promise<ApprovalDecisionReceipt>((resolve) => {
          settle = resolve;
        }),
    );
    render(<ApprovalToolUI {...props} />);
    const allow = screen.getByRole('button', { name: /^allow$/i });
    await userEvent.dblClick(allow);
    expect(decideApproval).toHaveBeenCalledTimes(1);
    expect(allow).toBeDisabled();
    settle({} as ApprovalDecisionReceipt);
    expect(await screen.findByText(/allowed/i)).toBeInTheDocument();
    expect(decideApproval).toHaveBeenCalledTimes(1);
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });

  it('shows the governed tool name, risk tier and expiry', () => {
    render(<ApprovalToolUI {...props} />);
    expect(screen.getByText(/approve bash/i)).toBeInTheDocument();
    expect(screen.getByText('DESTRUCTIVE')).toBeInTheDocument();
    expect(screen.getByText(/expires/i)).toBeInTheDocument();
  });

  it('renders one button per offered option and maps allow/deny to the decision', async () => {
    const { decideApproval } = await import('../../../api/approvals');
    render(
      <ApprovalToolUI
        {...withArgs({
          approvalId: 'a1',
          toolName: 'bash',
          optionsJson: JSON.stringify([
            { optionId: 'proceed_once', kind: 'allow_once', name: 'Allow once' },
            { optionId: 'cancel', kind: 'reject_once', name: 'Reject' },
          ]),
        })}
      />,
    );
    expect(screen.getByRole('button', { name: 'Allow once' })).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Allow once' }));
    await waitFor(() => expect(decideApproval).toHaveBeenCalledWith('a1', { approved: true }));
  });

  it('posts a rejection from the offered reject option', async () => {
    const { decideApproval } = await import('../../../api/approvals');
    render(
      <ApprovalToolUI
        {...withArgs({
          approvalId: 'a1',
          toolName: 'bash',
          optionsJson: JSON.stringify([
            { optionId: 'proceed_once', kind: 'allow_once', name: 'Allow once' },
            { optionId: 'cancel', kind: 'reject_once', name: 'Reject' },
          ]),
        })}
      />,
    );
    await userEvent.click(screen.getByRole('button', { name: 'Reject' }));
    await waitFor(() => expect(decideApproval).toHaveBeenCalledWith('a1', { approved: false }));
  });

  it('refuses to guess a decision for an always-scope option', () => {
    render(
      <ApprovalToolUI
        {...withArgs({
          approvalId: 'a1',
          toolName: 'bash',
          optionsJson: JSON.stringify([
            { optionId: 'proceed_always', kind: 'allow_always', name: 'Always allow' },
          ]),
        })}
      />,
    );
    expect(screen.getByRole('button', { name: 'Always allow' })).toBeDisabled();
  });

  it('maps an ALLOW_ONCE backend option to an approval', async () => {
    const { decideApproval } = await import('../../../api/approvals');
    render(
      <ApprovalToolUI
        {...withArgs({
          approvalId: 'a1',
          toolName: 'bash',
          optionsJson: JSON.stringify([
            { optionId: 'proceed_once', choice: 'ALLOW_ONCE' },
            { optionId: 'cancel', choice: 'DENY' },
          ]),
        })}
      />,
    );
    // No label field: the button is labelled from the option id, humanized.
    await userEvent.click(screen.getByRole('button', { name: /proceed once/i }));
    await waitFor(() => expect(decideApproval).toHaveBeenCalledWith('a1', { approved: true }));
    expect(screen.getByText(/allowed/i)).toBeInTheDocument();
  });

  it('maps a DENY backend option to a rejection', async () => {
    const { decideApproval } = await import('../../../api/approvals');
    render(
      <ApprovalToolUI
        {...withArgs({
          approvalId: 'a1',
          toolName: 'bash',
          optionsJson: JSON.stringify([
            { optionId: 'proceed_once', choice: 'ALLOW_ONCE' },
            { optionId: 'cancel', choice: 'DENY' },
          ]),
        })}
      />,
    );
    await userEvent.click(screen.getByRole('button', { name: /cancel/i }));
    await waitFor(() => expect(decideApproval).toHaveBeenCalledWith('a1', { approved: false }));
  });

  it('falls back to allow/deny when optionsJson is unparseable', () => {
    render(<ApprovalToolUI {...withArgs({ approvalId: 'a1', toolName: 'bash', optionsJson: 'not json' })} />);
    expect(screen.getByRole('button', { name: /^allow$/i })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /^deny$/i })).toBeInTheDocument();
  });

  it('surfaces a failed decision and keeps the card retryable', async () => {
    const { decideApproval } = await import('../../../api/approvals');
    vi.mocked(decideApproval).mockRejectedValueOnce({
      response: { status: 500, data: { message: 'ask expired' } },
    });
    render(<ApprovalToolUI {...props} />);
    await userEvent.click(screen.getByRole('button', { name: /allow/i }));
    expect(await screen.findByRole('alert')).toHaveTextContent('ask expired');
    expect(screen.getByRole('button', { name: /allow/i })).toBeInTheDocument();
  });

  it('settles a 409 as an expired/decided-elsewhere receipt, not a retryable error', async () => {
    const { decideApproval } = await import('../../../api/approvals');
    vi.mocked(decideApproval).mockRejectedValueOnce({
      response: { status: 409, data: { error: 'Ask apr-already is already DECIDED' } },
    });
    render(<ApprovalToolUI {...props} />);
    await userEvent.click(screen.getByRole('button', { name: /allow/i }));
    const receipt = await screen.findByRole('status');
    expect(receipt).toHaveTextContent(/expired or decided elsewhere/i);
    // The backend's own wording, not raw axios text.
    expect(receipt).toHaveTextContent('Ask apr-already is already DECIDED');
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /allow/i })).not.toBeInTheDocument();
  });

  it('renders the redacted arguments of the ask it fetched', async () => {
    const { getApproval } = await import('../../../api/approvals');
    vi.mocked(getApproval).mockResolvedValueOnce({
      id: 'a1',
      arguments: '{"command":"ls -la","cwd":"/tmp"}',
    } as never);
    render(<ApprovalToolUI {...props} />);
    expect(await screen.findByText(/command: ls -la/)).toBeInTheDocument();
    expect(screen.getByText(/cwd: \/tmp/)).toBeInTheDocument();
  });

  // F-B1 parity with AriaApprovalCard: a NATIVE_TOOL ask stores the whole ACP
  // `permission.request` envelope as `arguments` (transport plumbing plus the
  // tool's `input`). The legacy card reduces it to `input`; the chat card must
  // show that same payload, never the digest/session/sequence fields.
  it('reduces an ACP permission envelope to the tool input it carries', async () => {
    const { getApproval } = await import('../../../api/approvals');
    const envelope = {
      seq: 248,
      at: '2026-11-11T23:26:57.051Z',
      type: 'permission.request',
      requestId: 7,
      sessionId: '5708a4ad-6ef0-4605-861d-0fc7c05cc799',
      toolCallId: 'call_76afe3236bab41868add80af',
      toolName: 'mcp__aria-conductor__remember_fact',
      input: { canonicalKey: 'person:operator', factKey: 'morning_briefing_content' },
      inputBytes: 295,
      inputTruncated: false,
      argumentsDigest: 'a790e991fa9d6983f46dcade8c5dcb13c5079e20419538e44094c878f5c60f7f',
      options: [{ optionId: 'proceed_once', kind: 'allow_once', name: 'Allow' }],
    };
    vi.mocked(getApproval).mockResolvedValueOnce({
      id: 'a1',
      toolName: 'remember_fact',
      arguments: JSON.stringify(envelope),
    } as never);
    render(<ApprovalToolUI {...props} />);

    const summary = await screen.findByText(/canonicalKey: person:operator/);
    expect(summary.textContent).toBe(
      'canonicalKey: person:operator\nfactKey: morning_briefing_content',
    );
    for (const leaked of [
      'permission.request', 'seq', 'sessionId', 'requestId', 'toolCallId',
      'inputBytes', 'inputTruncated', 'argumentsDigest', 'a790e991', 'proceed_once',
    ]) {
      expect(summary.textContent).not.toContain(leaked);
      expect(document.body.textContent).not.toContain(leaked);
    }
  });

  it('says unclassified when the ask carries no risk tier', () => {
    render(<ApprovalToolUI {...withArgs({ approvalId: 'a1', toolName: 'bash' })} />);
    expect(screen.getByText('unclassified')).toBeInTheDocument();
    expect(screen.queryByText('REVIEW')).not.toBeInTheDocument();
  });

  it('renders tool as the governed name when the frame carries none', () => {
    render(<ApprovalToolUI {...withArgs({ approvalId: 'a1', toolName: '' })} />);
    expect(screen.getByText('Approve tool?')).toBeInTheDocument();
  });

  it('reports an unreadable argument read instead of pretending there are none', async () => {
    const { getApproval } = await import('../../../api/approvals');
    vi.mocked(getApproval).mockRejectedValueOnce({ response: { status: 404 } });
    render(<ApprovalToolUI {...props} />);
    expect(await screen.findByText(/arguments could not be read/i)).toBeInTheDocument();
  });
});
