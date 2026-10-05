package mg.improve.attend.validator;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import mg.improve.attend.domain.dto.request.RegisterRequest;
import mg.improve.attend.exceptions.BadRequestException;
import mg.improve.attend.exceptions.ErrorDetail;
import org.springframework.stereotype.Component;

/**
 * Validates the raw {@link RegisterRequest} format and shape. It is intentionally free of
 * persistence: uniqueness (email/username already taken) is a service concern because it
 * needs the repository.
 *
 * <p>All failures are collected so the client receives every problem at once in the
 * documented {@code VALIDATION_ERROR} body.
 */
@Component
public class RegisterRequestValidator {

    private static final Pattern EMAIL_PATTERN =
            Pattern.compile("^[A-Za-z0-9._%+\\-]+@[A-Za-z0-9.\\-]+\\.[A-Za-z]{2,}$");
    private static final Pattern USERNAME_PATTERN = Pattern.compile("^[A-Za-z0-9_]+$");

    private static final int EMAIL_MAX = 255;
    private static final int PASSWORD_MIN = 8;
    private static final int PASSWORD_MAX = 72;
    private static final int FULL_NAME_MAX = 255;
    private static final int USERNAME_MIN = 3;
    private static final int USERNAME_MAX = 50;

    public void validate(RegisterRequest request) {
        List<ErrorDetail> details = new ArrayList<>();

        validateEmail(request.email(), details);
        validatePassword(request.password(), details);
        validateFullName(request.fullName(), details);
        validateUsername(request.username(), details);
        validateDateBirth(request.dateBirth(), details);

        if (!details.isEmpty()) {
            throw new BadRequestException("VALIDATION_ERROR", "Validation failed", details);
        }
    }

    private void validateEmail(String email, List<ErrorDetail> details) {
        if (isBlank(email)) {
            details.add(new ErrorDetail("email", "must not be blank"));
        } else if (email.length() > EMAIL_MAX) {
            details.add(new ErrorDetail("email", "must be at most " + EMAIL_MAX + " characters"));
        } else if (!EMAIL_PATTERN.matcher(email).matches()) {
            details.add(new ErrorDetail("email", "must be a valid email address"));
        }
    }

    private void validatePassword(String password, List<ErrorDetail> details) {
        if (password == null || password.isEmpty()) {
            details.add(new ErrorDetail("password", "must not be blank"));
        } else if (password.length() < PASSWORD_MIN || password.length() > PASSWORD_MAX) {
            details.add(new ErrorDetail("password",
                    "must be between " + PASSWORD_MIN + " and " + PASSWORD_MAX + " characters"));
        }
    }

    private void validateFullName(String fullName, List<ErrorDetail> details) {
        if (isBlank(fullName)) {
            details.add(new ErrorDetail("fullName", "must not be blank"));
        } else if (fullName.length() > FULL_NAME_MAX) {
            details.add(new ErrorDetail("fullName", "must be at most " + FULL_NAME_MAX + " characters"));
        }
    }

    private void validateUsername(String username, List<ErrorDetail> details) {
        if (isBlank(username)) {
            details.add(new ErrorDetail("username", "must not be blank"));
        } else if (username.length() < USERNAME_MIN || username.length() > USERNAME_MAX) {
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
