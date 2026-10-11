package mg.improve.attend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import mg.improve.attend.domain.dto.request.InvitationCreateRequest;
import mg.improve.attend.domain.dto.request.InvitationUpdateRequest;
import mg.improve.attend.domain.dto.response.InvitationResponse;
import mg.improve.attend.domain.dto.response.PageInvitation;
import mg.improve.attend.domain.entity.Events;
import mg.improve.attend.domain.entity.Invitation;
import mg.improve.attend.domain.entity.Users;
import mg.improve.attend.domain.enums.EventRole;
import mg.improve.attend.domain.enums.EventStatus;
import mg.improve.attend.domain.enums.InvitationStatus;
import mg.improve.attend.domain.mapper.InvitationMapper;
import mg.improve.attend.exceptions.BadRequestException;
import mg.improve.attend.exceptions.ConflictException;
import mg.improve.attend.exceptions.ForbiddenException;
import mg.improve.attend.exceptions.NotFoundException;
import mg.improve.attend.repository.JEventOrganizerRepository;
import mg.improve.attend.repository.JEventsRepository;
import mg.improve.attend.repository.JInvitationRepository;
import mg.improve.attend.repository.JUsersRepository;
import mg.improve.attend.repository.entities.JEventOrganizer;
import mg.improve.attend.repository.entities.JEvents;
import mg.improve.attend.repository.entities.JInvitation;
import mg.improve.attend.repository.entities.JUsers;
import mg.improve.attend.validator.InvitationValidator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;

@ExtendWith(MockitoExtension.class)
class InvitationServiceTests {

    @Mock
    private JEventsRepository events;

    @Mock
    private JEventOrganizerRepository organizers;

    @Mock
    private JInvitationRepository invitations;

    @Mock
    private JUsersRepository users;

    @Mock
    private InvitationMapper invitationMapper;

    @Mock
    private InvitationValidator validator;

    @InjectMocks
    private InvitationService invitationService;

    // ------------------------------------------------------------------
    // createInvitation
    // ------------------------------------------------------------------

    @Test
    void createInvitation_persistsPendingInvitationForCreator() {
        UUID eventId = UUID.randomUUID();
        UUID creatorId = UUID.randomUUID();
        UUID receiverId = UUID.randomUUID();
        UUID invitationId = UUID.randomUUID();
        JEvents event = eventEntity(eventId);
        JUsers sender = userEntity(creatorId, "janedoe");
        JUsers receiver = userEntity(receiverId, "johnsmith");
        JInvitation saved = invitationEntity(invitationId, sender, receiver, event, InvitationStatus.PENDING);
        Invitation domain = invitationDomain(invitationId, userDomain(creatorId, "janedoe"),
                userDomain(receiverId, "johnsmith"), eventDomain(eventId), InvitationStatus.PENDING);

        when(events.findById(eventId)).thenReturn(Optional.of(event));
        when(organizers.existsByEvent_IdAndUser_IdAndEventRole(eventId, creatorId, EventRole.CREATOR))
                .thenReturn(true);
        when(users.findById(creatorId)).thenReturn(Optional.of(sender));
        when(users.findByUsername("johnsmith")).thenReturn(Optional.of(receiver));
        when(organizers.existsByEvent_IdAndUser_Id(eventId, receiverId)).thenReturn(false);
        when(invitations.existsByEvent_IdAndReceiver_IdAndInvitationStatus(
                eventId, receiverId, InvitationStatus.PENDING)).thenReturn(false);
        when(invitations.saveAndFlush(any(JInvitation.class))).thenReturn(saved);
        when(invitationMapper.toDomain(saved)).thenReturn(domain);

        InvitationResponse response = invitationService.createInvitation(
                eventId, creatorId, new InvitationCreateRequest("johnsmith", null));

        ArgumentCaptor<JInvitation> captor = ArgumentCaptor.forClass(JInvitation.class);
        verify(invitations).saveAndFlush(captor.capture());
        JInvitation persisted = captor.getValue();
        assertThat(persisted.getSender()).isSameAs(sender);
        assertThat(persisted.getReceiver()).isSameAs(receiver);
        assertThat(persisted.getEvent()).isSameAs(event);
        assertThat(persisted.getInvitationStatus()).isEqualTo(InvitationStatus.PENDING);

        assertThat(response.id()).isEqualTo(invitationId);
        assertThat(response.event().id()).isEqualTo(eventId);
        assertThat(response.sender().username()).isEqualTo("janedoe");
        assertThat(response.receiver().username()).isEqualTo("johnsmith");
        assertThat(response.invitationStatus()).isEqualTo(InvitationStatus.PENDING);
    }

