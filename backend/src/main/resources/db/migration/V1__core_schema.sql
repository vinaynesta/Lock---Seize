-- Lock core schema.
-- Immutability of locked memories is enforced HERE as well as in the application
-- (design doc §14: UI, domain state machine AND database constraints).
-- The only bypass is a transaction-local setting used by the deliberate,
-- delayed deletion process:  SELECT set_config('lock.admin_purge', 'on', true);

CREATE TABLE users (
    id            UUID PRIMARY KEY,
    email         VARCHAR(320) NOT NULL,
    password_hash VARCHAR(200) NOT NULL,
    display_name  VARCHAR(100) NOT NULL,
    status        VARCHAR(20)  NOT NULL,
    version       BIGINT       NOT NULL DEFAULT 0,
    created_at    TIMESTAMPTZ  NOT NULL,
    updated_at    TIMESTAMPTZ  NOT NULL
);
CREATE UNIQUE INDEX ux_users_email ON users (lower(email));

CREATE TABLE vaults (
    id          UUID PRIMARY KEY,
    type        VARCHAR(20)  NOT NULL CHECK (type IN ('PERSONAL', 'SHARED')),
    name        VARCHAR(120) NOT NULL,
    owner_id    UUID         NOT NULL REFERENCES users (id),
    state       VARCHAR(20)  NOT NULL,
    -- Vault data key, wrapped (encrypted) by the key-encryption key. Never stored in plaintext.
    wrapped_key BYTEA        NOT NULL,
    key_version INT          NOT NULL DEFAULT 1,
    version     BIGINT       NOT NULL DEFAULT 0,
    created_at  TIMESTAMPTZ  NOT NULL,
    updated_at  TIMESTAMPTZ  NOT NULL
);
CREATE INDEX ix_vaults_owner ON vaults (owner_id);

CREATE TABLE vault_members (
    vault_id   UUID        NOT NULL REFERENCES vaults (id),
    user_id    UUID        NOT NULL REFERENCES users (id),
    role       VARCHAR(20) NOT NULL CHECK (role IN ('OWNER', 'MEMBER')),
    status     VARCHAR(20) NOT NULL CHECK (status IN ('INVITED', 'ACTIVE', 'DECLINED', 'REMOVED', 'LEFT')),
    invited_by UUID REFERENCES users (id),
    invited_at TIMESTAMPTZ,
    joined_at  TIMESTAMPTZ,
    removed_at TIMESTAMPTZ,
    version    BIGINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (vault_id, user_id)
);
CREATE INDEX ix_vault_members_user ON vault_members (user_id, status);

CREATE TABLE memories (
    id            UUID PRIMARY KEY,
    vault_id      UUID        NOT NULL REFERENCES vaults (id),
    created_by    UUID        NOT NULL REFERENCES users (id),
    -- Content fields are AES-GCM ciphertext under the vault data key.
    title_ct      BYTEA       NOT NULL,
    thought_ct    BYTEA,
    feeling_ct    BYTEA,
    location_ct   BYTEA,
    occurred_at   TIMESTAMPTZ NOT NULL,
    reveal_at     TIMESTAMPTZ, -- time capsule: content hidden until this instant
    state         VARCHAR(20) NOT NULL CHECK (state IN ('DRAFT', 'SEALING', 'LOCKED', 'ARCHIVED')),
    sealing_until TIMESTAMPTZ, -- end of the cooling-off window while SEALING
    locked_at     TIMESTAMPTZ,
    content_hash  VARCHAR(64), -- SHA-256 proof of preservation, fixed when sealing starts
    key_version   INT         NOT NULL,
    idempotency_key VARCHAR(100), -- client retry safety for creation (design doc §29)
    version       BIGINT      NOT NULL DEFAULT 0,
    created_at    TIMESTAMPTZ NOT NULL,
    updated_at    TIMESTAMPTZ NOT NULL,
    CHECK (state <> 'SEALING' OR (sealing_until IS NOT NULL AND content_hash IS NOT NULL)),
    CHECK (state NOT IN ('LOCKED', 'ARCHIVED') OR (locked_at IS NOT NULL AND content_hash IS NOT NULL))
);
CREATE UNIQUE INDEX ux_memories_idempotency ON memories (created_by, idempotency_key) WHERE idempotency_key IS NOT NULL;
CREATE INDEX ix_memories_vault_timeline ON memories (vault_id, occurred_at DESC);
CREATE INDEX ix_memories_sealing ON memories (sealing_until) WHERE state = 'SEALING';

