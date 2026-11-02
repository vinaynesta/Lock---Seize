-- Roadmap 3.3: cold-tier archival. Originals of memories locked longer than lock.cold-tier.after are
-- tagged for a bucket lifecycle rule that moves them to a cheaper class (same key, bytes and version,
-- so checksums, retention and the integrity proof are unaffected). Derivatives stay hot so timelines
-- and Relive stay fast. archived_at is bookkeeping only: the asset trigger lets it change after locking.
ALTER TABLE memory_assets ADD COLUMN archived_at TIMESTAMPTZ;
CREATE INDEX ix_memory_assets_hot ON memory_assets (memory_id) WHERE archived_at IS NULL;

-- The asset trigger from V1 forbids any UPDATE once the memory left DRAFT. Allow changes that touch
-- only the storage-tier columns.
CREATE OR REPLACE FUNCTION enforce_asset_immutability() RETURNS TRIGGER AS $$
DECLARE
    parent_state VARCHAR(20);
BEGIN
    IF lock_admin_purge_enabled() THEN
        RETURN coalesce(NEW, OLD);
    END IF;

    IF TG_OP = 'UPDATE'
        AND NEW.id = OLD.id
        AND NEW.memory_id = OLD.memory_id
        AND NEW.object_key = OLD.object_key
        AND NEW.asset_type = OLD.asset_type
        AND NEW.mime_type = OLD.mime_type
        AND NEW.size_bytes = OLD.size_bytes
        AND NEW.checksum_sha256 = OLD.checksum_sha256
        AND NEW.upload_status = OLD.upload_status
        AND NEW.encryption_metadata = OLD.encryption_metadata
        AND NEW.created_at = OLD.created_at
        AND NEW.verified_at IS NOT DISTINCT FROM OLD.verified_at
        AND NEW.scanned_at IS NOT DISTINCT FROM OLD.scanned_at THEN
        RETURN NEW; -- only archived_at (and the optimistic-lock version) changed
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
