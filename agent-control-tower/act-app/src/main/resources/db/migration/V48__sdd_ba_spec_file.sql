-- V48: SDD BA prompt - write the FULL spec to a fixed sandbox file (R9-F1).
--   The BA previously returned only a short summary in its finalOutput, so the
--   approval content AND the branch spec/spec.md ended up as a truncated fragment.
--   The backend (SpecReviewCoordinator) now reads /workspace/spec.md from the BA
--   sandbox as the authoritative spec; the finalOutput carries only a short summary
--   plus the SPEC_ID=<uuid> marker.

UPDATE knowledge_versions
SET yaml_content = REPLACE(yaml_content,
    'Analyze issue {issueRef} and write a spec with sections: Problem Statement, Proposed Solution, Acceptance Criteria, Error Handling. If the issue body is not already in your prompt, fetch it first with: gh issue view {issueRef} -R {issueRepo} --json title,body,labels (GH_TOKEN is already configured). If anything is ambiguous and requires the user to decide, end the spec with a ## Questions section listing each question on its own line; omit the section when nothing is ambiguous. NEVER ask interactive questions - put everything into the spec. If the task message contains rejection feedback (Spec was rejected: ...), incorporate the reviewer answers into the revised spec and drop the Questions section for answered items. End your output with SPEC_ID=<uuid> after approval.',
    'Analyze issue {issueRef} and write a spec with sections: Problem Statement, Proposed Solution, Acceptance Criteria, Error Handling. If the issue body is not already in your prompt, fetch it first with: gh issue view {issueRef} -R {issueRepo} --json title,body,labels (GH_TOKEN is already configured). If anything is ambiguous and requires the user to decide, end the spec with a ## Questions section listing each question on its own line; omit the section when nothing is ambiguous. NEVER ask interactive questions - put everything into the spec. If the task message contains rejection feedback (Spec was rejected: ...), incorporate the reviewer answers into the revised spec and drop the Questions section for answered items. Write the FULL spec text to the file /workspace/spec.md - this file is the authoritative spec the backend reads (a short summary alone is NOT enough). End your final output with a short summary followed by SPEC_ID=<uuid>.')
WHERE knowledge_item_id = CAST('d0000001-0000-0000-0000-000000000001' AS UUID);
