-- Roadmap 3.2: storage quotas. A user's plan sets how many bytes of original media they may upload,
-- counted across every vault. Only a draft's creator can add media to it, so usage is the size of the
-- assets in memories the user created. Derivatives are not counted: they are the service's cost, and
-- they can be regenerated. NULL plan = lock.quota.default-plan.
ALTER TABLE users ADD COLUMN plan VARCHAR(30);

CREATE INDEX ix_memories_created_by ON memories (created_by);
