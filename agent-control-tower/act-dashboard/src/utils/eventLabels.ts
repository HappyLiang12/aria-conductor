const LABELS: Record<string, string> = {
  'run.started': 'Run Started',
  'run.completed': 'Run Completed',
  'run.iteration': 'Agent Iteration',
  'run.failed': 'Run Failed',
  'approval.requested': 'Approval Needed',
  'approval.decided': 'Approval Decided',
  'agent.created': 'Agent Created',
  'agent.retired': 'Agent Retired',
  'tool_call': 'Tool Call',
  'tool_result': 'Tool Result',
  'thinking': 'Thinking',
  'kanban.created': 'Kanban Item Created',
  'kanban.transitioned': 'Kanban Item Transitioned',
  'knowledge.submitted': 'Knowledge Submitted',
  'knowledge.approved': 'Knowledge Approved',
  'knowledge.retired': 'Knowledge Retired',
  'report.generated': 'Report Generated',
  'report.amended': 'Report Amended',
  'workflow.started': 'Workflow Started',
  'workflow.completed': 'Workflow Completed',
  'workflow.advanced': 'Workflow Advanced',
  'run.progress': 'Agent Progress',
};

export function eventLabel(type: string): string {
  return LABELS[type] ?? type;
}
