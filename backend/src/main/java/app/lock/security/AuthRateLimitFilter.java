package app.lock.security;

import app.lock.config.LockProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Duration;
import java.util.Set;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Per-IP request limit on the unauthenticated auth endpoints, so password guessing, account
 * creation spam and refresh-token brute force are throttled before any work is done.
 * Registered inside the Spring Security chain by {@code SecurityConfig}.
 */
public class AuthRateLimitFilter extends OncePerRequestFilter {

    private static final Set<String> LIMITED = Set.of("/auth/login", "/auth/register", "/auth/refresh", "/auth/logout",
            "/auth/password-reset", "/auth/password-reset/confirm", "/auth/login/mfa");
    private static final Duration WINDOW = Duration.ofMinutes(1);

    private final RateLimiter limiter;
    private final LockProperties props;

    public AuthRateLimitFilter(RateLimiter limiter, LockProperties props) {
        this.limiter = limiter;
        this.props = props;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !"POST".equals(request.getMethod()) || !LIMITED.contains(path(request));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        // getRemoteAddr honours X-Forwarded-For only when server.forward-headers-strategy is enabled.
        String key = "ip:" + path(request) + ":" + request.getRemoteAddr();
        Duration wait = limiter.tryAcquire(key, props.rateLimit().authRequestsPerMinute(), WINDOW);
        if (wait.isZero()) {
            chain.doFilter(request, response);
            return;
        }
        long seconds = Math.max(1, (wait.toMillis() + 999) / 1000);
        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setHeader(HttpHeaders.RETRY_AFTER, String.valueOf(seconds));
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.getWriter().write("""
                {"status":429,"title":"Too Many Requests","code":"RATE_LIMITED","detail":"Too many requests; retry in %d seconds"}"""
                .formatted(seconds));
    }

    private static String path(HttpServletRequest request) {
        return request.getRequestURI().substring(request.getContextPath().length());
    }
}
