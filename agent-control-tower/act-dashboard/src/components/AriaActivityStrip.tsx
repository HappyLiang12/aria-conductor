import { useState } from 'react';
import type { PanelItem, ToolActivity } from './ariaPanelState';

type ActivityItem = Extract<PanelItem, { kind: 'activity' }>;

/**
 * One turn's tool calls as a single collapsed row (F7: tool activity must not
 * flood the transcript). Collapsed it names the tools and their status;
 * expanded it shows the arguments and result of each call. A restored turn and
 * a live turn render through this same component, so a reconnect changes
 * nothing about how activity reads.
 */

const STATUS_GLYPH: Record<ToolActivity['status'], string> = {
  running: '⟢',
  ok: '✓',
  error: '✕',
};

function durationLabel(tool: ToolActivity): string | null {
  if (tool.durationMs !== undefined) {
    return tool.durationMs < 1000 ? `${tool.durationMs}ms` : `${(tool.durationMs / 1000).toFixed(1)}s`;
  }
  return tool.status === 'running' ? 'running' : null;
}

export function AriaActivityStrip({ item }: { item: ActivityItem }) {
  const [expanded, setExpanded] = useState(!item.collapsed);
  const tools = item.tools;
  const failed = tools.filter((tool) => tool.status === 'error').length;
  const summary = `${tools.length} tool call${tools.length === 1 ? '' : 's'}${failed > 0 ? ` · ${failed} failed` : ''}`;

  return (
    <div className={`ai-activity${failed > 0 ? ' ai-activity-failed' : ''}`}>
      <button
        type="button"
        className="ai-activity-toggle"
        aria-expanded={expanded}
        onClick={() => setExpanded((value) => !value)}
      >
        <span className="ai-activity-caret" aria-hidden="true">{expanded ? '▾' : '▸'}</span>
        <span className="ai-activity-summary">{summary}</span>
        <span className="ai-activity-tools">
          {tools.map((tool, index) => (
            <span key={`${tool.name}:${index}`} className={`ai-activity-tool ai-activity-${tool.status}`}>
              <span className="ai-activity-glyph" aria-hidden="true">{STATUS_GLYPH[tool.status]}</span>
              <code>{tool.name}</code>
              {durationLabel(tool) !== null && (
                <span className="ai-activity-duration">{durationLabel(tool)}</span>
              )}
            </span>
          ))}
        </span>
      </button>
      {expanded && (
        <div className="ai-activity-detail">
          {tools.map((tool, index) => (
            <div className="ai-activity-entry" key={`detail:${tool.name}:${index}`}>
              <div className="ai-activity-entry-head">
                <code>{tool.name}</code>
                <span className="ai-activity-status">{tool.status}</span>
              </div>
              {tool.args !== undefined && tool.args !== '' && (
                <pre className="ai-activity-json">{tool.args}</pre>
              )}
              {tool.result !== undefined && tool.result !== '' && (
                <pre className="ai-activity-json">{tool.result}</pre>
              )}
            </div>
          ))}
        </div>
      )}
    </div>
  );
}

export default AriaActivityStrip;
