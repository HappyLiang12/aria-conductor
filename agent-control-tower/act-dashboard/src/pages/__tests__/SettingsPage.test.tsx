import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, waitFor, within } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import type { LlmProvider } from '../../types';

vi.mock('../../api/llmProviders', () => ({
  listProviders: vi.fn(),
  createProvider: vi.fn(),
  updateProvider: vi.fn(),
  deleteProvider: vi.fn(),
  testProvider: vi.fn(),
  activateProvider: vi.fn(),
}));

import {
  listProviders,
  createProvider,
  activateProvider,
  deleteProvider,
} from '../../api/llmProviders';
import { SettingsPage } from '../SettingsPage';

const list = vi.mocked(listProviders);
const create = vi.mocked(createProvider);
const activate = vi.mocked(activateProvider);
const remove = vi.mocked(deleteProvider);

function provider(over: Partial<LlmProvider> = {}): LlmProvider {
  return {
    id: 'p-1',
    name: 'Local Ollama',
    type: 'LOCAL',
    baseUrl: 'http://127.0.0.1:11434',
    apiKeyMasked: 'sk-…abcd',
    defaultModel: 'llama3',
    defaultMaxTokens: 4096,
    active: false,
    createdAt: '2026-01-01T00:00:00Z',
    updatedAt: null,
    ...over,
  };
}