    @Test
    void createInvitation_resolvesReceiverByEmail() {
        UUID eventId = UUID.randomUUID();
        UUID creatorId = UUID.randomUUID();
        UUID receiverId = UUID.randomUUID();
        JEvents event = eventEntity(eventId);
        JUsers sender = userEntity(creatorId, "janedoe");
        JUsers receiver = userEntity(receiverId, "johnsmith");
        JInvitation saved = invitationEntity(UUID.randomUUID(), sender, receiver, event, InvitationStatus.PENDING);
        Invitation domain = invitationDomain(saved.getId(), userDomain(creatorId, "janedoe"),
                userDomain(receiverId, "johnsmith"), eventDomain(eventId), InvitationStatus.PENDING);

        when(events.findById(eventId)).thenReturn(Optional.of(event));
        when(organizers.existsByEvent_IdAndUser_IdAndEventRole(eventId, creatorId, EventRole.CREATOR))
                .thenReturn(true);
        when(users.findById(creatorId)).thenReturn(Optional.of(sender));
        when(users.findByEmail("john@example.com")).thenReturn(Optional.of(receiver));
        when(organizers.existsByEvent_IdAndUser_Id(eventId, receiverId)).thenReturn(false);
        when(invitations.existsByEvent_IdAndReceiver_IdAndInvitationStatus(
                eventId, receiverId, InvitationStatus.PENDING)).thenReturn(false);
        when(invitations.saveAndFlush(any(JInvitation.class))).thenReturn(saved);
        when(invitationMapper.toDomain(saved)).thenReturn(domain);

        InvitationResponse response = invitationService.createInvitation(
                eventId, creatorId, new InvitationCreateRequest(null, "john@example.com"));

        assertThat(response.receiver().username()).isEqualTo("johnsmith");
        verify(users).findByEmail("john@example.com");
    }

