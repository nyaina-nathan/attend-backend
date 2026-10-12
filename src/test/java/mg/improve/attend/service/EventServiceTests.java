package mg.improve.attend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import mg.improve.attend.domain.dto.request.AddOrganizerRequest;
import mg.improve.attend.domain.dto.request.EventCreateRequest;
import mg.improve.attend.domain.dto.request.EventUpdateRequest;
import mg.improve.attend.domain.dto.response.EventDetail;
import mg.improve.attend.domain.dto.response.EventStats;
import mg.improve.attend.domain.dto.response.Organizer;
import mg.improve.attend.domain.dto.response.PageEventSummary;
import mg.improve.attend.domain.dto.response.TierStats;
import mg.improve.attend.domain.dto.response.TierSummary;
import mg.improve.attend.domain.entity.EventOrganizer;
import mg.improve.attend.domain.entity.Events;
import mg.improve.attend.domain.entity.Users;
import mg.improve.attend.domain.enums.EventRole;
import mg.improve.attend.domain.enums.EventStatus;
import mg.improve.attend.domain.enums.TicketStatus;
import mg.improve.attend.domain.mapper.EventOrganizerMapper;
import mg.improve.attend.domain.mapper.EventsMapper;
import mg.improve.attend.exceptions.BadRequestException;
import mg.improve.attend.exceptions.ConflictException;
import mg.improve.attend.exceptions.ForbiddenException;
import mg.improve.attend.exceptions.NotFoundException;
import mg.improve.attend.exceptions.UnauthorizedException;
import mg.improve.attend.repository.JEventOrganizerRepository;
import mg.improve.attend.repository.JEventPaymentMethodRepository;
import mg.improve.attend.repository.JEventsRepository;
import mg.improve.attend.repository.JTicketRepository;
import mg.improve.attend.repository.JTicketTierRepository;
import mg.improve.attend.repository.JUsersRepository;
import mg.improve.attend.repository.entities.JEventOrganizer;
import mg.improve.attend.repository.entities.JEvents;
import mg.improve.attend.repository.entities.JTicketTier;
import mg.improve.attend.repository.entities.JUsers;
import mg.improve.attend.validator.EventValidator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;

@ExtendWith(MockitoExtension.class)
class EventServiceTests {

    private static final Instant START = Instant.parse("2026-11-20T18:00:00Z");
    private static final Instant END = Instant.parse("2026-11-20T22:00:00Z");

    @Mock
    private JEventsRepository events;

    @Mock
    private JEventOrganizerRepository organizers;

    @Mock
    private JEventPaymentMethodRepository paymentMethods;

    @Mock
    private JTicketTierRepository tiers;

    @Mock
    private JTicketRepository tickets;

    @Mock
    private JUsersRepository users;

    @Mock
    private EventsMapper eventsMapper;

    @Mock
    private EventOrganizerMapper organizerMapper;

    @Mock
    private EventValidator validator;

    @InjectMocks
    private EventService eventService;

    // ------------------------------------------------------------------
    // createEvent
    // ------------------------------------------------------------------

    @Test
    void createEvent_persistsEventAndCreatorOrganizer() {
        UUID userId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        EventCreateRequest request = new EventCreateRequest("Jazz Night", "An evening", START, END, null, "Venue", null, null, null);
        JUsers creator = userEntity(userId);
        JEvents saved = eventEntity(eventId, true);
        Events domain = eventDomain(eventId, true);

        when(users.findById(userId)).thenReturn(Optional.of(creator));
        when(events.saveAndFlush(any(JEvents.class))).thenReturn(saved);
        when(eventsMapper.toDomain(saved)).thenReturn(domain);

        EventDetail response = eventService.createEvent(userId, request);

        verify(validator).validate(request);

        ArgumentCaptor<JEvents> eventCaptor = ArgumentCaptor.forClass(JEvents.class);
        verify(events).saveAndFlush(eventCaptor.capture());
        JEvents toSave = eventCaptor.getValue();
        assertThat(toSave.getTitle()).isEqualTo("Jazz Night");
        assertThat(toSave.getStartTime()).isEqualTo(START);
        assertThat(toSave.getEndTime()).isEqualTo(END);
        assertThat(toSave.isPublic()).isTrue();
        assertThat(toSave.getStatus()).isEqualTo(EventStatus.OK);

        ArgumentCaptor<JEventOrganizer> organizerCaptor = ArgumentCaptor.forClass(JEventOrganizer.class);
        verify(organizers).save(organizerCaptor.capture());
        JEventOrganizer organizer = organizerCaptor.getValue();
        assertThat(organizer.getEventRole()).isEqualTo(EventRole.CREATOR);
        assertThat(organizer.getUser()).isSameAs(creator);
        assertThat(organizer.getEvent()).isSameAs(saved);

        assertThat(response).isEqualTo(EventDetail.from(domain, EventRole.CREATOR, List.of()));
    }

    @Test
    void createEvent_honorsExplicitIsPublicFalse() {
        UUID userId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        EventCreateRequest request = new EventCreateRequest("Private", null, START, END, false, null, null, null, null);
        JUsers creator = userEntity(userId);
        JEvents saved = eventEntity(eventId, false);

        when(users.findById(userId)).thenReturn(Optional.of(creator));
        when(events.saveAndFlush(any(JEvents.class))).thenReturn(saved);
        when(eventsMapper.toDomain(saved)).thenReturn(eventDomain(eventId, false));

        eventService.createEvent(userId, request);

        ArgumentCaptor<JEvents> eventCaptor = ArgumentCaptor.forClass(JEvents.class);
        verify(events).saveAndFlush(eventCaptor.capture());
        assertThat(eventCaptor.getValue().isPublic()).isFalse();
    }

