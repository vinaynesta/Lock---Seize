package app.lock.common;

import java.time.Duration;
import org.springframework.http.HttpStatus;

/** 429 with a {@code Retry-After} header, set by {@link GlobalExceptionHandler}. */
public class RateLimitedException extends ApiException {

    private final Duration retryAfter;

    public RateLimitedException(String code, String message, Duration retryAfter) {
        super(HttpStatus.TOO_MANY_REQUESTS, code, message);
        this.retryAfter = retryAfter;
    }

    /** Whole seconds, rounded up, never below 1. */
    public long retryAfterSeconds() {
        return Math.max(1, (retryAfter.toMillis() + 999) / 1000);
    }
}
