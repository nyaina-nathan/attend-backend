package mg.improve.attend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import mg.improve.attend.domain.dto.request.TierCreateRequest;
import mg.improve.attend.domain.dto.request.TierUpdateRequest;
import mg.improve.attend.domain.dto.response.Tier;
import mg.improve.attend.domain.entity.Events;
import mg.improve.attend.domain.entity.TicketTier;
import mg.improve.attend.domain.enums.TicketStatus;
import mg.improve.attend.domain.mapper.TicketTierMapper;
import mg.improve.attend.exceptions.BadRequestException;
import mg.improve.attend.exceptions.ConflictException;
import mg.improve.attend.exceptions.ForbiddenException;
import mg.improve.attend.exceptions.NotFoundException;
import mg.improve.attend.exceptions.UnauthorizedException;
import mg.improve.attend.repository.JEventOrganizerRepository;
import mg.improve.attend.repository.JEventsRepository;
import mg.improve.attend.repository.JTicketOrderItemRepository;
import mg.improve.attend.repository.JTicketRepository;
import mg.improve.attend.repository.JTicketTierRepository;
import mg.improve.attend.repository.entities.JEvents;
import mg.improve.attend.repository.entities.JTicketTier;
import mg.improve.attend.validator.TicketTierValidator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

@ExtendWith(MockitoExtension.class)
class TicketTierServiceTests {

    private static final List<TicketStatus> CAPACITY_STATUSES =
            List.of(TicketStatus.VALID, TicketStatus.USED);

    @Mock
    private JEventsRepository events;

    @Mock
    private JEventOrganizerRepository organizers;

    @Mock
    private JTicketTierRepository tiers;

    @Mock
    private JTicketRepository tickets;

    @Mock
    private JTicketOrderItemRepository ticketOrderItems;

    @Mock
    private TicketTierMapper tierMapper;

    @Mock
    private TicketTierValidator validator;

    @InjectMocks
    private TicketTierService tierService;

    // ------------------------------------------------------------------
    // listTiers
    // ------------------------------------------------------------------

    @Test
    void listTiers_returnsTiersForPublicEventToAnonymous() {
        UUID eventId = UUID.randomUUID();
        UUID tierId = UUID.randomUUID();
        JEvents event = eventEntity(eventId, true);
        JTicketTier tier = tierEntity(tierId, event, "VIP", "40.00", 10);
        TicketTier domain = tierDomain(tierId, eventDomain(eventId, true), "VIP", "40.00", 10);

        when(events.findById(eventId)).thenReturn(Optional.of(event));
        when(tiers.findByEvent_Id(eventId)).thenReturn(List.of(tier));
        when(tickets.countIssuedByTier(eventId, CAPACITY_STATUSES))
                .thenReturn(List.of(tierIssued(tierId, 3)));
        when(tierMapper.toDomain(tier)).thenReturn(domain);

        List<Tier> response = tierService.listTiers(eventId, null);

        assertThat(response).hasSize(1);
        assertThat(response.get(0).id()).isEqualTo(tierId);
        assertThat(response.get(0).eventId()).isEqualTo(eventId);
        assertThat(response.get(0).remaining()).isEqualTo(7);
        verifyNoInteractions(organizers);
    }

    @Test
    void listTiers_allowsOrganizerOnPrivateEvent() {
        UUID eventId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        JEvents event = eventEntity(eventId, false);

        when(events.findById(eventId)).thenReturn(Optional.of(event));
        when(organizers.existsByEvent_IdAndUser_Id(eventId, userId)).thenReturn(true);
        when(tiers.findByEvent_Id(eventId)).thenReturn(List.of());

        assertThat(tierService.listTiers(eventId, userId)).isEmpty();
    }

    @Test
    void listTiers_rejectsAnonymousOnPrivateEvent() {
        UUID eventId = UUID.randomUUID();
        JEvents event = eventEntity(eventId, false);
        when(events.findById(eventId)).thenReturn(Optional.of(event));

        assertThatExceptionOfType(UnauthorizedException.class)
                .isThrownBy(() -> tierService.listTiers(eventId, null))
                .satisfies(ex -> assertThat(ex.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED));

        verifyNoInteractions(tiers, tickets);
    }

