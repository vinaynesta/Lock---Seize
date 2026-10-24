-- Roadmap 2.3: asynchronous ZIP export (manifest + original media). The ZIP holds decrypted content,
-- so it is short-lived (expires_at) and only its requester can download it.
CREATE TABLE vault_exports (
    id           UUID PRIMARY KEY,
    vault_id     UUID         NOT NULL REFERENCES vaults (id) ON DELETE CASCADE,
    requested_by UUID         NOT NULL REFERENCES users (id),
    status       VARCHAR(20)  NOT NULL CHECK (status IN ('QUEUED', 'READY', 'FAILED', 'EXPIRED')),
    object_key   VARCHAR(300),
    size_bytes   BIGINT,
    memory_count INT,
    asset_count  INT,
    error        VARCHAR(300),
    requested_at TIMESTAMPTZ  NOT NULL,
    completed_at TIMESTAMPTZ,
    expires_at   TIMESTAMPTZ,
    version      BIGINT       NOT NULL DEFAULT 0
);
-- One export in flight per user per vault.
CREATE UNIQUE INDEX ux_vault_exports_active ON vault_exports (vault_id, requested_by) WHERE status = 'QUEUED';
CREATE INDEX ix_vault_exports_expiry ON vault_exports (expires_at) WHERE status = 'READY';
CREATE INDEX ix_vault_exports_user ON vault_exports (requested_by, requested_at DESC);