CREATE TABLE memory_assets (
    id                  UUID PRIMARY KEY,
    memory_id           UUID         NOT NULL REFERENCES memories (id) ON DELETE CASCADE,
    object_key          VARCHAR(512) NOT NULL UNIQUE,
    asset_type          VARCHAR(20)  NOT NULL CHECK (asset_type IN ('PHOTO', 'VIDEO', 'AUDIO')),
    mime_type           VARCHAR(100) NOT NULL,
    size_bytes          BIGINT       NOT NULL CHECK (size_bytes > 0),
    checksum_sha256     VARCHAR(64)  NOT NULL, -- base64 SHA-256 declared by the client, verified by storage
    upload_status       VARCHAR(30)  NOT NULL CHECK (upload_status IN
                            ('INITIATED', 'VERIFIED', 'VERIFICATION_FAILED', 'ATTACHED', 'EXPIRED')),
    encryption_metadata VARCHAR(100) NOT NULL,
    version             BIGINT       NOT NULL DEFAULT 0,
    created_at          TIMESTAMPTZ  NOT NULL,
    verified_at         TIMESTAMPTZ
);
CREATE INDEX ix_memory_assets_memory ON memory_assets (memory_id);
CREATE INDEX ix_memory_assets_pending ON memory_assets (created_at) WHERE upload_status = 'INITIATED';

