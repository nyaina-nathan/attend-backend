package mg.improve.attend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import mg.improve.attend.domain.dto.response.TicketResponse;
import mg.improve.attend.domain.entity.Events;
import mg.improve.attend.domain.entity.Ticket;
import mg.improve.attend.domain.entity.TicketTier;
import mg.improve.attend.domain.enums.EventStatus;
import mg.improve.attend.domain.enums.TicketStatus;
import mg.improve.attend.domain.mapper.TicketMapper;
import mg.improve.attend.exceptions.ConflictException;
import mg.improve.attend.exceptions.NotFoundException;
import mg.improve.attend.repository.JTicketRepository;
import mg.improve.attend.repository.JTicketTierRepository;
import mg.improve.attend.repository.JUsersRepository;
import mg.improve.attend.repository.entities.JEvents;
import mg.improve.attend.repository.entities.JTicket;
import mg.improve.attend.repository.entities.JTicketTier;
import mg.improve.attend.repository.entities.JUsers;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

@ExtendWith(MockitoExtension.class)
class TicketServiceTests {

    private static final List<TicketStatus> CAPACITY_STATUSES =
            List.of(TicketStatus.VALID, TicketStatus.USED);

    @Mock
    private JTicketTierRepository tiers;

    @Mock
    private JTicketRepository tickets;

    @Mock
    private JUsersRepository users;

    @Mock
    private TicketMapper ticketMapper;

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
}
