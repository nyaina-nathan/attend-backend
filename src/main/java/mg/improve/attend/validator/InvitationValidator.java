package mg.improve.attend.validator;

import java.util.ArrayList;
import java.util.List;
import mg.improve.attend.domain.dto.request.InvitationCreateRequest;
import mg.improve.attend.exceptions.BadRequestException;
import mg.improve.attend.exceptions.ErrorDetail;
import org.springframework.stereotype.Component;

/**
 * Validates the invitation request bodies. All failures are collected so the client receives
 * every problem at once in the documented {@code VALIDATION_ERROR} body.
 */
@Component
public class InvitationValidator {

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

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
