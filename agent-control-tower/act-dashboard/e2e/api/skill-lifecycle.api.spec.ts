import { test, expect } from '@playwright/test';
import {
  apiCall,
  assignSkill,
  promoteKnowledge,
  reviewKnowledge,
  seedAgent,
  seedKnowledgeItem,
  toggleSkill,
  uniqueName,
} from '../fixtures';

/**
 * API-layer coverage for the SKILL system: creation, assignment, execution,
 * plus a concurrent-toggle race. Runs against a live stack (API_URL).
 *
 * Key product fact this spec pins down (and documents as evidence):
 *   - GET /api/v1/skills lists SkillDefinition rows. On a fresh stack this is
 *     EMPTY: no skills are seeded (V34 migration explicitly seeds none), and
 *     there is NO REST endpoint that authors a SkillDefinition — they only
 *     arise from the self-improvement pipeline (SelfImprovementService.
 *     promoteToSkill, gated by PromotionEvaluator), which needs real prompt
 *     calls from completed LLM runs. POST /knowledge/{id}/promote produces a
 *     KnowledgeItem of type SKILL, which is a *different* artifact and is not
 *     assignable via POST /agents/{id}/skills.
 */
const HAS_LLM_KEY = !!(
  process.env.LLM_API_KEY ||
  process.env.LLM_PROVIDER_API_KEY ||
  process.env.DEEPSEEK_API_KEY
);

test.describe('Skill system — creation / assignment / execution', () => {
  test('A. skill registry is reachable and returns an array', async ({ request }) => {
    const { status, data } = await apiCall(request, 'GET', '/skills');
    expect(status).toBe(200);
    expect(Array.isArray(data)).toBe(true);
    // Informational: record the baseline size. Zero is expected on a fresh DB.
    console.log(`[skill] /api/v1/skills baseline count = ${data.length}`);
  });

  test('B. knowledge → SKILL promotion creates a type=SKILL knowledge artifact', async ({ request }) => {
    // The one skill-authoring path that IS exposed over REST (no LLM needed):
    // submit a reusable prompt, approve it, then promote to a SKILL item.
    const seed = await seedKnowledgeItem(request, {
      type: 'PROMPT',
      name: uniqueName('e2e-skill-source'),
      content: 'Summarize the given text in exactly three bullet points.',
    });
    expect(seed.status).toBe('PENDING');

    const approved = await reviewKnowledge(request, seed.id, 'APPROVED', 'promote source');
    expect(approved.status).toBe(200);
    expect(approved.data.status).toBe('APPROVED');

    const targetName = uniqueName('e2e-promoted-skill');
    const promoted = await promoteKnowledge(request, seed.id, 'SKILL', targetName);
    expect(promoted.status).toBe(201);
    expect(promoted.data.id).toBeTruthy();
    expect(promoted.data.id).not.toBe(seed.id);

    const fetched = await apiCall(request, 'GET', `/knowledge/${promoted.data.id}`);
    expect(fetched.status).toBe(200);
    expect(fetched.data.type).toBe('SKILL');
  });

  test('C. skill assignment enforces governance (unknown skill is rejected)', async ({ request }) => {
    const agent = await seedAgent(request, uniqueName('e2e-skill-agent'));

    // A brand-new agent starts with a resolvable (array) skill list.
    const before = await apiCall(request, 'GET', `/agents/${agent.id}/skills`);
    expect(before.status).toBe(200);
    expect(Array.isArray(before.data)).toBe(true);

    // Assigning a non-existent / non-approved skill must be refused: only
    // enabled SKILL-stage skills are assignable (mirrors tool governance).
    const bogus = await assignSkill(request, agent.id, '00000000-0000-0000-0000-000000000abc');
    expect(bogus.status).toBeGreaterThanOrEqual(400);
  });

  test('D. concurrent skill toggles converge to a deterministic state', async ({ request }) => {
    const list = await apiCall(request, 'GET', '/skills');
    const skills: any[] = Array.isArray(list.data) ? list.data : [];
    test.skip(
      skills.length === 0,
      'no SkillDefinition exists on a fresh stack (skills require the LLM self-improvement pipeline)',
    );

    const target = skills[0];
    const initialEnabled: boolean = target.enabled;

    // Fire an EVEN number of concurrent toggles: a correct, serialized toggle
    // implementation must return the flag to its initial value.
    const N = 10;
    const results = await Promise.all(
      Array.from({ length: N }, () => toggleSkill(request, target.id)),
    );
    for (const r of results) expect(r.status).toBeLessThan(500);

    const after = await apiCall(request, 'GET', `/skills/${target.id}`);
    expect(after.status).toBe(200);
    expect(after.data.enabled).toBe(initialEnabled);
  });

  test('E. real skill execution requires the self-improvement pipeline', async ({ request }) => {
    test.skip(
      !HAS_LLM_KEY,
      'requires a real LLM key AND an authored SkillDefinition; no REST path creates one today',
    );
    // Documented limitation: even with an LLM key there is no REST trigger for
    // SelfImprovementService.promoteToSkill, so a full author→assign→execute
    // journey cannot be driven end-to-end via the public API. Recorded in the
    // findings ledger as a governance/coverage gap rather than asserted here.
    const list = await apiCall(request, 'GET', '/skills');
    expect(list.status).toBe(200);
  });
});
