package mg.improve.attend.exceptions;

import java.util.List;
import org.springframework.http.HttpStatus;

/**
 * 403 - the caller is authenticated but not allowed on a visible resource
 * (e.g. {@code NOT_AN_ORGANIZER}).
 */
public class ForbiddenException extends ApiException {

    public ForbiddenException(String message) {
        this("FORBIDDEN", message);
    }

    public ForbiddenException(String code, String message) {
        this(code, message, List.of());
    }

    public ForbiddenException(String code, String message, List<ErrorDetail> details) {
        super(HttpStatus.FORBIDDEN, code, message, details);
    }
}
