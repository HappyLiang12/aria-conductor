import ExecutiveSummary from '../components/ExecutiveSummary';
import KanbanBoard from '../components/KanbanBoard';
import AgentTeam from '../components/AgentTeam';
import ReviewQueue from '../components/ReviewQueue';
import ActivityTimeline from '../components/ActivityTimeline';
import MorningBriefing from '../components/MorningBriefing';

export default function OverviewPage() {
  return (
    <div className="view-zone" data-view="overview">
      <div
        className="layout"
        style={{ gridTemplateColumns: 'minmax(0, 1fr) minmax(320px, 380px)' }}
      >
        <div className="col">
          <ExecutiveSummary />
          <KanbanBoard />
          <MorningBriefing />
        </div>
        <div className="col">
          <AgentTeam />
          <ReviewQueue />
          <ActivityTimeline />
        </div>
      </div>
    </div>
  );
}
