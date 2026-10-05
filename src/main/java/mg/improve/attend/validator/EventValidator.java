package mg.improve.attend.validator;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import mg.improve.attend.domain.dto.request.EventCreateRequest;
import mg.improve.attend.exceptions.BadRequestException;
import mg.improve.attend.exceptions.ErrorDetail;
import org.springframework.stereotype.Component;

/**
 * Validates the {@link EventCreateRequest} body. All failures are collected so the client
 * receives every problem at once in the documented {@code VALIDATION_ERROR} body.
 */
@Component
public class EventValidator {

    private static final int TITLE_MAX = 255;
    private static final int LOCATION_MAX = 255;

    public void validate(EventCreateRequest request) {
        List<ErrorDetail> details = new ArrayList<>();

        validateTitle(request.title(), details);
        validateStartTime(request.startTime(), details);
        validateEndTime(request.startTime(), request.endTime(), details);
        validateLocation(request.location(), details);

        if (!details.isEmpty()) {
            throw new BadRequestException("VALIDATION_ERROR", "Validation failed", details);
        }
    }

    private void validateTitle(String title, List<ErrorDetail> details) {
        if (isBlank(title)) {
            details.add(new ErrorDetail("title", "must not be blank"));
        } else if (title.length() > TITLE_MAX) {
            details.add(new ErrorDetail("title", "must be at most " + TITLE_MAX + " characters"));
        }
    }

    private void validateStartTime(Instant startTime, List<ErrorDetail> details) {
        if (startTime == null) {
            details.add(new ErrorDetail("startTime", "must not be null"));
        }
    }

    private void validateEndTime(Instant startTime, Instant endTime, List<ErrorDetail> details) {
        if (endTime == null) {
            details.add(new ErrorDetail("endTime", "must not be null"));
        } else if (startTime != null && !endTime.isAfter(startTime)) {
            details.add(new ErrorDetail("endTime", "must be after startTime"));
        }
    }

    private void validateLocation(String location, List<ErrorDetail> details) {
        if (location != null && location.length() > LOCATION_MAX) {
            details.add(new ErrorDetail("location", "must be at most " + LOCATION_MAX + " characters"));
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
