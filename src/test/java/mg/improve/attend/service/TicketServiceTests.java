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

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import mg.improve.attend.domain.dto.response.CheckInResponse;
import mg.improve.attend.domain.dto.response.PageAttendeeTicket;
import mg.improve.attend.domain.dto.response.PageTicket;
import mg.improve.attend.domain.dto.response.TicketDetail;
import mg.improve.attend.domain.dto.response.TicketResponse;
import mg.improve.attend.domain.entity.Events;
import mg.improve.attend.domain.entity.Ticket;
import mg.improve.attend.domain.entity.TicketTier;
import mg.improve.attend.domain.entity.Users;
import mg.improve.attend.domain.enums.EventStatus;
import mg.improve.attend.domain.enums.TicketStatus;
import mg.improve.attend.domain.mapper.TicketMapper;
import mg.improve.attend.exceptions.BadRequestException;
import mg.improve.attend.exceptions.ConflictException;
import mg.improve.attend.exceptions.ForbiddenException;
import mg.improve.attend.exceptions.NotFoundException;
import mg.improve.attend.repository.JEventOrganizerRepository;
import mg.improve.attend.repository.JEventsRepository;
import mg.improve.attend.repository.JTicketRepository;
import mg.improve.attend.repository.JTicketTierRepository;
import mg.improve.attend.repository.JUsersRepository;
import mg.improve.attend.repository.entities.JEvents;
import mg.improve.attend.repository.entities.JTicket;
import mg.improve.attend.repository.entities.JTicketTier;
import mg.improve.attend.repository.entities.JUsers;
import mg.improve.attend.validator.TicketValidator;
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
class TicketServiceTests {

    private static final List<TicketStatus> CAPACITY_STATUSES =
            List.of(TicketStatus.VALID, TicketStatus.USED);

    @Mock
    private JEventOrganizerRepository organizers;

    @Mock
    private JEventsRepository events;

    @Mock
    private JTicketTierRepository tiers;

    @Mock
    private JTicketRepository tickets;

    @Mock
    private JUsersRepository users;

    @Mock
    private TicketMapper ticketMapper;

    @Mock
    private TicketValidator validator;

    @InjectMocks
    private TicketService ticketService;

    // ------------------------------------------------------------------
    // issueTicket
    // ------------------------------------------------------------------

    @Test
    void issueTicket_persistsValidTicketWithTierPriceAndEventEndTime() {
        UUID eventId = UUID.randomUUID();
        UUID tierId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID ticketId = UUID.randomUUID();
        Instant endTime = Instant.now().plusSeconds(3600);
        JEvents event = eventEntity(eventId, EventStatus.OK, endTime);
        JTicketTier tier = tierEntity(tierId, event, "VIP", "40.00", 10);
        JUsers owner = userEntity(userId, "owner");

        JTicket saved = new JTicket();
        saved.setId(ticketId);
        saved.setOwner(owner);
        saved.setTier(tier);
        saved.setPricePaid(new BigDecimal("40.00"));
        saved.setTicketStatus(TicketStatus.VALID);
        saved.setValidUntil(endTime);

        Ticket domain = Ticket.builder()
                .id(ticketId)
                .tier(tierDomain(tierId, eventDomain(eventId, EventStatus.OK, endTime), "VIP", "40.00", 10))
                .pricePaid(new BigDecimal("40.00"))
                .ticketStatus(TicketStatus.VALID)
                .validUntil(endTime)
                .build();

        when(tiers.findByIdForUpdate(tierId)).thenReturn(Optional.of(tier));
        when(tickets.countByTier_IdAndTicketStatusIn(tierId, CAPACITY_STATUSES)).thenReturn(0L);
        when(users.findById(userId)).thenReturn(Optional.of(owner));
        when(tickets.saveAndFlush(any(JTicket.class))).thenReturn(saved);
        when(ticketMapper.toDomain(saved)).thenReturn(domain);

        TicketResponse response = ticketService.issueTicket(tierId, userId);

        ArgumentCaptor<JTicket> captor = ArgumentCaptor.forClass(JTicket.class);
        verify(tickets).saveAndFlush(captor.capture());
        assertThat(captor.getValue().getOwner()).isSameAs(owner);
        assertThat(captor.getValue().getTier()).isSameAs(tier);
        assertThat(captor.getValue().getPricePaid()).isEqualByComparingTo("40.00");
        assertThat(captor.getValue().getTicketStatus()).isEqualTo(TicketStatus.VALID);
        assertThat(captor.getValue().getValidUntil()).isEqualTo(endTime);

        assertThat(response.id()).isEqualTo(ticketId);
        assertThat(response.event().id()).isEqualTo(eventId);
        assertThat(response.tier().id()).isEqualTo(tierId);
        assertThat(response.tier().tierName()).isEqualTo("VIP");
        assertThat(response.pricePaid()).isEqualByComparingTo("40.00");
        assertThat(response.ticketStatus()).isEqualTo(TicketStatus.VALID);
        assertThat(response.checkedInAt()).isNull();
        assertThat(response.validUntil()).isEqualTo(endTime);
    }

