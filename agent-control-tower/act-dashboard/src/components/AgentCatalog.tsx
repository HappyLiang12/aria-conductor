import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { getTemplates, createFromTemplate } from '../api/agents';
import type { AgentTemplate } from '../types';

/**
 * AgentCatalog — hireable agent template grid.
 *
 * Rendered at the bottom of the Crew view. Each tile represents a
 * pre-configured agent role. Pressing "Deploy" issues
 * POST /api/v1/agents with sensible template defaults so the user
 * gets a one-click hiring flow without filling forms.
 */

export function AgentCatalog() {
  const queryClient = useQueryClient();

  const { data: templates, isLoading, error } = useQuery({
    queryKey: ['agent-templates'],
    queryFn: getTemplates,
  });

  const deployMutation = useMutation({
    mutationFn: (template: AgentTemplate) => {
      return createFromTemplate(template.id);
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['agents'] });
    },
  });

  return (
    <div className="template-grid" data-testid="agent-catalog">
      {isLoading && <div>Loading templates...</div>}
      {error && <div>Failed to load templates</div>}
      {templates?.map((tpl) => {
        const isPending = deployMutation.isPending && deployMutation.variables?.id === tpl.id;
        return (
          <div className="tmpl" key={tpl.id} data-template={tpl.id}>
            <div className="top">
              <span className="ic" aria-hidden="true">{tpl.label.charAt(0)}</span>
              <div>
                <div className="nm">{tpl.label}</div>
                <div style={{ fontSize: 10, color: 'var(--text-mute)', marginTop: 1, letterSpacing: '.6px', textTransform: 'uppercase' }}>
                  role · {tpl.role}
                </div>
              </div>
            </div>
            <div className="desc">{tpl.description}</div>
            <button
              type="button"
              className="add-btn"
              disabled={isPending}
              onClick={() => deployMutation.mutate(tpl)}
            >
              {isPending ? 'Deploying…' : '+ Deploy'}
            </button>
          </div>
        );
      })}
    </div>
  );
}

export default AgentCatalog;