-- Append-only reflections: "immutable core + mutable context" (feasibility §4).
CREATE TABLE memory_reflections (
    id         UUID PRIMARY KEY,
    memory_id  UUID        NOT NULL REFERENCES memories (id) ON DELETE CASCADE,
    author_id  UUID        NOT NULL REFERENCES users (id),
    body_ct    BYTEA       NOT NULL,
    version    BIGINT      NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX ix_memory_reflections_memory ON memory_reflections (memory_id, created_at);

-- Deliberate, delayed deletion of locked memories (legal right to erasure).
-- No FK to memories: the request record must survive the purge it triggers.
CREATE TABLE deletion_requests (
    id            UUID PRIMARY KEY,
    memory_id     UUID        NOT NULL,
    requested_by  UUID        NOT NULL REFERENCES users (id),
    reason        VARCHAR(500),
    status        VARCHAR(20) NOT NULL CHECK (status IN ('PENDING', 'CANCELLED', 'COMPLETED')),
    requested_at  TIMESTAMPTZ NOT NULL,
    execute_after TIMESTAMPTZ NOT NULL,
    closed_at     TIMESTAMPTZ,
    version       BIGINT      NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX ux_deletion_requests_pending ON deletion_requests (memory_id) WHERE status = 'PENDING';
CREATE INDEX ix_deletion_requests_due ON deletion_requests (execute_after) WHERE status = 'PENDING';

-- Audit trail. No FK to memories on purpose: the record of a purge must outlive the memory.
CREATE TABLE audit_events (
    id         UUID PRIMARY KEY,
    vault_id   UUID,
    memory_id  UUID,
    actor_id   UUID,
    event_type VARCHAR(50) NOT NULL,
    metadata   JSONB       NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX ix_audit_events_memory ON audit_events (memory_id, created_at);
CREATE INDEX ix_audit_events_vault ON audit_events (vault_id, created_at);

-- Transactional outbox: events committed atomically with state changes, dispatched asynchronously.
CREATE TABLE outbox_events (
    id             UUID PRIMARY KEY,
    aggregate_type VARCHAR(50) NOT NULL,
    aggregate_id   UUID        NOT NULL,
    event_type     VARCHAR(80) NOT NULL,
    payload        JSONB       NOT NULL,
    created_at     TIMESTAMPTZ NOT NULL,
    processed_at   TIMESTAMPTZ,
    attempts       INT         NOT NULL DEFAULT 0,
    last_error     VARCHAR(1000)
);
CREATE INDEX ix_outbox_unprocessed ON outbox_events (created_at) WHERE processed_at IS NULL;

------------------------------------------------------------------------
-- Immutability triggers
------------------------------------------------------------------------

CREATE FUNCTION lock_admin_purge_enabled() RETURNS BOOLEAN AS $$
    SELECT coalesce(current_setting('lock.admin_purge', true), '') = 'on';
$$ LANGUAGE sql STABLE;

CREATE FUNCTION enforce_memory_immutability() RETURNS TRIGGER AS $$
BEGIN
    IF lock_admin_purge_enabled() THEN
        RETURN coalesce(NEW, OLD);
    END IF;

    IF TG_OP = 'DELETE' THEN
        IF OLD.state <> 'DRAFT' THEN
            RAISE EXCEPTION 'LOCK_IMMUTABLE: memory % is % and cannot be deleted', OLD.id, OLD.state;
        END IF;
        RETURN OLD;
    END IF;

    IF NEW.id IS DISTINCT FROM OLD.id
        OR NEW.vault_id IS DISTINCT FROM OLD.vault_id
        OR NEW.created_by IS DISTINCT FROM OLD.created_by
        OR NEW.created_at IS DISTINCT FROM OLD.created_at THEN
        RAISE EXCEPTION 'LOCK_IMMUTABLE: memory identity columns cannot change';
    END IF;

    IF OLD.state IN ('SEALING', 'LOCKED', 'ARCHIVED') THEN
        IF NEW.title_ct IS DISTINCT FROM OLD.title_ct
            OR NEW.thought_ct IS DISTINCT FROM OLD.thought_ct
            OR NEW.feeling_ct IS DISTINCT FROM OLD.feeling_ct
            OR NEW.location_ct IS DISTINCT FROM OLD.location_ct
            OR NEW.occurred_at IS DISTINCT FROM OLD.occurred_at
            OR NEW.reveal_at IS DISTINCT FROM OLD.reveal_at
            OR NEW.key_version IS DISTINCT FROM OLD.key_version THEN
            RAISE EXCEPTION 'LOCK_IMMUTABLE: content of % memory % cannot change', OLD.state, OLD.id;
        END IF;
    END IF;

    IF OLD.state IN ('LOCKED', 'ARCHIVED') THEN
        IF NEW.state NOT IN ('LOCKED', 'ARCHIVED')
            OR NEW.locked_at IS DISTINCT FROM OLD.locked_at
            OR NEW.content_hash IS DISTINCT FROM OLD.content_hash THEN
            RAISE EXCEPTION 'LOCK_IMMUTABLE: memory % cannot leave the locked lifecycle', OLD.id;
        END IF;
    END IF;

    IF OLD.state = 'SEALING' AND NEW.state = 'SEALING'
        AND NEW.content_hash IS DISTINCT FROM OLD.content_hash THEN
        RAISE EXCEPTION 'LOCK_IMMUTABLE: content hash of sealing memory % cannot change', OLD.id;
    END IF;

    IF OLD.state = 'DRAFT' AND NEW.state IN ('LOCKED', 'ARCHIVED') THEN
        RAISE EXCEPTION 'LOCK_IMMUTABLE: memory % must pass through SEALING', OLD.id;
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_memories_immutability
    BEFORE UPDATE OR DELETE ON memories
    FOR EACH ROW EXECUTE FUNCTION enforce_memory_immutability();

-- Assets may only be added, changed or removed while their memory is a DRAFT.
CREATE FUNCTION enforce_asset_immutability() RETURNS TRIGGER AS $$
DECLARE
    parent_state VARCHAR(20);
BEGIN
    IF lock_admin_purge_enabled() THEN
        RETURN coalesce(NEW, OLD);
    END IF;

    -- FOR SHARE serialises against a concurrent lock of the parent memory.
    SELECT state INTO parent_state FROM memories WHERE id = coalesce(NEW.memory_id, OLD.memory_id) FOR SHARE;

    IF parent_state IS NOT NULL AND parent_state <> 'DRAFT' THEN
        RAISE EXCEPTION 'LOCK_IMMUTABLE: assets of % memory cannot be modified', parent_state;
    END IF;

    IF TG_OP = 'UPDATE' AND NEW.memory_id IS DISTINCT FROM OLD.memory_id THEN
        RAISE EXCEPTION 'LOCK_IMMUTABLE: asset cannot move between memories';
    END IF;

    RETURN coalesce(NEW, OLD);
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_memory_assets_immutability
    BEFORE INSERT OR UPDATE OR DELETE ON memory_assets
    FOR EACH ROW EXECUTE FUNCTION enforce_asset_immutability();

-- Append-only tables: rows can be inserted, never changed or removed (except by purge).
CREATE FUNCTION enforce_append_only() RETURNS TRIGGER AS $$
BEGIN
    IF lock_admin_purge_enabled() THEN
        RETURN coalesce(NEW, OLD);
    END IF;
    RAISE EXCEPTION 'LOCK_IMMUTABLE: % is append-only', TG_TABLE_NAME;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_memory_reflections_append_only
    BEFORE UPDATE OR DELETE ON memory_reflections
    FOR EACH ROW EXECUTE FUNCTION enforce_append_only();

CREATE TRIGGER trg_audit_events_append_only
    BEFORE UPDATE OR DELETE ON audit_events
    FOR EACH ROW EXECUTE FUNCTION enforce_append_only();

-- Reflections are only for memories that are locked.
CREATE FUNCTION enforce_reflection_target() RETURNS TRIGGER AS $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM memories WHERE id = NEW.memory_id AND state IN ('LOCKED', 'ARCHIVED')) THEN
        RAISE EXCEPTION 'LOCK_IMMUTABLE: reflections can only be added to locked memories';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_memory_reflections_target
    BEFORE INSERT ON memory_reflections
    FOR EACH ROW EXECUTE FUNCTION enforce_reflection_target();
