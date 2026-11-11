import { test, expect, type Locator, type Page } from '@playwright/test';
import { OPERATOR_BEARER_TOKEN, establishOperatorSession } from './fixtures';

/**
 * Live in-flow decisions (spec A3; findings F1, F4, F7).
 *
 * The acceptance this proves, against a real backend and a real model:
 *   - an ask the parked Aria turn raises renders as a decision card INSIDE the
 *     conversation dialog — no page switch, no trip to the Review Queue;
 *   - clicking Allow once sends the decision and the SAME turn resumes, so the
 *     card settles and the composer comes back;
 *   - a second conversation can be started while the first one is parked.
 *
 * There is no WebSocket stubbing and no fetch interception here: the point is
 * the whole path (ordered SSE signal → card → operator decision → resumed
 * stream). That path needs a model that actually chooses the governed memory
 * write, plus the operator credential the browser exchanges for a session, so
 * the spec skips (with the reason) on a stack that has neither — it is a live
 * acceptance spec, not a gate spec.
 */
test.describe.configure({ mode: 'serial', timeout: 300_000 });

const HAS_MODEL = (process.env.LLM_API_KEY ?? '').trim() !== '';
const HAS_OPERATOR = OPERATOR_BEARER_TOKEN !== '';
const SKIP_REASON =
  'Live PA decision acceptance needs a real model (LLM_API_KEY) and the operator '
  + 'credential (ARIA_OPERATOR_BEARER_TOKEN); both are supplied by the local stack, not by CI.';

/**
 * Asks Aria for a governed memory write. WORKER_WRITE is per-call approved by
 * design (only the five memory reads are auto-approved), so the turn parks on an
 * `approval_required` signal that the panel turns into a card.
 */
const ASK_PROMPT =
  'Save this as a long-term fact about me with the memory tool: my preferred release channel is email. '
  + 'Use the tool, do not just acknowledge.';

async function openAria(page: Page): Promise<void> {
  const fab = page.locator('button.ai-fab');
  await fab.waitFor({ state: 'visible', timeout: 15_000 });
  if (!(await page.locator('.ai-panel').isVisible().catch(() => false))) {
    await fab.click();
  }
  await expect(page.locator('.ai-panel')).toBeVisible({ timeout: 10_000 });
}

async function sendPrompt(page: Page, prompt: string): Promise<void> {
  const textarea = page.locator('.ai-panel .ai-compose-input');
  await textarea.waitFor({ state: 'visible', timeout: 10_000 });
  await textarea.fill(prompt);
  await textarea.press('Enter');
}

/**
 * Parks a turn on an ask and returns the card. The card arriving at all is the
 * finding this spec exists for: before Task 6 the panel showed nothing until
 * the 600 s client timeout.
 */
async function parkOnAsk(page: Page): Promise<Locator> {
  await establishOperatorSession(page);
  await page.goto('/');
  await page.waitForLoadState('networkidle');
  await openAria(page);

  await sendPrompt(page, ASK_PROMPT);
  const card = page.locator('.ai-panel .ai-decision-approval').first();
  await expect(card).toBeVisible({ timeout: 180_000 });
  return card;
}

test.describe('Aria in-flow decisions (live)', () => {
  test.skip(!HAS_MODEL || !HAS_OPERATOR, SKIP_REASON);

  test('an ask renders inside the conversation and Allow once resumes the same turn', async ({ page }) => {
    const card = await parkOnAsk(page);

    // The card is in the transcript, not on another page, and it names the
    // governed tool with both governance labels.
    await expect(page.getByRole('dialog', { name: 'Aria assistant' })).toBeVisible();
    expect(new URL(page.url()).pathname).toBe('/');
    await expect(card.locator('.ai-decision-tool code')).toContainText('remember_fact');
    await expect(card.locator('.ai-decision-tags .pill')).toHaveCount(2);

    // The composer is gated by the unanswered ask, and says why.
    await expect(page.locator('.ai-panel .ai-gate-hint')).toContainText('Answer or cancel the pending decision first.');
    await expect(page.locator('.ai-panel .ai-compose-input')).toBeDisabled();

    await card.getByRole('button', { name: /allow once/i }).click();

    // Approved: the card settles, the gate lifts, and the turn continues on its
    // own stream — the panel ends up idle (no Cancel button) with a reply.
    await expect(card.locator('.ai-decision-settled')).toContainText(/allowed once/i);
    await expect(page.locator('.ai-panel .ai-gate-hint')).toHaveCount(0);
    await expect(page.locator('.ai-panel .ai-cancel-btn')).toHaveCount(0, { timeout: 180_000 });
    await expect(page.locator('.ai-panel .ai-compose-input')).toBeEnabled();
    await expect(page.locator('.ai-panel .ai-msg-assistant').last()).toBeVisible();
    // Still the same conversation, on the same page.
    expect(new URL(page.url()).pathname).toBe('/');
  });

  test('a second conversation can be started while the first one is parked', async ({ page }) => {
    const card = await parkOnAsk(page);
    const firstConversation = (await page.locator('.ai-conversation-id').textContent())?.trim();
    expect(firstConversation).toBeTruthy();

    await page.getByRole('button', { name: 'Clear and start new conversation' }).click();

    // The parked transcript is gone (the run keeps living server-side), the
    // composer is usable again, and this is a new conversation id.
    await expect(page.locator('.ai-panel .ai-decision-card')).toHaveCount(0);
    await expect(page.locator('.ai-panel .ai-empty')).toBeVisible();
    await expect(page.locator('.ai-panel .ai-compose-input')).toBeEnabled();
    const secondConversation = (await page.locator('.ai-conversation-id').textContent())?.trim();
    expect(secondConversation).not.toBe(firstConversation);

    // The second conversation accepts a turn while the first is still parked.
    await sendPrompt(page, 'What is the current run queue status?');
    await expect(page.locator('.ai-panel .ai-msg-user').last()).toBeVisible({ timeout: 30_000 });
    await expect(card).toHaveCount(0);
  });
});
