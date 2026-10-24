-- Roadmap 2.6: password reset by email. Only a SHA-256 of each token is stored. The raw token is an
-- HMAC of the row id under a server secret, so the mail worker can rebuild it from the id alone: it
-- never sits in the outbox, and a database leak does not hand out reset links.
CREATE TABLE password_reset_tokens (
    id         UUID PRIMARY KEY,
    user_id    UUID        NOT NULL REFERENCES users (id),
    token_hash VARCHAR(64) NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    used_at    TIMESTAMPTZ -- set when used, or when a newer token or a password change supersedes it
);
CREATE UNIQUE INDEX ux_password_reset_tokens_hash ON password_reset_tokens (token_hash);
CREATE INDEX ix_password_reset_tokens_user ON password_reset_tokens (user_id) WHERE used_at IS NULL;
CREATE INDEX ix_password_reset_tokens_expiry ON password_reset_tokens (expires_at);
