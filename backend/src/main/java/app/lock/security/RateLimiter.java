package app.lock.security;

import java.time.Duration;

/**
 * Sliding-window counters keyed by an arbitrary string (an IP, an email, a user id). Two stores:
 * {@link InMemoryRateLimiter} (one instance) and {@link PostgresRateLimiter} (shared by every instance,
 * chosen with {@code lock.rate-limit.store=postgres}; required before running more than one).
 */
public interface RateLimiter {

    /**
     * Records an attempt for {@code key} if it is under {@code limit} in the last {@code window}.
     * Returns how long to wait when the limit is reached, or {@link Duration#ZERO} when allowed.
     */
    Duration tryAcquire(String key, int limit, Duration window);

    /** Counts a hit without checking, e.g. a failed login that feeds the lockout counter. */
    void record(String key);

    /** Number of hits for {@code key} in the last {@code window}. */
    int count(String key, Duration window);

    /** Forgets {@code key}, e.g. clearing failed-login counts after a successful login. */
    void reset(String key);

    /** Forgets every key starting with {@code prefix}, e.g. all per-IP counters of one account. */
    void resetPrefix(String prefix);

    /** Test hook: forget everything. */
    void clear();
}
