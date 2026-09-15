-- The three SDD role agents were seeded with model = 'mock' as a placeholder.
-- Nothing ever consumed that value: both providers take the model from the
-- active llm_providers row, and the real canned-response mechanism is the
-- noop-llm Spring profile. Leaving it made agents look like non-workers and
-- fed a false "mock means no real work" assumption. NULL it so `model` reads
-- as "unset", which is what it is.
UPDATE agents SET model = NULL
WHERE model = 'mock'
  AND id IN ('ba000000-0000-0000-0000-000000000001',
             'de000000-0000-0000-0000-000000000002',
             'aa000000-0000-0000-0000-000000000003');
