package mg.improve.attend.domain.dto.response;

import java.util.UUID;
import mg.improve.attend.domain.entity.Users;

/**
 * Minimal user reference nested in an {@link InvitationResponse} for its sender and receiver.
 */
public record UserRef(
        UUID id,
        String fullName,
        String username) {

    public static UserRef from(Users user) {
        if (user == null) {
            return null;
        }
        return new UserRef(user.getId(), user.getFullName(), user.getUsername());
    }
}
