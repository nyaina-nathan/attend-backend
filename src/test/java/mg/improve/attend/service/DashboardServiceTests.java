package mg.improve.attend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import mg.improve.attend.domain.dto.response.AttentionItem;
import mg.improve.attend.domain.dto.response.AttentionType;
import mg.improve.attend.domain.dto.response.DashboardResponse;
import mg.improve.attend.domain.dto.response.NextOrganizingEvent;
import mg.improve.attend.domain.entity.Events;
import mg.improve.attend.domain.entity.TicketTier;
import mg.improve.attend.domain.enums.EventRole;
import mg.improve.attend.domain.enums.EventStatus;
import mg.improve.attend.domain.enums.TicketStatus;
import mg.improve.attend.domain.mapper.TicketTierMapper;
import mg.improve.attend.repository.JEventsRepository;
import mg.improve.attend.repository.JTicketRepository;
import mg.improve.attend.repository.JTicketTierRepository;
import mg.improve.attend.repository.entities.JEvents;
import mg.improve.attend.repository.entities.JTicket;
import mg.improve.attend.repository.entities.JTicketTier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class DashboardServiceTests {

    private static final List<EventStatus> UPCOMING = List.of(EventStatus.OK, EventStatus.POSTPONED);
    private static final List<TicketStatus> COUNTED = List.of(TicketStatus.VALID, TicketStatus.USED);
    private static final Instant T0 = Instant.parse("2026-10-08T10:00:00Z");

    @Mock
    private JEventsRepository events;

    @Mock
    private JTicketTierRepository tiers;

    @Mock
    private JTicketRepository tickets;

    @Mock
    private TicketTierMapper tierMapper;

    @InjectMocks
    private DashboardService dashboardService;

    @Test
    void getDashboard_returnsZeroedWhenNoActivity() {
        UUID userId = UUID.randomUUID();

        when(events.countOrganizing(eq(userId), any(), eq(UPCOMING))).thenReturn(counters(0, 0));
        when(tickets.aggregateOrganizingSales(eq(userId), any(), eq(UPCOMING), eq(COUNTED)))
                .thenReturn(sales(0, null));
        when(events.findNextOrganizingEvent(eq(userId), any(), eq(UPCOMING), any()))
                .thenReturn(List.of());
        when(events.findNoTierAttention(eq(userId), any(), any(), eq(EventStatus.OK)))
                .thenReturn(List.of());
        when(tiers.findLowStockTiers(eq(userId), any(), eq(EventStatus.OK), eq(COUNTED)))
                .thenReturn(List.of());
        when(events.findPostponedAttention(eq(userId), eq(EventStatus.POSTPONED), eq(COUNTED)))
                .thenReturn(List.of());
        when(tickets.countUpcomingTickets(eq(userId), any(), eq(UPCOMING), eq(TicketStatus.VALID)))
                .thenReturn(0L);
        when(tickets.findNextUpcomingTicket(eq(userId), any(), eq(UPCOMING), eq(TicketStatus.VALID), any()))
                .thenReturn(List.of());

        DashboardResponse response = dashboardService.getDashboard(userId);

        assertThat(response.organizing().eventsTotal()).isZero();
        assertThat(response.organizing().eventsUpcoming()).isZero();
        assertThat(response.organizing().ticketsSold()).isZero();
        assertThat(response.organizing().revenue()).isEqualByComparingTo("0");
        assertThat(response.organizing().nextEvent()).isNull();
        assertThat(response.organizing().attention()).isEmpty();
        assertThat(response.attending().ticketsUpcoming()).isZero();
        assertThat(response.attending().nextTicket()).isNull();
    }

    @Test
    void getDashboard_aggregatesOrganizingAndNextEvent() {
        UUID userId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        UUID tierId = UUID.randomUUID();
        JEvents event = event(eventId, "Tana Jazz Night", T0.plusSeconds(3600 * 24 * 30), EventStatus.OK);

        when(events.countOrganizing(eq(userId), any(), eq(UPCOMING))).thenReturn(counters(12, 3));
        when(tickets.aggregateOrganizingSales(eq(userId), any(), eq(UPCOMING), eq(COUNTED)))
                .thenReturn(sales(214, new BigDecimal("3450.00")));
        when(events.findNextOrganizingEvent(eq(userId), any(), eq(UPCOMING), any()))
                .thenReturn(List.of(nextEventRow(event, EventRole.CREATOR)));
        when(tiers.findByEvent_Id(eventId)).thenReturn(List.of(tier(tierId, "VIP", 250)));
        when(tickets.aggregateByTier(eq(eventId), eq(COUNTED), eq(TicketStatus.USED)))
                .thenReturn(List.of(aggregate(tierId, 107, 0, new BigDecimal("4280.00"))));
        when(events.findNoTierAttention(eq(userId), any(), any(), eq(EventStatus.OK)))
                .thenReturn(List.of());
        when(tiers.findLowStockTiers(eq(userId), any(), eq(EventStatus.OK), eq(COUNTED)))
                .thenReturn(List.of());
        when(events.findPostponedAttention(eq(userId), eq(EventStatus.POSTPONED), eq(COUNTED)))
                .thenReturn(List.of());
        when(tickets.countUpcomingTickets(eq(userId), any(), eq(UPCOMING), eq(TicketStatus.VALID)))
                .thenReturn(0L);
        when(tickets.findNextUpcomingTicket(eq(userId), any(), eq(UPCOMING), eq(TicketStatus.VALID), any()))
                .thenReturn(List.of());

        DashboardResponse response = dashboardService.getDashboard(userId);

        assertThat(response.organizing().eventsTotal()).isEqualTo(12);
        assertThat(response.organizing().eventsUpcoming()).isEqualTo(3);
        assertThat(response.organizing().ticketsSold()).isEqualTo(214);
        assertThat(response.organizing().revenue()).isEqualByComparingTo("3450.00");

        NextOrganizingEvent next = response.organizing().nextEvent();
        assertThat(next).isNotNull();
        assertThat(next.id()).isEqualTo(eventId);
        assertThat(next.title()).isEqualTo("Tana Jazz Night");
        assertThat(next.status()).isEqualTo(EventStatus.OK);
        assertThat(next.myRole()).isEqualTo(EventRole.CREATOR);
        assertThat(next.totalCapacity()).isEqualTo(250);
        assertThat(next.ticketsIssued()).isEqualTo(107);
        assertThat(next.ticketsCheckedIn()).isZero();
    }

    @Test
    void getDashboard_mergesAttentionAndAppliesRules() {
        UUID userId = UUID.randomUUID();
        UUID earlyEvent = UUID.randomUUID();
        UUID lateEvent = UUID.randomUUID();
        UUID stockEvent = UUID.randomUUID();
        UUID soldOutEvent = UUID.randomUUID();

        when(events.countOrganizing(eq(userId), any(), eq(UPCOMING))).thenReturn(counters(4, 4));
        when(tickets.aggregateOrganizingSales(eq(userId), any(), eq(UPCOMING), eq(COUNTED)))
                .thenReturn(sales(0, null));
        when(events.findNextOrganizingEvent(eq(userId), any(), eq(UPCOMING), any()))
                .thenReturn(List.of());
        when(events.findNoTierAttention(eq(userId), any(), any(), eq(EventStatus.OK)))
                .thenReturn(List.of(attentionEvent(lateEvent, "Open Mic", T0.plusSeconds(5 * 86400))));
        when(tiers.findLowStockTiers(eq(userId), any(), eq(EventStatus.OK), eq(COUNTED)))
                .thenReturn(List.of(
                        lowStockTier(UUID.randomUUID(), "VIP", stockEvent, "Gala", T0.plusSeconds(2 * 86400), 13, 10),
                        // remaining 0: sold out, excluded
                        lowStockTier(UUID.randomUUID(), "Std", soldOutEvent, "Sold Out",
                                T0.plusSeconds(3 * 86400), 5, 5),
                        // remaining 11: above the threshold, excluded
                        lowStockTier(UUID.randomUUID(), "Std", UUID.randomUUID(), "Plenty",
                                T0.plusSeconds(4 * 86400), 20, 9)));
        when(events.findPostponedAttention(eq(userId), eq(EventStatus.POSTPONED), eq(COUNTED)))
                .thenReturn(List.of(postponed(earlyEvent, "Rescheduled", T0.plusSeconds(86400), 7)));
        when(tickets.countUpcomingTickets(eq(userId), any(), eq(UPCOMING), eq(TicketStatus.VALID)))
                .thenReturn(0L);
        when(tickets.findNextUpcomingTicket(eq(userId), any(), eq(UPCOMING), eq(TicketStatus.VALID), any()))
                .thenReturn(List.of());

        List<AttentionItem> attention = dashboardService.getDashboard(userId).organizing().attention();

        assertThat(attention).extracting(AttentionItem::type)
                .containsExactly(AttentionType.POSTPONED, AttentionType.LOW_STOCK, AttentionType.NO_TIERS);
        assertThat(attention).extracting(AttentionItem::eventId)
                .containsExactly(earlyEvent, stockEvent, lateEvent);

        AttentionItem lowStock = attention.get(1);
        assertThat(lowStock.tierName()).isEqualTo("VIP");
        assertThat(lowStock.remaining()).isEqualTo(3);
        assertThat(lowStock.ticketsIssued()).isNull();

        AttentionItem postponed = attention.get(0);
        assertThat(postponed.ticketsIssued()).isEqualTo(7);
        assertThat(postponed.tierId()).isNull();
    }

    @Test
    void getDashboard_capsAttentionAtFiveOrderedByStartTime() {
        UUID userId = UUID.randomUUID();
        List<JEventsRepository.AttentionEventRow> rows = new java.util.ArrayList<>();
        for (int i = 6; i >= 1; i--) {
            rows.add(attentionEvent(UUID.randomUUID(), "Event " + i, T0.plusSeconds(i * 3600L)));
        }

        when(events.countOrganizing(eq(userId), any(), eq(UPCOMING))).thenReturn(counters(6, 6));
        when(tickets.aggregateOrganizingSales(eq(userId), any(), eq(UPCOMING), eq(COUNTED)))
                .thenReturn(sales(0, null));
        when(events.findNextOrganizingEvent(eq(userId), any(), eq(UPCOMING), any()))
                .thenReturn(List.of());
        when(events.findNoTierAttention(eq(userId), any(), any(), eq(EventStatus.OK))).thenReturn(rows);
        when(tiers.findLowStockTiers(eq(userId), any(), eq(EventStatus.OK), eq(COUNTED)))
                .thenReturn(List.of());
        when(events.findPostponedAttention(eq(userId), eq(EventStatus.POSTPONED), eq(COUNTED)))
                .thenReturn(List.of());
        when(tickets.countUpcomingTickets(eq(userId), any(), eq(UPCOMING), eq(TicketStatus.VALID)))
                .thenReturn(0L);
        when(tickets.findNextUpcomingTicket(eq(userId), any(), eq(UPCOMING), eq(TicketStatus.VALID), any()))
                .thenReturn(List.of());

        List<AttentionItem> attention = dashboardService.getDashboard(userId).organizing().attention();

        assertThat(attention).hasSize(5);
        assertThat(attention).extracting(AttentionItem::eventTitle)
                .containsExactly("Event 1", "Event 2", "Event 3", "Event 4", "Event 5");
    }

    @Test
    void getDashboard_mapsNextTicket() {
        UUID userId = UUID.randomUUID();
        UUID ticketId = UUID.randomUUID();
        UUID tierId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        JEvents event = event(eventId, "Antsirabe Food Fair", T0.plusSeconds(86400), EventStatus.OK);
        JTicketTier entityTier = new JTicketTier();
        entityTier.setId(tierId);
        entityTier.setTierName("Standard");
        entityTier.setEvent(event);
        JTicket entity = new JTicket();
        entity.setId(ticketId);
        entity.setTier(entityTier);
        entity.setTicketStatus(TicketStatus.VALID);

        TicketTier domainTier = new TicketTier();
        domainTier.setId(tierId);
        domainTier.setTierName("Standard");
        Events domainEvent = new Events();
        domainEvent.setId(eventId);
        domainEvent.setTitle("Antsirabe Food Fair");
        domainEvent.setStartTime(T0.plusSeconds(86400));
        domainEvent.setLocation("Antsirabe");
        domainTier.setEvent(domainEvent);

        when(events.countOrganizing(eq(userId), any(), eq(UPCOMING))).thenReturn(counters(0, 0));
        when(tickets.aggregateOrganizingSales(eq(userId), any(), eq(UPCOMING), eq(COUNTED)))
                .thenReturn(sales(0, null));
        when(events.findNextOrganizingEvent(eq(userId), any(), eq(UPCOMING), any()))
                .thenReturn(List.of());
        when(events.findNoTierAttention(eq(userId), any(), any(), eq(EventStatus.OK)))
                .thenReturn(List.of());
        when(tiers.findLowStockTiers(eq(userId), any(), eq(EventStatus.OK), eq(COUNTED)))
                .thenReturn(List.of());
        when(events.findPostponedAttention(eq(userId), eq(EventStatus.POSTPONED), eq(COUNTED)))
                .thenReturn(List.of());
        when(tickets.countUpcomingTickets(eq(userId), any(), eq(UPCOMING), eq(TicketStatus.VALID)))
                .thenReturn(2L);
        when(tickets.findNextUpcomingTicket(eq(userId), any(), eq(UPCOMING), eq(TicketStatus.VALID), any()))
                .thenReturn(List.of(entity));
        when(tierMapper.toDomain(entityTier)).thenReturn(domainTier);

        DashboardResponse response = dashboardService.getDashboard(userId);

        assertThat(response.attending().ticketsUpcoming()).isEqualTo(2);
        assertThat(response.attending().nextTicket()).isNotNull();
        assertThat(response.attending().nextTicket().id()).isEqualTo(ticketId);
        assertThat(response.attending().nextTicket().event().id()).isEqualTo(eventId);
        assertThat(response.attending().nextTicket().event().location()).isEqualTo("Antsirabe");
        assertThat(response.attending().nextTicket().tier().tierName()).isEqualTo("Standard");
        assertThat(response.attending().nextTicket().ticketStatus()).isEqualTo(TicketStatus.VALID);
    }

    // ------------------------------------------------------------------
    // fixtures
    // ------------------------------------------------------------------

    private static JEventsRepository.OrganizingCounters counters(long total, long upcoming) {
        return new JEventsRepository.OrganizingCounters() {
            @Override
            public long getEventsTotal() {
                return total;
            }

            @Override
            public long getEventsUpcoming() {
                return upcoming;
            }
        };
    }

    private static JTicketRepository.OrganizingSales sales(long ticketsSold, BigDecimal revenue) {
        return new JTicketRepository.OrganizingSales() {
            @Override
            public long getTicketsSold() {
                return ticketsSold;
            }

            @Override
            public BigDecimal getRevenue() {
                return revenue;
            }
        };
    }

    private static JEventsRepository.NextOrganizingEventRow nextEventRow(JEvents event, EventRole role) {
        return new JEventsRepository.NextOrganizingEventRow() {
            @Override
            public JEvents getEvent() {
                return event;
            }

            @Override
            public EventRole getEventRole() {
                return role;
            }
        };
    }

    private static JEventsRepository.AttentionEventRow attentionEvent(UUID eventId, String title, Instant start) {
        return new JEventsRepository.AttentionEventRow() {
            @Override
            public UUID getEventId() {
                return eventId;
            }

            @Override
            public String getEventTitle() {
                return title;
            }

            @Override
            public Instant getStartTime() {
                return start;
            }
        };
    }

    private static JEventsRepository.PostponedAttentionRow postponed(UUID eventId, String title, Instant start,
            long issued) {
        return new JEventsRepository.PostponedAttentionRow() {
            @Override
            public UUID getEventId() {
                return eventId;
            }

            @Override
            public String getEventTitle() {
                return title;
            }

            @Override
            public Instant getStartTime() {
                return start;
            }

            @Override
            public long getTicketsIssued() {
                return issued;
            }
        };
    }

    private static JTicketTierRepository.LowStockTier lowStockTier(UUID tierId, String tierName, UUID eventId,
            String eventTitle, Instant start, int capacity, long issued) {
        return new JTicketTierRepository.LowStockTier() {
            @Override
            public UUID getTierId() {
                return tierId;
            }

            @Override
            public String getTierName() {
                return tierName;
            }

            @Override
            public UUID getEventId() {
                return eventId;
            }

            @Override
            public String getEventTitle() {
                return eventTitle;
            }

            @Override
            public Instant getStartTime() {
                return start;
            }

            @Override
            public int getCapacity() {
                return capacity;
            }

            @Override
            public long getIssued() {
                return issued;
            }
        };
    }

    private static JTicketRepository.TierAggregate aggregate(UUID tierId, long issued, long checkedIn,
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

    private static JEvents event(UUID id, String title, Instant start, EventStatus status) {
        JEvents event = new JEvents();
        event.setId(id);
        event.setTitle(title);
        event.setStartTime(start);
        event.setEndTime(start.plusSeconds(3600));
        event.setStatus(status);
        event.setLocation("Location");
        return event;
    }

    private static JTicketTier tier(UUID id, String name, int capacity) {
        JTicketTier tier = new JTicketTier();
        tier.setId(id);
        tier.setTierName(name);
        tier.setPrice(new BigDecimal("10.00"));
        tier.setCapacity(capacity);
        return tier;
    }
}
