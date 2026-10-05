package mg.improve.attend.validator;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import mg.improve.attend.domain.dto.request.EventCreateRequest;
import mg.improve.attend.exceptions.BadRequestException;
import mg.improve.attend.exceptions.ErrorDetail;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;

/**
 * Validates the event request bodies and query parameters. All failures are collected so the
 * client receives every problem at once in the documented {@code VALIDATION_ERROR} body.
 */
@Component
public class EventValidator {

    private static final int TITLE_MAX = 255;
    private static final int LOCATION_MAX = 255;
    private static final int SIZE_MIN = 1;
    private static final int SIZE_MAX = 100;
    private static final String DEFAULT_SORT_FIELD = "startTime";
    private static final Set<String> SORTABLE_FIELDS = Set.of("startTime", "endTime", "title", "createdAt");

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

    /**
     * Validates the query parameters of {@code GET /api/events} and resolves the requested
     * sort into a Spring Data {@link Sort}. Absent sort defaults to {@code startTime,asc}.
     */
    public Sort validateListQuery(int page, int size, String sort) {
        List<ErrorDetail> details = new ArrayList<>();

        if (page < 0) {
            details.add(new ErrorDetail("page", "must be greater than or equal to 0"));
        }
        if (size < SIZE_MIN || size > SIZE_MAX) {
            details.add(new ErrorDetail("size", "must be between " + SIZE_MIN + " and " + SIZE_MAX));
        }
        Sort resolvedSort = parseSort(sort, details);

        if (!details.isEmpty()) {
            throw new BadRequestException("VALIDATION_ERROR", "Validation failed", details);
        }
        return resolvedSort;
    }

    private Sort parseSort(String sort, List<ErrorDetail> details) {
        if (isBlank(sort)) {
            return Sort.by(Sort.Direction.ASC, DEFAULT_SORT_FIELD);
        }
        String[] parts = sort.split(",", -1);
        if (parts.length > 2) {
            details.add(new ErrorDetail("sort",
                    "must be a property with an optional direction, e.g. startTime,asc"));
            return Sort.unsorted();
        }

        String property = parts[0].trim();
        if (!SORTABLE_FIELDS.contains(property)) {
            details.add(new ErrorDetail("sort", "unsupported sort field '" + property + "'"));
            return Sort.unsorted();
        }

        Sort.Direction direction = Sort.Direction.ASC;
        if (parts.length == 2) {
            String rawDirection = parts[1].trim().toLowerCase(Locale.ROOT);
            if (rawDirection.equals("asc")) {
                direction = Sort.Direction.ASC;
            } else if (rawDirection.equals("desc")) {
                direction = Sort.Direction.DESC;
            } else {
                details.add(new ErrorDetail("sort", "direction must be 'asc' or 'desc'"));
                return Sort.unsorted();
            }
        }
        return Sort.by(direction, property);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
