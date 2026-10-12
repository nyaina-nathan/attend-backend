package mg.improve.attend.service;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import mg.improve.attend.domain.dto.response.AttendingDashboard;
import mg.improve.attend.domain.dto.response.AttentionItem;
import mg.improve.attend.domain.dto.response.DashboardResponse;
import mg.improve.attend.domain.dto.response.NextOrganizingEvent;
import mg.improve.attend.domain.dto.response.NextTicket;
import mg.improve.attend.domain.dto.response.OrganizingDashboard;
import mg.improve.attend.domain.dto.response.TicketEventRef;
import mg.improve.attend.domain.dto.response.TicketTierRef;
import mg.improve.attend.domain.entity.TicketTier;
import mg.improve.attend.domain.enums.EventStatus;
import mg.improve.attend.domain.enums.TicketStatus;
import mg.improve.attend.domain.mapper.TicketTierMapper;
import mg.improve.attend.repository.JEventsRepository;
import mg.improve.attend.repository.JTicketRepository;
import mg.improve.attend.repository.JTicketTierRepository;
import mg.improve.attend.repository.entities.JEvents;
import mg.improve.attend.repository.entities.JTicket;
import mg.improve.attend.repository.entities.JTicketTier;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Dashboard aggregation. Everything is computed with a fixed set of set-based queries (no
 * per-event calls) and a single {@code now}, so an inactive user consistently gets zeroed
 * counters instead of an error.
 */
@Service
@RequiredArgsConstructor
public class DashboardService {

    /** An event is upcoming while it has not ended and is not cancelled. */
    private static final List<EventStatus> UPCOMING_STATUSES =
            List.of(EventStatus.OK, EventStatus.POSTPONED);

    /** Statuses that consume capacity and count toward sales. */
    private static final List<TicketStatus> COUNTED_STATUSES =
            List.of(TicketStatus.VALID, TicketStatus.USED);

    private static final Duration NO_TIERS_WINDOW = Duration.ofDays(7);
    private static final int MAX_ATTENTION_ITEMS = 5;
    private static final int LOW_STOCK_MAX_REMAINING = 10;

    private final JEventsRepository events;
    private final JTicketTierRepository tiers;
    private final JTicketRepository tickets;
    private final TicketTierMapper tierMapper;

    @Transactional(readOnly = true)
    public DashboardResponse getDashboard(UUID userId) {
        Instant now = Instant.now();
        return new DashboardResponse(organizing(userId, now), attending(userId, now));
    }

    private OrganizingDashboard organizing(UUID userId, Instant now) {
        JEventsRepository.OrganizingCounters counters =
                events.countOrganizing(userId, now, UPCOMING_STATUSES);
        JTicketRepository.OrganizingSales sales =
                tickets.aggregateOrganizingSales(userId, now, UPCOMING_STATUSES, COUNTED_STATUSES);
        BigDecimal revenue = sales.getRevenue() == null ? BigDecimal.ZERO : sales.getRevenue();

        return new OrganizingDashboard(
                counters.getEventsTotal(),
                counters.getEventsUpcoming(),
                sales.getTicketsSold(),
                revenue,
                nextOrganizingEvent(userId, now),
                attention(userId, now));
    }

    /** The soonest upcoming organized event, with the same counters as the stats endpoint. */
    private NextOrganizingEvent nextOrganizingEvent(UUID userId, Instant now) {
        List<JEventsRepository.NextOrganizingEventRow> rows =
                events.findNextOrganizingEvent(userId, now, UPCOMING_STATUSES, PageRequest.of(0, 1));
        if (rows.isEmpty()) {
            return null;
        }

        JEventsRepository.NextOrganizingEventRow row = rows.get(0);
        JEvents event = row.getEvent();

        int totalCapacity = tiers.findByEvent_Id(event.getId()).stream()
                .mapToInt(JTicketTier::getCapacity)
                .sum();
        List<JTicketRepository.TierAggregate> aggregates =
                tickets.aggregateByTier(event.getId(), COUNTED_STATUSES, TicketStatus.USED);
        long ticketsIssued = aggregates.stream()
                .mapToLong(JTicketRepository.TierAggregate::getIssued)
                .sum();
        long ticketsCheckedIn = aggregates.stream()
                .mapToLong(JTicketRepository.TierAggregate::getCheckedIn)
                .sum();

        return new NextOrganizingEvent(
                event.getId(),
                event.getTitle(),
                event.getStartTime(),
                event.getEndTime(),
                event.getLocation(),
                event.getPriceUnit(),
                event.getStatus(),
                row.getEventRole(),
                totalCapacity,
                ticketsIssued,
                ticketsCheckedIn);
    }

    /**
     * The attention list: candidates from the three rules merged, earliest start first, capped.
     * The secondary sort keys only make ties deterministic.
     */
    private List<AttentionItem> attention(UUID userId, Instant now) {
        List<AttentionItem> items = new ArrayList<>();

        for (JEventsRepository.AttentionEventRow row : events.findNoTierAttention(
                userId, now, now.plus(NO_TIERS_WINDOW), EventStatus.OK)) {
            items.add(AttentionItem.noTiers(row.getEventId(), row.getEventTitle(), row.getStartTime()));
        }

        for (JTicketTierRepository.LowStockTier tier : tiers.findLowStockTiers(
                userId, now, EventStatus.OK, COUNTED_STATUSES)) {
            int remaining = Math.max(0, tier.getCapacity() - (int) tier.getIssued());
            if (remaining > 0 && remaining <= LOW_STOCK_MAX_REMAINING) {
                items.add(AttentionItem.lowStock(tier.getEventId(), tier.getEventTitle(),
                        tier.getStartTime(), tier.getTierId(), tier.getTierName(), remaining));
            }
        }

        for (JEventsRepository.PostponedAttentionRow row : events.findPostponedAttention(
                userId, EventStatus.POSTPONED, COUNTED_STATUSES)) {
            items.add(AttentionItem.postponed(row.getEventId(), row.getEventTitle(),
                    row.getStartTime(), row.getTicketsIssued()));
        }

        return items.stream()
                .sorted(Comparator.comparing(AttentionItem::startTime)
                        .thenComparing(AttentionItem::type)
                        .thenComparing(AttentionItem::eventId))
                .limit(MAX_ATTENTION_ITEMS)
                .toList();
    }

    private AttendingDashboard attending(UUID userId, Instant now) {
        long ticketsUpcoming = tickets.countUpcomingTickets(
                userId, now, UPCOMING_STATUSES, TicketStatus.VALID);
        return new AttendingDashboard(ticketsUpcoming, nextTicket(userId, now));
    }

    /** The valid ticket for the soonest-starting upcoming event, or {@code null}. */
    private NextTicket nextTicket(UUID userId, Instant now) {
        List<JTicket> rows = tickets.findNextUpcomingTicket(
                userId, now, UPCOMING_STATUSES, TicketStatus.VALID, PageRequest.of(0, 1));
        if (rows.isEmpty()) {
            return null;
        }

        JTicket ticket = rows.get(0);
        TicketTier tier = tierMapper.toDomain(ticket.getTier());
        return new NextTicket(
                ticket.getId(),
                TicketEventRef.from(tier.getEvent()),
                TicketTierRef.from(tier),
                ticket.getTicketStatus());
    }
}
