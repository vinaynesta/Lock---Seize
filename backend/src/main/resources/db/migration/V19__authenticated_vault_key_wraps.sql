-- Security review F2 (docs/security-review-sealed-vault.md): wrapped vault keys are now authenticated by
-- the wrapping member's X25519 key (format v2). The device needs that public key to verify a wrap, also
-- after the wrapping member deleted their account (which removes their key bundle), so it is copied
-- onto the row when the wrap is stored. Rows stay immutable (trg_vault_member_keys_no_update); older
-- version-1 rows keep NULL here, since anonymous wraps have no sender key to check.
ALTER TABLE vault_member_keys ADD COLUMN wrapped_by_public_key BYTEA;
