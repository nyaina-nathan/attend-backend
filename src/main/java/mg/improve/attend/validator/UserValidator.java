package mg.improve.attend.validator;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import mg.improve.attend.domain.dto.request.ChangePasswordRequest;
import mg.improve.attend.domain.dto.request.UserUpdateRequest;
import mg.improve.attend.exceptions.BadRequestException;
import mg.improve.attend.exceptions.ErrorDetail;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;

/**
 * Validates the partial {@link UserUpdateRequest}. Only the fields that are present are
 * checked, so an omitted field never fails validation.
 *
 * <p>All failures are collected so the client receives every problem at once in the
 * documented {@code VALIDATION_ERROR} body.
 */
@Component
public class UserValidator {

    private static final Pattern USERNAME_PATTERN = Pattern.compile("^[A-Za-z0-9_]+$");

    private static final int FULL_NAME_MAX = 255;
    private static final int USERNAME_MIN = 3;
    private static final int USERNAME_MAX = 50;
    private static final int PASSWORD_MIN = 8;
    private static final int PASSWORD_MAX = 72;
    private static final int SIZE_MIN = 1;
    private static final int SIZE_MAX = 100;
    private static final String DEFAULT_SORT_FIELD = "username";
    private static final Set<String> SORTABLE_FIELDS = Set.of("username", "fullName", "createdAt");

    public void validate(UserUpdateRequest request) {
        List<ErrorDetail> details = new ArrayList<>();

        validateFullName(request.fullName(), details);
        validateUsername(request.username(), details);
        validateDateBirth(request.dateBirth(), details);

        if (!details.isEmpty()) {
            throw new BadRequestException("VALIDATION_ERROR", "Validation failed", details);
        }
    }

    public void validate(ChangePasswordRequest request) {
        List<ErrorDetail> details = new ArrayList<>();

        if (isBlank(request.currentPassword())) {
            details.add(new ErrorDetail("currentPassword", "must not be blank"));
        }
        if (isBlank(request.newPassword())) {
            details.add(new ErrorDetail("newPassword", "must not be blank"));
        } else if (request.newPassword().length() < PASSWORD_MIN || request.newPassword().length() > PASSWORD_MAX) {
            details.add(new ErrorDetail("newPassword",
                    "must be between " + PASSWORD_MIN + " and " + PASSWORD_MAX + " characters"));
        } else if (request.newPassword().equals(request.currentPassword())) {
            details.add(new ErrorDetail("newPassword", "must differ from current password"));
        }

        if (!details.isEmpty()) {
            throw new BadRequestException("VALIDATION_ERROR", "Validation failed", details);
        }
    }

    /**
     * Validates the query parameters of {@code GET /api/users} and resolves the requested sort
     * into a Spring Data {@link Sort}. At least one of {@code username} or {@code email} must be
     * present; both may be, in which case a user matching either is returned. Absent sort defaults
     * to {@code username,asc}.
     */
    public Sort validateSearchQuery(String username, String email, int page, int size, String sort) {
        List<ErrorDetail> details = new ArrayList<>();

        if (isBlank(username) && isBlank(email)) {
            details.add(new ErrorDetail("username", "provide either username or email"));
        }
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
                    "must be a property with an optional direction, e.g. username,asc"));
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

    private void validateFullName(String fullName, List<ErrorDetail> details) {
        if (fullName == null) {
            return;
        }
        if (fullName.isBlank()) {
            details.add(new ErrorDetail("fullName", "must not be blank"));
        } else if (fullName.length() > FULL_NAME_MAX) {
            details.add(new ErrorDetail("fullName", "must be at most " + FULL_NAME_MAX + " characters"));
        }
    }

    private void validateUsername(String username, List<ErrorDetail> details) {
        if (username == null) {
            return;
        }
        if (username.length() < USERNAME_MIN || username.length() > USERNAME_MAX) {
            details.add(new ErrorDetail("username",
                    "must be between " + USERNAME_MIN + " and " + USERNAME_MAX + " characters"));
        } else if (!USERNAME_PATTERN.matcher(username).matches()) {
            details.add(new ErrorDetail("username", "must contain only letters, digits and underscores"));
        }
    }

    private void validateDateBirth(LocalDate dateBirth, List<ErrorDetail> details) {
        if (dateBirth != null && dateBirth.isAfter(LocalDate.now())) {
            details.add(new ErrorDetail("dateBirth", "must be in the past"));
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
