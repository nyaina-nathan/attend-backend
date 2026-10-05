package mg.improve.attend.domain.dto.response;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import mg.improve.attend.domain.entity.Users;

/**
 * The authenticated user's profile, returned by {@code GET/PATCH /users/me}. Adds
 * {@code updatedAt} on top of {@link UserResponse}. Never exposes {@code passwordHash}.
 */
public record UserMeResponse(
        UUID id,
        String email,
        String fullName,
        String username,
        LocalDate dateBirth,
        Instant createdAt,
        Instant updatedAt) {

    public static UserMeResponse from(Users user) {
        return new UserMeResponse(
                user.getId(),
                user.getEmail(),
                user.getFullName(),
                user.getUsername(),
                user.getDateBirth(),
                user.getCreatedAt(),
                user.getUpdatedAt());
    }
}
