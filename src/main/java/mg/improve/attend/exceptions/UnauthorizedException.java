package mg.improve.attend.exceptions;

import java.util.List;
import org.springframework.http.HttpStatus;

/**
 * 401 - the caller is not authenticated (e.g. {@code INVALID_CREDENTIALS}).
 */
public class UnauthorizedException extends ApiException {

    public UnauthorizedException(String message) {
        this("UNAUTHORIZED", message);
    }

    public UnauthorizedException(String code, String message) {
        this(code, message, List.of());
    }

    public UnauthorizedException(String code, String message, List<ErrorDetail> details) {
        super(HttpStatus.UNAUTHORIZED, code, message, details);
    }
}
