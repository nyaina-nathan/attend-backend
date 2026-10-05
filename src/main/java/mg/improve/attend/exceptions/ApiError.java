package mg.improve.attend.exceptions;

import java.time.Instant;
import java.util.List;

/**
 * The documented JSON error body shared by {@code @RestControllerAdvice} and the
 * security entry point. {@code error} is the machine-readable code, {@code message}
 * is human-readable, and {@code details} carries optional field-level failures.
 */
public record ApiError(
        String timestamp,
        int status,
        String error,
        String message,
        String path,
        List<ErrorDetail> details) {

    public static ApiError of(int status, String error, String message, String path,
            List<ErrorDetail> details) {
        return new ApiError(
                Instant.now().toString(),
                status,
                error,
                message,
                path,
                details == null ? List.of() : List.copyOf(details));
    }
}
