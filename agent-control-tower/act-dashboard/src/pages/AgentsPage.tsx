import { useState, useEffect } from 'react';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { listAgents, createAgent, updateAgent, retireAgent, getTemplates } from '../api/agents';
import { useWebSocket } from '../hooks/useWebSocket';
import { StatusBadge } from '../components/StatusBadge';
import type { CreateAgentRequest, AgentType, AgentHealthStatus, Agent, AgentTemplate } from '../types';

interface ConfirmDialogState {
  open: boolean;
  agentId: string;
  agentName: string;
}

interface EditDialogState {
  open: boolean;
  agent: Agent | null;
}

interface DetailDialogState {
  open: boolean;
  agent: Agent | null;
}

export function AgentsPage() {
  const queryClient = useQueryClient();
  const { lastMessage } = useWebSocket();
  const [showForm, setShowForm] = useState(false);
  const [selectedTemplate, setSelectedTemplate] = useState<string>('');
  const [filterHealth, setFilterHealth] = useState<AgentHealthStatus | ''>('');
  const [filterType, setFilterType] = useState<AgentType | ''>('');
  const [confirmDialog, setConfirmDialog] = useState<ConfirmDialogState>({ open: false, agentId: '', agentName: '' });
  const [editDialog, setEditDialog] = useState<EditDialogState>({ open: false, agent: null });
  const [detailDialog, setDetailDialog] = useState<DetailDialogState>({ open: false, agent: null });
  const [formErrors, setFormErrors] = useState<Record<string, string>>({});

  const [form, setForm] = useState<CreateAgentRequest>({
    name: '',
    agentType: 'NATIVE',
    description: '',
    role: '',
    model: '',
    provider: '',
    adkProvider: 'langchain',
    config: { maxToolCallRounds: 50 },
  });

  const [editForm, setEditForm] = useState<CreateAgentRequest>({
    name: '',
    agentType: 'NATIVE',
    description: '',
    role: '',
    model: '',
    provider: '',
    adkProvider: 'langchain',
  });

  const { data: agents, isLoading, error } = useQuery({
    queryKey: ['agents'],
    queryFn: listAgents,
  });

  const { data: templates } = useQuery({
    queryKey: ['agent-templates'],
    queryFn: getTemplates,
  });

  const createMutation = useMutation({
    mutationFn: createAgent,
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['agents'] });
      setShowForm(false);
      resetForm();
    },
  });

  const updateMutation = useMutation({
    mutationFn: ({ id, req }: { id: string; req: Partial<CreateAgentRequest> }) => updateAgent(id, req),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['agents'] });
      setEditDialog({ open: false, agent: null });
    },
  });

  const retireMutation = useMutation({
    mutationFn: retireAgent,
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['agents'] });
      setConfirmDialog({ open: false, agentId: '', agentName: '' });
    },
  });

  useEffect(() => {
    if (lastMessage?.type.startsWith('agent.')) {
      queryClient.invalidateQueries({ queryKey: ['agents'] });
    }
  }, [lastMessage, queryClient]);

  const resetForm = () => {
    setForm({ name: '', agentType: 'NATIVE', description: '', role: '', model: '', provider: '', adkProvider: 'langchain' });
    setSelectedTemplate('');
    setFormErrors({});
  };

  const applyTemplate = (templateId: string) => {
    setSelectedTemplate(templateId);
    if (!templateId) return;
    const template = templates?.find((t) => t.id === templateId);
    if (template) {
      setForm({
        name: form.name || '',
        agentType: template.agentType,
        role: template.role,
        model: template.model,
        provider: template.provider,
        adkProvider: template.adkProvider || 'langchain',
        description: template.description,
      });
    }
  };

  const validateForm = (f: CreateAgentRequest): boolean => {
    const errors: Record<string, string> = {};
    if (!f.name.trim()) errors.name = 'Name is required';
    if (!f.agentType) errors.agentType = 'Type is required';
    setFormErrors(errors);
    return Object.keys(errors).length === 0;
  };

  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault();
    if (!validateForm(form)) return;
    createMutation.mutate(form);
  };

  const handleEditSubmit = (e: React.FormEvent) => {
    e.preventDefault();
    if (!editDialog.agent) return;
    if (!validateForm(editForm)) return;
    updateMutation.mutate({ id: editDialog.agent.id, req: editForm });
  };

  const handleRetire = () => {
    if (confirmDialog.agentId) {
      retireMutation.mutate(confirmDialog.agentId);
    }
  };

  const filteredAgents = agents?.filter((agent) => {
    if (filterHealth && agent.healthStatus !== filterHealth) return false;
    if (filterType && agent.agentType !== filterType) return false;
    return true;
  }) ?? [];

  return (
    <div className="page">
      <div className="page-header">
        <h2>Agents</h2>
        <button className="btn btn-primary" onClick={() => setShowForm(!showForm)}>
          {showForm ? 'Cancel' : '+ Create Agent'}
        </button>
      </div>

      {/* Filters */}
      <div className="filter-bar">
        <select value={filterHealth} onChange={(e) => setFilterHealth(e.target.value as AgentHealthStatus | '')}>
          <option value="">All Health Status</option>
          <option value="HEALTHY">HEALTHY</option>
          <option value="DEGRADED">DEGRADED</option>
          <option value="UNHEALTHY">UNHEALTHY</option>
          <option value="RETIRED">RETIRED</option>
        </select>
        <select value={filterType} onChange={(e) => setFilterType(e.target.value as AgentType | '')}>
          <option value="">All Types</option>
          <option value="NATIVE">NATIVE</option>
          <option value="ADK">ADK</option>
        </select>
      </div>

      {/* Create Agent Form */}
      {showForm && (
        <form className="card form-card" onSubmit={handleSubmit}>
          <h3 className="form-title">Create New Agent</h3>
          <div className="form-field">
            <label>From Template</label>
            <select value={selectedTemplate} onChange={(e) => applyTemplate(e.target.value)}>
              <option value="">— None —</option>
              {templates?.map((t) => (
                <option key={t.id} value={t.id}>{t.label}</option>
              ))}
            </select>
          </div>
          <div className="form-grid">
            <div className={`form-field ${formErrors.name ? 'field-error' : ''}`}>
              <label>Name *</label>
              <input value={form.name} onChange={(e) => setForm({ ...form, name: e.target.value })} />
              {formErrors.name && <span className="error-text">{formErrors.name}</span>}
            </div>
            <div className={`form-field ${formErrors.agentType ? 'field-error' : ''}`}>
              <label>Type *</label>
              <select value={form.agentType} onChange={(e) => setForm({ ...form, agentType: e.target.value as AgentType })}>
                <option value="NATIVE">NATIVE</option>
                <option value="ADK">ADK</option>
              </select>
              {formErrors.agentType && <span className="error-text">{formErrors.agentType}</span>}
            </div>
            <div className="form-field">
              <label>Role</label>
              <input value={form.role} onChange={(e) => setForm({ ...form, role: e.target.value })} />
            </div>
            <div className="form-field">
              <label>Model</label>
              <input value={form.model} onChange={(e) => setForm({ ...form, model: e.target.value })} />
            </div>
            <div className="form-field">
              <label>Provider</label>
              <input value={form.provider} onChange={(e) => setForm({ ...form, provider: e.target.value })} />
            </div>
            <div className="form-field">
              <label>ADK Provider</label>
              <select value={form.adkProvider} onChange={(e) => setForm({ ...form, adkProvider: e.target.value })}>
                <option value="langchain">LangChain</option>
                <option value="langchain">LangChain ADK</option>
              </select>
            </div>
            <div className="form-field full-width">
              <label>Description</label>
              <textarea value={form.description} onChange={(e) => setForm({ ...form, description: e.target.value })} rows={3} />
            </div>
          </div>
          <div className="form-actions">
            <button className="btn btn-primary" type="submit" disabled={createMutation.isPending}>
              {createMutation.isPending ? 'Creating...' : 'Create Agent'}
            </button>
            <button className="btn" type="button" onClick={() => { setShowForm(false); resetForm(); }}>Cancel</button>
          </div>
        </form>
      )}

      {/* Loading / Error / Empty States */}
      {isLoading && <div className="loading-spinner"><div className="spinner" /><span>Loading agents...</span></div>}
      {error && <div className="error-state">Failed to load agents. Please retry.</div>}

      {filteredAgents.length === 0 && !isLoading && (
        <div className="empty-state">
          {agents?.length === 0 ? 'No agents registered yet. Create one above.' : 'No agents match your filter criteria.'}
        </div>
      )}

      {/* Table */}
      {filteredAgents.length > 0 && (
        <div className="table-wrapper">
          <table className="data-table">
            <thead>
              <tr>
                <th>Name</th>
                <th>Type</th>
                <th>Role</th>
                <th>Model</th>
                <th>Health</th>
                <th>Created</th>
                <th>Actions</th>
              </tr>
            </thead>
            <tbody>
              {filteredAgents.map((agent) => (
                <tr key={agent.id}>
                  <td className="cell-primary">{agent.name}</td>
                  <td><span className="type-badge">{agent.agentType}</span></td>
                  <td>{agent.role || '—'}</td>
                  <td>{agent.model || '—'}</td>
                  <td><StatusBadge status={agent.healthStatus} /></td>
                  <td>{new Date(agent.createdAt).toLocaleDateString()}</td>
                  <td className="action-cell">
                    <button className="btn btn-sm" onClick={() => setDetailDialog({ open: true, agent })}>Details</button>
                    {agent.healthStatus !== 'RETIRED' && (
                      <button className="btn btn-sm" onClick={() => {
                        setEditDialog({ open: true, agent });
                        setEditForm({
                          name: agent.name,
                          agentType: agent.agentType,
                          role: agent.role,
                          model: agent.model,
                          provider: agent.provider,
                          adkProvider: agent.adkProvider || 'langchain',
                          description: agent.description,
                        });
                      }}>Edit</button>
                    )}
                    {agent.healthStatus !== 'RETIRED' && (
                      <button className="btn btn-sm btn-danger" onClick={() => setConfirmDialog({ open: true, agentId: agent.id, agentName: agent.name })}>Retire</button>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      {/* Confirm Retire Dialog */}
      {confirmDialog.open && (
        <div className="modal-overlay" onClick={() => setConfirmDialog({ open: false, agentId: '', agentName: '' })}>
          <div className="modal-dialog" onClick={(e) => e.stopPropagation()}>
            <h3>Confirm Retire Agent</h3>
            <p>Are you sure you want to retire <strong>{confirmDialog.agentName}</strong>? This action cannot be undone.</p>
            <div className="modal-actions">
              <button className="btn btn-danger" onClick={handleRetire} disabled={retireMutation.isPending}>
                {retireMutation.isPending ? 'Retiring...' : 'Retire Agent'}
              </button>
              <button className="btn" onClick={() => setConfirmDialog({ open: false, agentId: '', agentName: '' })}>Cancel</button>
            </div>
          </div>
        </div>
      )}

      {/* Edit Agent Dialog */}
      {editDialog.open && editDialog.agent && (
        <div className="modal-overlay" onClick={() => setEditDialog({ open: false, agent: null })}>
          <div className="modal-dialog modal-wide" onClick={(e) => e.stopPropagation()}>
            <h3>Edit Agent: {editDialog.agent.name}</h3>
            <form onSubmit={handleEditSubmit}>
              <div className="form-grid">
                <div className={`form-field ${formErrors.name ? 'field-error' : ''}`}>
                  <label>Name *</label>
                  <input value={editForm.name} onChange={(e) => setEditForm({ ...editForm, name: e.target.value })} />
                  {formErrors.name && <span className="error-text">{formErrors.name}</span>}
                </div>
                <div className="form-field">
                  <label>Role</label>
                  <input value={editForm.role} onChange={(e) => setEditForm({ ...editForm, role: e.target.value })} />
                </div>
                <div className="form-field">
                  <label>Model</label>
                  <input value={editForm.model} onChange={(e) => setEditForm({ ...editForm, model: e.target.value })} />
                </div>
                <div className="form-field">
                  <label>Provider</label>
                  <input value={editForm.provider} onChange={(e) => setEditForm({ ...editForm, provider: e.target.value })} />
                </div>
                <div className="form-field">
                  <label>ADK Provider</label>
                  <select value={editForm.adkProvider} onChange={(e) => setEditForm({ ...editForm, adkProvider: e.target.value })}>
                    <option value="langchain">LangChain</option>
                    <option value="langchain">LangChain ADK</option>
                  </select>
                </div>
                <div className="form-field full-width">
                  <label>Description</label>
                  <textarea value={editForm.description} onChange={(e) => setEditForm({ ...editForm, description: e.target.value })} rows={3} />
                </div>
              </div>
              <div className="modal-actions">
                <button className="btn btn-primary" type="submit" disabled={updateMutation.isPending}>
                  {updateMutation.isPending ? 'Saving...' : 'Save Changes'}
                </button>
                <button className="btn" type="button" onClick={() => setEditDialog({ open: false, agent: null })}>Cancel</button>
              </div>
            </form>
          </div>
        </div>
      )}

      {/* Detail Dialog */}
      {detailDialog.open && detailDialog.agent && (
        <div className="modal-overlay" onClick={() => setDetailDialog({ open: false, agent: null })}>
          <div className="modal-dialog modal-wide" onClick={(e) => e.stopPropagation()}>
            <h3>Agent Details: {detailDialog.agent.name}</h3>
            <div className="detail-grid">
              <div className="detail-item"><span className="detail-label">ID</span><span className="cell-mono">{detailDialog.agent.id}</span></div>
              <div className="detail-item"><span className="detail-label">Type</span><span className="type-badge">{detailDialog.agent.agentType}</span></div>
              <div className="detail-item"><span className="detail-label">Role</span><span>{detailDialog.agent.role || '—'}</span></div>
              <div className="detail-item"><span className="detail-label">Model</span><span>{detailDialog.agent.model || '—'}</span></div>
              <div className="detail-item"><span className="detail-label">Provider</span><span>{detailDialog.agent.provider || '—'}</span></div>
              <div className="detail-item"><span className="detail-label">ADK Provider</span><span>{detailDialog.agent.adkProvider || '—'}</span></div>
              <div className="detail-item"><span className="detail-label">Health</span><StatusBadge status={detailDialog.agent.healthStatus} /></div>
              <div className="detail-item"><span className="detail-label">Created</span><span>{new Date(detailDialog.agent.createdAt).toLocaleString()}</span></div>
              <div className="detail-item detail-full"><span className="detail-label">Description</span><span>{detailDialog.agent.description || '—'}</span></div>
            </div>
            <div className="modal-actions">
              <button className="btn" onClick={() => setDetailDialog({ open: false, agent: null })}>Close</button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}