    @Test
    void listTiers_rejectsNonOrganizerOnPrivateEvent() {
        UUID eventId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        JEvents event = eventEntity(eventId, false);

        when(events.findById(eventId)).thenReturn(Optional.of(event));
        when(organizers.existsByEvent_IdAndUser_Id(eventId, userId)).thenReturn(false);

        assertThatExceptionOfType(ForbiddenException.class)
                .isThrownBy(() -> tierService.listTiers(eventId, userId))
                .satisfies(ex -> assertThat(ex.getCode()).isEqualTo("NOT_AN_ORGANIZER"));

        verifyNoInteractions(tiers, tickets);
    }

    @Test
    void listTiers_rejectsUnknownEvent() {
        UUID eventId = UUID.randomUUID();
        when(events.findById(eventId)).thenReturn(Optional.empty());

        assertThatExceptionOfType(NotFoundException.class)
                .isThrownBy(() -> tierService.listTiers(eventId, null))
                .satisfies(ex -> assertThat(ex.getCode()).isEqualTo("EVENT_NOT_FOUND"));

        verifyNoInteractions(tiers, tickets);
    }

    @Test
    void listTiers_skipsTicketQueryWhenNoTiers() {
        UUID eventId = UUID.randomUUID();
        JEvents event = eventEntity(eventId, true);

        when(events.findById(eventId)).thenReturn(Optional.of(event));
        when(tiers.findByEvent_Id(eventId)).thenReturn(List.of());

        assertThat(tierService.listTiers(eventId, null)).isEmpty();
        verifyNoInteractions(tickets);
    }

    // ------------------------------------------------------------------
    // createTier
    // ------------------------------------------------------------------

    @Test
    void createTier_persistsTierForOrganizer() {
        UUID eventId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID tierId = UUID.randomUUID();
        JEvents event = eventEntity(eventId, true);
        TierCreateRequest request = new TierCreateRequest("VIP", new BigDecimal("40.00"), 50);
        JTicketTier saved = tierEntity(tierId, event, "VIP", "40.00", 50);
        TicketTier domain = tierDomain(tierId, eventDomain(eventId, true), "VIP", "40.00", 50);

        when(events.findById(eventId)).thenReturn(Optional.of(event));
        when(organizers.existsByEvent_IdAndUser_Id(eventId, userId)).thenReturn(true);
        when(tiers.existsByEvent_IdAndTierName(eventId, "VIP")).thenReturn(false);
        when(tiers.saveAndFlush(any(JTicketTier.class))).thenReturn(saved);
        when(tierMapper.toDomain(saved)).thenReturn(domain);

        Tier response = tierService.createTier(eventId, userId, request);

        verify(validator).validateCreate(request);
        ArgumentCaptor<JTicketTier> captor = ArgumentCaptor.forClass(JTicketTier.class);
        verify(tiers).saveAndFlush(captor.capture());
        assertThat(captor.getValue().getTierName()).isEqualTo("VIP");
        assertThat(captor.getValue().getPrice()).isEqualByComparingTo("40.00");
        assertThat(captor.getValue().getCapacity()).isEqualTo(50);
        assertThat(captor.getValue().getEvent()).isSameAs(event);
        assertThat(response.remaining()).isEqualTo(50);
        assertThat(response.eventId()).isEqualTo(eventId);
    }

    @Test
    void createTier_rejectsNonOrganizer() {
        UUID eventId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        JEvents event = eventEntity(eventId, true);
        TierCreateRequest request = new TierCreateRequest("VIP", new BigDecimal("40.00"), 50);

        when(events.findById(eventId)).thenReturn(Optional.of(event));
        when(organizers.existsByEvent_IdAndUser_Id(eventId, userId)).thenReturn(false);

        assertThatExceptionOfType(ForbiddenException.class)
                .isThrownBy(() -> tierService.createTier(eventId, userId, request))
                .satisfies(ex -> assertThat(ex.getCode()).isEqualTo("NOT_AN_ORGANIZER"));

        verifyNoInteractions(validator);
        verify(tiers, never()).saveAndFlush(any());
    }

