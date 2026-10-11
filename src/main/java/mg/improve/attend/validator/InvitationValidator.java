package mg.improve.attend.validator;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import mg.improve.attend.domain.dto.request.InvitationCreateRequest;
import mg.improve.attend.domain.dto.request.InvitationUpdateRequest;
import mg.improve.attend.domain.enums.InvitationStatus;
import mg.improve.attend.exceptions.BadRequestException;
import mg.improve.attend.exceptions.ErrorDetail;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;

/**
 * Validates the invitation request bodies and query parameters. All failures are collected so
 * the client receives every problem at once in the documented {@code VALIDATION_ERROR} body.
 */
@Component
public class InvitationValidator {

    private static final int SIZE_MIN = 1;
    private static final int SIZE_MAX = 100;
    private static final String DEFAULT_SORT_FIELD = "createdAt";
    private static final Set<String> SORTABLE_FIELDS = Set.of("createdAt", "invitationStatus");
    private static final Set<String> DIRECTIONS = Set.of("received", "sent", "all");

    /**
     * Validates the body of {@code POST /events/{eventId}/invitations}. Exactly one of
     * {@code username} or {@code email} must identify the user to invite.
     */
    public void validate(InvitationCreateRequest request) {
        List<ErrorDetail> details = new ArrayList<>();

        boolean hasUsername = !isBlank(request.username());
        boolean hasEmail = !isBlank(request.email());
        if (!hasUsername && !hasEmail) {
            details.add(new ErrorDetail("username", "provide either username or email"));
        } else if (hasUsername && hasEmail) {
            details.add(new ErrorDetail("username", "provide only one of username or email"));
        }

        if (!details.isEmpty()) {
            throw new BadRequestException("VALIDATION_ERROR", "Validation failed", details);
        }
    }

    /**
     * Validates the body of {@code PATCH /invitations/{invitationId}}. The receiver may only
     * accept or refuse, so {@code invitationStatus} must be present and must not be
     * {@code PENDING}.
     */
    public void validateDecision(InvitationUpdateRequest request) {
        List<ErrorDetail> details = new ArrayList<>();

        if (request.invitationStatus() == null) {
            details.add(new ErrorDetail("invitationStatus", "must not be null"));
        } else if (request.invitationStatus() == InvitationStatus.PENDING) {
            details.add(new ErrorDetail("invitationStatus", "must be ACCEPTED or DECLINED"));
        }

        if (!details.isEmpty()) {
            throw new BadRequestException("VALIDATION_ERROR", "Validation failed", details);
        }
    }

    /**
     * Validates the query parameters of {@code GET /invitations} and resolves the requested sort
     * into a Spring Data {@link Sort}. Absent sort defaults to {@code createdAt,desc}.
     */
    public Sort validateListQuery(int page, int size, String sort, String direction) {
        List<ErrorDetail> details = new ArrayList<>();

        if (page < 0) {
            details.add(new ErrorDetail("page", "must be greater than or equal to 0"));
        }
        if (size < SIZE_MIN || size > SIZE_MAX) {
            details.add(new ErrorDetail("size", "must be between " + SIZE_MIN + " and " + SIZE_MAX));
        }
        Sort resolvedSort = parseSort(sort, details);
        if (direction == null || !DIRECTIONS.contains(direction.trim().toLowerCase(Locale.ROOT))) {
            details.add(new ErrorDetail("direction", "must be one of received, sent, all"));
        }

        if (!details.isEmpty()) {
            throw new BadRequestException("VALIDATION_ERROR", "Validation failed", details);
        }
        return resolvedSort;
    }

    private Sort parseSort(String sort, List<ErrorDetail> details) {
        if (isBlank(sort)) {
            return Sort.by(Sort.Direction.DESC, DEFAULT_SORT_FIELD);
        }
        String[] parts = sort.split(",", -1);
        if (parts.length > 2) {
            details.add(new ErrorDetail("sort",
                    "must be a property with an optional direction, e.g. createdAt,desc"));
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
