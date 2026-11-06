-- V47: SDD QA prompt - commit qa_report.md into the branch so the backend can
--   capture it into a platform report artifact at chain completion (R8-F4).
--   The report is written to /workspace/qa_report.md (outside the clone), so the
--   QA agent copies it into /workspace/repo and pushes it to {branchName}; the
--   backend then pulls qa_report.md from the branch via GitBranchService.getFile.

UPDATE knowledge_versions
SET yaml_content = REPLACE(yaml_content,
    'Check out the work first: git clone --branch {branchName} {repoUrl} /workspace/repo (GH_TOKEN is configured for private repos). Verify the code in /workspace/repo against the spec at /workspace/repo/spec/spec.md. Run the real tests and record their actual results. Write your findings to /workspace/qa_report.md. Submit your verdict with the submit_dod_review tool AND end your output with VERDICT=<PASS|DEFECT|SPEC_GAP> then REPORT_ID=<uuid>. In this sandbox the submit_dod_review tool may not be available; the VERDICT=<PASS|DEFECT|SPEC_GAP> marker in your final output IS the official verdict submission - both are equally valid.',
    'Check out the work first: git clone --branch {branchName} {repoUrl} /workspace/repo (GH_TOKEN is configured for private repos). Verify the code in /workspace/repo against the spec at /workspace/repo/spec/spec.md. Run the real tests and record their actual results. Write your findings to /workspace/qa_report.md. Commit the report to the branch so it survives the sandbox: cd /workspace/repo && cp /workspace/qa_report.md ./qa_report.md && git add qa_report.md && git commit -m ''sdd qa report'' && git push origin {branchName}. Submit your verdict with the submit_dod_review tool AND end your output with VERDICT=<PASS|DEFECT|SPEC_GAP> then REPORT_ID=<uuid>. In this sandbox the submit_dod_review tool may not be available; the VERDICT=<PASS|DEFECT|SPEC_GAP> marker in your final output IS the official verdict submission - both are equally valid.')
WHERE knowledge_item_id = CAST('d0000001-0000-0000-0000-000000000001' AS UUID);
