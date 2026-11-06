import '@testing-library/jest-dom/vitest';
import { configure } from '@testing-library/react';

// CI runners (2-core, v8 coverage) can exceed the 1000ms default while a heavy
// page renders: async queries get a wider window so load-dependent renders do
// not fail as "element not found" (2026-10-04 PR #105). Assertions unchanged.
configure({ asyncUtilTimeout: 4000 });
