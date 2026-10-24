-- Roadmap 2.5: account deletion under Policy C (decisions.md). Re-authenticated, delayed by a grace
-- period during which it can be cancelled. On execution personal vaults are purged, locked
-- contributions to shared vaults stay (attributed to a "former member"), and the user row is
-- anonymised rather than deleted, because shared memories and the audit trail still reference it.
CREATE TABLE account_deletions (
    id            UUID PRIMARY KEY,
    user_id       UUID        NOT NULL REFERENCES users (id),
    status        VARCHAR(20) NOT NULL CHECK (status IN ('PENDING', 'CANCELLED', 'COMPLETED')),
    requested_at  TIMESTAMPTZ NOT NULL,
    execute_after TIMESTAMPTZ NOT NULL,
    closed_at     TIMESTAMPTZ,
    version       BIGINT      NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX ux_account_deletions_pending ON account_deletions (user_id) WHERE status = 'PENDING';
CREATE INDEX ix_account_deletions_due ON account_deletions (execute_after) WHERE status = 'PENDING';
