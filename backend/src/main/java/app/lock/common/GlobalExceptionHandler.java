package app.lock.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    ProblemDetail handleApi(ApiException e) {
        return problem(e.status(), e.code(), e.getMessage());
    }

    @ExceptionHandler(RateLimitedException.class)
    ResponseEntity<ProblemDetail> handleRateLimited(RateLimitedException e) {
        return ResponseEntity.status(e.status())
                .header(HttpHeaders.RETRY_AFTER, String.valueOf(e.retryAfterSeconds()))
                .body(problem(e.status(), e.code(), e.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ProblemDetail handleValidation(MethodArgumentNotValidException e) {
        String detail = e.getBindingResult().getFieldErrors().stream()
                .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
                .reduce((a, b) -> a + "; " + b)
                .orElse("Invalid request");
        return problem(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", detail);
    }

    @ExceptionHandler(OptimisticLockingFailureException.class)
    ProblemDetail handleOptimisticLock(OptimisticLockingFailureException e) {
        return problem(HttpStatus.CONFLICT, "CONCURRENT_MODIFICATION", "The resource was modified concurrently; retry");
    }

    /** Last line of defence: the database immutability triggers rejected a write. */
    @ExceptionHandler(DataAccessException.class)
    ProblemDetail handleDataAccess(DataAccessException e) {
        String msg = String.valueOf(e.getMostSpecificCause().getMessage());
        if (msg.contains("LOCK_IMMUTABLE")) {
            log.warn("Immutability trigger rejected a write: {}", msg);
            return problem(HttpStatus.CONFLICT, "MEMORY_IMMUTABLE", "Locked memory content cannot be changed");
        }
        throw e;
    }

    private static ProblemDetail problem(HttpStatus status, String code, String detail) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(status, detail);
        pd.setProperty("code", code);
        return pd;
    }
}
