import { useAmbientInvalidation } from '../../hooks/useAmbientInvalidation';

/**
 * Mounts the ambient invalidation subscription once per page load. It renders
 * nothing: the hook exists to turn STOMP events into TanStack Query
 * invalidations, so it belongs to the shell, not to any one surface.
 */
export function AmbientInvalidation() {
  useAmbientInvalidation();
  return null;
}

export default AmbientInvalidation;
