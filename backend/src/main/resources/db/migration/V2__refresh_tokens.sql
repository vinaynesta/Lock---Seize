-- Refresh tokens (roadmap 1.1). Only a SHA-256 of each token is stored, never the token itself.
-- Every login starts a family; each refresh rotates to a new token in the same family. Presenting a
-- token that was already rotated or revoked is treated as theft and revokes the whole family.
CREATE TABLE refresh_tokens (
    id         UUID PRIMARY KEY,
    user_id    UUID        NOT NULL REFERENCES users (id),
    family_id  UUID        NOT NULL,
    token_hash VARCHAR(64) NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    rotated_at TIMESTAMPTZ, -- set when this token was exchanged for its successor
    revoked_at TIMESTAMPTZ  -- set on logout, password change or detected reuse
);
CREATE UNIQUE INDEX ux_refresh_tokens_hash ON refresh_tokens (token_hash);
CREATE INDEX ix_refresh_tokens_family ON refresh_tokens (family_id);
CREATE INDEX ix_refresh_tokens_user ON refresh_tokens (user_id) WHERE revoked_at IS NULL;
CREATE INDEX ix_refresh_tokens_expiry ON refresh_tokens (expires_at);
