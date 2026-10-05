package mg.improve.attend.validator;

import java.util.ArrayList;
import java.util.List;
import mg.improve.attend.domain.dto.request.LoginRequest;
import mg.improve.attend.exceptions.BadRequestException;
import mg.improve.attend.exceptions.ErrorDetail;
import org.springframework.stereotype.Component;

/**
 * Validates the raw {@link LoginRequest} shape.
 *
 * <p>Only presence is checked on purpose: a syntactically valid but unknown email, or a wrong
 * password, must both surface as {@code 401 INVALID_CREDENTIALS} rather than {@code 400}, so
 * the endpoint does not help enumerate accounts.
 */
@Component
public class LoginRequestValidator {

    private static final int EMAIL_MAX = 255;

    public void validate(LoginRequest request) {
        List<ErrorDetail> details = new ArrayList<>();

        if (isBlank(request.email())) {
            details.add(new ErrorDetail("email", "must not be blank"));
        } else if (request.email().length() > EMAIL_MAX) {
            details.add(new ErrorDetail("email", "must be at most " + EMAIL_MAX + " characters"));
        }
        if (isBlank(request.password())) {
            details.add(new ErrorDetail("password", "must not be blank"));
        }

        if (!details.isEmpty()) {
            throw new BadRequestException("VALIDATION_ERROR", "Validation failed", details);
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
