package mg.improve.attend.exceptions;

import java.util.List;
import org.springframework.http.HttpStatus;

/**
 * Base class for all application exceptions. It carries the HTTP status, a
 * machine-readable error code and optional field-level details. Subclasses are
 * entity-agnostic: callers distinguish cases through the {@code code} and
 * {@code message} passed at the throw site, never through a new class per entity.
 */
public abstract class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;
    private final List<ErrorDetail> details;

    protected ApiException(HttpStatus status, String code, String message, List<ErrorDetail> details) {
        super(message);
        this.status = status;
        this.code = code;
        this.details = details == null ? List.of() : List.copyOf(details);
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getCode() {
        return code;
    }

    public List<ErrorDetail> getDetails() {
        return details;
    }
}
