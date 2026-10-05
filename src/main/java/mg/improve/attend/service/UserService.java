package mg.improve.attend.service;

import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import mg.improve.attend.domain.enums.EventRole;
import mg.improve.attend.repository.JEventOrganizerRepository;
import mg.improve.attend.repository.JEventsRepository;
import mg.improve.attend.repository.JTicketRepository;
import mg.improve.attend.repository.JTicketTierRepository;
import mg.improve.attend.repository.JUsersRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Account-level use cases. Currently the deletion of the current user together with all the
 * data that references them.
 */
@Service
@RequiredArgsConstructor
public class UserService {

    private final JUsersRepository users;
    private final JEventsRepository events;
    private final JEventOrganizerRepository organizers;
    private final JTicketTierRepository tiers;
    private final JTicketRepository tickets;

    /**
     * Hard-deletes the user and everything that depends on them, in foreign-key order.
     *
     * <p>Events they created are removed entirely, including tiers and tickets held by other
     * users. Refresh tokens are removed by the database {@code ON DELETE CASCADE}.
     */
    @Transactional
    public void deleteAccount(UUID userId) {
        // 1. Tickets the user owns, in any event.
        tickets.deleteByOwnerId(userId);

        // 2. Events they created: remove every dependent row before the event itself.
        List<UUID> createdEventIds = organizers.findEventIdsByUserAndRole(userId, EventRole.CREATOR);
        for (UUID eventId : createdEventIds) {
            tickets.deleteByEventId(eventId);
            tiers.deleteByEventId(eventId);
            organizers.deleteByEventId(eventId);
            events.deleteById(eventId);
        }

        // 3. Remaining organizer memberships (ADMIN roles) on events that survive.
        organizers.deleteByUserId(userId);

        // 4. Scan marker on surviving tickets; status is kept.
        tickets.clearCheckInByUserId(userId);

        // 5. The user. refresh_token rows go with them via the cascading FK.
        users.findById(userId).ifPresent(users::delete);
    }
}
