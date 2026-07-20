import { useLocation, useNavigate } from 'react-router-dom';

interface RailItem {
  path: string;
  label: string;
  icon: string;
}

const ITEMS: RailItem[] = [
  { path: '/', label: 'Overview', icon: '▦' },
  { path: '/crew', label: 'Crew', icon: '👥' },
  { path: '/runs', label: 'Runs', icon: '▶️' },
  { path: '/knowledge', label: 'Knowledge', icon: '📚' },
  { path: '/reports', label: 'Reports', icon: '📊' },
  { path: '/chat', label: 'Chat', icon: '💬' },
  { path: '/workflows', label: 'Workflows', icon: '🔗' },
  { path: '/approvals', label: 'Approvals', icon: '✅' },
  { path: '/ops', label: 'Ops', icon: '🛡️' },
  { path: '/scheduled-jobs', label: 'Jobs', icon: '📅' },
];

export function RailNav() {
  const location = useLocation();
  const navigate = useNavigate();

  const isActive = (path: string) => {
    if (path === '/') return location.pathname === '/';
    return location.pathname.startsWith(path);
  };

  const openConfigure = () => {
    window.dispatchEvent(new CustomEvent('act:open-configure'));
    document.body.classList.add('configure-open');
  };

  return (
    <aside className="rail" role="navigation" aria-label="Main views">
      {ITEMS.map((item) => (
        <button
          key={item.path}
          type="button"
          className={`rail-btn${isActive(item.path) ? ' active' : ''}`}
          data-view={item.label.toLowerCase()}
          onClick={() => navigate(item.path)}
        >
          <span className="ico" aria-hidden="true">
            {item.icon}
          </span>
          {item.label}
        </button>
      ))}

      <div className="rail-sep" />

      <button
        type="button"
        className="rail-btn"
        onClick={openConfigure}
        aria-label="Open configuration"
      >
        <span className="ico" aria-hidden="true">
          🔧
        </span>
        Configure
      </button>
    </aside>
  );
}

export default RailNav;
