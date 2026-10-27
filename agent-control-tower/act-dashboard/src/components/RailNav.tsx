import { Link, useLocation } from 'react-router-dom';

interface RailItem {
  path: string;
  label: string;
  icon: string;
}

const ITEMS: RailItem[] = [
  { path: '/', label: 'Overview', icon: '▦' },
  { path: '/crew', label: 'Crew', icon: '👥' },
  { path: '/providers', label: 'Providers', icon: '🔄' },
  { path: '/runs', label: 'Runs', icon: '▶️' },
  { path: '/knowledge', label: 'Knowledge', icon: '📚' },
  { path: '/reports', label: 'Reports', icon: '📊' },
  { path: '/chat', label: 'Chat', icon: '💬' },
  { path: '/workflows', label: 'Workflows', icon: '🔗' },
  { path: '/ops', label: 'Ops', icon: '🛡️' },
  { path: '/scheduled-jobs', label: 'Jobs', icon: '📅' },
];

export function RailNav() {
  const location = useLocation();

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
        // Real links, not buttons: middle-click, open-in-new-tab, focus order
        // and assistive-tech semantics come free; aria-current names the
        // current view. Task 8.
        <Link
          key={item.path}
          to={item.path}
          className={`rail-btn${isActive(item.path) ? ' active' : ''}`}
          data-view={item.label.toLowerCase()}
          aria-current={isActive(item.path) ? 'page' : undefined}
          style={{ textDecoration: 'none' }}
        >
          <span className="ico" aria-hidden="true">
            {item.icon}
          </span>
          {item.label}
        </Link>
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
