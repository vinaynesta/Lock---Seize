-- Roadmap 2.4: versioned vault data keys. Removing a member (or a member leaving) adds a new version
-- that all NEW content uses. Content keeps the version it was encrypted with (memories.key_version,
-- memory_reflections.key_version): locked ciphertext is never re-encrypted, which would break the
-- immutability triggers and the integrity proof. See decisions.md, "Vault-key rotation".
CREATE TABLE vault_keys (
    vault_id    UUID         NOT NULL REFERENCES vaults (id) ON DELETE CASCADE,
    version     INT          NOT NULL,
    -- Data key wrapped by the KEK, and which KEK (see V3). Never stored in plaintext.
    wrapped_key BYTEA        NOT NULL,
    kek_id      VARCHAR(300),
    created_at  TIMESTAMPTZ  NOT NULL,
    PRIMARY KEY (vault_id, version)
);
CREATE INDEX ix_vault_keys_kek ON vault_keys (kek_id);

INSERT INTO vault_keys (vault_id, version, wrapped_key, kek_id, created_at)
    SELECT id, key_version, wrapped_key, kek_id, created_at FROM vaults;

-- vaults.key_version is now the CURRENT version; the keys themselves live in vault_keys only.
ALTER TABLE vaults DROP COLUMN wrapped_key;
ALTER TABLE vaults DROP COLUMN kek_id;

-- Reflections were all written under version 1 so far.
ALTER TABLE memory_reflections ADD COLUMN key_version INT NOT NULL DEFAULT 1;
