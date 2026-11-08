-- V78: the BA prompt names the F15 bound checkout (SDD hardening).
--
-- V77 shipped the BA text "your working directory starts empty". The runtime now
-- provisions a shallow checkout of the chain repoUrl at <scratch>/repo for a BA
-- step, and the prompt must point at it instead of contradicting it. This was
-- first edited into V77 directly; V77 is immutable once a database has migrated
-- past it, so the correction ships as its own version.
--
-- H2 REPLACE is a plain case-sensitive string replace; the target sentence exists
-- exactly once in the BA prompt (V77 statement 1).

UPDATE knowledge_versions
SET yaml_content = REPLACE(yaml_content,
    'Your working directory starts empty and is the only checkout you may analyse: do not read any other working copy on this machine, because it is a different revision. Work from the issue reference in this prompt. Write the FULL spec text to the file spec.md in your working directory',
    'Your working directory is the only checkout you may analyse: when a repository is bound to this step, a shallow clone of its default branch is provisioned at {workspaceRoot}/ below your working directory - read it there. Do not read any other working copy on this machine, because it is a different revision; if no checkout is present, work from the issue reference alone. Write the FULL spec text to the file spec.md in your working directory')
WHERE knowledge_item_id = CAST('d0000001-0000-0000-0000-000000000001' AS UUID);
