-- Roadmap 3.1: two-step verification with TOTP (RFC 6238) and single-use recovery codes.
-- The TOTP secret is wrapped by the KEK, like vault keys, so a database dump alone cannot mint codes.
CREATE TABLE user_mfa (
    user_id        UUID PRIMARY KEY REFERENCES users (id),
    secret_wrapped BYTEA       NOT NULL,
    kek_id         VARCHAR(300),
    enabled_at     TIMESTAMPTZ, -- null while set up but not yet confirmed with a first code
    last_used_step BIGINT,      -- the newest 30-second step accepted: a code can never be replayed
    created_at     TIMESTAMPTZ NOT NULL,
    version        BIGINT      NOT NULL DEFAULT 0
);

CREATE TABLE mfa_recovery_codes (
    id         UUID PRIMARY KEY,
    user_id    UUID        NOT NULL REFERENCES users (id),
    code_hash  VARCHAR(64) NOT NULL,
    used_at    TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX ix_mfa_recovery_codes_user ON mfa_recovery_codes (user_id) WHERE used_at IS NULL;
