import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import { MemoryPage } from '../MemoryPage';
import type {
  MemoryEntityDetail,
  MemoryEntityView,
  MemoryFactView,
  MemoryProposalView,
} from '../../api/memory';

// The real api/memory module runs; only the transport is mocked, so the tests
// assert the exact wire path and body the MemoryController serves. `defaults`
// is what operatorSession.applyOperatorHeaders() writes the CSRF header onto.
vi.mock('../../api/client', () => ({
  default: {
    get: vi.fn(),
    post: vi.fn(),
    patch: vi.fn(),
    delete: vi.fn(),
    defaults: { headers: { common: {} as Record<string, string> } },
  },
}));

import client from '../../api/client';

const get = vi.mocked(client.get);
const post = vi.mocked(client.post);
const patch = vi.mocked(client.patch);

function ok(data: unknown) {
  return Promise.resolve({ data } as never);
}

function entity(over: Partial<MemoryEntityView> = {}): MemoryEntityView {
  return {
    id: 'e1',
    kind: 'PERSON',
    canonicalKey: 'person:happy',
    displayName: 'Happy',
    sensitivity: 'INTERNAL',
    status: 'ACTIVE',
    origin: 'OPERATOR',
    mergedInto: null,
    version: 1,
    createdAt: null,
    updatedAt: null,
    ...over,
  };
}

function fact(over: Partial<MemoryFactView> = {}): MemoryFactView {
  return {
    id: 'f1',
    entityId: 'e1',
    factKey: 'preferred_editor',
    valueText: 'IntelliJ IDEA',
    valueType: 'STRING',
    status: 'ACTIVE',
    origin: 'OPERATOR',
    sensitivity: 'INTERNAL',
    confidence: null,
    validFrom: null,
    validTo: null,
    version: 1,
    ...over,
  };
}

function proposal(over: Partial<MemoryProposalView> = {}): MemoryProposalView {
  return {
    id: 'p1',
    subjectType: 'ENTITY',
    entityId: 'p9',
    factKey: 'person:newfriend',
    valueText: 'Newfriend',
    predicate: null,
    toEntityId: null,
    status: 'PENDING',
    origin: 'EXTRACTION',
    sensitivity: 'INTERNAL',
    confidence: null,
    createdAt: null,
    validFrom: null,
    ...over,
  };
}

function detail(over: Partial<MemoryEntityDetail> = {}): MemoryEntityDetail {
  return {
    entity: entity(),
    facts: [fact()],
    relations: [],
    links: [],
    assertions: [],
    ...over,
  };
}

/** Routes GETs by exact path; entity/proposal lists answer per `status` param. */
function routeGets(options: {
  entities?: (status: string | undefined) => unknown;
  proposals?: (status: string | undefined) => unknown;
  detail?: unknown;
}) {
  get.mockImplementation((url: string, config?: { params?: Record<string, string> }) => {
    if (url === '/api/v1/memory/entities') {
      return ok(options.entities ? options.entities(config?.params?.status) : []);
    }
    if (url === '/api/v1/memory/proposals') {
      return ok(options.proposals ? options.proposals(config?.params?.status) : []);
    }
    if (url.startsWith('/api/v1/memory/entities/')) {
      return ok(options.detail ?? detail());
    }
    return Promise.reject(new Error(`unexpected GET ${url}`));
  });
}

