package app.lock.search;

import app.lock.crypto.ContentCipher;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Writes and queries the blind index (roadmap 3.4). Called by MemoryService whenever content is
 * written, in the same transaction, so the index never lags behind a draft edit.
 */
@Component
public class SearchIndex {

    private final JdbcTemplate jdbc;
    private final ContentCipher cipher;

    public SearchIndex(JdbcTemplate jdbc, ContentCipher cipher) {
        this.jdbc = jdbc;
        this.cipher = cipher;
    }

    /** Replaces the memory's tokens with those of the given plaintext fields. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void index(UUID memoryId, UUID vaultId, ContentCipher.WrappedKey key, String... fields) {
        jdbc.update("DELETE FROM memory_search_tokens WHERE memory_id = ?", memoryId);
        byte[] searchKey = cipher.searchKey(key);
        Set<String> terms = new LinkedHashSet<>();
        for (String f : fields) {
            terms.addAll(SearchTokenizer.indexTerms(f));
        }
        List<Object[]> rows = new ArrayList<>();
        for (String term : terms) {
            rows.add(new Object[] {memoryId, vaultId, SearchTokenizer.blind(searchKey, term)});
        }
        if (!rows.isEmpty()) {
            jdbc.batchUpdate("INSERT INTO memory_search_tokens (memory_id, vault_id, token) VALUES (?, ?, ?) ON CONFLICT DO NOTHING", rows);
        }
    }

    /**
     * Memories in the vault that match every query word (AND). Each key version of the vault is
     * searched with its own derived key, since memories keep the key they were written with.
     */
    @Transactional(readOnly = true)
    public List<UUID> search(UUID vaultId, Collection<ContentCipher.WrappedKey> keys, String query, int limit) {
        Set<String> words = SearchTokenizer.words(query);
        if (words.isEmpty()) {
            return List.of();
        }
        // For each word, a memory matches if it has any of that word's terms under any key version.
        StringBuilder sql = new StringBuilder("SELECT t0.memory_id FROM ");
        List<Object> args = new ArrayList<>();
        int i = 0;
        for (String word : words) {
            List<byte[]> candidates = new ArrayList<>();
            for (ContentCipher.WrappedKey key : keys) {
                byte[] searchKey = cipher.searchKey(key);
                for (String term : SearchTokenizer.queryTerms(word)) {
                    candidates.add(SearchTokenizer.blind(searchKey, term));
                }
            }
            if (i > 0) {
                sql.append(" JOIN ");
            }
            sql.append("(SELECT DISTINCT memory_id FROM memory_search_tokens WHERE vault_id = ? AND token IN (")
                    .append(String.join(",", java.util.Collections.nCopies(candidates.size(), "?")))
                    .append(")) t").append(i);
            if (i > 0) {
                sql.append(" ON t").append(i).append(".memory_id = t0.memory_id");
            }
            args.add(vaultId);
            args.addAll(candidates);
            i++;
        }
        sql.append(" LIMIT ?");
        args.add(limit);
        return jdbc.queryForList(sql.toString(), UUID.class, args.toArray());
    }

    /** Memories with no tokens yet (created before 3.4), for the backfill. */
    @Transactional(readOnly = true)
    public List<UUID> unindexed(int limit) {
        return jdbc.queryForList("""
                SELECT m.id FROM memories m
                WHERE NOT EXISTS (SELECT 1 FROM memory_search_tokens t WHERE t.memory_id = m.id)
                  AND NOT EXISTS (SELECT 1 FROM search_index_skips s WHERE s.memory_id = m.id)
                ORDER BY m.created_at LIMIT ?""", UUID.class, limit);
    }

    /** Remembers that a memory has nothing to index (e.g. only punctuation), so the backfill stops retrying it. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void markEmpty(UUID memoryId) {
        jdbc.update("INSERT INTO search_index_skips (memory_id) VALUES (?) ON CONFLICT DO NOTHING", memoryId);
    }
}
