-- Roadmap 3.6 (Sealed Vault, end-to-end encryption), pieces 1-3. Design: docs/sealed-vault-design.md,
-- decisions §24. The server stores only public keys and opaque wrapped (encrypted) keys; it never sees
-- a sealed vault's data key or the secrets that unwrap it.

-- A vault's encryption is chosen at creation and never changes (converting would re-encrypt locked
-- content, which the immutability rules forbid).
ALTER TABLE vaults ADD COLUMN encryption VARCHAR(20) NOT NULL DEFAULT 'SERVER'
    CHECK (encryption IN ('SERVER', 'SEALED'));

-- Piece 1: each user's X25519 key pair. The secret key is generated on the device and stored here only
-- wrapped twice: by a key derived (Argon2id) from the Sealed Vault passphrase, and by a key derived from
-- the 24-word recovery phrase. Neither secret is ever sent to the server.
CREATE TABLE user_key_bundles (
    user_id               UUID PRIMARY KEY REFERENCES users (id),
    public_key            BYTEA       NOT NULL CHECK (octet_length(public_key) = 32),
    wrapped_by_passphrase BYTEA       NOT NULL,
    passphrase_kdf        JSONB       NOT NULL,
    wrapped_by_recovery   BYTEA       NOT NULL,
    recovery_kdf          JSONB       NOT NULL,
    version               BIGINT      NOT NULL DEFAULT 0,
    created_at            TIMESTAMPTZ NOT NULL,
    updated_at            TIMESTAMPTZ NOT NULL
);

-- Vault keys are wrapped to the public key, so it can never change once anything depends on it.
CREATE FUNCTION enforce_public_key_immutable() RETURNS TRIGGER AS $$
BEGIN
    IF NEW.public_key IS DISTINCT FROM OLD.public_key THEN
        RAISE EXCEPTION 'LOCK_IMMUTABLE: a key bundle''s public key cannot change';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_user_key_bundles_public_key
    BEFORE UPDATE ON user_key_bundles
    FOR EACH ROW EXECUTE FUNCTION enforce_public_key_immutable();

-- Piece 2: a sealed vault's data key, one row per member and key version, wrapped by a member's device
-- to that member's public key (X25519 + HKDF + AES-GCM). Versions follow vaults.key_version exactly as
-- vault_keys does for server-side vaults (roadmap 2.4). Rows are never updated; they are deleted only
-- with the vault or the member's account.
CREATE TABLE vault_member_keys (
    vault_id    UUID        NOT NULL REFERENCES vaults (id) ON DELETE CASCADE,
    user_id     UUID        NOT NULL REFERENCES users (id),
    key_version INT         NOT NULL,
    wrapped_key BYTEA       NOT NULL,
    wrapped_by  UUID        NOT NULL REFERENCES users (id),
    created_at  TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (vault_id, user_id, key_version)
);
CREATE INDEX ix_vault_member_keys_user ON vault_member_keys (user_id);

CREATE FUNCTION enforce_no_update() RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'LOCK_IMMUTABLE: % rows cannot be updated', TG_TABLE_NAME;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_vault_member_keys_no_update
    BEFORE UPDATE ON vault_member_keys
    FOR EACH ROW EXECUTE FUNCTION enforce_no_update();