    @Test
    void createEvent_persistsCoordinates() {
        UUID userId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        BigDecimal latitude = new BigDecimal("-18.879200");
        BigDecimal longitude = new BigDecimal("47.507900");
        EventCreateRequest request = new EventCreateRequest("Jazz", null, START, END, null, "Venue",
                latitude, longitude, null);
        JUsers creator = userEntity(userId);
        JEvents saved = eventEntity(eventId, true);

        when(users.findById(userId)).thenReturn(Optional.of(creator));
        when(events.saveAndFlush(any(JEvents.class))).thenReturn(saved);
        when(eventsMapper.toDomain(saved)).thenReturn(eventDomain(eventId, true));

        eventService.createEvent(userId, request);

        ArgumentCaptor<JEvents> eventCaptor = ArgumentCaptor.forClass(JEvents.class);
        verify(events).saveAndFlush(eventCaptor.capture());
        assertThat(eventCaptor.getValue().getLatitude()).isEqualByComparingTo(latitude);
        assertThat(eventCaptor.getValue().getLongitude()).isEqualByComparingTo(longitude);
    }

    @Test
    void createEvent_rejectsUnknownUser() {
        UUID userId = UUID.randomUUID();
        EventCreateRequest request = new EventCreateRequest("Jazz", null, START, END, null, null, null, null, null);
        when(users.findById(userId)).thenReturn(Optional.empty());

        assertThatExceptionOfType(NotFoundException.class)
                .isThrownBy(() -> eventService.createEvent(userId, request))
                .satisfies(ex -> {
                    assertThat(ex.getCode()).isEqualTo("USER_NOT_FOUND");
                    assertThat(ex.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
                });

        verify(events, never()).saveAndFlush(any());
    }

    @Test
    void createEvent_propagatesValidationFailure() {
        UUID userId = UUID.randomUUID();
        EventCreateRequest request = new EventCreateRequest(null, null, null, null, null, null, null, null, null);
        doThrow(new BadRequestException("VALIDATION_ERROR", "Validation failed"))
                .when(validator).validate(any(EventCreateRequest.class));

        assertThatExceptionOfType(BadRequestException.class)
                .isThrownBy(() -> eventService.createEvent(userId, request))
                .satisfies(ex -> assertThat(ex.getCode()).isEqualTo("VALIDATION_ERROR"));

        verifyNoInteractions(users, events, organizers);
    }

    // ------------------------------------------------------------------
    // getEvent
    // ------------------------------------------------------------------

    @Test
    void getEvent_returnsPublicEventForAnonymous() {
        UUID eventId = UUID.randomUUID();
        JEvents entity = eventEntity(eventId, true);
        Events domain = eventDomain(eventId, true);

        when(events.findById(eventId)).thenReturn(Optional.of(entity));
        when(tiers.findByEvent_Id(eventId)).thenReturn(List.of());
        when(eventsMapper.toDomain(entity)).thenReturn(domain);

        EventDetail response = eventService.getEvent(eventId, null);

        assertThat(response).isEqualTo(EventDetail.from(domain, null, List.of()));
        verify(organizers, never()).findByEvent_IdAndUser_Id(any(), any());
    }

    @Test
    void getEvent_returnsOrganizerRoleForAuthenticatedOrganizer() {
        UUID eventId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        JEvents entity = eventEntity(eventId, true);
        Events domain = eventDomain(eventId, true);
        JEventOrganizer organizer = organizerEntity(userEntity(userId), entity, EventRole.ADMIN);

        when(events.findById(eventId)).thenReturn(Optional.of(entity));
        when(organizers.findByEvent_IdAndUser_Id(eventId, userId)).thenReturn(Optional.of(organizer));
        when(tiers.findByEvent_Id(eventId)).thenReturn(List.of());
        when(eventsMapper.toDomain(entity)).thenReturn(domain);

        EventDetail response = eventService.getEvent(eventId, userId);

        assertThat(response.myRole()).isEqualTo(EventRole.ADMIN);
    }

    @Test
    void getEvent_rejectsAnonymousOnPrivateEvent() {
        UUID eventId = UUID.randomUUID();
        JEvents entity = eventEntity(eventId, false);
        when(events.findById(eventId)).thenReturn(Optional.of(entity));

        assertThatExceptionOfType(UnauthorizedException.class)
                .isThrownBy(() -> eventService.getEvent(eventId, null))
                .satisfies(ex -> assertThat(ex.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED));

        verifyNoInteractions(tiers, eventsMapper);
    }

    @Test
    void getEvent_rejectsNonOrganizerOnPrivateEvent() {
        UUID eventId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        JEvents entity = eventEntity(eventId, false);

        when(events.findById(eventId)).thenReturn(Optional.of(entity));
        when(organizers.findByEvent_IdAndUser_Id(eventId, userId)).thenReturn(Optional.empty());

        assertThatExceptionOfType(ForbiddenException.class)
                .isThrownBy(() -> eventService.getEvent(eventId, userId))
                .satisfies(ex -> {
                    assertThat(ex.getCode()).isEqualTo("NOT_AN_ORGANIZER");
                    assertThat(ex.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
                });

        verifyNoInteractions(tiers, eventsMapper);
    }

    @Test
    void getEvent_allowsOrganizerOnPrivateEvent() {
        UUID eventId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        JEvents entity = eventEntity(eventId, false);
        Events domain = eventDomain(eventId, false);
        JEventOrganizer organizer = organizerEntity(userEntity(userId), entity, EventRole.CREATOR);

        when(events.findById(eventId)).thenReturn(Optional.of(entity));
        when(organizers.findByEvent_IdAndUser_Id(eventId, userId)).thenReturn(Optional.of(organizer));
        when(tiers.findByEvent_Id(eventId)).thenReturn(List.of());
        when(eventsMapper.toDomain(entity)).thenReturn(domain);

        EventDetail response = eventService.getEvent(eventId, userId);

        assertThat(response.myRole()).isEqualTo(EventRole.CREATOR);
    }

    @Test
    void getEvent_rejectsUnknownEvent() {
        UUID eventId = UUID.randomUUID();
        when(events.findById(eventId)).thenReturn(Optional.empty());

        assertThatExceptionOfType(NotFoundException.class)
                .isThrownBy(() -> eventService.getEvent(eventId, null))
                .satisfies(ex -> assertThat(ex.getCode()).isEqualTo("EVENT_NOT_FOUND"));
    }

    @Test
    void getEvent_computesRemainingPerTier() {
        UUID eventId = UUID.randomUUID();
        UUID vipId = UUID.randomUUID();
        UUID stdId = UUID.randomUUID();
        UUID overId = UUID.randomUUID();
        JEvents entity = eventEntity(eventId, true);
        Events domain = eventDomain(eventId, true);
        JTicketTier vip = tierEntity(vipId, "VIP", new BigDecimal("40.00"), 10);
        JTicketTier std = tierEntity(stdId, "Standard", new BigDecimal("10.00"), 5);
        JTicketTier over = tierEntity(overId, "Over", new BigDecimal("5.00"), 2);

        when(events.findById(eventId)).thenReturn(Optional.of(entity));
        when(tiers.findByEvent_Id(eventId)).thenReturn(List.of(vip, std, over));
        when(tickets.countIssuedByTier(eventId, List.of(TicketStatus.VALID, TicketStatus.USED)))
                .thenReturn(List.of(tierIssued(vipId, 3), tierIssued(stdId, 0), tierIssued(overId, 5)));
        when(eventsMapper.toDomain(entity)).thenReturn(domain);

        EventDetail response = eventService.getEvent(eventId, null);

        assertThat(response.tiers()).extracting(TierSummary::remaining).containsExactly(7, 5, 0);
    }

    // ------------------------------------------------------------------
    // listEvents
    // ------------------------------------------------------------------

    @Test
    void listEvents_mapsPageAndMinPrices() {
        UUID eventId = UUID.randomUUID();
        JEvents entity = eventEntity(eventId, true);
        Events domain = eventDomain(eventId, true);
        Page<JEvents> page = new PageImpl<>(List.of(entity));

        when(validator.validateListQuery(0, 20, "startTime,asc")).thenReturn(Sort.by("startTime"));
        when(events.findAll(ArgumentMatchers.<Specification<JEvents>>any(), any(Pageable.class))).thenReturn(page);
        when(tiers.findMinPriceByEventIds(List.of(eventId)))
                .thenReturn(List.of(minPrice(eventId, new BigDecimal("10.00"))));
        when(eventsMapper.toDomain(entity)).thenReturn(domain);

        PageEventSummary response = eventService.listEvents(null, null, null, EventStatus.OK,
                false, null, null, 0, 20, "startTime,asc");

        assertThat(response.page()).isZero();
        assertThat(response.size()).isEqualTo(1);
        assertThat(response.totalElements()).isEqualTo(1);
        assertThat(response.content()).hasSize(1);
        assertThat(response.content().get(0).minPrice()).isEqualByComparingTo("10.00");
    }

    @Test
    void listEvents_returnsNullMinPriceWhenNoTiers() {
        UUID eventId = UUID.randomUUID();
        JEvents entity = eventEntity(eventId, true);
        Page<JEvents> page = new PageImpl<>(List.of(entity));

        when(validator.validateListQuery(0, 20, "startTime,asc")).thenReturn(Sort.by("startTime"));
        when(events.findAll(ArgumentMatchers.<Specification<JEvents>>any(), any(Pageable.class))).thenReturn(page);
        when(tiers.findMinPriceByEventIds(List.of(eventId))).thenReturn(List.of());
        when(eventsMapper.toDomain(entity)).thenReturn(eventDomain(eventId, true));

        PageEventSummary response = eventService.listEvents(null, null, null, EventStatus.OK,
                false, null, null, 0, 20, "startTime,asc");

        assertThat(response.content().get(0).minPrice()).isNull();
    }

    @Test
    void listEvents_skipsMinPriceQueryWhenPageEmpty() {
        when(validator.validateListQuery(0, 20, "startTime,asc")).thenReturn(Sort.by("startTime"));
        when(events.findAll(ArgumentMatchers.<Specification<JEvents>>any(), any(Pageable.class))).thenReturn(Page.empty());

        PageEventSummary response = eventService.listEvents(null, null, null, EventStatus.OK,
                false, null, null, 0, 20, "startTime,asc");

        assertThat(response.content()).isEmpty();
        verifyNoInteractions(tiers);
    }

    @Test
    void listEvents_propagatesValidationFailure() {
        doThrow(new BadRequestException("VALIDATION_ERROR", "Validation failed"))
                .when(validator).validateListQuery(0, 0, "bogus");

        assertThatExceptionOfType(BadRequestException.class)
                .isThrownBy(() -> eventService.listEvents(null, null, null, EventStatus.OK,
                        false, null, null, 0, 0, "bogus"))
                .satisfies(ex -> assertThat(ex.getCode()).isEqualTo("VALIDATION_ERROR"));

        verifyNoInteractions(events);
    }

    // ------------------------------------------------------------------
    // updateEvent
    // ------------------------------------------------------------------

    @Test
    void updateEvent_appliesOnlyProvidedFields() {
        UUID eventId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        JEvents entity = eventEntity(eventId, true);
        EventUpdateRequest request = new EventUpdateRequest("Renamed", null, null, null, false, null,
                null, null, null, EventStatus.POSTPONED);

        when(events.findById(eventId)).thenReturn(Optional.of(entity));
        when(organizers.existsByEvent_IdAndUser_IdAndEventRole(eventId, userId, EventRole.CREATOR))
                .thenReturn(true);
        when(events.saveAndFlush(entity)).thenReturn(entity);
        when(tiers.findByEvent_Id(eventId)).thenReturn(List.of());
        when(eventsMapper.toDomain(entity)).thenReturn(eventDomain(eventId, false));

        EventDetail response = eventService.updateEvent(eventId, userId, request);

        verify(validator).validateUpdate(request, START, END, null, null);
        assertThat(entity.getTitle()).isEqualTo("Renamed");
        assertThat(entity.getDescription()).isEqualTo("Description");
        assertThat(entity.getStartTime()).isEqualTo(START);
        assertThat(entity.getEndTime()).isEqualTo(END);
        assertThat(entity.isPublic()).isFalse();
        assertThat(entity.getStatus()).isEqualTo(EventStatus.POSTPONED);
        assertThat(response.myRole()).isEqualTo(EventRole.CREATOR);
    }

    @Test
    void updateEvent_appliesCoordinates() {
        UUID eventId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        JEvents entity = eventEntity(eventId, true);
        BigDecimal latitude = new BigDecimal("-18.879200");
        BigDecimal longitude = new BigDecimal("47.507900");
        EventUpdateRequest request = new EventUpdateRequest(null, null, null, null, null, null,
                latitude, longitude, null, null);

        when(events.findById(eventId)).thenReturn(Optional.of(entity));
        when(organizers.existsByEvent_IdAndUser_IdAndEventRole(eventId, userId, EventRole.CREATOR))
                .thenReturn(true);
        when(events.saveAndFlush(entity)).thenReturn(entity);
        when(tiers.findByEvent_Id(eventId)).thenReturn(List.of());
        when(eventsMapper.toDomain(entity)).thenReturn(eventDomain(eventId, false));

        eventService.updateEvent(eventId, userId, request);

        assertThat(entity.getLatitude()).isEqualByComparingTo(latitude);
        assertThat(entity.getLongitude()).isEqualByComparingTo(longitude);
    }

    @Test
    void updateEvent_rejectsNonCreator() {
        UUID eventId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        JEvents entity = eventEntity(eventId, true);

        when(events.findById(eventId)).thenReturn(Optional.of(entity));
        when(organizers.existsByEvent_IdAndUser_IdAndEventRole(eventId, userId, EventRole.CREATOR))
                .thenReturn(false);

        assertThatExceptionOfType(ForbiddenException.class)
                .isThrownBy(() -> eventService.updateEvent(eventId, userId,
                        new EventUpdateRequest("x", null, null, null, null, null, null, null, null, null)))
                .satisfies(ex -> assertThat(ex.getCode()).isEqualTo("NOT_THE_CREATOR"));

        verify(events, never()).saveAndFlush(any());
        verifyNoInteractions(validator);
    }

    @Test
    void updateEvent_rejectsUnknownEvent() {
        UUID eventId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        when(events.findById(eventId)).thenReturn(Optional.empty());

        assertThatExceptionOfType(NotFoundException.class)
                .isThrownBy(() -> eventService.updateEvent(eventId, userId,
                        new EventUpdateRequest("x", null, null, null, null, null, null, null, null, null)))
                .satisfies(ex -> assertThat(ex.getCode()).isEqualTo("EVENT_NOT_FOUND"));

        verifyNoInteractions(validator);
    }

    @Test
    void updateEvent_propagatesValidationFailure() {
        UUID eventId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        JEvents entity = eventEntity(eventId, true);
        EventUpdateRequest request = new EventUpdateRequest(null, null, END, START, null, null, null, null, null, null);

        when(events.findById(eventId)).thenReturn(Optional.of(entity));
        when(organizers.existsByEvent_IdAndUser_IdAndEventRole(eventId, userId, EventRole.CREATOR))
                .thenReturn(true);
        doThrow(new BadRequestException("VALIDATION_ERROR", "Validation failed"))
                .when(validator).validateUpdate(any(), any(), any(), any(), any());

        assertThatExceptionOfType(BadRequestException.class)
                .isThrownBy(() -> eventService.updateEvent(eventId, userId, request));

        verify(events, never()).saveAndFlush(any());
    }

    // ------------------------------------------------------------------
    // deleteEvent
    // ------------------------------------------------------------------

    @Test
    void deleteEvent_deletesEventWhenNoTickets() {
        UUID eventId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        JEvents entity = eventEntity(eventId, true);

        when(events.findById(eventId)).thenReturn(Optional.of(entity));
        when(organizers.existsByEvent_IdAndUser_IdAndEventRole(eventId, userId, EventRole.CREATOR))
                .thenReturn(true);
        when(tickets.existsByTier_Event_Id(eventId)).thenReturn(false);

        eventService.deleteEvent(eventId, userId);

        InOrder inOrder = inOrder(organizers, paymentMethods, tiers, events);
        inOrder.verify(organizers).deleteByEventId(eventId);
        inOrder.verify(paymentMethods).deleteByEventId(eventId);
        inOrder.verify(tiers).deleteByEventId(eventId);
        inOrder.verify(events).deleteById(eventId);
    }

    @Test
    void deleteEvent_rejectsWhenTicketsExist() {
        UUID eventId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        JEvents entity = eventEntity(eventId, true);

        when(events.findById(eventId)).thenReturn(Optional.of(entity));
        when(organizers.existsByEvent_IdAndUser_IdAndEventRole(eventId, userId, EventRole.CREATOR))
                .thenReturn(true);
        when(tickets.existsByTier_Event_Id(eventId)).thenReturn(true);

        assertThatExceptionOfType(ConflictException.class)
                .isThrownBy(() -> eventService.deleteEvent(eventId, userId))
                .satisfies(ex -> {
                    assertThat(ex.getCode()).isEqualTo("EVENT_HAS_TICKETS");
                    assertThat(ex.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                });

        verify(tiers, never()).deleteByEventId(any());
        verify(events, never()).deleteById(any());
    }

    @Test
    void deleteEvent_rejectsNonCreator() {
        UUID eventId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        JEvents entity = eventEntity(eventId, true);

        when(events.findById(eventId)).thenReturn(Optional.of(entity));
        when(organizers.existsByEvent_IdAndUser_IdAndEventRole(eventId, userId, EventRole.CREATOR))
                .thenReturn(false);

        assertThatExceptionOfType(ForbiddenException.class)
                .isThrownBy(() -> eventService.deleteEvent(eventId, userId))
                .satisfies(ex -> assertThat(ex.getCode()).isEqualTo("NOT_THE_CREATOR"));

        verifyNoInteractions(tickets, tiers);
        verify(events, never()).deleteById(any());
    }

    @Test
    void deleteEvent_rejectsUnknownEvent() {
        UUID eventId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        when(events.findById(eventId)).thenReturn(Optional.empty());

        assertThatExceptionOfType(NotFoundException.class)
                .isThrownBy(() -> eventService.deleteEvent(eventId, userId))
                .satisfies(ex -> assertThat(ex.getCode()).isEqualTo("EVENT_NOT_FOUND"));

        verifyNoInteractions(tickets, tiers);
        verify(events, never()).deleteById(any());
    }

    // ------------------------------------------------------------------
    // cancelEvent
    // ------------------------------------------------------------------

    @Test
    void cancelEvent_setsStatusCancelled() {
        UUID eventId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        JEvents entity = eventEntity(eventId, true);

        when(events.findById(eventId)).thenReturn(Optional.of(entity));
        when(organizers.existsByEvent_IdAndUser_IdAndEventRole(eventId, userId, EventRole.CREATOR))
                .thenReturn(true);
        when(events.saveAndFlush(entity)).thenReturn(entity);
        when(tiers.findByEvent_Id(eventId)).thenReturn(List.of());
        Events cancelledDomain = eventDomain(eventId, true);
        cancelledDomain.setStatus(EventStatus.CANCELLED);
        when(eventsMapper.toDomain(entity)).thenReturn(cancelledDomain);

        EventDetail response = eventService.cancelEvent(eventId, userId);

        assertThat(entity.getStatus()).isEqualTo(EventStatus.CANCELLED);
        assertThat(response.status()).isEqualTo(EventStatus.CANCELLED);
        assertThat(response.myRole()).isEqualTo(EventRole.CREATOR);
    }

    @Test
    void cancelEvent_rejectsNonCreator() {
        UUID eventId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        JEvents entity = eventEntity(eventId, true);

        when(events.findById(eventId)).thenReturn(Optional.of(entity));
        when(organizers.existsByEvent_IdAndUser_IdAndEventRole(eventId, userId, EventRole.CREATOR))
                .thenReturn(false);

        assertThatExceptionOfType(ForbiddenException.class)
                .isThrownBy(() -> eventService.cancelEvent(eventId, userId))
                .satisfies(ex -> assertThat(ex.getCode()).isEqualTo("NOT_THE_CREATOR"));

        verify(events, never()).saveAndFlush(any());
    }

    @Test
    void cancelEvent_rejectsUnknownEvent() {
        UUID eventId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        when(events.findById(eventId)).thenReturn(Optional.empty());

        assertThatExceptionOfType(NotFoundException.class)
                .isThrownBy(() -> eventService.cancelEvent(eventId, userId))
                .satisfies(ex -> assertThat(ex.getCode()).isEqualTo("EVENT_NOT_FOUND"));
    }

    // ------------------------------------------------------------------
    // listOrganizers
    // ------------------------------------------------------------------

    @Test
    void listOrganizers_returnsAllForAnyOrganizer() {
        UUID eventId = UUID.randomUUID();
        UUID creatorId = UUID.randomUUID();
        UUID adminId = UUID.randomUUID();
        JEvents entity = eventEntity(eventId, true);
        JEventOrganizer creatorRow = organizerEntity(userEntity(creatorId), entity, EventRole.CREATOR);
        JEventOrganizer adminRow = organizerEntity(userEntity(adminId), entity, EventRole.ADMIN);
        EventOrganizer creatorDomain =
                organizerDomain(UUID.randomUUID(), userDomain(creatorId, "creator", "Creator"), EventRole.CREATOR);
        EventOrganizer adminDomain =
                organizerDomain(UUID.randomUUID(), userDomain(adminId, "admin", "Admin"), EventRole.ADMIN);

        when(events.findById(eventId)).thenReturn(Optional.of(entity));
        when(organizers.findByEvent_Id(eventId)).thenReturn(List.of(creatorRow, adminRow));
        when(organizerMapper.toDomain(creatorRow)).thenReturn(creatorDomain);
        when(organizerMapper.toDomain(adminRow)).thenReturn(adminDomain);

        // The caller is the ADMIN, proving non-creator organizers may list.
        List<Organizer> response = eventService.listOrganizers(eventId, adminId);

        assertThat(response).extracting(Organizer::eventRole)
                .containsExactlyInAnyOrder(EventRole.CREATOR, EventRole.ADMIN);
        assertThat(response).extracting(Organizer::username)
                .containsExactlyInAnyOrder("creator", "admin");
    }

    @Test
    void listOrganizers_rejectsNonOrganizer() {
        UUID eventId = UUID.randomUUID();
        UUID callerId = UUID.randomUUID();
        JEvents entity = eventEntity(eventId, true);
        JEventOrganizer creatorRow = organizerEntity(userEntity(UUID.randomUUID()), entity, EventRole.CREATOR);

        when(events.findById(eventId)).thenReturn(Optional.of(entity));
        when(organizers.findByEvent_Id(eventId)).thenReturn(List.of(creatorRow));

        assertThatExceptionOfType(ForbiddenException.class)
                .isThrownBy(() -> eventService.listOrganizers(eventId, callerId))
                .satisfies(ex -> {
                    assertThat(ex.getCode()).isEqualTo("NOT_AN_ORGANIZER");
                    assertThat(ex.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
                });

        verifyNoInteractions(organizerMapper);
    }

    @Test
    void listOrganizers_rejectsUnknownEvent() {
        UUID eventId = UUID.randomUUID();
        when(events.findById(eventId)).thenReturn(Optional.empty());

        assertThatExceptionOfType(NotFoundException.class)
                .isThrownBy(() -> eventService.listOrganizers(eventId, UUID.randomUUID()))
                .satisfies(ex -> assertThat(ex.getCode()).isEqualTo("EVENT_NOT_FOUND"));

        verify(organizers, never()).findByEvent_Id(any());
    }

    // ------------------------------------------------------------------
    // addOrganizer
    // ------------------------------------------------------------------

    @Test
    void addOrganizer_addsAdminForCreator() {
        UUID eventId = UUID.randomUUID();
        UUID creatorId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        JEvents entity = eventEntity(eventId, true);
        JUsers target = userEntity(targetId);
        AddOrganizerRequest request = new AddOrganizerRequest("admin", null);

        when(events.findById(eventId)).thenReturn(Optional.of(entity));
        when(organizers.existsByEvent_IdAndUser_IdAndEventRole(eventId, creatorId, EventRole.CREATOR))
                .thenReturn(true);
        when(users.findByUsername("admin")).thenReturn(Optional.of(target));
        when(organizers.existsByEvent_IdAndUser_Id(eventId, targetId)).thenReturn(false);
        when(organizers.saveAndFlush(any(JEventOrganizer.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(organizerMapper.toDomain(any(JEventOrganizer.class)))
                .thenReturn(organizerDomain(UUID.randomUUID(), userDomain(targetId, "admin", "Admin"), EventRole.ADMIN));

        Organizer response = eventService.addOrganizer(eventId, creatorId, request);

        verify(validator).validate(request);
        ArgumentCaptor<JEventOrganizer> captor = ArgumentCaptor.forClass(JEventOrganizer.class);
        verify(organizers).saveAndFlush(captor.capture());
        assertThat(captor.getValue().getEventRole()).isEqualTo(EventRole.ADMIN);
        assertThat(captor.getValue().getUser()).isSameAs(target);
        assertThat(captor.getValue().getEvent()).isSameAs(entity);
        assertThat(response.eventRole()).isEqualTo(EventRole.ADMIN);
    }

    @Test
    void addOrganizer_resolvesByEmail() {
        UUID eventId = UUID.randomUUID();
        UUID creatorId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        JEvents entity = eventEntity(eventId, true);
        JUsers target = userEntity(targetId);
        AddOrganizerRequest request = new AddOrganizerRequest(null, "admin@example.com");

        when(events.findById(eventId)).thenReturn(Optional.of(entity));
        when(organizers.existsByEvent_IdAndUser_IdAndEventRole(eventId, creatorId, EventRole.CREATOR))
                .thenReturn(true);
        when(users.findByEmail("admin@example.com")).thenReturn(Optional.of(target));
        when(organizers.existsByEvent_IdAndUser_Id(eventId, targetId)).thenReturn(false);
        when(organizers.saveAndFlush(any(JEventOrganizer.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(organizerMapper.toDomain(any(JEventOrganizer.class)))
                .thenReturn(organizerDomain(UUID.randomUUID(), userDomain(targetId, "admin", "Admin"), EventRole.ADMIN));

        Organizer response = eventService.addOrganizer(eventId, creatorId, request);

        verify(users).findByEmail("admin@example.com");
        verify(users, never()).findByUsername(any());
        assertThat(response.eventRole()).isEqualTo(EventRole.ADMIN);
    }

    @Test
    void addOrganizer_rejectsNonCreator() {
        UUID eventId = UUID.randomUUID();
        UUID callerId = UUID.randomUUID();
        JEvents entity = eventEntity(eventId, true);

        when(events.findById(eventId)).thenReturn(Optional.of(entity));
        when(organizers.existsByEvent_IdAndUser_IdAndEventRole(eventId, callerId, EventRole.CREATOR))
                .thenReturn(false);

        assertThatExceptionOfType(ForbiddenException.class)
                .isThrownBy(() -> eventService.addOrganizer(eventId, callerId,
                        new AddOrganizerRequest("admin", null)))
                .satisfies(ex -> assertThat(ex.getCode()).isEqualTo("NOT_THE_CREATOR"));

        verifyNoInteractions(validator, users);
    }

    @Test
    void addOrganizer_rejectsUnknownEvent() {
        UUID eventId = UUID.randomUUID();
        when(events.findById(eventId)).thenReturn(Optional.empty());

        assertThatExceptionOfType(NotFoundException.class)
                .isThrownBy(() -> eventService.addOrganizer(eventId, UUID.randomUUID(),
                        new AddOrganizerRequest("admin", null)))
                .satisfies(ex -> assertThat(ex.getCode()).isEqualTo("EVENT_NOT_FOUND"));

        verifyNoInteractions(validator, users);
    }

    @Test
    void addOrganizer_propagatesValidationFailure() {
        UUID eventId = UUID.randomUUID();
        UUID creatorId = UUID.randomUUID();
        JEvents entity = eventEntity(eventId, true);
        AddOrganizerRequest request = new AddOrganizerRequest(null, null);

        when(events.findById(eventId)).thenReturn(Optional.of(entity));
        when(organizers.existsByEvent_IdAndUser_IdAndEventRole(eventId, creatorId, EventRole.CREATOR))
                .thenReturn(true);
        doThrow(new BadRequestException("VALIDATION_ERROR", "Validation failed"))
                .when(validator).validate(any(AddOrganizerRequest.class));

        assertThatExceptionOfType(BadRequestException.class)
                .isThrownBy(() -> eventService.addOrganizer(eventId, creatorId, request))
                .satisfies(ex -> assertThat(ex.getCode()).isEqualTo("VALIDATION_ERROR"));

        verifyNoInteractions(users);
        verify(organizers, never()).saveAndFlush(any());
    }

    @Test
    void addOrganizer_rejectsUnknownUser() {
        UUID eventId = UUID.randomUUID();
        UUID creatorId = UUID.randomUUID();
        JEvents entity = eventEntity(eventId, true);

        when(events.findById(eventId)).thenReturn(Optional.of(entity));
        when(organizers.existsByEvent_IdAndUser_IdAndEventRole(eventId, creatorId, EventRole.CREATOR))
                .thenReturn(true);
        when(users.findByUsername("ghost")).thenReturn(Optional.empty());

        assertThatExceptionOfType(NotFoundException.class)
                .isThrownBy(() -> eventService.addOrganizer(eventId, creatorId,
                        new AddOrganizerRequest("ghost", null)))
                .satisfies(ex -> assertThat(ex.getCode()).isEqualTo("USER_NOT_FOUND"));

        verify(organizers, never()).saveAndFlush(any());
    }

    @Test
    void addOrganizer_rejectsAlreadyOrganizer() {
        UUID eventId = UUID.randomUUID();
        UUID creatorId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        JEvents entity = eventEntity(eventId, true);
        JUsers target = userEntity(targetId);

        when(events.findById(eventId)).thenReturn(Optional.of(entity));
        when(organizers.existsByEvent_IdAndUser_IdAndEventRole(eventId, creatorId, EventRole.CREATOR))
                .thenReturn(true);
        when(users.findByUsername("admin")).thenReturn(Optional.of(target));
        when(organizers.existsByEvent_IdAndUser_Id(eventId, targetId)).thenReturn(true);

        assertThatExceptionOfType(ConflictException.class)
                .isThrownBy(() -> eventService.addOrganizer(eventId, creatorId,
                        new AddOrganizerRequest("admin", null)))
                .satisfies(ex -> {
                    assertThat(ex.getCode()).isEqualTo("ALREADY_ORGANIZER");
                    assertThat(ex.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                });

        verify(organizers, never()).saveAndFlush(any());
    }

    // ------------------------------------------------------------------
    // removeOrganizer
    // ------------------------------------------------------------------

    @Test
    void removeOrganizer_deletesAdminForCreator() {
        UUID eventId = UUID.randomUUID();
        UUID creatorId = UUID.randomUUID();
        UUID organizerId = UUID.randomUUID();
        JEvents entity = eventEntity(eventId, true);
        JEventOrganizer adminRow = organizerEntity(userEntity(UUID.randomUUID()), entity, EventRole.ADMIN);
        adminRow.setId(organizerId);

        when(events.findById(eventId)).thenReturn(Optional.of(entity));
        when(organizers.existsByEvent_IdAndUser_IdAndEventRole(eventId, creatorId, EventRole.CREATOR))
                .thenReturn(true);
        when(organizers.findByIdAndEvent_Id(organizerId, eventId)).thenReturn(Optional.of(adminRow));

        eventService.removeOrganizer(eventId, creatorId, organizerId);

        verify(organizers).delete(adminRow);
    }

    @Test
    void removeOrganizer_rejectsNonCreator() {
        UUID eventId = UUID.randomUUID();
        UUID callerId = UUID.randomUUID();
        JEvents entity = eventEntity(eventId, true);

        when(events.findById(eventId)).thenReturn(Optional.of(entity));
        when(organizers.existsByEvent_IdAndUser_IdAndEventRole(eventId, callerId, EventRole.CREATOR))
                .thenReturn(false);

        assertThatExceptionOfType(ForbiddenException.class)
                .isThrownBy(() -> eventService.removeOrganizer(eventId, callerId, UUID.randomUUID()))
                .satisfies(ex -> assertThat(ex.getCode()).isEqualTo("NOT_THE_CREATOR"));

        verify(organizers, never()).findByIdAndEvent_Id(any(), any());
        verify(organizers, never()).delete(any());
    }

    @Test
    void removeOrganizer_rejectsUnknownEvent() {
        UUID eventId = UUID.randomUUID();
        when(events.findById(eventId)).thenReturn(Optional.empty());

        assertThatExceptionOfType(NotFoundException.class)
                .isThrownBy(() -> eventService.removeOrganizer(eventId, UUID.randomUUID(), UUID.randomUUID()))
                .satisfies(ex -> assertThat(ex.getCode()).isEqualTo("EVENT_NOT_FOUND"));

        verify(organizers, never()).delete(any());
    }

    @Test
    void removeOrganizer_rejectsUnknownRow() {
        UUID eventId = UUID.randomUUID();
        UUID creatorId = UUID.randomUUID();
        UUID organizerId = UUID.randomUUID();
        JEvents entity = eventEntity(eventId, true);

        when(events.findById(eventId)).thenReturn(Optional.of(entity));
        when(organizers.existsByEvent_IdAndUser_IdAndEventRole(eventId, creatorId, EventRole.CREATOR))
                .thenReturn(true);
        when(organizers.findByIdAndEvent_Id(organizerId, eventId)).thenReturn(Optional.empty());

        assertThatExceptionOfType(NotFoundException.class)
                .isThrownBy(() -> eventService.removeOrganizer(eventId, creatorId, organizerId))
                .satisfies(ex -> assertThat(ex.getCode()).isEqualTo("ORGANIZER_NOT_FOUND"));

        verify(organizers, never()).delete(any());
    }

    @Test
    void removeOrganizer_rejectsCreatorRow() {
        UUID eventId = UUID.randomUUID();
        UUID creatorId = UUID.randomUUID();
        UUID organizerId = UUID.randomUUID();
        JEvents entity = eventEntity(eventId, true);
        JEventOrganizer creatorRow = organizerEntity(userEntity(creatorId), entity, EventRole.CREATOR);
        creatorRow.setId(organizerId);

        when(events.findById(eventId)).thenReturn(Optional.of(entity));
        when(organizers.existsByEvent_IdAndUser_IdAndEventRole(eventId, creatorId, EventRole.CREATOR))
                .thenReturn(true);
        when(organizers.findByIdAndEvent_Id(organizerId, eventId)).thenReturn(Optional.of(creatorRow));

        assertThatExceptionOfType(ConflictException.class)
                .isThrownBy(() -> eventService.removeOrganizer(eventId, creatorId, organizerId))
                .satisfies(ex -> {
                    assertThat(ex.getCode()).isEqualTo("CANNOT_REMOVE_CREATOR");
                    assertThat(ex.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                });

        verify(organizers, never()).delete(any());
    }

    // ------------------------------------------------------------------
    // getEventStats
    // ------------------------------------------------------------------

    @Test
    void getEventStats_aggregatesTiersAndIncludesZeroActivityTiers() {
        UUID eventId = UUID.randomUUID();
        UUID callerId = UUID.randomUUID();
        UUID standardId = UUID.randomUUID();
        UUID vipId = UUID.randomUUID();
        UUID freeId = UUID.randomUUID();
        JEvents entity = eventEntity(eventId, true);
        JTicketTier vip = tierEntity(vipId, "VIP", new BigDecimal("40.00"), 10);
        JTicketTier standard = tierEntity(standardId, "Standard", new BigDecimal("10.00"), 100);
        JTicketTier free = tierEntity(freeId, "Free", BigDecimal.ZERO, 5);

        when(events.findById(eventId)).thenReturn(Optional.of(entity));
        when(organizers.existsByEvent_IdAndUser_Id(eventId, callerId)).thenReturn(true);
        when(tiers.findByEvent_Id(eventId)).thenReturn(List.of(vip, standard, free));
        when(tickets.aggregateByTier(any(), any(), any())).thenReturn(List.of(
                tierAggregate(standardId, 3, 1, new BigDecimal("30.00")),
                tierAggregate(vipId, 1, 1, new BigDecimal("40.00"))));

        EventStats stats = eventService.getEventStats(eventId, callerId);

        assertThat(stats.eventId()).isEqualTo(eventId);
        assertThat(stats.totalCapacity()).isEqualTo(115);
        assertThat(stats.ticketsIssued()).isEqualTo(4);
        assertThat(stats.ticketsCheckedIn()).isEqualTo(2);
        assertThat(stats.revenue()).isEqualByComparingTo("70.00");
        assertThat(stats.byTier()).extracting(TierStats::tierName)
                .containsExactly("Free", "Standard", "VIP");

        TierStats freeStats = stats.byTier().get(0);
        assertThat(freeStats.tierId()).isEqualTo(freeId);
        assertThat(freeStats.issued()).isZero();
        assertThat(freeStats.checkedIn()).isZero();
        assertThat(freeStats.revenue()).isEqualByComparingTo("0");

        assertThat(stats.byTier().get(1).issued()).isEqualTo(3);
        assertThat(stats.byTier().get(1).checkedIn()).isEqualTo(1);
        assertThat(stats.byTier().get(1).revenue()).isEqualByComparingTo("30.00");

        verify(tickets).aggregateByTier(eq(eventId),
                eq(List.of(TicketStatus.VALID, TicketStatus.USED)), eq(TicketStatus.USED));
    }

    @Test
    void getEventStats_rejectsUnknownEvent() {
        UUID eventId = UUID.randomUUID();
        when(events.findById(eventId)).thenReturn(Optional.empty());

        assertThatExceptionOfType(NotFoundException.class)
                .isThrownBy(() -> eventService.getEventStats(eventId, UUID.randomUUID()))
                .satisfies(ex -> assertThat(ex.getCode()).isEqualTo("EVENT_NOT_FOUND"));

        verifyNoInteractions(organizers, tiers, tickets);
    }

    @Test
    void getEventStats_rejectsNonOrganizer() {
        UUID eventId = UUID.randomUUID();
        UUID callerId = UUID.randomUUID();
        when(events.findById(eventId)).thenReturn(Optional.of(eventEntity(eventId, true)));
        when(organizers.existsByEvent_IdAndUser_Id(eventId, callerId)).thenReturn(false);

        assertThatExceptionOfType(ForbiddenException.class)
                .isThrownBy(() -> eventService.getEventStats(eventId, callerId))
                .satisfies(ex -> assertThat(ex.getCode()).isEqualTo("NOT_AN_ORGANIZER"));

        verifyNoInteractions(tiers, tickets);
    }

    // ------------------------------------------------------------------
    // fixtures
    // ------------------------------------------------------------------

    private static JEvents eventEntity(UUID id, boolean isPublic) {
        JEvents entity = new JEvents();
        entity.setId(id);
        entity.setTitle("Title");
        entity.setDescription("Description");
        entity.setStartTime(START);
        entity.setEndTime(END);
        entity.setPublic(isPublic);
        entity.setLocation("Location");
        entity.setStatus(EventStatus.OK);
        entity.setCreatedAt(Instant.parse("2026-10-05T10:00:00Z"));
        entity.setUpdatedAt(Instant.parse("2026-10-05T10:00:00Z"));
        return entity;
    }

    private static Events eventDomain(UUID id, boolean isPublic) {
        Events domain = new Events();
        domain.setId(id);
        domain.setTitle("Title");
        domain.setDescription("Description");
        domain.setStartTime(START);
        domain.setEndTime(END);
        domain.setPublic(isPublic);
        domain.setLocation("Location");
        domain.setStatus(EventStatus.OK);
        domain.setCreatedAt(Instant.parse("2026-10-05T10:00:00Z"));
        domain.setUpdatedAt(Instant.parse("2026-10-05T10:00:00Z"));
        return domain;
    }

    private static JUsers userEntity(UUID id) {
        JUsers user = new JUsers();
        user.setId(id);
        user.setEmail("user@example.com");
        user.setUsername("user");
        return user;
    }

    private static JEventOrganizer organizerEntity(JUsers user, JEvents event, EventRole role) {
        JEventOrganizer organizer = new JEventOrganizer();
        organizer.setUser(user);
        organizer.setEvent(event);
        organizer.setEventRole(role);
        return organizer;
    }

    private static JTicketTier tierEntity(UUID id, String name, BigDecimal price, int capacity) {
        JTicketTier tier = new JTicketTier();
        tier.setId(id);
        tier.setTierName(name);
        tier.setPrice(price);
        tier.setCapacity(capacity);
        return tier;
    }

    private static JTicketRepository.TierIssued tierIssued(UUID tierId, long issued) {
        return new JTicketRepository.TierIssued() {
            @Override
            public UUID getTierId() {
                return tierId;
            }

            @Override
            public long getIssued() {
                return issued;
            }
        };
    }

    private static JTicketRepository.TierAggregate tierAggregate(UUID tierId, long issued, long checkedIn,
            BigDecimal revenue) {
        return new JTicketRepository.TierAggregate() {
            @Override
            public UUID getTierId() {
                return tierId;
            }

            @Override
            public long getIssued() {
                return issued;
            }

            @Override
            public long getCheckedIn() {
                return checkedIn;
            }

            @Override
            public BigDecimal getRevenue() {
                return revenue;
            }
        };
    }

    private static JTicketTierRepository.MinPrice minPrice(UUID eventId, BigDecimal price) {
        return new JTicketTierRepository.MinPrice() {
            @Override
            public UUID getEventId() {
                return eventId;
            }

            @Override
            public BigDecimal getMinPrice() {
                return price;
            }
        };
    }

    private static EventOrganizer organizerDomain(UUID id, Users user, EventRole role) {
        EventOrganizer domain = new EventOrganizer();
        domain.setId(id);
        domain.setUser(user);
        domain.setEventRole(role);
        return domain;
    }

    private static Users userDomain(UUID id, String username, String fullName) {
        Users user = new Users();
        user.setId(id);
        user.setUsername(username);
        user.setFullName(fullName);
        return user;
    }
}