function ui() {
  const qc = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  return render(
    <QueryClientProvider client={qc}>
      <MemoryRouter>
        <MemoryPage />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

beforeEach(() => {
  vi.clearAllMocks();
  post.mockResolvedValue({ data: entity() } as never);
  patch.mockResolvedValue({ data: entity() } as never);
});

describe('MemoryPage — entity list', () => {
  it('lists ACTIVE entities with their canonical key and asks for the ACTIVE page', async () => {
    routeGets({ entities: () => [entity()] });
    ui();

    expect(await screen.findByText('Happy')).toBeInTheDocument();
    expect(screen.getByText('person:happy')).toBeInTheDocument();
    expect(get).toHaveBeenCalledWith('/api/v1/memory/entities', { params: { status: 'ACTIVE' } });
    // A pending-decision empty inbox is an honest empty state, not a hidden panel.
    expect(get).toHaveBeenCalledWith('/api/v1/memory/proposals', { params: { status: 'PENDING' } });
    expect(await screen.findByText(/no proposals awaiting a decision/i)).toBeInTheDocument();
  });

  it('shows an honest empty state when there is no memory yet', async () => {
    routeGets({});
    ui();

    expect(await screen.findByText(/no memory entities yet/i)).toBeInTheDocument();
  });

  it('shows the loading notice while the first page is in flight', async () => {
    get.mockImplementation(() => new Promise(() => {}));
    ui();

    expect(await screen.findByText('Loading memory…')).toBeInTheDocument();
    expect(screen.getByText('Loading proposals…')).toBeInTheDocument();
  });

  it('renders the operator-session notice for a 403 instead of an empty list', async () => {
    get.mockRejectedValue({ response: { status: 403, data: {} } });
    ui();

    expect(await screen.findByTestId('operator-session-notice')).toBeInTheDocument();
    expect(screen.queryByText(/no memory entities yet/i)).not.toBeInTheDocument();
  });

  it('renders a non-authority failure as the backend message', async () => {
    get.mockImplementation((url: string) =>
      url === '/api/v1/memory/proposals'
        ? ok([])
        : Promise.reject({
            response: { status: 500, data: { message: 'memory store unavailable' } },
          }),
    );
    ui();

    expect(await screen.findByText('memory store unavailable')).toBeInTheDocument();
    expect(screen.queryByTestId('operator-session-notice')).not.toBeInTheDocument();
  });

  it('lists PENDING entities without a detail control, because the detail read 404s them', async () => {
    routeGets({
      entities: (status) =>
        status === 'PENDING'
          ? [entity({ id: 'p9', status: 'PENDING', origin: 'EXTRACTION', displayName: 'Newfriend' })]
          : [],
    });
    ui();

    fireEvent.click(await screen.findByRole('button', { name: 'Pending entities' }));

    expect(await screen.findByText('Newfriend')).toBeInTheDocument();
    expect(get).toHaveBeenCalledWith('/api/v1/memory/entities', { params: { status: 'PENDING' } });
    expect(screen.queryByRole('button', { name: 'Open' })).not.toBeInTheDocument();
  });
});

describe('MemoryPage — entity creation', () => {
  it('opens the create dialog, validates the required fields, then posts the entity body', async () => {
    routeGets({});
    ui();

    fireEvent.click(await screen.findByRole('button', { name: /new entity/i }));
    expect(screen.getByRole('dialog', { name: /create memory entity/i })).toBeInTheDocument();

    // Empty submit is refused client-side: no request leaves the page. The
    // first missing field is named, then the next one once it is filled.
    fireEvent.click(screen.getByRole('button', { name: /create entity/i }));
    expect(await screen.findByText('Canonical key is required')).toBeInTheDocument();
    expect(post).not.toHaveBeenCalled();

    fireEvent.change(screen.getByLabelText('Canonical key'), {
      target: { value: 'person:happy' },
    });
    fireEvent.click(screen.getByRole('button', { name: /create entity/i }));
    expect(await screen.findByText('Display name is required')).toBeInTheDocument();
    expect(post).not.toHaveBeenCalled();

    fireEvent.change(screen.getByLabelText('Kind'), { target: { value: 'PERSON' } });
    fireEvent.change(screen.getByLabelText('Display name'), { target: { value: 'Happy' } });
    fireEvent.change(screen.getByLabelText('Sensitivity'), { target: { value: 'CONFIDENTIAL' } });
    fireEvent.click(screen.getByRole('button', { name: /create entity/i }));

    await waitFor(() =>
      expect(post).toHaveBeenCalledWith('/api/v1/memory/entities', {
        kind: 'PERSON',
        canonicalKey: 'person:happy',
        displayName: 'Happy',
        sensitivity: 'CONFIDENTIAL',
      }),
    );
    expect(await screen.findByText(/entity created/i)).toBeInTheDocument();
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  });
});

describe('MemoryPage — proposal inbox', () => {
  it('lists the PENDING proposals and decides one through a confirmation', async () => {
    routeGets({ proposals: (status) => (status === 'PENDING' ? [proposal()] : []) });
    post.mockResolvedValue({ data: proposal({ status: 'ACTIVE' }) } as never);
    ui();

    expect(await screen.findByText('person:newfriend')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'Approve' }));

    const dialog = screen.getByRole('dialog', { name: /confirm memory decision/i });
    expect(dialog).toBeInTheDocument();
    expect(post).not.toHaveBeenCalled();

    fireEvent.click(screen.getByRole('button', { name: 'Confirm' }));

    await waitFor(() =>
      expect(post).toHaveBeenCalledWith('/api/v1/memory/proposals/p1/decide', {
        decision: 'APPROVE',
      }),
    );
  });

  it('rejects a proposal with the REJECT decision', async () => {
    routeGets({ proposals: (status) => (status === 'PENDING' ? [proposal()] : []) });
    post.mockResolvedValue({ data: proposal({ status: 'RETIRED' }) } as never);
    ui();

    fireEvent.click(await screen.findByRole('button', { name: 'Reject' }));
    fireEvent.click(screen.getByRole('button', { name: 'Confirm' }));

    await waitFor(() =>
      expect(post).toHaveBeenCalledWith('/api/v1/memory/proposals/p1/decide', {
        decision: 'REJECT',
      }),
    );
  });

  it('shows settled proposals as their own state, with no decision control', async () => {
    routeGets({
      proposals: (status) =>
        status === 'RETIRED' ? [proposal({ status: 'RETIRED', valueText: 'Retired friend' })] : [],
    });
    ui();

    fireEvent.click(await screen.findByRole('button', { name: 'Retired' }));

    expect(await screen.findByText(/Retired friend/)).toBeInTheDocument();
    expect(get).toHaveBeenCalledWith('/api/v1/memory/proposals', { params: { status: 'RETIRED' } });
    expect(screen.queryByRole('button', { name: 'Approve' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Reject' })).not.toBeInTheDocument();
  });

  it('keeps the confirmation open and reports a refused decision', async () => {
    routeGets({ proposals: (status) => (status === 'PENDING' ? [proposal()] : []) });
    post.mockRejectedValue({
      response: { status: 409, data: { message: 'proposal already decided' } },
    });
    ui();

    fireEvent.click(await screen.findByRole('button', { name: 'Approve' }));
    fireEvent.click(screen.getByRole('button', { name: 'Confirm' }));

    expect(await screen.findByText('proposal already decided')).toBeInTheDocument();
    expect(screen.getByRole('dialog', { name: /confirm memory decision/i })).toBeInTheDocument();
  });
});

describe('MemoryPage — entity detail and retirement', () => {
  it('opens the detail, lists its facts, and retires one through the confirmation', async () => {
    routeGets({ entities: () => [entity()] });
    post.mockResolvedValue({ data: fact({ status: 'RETIRED' }) } as never);
    ui();

    fireEvent.click(await screen.findByRole('button', { name: 'Open' }));

    expect(await screen.findByText('preferred_editor')).toBeInTheDocument();
    expect(screen.getByText('IntelliJ IDEA')).toBeInTheDocument();

    fireEvent.click(screen.getByRole('button', { name: /retire fact/i }));
    expect(
      screen.getByRole('dialog', { name: /retire fact/i }),
    ).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'Confirm' }));

    await waitFor(() =>
      expect(post).toHaveBeenCalledWith('/api/v1/memory/facts/f1/retire', {
        sourceRef: 'dashboard',
      }),
    );
  });

  it('retires an entity through a destructive confirmation (PATCH retire=true)', async () => {
    routeGets({ entities: () => [entity()] });
    ui();

    fireEvent.click(await screen.findByRole('button', { name: /retire entity/i }));
    expect(screen.getByRole('dialog', { name: /retire entity/i })).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'Confirm' }));

    await waitFor(() =>
      expect(patch).toHaveBeenCalledWith('/api/v1/memory/entities/e1', { retire: true }),
    );
  });
});
