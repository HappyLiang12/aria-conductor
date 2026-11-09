-- Split from cleanup-all.sql because context_manifests is created by V81 and
-- does not exist at the Flyway targets some migration tests pin (V65). The
-- full-schema cleanup keeps purging it; a pinned-target class overrides the
-- inherited @Sql to run cleanup-all.sql alone.
TRUNCATE TABLE context_manifests RESTART IDENTITY;
