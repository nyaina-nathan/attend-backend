package mg.improve.attend.controller;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import mg.improve.attend.TestcontainersConfiguration;
import mg.improve.attend.domain.dto.response.TicketResponse;
import mg.improve.attend.domain.enums.EventRole;
import mg.improve.attend.domain.enums.EventStatus;
import mg.improve.attend.domain.enums.TicketStatus;
import mg.improve.attend.exceptions.ApiError;
import mg.improve.attend.repository.JEventOrganizerRepository;
import mg.improve.attend.repository.JEventsRepository;
import mg.improve.attend.repository.JTicketRepository;
import mg.improve.attend.repository.JTicketTierRepository;
import mg.improve.attend.repository.entities.JEventOrganizer;
import mg.improve.attend.repository.entities.JEvents;
import mg.improve.attend.repository.entities.JTicket;
import mg.improve.attend.repository.entities.JTicketTier;
import mg.improve.attend.repository.entities.JUsers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class TicketControllerIT extends IntegrationTestSupport {

    @Autowired
    private JEventsRepository events;

    @Autowired
    private JEventOrganizerRepository organizers;

    @Autowired
    private JTicketTierRepository tiers;

    @Autowired
    private JTicketRepository tickets;

    // ------------------------------------------------------------------
    // POST /api/tiers/{tierId}/tickets
    // ------------------------------------------------------------------

    @Test
    void issueTicket_createsTicketForAuthenticatedCaller() {
        JUsers owner = seedUser("owner@example.com", "owner", "secret123");
        JEvents event = seedEvent(owner, EventStatus.OK, Instant.now().plus(Duration.ofDays(1)));
        JTicketTier tier = seedTier(event, "VIP", "40.00", 10);
        HttpResponse<String> registered = register("buyer@example.com", "secret123", "Buyer", "buyer");
        JUsers buyer = users.findByEmail("buyer@example.com").orElseThrow();
        HttpHeaders auth = withCookies(new HttpHeaders(), ACCESS_COOKIE, accessToken(registered));

        HttpResponse<String> response = call(HttpMethod.POST, "/api/tiers/" + tier.getId() + "/tickets", null,
                auth);

        assertThat(response.statusCode()).isEqualTo(HttpStatus.CREATED.value());
        TicketResponse body = readBody(response, TicketResponse.class);
        assertThat(body.id()).isNotNull();
        assertThat(body.event().id()).isEqualTo(event.getId());
        assertThat(body.tier().id()).isEqualTo(tier.getId());
        assertThat(body.tier().tierName()).isEqualTo("VIP");
        assertThat(body.pricePaid()).isEqualByComparingTo("40.00");
        assertThat(body.ticketStatus()).isEqualTo(TicketStatus.VALID);
        assertThat(body.checkedInAt()).isNull();
        assertThat(body.validUntil()).isEqualTo(event.getEndTime());
        assertThat(body.createdAt()).isNotNull();

        JTicket stored = tickets.findById(body.id()).orElseThrow();
        assertThat(stored.getOwner().getId()).isEqualTo(buyer.getId());
        assertThat(stored.getTicketStatus()).isEqualTo(TicketStatus.VALID);
        assertThat(stored.getPricePaid()).isEqualByComparingTo("40.00");
    }

    @Test
    void issueTicket_requiresAuthentication() {
        JUsers owner = seedUser("owner@example.com", "owner", "secret123");
        JEvents event = seedEvent(owner, EventStatus.OK, Instant.now().plus(Duration.ofDays(1)));
        JTicketTier tier = seedTier(event, "VIP", "40.00", 10);

        HttpResponse<String> response = call(HttpMethod.POST, "/api/tiers/" + tier.getId() + "/tickets", null,
                new HttpHeaders());

        assertThat(response.statusCode()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
    }

    @Test
    void issueTicket_returnsNotFoundForUnknownTier() {
        HttpResponse<String> registered = register("buyer@example.com", "secret123", "Buyer", "buyer");
        HttpHeaders auth = withCookies(new HttpHeaders(), ACCESS_COOKIE, accessToken(registered));

        HttpResponse<String> response = call(HttpMethod.POST, "/api/tiers/" + UUID.randomUUID() + "/tickets", null,
                auth);

        assertThat(response.statusCode()).isEqualTo(HttpStatus.NOT_FOUND.value());
        assertThat(readBody(response, ApiError.class).error()).isEqualTo("TIER_NOT_FOUND");
    }

    @Test
    void issueTicket_rejectsWhenSoldOut() {
        JUsers owner = seedUser("owner@example.com", "owner", "secret123");
        JEvents event = seedEvent(owner, EventStatus.OK, Instant.now().plus(Duration.ofDays(1)));
        JTicketTier tier = seedTier(event, "VIP", "40.00", 1);
        seedTicket(owner, tier, TicketStatus.VALID);
        HttpResponse<String> registered = register("buyer@example.com", "secret123", "Buyer", "buyer");
        HttpHeaders auth = withCookies(new HttpHeaders(), ACCESS_COOKIE, accessToken(registered));

        HttpResponse<String> response = call(HttpMethod.POST, "/api/tiers/" + tier.getId() + "/tickets", null,
                auth);

        assertThat(response.statusCode()).isEqualTo(HttpStatus.CONFLICT.value());
        assertThat(readBody(response, ApiError.class).error()).isEqualTo("SOLD_OUT");
        assertThat(tickets.countByTier_IdAndTicketStatusIn(tier.getId(),
                java.util.List.of(TicketStatus.VALID, TicketStatus.USED))).isEqualTo(1);
    }

    @Test
    void issueTicket_countsCancelledTicketsAsFreeCapacity() {
        JUsers owner = seedUser("owner@example.com", "owner", "secret123");
        JEvents event = seedEvent(owner, EventStatus.OK, Instant.now().plus(Duration.ofDays(1)));
        JTicketTier tier = seedTier(event, "VIP", "40.00", 1);
        seedTicket(owner, tier, TicketStatus.CANCELLED);
        HttpResponse<String> registered = register("buyer@example.com", "secret123", "Buyer", "buyer");
        HttpHeaders auth = withCookies(new HttpHeaders(), ACCESS_COOKIE, accessToken(registered));

        HttpResponse<String> response = call(HttpMethod.POST, "/api/tiers/" + tier.getId() + "/tickets", null,
                auth);

        assertThat(response.statusCode()).isEqualTo(HttpStatus.CREATED.value());
    }

    @Test
    void issueTicket_rejectsCancelledEvent() {
        JUsers owner = seedUser("owner@example.com", "owner", "secret123");
        JEvents event = seedEvent(owner, EventStatus.CANCELLED, Instant.now().plus(Duration.ofDays(1)));
        JTicketTier tier = seedTier(event, "VIP", "40.00", 10);
        HttpResponse<String> registered = register("buyer@example.com", "secret123", "Buyer", "buyer");
        HttpHeaders auth = withCookies(new HttpHeaders(), ACCESS_COOKIE, accessToken(registered));

        HttpResponse<String> response = call(HttpMethod.POST, "/api/tiers/" + tier.getId() + "/tickets", null,
                auth);

        assertThat(response.statusCode()).isEqualTo(HttpStatus.CONFLICT.value());
        assertThat(readBody(response, ApiError.class).error()).isEqualTo("EVENT_NOT_OPEN");
    }

    @Test
    void issueTicket_rejectsEndedEvent() {
        JUsers owner = seedUser("owner@example.com", "owner", "secret123");
        JEvents event = seedEvent(owner, EventStatus.OK, Instant.now().minus(Duration.ofHours(1)));
        JTicketTier tier = seedTier(event, "VIP", "40.00", 10);
        HttpResponse<String> registered = register("buyer@example.com", "secret123", "Buyer", "buyer");
        HttpHeaders auth = withCookies(new HttpHeaders(), ACCESS_COOKIE, accessToken(registered));

        HttpResponse<String> response = call(HttpMethod.POST, "/api/tiers/" + tier.getId() + "/tickets", null,
                auth);

        assertThat(response.statusCode()).isEqualTo(HttpStatus.CONFLICT.value());
        assertThat(readBody(response, ApiError.class).error()).isEqualTo("EVENT_NOT_OPEN");
    }

    // ------------------------------------------------------------------
    // fixtures
    // ------------------------------------------------------------------

    private JEvents seedEvent(JUsers owner, EventStatus status, Instant endTime) {
        // Postgres stores timestamptz at microsecond precision, so normalize to whole seconds
        // to keep the value read back from the DB equal to the one asserted in the tests.
        Instant normalizedEnd = endTime.truncatedTo(ChronoUnit.SECONDS);
        JEvents event = new JEvents();
        event.setTitle("Event");
        event.setStartTime(normalizedEnd.minus(Duration.ofHours(4)));
        event.setEndTime(normalizedEnd);
        event.setPublic(true);
        event.setStatus(status);
        event = events.saveAndFlush(event);
        seedOrganizer(owner, event, EventRole.CREATOR);
        return event;
    }

    private void seedOrganizer(JUsers user, JEvents event, EventRole role) {
        JEventOrganizer organizer = new JEventOrganizer();
        organizer.setUser(user);
        organizer.setEvent(event);
        organizer.setEventRole(role);
        organizers.saveAndFlush(organizer);
    }

    private JTicketTier seedTier(JEvents event, String name, String price, int capacity) {
        JTicketTier tier = new JTicketTier();
        tier.setEvent(event);
        tier.setTierName(name);
        tier.setPrice(new BigDecimal(price));
        tier.setCapacity(capacity);
        return tiers.saveAndFlush(tier);
    }

    private void seedTicket(JUsers owner, JTicketTier tier, TicketStatus status) {
        JTicket ticket = new JTicket();
        ticket.setOwner(owner);
        ticket.setTier(tier);
        ticket.setPricePaid(tier.getPrice());
        ticket.setTicketStatus(status);
        tickets.saveAndFlush(ticket);
    }
}
