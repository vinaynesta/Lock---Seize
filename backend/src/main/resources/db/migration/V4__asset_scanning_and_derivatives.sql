-- Roadmap 2.2: uploads wait for a malware scan. After size and checksum are verified an asset is
-- PENDING_SCAN; the scanner moves it to VERIFIED (clean) or REJECTED (malware, bytes quarantined).
-- Memories cannot lock while any asset is not VERIFIED, so nothing unscanned is ever preserved.
ALTER TABLE memory_assets DROP CONSTRAINT memory_assets_upload_status_check;
ALTER TABLE memory_assets ADD CONSTRAINT memory_assets_upload_status_check CHECK (upload_status IN
    ('INITIATED', 'PENDING_SCAN', 'VERIFIED', 'VERIFICATION_FAILED', 'REJECTED', 'ATTACHED', 'EXPIRED'));
ALTER TABLE memory_assets ADD COLUMN scanned_at TIMESTAMPTZ;

-- Roadmap 2.1: regenerable derivatives (thumbnails) of photo assets. Originals are never modified,
-- so derivatives are NOT part of the integrity proof and are never object-locked. No immutability
-- trigger: a derivative can be (re)generated after its memory has locked.
CREATE TABLE asset_derivatives (
    id         UUID PRIMARY KEY,
    asset_id   UUID         NOT NULL REFERENCES memory_assets (id) ON DELETE CASCADE,
    variant    VARCHAR(20)  NOT NULL CHECK (variant IN ('THUMB', 'PREVIEW')),
    object_key VARCHAR(600) NOT NULL UNIQUE,
    mime_type  VARCHAR(100) NOT NULL,
    width      INT          NOT NULL,
    height     INT          NOT NULL,
    size_bytes BIGINT       NOT NULL,
    created_at TIMESTAMPTZ  NOT NULL
);
CREATE UNIQUE INDEX ux_asset_derivatives_variant ON asset_derivatives (asset_id, variant);

-- Draft assets uploaded before scanning existed were never scanned: send them through the scan.
-- (Assets of already locked memories are ATTACHED and immutable; they predate 2.2 and stay as they are.)
UPDATE memory_assets SET upload_status = 'PENDING_SCAN' WHERE upload_status = 'VERIFIED';
