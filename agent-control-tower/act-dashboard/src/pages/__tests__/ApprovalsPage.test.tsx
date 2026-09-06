import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { ApprovalsPage } from '../ApprovalsPage';
import type { Approval, WorkflowChain, WorkflowStepStatus } from '../../types';

vi.mock('../../api/approvals', () => ({
  listApprovals: vi.fn(),
  decideApproval: vi.fn(),
}));
vi.mock('../../api/agents', () => ({
  listAgents: vi.fn().mockResolvedValue([]),
}));
vi.mock('../../api/workflows', () => ({
  listWorkflows: vi.fn(),
  resubmitApproval: vi.fn(),
}));
vi.mock('../../components/Layout', () => ({
  useWebSocketContext: () => ({
    lastMessage: null,
    isConnected: true,
    subscribe: () => ({ unsubscribe: () => {} }),
  }),
}));

import { listApprovals, decideApproval } from '../../api/approvals';
import { listWorkflows, resubmitApproval } from '../../api/workflows';

const FUTURE = new Date(Date.now() + 3600_000).toISOString();

function mkApproval(over: Partial<Approval> = {}): Approval {
  return {
    id: 'ap-1',
    runId: 'run-0001',
    toolCallId: null,
    status: 'PENDING',
    reason: '',
    requestedAt: new Date().toISOString(),
    decidedAt: null,
    expiresAt: FUTURE,
    ...over,
  };
}

function mkStep(over: Partial<{ runId: string | null; status: WorkflowStepStatus }> = {}) {
  return {
    index: 0,
    agentId: 'agent-1',
    promptTemplate: 'tpl',
    status: 'PENDING' as WorkflowStepStatus,
    runId: null,
    outputPreview: null,
    ...over,
  };
}

function mkChain(over: Partial<WorkflowChain> = {}): WorkflowChain {
  return {
    id: 'wf-1',
    name: 'Chain',
    status: 'WAITING_APPROVAL',
    currentStepIndex: 0,
    totalSteps: 1,
    steps: [],
    createdAt: new Date().toISOString(),
    completedAt: null,
    ...over,
  };
}

function ui() {
  const qc = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  return render(
    <QueryClientProvider client={qc}>
      <ApprovalsPage />
    </QueryClientProvider>,
  );
}

beforeEach(() => {
  vi.clearAllMocks();
  vi.mocked(listApprovals).mockResolvedValue([]);
  vi.mocked(decideApproval).mockResolvedValue(mkApproval());
  vi.mocked(listWorkflows).mockResolvedValue([]);
  vi.mocked(resubmitApproval).mockResolvedValue(mkChain());
});

describe('ApprovalsPage deny confirmation (#UI audit)', () => {
  it('deny_opensConfirmDialog: cancel keeps PENDING, confirm calls decideApproval(approved=false)', async () => {
    vi.mocked(listApprovals).mockResolvedValue([
      mkApproval({ id: 'ap-deny', runId: 'run-deny', reason: 'gate check' }),
    ]);
    ui();
    await screen.findByText('Run run-deny');

    fireEvent.click(screen.getByRole('button', { name: 'Deny' }));
    expect(screen.getByText('Confirm Denial')).toBeInTheDocument();
    expect(decideApproval).not.toHaveBeenCalled();

    fireEvent.click(screen.getByRole('button', { name: 'Cancel' }));
    expect(screen.queryByText('Confirm Denial')).not.toBeInTheDocument();
    expect(decideApproval).not.toHaveBeenCalled();

    fireEvent.click(screen.getByRole('button', { name: 'Deny' }));
    fireEvent.click(screen.getByRole('button', { name: 'Confirm Deny' }));
    await waitFor(() =>
      expect(decideApproval).toHaveBeenCalledWith('ap-deny', { approved: false, reason: '' }),
    );
  });
});

describe('ApprovalsPage history detail (#UI audit)', () => {
  it('historyRow_expandsMarkdown: resolved SPEC_REVIEW row expands to MarkdownViewer content', async () => {
    vi.mocked(listApprovals).mockResolvedValue([
      mkApproval({
        id: 'ap-hist',
        runId: 'run-hist',
        status: 'APPROVED',
        decidedAt: new Date().toISOString(),
        approvalType: 'SPEC_REVIEW',
        content: '# Approved Spec Detail\n\nBody text here',
        knowledgeItemId: 'ki-123456',
      }),
    ]);
    ui();
    fireEvent.click(await screen.findByRole('button', { name: /History/ }));

    expect(screen.queryByText('Approved Spec Detail')).not.toBeInTheDocument();
    fireEvent.click(screen.getByText('run-hist'));

    expect(await screen.findByText('Approved Spec Detail')).toBeInTheDocument();
    expect(document.querySelector('.spec-review-markdown')).not.toBeNull();
  });

  it('historyRow_toolCall shows the arguments block when expanded', async () => {
    vi.mocked(listApprovals).mockResolvedValue([
      mkApproval({
        id: 'ap-tool',
        runId: 'run-tool',
        status: 'DENIED',
        decidedAt: new Date().toISOString(),
        approvalType: 'TOOL_CALL',
        toolName: 'shell',
        arguments: '{"cmd":"ls"}',
      }),
    ]);
    ui();
    fireEvent.click(await screen.findByRole('button', { name: /History/ }));
    fireEvent.click(screen.getByText('run-tool'));

    expect(await screen.findByText('Arguments')).toBeInTheDocument();
    expect(screen.getByText('{"cmd":"ls"}')).toBeInTheDocument();
  });
});

describe('ApprovalsPage stuck chains (#UI audit)', () => {
  it('stuckChain_shown_whenGateExpired: warns for chains whose runIds match no pending approval; resubmit calls the API', async () => {
    vi.mocked(listApprovals).mockResolvedValue([
      mkApproval({ id: 'ap-live', runId: 'run-live' }),
    ]);
    vi.mocked(listWorkflows).mockResolvedValue([
      mkChain({
        id: 'wf-stuck',
        name: 'Stuck Chain Alpha',
        steps: [mkStep({ status: 'PENDING', runId: 'run-orphan' })],
      }),
      mkChain({
        id: 'wf-ok',
        name: 'Healthy Chain',
        steps: [mkStep({ status: 'PENDING', runId: 'run-live' })],
      }),
    ]);
    ui();

    expect(await screen.findByText('Stuck Chain Alpha')).toBeInTheDocument();
    expect(screen.queryByText('Healthy Chain')).not.toBeInTheDocument();

    fireEvent.click(screen.getByRole('button', { name: /Resubmit approval/i }));
    await waitFor(() => expect(resubmitApproval).toHaveBeenCalledWith('wf-stuck'));
  });

  it('renders no stuck panel when every WAITING_APPROVAL chain has a pending approval', async () => {
    vi.mocked(listApprovals).mockResolvedValue([
      mkApproval({ id: 'ap-live', runId: 'run-live' }),
    ]);
    vi.mocked(listWorkflows).mockResolvedValue([
      mkChain({
        id: 'wf-ok',
        name: 'Healthy Chain',
        steps: [mkStep({ status: 'PENDING', runId: 'run-live' })],
      }),
    ]);
    ui();

    await screen.findByText(/pending/i);
    expect(screen.queryByText(/Stuck approval gates/i)).not.toBeInTheDocument();
    expect(screen.queryByText('Healthy Chain')).not.toBeInTheDocument();
  });
});
