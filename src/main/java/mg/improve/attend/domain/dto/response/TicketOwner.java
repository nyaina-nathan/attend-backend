package mg.improve.attend.domain.dto.response;

import java.util.UUID;
import mg.improve.attend.domain.entity.Users;

/**
 * Owner of a ticket, returned to organizers in a {@link TicketDetail}.
 */
public record TicketOwner(
        UUID id,
        String fullName,
        String username) {

    public static TicketOwner from(Users user) {
        if (user == null) {
            return null;
        }
        return new TicketOwner(user.getId(), user.getFullName(), user.getUsername());
    }
}
