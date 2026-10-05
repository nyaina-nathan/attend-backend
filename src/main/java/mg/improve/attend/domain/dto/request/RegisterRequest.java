package mg.improve.attend.domain.dto.request;

import java.time.LocalDate;

/**
 * Body of {@code POST /api/auth/register}. A plain data carrier: input validation lives in
 * {@code RegisterRequestValidator}, not here.
 */
public record RegisterRequest(
        String email,
        String password,
        String fullName,
        String username,
        LocalDate dateBirth) {
}
