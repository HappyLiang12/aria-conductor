import { Link } from 'react-router-dom';

export default function NotFoundPage() {
  return (
    <div className="panel" data-testid="not-found">
      <h2>Page not found</h2>
      <p>There is no view at this address.</p>
      <Link className="btn primary" to="/">
        Back to Overview
      </Link>
    </div>
  );
}
