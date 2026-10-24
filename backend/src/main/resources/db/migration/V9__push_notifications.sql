-- Roadmap 2.7: push notifications.

-- One row per device push token (Expo). Re-registering a token moves it to the signed-in user, so a
-- shared phone never notifies the previous account.
CREATE TABLE devices (
    id           UUID PRIMARY KEY,
    user_id      UUID         NOT NULL REFERENCES users (id),
    push_token   VARCHAR(300) NOT NULL,
    platform     VARCHAR(20)  NOT NULL CHECK (platform IN ('IOS', 'ANDROID', 'WEB')),
    created_at   TIMESTAMPTZ  NOT NULL,
    last_seen_at TIMESTAMPTZ  NOT NULL
);
CREATE UNIQUE INDEX ux_devices_token ON devices (push_token);
CREATE INDEX ix_devices_user ON devices (user_id);

-- Opt-outs per category. No row means every category is on. Deletion reminders are a safety feature
-- and cannot be turned off, so they have no column.
CREATE TABLE notification_preferences (
    user_id          UUID PRIMARY KEY REFERENCES users (id),
    memory_locked    BOOLEAN     NOT NULL DEFAULT TRUE,
    capsule_revealed BOOLEAN     NOT NULL DEFAULT TRUE,
    on_this_day      BOOLEAN     NOT NULL DEFAULT TRUE,
    vault_invitation BOOLEAN     NOT NULL DEFAULT TRUE,
    asset_rejected   BOOLEAN     NOT NULL DEFAULT TRUE,
    export_ready     BOOLEAN     NOT NULL DEFAULT TRUE,
    version          BIGINT      NOT NULL DEFAULT 0,
    updated_at       TIMESTAMPTZ NOT NULL
);

-- Every notification, immediate or scheduled, is a row here first. dedupe_key makes enqueueing
-- idempotent (an outbox event delivered twice still notifies once). group_key lets a later event
-- cancel what is still pending, e.g. reminders for an account deletion that was cancelled.
-- Exactly one of user_id / vault_id: a vault notification goes to its active members at send time.
-- Text is generic on purpose: memory content is encrypted and never goes to a push provider.
CREATE TABLE notifications (
    id         UUID PRIMARY KEY,
    user_id    UUID REFERENCES users (id),
    vault_id   UUID,
    category   VARCHAR(30)  NOT NULL,
    title      VARCHAR(200) NOT NULL,
    body       VARCHAR(500) NOT NULL,
    data       JSONB        NOT NULL DEFAULT '{}'::jsonb,
    dedupe_key VARCHAR(300) NOT NULL,
    group_key  VARCHAR(300),
    send_at    TIMESTAMPTZ  NOT NULL,
    status     VARCHAR(20)  NOT NULL CHECK (status IN ('PENDING', 'SENT', 'SKIPPED', 'CANCELLED', 'FAILED')),
    attempts   INT          NOT NULL DEFAULT 0,
    last_error VARCHAR(1000),
    sent_at    TIMESTAMPTZ,
    created_at TIMESTAMPTZ  NOT NULL,
    CHECK ((user_id IS NULL) <> (vault_id IS NULL))
);
CREATE UNIQUE INDEX ux_notifications_dedupe ON notifications (dedupe_key);
CREATE INDEX ix_notifications_due ON notifications (send_at) WHERE status = 'PENDING';
CREATE INDEX ix_notifications_group ON notifications (group_key) WHERE status = 'PENDING';
