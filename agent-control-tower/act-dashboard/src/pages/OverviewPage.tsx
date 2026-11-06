import ExecutiveSummary from '../components/ExecutiveSummary';
import KanbanBoard from '../components/KanbanBoard';
import AgentTeam from '../components/AgentTeam';
import ReviewQueue from '../components/ReviewQueue';
import ActivityTimeline from '../components/ActivityTimeline';
import MorningBriefing from '../components/MorningBriefing';
import { ReviewWorkspace } from '../components/ReviewWorkspace';
import { useDrawerContext } from '../components/DrawerContext';

export default function OverviewPage() {
  const { state } = useDrawerContext();
  // Spec 10.3 in-place expand: while a review target is set, the
  // ReviewWorkspace is rendered inside the main column (no fixed overlay) and
  // the widgets reflow into a single bottom strip. The grid column change is
  // animated via the .layout transition rule in styles/index.css.
  const reviewId = state.reviewTargetId;
  return (
    <div className="view-zone" data-view="overview">
      <div
        className="layout"
        style={{
          gridTemplateColumns: reviewId
            ? 'minmax(0, 1fr)'
            : 'minmax(0, 1fr) minmax(320px, 380px)',
        }}
      >
        <div className="col">
          {reviewId ? (
            <ReviewWorkspace itemId={reviewId} />
          ) : (
            <>
              <ExecutiveSummary />
              <KanbanBoard />
              <MorningBriefing />
            </>
          )}
        </div>
        {!reviewId && (
          <div className="col">
            <AgentTeam />
            <ReviewQueue />
            <ActivityTimeline />
          </div>
        )}
      </div>
      {reviewId && (
        // D5: widgets reflow to the bottom while the review workspace is in
        // place. The strip grows with its content (a fixed height clipped the
        // widgets) — the page scrolls when the four columns need more room.
        <div
          className="expanded-strip layout"
          style={{ gridTemplateColumns: 'repeat(4, minmax(240px, 1fr))' }}
        >
          <AgentTeam />
          <ReviewQueue />
          <ActivityTimeline />
          <MorningBriefing />
        </div>
      )}
    </div>
  );
}
