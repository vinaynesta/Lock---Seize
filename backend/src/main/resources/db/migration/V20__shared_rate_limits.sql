-- Roadmap 1.2 follow-up: rate-limit and lockout counters shared by every API instance
-- (lock.rate-limit.store=postgres). One row per hit; PostgresRateLimiter sweeps rows older than 2 hours.
-- Keys are prefixed by purpose ("ip:/auth/login:203.0.113.7", "fail:login:<email>|ip:<ip>", ...).
CREATE TABLE rate_limit_hits (
    id     BIGSERIAL   PRIMARY KEY,
    key    TEXT        NOT NULL,
    hit_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX ix_rate_limit_hits_key_time ON rate_limit_hits (key, hit_at DESC);
CREATE INDEX ix_rate_limit_hits_time ON rate_limit_hits (hit_at);
