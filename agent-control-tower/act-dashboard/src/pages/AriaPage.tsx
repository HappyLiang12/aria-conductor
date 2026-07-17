/**
 * AriaPage — kept as a minimal placeholder.
 *
 * The dedicated Aria page is being decommissioned in favour of the global
 * floating panel ({@link ../components/AriaPanel}), which is summonable from
 * any route. This stub stays in place so existing route registrations don't
 * 404 while another task removes the route entry. Anything operators need to
 * do with Aria now happens through the FAB in the bottom-right corner.
 */
export function AriaPage() {
  return (
    <div className="page aria-page">
      <div className="page-header">
        <h2>Aria</h2>
      </div>
      <div className="card" style={{ textAlign: 'center', padding: '2.5rem 1.5rem' }}>
        <p style={{ color: 'var(--text-muted)', fontSize: '0.9rem', lineHeight: 1.6 }}>
          Aria has moved to the floating panel — look for the sparkle button in
          the bottom-right corner of any page.
        </p>
      </div>
    </div>
  );
}

export default AriaPage;