    @Test
    void createTier_rejectsUnknownEvent() {
        UUID eventId = UUID.randomUUID();
        when(events.findById(eventId)).thenReturn(Optional.empty());

        assertThatExceptionOfType(NotFoundException.class)
                .isThrownBy(() -> tierService.createTier(eventId, UUID.randomUUID(),
                        new TierCreateRequest("VIP", new BigDecimal("40.00"), 50)))
                .satisfies(ex -> assertThat(ex.getCode()).isEqualTo("EVENT_NOT_FOUND"));

        verifyNoInteractions(validator);
    }

    @Test
    void createTier_propagatesValidationFailure() {
        UUID eventId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        JEvents event = eventEntity(eventId, true);
        TierCreateRequest request = new TierCreateRequest(null, null, null);

        when(events.findById(eventId)).thenReturn(Optional.of(event));
        when(organizers.existsByEvent_IdAndUser_Id(eventId, userId)).thenReturn(true);
        doThrow(new BadRequestException("VALIDATION_ERROR", "Validation failed"))
                .when(validator).validateCreate(any(TierCreateRequest.class));

        assertThatExceptionOfType(BadRequestException.class)
                .isThrownBy(() -> tierService.createTier(eventId, userId, request))
                .satisfies(ex -> assertThat(ex.getCode()).isEqualTo("VALIDATION_ERROR"));

        verify(tiers, never()).saveAndFlush(any());
    }

