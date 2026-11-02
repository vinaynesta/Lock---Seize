-- Roadmap 3.6 pieces d-f (docs/sealed-vault-design.md, decisions §24-25).

-- 3.6d: media in a sealed vault is encrypted on the device, so the server can't scan or resize it.
-- encryption_metadata 'e2e:v1' marks such an asset. Its thumbnail is made and encrypted on the device
-- too, then uploaded as a derivative. 'e2e:v1' assets skip the malware scan (decisions §25: members-only
-- risk) and must never reach the server-side media worker.
CREATE INDEX ix_memory_assets_e2e ON memory_assets (memory_id) WHERE encryption_metadata = 'e2e:v1';

-- 3.6e: approving a new device from one that is already unlocked (QR code). The new device publishes a
-- one-time X25519 public key; the approving device sends back the account secret key sealed to it. Rows
-- live 10 minutes and are deleted once used. The server only relays ciphertext.
CREATE TABLE device_approvals (
    id                UUID PRIMARY KEY,
    user_id           UUID        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    request_key       BYTEA       NOT NULL CHECK (octet_length(request_key) = 32),
    device_name       VARCHAR(100),
    sealed_secret     BYTEA,
    approved_at       TIMESTAMPTZ,
    created_at        TIMESTAMPTZ NOT NULL,
    expires_at        TIMESTAMPTZ NOT NULL
);
CREATE INDEX ix_device_approvals_user ON device_approvals (user_id, created_at);

-- 3.6f: the same blind-index table serves sealed vaults, but the tokens are computed on the device with
-- a key derived from the vault key (the server can't). Nothing else changes: same table, same query.
