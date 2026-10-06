package mg.improve.attend.domain.dto.response;

import java.util.UUID;
import mg.improve.attend.domain.entity.EventOrganizer;
import mg.improve.attend.domain.entity.Users;
import mg.improve.attend.domain.enums.EventRole;

/**
 * An organizer of an event, returned by the organizer endpoints. {@code id} is the
 * {@code event_organizer} row id; {@code userId} is the user behind it.
 */
public record Organizer(
        UUID id,
        UUID userId,
        String username,
        String fullName,
        EventRole eventRole) {

    public static Organizer from(EventOrganizer organizer) {
        Users user = organizer.getUser();
        return new Organizer(
                organizer.getId(),
                user.getId(),
                user.getUsername(),
                user.getFullName(),
                organizer.getEventRole());
    }
}
