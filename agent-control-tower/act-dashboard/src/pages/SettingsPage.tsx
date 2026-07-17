import { useState } from 'react';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { listProviders, createProvider, updateProvider, deleteProvider, testProvider, activateProvider } from '../api/llmProviders';
import type { LlmProvider, LlmProviderType, CreateLlmProviderRequest } from '../types';

interface ToastMessage {
  id: number;
  message: string;
  type: 'success' | 'error' | 'info';
}

interface EditState {
  open: boolean;
  provider: LlmProvider | null;
}

interface ConfirmDeleteState {
  open: boolean;
  id: string;
  name: string;
}

let toastCounter = 0;

const PROVIDER_TYPES: LlmProviderType[] = ['OPENAI', 'AZURE', 'ANTHROPIC', 'LOCAL'];

const emptyForm: CreateLlmProviderRequest = {
  name: '',
  type: 'OPENAI',
  baseUrl: '',
  apiKey: '',
  defaultModel: '',
  maxTokens: 4096,
};

export function SettingsPage() {
  const queryClient = useQueryClient();
  const [showForm, setShowForm] = useState(false);
  const [editState, setEditState] = useState<EditState>({ open: false, provider: null });
  const [confirmDelete, setConfirmDelete] = useState<ConfirmDeleteState>({ open: false, id: '', name: '' });
  const [toasts, setToasts] = useState<ToastMessage[]>([]);
  const [formErrors, setFormErrors] = useState<Record<string, string>>({});
  const [form, setForm] = useState<CreateLlmProviderRequest>(emptyForm);
  const [testingId, setTestingId] = useState<string | null>(null);

  const { data: providers, isLoading, error } = useQuery({
    queryKey: ['llm-providers'],
    queryFn: listProviders,
  });

  const createMutation = useMutation({
    mutationFn: createProvider,
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['llm-providers'] });
      setShowForm(false);
      setForm(emptyForm);
      setFormErrors({});
      addToast('Provider created successfully', 'success');
    },
    onError: () => addToast('Failed to create provider', 'error'),
  });

  const updateMutation = useMutation({
    mutationFn: ({ id, req }: { id: string; req: Partial<CreateLlmProviderRequest> }) => updateProvider(id, req),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['llm-providers'] });
      setEditState({ open: false, provider: null });
      setForm(emptyForm);
      setFormErrors({});
      addToast('Provider updated successfully', 'success');
    },
    onError: () => addToast('Failed to update provider', 'error'),
  });

  const deleteMutation = useMutation({
    mutationFn: deleteProvider,
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['llm-providers'] });
      setConfirmDelete({ open: false, id: '', name: '' });
      addToast('Provider deleted', 'success');
    },
    onError: () => addToast('Failed to delete provider', 'error'),
  });

  const activateMutation = useMutation({
    mutationFn: activateProvider,
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['llm-providers'] });
      addToast('Provider activated', 'success');
    },
    onError: () => addToast('Failed to activate provider', 'error'),
  });

  const addToast = (message: string, type: ToastMessage['type']) => {
    const id = ++toastCounter;
    setToasts((prev) => [...prev.slice(-4), { id, message, type }]);
    setTimeout(() => setToasts((prev) => prev.filter((t) => t.id !== id)), 4000);
  };

  const validateForm = (f: CreateLlmProviderRequest): boolean => {
    const errors: Record<string, string> = {};
    if (!f.name.trim()) errors.name = 'Name is required';
    if (!f.type) errors.type = 'Type is required';
    if (!f.baseUrl.trim()) errors.baseUrl = 'Base URL is required';
    if (!editState.open && !f.apiKey?.trim()) errors.apiKey = 'API Key is required';
    if (!f.defaultModel.trim()) errors.defaultModel = 'Model is required';
    setFormErrors(errors);
    return Object.keys(errors).length === 0;
  };

  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault();
    if (!validateForm(form)) return;
    if (editState.open && editState.provider) {
      const req: Partial<CreateLlmProviderRequest> = { ...form };
      if (!req.apiKey?.trim()) delete req.apiKey;
      updateMutation.mutate({ id: editState.provider.id, req });
    } else {
      createMutation.mutate(form);
    }
  };

  const handleEdit = (provider: LlmProvider) => {
    setForm({
      name: provider.name,
      type: provider.type,
      baseUrl: provider.baseUrl || '',
      apiKey: '',
      defaultModel: provider.defaultModel || '',
      maxTokens: provider.defaultMaxTokens,
    });
    setFormErrors({});
    setEditState({ open: true, provider });
  };

  const handleTest = async (id: string) => {
    setTestingId(id);
    try {
      const result = await testProvider(id);
      addToast(result.message, result.success ? 'success' : 'error');
    } catch {
      addToast('Connection test failed', 'error');
    } finally {
      setTestingId(null);
    }
  };

  const handleCancel = () => {
    setShowForm(false);
    setEditState({ open: false, provider: null });
    setForm(emptyForm);
    setFormErrors({});
  };

  const activeProvider = providers?.find((p) => p.active);

  return (
    <div className="page">
      <div className="page-header">
        <h2>Settings</h2>
        <button className="btn btn-primary" onClick={() => { setShowForm(true); setEditState({ open: false, provider: null }); setForm(emptyForm); setFormErrors({}); }}>
          + Add Provider
        </button>
      </div>

      {/* Active Provider Info */}
      {activeProvider && (
        <div className="card" style={{ marginBottom: '1rem', borderColor: '#22c55e' }}>
          <div style={{ display: 'flex', alignItems: 'center', gap: '0.5rem' }}>
            <span style={{ color: '#22c55e', fontWeight: 700 }}>● Active Provider</span>
            <span style={{ fontWeight: 600 }}>{activeProvider.name}</span>
            <span className="type-badge">{activeProvider.type}</span>
            <span style={{ color: 'var(--text-muted)', fontSize: '0.85rem' }}>
              {activeProvider.defaultModel} @ {activeProvider.baseUrl}
            </span>
          </div>
        </div>
      )}

      {/* Fallback YAML Config Notice */}
      {!activeProvider && (
        <div className="card" style={{ marginBottom: '1rem', borderColor: 'var(--border-color)', background: 'var(--bg-secondary)' }}>
          <span style={{ fontWeight: 600 }}>No active provider configured.</span>
          <span style={{ color: 'var(--text-muted)', marginLeft: '0.5rem' }}>
            The system is using the default YAML configuration (llm.provider.* properties) as fallback.
          </span>
        </div>
      )}

      {/* Create/Edit Form */}
      {(showForm || editState.open) && (
        <form className="card form-card" onSubmit={handleSubmit}>
          <h3 className="form-title">
            {editState.open ? `Edit Provider: ${editState.provider?.name}` : 'Add LLM Provider'}
          </h3>
          <div className="form-grid">
            <div className={`form-field ${formErrors.name ? 'field-error' : ''}`}>
              <label>Name *</label>
              <input value={form.name} onChange={(e) => setForm({ ...form, name: e.target.value })} />
              {formErrors.name && <span className="error-text">{formErrors.name}</span>}
            </div>
            <div className={`form-field ${formErrors.type ? 'field-error' : ''}`}>
              <label>Type *</label>
              <select value={form.type} onChange={(e) => setForm({ ...form, type: e.target.value as LlmProviderType })}>
                {PROVIDER_TYPES.map((t) => <option key={t} value={t}>{t}</option>)}
              </select>
              {formErrors.type && <span className="error-text">{formErrors.type}</span>}
            </div>
            <div className={`form-field ${formErrors.baseUrl ? 'field-error' : ''}`}>
              <label>Base URL *</label>
              <input value={form.baseUrl} onChange={(e) => setForm({ ...form, baseUrl: e.target.value })} placeholder="https://api.openai.com/v1" />
              {formErrors.baseUrl && <span className="error-text">{formErrors.baseUrl}</span>}
            </div>
            <div className={`form-field ${formErrors.apiKey ? 'field-error' : ''}`}>
              <label>API Key {editState.open ? '(leave empty to keep current)' : '*'}</label>
              <input type="password" value={form.apiKey} onChange={(e) => setForm({ ...form, apiKey: e.target.value })} />
              {formErrors.apiKey && <span className="error-text">{formErrors.apiKey}</span>}
            </div>
            <div className={`form-field ${formErrors.defaultModel ? 'field-error' : ''}`}>
              <label>Default Model *</label>
              <input value={form.defaultModel} onChange={(e) => setForm({ ...form, defaultModel: e.target.value })} placeholder="gpt-4" />
              {formErrors.defaultModel && <span className="error-text">{formErrors.defaultModel}</span>}
            </div>
            <div className="form-field">
              <label>Max Tokens</label>
              <input type="number" value={form.maxTokens} onChange={(e) => setForm({ ...form, maxTokens: parseInt(e.target.value) || 4096 })} />
            </div>
          </div>
          <div className="form-actions">
            <button className="btn btn-primary" type="submit" disabled={createMutation.isPending || updateMutation.isPending}>
              {createMutation.isPending || updateMutation.isPending ? 'Saving...' : editState.open ? 'Update' : 'Create'}
            </button>
            <button className="btn" type="button" onClick={handleCancel}>Cancel</button>
          </div>
        </form>
      )}

      {isLoading && <div className="loading-spinner"><div className="spinner" /><span>Loading providers...</span></div>}
      {error && <div className="error-state">Failed to load providers.</div>}

      {providers && providers.length === 0 && !isLoading && (
        <div className="empty-state">No LLM providers configured. Click "Add Provider" to get started.</div>
      )}

      {providers && providers.length > 0 && (
        <div className="table-wrapper">
          <table className="data-table">
            <thead>
              <tr>
                <th>Name</th>
                <th>Type</th>
                <th>Base URL</th>
                <th>Model</th>
                <th>API Key</th>
                <th>Status</th>
                <th>Actions</th>
              </tr>
            </thead>
            <tbody>
              {providers.map((provider) => (
                <tr key={provider.id} className={provider.active ? 'row-active' : ''}>
                  <td className="cell-primary">{provider.name}</td>
                  <td><span className="type-badge">{provider.type}</span></td>
                  <td className="cell-mono" style={{ fontSize: '0.85rem' }}>{provider.baseUrl}</td>
                  <td>{provider.defaultModel}</td>
                  <td className="cell-mono">{provider.apiKeyMasked}</td>
                  <td>
                    {provider.active
                      ? <span style={{ color: '#22c55e', fontWeight: 600 }}>Active</span>
                      : <span style={{ color: 'var(--text-muted)' }}>Inactive</span>
                    }
                  </td>
                  <td className="action-cell">
                    {!provider.active && (
                      <button className="btn btn-sm btn-success" onClick={() => activateMutation.mutate(provider.id)} disabled={activateMutation.isPending}>
                        Activate
                      </button>
                    )}
                    <button className="btn btn-sm" onClick={() => handleTest(provider.id)} disabled={testingId === provider.id}>
                      {testingId === provider.id ? 'Testing...' : 'Test'}
                    </button>
                    <button className="btn btn-sm" onClick={() => handleEdit(provider)}>Edit</button>
                    <button className="btn btn-sm btn-danger" onClick={() => setConfirmDelete({ open: true, id: provider.id, name: provider.name })}>
                      Delete
                    </button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      {/* Confirm Delete Dialog */}
      {confirmDelete.open && (
        <div className="modal-overlay" onClick={() => setConfirmDelete({ open: false, id: '', name: '' })}>
          <div className="modal-dialog" onClick={(e) => e.stopPropagation()}>
            <h3>Confirm Delete</h3>
            <p>Are you sure you want to delete <strong>{confirmDelete.name}</strong>?</p>
            <div className="modal-actions">
              <button className="btn btn-danger" onClick={() => deleteMutation.mutate(confirmDelete.id)} disabled={deleteMutation.isPending}>
                {deleteMutation.isPending ? 'Deleting...' : 'Delete'}
              </button>
              <button className="btn" onClick={() => setConfirmDelete({ open: false, id: '', name: '' })}>Cancel</button>
            </div>
          </div>
        </div>
      )}

      {/* Toasts */}
      {toasts.length > 0 && (
        <div className="toast-container" style={{ position: 'fixed', bottom: '1rem', right: '1rem', zIndex: 1000 }}>
          {toasts.map((toast) => (
            <div key={toast.id} className="toast-item" style={{
              background: toast.type === 'success' ? '#22c55e' : toast.type === 'error' ? '#ef4444' : '#3b82f6',
              color: 'white',
              padding: '0.5rem 1rem',
              borderRadius: '0.375rem',
              marginBottom: '0.5rem',
            }}>
              {toast.message}
            </div>
          ))}
        </div>
      )}
    </div>
  );
}
