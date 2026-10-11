package mg.improve.attend.service;

import java.util.List;
import java.util.Locale;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import mg.improve.attend.domain.dto.request.InvitationCreateRequest;
import mg.improve.attend.domain.dto.response.InvitationResponse;
import mg.improve.attend.domain.dto.response.PageInvitation;
import mg.improve.attend.domain.entity.Invitation;
import mg.improve.attend.domain.enums.EventRole;
import mg.improve.attend.domain.enums.InvitationDirection;
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
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
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

    /**
     * Lists the caller's invitations, most recent first by default. {@code direction} selects
     * the side ({@code received}, {@code sent} or {@code all}) and {@code status} optionally
     * narrows by invitation status. Paginated.
     */
    @Transactional(readOnly = true)
    public PageInvitation listInvitations(UUID userId, String direction, InvitationStatus status,
            int page, int size, String sort) {
        Sort resolvedSort = validator.validateListQuery(page, size, sort, direction);
        InvitationDirection resolvedDirection =
                InvitationDirection.valueOf(direction.trim().toUpperCase(Locale.ROOT));
        Pageable pageable = PageRequest.of(page, size, resolvedSort);

        Page<JInvitation> result =
                invitations.findForUser(userId, resolvedDirection.name(), status, pageable);

        List<InvitationResponse> content = result.getContent().stream()
                .map(invitationMapper::toDomain)
                .map(InvitationResponse::from)
                .toList();

        return new PageInvitation(
                result.getNumber(),
                result.getSize(),
                result.getTotalElements(),
                result.getTotalPages(),
                content);
    }

    /**
     * Returns an invitation to its sender or its receiver. A caller who is neither gets
     * {@code 403}.
     */
    @Transactional(readOnly = true)
    public InvitationResponse getInvitation(UUID invitationId, UUID userId) {
        JInvitation invitation = invitations.findDetailById(invitationId)
                .orElseThrow(() -> new NotFoundException("INVITATION_NOT_FOUND", "Invitation not found"));

        boolean sender = invitation.getSender().getId().equals(userId);
        boolean receiver = invitation.getReceiver().getId().equals(userId);
        if (!sender && !receiver) {
            throw new ForbiddenException("INVITATION_FORBIDDEN",
                    "Caller is neither the sender nor the receiver of this invitation");
        }

        return InvitationResponse.from(invitationMapper.toDomain(invitation));
    }

    /**
     * Deletes an invitation. Sender (the event's creator) only. Only the invitation record is
     * removed: an already accepted invitation does not revoke the receiver's organizer row.
     */
    @Transactional
    public void deleteInvitation(UUID invitationId, UUID userId) {
        JInvitation invitation = invitations.findById(invitationId)
                .orElseThrow(() -> new NotFoundException("INVITATION_NOT_FOUND", "Invitation not found"));
        if (!invitation.getSender().getId().equals(userId)) {
            throw new ForbiddenException("NOT_THE_SENDER", "Caller is not the sender of this invitation");
        }
        invitations.delete(invitation);
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
