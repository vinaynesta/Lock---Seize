package app.lock.security;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Sliding-window counters in Postgres, so every API instance shares them (roadmap 1.2 follow-up:
 * in-memory counts would give an attacker N times the limit with N instances). One row per hit;
 * {@code tryAcquire} serialises per key with a transaction-scoped advisory lock, so two instances can't
 * both let the last allowed attempt through. Old rows are swept every 10 minutes.
 *
 * <p>Uses the database the API already depends on rather than adding Redis. Each auth request costs a
 * couple of indexed queries, which is small next to the password hash it guards.
 */
@Component
@ConditionalOnProperty(name = "lock.rate-limit.store", havingValue = "postgres")
public class PostgresRateLimiter implements RateLimiter {

    /** Longer than any window in use (lockout 15 min, reset requests 1 h). */
    static final Duration RETENTION = Duration.ofHours(2);

    private final JdbcTemplate jdbc;
    private final Clock clock;

    public PostgresRateLimiter(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Duration tryAcquire(String key, int limit, Duration window) {
        Instant now = Instant.now(clock);
        jdbc.queryForList("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))", key);
        Timestamp oldest = jdbc.query("""
                SELECT min(hit_at) FROM (
                    SELECT hit_at FROM rate_limit_hits WHERE key = ? AND hit_at > ? ORDER BY hit_at DESC LIMIT ?
                ) recent HAVING count(*) >= ?""",
                rs -> rs.next() ? rs.getTimestamp(1) : null, key, Timestamp.from(now.minus(window)), limit, limit);
        if (oldest != null) {
            Duration wait = Duration.between(now, oldest.toInstant().plus(window));
            return wait.isNegative() || wait.isZero() ? Duration.ofSeconds(1) : wait;
        }
        insert(key, now);
        return Duration.ZERO;
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(String key) {
        insert(key, Instant.now(clock));
    }

    @Override
    public int count(String key, Duration window) {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM rate_limit_hits WHERE key = ? AND hit_at > ?",
                Integer.class, key, Timestamp.from(Instant.now(clock).minus(window)));
        return n == null ? 0 : n;
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void reset(String key) {
        jdbc.update("DELETE FROM rate_limit_hits WHERE key = ?", key);
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void resetPrefix(String prefix) {
        String escaped = prefix.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
        jdbc.update("DELETE FROM rate_limit_hits WHERE key LIKE ?", escaped + "%");
    }

    @Override
    public void clear() {
        jdbc.update("DELETE FROM rate_limit_hits");
    }

    /** Idempotent, so it needs no scheduler lock: concurrent sweeps just delete the same rows. */
    @Scheduled(fixedDelayString = "PT10M", initialDelayString = "PT1M")
    public int sweep() {
        return jdbc.update("DELETE FROM rate_limit_hits WHERE hit_at < ?", Timestamp.from(Instant.now(clock).minus(RETENTION)));
    }

    private void insert(String key, Instant at) {
        jdbc.update("INSERT INTO rate_limit_hits (key, hit_at) VALUES (?, ?)", key, Timestamp.from(at));
    }
}
