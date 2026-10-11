package mg.improve.attend.service;

import java.util.UUID;
import lombok.RequiredArgsConstructor;
import mg.improve.attend.domain.dto.request.InvitationCreateRequest;
import mg.improve.attend.domain.dto.response.InvitationResponse;
import mg.improve.attend.domain.entity.Invitation;
import mg.improve.attend.domain.enums.EventRole;
import mg.improve.attend.domain.enums.InvitationStatus;
import mg.improve.attend.domain.mapper.InvitationMapper;
import mg.improve.attend.exceptions.ConflictException;
import mg.improve.attend.exceptions.ForbiddenException;
import mg.improve.attend.exceptions.NotFoundException;
import mg.improve.attend.repository.JEventOrganizerRepository;
import mg.improve.attend.repository.JEventsRepository;
import mg.improve.attend.repository.JInvitationRepository;
import mg.improve.attend.repository.JUsersRepository;
import mg.improve.attend.repository.entities.JEvents;
import mg.improve.attend.repository.entities.JInvitation;
import mg.improve.attend.repository.entities.JUsers;
import mg.improve.attend.validator.InvitationValidator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Invitation use cases. Per-event role checks live here, never in the URL security config,
 * which only answers "is the caller authenticated?".
 */
@Service
@RequiredArgsConstructor
public class InvitationService {

    private final JEventsRepository events;
    private final JEventOrganizerRepository organizers;
    private final JInvitationRepository invitations;
    private final JUsersRepository users;
    private final InvitationMapper invitationMapper;
    private final InvitationValidator validator;

    /**
     * Invites a user to become an {@code ADMIN} of an event. Creator only. The target user is
     * resolved by {@code username} or {@code email} and must not already be an organizer or
     * have a pending invitation. The invitation starts {@code PENDING} and grants no access
     * until the receiver accepts it.
     */
    @Transactional
    public InvitationResponse createInvitation(UUID eventId, UUID userId, InvitationCreateRequest request) {
        JEvents event = requireCreator(eventId, userId);
        validator.validate(request);

        JUsers sender = users.findById(userId)
                .orElseThrow(() -> new NotFoundException("USER_NOT_FOUND", "User not found"));
        JUsers receiver = resolveUser(request);

        if (receiver.getId().equals(userId)) {
            throw new ConflictException("CANNOT_INVITE_CREATOR", "The event creator cannot be invited");
        }
        if (organizers.existsByEvent_IdAndUser_Id(eventId, receiver.getId())) {
            throw new ConflictException("ALREADY_ORGANIZER", "User is already an organizer of this event");
        }
        if (invitations.existsByEvent_IdAndReceiver_IdAndInvitationStatus(
                eventId, receiver.getId(), InvitationStatus.PENDING)) {
            throw new ConflictException("ALREADY_INVITED", "A pending invitation already exists for this user");
        }

        JInvitation invitation = new JInvitation();
        invitation.setSender(sender);
        invitation.setReceiver(receiver);
        invitation.setEvent(event);
        invitation.setInvitationStatus(InvitationStatus.PENDING);

        // saveAndFlush so the @CreationTimestamp value is set before mapping.
        JInvitation saved = invitations.saveAndFlush(invitation);
        Invitation domain = invitationMapper.toDomain(saved);
        return InvitationResponse.from(domain);
    }

    private JUsers resolveUser(InvitationCreateRequest request) {
        if (request.username() != null && !request.username().isBlank()) {
            return users.findByUsername(request.username())
                    .orElseThrow(() -> new NotFoundException("USER_NOT_FOUND", "User not found"));
        }
        return users.findByEmail(request.email())
                .orElseThrow(() -> new NotFoundException("USER_NOT_FOUND", "User not found"));
    }

    private JEvents requireCreator(UUID eventId, UUID userId) {
        JEvents event = events.findById(eventId)
                .orElseThrow(() -> new NotFoundException("EVENT_NOT_FOUND", "Event not found"));
        if (!organizers.existsByEvent_IdAndUser_IdAndEventRole(eventId, userId, EventRole.CREATOR)) {
            throw new ForbiddenException("NOT_THE_CREATOR", "Caller is not the creator of this event");
        }
        return event;
    }
}
