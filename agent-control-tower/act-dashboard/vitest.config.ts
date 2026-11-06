import { defineConfig, mergeConfig } from 'vitest/config';
import viteConfig from './vite.config';

export default mergeConfig(
  viteConfig,
  defineConfig({
    test: {
      environment: 'jsdom',
      globals: true,
      setupFiles: ['./src/test/setup.ts'],
      include: ['src/**/__tests__/**/*.test.{ts,tsx}'],
      // CI runners are 2-core and run the suite under v8 coverage, two to three
      // times slower than a dev machine: the default 5s test window produced
      // load-dependent timeouts in heavy interaction tests (CrewPage/ReportsPage,
      // 2026-10-04 PR #105). The windows are raised; assertions are unchanged.
      testTimeout: 20000,
      hookTimeout: 20000,
      // Coverage ratchet mirror of the JaCoCo milestones (docs/testing-baseline.md).
      // Measured (v8, all files): 5.7% lines / 65.88% branches / 44.76% funcs.
      // Thresholds locked just below measured; raise in dedicated one-line PRs only.
      coverage: {
        provider: 'v8',
        thresholds: {
          lines: 5,
          branches: 60,
          functions: 40,
        },
      },
    },
  }),
);
