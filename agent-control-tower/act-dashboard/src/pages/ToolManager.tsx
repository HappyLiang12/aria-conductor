import { useState, useEffect } from 'react';

interface ToolDef {
  id: string; name: string; displayName: string; description: string;
  tier: string; category: string; sandboxMode: string; enabled: boolean;
}

export default function ToolManager() {
  const [tools, setTools] = useState<ToolDef[]>([]);
  const [filter, setFilter] = useState('');

  useEffect(() => {
    fetch('/api/v1/tools').then(r => r.json()).then(setTools);
  }, []);

  const toggleTool = async (id: string) => {
    await fetch(`/api/v1/tools/${id}/toggle`, { method: 'POST' });
    setTools(prev => prev.map(t => t.id === id ? { ...t, enabled: !t.enabled } : t));
  };

  const filtered = filter ? tools.filter(t => t.tier === filter) : tools;

  return (
    <div className="tool-manager">
      <h1>Tool Manager</h1>
      <div className="filters">
        {['TIER_1','TIER_2','TIER_3'].map(t => (
          <button key={t} onClick={() => setFilter(t)}>{t}</button>
        ))}
        <button onClick={() => setFilter('')}>All</button>
      </div>
      <table>
        <thead><tr><th>Name</th><th>Tier</th><th>Category</th><th>Sandbox</th><th>Status</th><th>Actions</th></tr></thead>
        <tbody>
          {filtered.map(t => (
            <tr key={t.id}>
              <td>{t.name}</td><td>{t.tier}</td><td>{t.category}</td><td>{t.sandboxMode}</td>
              <td>{t.enabled ? 'Active' : 'Disabled'}</td>
              <td><button onClick={() => toggleTool(t.id)}>{t.enabled ? 'Disable' : 'Enable'}</button></td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
