import '@testing-library/jest-dom/vitest';
import { configure } from '@testing-library/react';

// jsdom ships no `window.matchMedia`; the shadcn sidebar asks for it through
// `useIsMobile`. Provide the API (never matched, so tests stay desktop-width)
// instead of making every shell test stub it.
if (typeof window !== 'undefined' && typeof window.matchMedia !== 'function') {
  window.matchMedia = (query: string): MediaQueryList => ({
    matches: false,
    media: query,
    onchange: null,
    addEventListener: () => {},
    removeEventListener: () => {},
    addListener: () => {},
    removeListener: () => {},
    dispatchEvent: () => false,
  }) as unknown as MediaQueryList;
}

// CI runners (2-core, v8 coverage) can exceed the 1000ms default while a heavy
// page renders: async queries get a wider window so load-dependent renders do
// not fail as "element not found" (2026-10-04 PR #105). Assertions unchanged.
configure({ asyncUtilTimeout: 4000 });
