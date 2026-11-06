-- V46: SDD QA prompt - bless the VERDICT marker as the official sandbox verdict channel.
--   In the sandbox the submit_dod_review tool may be unavailable, so the QA agent must
--   treat the VERDICT=<PASS|DEFECT|SPEC_GAP> marker in its final output as the official
--   verdict submission (equally valid to the tool call). The backend marker path already
--   routes on the VERDICT= marker (R8-F2); this migration makes the prompt agree with it.

UPDATE knowledge_versions
SET yaml_content = REPLACE(yaml_content,
    'Check out the work first: git clone --branch {branchName} {repoUrl} /workspace/repo (GH_TOKEN is configured for private repos). Verify the code in /workspace/repo against the spec at /workspace/repo/spec/spec.md. Run the real tests and record their actual results. Write your findings to /workspace/qa_report.md. Submit your verdict with the submit_dod_review tool AND end your output with VERDICT=<PASS|DEFECT|SPEC_GAP> then REPORT_ID=<uuid>.',
    'Check out the work first: git clone --branch {branchName} {repoUrl} /workspace/repo (GH_TOKEN is configured for private repos). Verify the code in /workspace/repo against the spec at /workspace/repo/spec/spec.md. Run the real tests and record their actual results. Write your findings to /workspace/qa_report.md. Submit your verdict with the submit_dod_review tool AND end your output with VERDICT=<PASS|DEFECT|SPEC_GAP> then REPORT_ID=<uuid>. In this sandbox the submit_dod_review tool may not be available; the VERDICT=<PASS|DEFECT|SPEC_GAP> marker in your final output IS the official verdict submission - both are equally valid.')
WHERE knowledge_item_id = CAST('d0000001-0000-0000-0000-000000000001' AS UUID);
