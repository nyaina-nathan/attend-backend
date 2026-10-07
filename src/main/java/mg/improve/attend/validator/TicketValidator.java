package mg.improve.attend.validator;

import java.util.ArrayList;
import java.util.List;
import mg.improve.attend.exceptions.BadRequestException;
import mg.improve.attend.exceptions.ErrorDetail;
import org.springframework.stereotype.Component;

/**
 * Validates the ticket query parameters. Failures are collected so the client receives every
 * problem at once in the documented {@code VALIDATION_ERROR} body.
 */
@Component
public class TicketValidator {

    private static final int SIZE_MIN = 1;
    private static final int SIZE_MAX = 100;

    /** Validates the pagination of a ticket list query. */
    public void validateListQuery(int page, int size) {
        List<ErrorDetail> details = new ArrayList<>();

        if (page < 0) {
            details.add(new ErrorDetail("page", "must be greater than or equal to 0"));
        }
        if (size < SIZE_MIN || size > SIZE_MAX) {
            details.add(new ErrorDetail("size", "must be between " + SIZE_MIN + " and " + SIZE_MAX));
        }

        if (!details.isEmpty()) {
            throw new BadRequestException("VALIDATION_ERROR", "Validation failed", details);
        }
    }
}