function ui() {
  const qc = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  return render(
    <QueryClientProvider client={qc}>
      <MemoryRouter>
        <SettingsPage />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

beforeEach(() => {
  vi.clearAllMocks();
  list.mockResolvedValue([provider()]);
  create.mockResolvedValue(provider({ id: 'p-2', name: 'OpenAI' }));
  activate.mockResolvedValue(provider({ active: true }));
});

// ---------------------------------------------------------------------------
// Task 30 / Item 1: the LLM-provider mutations are operator-only server-side.
// Before this, every refusal was a toast echoing `apiErrorMessage`, which reads
// like a bug rather than a session problem.
// ---------------------------------------------------------------------------
describe('SettingsPage LLM-provider refusals (Task 30)', () => {
  async function activateProviderRow() {
    ui();
    fireEvent.click(await screen.findByRole('button', { name: 'Activate' }));
  }

  it.each([401, 403])('renders the operator-session notice for a %i', async (status) => {
    activate.mockRejectedValue({
      response: { status, data: { message: 'PROVIDER_RAW_SERVER_TEXT' } },
    });

    await activateProviderRow();

    const notice = await screen.findByTestId('operator-session-notice');
    expect(notice).toHaveTextContent(/changing llm providers is operator-only/i);
    expect(notice).toHaveTextContent(/loopback requests are the operator automatically/i);
    expect(screen.queryByText(/PROVIDER_RAW_SERVER_TEXT/)).not.toBeInTheDocument();
    expect(screen.queryByText(/Failed to activate provider/)).not.toBeInTheDocument();
  });

  it('keeps the ordinary toast for a 500, not a session notice', async () => {
    activate.mockRejectedValue({
      response: { status: 500, data: { message: 'provider store unavailable' } },
    });

    await activateProviderRow();

    expect(await screen.findByText('provider store unavailable')).toBeInTheDocument();
    expect(screen.queryByTestId('operator-session-notice')).not.toBeInTheDocument();
  });

  it('still activates a provider on success', async () => {
    ui();
    fireEvent.click(await screen.findByRole('button', { name: 'Activate' }));

    await waitFor(() => expect(activate).toHaveBeenCalledWith('p-1', expect.anything()));
    expect(await screen.findByText('Provider activated')).toBeInTheDocument();
    expect(screen.queryByTestId('operator-session-notice')).not.toBeInTheDocument();
  });

  it('renders the notice for a refused create, and no raw text in the dialog', async () => {
    create.mockRejectedValue({
      response: { status: 403, data: { message: 'CREATE_RAW_SERVER_TEXT' } },
    });
    ui();

    fireEvent.click(await screen.findByRole('button', { name: /add provider/i }));
    // The form's labels are not associated with their controls (existing markup),
    // so target the fields the way the other settings tests do: by order.
    const fields = document.querySelectorAll<HTMLInputElement>('.form-card input');
    fireEvent.change(fields[0], { target: { value: 'OpenAI' } });
    fireEvent.change(fields[1], { target: { value: 'https://api.openai.com/v1' } });
    fireEvent.change(fields[2], { target: { value: 'sk-test' } });
    fireEvent.change(fields[3], { target: { value: 'gpt-4' } });
    fireEvent.click(screen.getByRole('button', { name: 'Create' }));

    const notice = await screen.findByTestId('operator-session-notice');
    expect(notice).toHaveTextContent(/changing llm providers is operator-only/i);
    expect(screen.queryByText(/CREATE_RAW_SERVER_TEXT/)).not.toBeInTheDocument();
    expect(screen.queryByText(/Failed to create provider/)).not.toBeInTheDocument();
  });

  // Task 37 / Item 6 (T35 audit row 11): delete only closes the Confirm Delete
  // dialog on success, so a refusal rendered at page level sat behind the
  // still-open dialog — present in the document, invisible to the operator.
  async function openDeleteDialog() {
    ui();
    fireEvent.click(await screen.findByRole('button', { name: 'Delete' }));
    return screen.getByRole('dialog', { name: /confirm delete/i });
  }

  it('shows the delete refusal inside the Confirm Delete dialog that caused it', async () => {
    remove.mockRejectedValue({
      response: { status: 403, data: { message: 'DELETE_RAW_SERVER_TEXT' } },
    });

    const dialog = await openDeleteDialog();
    fireEvent.click(within(dialog).getByRole('button', { name: 'Delete' }));

    const notice = await within(dialog).findByTestId('operator-session-notice');
    expect(notice).toBeVisible();
    expect(notice).toHaveTextContent(/deleting the llm provider is operator-only/i);
    // The refusal is not the server's raw wording, and it is not a toast.
    expect(within(dialog).queryByText(/DELETE_RAW_SERVER_TEXT/)).not.toBeInTheDocument();
    expect(screen.queryByText(/Failed to delete provider/)).not.toBeInTheDocument();
    // The dialog is still up: the operator is deciding here, not on the page.
    expect(screen.getByRole('dialog', { name: /confirm delete/i })).toBeInTheDocument();
  });

  // Positive control: a 500 is a fault, not a session problem. It keeps the
  // ordinary text and must not fabricate an operator-session notice — and the
  // text must land INSIDE the dialog (Task 41 m3): the dialog stays open, and a
  // toast sits at z-index 1000 behind the blurred z-index 2000 overlay.
  it('keeps the ordinary toast for a 500 delete and shows no session notice', async () => {
    remove.mockRejectedValue({
      response: { status: 500, data: { message: 'provider store unavailable' } },
    });

    const dialog = await openDeleteDialog();
    fireEvent.click(within(dialog).getByRole('button', { name: 'Delete' }));

    expect(await screen.findAllByText('provider store unavailable')).not.toHaveLength(0);
    expect(screen.queryByTestId('operator-session-notice')).not.toBeInTheDocument();
    // The operator caused the failure from this dialog and it is still open, so
    // the text is inside it (the toast alone is behind the overlay).
    expect(await within(dialog).findByRole('alert')).toHaveTextContent(
      'provider store unavailable',
    );
    // Positive control: the ordinary toast path still fires too.
    const toasts = (await screen.findAllByText('provider store unavailable'))
      .filter((el) => !dialog.contains(el));
    expect(toasts.length).toBeGreaterThan(0);
    expect(screen.getByRole('dialog', { name: /confirm delete/i })).toBeInTheDocument();
  });
});