    @Test
    void createTier_rejectsDuplicateName() {
        UUID eventId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        JEvents event = eventEntity(eventId, true);
        TierCreateRequest request = new TierCreateRequest("VIP", new BigDecimal("40.00"), 50);

        when(events.findById(eventId)).thenReturn(Optional.of(event));
        when(organizers.existsByEvent_IdAndUser_Id(eventId, userId)).thenReturn(true);
        when(tiers.existsByEvent_IdAndTierName(eventId, "VIP")).thenReturn(true);

        assertThatExceptionOfType(ConflictException.class)
                .isThrownBy(() -> tierService.createTier(eventId, userId, request))
                .satisfies(ex -> {
                    assertThat(ex.getCode()).isEqualTo("TIER_NAME_TAKEN");
                    assertThat(ex.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                });

        verify(tiers, never()).saveAndFlush(any());
    }

    // ------------------------------------------------------------------
    // updateTier
    // ------------------------------------------------------------------

    @Test
    void updateTier_appliesFieldsAndRecomputesRemaining() {
        UUID eventId = UUID.randomUUID();
        UUID tierId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        JEvents event = eventEntity(eventId, true);
        JTicketTier entity = tierEntity(tierId, event, "VIP", "40.00", 10);
        TierUpdateRequest request = new TierUpdateRequest("VIP Plus", new BigDecimal("45.00"), 5);
        TicketTier domain = tierDomain(tierId, eventDomain(eventId, true), "VIP Plus", "45.00", 5);

        when(tiers.findById(tierId)).thenReturn(Optional.of(entity));
        when(organizers.existsByEvent_IdAndUser_Id(eventId, userId)).thenReturn(true);
        when(tiers.existsByEvent_IdAndTierNameAndIdNot(eventId, "VIP Plus", tierId)).thenReturn(false);
        when(tickets.countByTier_IdAndTicketStatusIn(tierId, CAPACITY_STATUSES)).thenReturn(2L);
        when(tiers.saveAndFlush(entity)).thenReturn(entity);
        when(tierMapper.toDomain(entity)).thenReturn(domain);

        Tier response = tierService.updateTier(tierId, userId, request);

        verify(validator).validateUpdate(request);
        assertThat(entity.getTierName()).isEqualTo("VIP Plus");
        assertThat(entity.getPrice()).isEqualByComparingTo("45.00");
        assertThat(entity.getCapacity()).isEqualTo(5);
        assertThat(response.remaining()).isEqualTo(3);
    }

    @Test
    void updateTier_rejectsNonOrganizer() {
        UUID eventId = UUID.randomUUID();
        UUID tierId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        JTicketTier entity = tierEntity(tierId, eventEntity(eventId, true), "VIP", "40.00", 10);

        when(tiers.findById(tierId)).thenReturn(Optional.of(entity));
        when(organizers.existsByEvent_IdAndUser_Id(eventId, userId)).thenReturn(false);

        assertThatExceptionOfType(ForbiddenException.class)
                .isThrownBy(() -> tierService.updateTier(tierId, userId,
                        new TierUpdateRequest("Nope", null, null)))
                .satisfies(ex -> assertThat(ex.getCode()).isEqualTo("NOT_AN_ORGANIZER"));

        verifyNoInteractions(validator);
        verify(tiers, never()).saveAndFlush(any());
    }

    @Test
    void updateTier_rejectsUnknownTier() {
        UUID tierId = UUID.randomUUID();
        when(tiers.findById(tierId)).thenReturn(Optional.empty());

        assertThatExceptionOfType(NotFoundException.class)
                .isThrownBy(() -> tierService.updateTier(tierId, UUID.randomUUID(),
                        new TierUpdateRequest("Nope", null, null)))
                .satisfies(ex -> assertThat(ex.getCode()).isEqualTo("TIER_NOT_FOUND"));

        verifyNoInteractions(organizers, validator);
    }

    @Test
    void updateTier_rejectsDuplicateName() {
        UUID eventId = UUID.randomUUID();
        UUID tierId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        JTicketTier entity = tierEntity(tierId, eventEntity(eventId, true), "VIP", "40.00", 10);

        when(tiers.findById(tierId)).thenReturn(Optional.of(entity));
        when(organizers.existsByEvent_IdAndUser_Id(eventId, userId)).thenReturn(true);
        when(tiers.existsByEvent_IdAndTierNameAndIdNot(eventId, "Standard", tierId)).thenReturn(true);

        assertThatExceptionOfType(ConflictException.class)
                .isThrownBy(() -> tierService.updateTier(tierId, userId,
                        new TierUpdateRequest("Standard", null, null)))
                .satisfies(ex -> assertThat(ex.getCode()).isEqualTo("TIER_NAME_TAKEN"));

        verify(tiers, never()).saveAndFlush(any());
    }

    @Test
    void updateTier_rejectsCapacityBelowIssued() {
        UUID eventId = UUID.randomUUID();
        UUID tierId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        JTicketTier entity = tierEntity(tierId, eventEntity(eventId, true), "VIP", "40.00", 10);

        when(tiers.findById(tierId)).thenReturn(Optional.of(entity));
        when(organizers.existsByEvent_IdAndUser_Id(eventId, userId)).thenReturn(true);
        when(tickets.countByTier_IdAndTicketStatusIn(tierId, CAPACITY_STATUSES)).thenReturn(5L);

        assertThatExceptionOfType(ConflictException.class)
                .isThrownBy(() -> tierService.updateTier(tierId, userId,
                        new TierUpdateRequest(null, null, 2)))
                .satisfies(ex -> {
                    assertThat(ex.getCode()).isEqualTo("CAPACITY_BELOW_ISSUED");
                    assertThat(ex.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                });

        verify(tiers, never()).saveAndFlush(any());
    }

    @Test
    void updateTier_propagatesValidationFailure() {
        UUID eventId = UUID.randomUUID();
        UUID tierId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        JTicketTier entity = tierEntity(tierId, eventEntity(eventId, true), "VIP", "40.00", 10);

        when(tiers.findById(tierId)).thenReturn(Optional.of(entity));
        when(organizers.existsByEvent_IdAndUser_Id(eventId, userId)).thenReturn(true);
        doThrow(new BadRequestException("VALIDATION_ERROR", "Validation failed"))
                .when(validator).validateUpdate(any(TierUpdateRequest.class));

        assertThatExceptionOfType(BadRequestException.class)
                .isThrownBy(() -> tierService.updateTier(tierId, userId,
                        new TierUpdateRequest(null, new BigDecimal("-1"), null)));

        verify(tiers, never()).saveAndFlush(any());
    }

    // ------------------------------------------------------------------
    // deleteTier
    // ------------------------------------------------------------------

    @Test
    void deleteTier_deletesWhenNoTickets() {
        UUID eventId = UUID.randomUUID();
        UUID tierId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        JTicketTier entity = tierEntity(tierId, eventEntity(eventId, true), "VIP", "40.00", 10);

        when(tiers.findById(tierId)).thenReturn(Optional.of(entity));
        when(organizers.existsByEvent_IdAndUser_Id(eventId, userId)).thenReturn(true);
        when(tickets.existsByTier_Id(tierId)).thenReturn(false);

        tierService.deleteTier(tierId, userId);

        verify(tiers).delete(entity);
    }

    @Test
    void deleteTier_rejectsNonOrganizer() {
        UUID eventId = UUID.randomUUID();
        UUID tierId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        JTicketTier entity = tierEntity(tierId, eventEntity(eventId, true), "VIP", "40.00", 10);

        when(tiers.findById(tierId)).thenReturn(Optional.of(entity));
        when(organizers.existsByEvent_IdAndUser_Id(eventId, userId)).thenReturn(false);

        assertThatExceptionOfType(ForbiddenException.class)
                .isThrownBy(() -> tierService.deleteTier(tierId, userId))
                .satisfies(ex -> assertThat(ex.getCode()).isEqualTo("NOT_AN_ORGANIZER"));

        verify(tiers, never()).delete(any());
    }

    @Test
    void deleteTier_rejectsUnknownTier() {
        UUID tierId = UUID.randomUUID();
        when(tiers.findById(tierId)).thenReturn(Optional.empty());

        assertThatExceptionOfType(NotFoundException.class)
                .isThrownBy(() -> tierService.deleteTier(tierId, UUID.randomUUID()))
                .satisfies(ex -> assertThat(ex.getCode()).isEqualTo("TIER_NOT_FOUND"));

        verifyNoInteractions(organizers);
        verify(tiers, never()).delete(any());
    }

    @Test
    void deleteTier_rejectsWhenTicketsExist() {
        UUID eventId = UUID.randomUUID();
        UUID tierId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        JTicketTier entity = tierEntity(tierId, eventEntity(eventId, true), "VIP", "40.00", 10);

        when(tiers.findById(tierId)).thenReturn(Optional.of(entity));
        when(organizers.existsByEvent_IdAndUser_Id(eventId, userId)).thenReturn(true);
        when(tickets.existsByTier_Id(tierId)).thenReturn(true);

        assertThatExceptionOfType(ConflictException.class)
                .isThrownBy(() -> tierService.deleteTier(tierId, userId))
                .satisfies(ex -> {
                    assertThat(ex.getCode()).isEqualTo("TIER_HAS_TICKETS");
                    assertThat(ex.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                });

        verify(tiers, never()).delete(any());
    }

    @Test
    void deleteTier_rejectsWhenOrdersExist() {
        UUID eventId = UUID.randomUUID();
        UUID tierId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        JTicketTier entity = tierEntity(tierId, eventEntity(eventId, true), "VIP", "40.00", 10);

        when(tiers.findById(tierId)).thenReturn(Optional.of(entity));
        when(organizers.existsByEvent_IdAndUser_Id(eventId, userId)).thenReturn(true);
        when(tickets.existsByTier_Id(tierId)).thenReturn(false);
        when(ticketOrderItems.existsByTier_Id(tierId)).thenReturn(true);

        assertThatExceptionOfType(ConflictException.class)
                .isThrownBy(() -> tierService.deleteTier(tierId, userId))
                .satisfies(ex -> {
                    assertThat(ex.getCode()).isEqualTo("TIER_IN_USE");
                    assertThat(ex.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                });

        verify(tiers, never()).delete(any());
    }

    // ------------------------------------------------------------------
    // fixtures
    // ------------------------------------------------------------------

    private static JEvents eventEntity(UUID id, boolean isPublic) {
        JEvents entity = new JEvents();
        entity.setId(id);
        entity.setTitle("Title");
        entity.setPublic(isPublic);
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

    private static Events eventDomain(UUID id, boolean isPublic) {
        Events domain = new Events();
        domain.setId(id);
        domain.setPublic(isPublic);
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
}
