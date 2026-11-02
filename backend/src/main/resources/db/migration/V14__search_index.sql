-- Roadmap 3.4: search over memories with a blind index (design note: docs/decisions.md §21).
-- Content is encrypted, so plaintext full-text search is not possible. Instead each normalised word
-- (and each word prefix of 3+ characters) of title, thought, feeling and location is stored as
-- HMAC-SHA256(search key, token), truncated to 16 bytes. The search key is derived from the vault's
-- data key at the memory's key version, so tokens of different vaults never match, and the table
-- alone reveals no words. It does reveal how often the same token appears within a vault: an
-- accepted trade-off, documented in decisions §21.
-- Not part of the immutability rules or the proof: the index can be rebuilt from content any time.
CREATE TABLE memory_search_tokens (
    memory_id UUID  NOT NULL REFERENCES memories (id) ON DELETE CASCADE,
    vault_id  UUID  NOT NULL,
    token     BYTEA NOT NULL,
    PRIMARY KEY (memory_id, token)
);
CREATE INDEX ix_memory_search_tokens_lookup ON memory_search_tokens (vault_id, token);

-- Memories are deleted only by draft discard or purge, both of which cascade here. The memory row's
-- own trigger permits those deletes; this table has no trigger of its own.

-- Memories with nothing indexable, so the backfill does not retry them forever.
CREATE TABLE search_index_skips (
    memory_id UUID PRIMARY KEY REFERENCES memories (id) ON DELETE CASCADE
);
