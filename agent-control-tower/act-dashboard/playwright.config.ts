import { defineConfig, devices } from '@playwright/test';

export default defineConfig({
  testDir: './e2e',
  timeout: 300_000, // 5 min per test (LLM calls are slow)
  fullyParallel: false,
  // CI-only single retry: quarantines transient infra flakes; local runs stay strict.
  retries: process.env.CI ? 1 : 0,
  reporter: [['list']],
  use: {
    // BASE_URL override enables isolated local stacks (e.g. worktrees on alternate ports).
    baseURL: process.env.BASE_URL || 'http://localhost:5173',
    channel: process.env.PLAYWRIGHT_BROWSER_CHANNEL ?? 'chrome',
    trace: 'retain-on-failure',
    screenshot: 'on',
    headless: !!process.env.CI,
    viewport: { width: 1400, height: 900 },
    actionTimeout: 15_000,
    // Headless chromium denies clipboard access by default; specs assert copy buttons.
    permissions: ['clipboard-read', 'clipboard-write'],
  },
  projects: [
    { name: 'chromium', use: { ...devices['Desktop Chrome'] } },
  ],
});
