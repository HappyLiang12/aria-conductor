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
