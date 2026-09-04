-- V50: Seed preset slash-command skills for the Aria chat `/` menu.
-- The 'workflow' skill instructs Aria to use the instantiate_template tool
-- to start the governed SDD loop (BA → spec approval → Dev → QA).
-- stage MUST be 'SKILL' to pass SkillContextProviderImpl governance filter.

INSERT INTO skill_definitions (id, name, description, template, trigger_conditions, stage, enabled, tier, usage_count, created_at, updated_at)
SELECT 'skill-slash-workflow',
       'workflow',
       'Run the governed BA→Dev→QA spec-driven development workflow on a GitHub issue',
       'You are now operating under the /workflow skill. The user wants to start a spec-driven development workflow.

Instructions:
1. Find the APPROVED "development-workflow" WORKFLOW knowledge item (its fixed ID is d0000001-0000-0000-0000-000000000001).
2. Use the instantiate_template tool with:
   - templateId: "d0000001-0000-0000-0000-000000000001"
   - parameters: { "issueRef": "<from user>", "issueRepo": "<owner/repo from user>", "repoUrl": "<repo clone URL>" }
3. If the user has not provided issueRef, issueRepo, or repoUrl — ASK for the missing values before calling the tool. Do NOT guess or fabricate.
4. NEVER use create_workflow for the BA→Dev→QA loop; always use instantiate_template.
5. After instantiation, report the chain ID and explain that the loop will pause for human spec approval (SPEC_REVIEW) before Dev runs.',
       '{"slash":"/workflow","variables":["issueRef","issueRepo","repoUrl"]}',
       'SKILL',
       TRUE,
       'TIER_1',
       0,
       CURRENT_TIMESTAMP,
       CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM skill_definitions WHERE id = 'skill-slash-workflow');
