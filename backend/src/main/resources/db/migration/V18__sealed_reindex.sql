-- Sealed follow-up (roadmap 3.6, after 3.6f): devices re-index sealed memories written before on-device
-- search existed. The server backfill used to mark every sealed memory as "nothing to index", which hid
-- them from the device. From now on the server backfill skips sealed vaults by query, and a skip row
-- for a sealed memory means only "the device found no words" (written by PUT /memories/{id}/search-tokens).
DELETE FROM search_index_skips s
USING memories m, vaults v
WHERE s.memory_id = m.id AND m.vault_id = v.id AND v.encryption = 'SEALED';
