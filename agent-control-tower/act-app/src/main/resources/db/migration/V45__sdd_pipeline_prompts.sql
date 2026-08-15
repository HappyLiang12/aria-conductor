-- V45: SDD pipeline prompts - branch-scoped checkout + verdict marker.
--   DEV: clone the spec-carrying branch, read spec/spec.md, run real tests, push.
--   QA: clone the branch, verify against spec/spec.md, emit VERDICT=<...> marker.

-- DEV prompt: replace the V44 clone-guidance prompt with the branch-scoped pipeline prompt.
UPDATE knowledge_versions
SET yaml_content = REPLACE(yaml_content,
    'Check out the project first: git clone {repoUrl} /workspace/repo (GH_TOKEN is configured for private repos). Implement per approved spec {specRef}; run unit + integration tests and verify CI before finishing.',
    'Check out the project first: git clone --branch {branchName} {repoUrl} /workspace/repo (GH_TOKEN is configured for private repos). Read the spec at /workspace/repo/spec/spec.md and implement ONLY what it requires. Make code changes inside /workspace/repo. Run the real test commands and report their actual output. Do NOT claim tests passed unless you ran them and saw them pass. When done: git add -A && git commit -m ''sdd dev'' && git push origin {branchName}.')
WHERE knowledge_item_id = CAST('d0000001-0000-0000-0000-000000000001' AS UUID);

-- QA prompt: replace the seeded QA prompt with the branch-scoped verification + verdict marker.
UPDATE knowledge_versions
SET yaml_content = REPLACE(yaml_content,
    'Verify against spec {specRef}; generate a QA report via generate_report and submit the DoD verdict. End your output with REPORT_ID=<uuid>.',
    'Check out the work first: git clone --branch {branchName} {repoUrl} /workspace/repo (GH_TOKEN is configured for private repos). Verify the code in /workspace/repo against the spec at /workspace/repo/spec/spec.md. Run the real tests and record their actual results. Write your findings to /workspace/qa_report.md. Submit your verdict with the submit_dod_review tool AND end your output with VERDICT=<PASS|DEFECT|SPEC_GAP> then REPORT_ID=<uuid>.')
WHERE knowledge_item_id = CAST('d0000001-0000-0000-0000-000000000001' AS UUID);
