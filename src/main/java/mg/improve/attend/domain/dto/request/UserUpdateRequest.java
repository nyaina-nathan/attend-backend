package mg.improve.attend.domain.dto.request;

import java.time.LocalDate;

/**
 * Body of {@code PATCH /api/users/me}. Partial update: every field is optional and only the
 * non-null ones are applied. Validation lives in {@code UserValidator}.
 */
public record UserUpdateRequest(String fullName, String username, LocalDate dateBirth) {
}
