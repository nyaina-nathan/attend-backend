package mg.improve.attend.exceptions;

import java.util.List;
import org.springframework.http.HttpStatus;

/**
 * 404 - a resource does not exist, or is hidden on purpose so its existence is
 * not leaked. Used for every entity: {@code USER_NOT_FOUND}, {@code TICKET_NOT_FOUND},
 * {@code EVENT_NOT_FOUND}, ... all differ only by code/message.
 */
public class NotFoundException extends ApiException {

    public NotFoundException(String message) {
        this("NOT_FOUND", message);
    }

    public NotFoundException(String code, String message) {
        this(code, message, List.of());
    }

    public NotFoundException(String code, String message, List<ErrorDetail> details) {
        super(HttpStatus.NOT_FOUND, code, message, details);
    }
}
