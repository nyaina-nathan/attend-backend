package mg.improve.attend.domain.dto.response;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import mg.improve.attend.domain.entity.Users;

/**
 * User object returned by register/login. Never exposes {@code passwordHash}.
 */
public record UserResponse(
        UUID id,
        String email,
        String fullName,
        String username,
        LocalDate dateBirth,
        Instant createdAt) {

    public static UserResponse from(Users user) {
        return new UserResponse(
                user.getId(),
                user.getEmail(),
                user.getFullName(),
                user.getUsername(),
                user.getDateBirth(),
                user.getCreatedAt());
    }
}