    @Test
    void issueTicket_rejectsUnknownTier() {
        UUID tierId = UUID.randomUUID();
        when(tiers.findByIdForUpdate(tierId)).thenReturn(Optional.empty());

        assertThatExceptionOfType(NotFoundException.class)
                .isThrownBy(() -> ticketService.issueTicket(tierId, UUID.randomUUID()))
                .satisfies(ex -> {
                    assertThat(ex.getCode()).isEqualTo("TIER_NOT_FOUND");
                    assertThat(ex.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
                });

        verifyNoInteractions(tickets, users);
    }

    @Test
    void issueTicket_rejectsSoldOutTier() {
        UUID tierId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        JEvents event = eventEntity(UUID.randomUUID(), EventStatus.OK, Instant.now().plusSeconds(3600));
        JTicketTier tier = tierEntity(tierId, event, "VIP", "40.00", 1);

        when(tiers.findByIdForUpdate(tierId)).thenReturn(Optional.of(tier));
        when(tickets.countByTier_IdAndTicketStatusIn(tierId, CAPACITY_STATUSES)).thenReturn(1L);

        assertThatExceptionOfType(ConflictException.class)
                .isThrownBy(() -> ticketService.issueTicket(tierId, userId))
                .satisfies(ex -> {
                    assertThat(ex.getCode()).isEqualTo("SOLD_OUT");
                    assertThat(ex.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                });

        verify(tickets, never()).saveAndFlush(any());
        verifyNoInteractions(users);
    }

    @Test
    void issueTicket_rejectsCancelledEvent() {
        UUID tierId = UUID.randomUUID();
        JEvents event = eventEntity(UUID.randomUUID(), EventStatus.CANCELLED, Instant.now().plusSeconds(3600));
        JTicketTier tier = tierEntity(tierId, event, "VIP", "40.00", 10);

        when(tiers.findByIdForUpdate(tierId)).thenReturn(Optional.of(tier));

        assertThatExceptionOfType(ConflictException.class)
                .isThrownBy(() -> ticketService.issueTicket(tierId, UUID.randomUUID()))
                .satisfies(ex -> assertThat(ex.getCode()).isEqualTo("EVENT_NOT_OPEN"));

        verifyNoInteractions(tickets, users);
    }

    @Test
    void issueTicket_rejectsEndedEvent() {
        UUID tierId = UUID.randomUUID();
        JEvents event = eventEntity(UUID.randomUUID(), EventStatus.OK, Instant.now().minusSeconds(60));
        JTicketTier tier = tierEntity(tierId, event, "VIP", "40.00", 10);

        when(tiers.findByIdForUpdate(tierId)).thenReturn(Optional.of(tier));

        assertThatExceptionOfType(ConflictException.class)
                .isThrownBy(() -> ticketService.issueTicket(tierId, UUID.randomUUID()))
                .satisfies(ex -> assertThat(ex.getCode()).isEqualTo("EVENT_NOT_OPEN"));

        verifyNoInteractions(tickets, users);
    }

    @Test
    void issueTicket_rejectsUnknownUser() {
        UUID tierId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        JEvents event = eventEntity(UUID.randomUUID(), EventStatus.OK, Instant.now().plusSeconds(3600));
        JTicketTier tier = tierEntity(tierId, event, "VIP", "40.00", 10);

        when(tiers.findByIdForUpdate(tierId)).thenReturn(Optional.of(tier));
        when(tickets.countByTier_IdAndTicketStatusIn(tierId, CAPACITY_STATUSES)).thenReturn(0L);
        when(users.findById(userId)).thenReturn(Optional.empty());

        assertThatExceptionOfType(NotFoundException.class)
                .isThrownBy(() -> ticketService.issueTicket(tierId, userId))
                .satisfies(ex -> assertThat(ex.getCode()).isEqualTo("USER_NOT_FOUND"));

        verify(tickets, never()).saveAndFlush(any());
    }

    // ------------------------------------------------------------------
    // listMyTickets
    // ------------------------------------------------------------------

    @Test
    void listMyTickets_mapsPageAndPassesFilters() {
        UUID userId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        UUID tierId = UUID.randomUUID();
        UUID ticketId = UUID.randomUUID();
        Instant endTime = Instant.now().plusSeconds(3600);
        JEvents event = eventEntity(eventId, EventStatus.OK, endTime);
        JTicketTier tier = tierEntity(tierId, event, "VIP", "40.00", 10);
        JUsers owner = userEntity(userId, "owner");
        JTicket ticket = new JTicket();
        ticket.setId(ticketId);
        ticket.setOwner(owner);
        ticket.setTier(tier);
        ticket.setPricePaid(new BigDecimal("40.00"));
        ticket.setTicketStatus(TicketStatus.VALID);

        Ticket domain = Ticket.builder()
                .id(ticketId)
                .tier(tierDomain(tierId, eventDomain(eventId, EventStatus.OK, endTime), "VIP", "40.00", 10))
                .pricePaid(new BigDecimal("40.00"))
                .ticketStatus(TicketStatus.VALID)
                .build();

        when(tickets.findMyTickets(eq(userId), eq(TicketStatus.VALID), eq(true), any(Instant.class),
                any(Pageable.class))).thenReturn(new PageImpl<>(List.of(ticket), PageRequest.of(0, 20), 1));
        when(ticketMapper.toDomain(ticket)).thenReturn(domain);

        PageTicket response = ticketService.listMyTickets(userId, TicketStatus.VALID, true, 0, 20);

        verify(validator).validateListQuery(0, 20);
        assertThat(response.page()).isZero();
        assertThat(response.size()).isEqualTo(20);
        assertThat(response.totalElements()).isEqualTo(1);
        assertThat(response.totalPages()).isEqualTo(1);
        assertThat(response.content()).hasSize(1);
        assertThat(response.content().get(0).id()).isEqualTo(ticketId);
        assertThat(response.content().get(0).tier().tierName()).isEqualTo("VIP");

        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        verify(tickets).findMyTickets(eq(userId), eq(TicketStatus.VALID), eq(true), any(Instant.class),
                pageableCaptor.capture());
        Sort.Order order = pageableCaptor.getValue().getSort().getOrderFor("tier.event.startTime");
        assertThat(order).isNotNull();
        assertThat(order.getDirection()).isEqualTo(Sort.Direction.ASC);
    }

    @Test
    void listMyTickets_returnsEmptyPage() {
        UUID userId = UUID.randomUUID();
        when(tickets.findMyTickets(eq(userId), isNull(), eq(false), any(Instant.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0));

        PageTicket response = ticketService.listMyTickets(userId, null, false, 0, 20);

        assertThat(response.content()).isEmpty();
        assertThat(response.totalElements()).isZero();
        assertThat(response.totalPages()).isZero();
    }

    @Test
    void listMyTickets_propagatesValidationFailure() {
        UUID userId = UUID.randomUUID();
        doThrow(new BadRequestException("VALIDATION_ERROR", "Validation failed"))
                .when(validator).validateListQuery(-1, 20);

        assertThatExceptionOfType(BadRequestException.class)
                .isThrownBy(() -> ticketService.listMyTickets(userId, null, false, -1, 20))
                .satisfies(ex -> assertThat(ex.getCode()).isEqualTo("VALIDATION_ERROR"));

        verifyNoInteractions(tickets);
    }

    // ------------------------------------------------------------------
    // getTicket
    // ------------------------------------------------------------------

    @Test
    void getTicket_returnsBaseDetailForOwner() {
        UUID eventId = UUID.randomUUID();
        UUID tierId = UUID.randomUUID();
        UUID ticketId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        Instant endTime = Instant.now().plusSeconds(3600);
        JEvents event = eventEntity(eventId, EventStatus.OK, endTime);
        JTicketTier tier = tierEntity(tierId, event, "VIP", "40.00", 10);
        JUsers owner = userEntity(userId, "owner");
        JTicket ticket = new JTicket();
        ticket.setId(ticketId);
        ticket.setOwner(owner);
        ticket.setTier(tier);
        ticket.setPricePaid(new BigDecimal("40.00"));
        ticket.setTicketStatus(TicketStatus.VALID);

        Ticket domain = Ticket.builder()
                .id(ticketId)
                .owner(userDomain(userId, "owner", "Owner"))
                .tier(tierDomain(tierId, eventDomain(eventId, EventStatus.OK, endTime), "VIP", "40.00", 10))
                .pricePaid(new BigDecimal("40.00"))
                .ticketStatus(TicketStatus.VALID)
                .build();

        when(tickets.findDetailById(ticketId)).thenReturn(Optional.of(ticket));
        when(organizers.existsByEvent_IdAndUser_Id(eventId, userId)).thenReturn(false);
        when(ticketMapper.toDomain(ticket)).thenReturn(domain);

        TicketDetail response = ticketService.getTicket(ticketId, userId);

        assertThat(response.id()).isEqualTo(ticketId);
        assertThat(response.event().id()).isEqualTo(eventId);
        assertThat(response.tier().tierName()).isEqualTo("VIP");
        assertThat(response.owner()).isNull();
        assertThat(response.checkedBy()).isNull();
    }

    @Test
    void getTicket_includesOwnerAndCheckedByForOrganizer() {
        UUID eventId = UUID.randomUUID();
        UUID tierId = UUID.randomUUID();
        UUID ticketId = UUID.randomUUID();
        UUID organizerId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        UUID scannerId = UUID.randomUUID();
        Instant endTime = Instant.now().plusSeconds(3600);
        JEvents event = eventEntity(eventId, EventStatus.OK, endTime);
        JTicketTier tier = tierEntity(tierId, event, "VIP", "40.00", 10);
        JTicket ticket = new JTicket();
        ticket.setId(ticketId);
        ticket.setTier(tier);
        ticket.setOwner(userEntity(ownerId, "owner"));
        ticket.setPricePaid(new BigDecimal("40.00"));
        ticket.setTicketStatus(TicketStatus.USED);

        Ticket domain = Ticket.builder()
                .id(ticketId)
                .owner(userDomain(ownerId, "owner", "Owner Name"))
                .checkedBy(userDomain(scannerId, "scanner", "Scanner"))
                .tier(tierDomain(tierId, eventDomain(eventId, EventStatus.OK, endTime), "VIP", "40.00", 10))
                .pricePaid(new BigDecimal("40.00"))
                .ticketStatus(TicketStatus.USED)
                .build();

        when(tickets.findDetailById(ticketId)).thenReturn(Optional.of(ticket));
        when(organizers.existsByEvent_IdAndUser_Id(eventId, organizerId)).thenReturn(true);
        when(ticketMapper.toDomain(ticket)).thenReturn(domain);

        TicketDetail response = ticketService.getTicket(ticketId, organizerId);

        assertThat(response.owner()).isNotNull();
        assertThat(response.owner().id()).isEqualTo(ownerId);
        assertThat(response.owner().fullName()).isEqualTo("Owner Name");
        assertThat(response.owner().username()).isEqualTo("owner");
        assertThat(response.checkedBy()).isNotNull();
        assertThat(response.checkedBy().id()).isEqualTo(scannerId);
        assertThat(response.checkedBy().username()).isEqualTo("scanner");
    }

    @Test
    void getTicket_rejectsUnknownTicket() {
        UUID ticketId = UUID.randomUUID();
        when(tickets.findDetailById(ticketId)).thenReturn(Optional.empty());

        assertThatExceptionOfType(NotFoundException.class)
                .isThrownBy(() -> ticketService.getTicket(ticketId, UUID.randomUUID()))
                .satisfies(ex -> assertThat(ex.getCode()).isEqualTo("TICKET_NOT_FOUND"));

        verifyNoInteractions(organizers);
    }

    @Test
    void getTicket_rejectsNonOwnerNonOrganizer() {
        UUID eventId = UUID.randomUUID();
        UUID tierId = UUID.randomUUID();
        UUID ticketId = UUID.randomUUID();
        UUID callerId = UUID.randomUUID();
        JEvents event = eventEntity(eventId, EventStatus.OK, Instant.now().plusSeconds(3600));
        JTicketTier tier = tierEntity(tierId, event, "VIP", "40.00", 10);
        JTicket ticket = new JTicket();
        ticket.setId(ticketId);
        ticket.setTier(tier);
        ticket.setOwner(userEntity(UUID.randomUUID(), "owner"));
        ticket.setPricePaid(new BigDecimal("40.00"));
        ticket.setTicketStatus(TicketStatus.VALID);

        when(tickets.findDetailById(ticketId)).thenReturn(Optional.of(ticket));
        when(organizers.existsByEvent_IdAndUser_Id(eventId, callerId)).thenReturn(false);

        assertThatExceptionOfType(ForbiddenException.class)
                .isThrownBy(() -> ticketService.getTicket(ticketId, callerId))
                .satisfies(ex -> {
                    assertThat(ex.getCode()).isEqualTo("TICKET_FORBIDDEN");
                    assertThat(ex.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
                });

        verifyNoInteractions(ticketMapper);
    }

    // ------------------------------------------------------------------
    // checkInTicket
    // ------------------------------------------------------------------

    @Test
    void checkInTicket_marksUsedForOrganizer() {
        UUID eventId = UUID.randomUUID();
        UUID tierId = UUID.randomUUID();
        UUID ticketId = UUID.randomUUID();
        UUID organizerId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        Instant endTime = Instant.now().plusSeconds(3600);
        JEvents event = eventEntity(eventId, EventStatus.OK, endTime);
        JTicketTier tier = tierEntity(tierId, event, "VIP", "40.00", 10);
        JUsers owner = userEntity(ownerId, "owner");
        JUsers scanner = userEntity(organizerId, "scanner");
        JTicket ticket = new JTicket();
        ticket.setId(ticketId);
        ticket.setOwner(owner);
        ticket.setTier(tier);
        ticket.setPricePaid(new BigDecimal("40.00"));
        ticket.setTicketStatus(TicketStatus.VALID);
        ticket.setValidUntil(endTime);

        Ticket domain = Ticket.builder()
                .id(ticketId)
                .owner(userDomain(ownerId, "owner", "Owner Name"))
                .tier(tierDomain(tierId, eventDomain(eventId, EventStatus.OK, endTime), "VIP", "40.00", 10))
                .pricePaid(new BigDecimal("40.00"))
                .ticketStatus(TicketStatus.VALID)
                .build();

        when(tickets.findDetailById(ticketId)).thenReturn(Optional.of(ticket));
        when(organizers.existsByEvent_IdAndUser_Id(eventId, organizerId)).thenReturn(true);
        when(users.findById(organizerId)).thenReturn(Optional.of(scanner));
        when(tickets.markCheckedIn(eq(ticketId), eq(TicketStatus.VALID), eq(TicketStatus.USED),
                any(Instant.class), eq(scanner))).thenReturn(1);
        when(ticketMapper.toDomain(ticket)).thenReturn(domain);

        CheckInResponse response = ticketService.checkInTicket(ticketId, organizerId);

        assertThat(response.id()).isEqualTo(ticketId);
        assertThat(response.ticketStatus()).isEqualTo(TicketStatus.USED);
        assertThat(response.checkedInAt()).isNotNull();
        assertThat(response.checkedBy().id()).isEqualTo(organizerId);
        assertThat(response.checkedBy().username()).isEqualTo("scanner");
        assertThat(response.owner().fullName()).isEqualTo("Owner Name");
        assertThat(response.owner().username()).isEqualTo("owner");
        assertThat(response.tier().tierName()).isEqualTo("VIP");
    }

    @Test
    void checkInTicket_rejectsUnknownTicket() {
        UUID ticketId = UUID.randomUUID();
        when(tickets.findDetailById(ticketId)).thenReturn(Optional.empty());

        assertThatExceptionOfType(NotFoundException.class)
                .isThrownBy(() -> ticketService.checkInTicket(ticketId, UUID.randomUUID()))
                .satisfies(ex -> assertThat(ex.getCode()).isEqualTo("TICKET_NOT_FOUND"));

        verifyNoInteractions(organizers);
    }

    @Test
    void checkInTicket_rejectsNonOrganizer() {
        UUID eventId = UUID.randomUUID();
        UUID ticketId = UUID.randomUUID();
        UUID callerId = UUID.randomUUID();
        JEvents event = eventEntity(eventId, EventStatus.OK, Instant.now().plusSeconds(3600));
        JTicketTier tier = tierEntity(UUID.randomUUID(), event, "VIP", "40.00", 10);
        JTicket ticket = ticketEntity(ticketId, tier, userEntity(UUID.randomUUID(), "owner"), TicketStatus.VALID);

        when(tickets.findDetailById(ticketId)).thenReturn(Optional.of(ticket));
        when(organizers.existsByEvent_IdAndUser_Id(eventId, callerId)).thenReturn(false);

        assertThatExceptionOfType(ForbiddenException.class)
                .isThrownBy(() -> ticketService.checkInTicket(ticketId, callerId))
                .satisfies(ex -> assertThat(ex.getCode()).isEqualTo("NOT_AN_ORGANIZER"));

        verify(tickets, never()).markCheckedIn(any(), any(), any(), any(), any());
    }

    @Test
    void checkInTicket_rejectsClosedEvent() {
        UUID eventId = UUID.randomUUID();
        UUID ticketId = UUID.randomUUID();
        UUID callerId = UUID.randomUUID();
        JEvents event = eventEntity(eventId, EventStatus.CANCELLED, Instant.now().plusSeconds(3600));
        JTicketTier tier = tierEntity(UUID.randomUUID(), event, "VIP", "40.00", 10);
        JTicket ticket = ticketEntity(ticketId, tier, userEntity(UUID.randomUUID(), "owner"), TicketStatus.VALID);

        when(tickets.findDetailById(ticketId)).thenReturn(Optional.of(ticket));
        when(organizers.existsByEvent_IdAndUser_Id(eventId, callerId)).thenReturn(true);

        assertThatExceptionOfType(ConflictException.class)
                .isThrownBy(() -> ticketService.checkInTicket(ticketId, callerId))
                .satisfies(ex -> assertThat(ex.getCode()).isEqualTo("EVENT_NOT_OPEN"));

        verify(tickets, never()).markCheckedIn(any(), any(), any(), any(), any());
    }

    @Test
    void checkInTicket_rejectsAlreadyUsedTicket() {
        UUID eventId = UUID.randomUUID();
        UUID ticketId = UUID.randomUUID();
        UUID callerId = UUID.randomUUID();
        Instant checkedInAt = Instant.now().minusSeconds(120);
        JEvents event = eventEntity(eventId, EventStatus.OK, Instant.now().plusSeconds(3600));
        JTicketTier tier = tierEntity(UUID.randomUUID(), event, "VIP", "40.00", 10);
        JTicket ticket = ticketEntity(ticketId, tier, userEntity(UUID.randomUUID(), "owner"), TicketStatus.USED);
        ticket.setCheckedInAt(checkedInAt);

        when(tickets.findDetailById(ticketId)).thenReturn(Optional.of(ticket));
        when(organizers.existsByEvent_IdAndUser_Id(eventId, callerId)).thenReturn(true);

        assertThatExceptionOfType(ConflictException.class)
                .isThrownBy(() -> ticketService.checkInTicket(ticketId, callerId))
                .satisfies(ex -> {
                    assertThat(ex.getCode()).isEqualTo("TICKET_ALREADY_USED");
                    assertThat(ex.getDetails()).hasSize(1);
                    assertThat(ex.getDetails().get(0).field()).isEqualTo("checkedInAt");
                });

        verify(tickets, never()).markCheckedIn(any(), any(), any(), any(), any());
    }

    @Test
    void checkInTicket_rejectsCancelledTicket() {
        UUID eventId = UUID.randomUUID();
        UUID ticketId = UUID.randomUUID();
        UUID callerId = UUID.randomUUID();
        JEvents event = eventEntity(eventId, EventStatus.OK, Instant.now().plusSeconds(3600));
        JTicketTier tier = tierEntity(UUID.randomUUID(), event, "VIP", "40.00", 10);
        JTicket ticket = ticketEntity(ticketId, tier, userEntity(UUID.randomUUID(), "owner"),
                TicketStatus.CANCELLED);

        when(tickets.findDetailById(ticketId)).thenReturn(Optional.of(ticket));
        when(organizers.existsByEvent_IdAndUser_Id(eventId, callerId)).thenReturn(true);

        assertThatExceptionOfType(ConflictException.class)
                .isThrownBy(() -> ticketService.checkInTicket(ticketId, callerId))
                .satisfies(ex -> assertThat(ex.getCode()).isEqualTo("TICKET_NOT_VALID"));

        verify(tickets, never()).markCheckedIn(any(), any(), any(), any(), any());
    }

    @Test
    void checkInTicket_rejectsExpiredTicket() {
        UUID eventId = UUID.randomUUID();
        UUID ticketId = UUID.randomUUID();
        UUID callerId = UUID.randomUUID();
        JEvents event = eventEntity(eventId, EventStatus.OK, Instant.now().plusSeconds(3600));
        JTicketTier tier = tierEntity(UUID.randomUUID(), event, "VIP", "40.00", 10);
        JTicket ticket = ticketEntity(ticketId, tier, userEntity(UUID.randomUUID(), "owner"), TicketStatus.VALID);
        ticket.setValidUntil(Instant.now().minusSeconds(60));

        when(tickets.findDetailById(ticketId)).thenReturn(Optional.of(ticket));
        when(organizers.existsByEvent_IdAndUser_Id(eventId, callerId)).thenReturn(true);

        assertThatExceptionOfType(ConflictException.class)
                .isThrownBy(() -> ticketService.checkInTicket(ticketId, callerId))
                .satisfies(ex -> assertThat(ex.getCode()).isEqualTo("TICKET_EXPIRED"));

        verify(tickets, never()).markCheckedIn(any(), any(), any(), any(), any());
    }

    @Test
    void checkInTicket_mapsLostRaceToAlreadyUsed() {
        UUID eventId = UUID.randomUUID();
        UUID ticketId = UUID.randomUUID();
        UUID callerId = UUID.randomUUID();
        Instant endTime = Instant.now().plusSeconds(3600);
        JEvents event = eventEntity(eventId, EventStatus.OK, endTime);
        JTicketTier tier = tierEntity(UUID.randomUUID(), event, "VIP", "40.00", 10);
        JTicket valid = ticketEntity(ticketId, tier, userEntity(UUID.randomUUID(), "owner"), TicketStatus.VALID);
        JTicket used = ticketEntity(ticketId, tier, userEntity(UUID.randomUUID(), "owner"), TicketStatus.USED);
        JUsers scanner = userEntity(callerId, "scanner");

        when(tickets.findDetailById(ticketId))
                .thenReturn(Optional.of(valid))
                .thenReturn(Optional.of(used));
        when(organizers.existsByEvent_IdAndUser_Id(eventId, callerId)).thenReturn(true);
        when(users.findById(callerId)).thenReturn(Optional.of(scanner));
        when(tickets.markCheckedIn(eq(ticketId), eq(TicketStatus.VALID), eq(TicketStatus.USED),
                any(Instant.class), eq(scanner))).thenReturn(0);

        assertThatExceptionOfType(ConflictException.class)
                .isThrownBy(() -> ticketService.checkInTicket(ticketId, callerId))
                .satisfies(ex -> assertThat(ex.getCode()).isEqualTo("TICKET_ALREADY_USED"));
    }

    // ------------------------------------------------------------------
    // cancelTicket
    // ------------------------------------------------------------------

    @Test
    void cancelTicket_cancelsOwnValidTicket() {
        UUID ticketId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        JTicket ticket = ticketEntity(ticketId, tierEntity(UUID.randomUUID(), null, "VIP", "40.00", 10),
                userEntity(userId, "owner"), TicketStatus.VALID);
        Ticket domain = Ticket.builder()
                .id(ticketId)
                .owner(userDomain(userId, "owner", "Owner"))
                .tier(tierDomain(UUID.randomUUID(),
                        eventDomain(UUID.randomUUID(), EventStatus.OK, Instant.now().plusSeconds(3600)),
                        "VIP", "40.00", 10))
                .pricePaid(new BigDecimal("40.00"))
                .ticketStatus(TicketStatus.VALID)
                .build();

        when(tickets.findDetailById(ticketId)).thenReturn(Optional.of(ticket));
        when(tickets.updateStatus(ticketId, List.of(TicketStatus.VALID), TicketStatus.CANCELLED)).thenReturn(1);
        when(ticketMapper.toDomain(ticket)).thenReturn(domain);

        TicketResponse response = ticketService.cancelTicket(ticketId, userId);

        assertThat(response.id()).isEqualTo(ticketId);
        assertThat(response.ticketStatus()).isEqualTo(TicketStatus.CANCELLED);
    }

    @Test
    void cancelTicket_rejectsNonOwner() {
        UUID ticketId = UUID.randomUUID();
        JTicket ticket = ticketEntity(ticketId, tierEntity(UUID.randomUUID(), null, "VIP", "40.00", 10),
                userEntity(UUID.randomUUID(), "owner"), TicketStatus.VALID);

        when(tickets.findDetailById(ticketId)).thenReturn(Optional.of(ticket));

        assertThatExceptionOfType(ForbiddenException.class)
                .isThrownBy(() -> ticketService.cancelTicket(ticketId, UUID.randomUUID()))
                .satisfies(ex -> assertThat(ex.getCode()).isEqualTo("NOT_THE_OWNER"));

        verify(tickets, never()).updateStatus(any(), any(), any());
    }

    @Test
    void cancelTicket_rejectsUnknownTicket() {
        UUID ticketId = UUID.randomUUID();
        when(tickets.findDetailById(ticketId)).thenReturn(Optional.empty());

        assertThatExceptionOfType(NotFoundException.class)
                .isThrownBy(() -> ticketService.cancelTicket(ticketId, UUID.randomUUID()))
                .satisfies(ex -> assertThat(ex.getCode()).isEqualTo("TICKET_NOT_FOUND"));
    }

    @Test
    void cancelTicket_rejectsAlreadyUsedTicket() {
        UUID ticketId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        JTicket ticket = ticketEntity(ticketId, tierEntity(UUID.randomUUID(), null, "VIP", "40.00", 10),
                userEntity(userId, "owner"), TicketStatus.USED);
        ticket.setCheckedInAt(Instant.now());

        when(tickets.findDetailById(ticketId)).thenReturn(Optional.of(ticket));

        assertThatExceptionOfType(ConflictException.class)
                .isThrownBy(() -> ticketService.cancelTicket(ticketId, userId))
                .satisfies(ex -> assertThat(ex.getCode()).isEqualTo("TICKET_ALREADY_USED"));

        verify(tickets, never()).updateStatus(any(), any(), any());
    }

    // ------------------------------------------------------------------
    // refundTicket
    // ------------------------------------------------------------------

    @Test
    void refundTicket_refundsValidTicketForOrganizer() {
        UUID eventId = UUID.randomUUID();
        UUID ticketId = UUID.randomUUID();
        UUID organizerId = UUID.randomUUID();
        JEvents event = eventEntity(eventId, EventStatus.OK, Instant.now().plusSeconds(3600));
        JTicketTier tier = tierEntity(UUID.randomUUID(), event, "VIP", "40.00", 10);
        JTicket ticket = ticketEntity(ticketId, tier, userEntity(UUID.randomUUID(), "owner"), TicketStatus.VALID);
        Ticket domain = Ticket.builder()
                .id(ticketId)
                .owner(userDomain(UUID.randomUUID(), "owner", "Owner"))
                .tier(tierDomain(UUID.randomUUID(),
                        eventDomain(UUID.randomUUID(), EventStatus.OK, Instant.now().plusSeconds(3600)),
                        "VIP", "40.00", 10))
                .pricePaid(new BigDecimal("40.00"))
                .ticketStatus(TicketStatus.VALID)
                .build();

        when(tickets.findDetailById(ticketId)).thenReturn(Optional.of(ticket));
        when(organizers.existsByEvent_IdAndUser_Id(eventId, organizerId)).thenReturn(true);
        when(tickets.updateStatus(ticketId, List.of(TicketStatus.VALID), TicketStatus.REFUNDED)).thenReturn(1);
        when(ticketMapper.toDomain(ticket)).thenReturn(domain);

        TicketResponse response = ticketService.refundTicket(ticketId, organizerId);

        assertThat(response.id()).isEqualTo(ticketId);
        assertThat(response.ticketStatus()).isEqualTo(TicketStatus.REFUNDED);
    }

    @Test
    void refundTicket_rejectsNonOrganizer() {
        UUID eventId = UUID.randomUUID();
        UUID ticketId = UUID.randomUUID();
        UUID callerId = UUID.randomUUID();
        JEvents event = eventEntity(eventId, EventStatus.OK, Instant.now().plusSeconds(3600));
        JTicketTier tier = tierEntity(UUID.randomUUID(), event, "VIP", "40.00", 10);
        JTicket ticket = ticketEntity(ticketId, tier, userEntity(UUID.randomUUID(), "owner"), TicketStatus.VALID);

        when(tickets.findDetailById(ticketId)).thenReturn(Optional.of(ticket));
        when(organizers.existsByEvent_IdAndUser_Id(eventId, callerId)).thenReturn(false);

        assertThatExceptionOfType(ForbiddenException.class)
                .isThrownBy(() -> ticketService.refundTicket(ticketId, callerId))
                .satisfies(ex -> assertThat(ex.getCode()).isEqualTo("NOT_AN_ORGANIZER"));

        verify(tickets, never()).updateStatus(any(), any(), any());
    }

    @Test
    void refundTicket_rejectsUnknownTicket() {
        UUID ticketId = UUID.randomUUID();
        when(tickets.findDetailById(ticketId)).thenReturn(Optional.empty());

        assertThatExceptionOfType(NotFoundException.class)
                .isThrownBy(() -> ticketService.refundTicket(ticketId, UUID.randomUUID()))
                .satisfies(ex -> assertThat(ex.getCode()).isEqualTo("TICKET_NOT_FOUND"));

        verifyNoInteractions(organizers);
    }

    @Test
    void refundTicket_rejectsUsedTicket() {
        UUID eventId = UUID.randomUUID();
        UUID ticketId = UUID.randomUUID();
        UUID organizerId = UUID.randomUUID();
        JEvents event = eventEntity(eventId, EventStatus.OK, Instant.now().plusSeconds(3600));
        JTicketTier tier = tierEntity(UUID.randomUUID(), event, "VIP", "40.00", 10);
        JTicket ticket = ticketEntity(ticketId, tier, userEntity(UUID.randomUUID(), "owner"), TicketStatus.USED);

        when(tickets.findDetailById(ticketId)).thenReturn(Optional.of(ticket));
        when(organizers.existsByEvent_IdAndUser_Id(eventId, organizerId)).thenReturn(true);

        assertThatExceptionOfType(ConflictException.class)
                .isThrownBy(() -> ticketService.refundTicket(ticketId, organizerId))
                .satisfies(ex -> assertThat(ex.getCode()).isEqualTo("TICKET_NOT_REFUNDABLE"));

        verify(tickets, never()).updateStatus(any(), any(), any());
    }

    // ------------------------------------------------------------------
    // listEventTickets
    // ------------------------------------------------------------------

    @Test
    void listEventTickets_mapsPageAndNormalizesQuery() {
        UUID eventId = UUID.randomUUID();
        UUID tierId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        UUID organizerId = UUID.randomUUID();
        UUID ticketId = UUID.randomUUID();
        Instant endTime = Instant.now().plusSeconds(3600);
        JEvents event = eventEntity(eventId, EventStatus.OK, endTime);
        JTicketTier tier = tierEntity(tierId, event, "VIP", "40.00", 10);
        JTicket ticket = ticketEntity(ticketId, tier, userEntity(ownerId, "alice"), TicketStatus.VALID);

        Ticket domain = Ticket.builder()
                .id(ticketId)
                .owner(userDomain(ownerId, "alice", "Alice Doe"))
                .tier(tierDomain(tierId, eventDomain(eventId, EventStatus.OK, endTime), "VIP", "40.00", 10))
                .pricePaid(new BigDecimal("40.00"))
                .ticketStatus(TicketStatus.VALID)
                .build();

        when(events.findById(eventId)).thenReturn(Optional.of(event));
        when(organizers.existsByEvent_IdAndUser_Id(eventId, organizerId)).thenReturn(true);
        when(tickets.findEventAttendees(eq(eventId), eq(TicketStatus.VALID), eq(tierId), eq("%alice%"),
                any(Pageable.class))).thenReturn(new PageImpl<>(List.of(ticket), PageRequest.of(0, 20), 1));
        when(ticketMapper.toDomain(ticket)).thenReturn(domain);

        PageAttendeeTicket response = ticketService.listEventTickets(eventId, organizerId,
                TicketStatus.VALID, tierId, "Alice", 0, 20);

        verify(validator).validateListQuery(0, 20);
        assertThat(response.totalElements()).isEqualTo(1);
        assertThat(response.totalPages()).isEqualTo(1);
        assertThat(response.content()).hasSize(1);
        assertThat(response.content().get(0).id()).isEqualTo(ticketId);
        assertThat(response.content().get(0).owner().username()).isEqualTo("alice");
        assertThat(response.content().get(0).tier().tierName()).isEqualTo("VIP");

        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        verify(tickets).findEventAttendees(eq(eventId), eq(TicketStatus.VALID), eq(tierId), eq("%alice%"),
                pageableCaptor.capture());
        assertThat(pageableCaptor.getValue().getSort().getOrderFor("createdAt").getDirection())
                .isEqualTo(Sort.Direction.ASC);
    }

    @Test
    void listEventTickets_treatsBlankQueryAsNoFilter() {
        UUID eventId = UUID.randomUUID();
        UUID organizerId = UUID.randomUUID();
        JEvents event = eventEntity(eventId, EventStatus.OK, Instant.now().plusSeconds(3600));

        when(events.findById(eventId)).thenReturn(Optional.of(event));
        when(organizers.existsByEvent_IdAndUser_Id(eventId, organizerId)).thenReturn(true);
        when(tickets.findEventAttendees(eq(eventId), isNull(), isNull(), isNull(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0));

        PageAttendeeTicket response = ticketService.listEventTickets(eventId, organizerId, null, null, "   ", 0, 20);

        assertThat(response.content()).isEmpty();
    }

    @Test
    void listEventTickets_rejectsUnknownEvent() {
        UUID eventId = UUID.randomUUID();
        when(events.findById(eventId)).thenReturn(Optional.empty());

        assertThatExceptionOfType(NotFoundException.class)
                .isThrownBy(() -> ticketService.listEventTickets(eventId, UUID.randomUUID(), null, null, null, 0, 20))
                .satisfies(ex -> assertThat(ex.getCode()).isEqualTo("EVENT_NOT_FOUND"));

        verifyNoInteractions(organizers, tickets, validator);
    }

    @Test
    void listEventTickets_rejectsNonOrganizer() {
        UUID eventId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        when(events.findById(eventId)).thenReturn(Optional.of(eventEntity(eventId, EventStatus.OK,
                Instant.now().plusSeconds(3600))));
        when(organizers.existsByEvent_IdAndUser_Id(eventId, userId)).thenReturn(false);

        assertThatExceptionOfType(ForbiddenException.class)
                .isThrownBy(() -> ticketService.listEventTickets(eventId, userId, null, null, null, 0, 20))
                .satisfies(ex -> assertThat(ex.getCode()).isEqualTo("NOT_AN_ORGANIZER"));

        verifyNoInteractions(tickets, validator);
    }

    @Test
    void listEventTickets_propagatesValidationFailure() {
        UUID eventId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        when(events.findById(eventId)).thenReturn(Optional.of(eventEntity(eventId, EventStatus.OK,
                Instant.now().plusSeconds(3600))));
        when(organizers.existsByEvent_IdAndUser_Id(eventId, userId)).thenReturn(true);
        doThrow(new BadRequestException("VALIDATION_ERROR", "Validation failed"))
                .when(validator).validateListQuery(-1, 20);

        assertThatExceptionOfType(BadRequestException.class)
                .isThrownBy(() -> ticketService.listEventTickets(eventId, userId, null, null, null, -1, 20))
                .satisfies(ex -> assertThat(ex.getCode()).isEqualTo("VALIDATION_ERROR"));

        verifyNoInteractions(tickets);
    }

    // ------------------------------------------------------------------
    // fixtures
    // ------------------------------------------------------------------

    private static JEvents eventEntity(UUID id, EventStatus status, Instant endTime) {
        JEvents entity = new JEvents();
        entity.setId(id);
        entity.setTitle("Title");
        entity.setStartTime(endTime.minusSeconds(7200));
        entity.setEndTime(endTime);
        entity.setStatus(status);
        return entity;
    }

    private static JTicketTier tierEntity(UUID id, JEvents event, String name, String price, int capacity) {
        JTicketTier entity = new JTicketTier();
        entity.setId(id);
        entity.setEvent(event);
        entity.setTierName(name);
        entity.setPrice(new BigDecimal(price));
        entity.setCapacity(capacity);
        return entity;
    }

    private static JTicket ticketEntity(UUID id, JTicketTier tier, JUsers owner, TicketStatus status) {
        JTicket entity = new JTicket();
        entity.setId(id);
        entity.setTier(tier);
        entity.setOwner(owner);
        entity.setPricePaid(tier.getPrice());
        entity.setTicketStatus(status);
        return entity;
    }

    private static JUsers userEntity(UUID id, String username) {
        JUsers entity = new JUsers();
        entity.setId(id);
        entity.setUsername(username);
        entity.setFullName("Owner");
        return entity;
    }

    private static Events eventDomain(UUID id, EventStatus status, Instant endTime) {
        Events domain = new Events();
        domain.setId(id);
        domain.setTitle("Title");
        domain.setStartTime(endTime.minusSeconds(7200));
        domain.setEndTime(endTime);
        domain.setStatus(status);
        return domain;
    }

    private static TicketTier tierDomain(UUID id, Events event, String name, String price, int capacity) {
        TicketTier domain = new TicketTier();
        domain.setId(id);
        domain.setEvent(event);
        domain.setTierName(name);
        domain.setPrice(new BigDecimal(price));
        domain.setCapacity(capacity);
        return domain;
    }

    private static Users userDomain(UUID id, String username, String fullName) {
        Users domain = new Users();
        domain.setId(id);
        domain.setUsername(username);
        domain.setFullName(fullName);
        return domain;
    }
}
