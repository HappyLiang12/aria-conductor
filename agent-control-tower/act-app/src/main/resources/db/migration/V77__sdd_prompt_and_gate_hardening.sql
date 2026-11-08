-- V77: SDD prompt and gate hardening (F11, F15, F19, F22, F23).
--
-- The development-workflow prompts used to hardcode the sandbox path /workspace:
-- in HOST mode the clone resolved to C:/Program Files/Git/workspace/repo and failed
-- with "Permission denied" (F11). They also claimed "GH_TOKEN is already configured"
-- while the core saw tokens GitHub rejects (F14), copied the QA report through the
-- index (a partial checkout then committed a tree that deleted 300 files, F19), and
-- read "85 errors, 0 failures" as a green run (F23).
--
-- The checkout root is now the {workspaceRoot} placeholder: WorkflowService resolves
-- it per step from aria.workflows.workspace-root, and falls back to the relative
-- directory "repo" under the step working directory (and says so) when it is unset.
-- No prompt names /workspace any more.
--
-- H2 REPLACE is a plain case-sensitive string replace; each statement below targets a
-- substring that exists exactly once per prompt (or once for the shared DEV/QA text).

-- 1. BA: never claim a token is configured; state the workspace scope explicitly (F15).
UPDATE knowledge_versions
SET yaml_content = REPLACE(REPLACE(yaml_content,
    '(GH_TOKEN is already configured)',
    '(use GH_TOKEN or GITHUB_TOKEN only if this environment actually provides them - never assume a token is set)'),
    'Write the FULL spec text to the file spec.md in your working directory',
    'Your working directory starts empty and is the only checkout you may analyse: do not read any other working copy on this machine, because it is a different revision. Work from the issue reference in this prompt. Write the FULL spec text to the file spec.md in your working directory')
WHERE knowledge_item_id = CAST('d0000001-0000-0000-0000-000000000001' AS UUID);

-- 2. DEV + QA: parameterise the clone root and the token claim (F11, F14).
UPDATE knowledge_versions
SET yaml_content = REPLACE(yaml_content,
    'git clone --branch {branchName} {repoUrl} /workspace/repo (GH_TOKEN is configured for private repos)',
    'git clone --branch {branchName} {repoUrl} {workspaceRoot} (all paths below are under {workspaceRoot}; when it is relative it is relative to your current working directory; use GH_TOKEN or GITHUB_TOKEN only if this environment actually provides them and never assume a token is set)')
WHERE knowledge_item_id = CAST('d0000001-0000-0000-0000-000000000001' AS UUID);

-- 3. DEV + QA: the spec path, the QA verification root and the DEV edit root follow
--    the same placeholder (F11).
UPDATE knowledge_versions
SET yaml_content = REPLACE(REPLACE(REPLACE(yaml_content,
    '/workspace/repo/spec/spec.md',
    '{workspaceRoot}/spec/spec.md'),
    'Make code changes inside /workspace/repo.',
    'Make code changes inside {workspaceRoot}.'),
    'Verify the code in /workspace/repo against the spec at',
    'Verify the code in {workspaceRoot} against the spec at')
WHERE knowledge_item_id = CAST('d0000001-0000-0000-0000-000000000001' AS UUID);

-- 4. DEV: a Maven run with test errors is not a green run (F23); verify the pushed
--    diff so a partial checkout cannot delete files unnoticed (F19).
UPDATE knowledge_versions
SET yaml_content = REPLACE(REPLACE(yaml_content,
    'Do NOT claim tests passed unless you ran them and saw them pass.',
    'Do NOT claim tests passed unless you ran them and saw them pass. A Maven run that reports test errors is not a green run: errors are unexecuted or crashed tests, so report the run as failed and never as 0 failures.'),
    'When done: git add -A && git commit -m ''sdd dev'' && git push origin {branchName}.',
    'When done, stage only the files you changed: git add -A && git commit -m ''sdd dev'' && git diff --stat HEAD~1 && git push origin {branchName}. The diff must list only the files you intended; if it lists deletions you did not intend, repair the worktree before pushing.')
WHERE knowledge_item_id = CAST('d0000001-0000-0000-0000-000000000001' AS UUID);

-- 5. QA: errors are not a green run; never verify a criterion on an errored run (F23, F22).
UPDATE knowledge_versions
SET yaml_content = REPLACE(yaml_content,
    'Run the real tests and record their actual results.',
    'Run the real tests and record their actual results. A Maven run that reports test errors is not a green run: errors are unexecuted or crashed tests, so report the run as failed and never as 0 failures, and never mark an acceptance criterion verified on the strength of an errored run.')
WHERE knowledge_item_id = CAST('d0000001-0000-0000-0000-000000000001' AS UUID);

-- 6. QA: write the report inside the checkout and commit ONLY it with an explicit
--    pathspec, verifying the diff before the push (F19). The old instruction copied
--    the report through the index, so a partially checked-out tree committed 300
--    deletions in 984df552.
UPDATE knowledge_versions
SET yaml_content = REPLACE(yaml_content,
    'Write your findings to /workspace/qa_report.md. Commit the report to the branch so it survives the sandbox: cd /workspace/repo && cp /workspace/qa_report.md ./qa_report.md && git add qa_report.md && git commit -m ''sdd qa report'' && git push origin {branchName}.',
    'Write your findings to qa_report.md inside the checkout, at {workspaceRoot}/qa_report.md, then commit ONLY that file with an explicit pathspec so no deleted or partially checked-out entry can ride along: cd {workspaceRoot} && git add -- qa_report.md && git commit -m ''sdd qa report'' -- qa_report.md && git diff --stat HEAD~1 && git push origin {branchName}. Before pushing, the diff must list qa_report.md as the only changed file; if it lists any removed or modified source file, do not push: repair the checkout (git reset --mixed HEAD~1 && git checkout -- . && git add -- qa_report.md && git commit -m ''sdd qa report'' -- qa_report.md) and verify the diff again.')
WHERE knowledge_item_id = CAST('d0000001-0000-0000-0000-000000000001' AS UUID);

-- 7. QA: a PASS verdict requires every acceptance criterion to be verified (F22).
UPDATE knowledge_versions
SET yaml_content = REPLACE(yaml_content,
    'the VERDICT=<PASS|DEFECT|SPEC_GAP> marker in your final output IS the official verdict submission - both are equally valid.',
    'the VERDICT=<PASS|DEFECT|SPEC_GAP> marker in your final output IS the official verdict submission - both are equally valid. A PASS verdict requires every acceptance criterion to be verified; if any criterion is NOT VERIFIED, report VERDICT=DEFECT instead.')
WHERE knowledge_item_id = CAST('d0000001-0000-0000-0000-000000000001' AS UUID);
