package app.lock.security;

import app.lock.common.RateLimitedException;
import app.lock.config.LockProperties;
import org.springframework.stereotype.Component;

/**
 * Temporary lockouts after repeated wrong secrets.
 *
 * <p>Sign-in (roadmap 1.2 follow-up): wrong passwords lock out the <em>IP they came from</em> for that
 * account ({@code maxFailedLogins}), so a stranger can no longer lock a known email out by guessing
 * from their own machine. A much higher count from all IPs together ({@code maxFailedLoginsPerAccount})
 * still stops a botnet spreading its guesses. Other secrets (re-authentication, MFA codes) need a valid
 * session already, so they stay per account.
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

    /** Throws 429 if this IP is locked out of {@code email}, or the account is under a spread-out attack. */
    public void checkLogin(String email, String ip) {
        LockProperties.RateLimit cfg = props.rateLimit();
        if (limiter.count(loginKey(email, ip), cfg.lockout()) >= cfg.maxFailedLogins()
                || limiter.count(key(account(email)), cfg.lockout()) >= cfg.maxFailedLoginsPerAccount()) {
            throw new RateLimitedException("TOO_MANY_ATTEMPTS", "Too many failed attempts; try again later", cfg.lockout());
        }
    }

    public void recordLoginFailure(String email, String ip) {
        limiter.record(loginKey(email, ip));
        limiter.record(key(account(email)));
    }

    /** The right password from this IP clears its count; the account-wide backstop just ages out. */
    public void recordLoginSuccess(String email, String ip) {
        limiter.reset(loginKey(email, ip));
    }

    /** After a password reset: every IP's count for the account, and the account-wide one. */
    public void clearLogin(String email) {
        limiter.resetPrefix(key(account(email)));
    }

    private static String account(String email) {
        return "login:" + email.trim().toLowerCase();
    }

    private static String loginKey(String email, String ip) {
        return key(account(email)) + "|ip:" + (ip == null ? "unknown" : ip);
    }

    private static String key(String subject) {
        return "fail:" + subject;
    }
}
