import { defineConfig, devices } from '@playwright/test';

export default defineConfig({
  testDir: './e2e',
  // Default tier: 2 min covers every mock/no-LLM spec. Specs that drive a real
  // LLM opt into 300s/600s via test.describe.configure({ timeout }) in-file.
  timeout: 120_000,
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
