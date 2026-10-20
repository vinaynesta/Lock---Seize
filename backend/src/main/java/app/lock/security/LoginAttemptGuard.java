package app.lock.security;

import app.lock.common.RateLimitedException;
import app.lock.config.LockProperties;
import org.springframework.stereotype.Component;

/**
 * Temporary lockout after repeated wrong passwords for one account, whatever IP they come from.
 * Complements the per-IP limit in {@link AuthRateLimitFilter}, which a botnet could spread out.
 *
 * <p>The lockout answer is the same whether or not the account exists, so it cannot be used to
 * discover which emails are registered.
 */
@Component
public class LoginAttemptGuard {

    private final RateLimiter limiter;
    private final LockProperties props;

    public LoginAttemptGuard(RateLimiter limiter, LockProperties props) {
        this.limiter = limiter;
        this.props = props;
    }

    /** Throws 429 if {@code subject} (e.g. "login:alice@example.com") is locked out. */
    public void checkNotLocked(String subject) {
        LockProperties.RateLimit cfg = props.rateLimit();
        if (limiter.count(key(subject), cfg.lockout()) >= cfg.maxFailedLogins()) {
            throw new RateLimitedException("TOO_MANY_ATTEMPTS",
                    "Too many failed attempts; try again later", cfg.lockout());
        }
    }

    public void recordFailure(String subject) {
        limiter.record(key(subject));
    }

    public void recordSuccess(String subject) {
        limiter.reset(key(subject));
    }

    private static String key(String subject) {
        return "fail:" + subject;
    }
}
