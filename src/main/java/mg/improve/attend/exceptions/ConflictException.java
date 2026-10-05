package mg.improve.attend.exceptions;

import java.util.List;
import org.springframework.http.HttpStatus;

/**
 * 409 - the request conflicts with current state (e.g. {@code EMAIL_TAKEN},
 * {@code SOLD_OUT}, {@code TICKET_ALREADY_USED}).
 */
public class ConflictException extends ApiException {

    public ConflictException(String message) {
        this("CONFLICT", message);
    }

    public ConflictException(String code, String message) {
        this(code, message, List.of());
    }

    public ConflictException(String code, String message, List<ErrorDetail> details) {
        super(HttpStatus.CONFLICT, code, message, details);
    }
}
