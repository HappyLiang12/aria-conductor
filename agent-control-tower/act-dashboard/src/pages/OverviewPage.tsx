import ExecutiveSummary from '../components/ExecutiveSummary';
import KanbanBoard from '../components/KanbanBoard';
import AgentTeam from '../components/AgentTeam';
import ReviewQueue from '../components/ReviewQueue';
import ActivityTimeline from '../components/ActivityTimeline';
import MorningBriefing from '../components/MorningBriefing';
import { useDrawerContext } from '../components/DrawerContext';

export default function OverviewPage() {
  const { state } = useDrawerContext();
  // Full-page review mode: the review workspace takes over; widgets reflow to
  // a single bottom strip instead of sharing the screen with the board.
  const expanded = state.taskDrawer.open && state.reviewExpanded;
  return (
    <div className="view-zone" data-view="overview">
      <div
        className="layout"
        style={{
          gridTemplateColumns: expanded
            ? 'minmax(0, 1fr)'
            : 'minmax(0, 1fr) minmax(320px, 380px)',
        }}
      >
        <div className="col">
          <ExecutiveSummary />
          <KanbanBoard />
          {!expanded && <MorningBriefing />}
        </div>
        {!expanded && (
          <div className="col">
            <AgentTeam />
            <ReviewQueue />
            <ActivityTimeline />
          </div>
        )}
      </div>
      {expanded && (
        // D5: widgets reflow to the bottom while the review is expanded. The
        // strip gets a deterministic height so .review-fullpage (fixed overlay)
        // can end above it — see the bottom: 196px rule in styles/index.css;
        // keep the 180px height + 16px gap in sync with that value.
        <div
          className="expanded-strip layout"
          style={{
            gridTemplateColumns: 'repeat(4, minmax(0, 1fr))',
            height: 180,
            overflowY: 'auto',
            boxSizing: 'border-box',
          }}
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
