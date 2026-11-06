-- V70: SDD BA prompt - write the spec into the run workspace (relative path).
--   The run-owned core runs with the run workspace as its working directory in both
--   HOST and SANDBOX mode, but the absolute /workspace path exists only inside the
--   sandbox. SpecReviewCoordinator reads the spec.md the runtime exports to the run's
--   result directory, so the prompt must name the file relative to the working dir.

UPDATE knowledge_versions
SET yaml_content = REPLACE(yaml_content,
    'Write the FULL spec text to the file /workspace/spec.md - this file is the authoritative spec the backend reads (a short summary alone is NOT enough).',
    'Write the FULL spec text to the file spec.md in your working directory - this file is the authoritative spec the backend reads (a short summary alone is NOT enough).')
WHERE knowledge_item_id = CAST('d0000001-0000-0000-0000-000000000001' AS UUID);
