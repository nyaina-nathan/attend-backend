package mg.improve.attend.exceptions;

import java.util.List;
import org.springframework.http.HttpStatus;

/**
 * 400 - malformed or semantically invalid request (e.g. {@code VALIDATION_ERROR}).
 */
public class BadRequestException extends ApiException {

    public BadRequestException(String message) {
        this("BAD_REQUEST", message);
    }

    public BadRequestException(String code, String message) {
        this(code, message, List.of());
    }

    public BadRequestException(String code, String message, List<ErrorDetail> details) {
        super(HttpStatus.BAD_REQUEST, code, message, details);
    }
}