    @Test
    void createInvitation_rejectsUnknownEvent() {
        UUID eventId = UUID.randomUUID();
        when(events.findById(eventId)).thenReturn(Optional.empty());

        assertThatExceptionOfType(NotFoundException.class)
                .isThrownBy(() -> invitationService.createInvitation(
                        eventId, UUID.randomUUID(), new InvitationCreateRequest("johnsmith", null)))
                .satisfies(ex -> {
                    assertThat(ex.getCode()).isEqualTo("EVENT_NOT_FOUND");
                    assertThat(ex.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
                });

        verifyNoInteractions(organizers, users, invitations, validator);
    }

    @Test
    void createInvitation_rejectsNonCreator() {
        UUID eventId = UUID.randomUUID();
        UUID callerId = UUID.randomUUID();
        JEvents event = eventEntity(eventId);

        when(events.findById(eventId)).thenReturn(Optional.of(event));
        when(organizers.existsByEvent_IdAndUser_IdAndEventRole(eventId, callerId, EventRole.CREATOR))
                .thenReturn(false);

        assertThatExceptionOfType(ForbiddenException.class)
                .isThrownBy(() -> invitationService.createInvitation(
                        eventId, callerId, new InvitationCreateRequest("johnsmith", null)))
                .satisfies(ex -> assertThat(ex.getCode()).isEqualTo("NOT_THE_CREATOR"));

        verifyNoInteractions(users, invitations, validator);
    }

    @Test
    void createInvitation_rejectsUnknownUser() {
        UUID eventId = UUID.randomUUID();
        UUID creatorId = UUID.randomUUID();
        JEvents event = eventEntity(eventId);

        when(events.findById(eventId)).thenReturn(Optional.of(event));
        when(organizers.existsByEvent_IdAndUser_IdAndEventRole(eventId, creatorId, EventRole.CREATOR))
                .thenReturn(true);
        when(users.findById(creatorId)).thenReturn(Optional.of(userEntity(creatorId, "janedoe")));
        when(users.findByUsername("ghost")).thenReturn(Optional.empty());

        assertThatExceptionOfType(NotFoundException.class)
                .isThrownBy(() -> invitationService.createInvitation(
                        eventId, creatorId, new InvitationCreateRequest("ghost", null)))
                .satisfies(ex -> assertThat(ex.getCode()).isEqualTo("USER_NOT_FOUND"));

        verify(invitations, never()).saveAndFlush(any());
    }

    @Test
    void createInvitation_rejectsInvitingCreator() {
        UUID eventId = UUID.randomUUID();
        UUID creatorId = UUID.randomUUID();
        JEvents event = eventEntity(eventId);
        JUsers creator = userEntity(creatorId, "janedoe");

        when(events.findById(eventId)).thenReturn(Optional.of(event));
        when(organizers.existsByEvent_IdAndUser_IdAndEventRole(eventId, creatorId, EventRole.CREATOR))
                .thenReturn(true);
        when(users.findById(creatorId)).thenReturn(Optional.of(creator));
        when(users.findByUsername("janedoe")).thenReturn(Optional.of(creator));

        assertThatExceptionOfType(ConflictException.class)
                .isThrownBy(() -> invitationService.createInvitation(
                        eventId, creatorId, new InvitationCreateRequest("janedoe", null)))
                .satisfies(ex -> assertThat(ex.getCode()).isEqualTo("CANNOT_INVITE_CREATOR"));

        verify(invitations, never()).saveAndFlush(any());
    }

    @Test
    void createInvitation_rejectsExistingOrganizer() {
        UUID eventId = UUID.randomUUID();
        UUID creatorId = UUID.randomUUID();
        UUID receiverId = UUID.randomUUID();
        JEvents event = eventEntity(eventId);
        JUsers receiver = userEntity(receiverId, "johnsmith");

        when(events.findById(eventId)).thenReturn(Optional.of(event));
        when(organizers.existsByEvent_IdAndUser_IdAndEventRole(eventId, creatorId, EventRole.CREATOR))
                .thenReturn(true);
        when(users.findById(creatorId)).thenReturn(Optional.of(userEntity(creatorId, "janedoe")));
        when(users.findByUsername("johnsmith")).thenReturn(Optional.of(receiver));
        when(organizers.existsByEvent_IdAndUser_Id(eventId, receiverId)).thenReturn(true);

        assertThatExceptionOfType(ConflictException.class)
                .isThrownBy(() -> invitationService.createInvitation(
                        eventId, creatorId, new InvitationCreateRequest("johnsmith", null)))
                .satisfies(ex -> assertThat(ex.getCode()).isEqualTo("ALREADY_ORGANIZER"));

        verify(invitations, never()).saveAndFlush(any());
        verify(invitations, never())
                .existsByEvent_IdAndReceiver_IdAndInvitationStatus(any(), any(), any());
    }

    @Test
    void createInvitation_rejectsDuplicatePendingInvitation() {
        UUID eventId = UUID.randomUUID();
        UUID creatorId = UUID.randomUUID();
        UUID receiverId = UUID.randomUUID();
        JEvents event = eventEntity(eventId);
        JUsers receiver = userEntity(receiverId, "johnsmith");

        when(events.findById(eventId)).thenReturn(Optional.of(event));
        when(organizers.existsByEvent_IdAndUser_IdAndEventRole(eventId, creatorId, EventRole.CREATOR))
                .thenReturn(true);
        when(users.findById(creatorId)).thenReturn(Optional.of(userEntity(creatorId, "janedoe")));
        when(users.findByUsername("johnsmith")).thenReturn(Optional.of(receiver));
        when(organizers.existsByEvent_IdAndUser_Id(eventId, receiverId)).thenReturn(false);
        when(invitations.existsByEvent_IdAndReceiver_IdAndInvitationStatus(
                eventId, receiverId, InvitationStatus.PENDING)).thenReturn(true);

        assertThatExceptionOfType(ConflictException.class)
                .isThrownBy(() -> invitationService.createInvitation(
                        eventId, creatorId, new InvitationCreateRequest("johnsmith", null)))
                .satisfies(ex -> assertThat(ex.getCode()).isEqualTo("ALREADY_INVITED"));

        verify(invitations, never()).saveAndFlush(any());
    }

    @Test
    void createInvitation_propagatesValidationFailure() {
        UUID eventId = UUID.randomUUID();
        UUID creatorId = UUID.randomUUID();
        JEvents event = eventEntity(eventId);

        when(events.findById(eventId)).thenReturn(Optional.of(event));
        when(organizers.existsByEvent_IdAndUser_IdAndEventRole(eventId, creatorId, EventRole.CREATOR))
                .thenReturn(true);
        doThrow(new BadRequestException("VALIDATION_ERROR", "Validation failed"))
                .when(validator).validate(any(InvitationCreateRequest.class));

        assertThatExceptionOfType(BadRequestException.class)
                .isThrownBy(() -> invitationService.createInvitation(
                        eventId, creatorId, new InvitationCreateRequest(null, null)))
                .satisfies(ex -> assertThat(ex.getCode()).isEqualTo("VALIDATION_ERROR"));

        verifyNoInteractions(users, invitations);
    }

    // ------------------------------------------------------------------
    // listInvitations
    // ------------------------------------------------------------------

    @Test
    void listInvitations_mapsPageAndDelegatesReceivedDirection() {
        UUID userId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        UUID invitationId = UUID.randomUUID();
        JEvents event = eventEntity(eventId);
        JInvitation entity = invitationEntity(invitationId, userEntity(UUID.randomUUID(), "alice"),
                userEntity(userId, "bob"), event, InvitationStatus.PENDING);
        Invitation domain = invitationDomain(invitationId, userDomain(UUID.randomUUID(), "alice"),
                userDomain(userId, "bob"), eventDomain(eventId), InvitationStatus.PENDING);

        when(validator.validateListQuery(0, 20, "createdAt,desc", "received"))
                .thenReturn(Sort.by(Sort.Direction.DESC, "createdAt"));
        when(invitations.findForUser(eq(userId), eq("RECEIVED"), isNull(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(entity), PageRequest.of(0, 20), 1));
        when(invitationMapper.toDomain(entity)).thenReturn(domain);

        PageInvitation response =
                invitationService.listInvitations(userId, "received", null, 0, 20, "createdAt,desc");

        assertThat(response.page()).isZero();
        assertThat(response.size()).isEqualTo(20);
        assertThat(response.totalElements()).isEqualTo(1);
        assertThat(response.totalPages()).isEqualTo(1);
        assertThat(response.content()).hasSize(1);
        assertThat(response.content().get(0).id()).isEqualTo(invitationId);
        assertThat(response.content().get(0).receiver().username()).isEqualTo("bob");

        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        verify(invitations).findForUser(eq(userId), eq("RECEIVED"), isNull(), pageableCaptor.capture());
        assertThat(pageableCaptor.getValue().getSort().getOrderFor("createdAt").getDirection())
                .isEqualTo(Sort.Direction.DESC);
    }

    @Test
    void listInvitations_delegatesSentDirectionAndStatusFilter() {
        UUID userId = UUID.randomUUID();

        when(validator.validateListQuery(0, 20, "createdAt,desc", "sent"))
                .thenReturn(Sort.by(Sort.Direction.DESC, "createdAt"));
        when(invitations.findForUser(eq(userId), eq("SENT"), eq(InvitationStatus.ACCEPTED), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0));

        PageInvitation response = invitationService.listInvitations(
                userId, "sent", InvitationStatus.ACCEPTED, 0, 20, "createdAt,desc");

        assertThat(response.content()).isEmpty();
        verify(invitations).findForUser(eq(userId), eq("SENT"), eq(InvitationStatus.ACCEPTED),
                any(Pageable.class));
    }

    @Test
    void listInvitations_returnsEmptyPage() {
        UUID userId = UUID.randomUUID();

        when(validator.validateListQuery(0, 20, "createdAt,desc", "all"))
                .thenReturn(Sort.by(Sort.Direction.DESC, "createdAt"));
        when(invitations.findForUser(eq(userId), eq("ALL"), isNull(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0));

        PageInvitation response =
                invitationService.listInvitations(userId, "all", null, 0, 20, "createdAt,desc");

        assertThat(response.content()).isEmpty();
        assertThat(response.totalElements()).isZero();
        assertThat(response.totalPages()).isZero();
    }

    @Test
    void listInvitations_propagatesValidationFailure() {
        UUID userId = UUID.randomUUID();
        when(validator.validateListQuery(0, 20, "createdAt,desc", "received"))
                .thenThrow(new BadRequestException("VALIDATION_ERROR", "Validation failed"));

        assertThatExceptionOfType(BadRequestException.class)
                .isThrownBy(() -> invitationService.listInvitations(
                        userId, "received", null, 0, 20, "createdAt,desc"))
                .satisfies(ex -> assertThat(ex.getCode()).isEqualTo("VALIDATION_ERROR"));

        verifyNoInteractions(invitations);
    }

    // ------------------------------------------------------------------
    // getInvitation
    // ------------------------------------------------------------------

    @Test
    void getInvitation_returnsForSender() {
        UUID eventId = UUID.randomUUID();
        UUID invitationId = UUID.randomUUID();
        UUID senderId = UUID.randomUUID();
        UUID receiverId = UUID.randomUUID();
        JEvents event = eventEntity(eventId);
        JInvitation invitation = invitationEntity(invitationId, userEntity(senderId, "alice"),
                userEntity(receiverId, "bob"), event, InvitationStatus.PENDING);
        Invitation domain = invitationDomain(invitationId, userDomain(senderId, "alice"),
                userDomain(receiverId, "bob"), eventDomain(eventId), InvitationStatus.PENDING);

        when(invitations.findDetailById(invitationId)).thenReturn(Optional.of(invitation));
        when(invitationMapper.toDomain(invitation)).thenReturn(domain);

        InvitationResponse response = invitationService.getInvitation(invitationId, senderId);

        assertThat(response.id()).isEqualTo(invitationId);
        assertThat(response.sender().id()).isEqualTo(senderId);
        assertThat(response.receiver().id()).isEqualTo(receiverId);
        assertThat(response.invitationStatus()).isEqualTo(InvitationStatus.PENDING);
    }

    @Test
    void getInvitation_returnsForReceiver() {
        UUID eventId = UUID.randomUUID();
        UUID invitationId = UUID.randomUUID();
        UUID senderId = UUID.randomUUID();
        UUID receiverId = UUID.randomUUID();
        JInvitation invitation = invitationEntity(invitationId, userEntity(senderId, "alice"),
                userEntity(receiverId, "bob"), eventEntity(eventId), InvitationStatus.PENDING);
        Invitation domain = invitationDomain(invitationId, userDomain(senderId, "alice"),
                userDomain(receiverId, "bob"), eventDomain(eventId), InvitationStatus.PENDING);

        when(invitations.findDetailById(invitationId)).thenReturn(Optional.of(invitation));
        when(invitationMapper.toDomain(invitation)).thenReturn(domain);

        InvitationResponse response = invitationService.getInvitation(invitationId, receiverId);

        assertThat(response.receiver().username()).isEqualTo("bob");
    }

    @Test
    void getInvitation_rejectsUnknownInvitation() {
        UUID invitationId = UUID.randomUUID();
        when(invitations.findDetailById(invitationId)).thenReturn(Optional.empty());

        assertThatExceptionOfType(NotFoundException.class)
                .isThrownBy(() -> invitationService.getInvitation(invitationId, UUID.randomUUID()))
                .satisfies(ex -> assertThat(ex.getCode()).isEqualTo("INVITATION_NOT_FOUND"));

        verifyNoInteractions(invitationMapper);
    }

    @Test
    void getInvitation_rejectsUnrelatedCaller() {
        UUID eventId = UUID.randomUUID();
        UUID invitationId = UUID.randomUUID();
        JInvitation invitation = invitationEntity(invitationId, userEntity(UUID.randomUUID(), "alice"),
                userEntity(UUID.randomUUID(), "bob"), eventEntity(eventId), InvitationStatus.PENDING);

        when(invitations.findDetailById(invitationId)).thenReturn(Optional.of(invitation));

        assertThatExceptionOfType(ForbiddenException.class)
                .isThrownBy(() -> invitationService.getInvitation(invitationId, UUID.randomUUID()))
                .satisfies(ex -> {
                    assertThat(ex.getCode()).isEqualTo("INVITATION_FORBIDDEN");
                    assertThat(ex.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
                });

        verifyNoInteractions(invitationMapper);
    }

    // ------------------------------------------------------------------
    // respondToInvitation
    // ------------------------------------------------------------------

    @Test
    void respondToInvitation_acceptsAndGrantsAdmin() {
        UUID eventId = UUID.randomUUID();
        UUID invitationId = UUID.randomUUID();
        UUID senderId = UUID.randomUUID();
        UUID receiverId = UUID.randomUUID();
        JEvents event = eventEntity(eventId);
        JUsers sender = userEntity(senderId, "alice");
        JUsers receiver = userEntity(receiverId, "bob");
        JInvitation invitation = invitationEntity(invitationId, sender, receiver, event, InvitationStatus.PENDING);
        JInvitation saved = invitationEntity(invitationId, sender, receiver, event, InvitationStatus.ACCEPTED);
        Invitation domain = invitationDomain(invitationId, userDomain(senderId, "alice"),
                userDomain(receiverId, "bob"), eventDomain(eventId), InvitationStatus.ACCEPTED);

        when(invitations.findDetailById(invitationId)).thenReturn(Optional.of(invitation));
        when(organizers.existsByEvent_IdAndUser_Id(eventId, receiverId)).thenReturn(false);
        when(invitations.saveAndFlush(any(JInvitation.class))).thenReturn(saved);
        when(invitationMapper.toDomain(saved)).thenReturn(domain);

        InvitationResponse response = invitationService.respondToInvitation(
                invitationId, receiverId, new InvitationUpdateRequest(InvitationStatus.ACCEPTED));

        assertThat(response.invitationStatus()).isEqualTo(InvitationStatus.ACCEPTED);

        ArgumentCaptor<JEventOrganizer> organizerCaptor = ArgumentCaptor.forClass(JEventOrganizer.class);
        verify(organizers).save(organizerCaptor.capture());
        JEventOrganizer organizer = organizerCaptor.getValue();
        assertThat(organizer.getUser()).isSameAs(receiver);
        assertThat(organizer.getEvent()).isSameAs(event);
        assertThat(organizer.getEventRole()).isEqualTo(EventRole.ADMIN);
    }

    @Test
    void respondToInvitation_acceptsWithoutDuplicateOrganizer() {
        UUID eventId = UUID.randomUUID();
        UUID invitationId = UUID.randomUUID();
        UUID receiverId = UUID.randomUUID();
        JEvents event = eventEntity(eventId);
        JUsers sender = userEntity(UUID.randomUUID(), "alice");
        JUsers receiver = userEntity(receiverId, "bob");
        JInvitation invitation = invitationEntity(invitationId, sender, receiver, event, InvitationStatus.PENDING);
        JInvitation saved = invitationEntity(invitationId, sender, receiver, event, InvitationStatus.ACCEPTED);
        Invitation domain = invitationDomain(invitationId, userDomain(sender.getId(), "alice"),
                userDomain(receiverId, "bob"), eventDomain(eventId), InvitationStatus.ACCEPTED);

        when(invitations.findDetailById(invitationId)).thenReturn(Optional.of(invitation));
        when(organizers.existsByEvent_IdAndUser_Id(eventId, receiverId)).thenReturn(true);
        when(invitations.saveAndFlush(any(JInvitation.class))).thenReturn(saved);
        when(invitationMapper.toDomain(saved)).thenReturn(domain);

        invitationService.respondToInvitation(
                invitationId, receiverId, new InvitationUpdateRequest(InvitationStatus.ACCEPTED));

        verify(organizers, never()).save(any());
    }

    @Test
    void respondToInvitation_refusesWithoutGranting() {
        UUID eventId = UUID.randomUUID();
        UUID invitationId = UUID.randomUUID();
        UUID receiverId = UUID.randomUUID();
        JEvents event = eventEntity(eventId);
        JUsers sender = userEntity(UUID.randomUUID(), "alice");
        JUsers receiver = userEntity(receiverId, "bob");
        JInvitation invitation = invitationEntity(invitationId, sender, receiver, event, InvitationStatus.PENDING);
        JInvitation saved = invitationEntity(invitationId, sender, receiver, event, InvitationStatus.DECLINED);
        Invitation domain = invitationDomain(invitationId, userDomain(sender.getId(), "alice"),
                userDomain(receiverId, "bob"), eventDomain(eventId), InvitationStatus.DECLINED);

        when(invitations.findDetailById(invitationId)).thenReturn(Optional.of(invitation));
        when(invitations.saveAndFlush(any(JInvitation.class))).thenReturn(saved);
        when(invitationMapper.toDomain(saved)).thenReturn(domain);

        InvitationResponse response = invitationService.respondToInvitation(
                invitationId, receiverId, new InvitationUpdateRequest(InvitationStatus.DECLINED));

        assertThat(response.invitationStatus()).isEqualTo(InvitationStatus.DECLINED);
        verifyNoInteractions(organizers);
    }

    @Test
    void respondToInvitation_rejectsNonReceiver() {
        UUID eventId = UUID.randomUUID();
        UUID invitationId = UUID.randomUUID();
        JInvitation invitation = invitationEntity(invitationId, userEntity(UUID.randomUUID(), "alice"),
                userEntity(UUID.randomUUID(), "bob"), eventEntity(eventId), InvitationStatus.PENDING);

        when(invitations.findDetailById(invitationId)).thenReturn(Optional.of(invitation));

        assertThatExceptionOfType(ForbiddenException.class)
                .isThrownBy(() -> invitationService.respondToInvitation(
                        invitationId, UUID.randomUUID(), new InvitationUpdateRequest(InvitationStatus.ACCEPTED)))
                .satisfies(ex -> assertThat(ex.getCode()).isEqualTo("NOT_THE_RECEIVER"));

        verifyNoInteractions(validator, invitationMapper);
        verify(invitations, never()).saveAndFlush(any());
    }

    @Test
    void respondToInvitation_rejectsUnknownInvitation() {
        UUID invitationId = UUID.randomUUID();
        when(invitations.findDetailById(invitationId)).thenReturn(Optional.empty());

        assertThatExceptionOfType(NotFoundException.class)
                .isThrownBy(() -> invitationService.respondToInvitation(
                        invitationId, UUID.randomUUID(), new InvitationUpdateRequest(InvitationStatus.ACCEPTED)))
                .satisfies(ex -> assertThat(ex.getCode()).isEqualTo("INVITATION_NOT_FOUND"));

        verifyNoInteractions(validator, organizers, invitationMapper);
    }

    @Test
    void respondToInvitation_rejectsAlreadyDecided() {
        UUID eventId = UUID.randomUUID();
        UUID invitationId = UUID.randomUUID();
        UUID receiverId = UUID.randomUUID();
        JEvents event = eventEntity(eventId);
        JInvitation invitation = invitationEntity(invitationId, userEntity(UUID.randomUUID(), "alice"),
                userEntity(receiverId, "bob"), event, InvitationStatus.ACCEPTED);

        when(invitations.findDetailById(invitationId)).thenReturn(Optional.of(invitation));

        assertThatExceptionOfType(ConflictException.class)
                .isThrownBy(() -> invitationService.respondToInvitation(
                        invitationId, receiverId, new InvitationUpdateRequest(InvitationStatus.DECLINED)))
                .satisfies(ex -> assertThat(ex.getCode()).isEqualTo("INVITATION_NOT_PENDING"));

        verifyNoInteractions(organizers, invitationMapper);
        verify(invitations, never()).saveAndFlush(any());
    }

    @Test
    void respondToInvitation_propagatesInvalidDecision() {
        UUID eventId = UUID.randomUUID();
        UUID invitationId = UUID.randomUUID();
        UUID receiverId = UUID.randomUUID();
        JInvitation invitation = invitationEntity(invitationId, userEntity(UUID.randomUUID(), "alice"),
                userEntity(receiverId, "bob"), eventEntity(eventId), InvitationStatus.PENDING);

        when(invitations.findDetailById(invitationId)).thenReturn(Optional.of(invitation));
        doThrow(new BadRequestException("VALIDATION_ERROR", "Validation failed"))
                .when(validator).validateDecision(any(InvitationUpdateRequest.class));

        assertThatExceptionOfType(BadRequestException.class)
                .isThrownBy(() -> invitationService.respondToInvitation(
                        invitationId, receiverId, new InvitationUpdateRequest(InvitationStatus.PENDING)))
                .satisfies(ex -> assertThat(ex.getCode()).isEqualTo("VALIDATION_ERROR"));

        verifyNoInteractions(organizers, invitationMapper);
        verify(invitations, never()).saveAndFlush(any());
    }

    // ------------------------------------------------------------------
    // fixtures
    // ------------------------------------------------------------------

    private static JEvents eventEntity(UUID id) {
        JEvents event = new JEvents();
        event.setId(id);
        event.setTitle("Event");
        event.setStartTime(Instant.now());
        event.setEndTime(Instant.now().plusSeconds(3600));
        event.setStatus(EventStatus.OK);
        return event;
    }

    private static JUsers userEntity(UUID id, String username) {
        JUsers user = new JUsers();
        user.setId(id);
        user.setUsername(username);
        user.setFullName("User " + username);
        return user;
    }

    private static JInvitation invitationEntity(UUID id, JUsers sender, JUsers receiver, JEvents event,
            InvitationStatus status) {
        JInvitation invitation = new JInvitation();
        invitation.setId(id);
        invitation.setSender(sender);
        invitation.setReceiver(receiver);
        invitation.setEvent(event);
        invitation.setInvitationStatus(status);
        invitation.setCreatedAt(Instant.now());
        return invitation;
    }

    private static Events eventDomain(UUID id) {
        Events event = new Events();
        event.setId(id);
        event.setTitle("Event");
        event.setStartTime(Instant.now());
        event.setEndTime(Instant.now().plusSeconds(3600));
        event.setStatus(EventStatus.OK);
        return event;
    }

    private static Users userDomain(UUID id, String username) {
        Users user = new Users();
        user.setId(id);
        user.setUsername(username);
        user.setFullName("User " + username);
        return user;
    }

    private static Invitation invitationDomain(UUID id, Users sender, Users receiver, Events event,
            InvitationStatus status) {
        Invitation invitation = new Invitation();
        invitation.setId(id);
        invitation.setSender(sender);
        invitation.setReceiver(receiver);
        invitation.setEvent(event);
        invitation.setInvitationStatus(status);
        invitation.setCreatedAt(Instant.now());
        return invitation;
    }
}
