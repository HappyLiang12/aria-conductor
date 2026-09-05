import { useState, useEffect } from 'react';
import { withAuthHeaders } from '../api/auth';

interface Props { agentId: string; role: string; }
interface ToolDef { id: string; name: string; tier: string; enabled: boolean; }

export default function AgentToolPanel({ agentId, role }: Props) {
  const [tools, setTools] = useState<ToolDef[]>([]);
  const [assigned, setAssigned] = useState<Set<string>>(new Set());
  const [defaults, setDefaults] = useState<Set<string>>(new Set());
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    Promise.all([
      fetch('/api/v1/tools', { headers: withAuthHeaders() }).then(r => r.json()),
      fetch(`/api/v1/agents/${agentId}/tools`, { headers: withAuthHeaders() }).then(r => r.json()),
      fetch(`/api/v1/roles/${role}/tools`, { headers: withAuthHeaders() }).then(r => r.json()),
    ]).then(([t, a, d]) => {
      setTools(t);
      setAssigned(new Set(a.map((x: ToolDef) => x.id)));
      setDefaults(new Set(d.map((x: ToolDef) => x.id)));
      setLoading(false);
    });
  }, [agentId, role]);

  const toggle = async (id: string) => {
    const m = assigned.has(id) ? 'DELETE' : 'POST';
    await fetch(`/api/v1/agents/${agentId}/tools/${id}`, { method: m, headers: withAuthHeaders() });
    setAssigned(p => { const n = new Set(p); assigned.has(id) ? n.delete(id) : n.add(id); return n; });
  };

  const reset = async () => {
    await fetch(`/api/v1/agents/${agentId}/tools`, { method: 'PUT', headers: withAuthHeaders({'Content-Type':'application/json'}), body: JSON.stringify({ toolIds: [...defaults] }) });
    setAssigned(defaults);
  };

  if (loading) return <div>Loading...</div>;
  return (
    <div>
      <h2>Tool Assignment</h2>
      <button onClick={reset}>Reset to {role} defaults</button>
      {['TIER_1','TIER_2','TIER_3'].map(tier => (
        <div key={tier}><h3>{tier}</h3>
          {tools.filter(t => t.tier === tier).map(t => (
            <label key={t.id}><input type="checkbox" checked={assigned.has(t.id)} onChange={() => toggle(t.id)} />{t.name}{defaults.has(t.id) && ' (default)'}</label>
          ))}
        </div>
      ))}
    </div>
  );
}
