package mg.improve.attend.domain.dto.response;

import java.util.UUID;
import mg.improve.attend.domain.entity.Users;

/**
 * Organizer who scanned a ticket, returned to organizers in a {@link TicketDetail}. {@code null}
 * while the ticket has not been checked in.
 */
public record CheckedBy(
        UUID id,
        String username) {

    public static CheckedBy from(Users user) {
        if (user == null) {
            return null;
        }
        return new CheckedBy(user.getId(), user.getUsername());
    }
}
