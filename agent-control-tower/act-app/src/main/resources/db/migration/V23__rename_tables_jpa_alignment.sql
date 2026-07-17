-- V23: Rename scheduled_jobs → aria_scheduled_jobs (JPA entity uses @Table("aria_scheduled_jobs"))
-- Compatible with H2 (MODE=MySQL) and MariaDB 10.x

ALTER TABLE scheduled_jobs RENAME TO aria_scheduled_jobs;

-- Indexes automatically follow table rename in both databases
-- Safety net: CREATE IF NOT EXISTS
CREATE INDEX IF NOT EXISTS idx_jobs_status ON aria_scheduled_jobs(status);
CREATE INDEX IF NOT EXISTS idx_jobs_category ON aria_scheduled_jobs(category);
CREATE INDEX IF NOT EXISTS idx_jobs_next_fire ON aria_scheduled_jobs(next_fire_at);
