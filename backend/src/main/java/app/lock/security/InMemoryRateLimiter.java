package app.lock.security;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Sliding-window counters kept in this instance's memory ({@code lock.rate-limit.store=memory}, the
 * default for development and tests). With N instances an attacker gets up to N times the limit, so
 * production uses {@link PostgresRateLimiter}.
 */
@Component
@ConditionalOnProperty(name = "lock.rate-limit.store", havingValue = "memory", matchIfMissing = true)
public class InMemoryRateLimiter implements RateLimiter {

    /** Prevents unbounded growth from many distinct keys (e.g. a spray across random emails). */
    private static final int MAX_KEYS = 100_000;

    private final Map<String, Deque<Instant>> windows = new ConcurrentHashMap<>();
    private final Clock clock;

    public InMemoryRateLimiter(Clock clock) {
        this.clock = clock;
    }

    /**
     * Records an attempt for {@code key} if it is under {@code limit} in the last {@code window}.
     * Returns how long to wait when the limit is reached, or {@link Duration#ZERO} when allowed.
     */
    @Override
    public Duration tryAcquire(String key, int limit, Duration window) {
        Instant now = Instant.now(clock);
        if (windows.size() > MAX_KEYS) {
            evictIdle(now, window);
        }
        Deque<Instant> hits = windows.computeIfAbsent(key, k -> new ArrayDeque<>());
        synchronized (hits) {
            prune(hits, now.minus(window));
            if (hits.size() >= limit) {
                Duration wait = Duration.between(now, hits.peekFirst().plus(window));
                return wait.isNegative() || wait.isZero() ? Duration.ofSeconds(1) : wait;
            }
            hits.addLast(now);
            return Duration.ZERO;
        }
    }

    /** Counts a hit without checking, e.g. a failed login that feeds the lockout counter. */
    @Override
    public void record(String key) {
        Deque<Instant> hits = windows.computeIfAbsent(key, k -> new ArrayDeque<>());
        synchronized (hits) {
            hits.addLast(Instant.now(clock));
        }
    }

    /** Number of hits for {@code key} in the last {@code window}. */
    @Override
    public int count(String key, Duration window) {
        Deque<Instant> hits = windows.get(key);
        if (hits == null) {
            return 0;
        }
        synchronized (hits) {
            prune(hits, Instant.now(clock).minus(window));
            return hits.size();
        }
    }

    /** Forgets {@code key}, e.g. clearing failed-login counts after a successful login. */
    @Override
    public void reset(String key) {
        windows.remove(key);
    }

    @Override
    public void resetPrefix(String prefix) {
        windows.keySet().removeIf(k -> k.startsWith(prefix));
    }

    /** Test hook: forget everything. */
    @Override
    public void clear() {
        windows.clear();
    }

    private void evictIdle(Instant now, Duration window) {
        Instant cutoff = now.minus(window);
        windows.entrySet().removeIf(e -> {
            synchronized (e.getValue()) {
                prune(e.getValue(), cutoff);
                return e.getValue().isEmpty();
            }
        });
    }

    private static void prune(Deque<Instant> hits, Instant cutoff) {
        while (!hits.isEmpty() && !hits.peekFirst().isAfter(cutoff)) {
            hits.pollFirst();
        }
    }
}
