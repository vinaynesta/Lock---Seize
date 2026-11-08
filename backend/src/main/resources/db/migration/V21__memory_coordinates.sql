-- Memory map (roadmap 5.1): an optional map position per memory, captured on the device (GPS or a
-- dropped pin). Stored encrypted like the other content fields ("lat,lng" under the vault key, AAD
-- "<memoryId>:coordinates"; in a sealed vault encrypted on the device). Frozen once the memory locks:
-- the immutability trigger is redefined with the new column (otherwise unchanged from V1).
ALTER TABLE memories ADD COLUMN coordinates_ct BYTEA;

CREATE OR REPLACE FUNCTION enforce_memory_immutability() RETURNS TRIGGER AS $$
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
            OR NEW.coordinates_ct IS DISTINCT FROM OLD.coordinates_ct
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
