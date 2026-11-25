import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { getTemplates, createFromTemplate } from '../api/agents';
import { presentError } from '../api/errorPresentation';
import ErrorNotice from './ErrorNotice';
import type { AgentTemplate } from '../types';

/**
 * AgentCatalog — hireable agent template grid.
 *
 * Rendered at the bottom of the Crew view. Each tile represents a
 * pre-configured agent role. Pressing "Deploy" issues
 * POST /api/v1/agents/from-template/{name} so the template owns its defaults.
 */

export function AgentCatalog() {
  const queryClient = useQueryClient();

  const { data: templates, isLoading, error } = useQuery({
    queryKey: ['agent-templates'],
    queryFn: getTemplates,
  });

  const deployMutation = useMutation({
    mutationFn: (template: AgentTemplate) => createFromTemplate(template.id),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['agents'] });
    },
  });

  return (
    <div
      className="template-grid grid grid-cols-[repeat(auto-fill,minmax(240px,1fr))] gap-2.5"
      data-testid="agent-catalog"
    >
      {isLoading && <div className="text-sm text-muted-foreground">Loading templates...</div>}
      {error && <ErrorNotice presented={presentError(error, 'Failed to load templates.')} />}
      {deployMutation.error && (
        <ErrorNotice presented={presentError(deployMutation.error, 'The agent could not be deployed.')} />
      )}
      {templates?.map((tpl) => {
        const isPending = deployMutation.isPending && deployMutation.variables?.id === tpl.id;
        return (
          <div
            className="tmpl flex flex-col gap-2 rounded-xl border border-dashed border-border bg-card p-3 text-card-foreground transition-colors hover:border-primary/50"
            key={tpl.id}
            data-template={tpl.id}
          >
            <div className="top flex items-center gap-2">
              <span
                className="ic flex size-7 items-center justify-center rounded-md bg-primary/15 text-sm font-semibold text-primary"
                aria-hidden="true"
              >
                {tpl.label.charAt(0)}
              </span>
              <div>
                <div className="nm text-sm font-bold">{tpl.label}</div>
                <div className="mt-0.5 text-[10px] tracking-[.6px] text-muted-foreground uppercase">
                  role · {tpl.role}
                </div>
              </div>
            </div>
            <div className="desc text-xs text-muted-foreground">{tpl.description}</div>
            <button
              type="button"
              className="add-btn mt-auto inline-flex h-7 items-center justify-center rounded-lg border border-transparent bg-primary px-2.5 text-xs font-semibold text-primary-foreground transition-colors hover:bg-primary/80 disabled:opacity-50"
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
