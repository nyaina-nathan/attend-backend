package mg.improve.attend.controller;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import mg.improve.attend.TestcontainersConfiguration;
import mg.improve.attend.domain.dto.request.TierCreateRequest;
import mg.improve.attend.domain.dto.request.TierUpdateRequest;
import mg.improve.attend.domain.dto.response.Tier;
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
class TicketTierControllerIT extends IntegrationTestSupport {

    private static final Instant START = Instant.parse("2026-11-20T18:00:00Z");

    @Autowired
    private JEventsRepository events;

    @Autowired
    private JEventOrganizerRepository organizers;

    @Autowired
    private JTicketTierRepository tiers;

    @Autowired
    private JTicketRepository tickets;

    // ------------------------------------------------------------------
    // GET /api/events/{eventId}/tiers
    // ------------------------------------------------------------------

    @Test
    void listTiers_returnsTiersForPublicEventToAnonymous() {
        JUsers owner = seedUser("owner@example.com", "owner", "secret123");
        JEvents event = seedEvent(owner, "Public Event", true);
        JTicketTier tier = seedTier(event, "VIP", "40.00", 10);
        seedTicket(owner, tier, TicketStatus.VALID);
        seedTicket(owner, tier, TicketStatus.VALID);
        seedTicket(owner, tier, TicketStatus.CANCELLED);

        HttpResponse<String> response = call(HttpMethod.GET, "/api/events/" + event.getId() + "/tiers", null,
                new HttpHeaders());

        assertThat(response.statusCode()).isEqualTo(HttpStatus.OK.value());
        List<Tier> body = List.of(readBody(response, Tier[].class));
        assertThat(body).hasSize(1);
        assertThat(body.get(0).id()).isEqualTo(tier.getId());
        assertThat(body.get(0).eventId()).isEqualTo(event.getId());
        assertThat(body.get(0).tierName()).isEqualTo("VIP");
        assertThat(body.get(0).remaining()).isEqualTo(8);
    }

    @Test
    void listTiers_allowsOrganizerOnPrivateEvent() {
        HttpResponse<String> registered = register("owner@example.com", "secret123", "Owner", "owner");
        JUsers owner = users.findByEmail("owner@example.com").orElseThrow();
        HttpHeaders auth = withCookies(new HttpHeaders(), ACCESS_COOKIE, accessToken(registered));
        JEvents event = seedEvent(owner, "Private Event", false);
        seedTier(event, "VIP", "40.00", 10);

        HttpResponse<String> response = call(HttpMethod.GET, "/api/events/" + event.getId() + "/tiers", null, auth);

        assertThat(response.statusCode()).isEqualTo(HttpStatus.OK.value());
        assertThat(List.of(readBody(response, Tier[].class))).hasSize(1);
    }

    @Test
    void listTiers_rejectsAnonymousOnPrivateEvent() {
        JUsers owner = seedUser("owner@example.com", "owner", "secret123");
        JEvents event = seedEvent(owner, "Private Event", false);

        HttpResponse<String> response = call(HttpMethod.GET, "/api/events/" + event.getId() + "/tiers", null,
                new HttpHeaders());

        assertThat(response.statusCode()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
    }

    @Test
    void listTiers_rejectsNonOrganizerOnPrivateEvent() {
        JUsers owner = seedUser("owner@example.com", "owner", "secret123");
        JEvents event = seedEvent(owner, "Private Event", false);
        HttpResponse<String> registered = register("other@example.com", "secret123", "Other", "other");
        HttpHeaders auth = withCookies(new HttpHeaders(), ACCESS_COOKIE, accessToken(registered));

        HttpResponse<String> response = call(HttpMethod.GET, "/api/events/" + event.getId() + "/tiers", null, auth);

        assertThat(response.statusCode()).isEqualTo(HttpStatus.FORBIDDEN.value());
        assertThat(readBody(response, ApiError.class).error()).isEqualTo("NOT_AN_ORGANIZER");
    }

    @Test
    void listTiers_returnsNotFoundForUnknownEvent() {
        HttpResponse<String> response = call(HttpMethod.GET, "/api/events/" + UUID.randomUUID() + "/tiers", null,
                new HttpHeaders());

        assertThat(response.statusCode()).isEqualTo(HttpStatus.NOT_FOUND.value());
        assertThat(readBody(response, ApiError.class).error()).isEqualTo("EVENT_NOT_FOUND");
    }

    // ------------------------------------------------------------------
    // POST /api/events/{eventId}/tiers
    // ------------------------------------------------------------------

    @Test
    void createTier_createsForCreator() {
        HttpResponse<String> registered = register("owner@example.com", "secret123", "Owner", "owner");
        JUsers owner = users.findByEmail("owner@example.com").orElseThrow();
        HttpHeaders auth = withCookies(new HttpHeaders(), ACCESS_COOKIE, accessToken(registered));
        JEvents event = seedEvent(owner, "Event", true);

        HttpResponse<String> response = call(HttpMethod.POST, "/api/events/" + event.getId() + "/tiers",
                new TierCreateRequest("VIP", new BigDecimal("40.00"), 50), auth);

        assertThat(response.statusCode()).isEqualTo(HttpStatus.CREATED.value());
        Tier body = readBody(response, Tier.class);
        assertThat(body.tierName()).isEqualTo("VIP");
        assertThat(body.eventId()).isEqualTo(event.getId());
        assertThat(body.capacity()).isEqualTo(50);
        assertThat(body.remaining()).isEqualTo(50);
        assertThat(tiers.findByEvent_Id(event.getId())).hasSize(1);
    }

    @Test
    void createTier_allowsAdminOrganizer() {
        JUsers owner = seedUser("owner@example.com", "owner", "secret123");
        JEvents event = seedEvent(owner, "Event", true);
        HttpResponse<String> registered = register("admin@example.com", "secret123", "Admin", "admin");
        JUsers admin = users.findByEmail("admin@example.com").orElseThrow();
        seedOrganizer(admin, event, EventRole.ADMIN);
        HttpHeaders auth = withCookies(new HttpHeaders(), ACCESS_COOKIE, accessToken(registered));

        HttpResponse<String> response = call(HttpMethod.POST, "/api/events/" + event.getId() + "/tiers",
                new TierCreateRequest("VIP", new BigDecimal("40.00"), 50), auth);

        assertThat(response.statusCode()).isEqualTo(HttpStatus.CREATED.value());
    }

    @Test
    void createTier_rejectsNonOrganizer() {
        JUsers owner = seedUser("owner@example.com", "owner", "secret123");
        JEvents event = seedEvent(owner, "Event", true);
        HttpResponse<String> registered = register("other@example.com", "secret123", "Other", "other");
        HttpHeaders auth = withCookies(new HttpHeaders(), ACCESS_COOKIE, accessToken(registered));

        HttpResponse<String> response = call(HttpMethod.POST, "/api/events/" + event.getId() + "/tiers",
                new TierCreateRequest("VIP", new BigDecimal("40.00"), 50), auth);

        assertThat(response.statusCode()).isEqualTo(HttpStatus.FORBIDDEN.value());
        assertThat(readBody(response, ApiError.class).error()).isEqualTo("NOT_AN_ORGANIZER");
    }

    @Test
    void createTier_rejectsUnknownEvent() {
        HttpResponse<String> registered = register("owner@example.com", "secret123", "Owner", "owner");
        HttpHeaders auth = withCookies(new HttpHeaders(), ACCESS_COOKIE, accessToken(registered));

        HttpResponse<String> response = call(HttpMethod.POST, "/api/events/" + UUID.randomUUID() + "/tiers",
                new TierCreateRequest("VIP", new BigDecimal("40.00"), 50), auth);

        assertThat(response.statusCode()).isEqualTo(HttpStatus.NOT_FOUND.value());
        assertThat(readBody(response, ApiError.class).error()).isEqualTo("EVENT_NOT_FOUND");
    }

    @Test
    void createTier_rejectsDuplicateName() {
        HttpResponse<String> registered = register("owner@example.com", "secret123", "Owner", "owner");
        JUsers owner = users.findByEmail("owner@example.com").orElseThrow();
        HttpHeaders auth = withCookies(new HttpHeaders(), ACCESS_COOKIE, accessToken(registered));
        JEvents event = seedEvent(owner, "Event", true);
        seedTier(event, "VIP", "40.00", 10);

        HttpResponse<String> response = call(HttpMethod.POST, "/api/events/" + event.getId() + "/tiers",
                new TierCreateRequest("VIP", new BigDecimal("50.00"), 5), auth);

        assertThat(response.statusCode()).isEqualTo(HttpStatus.CONFLICT.value());
        assertThat(readBody(response, ApiError.class).error()).isEqualTo("TIER_NAME_TAKEN");
    }

    @Test
    void createTier_rejectsInvalidBody() {
        HttpResponse<String> registered = register("owner@example.com", "secret123", "Owner", "owner");
        JUsers owner = users.findByEmail("owner@example.com").orElseThrow();
        HttpHeaders auth = withCookies(new HttpHeaders(), ACCESS_COOKIE, accessToken(registered));
        JEvents event = seedEvent(owner, "Event", true);

        HttpResponse<String> response = call(HttpMethod.POST, "/api/events/" + event.getId() + "/tiers",
                new TierCreateRequest("VIP", new BigDecimal("-1.00"), -5), auth);

        assertThat(response.statusCode()).isEqualTo(HttpStatus.BAD_REQUEST.value());
        assertThat(readBody(response, ApiError.class).error()).isEqualTo("VALIDATION_ERROR");
    }

    @Test
    void createTier_requiresAuthentication() {
        JUsers owner = seedUser("owner@example.com", "owner", "secret123");
        JEvents event = seedEvent(owner, "Event", true);

        HttpResponse<String> response = call(HttpMethod.POST, "/api/events/" + event.getId() + "/tiers",
                new TierCreateRequest("VIP", new BigDecimal("40.00"), 50), new HttpHeaders());

        assertThat(response.statusCode()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
    }

    // ------------------------------------------------------------------
    // PATCH /api/tiers/{tierId}
    // ------------------------------------------------------------------

    @Test
    void updateTier_updatesFieldsAndRemaining() {
        HttpResponse<String> registered = register("owner@example.com", "secret123", "Owner", "owner");
        JUsers owner = users.findByEmail("owner@example.com").orElseThrow();
        HttpHeaders auth = withCookies(new HttpHeaders(), ACCESS_COOKIE, accessToken(registered));
        JEvents event = seedEvent(owner, "Event", true);
        JTicketTier tier = seedTier(event, "VIP", "40.00", 10);
        seedTicket(owner, tier, TicketStatus.VALID);
        seedTicket(owner, tier, TicketStatus.USED);

        HttpResponse<String> response = call(HttpMethod.PATCH, "/api/tiers/" + tier.getId(),
                new TierUpdateRequest("VIP Plus", new BigDecimal("45.00"), 5), auth);

        assertThat(response.statusCode()).isEqualTo(HttpStatus.OK.value());
        Tier body = readBody(response, Tier.class);
        assertThat(body.tierName()).isEqualTo("VIP Plus");
        assertThat(body.price()).isEqualByComparingTo("45.00");
        assertThat(body.capacity()).isEqualTo(5);
        assertThat(body.remaining()).isEqualTo(3);
    }

    @Test
    void updateTier_rejectsCapacityBelowIssued() {
        HttpResponse<String> registered = register("owner@example.com", "secret123", "Owner", "owner");
        JUsers owner = users.findByEmail("owner@example.com").orElseThrow();
        HttpHeaders auth = withCookies(new HttpHeaders(), ACCESS_COOKIE, accessToken(registered));
        JEvents event = seedEvent(owner, "Event", true);
        JTicketTier tier = seedTier(event, "VIP", "40.00", 10);
        seedTicket(owner, tier, TicketStatus.VALID);
        seedTicket(owner, tier, TicketStatus.VALID);
        seedTicket(owner, tier, TicketStatus.USED);

        HttpResponse<String> response = call(HttpMethod.PATCH, "/api/tiers/" + tier.getId(),
                new TierUpdateRequest(null, null, 2), auth);

        assertThat(response.statusCode()).isEqualTo(HttpStatus.CONFLICT.value());
        assertThat(readBody(response, ApiError.class).error()).isEqualTo("CAPACITY_BELOW_ISSUED");
    }

    @Test
    void updateTier_rejectsDuplicateName() {
        HttpResponse<String> registered = register("owner@example.com", "secret123", "Owner", "owner");
        JUsers owner = users.findByEmail("owner@example.com").orElseThrow();
        HttpHeaders auth = withCookies(new HttpHeaders(), ACCESS_COOKIE, accessToken(registered));
        JEvents event = seedEvent(owner, "Event", true);
        JTicketTier vip = seedTier(event, "VIP", "40.00", 10);
        seedTier(event, "Standard", "10.00", 100);

        HttpResponse<String> response = call(HttpMethod.PATCH, "/api/tiers/" + vip.getId(),
                new TierUpdateRequest("Standard", null, null), auth);

        assertThat(response.statusCode()).isEqualTo(HttpStatus.CONFLICT.value());
        assertThat(readBody(response, ApiError.class).error()).isEqualTo("TIER_NAME_TAKEN");
    }

    @Test
    void updateTier_rejectsNonOrganizer() {
        JUsers owner = seedUser("owner@example.com", "owner", "secret123");
        JEvents event = seedEvent(owner, "Event", true);
        JTicketTier tier = seedTier(event, "VIP", "40.00", 10);
        HttpResponse<String> registered = register("other@example.com", "secret123", "Other", "other");
        HttpHeaders auth = withCookies(new HttpHeaders(), ACCESS_COOKIE, accessToken(registered));

        HttpResponse<String> response = call(HttpMethod.PATCH, "/api/tiers/" + tier.getId(),
                new TierUpdateRequest("VIP Plus", null, null), auth);

        assertThat(response.statusCode()).isEqualTo(HttpStatus.FORBIDDEN.value());
        assertThat(readBody(response, ApiError.class).error()).isEqualTo("NOT_AN_ORGANIZER");
    }

    @Test
    void updateTier_returnsNotFoundForUnknownTier() {
        HttpResponse<String> registered = register("owner@example.com", "secret123", "Owner", "owner");
        HttpHeaders auth = withCookies(new HttpHeaders(), ACCESS_COOKIE, accessToken(registered));

        HttpResponse<String> response = call(HttpMethod.PATCH, "/api/tiers/" + UUID.randomUUID(),
                new TierUpdateRequest("VIP Plus", null, null), auth);

        assertThat(response.statusCode()).isEqualTo(HttpStatus.NOT_FOUND.value());
        assertThat(readBody(response, ApiError.class).error()).isEqualTo("TIER_NOT_FOUND");
    }

    @Test
    void updateTier_requiresAuthentication() {
        JUsers owner = seedUser("owner@example.com", "owner", "secret123");
        JEvents event = seedEvent(owner, "Event", true);
        JTicketTier tier = seedTier(event, "VIP", "40.00", 10);

        HttpResponse<String> response = call(HttpMethod.PATCH, "/api/tiers/" + tier.getId(),
                new TierUpdateRequest("VIP Plus", null, null), new HttpHeaders());

        assertThat(response.statusCode()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
    }

    // ------------------------------------------------------------------
    // DELETE /api/tiers/{tierId}
    // ------------------------------------------------------------------

    @Test
    void deleteTier_deletesWhenNoTickets() {
        HttpResponse<String> registered = register("owner@example.com", "secret123", "Owner", "owner");
        JUsers owner = users.findByEmail("owner@example.com").orElseThrow();
        HttpHeaders auth = withCookies(new HttpHeaders(), ACCESS_COOKIE, accessToken(registered));
        JEvents event = seedEvent(owner, "Event", true);
        JTicketTier tier = seedTier(event, "VIP", "40.00", 10);

        HttpResponse<String> response = call(HttpMethod.DELETE, "/api/tiers/" + tier.getId(), null, auth);

        assertThat(response.statusCode()).isEqualTo(HttpStatus.NO_CONTENT.value());
        assertThat(tiers.findById(tier.getId())).isEmpty();
    }

    @Test
    void deleteTier_rejectsWhenTicketsExist() {
        HttpResponse<String> registered = register("owner@example.com", "secret123", "Owner", "owner");
        JUsers owner = users.findByEmail("owner@example.com").orElseThrow();
        HttpHeaders auth = withCookies(new HttpHeaders(), ACCESS_COOKIE, accessToken(registered));
        JEvents event = seedEvent(owner, "Event", true);
        JTicketTier tier = seedTier(event, "VIP", "40.00", 10);
        seedTicket(owner, tier, TicketStatus.VALID);

        HttpResponse<String> response = call(HttpMethod.DELETE, "/api/tiers/" + tier.getId(), null, auth);

        assertThat(response.statusCode()).isEqualTo(HttpStatus.CONFLICT.value());
        assertThat(readBody(response, ApiError.class).error()).isEqualTo("TIER_HAS_TICKETS");
        assertThat(tiers.findById(tier.getId())).isPresent();
    }

    @Test
    void deleteTier_rejectsNonOrganizer() {
        JUsers owner = seedUser("owner@example.com", "owner", "secret123");
        JEvents event = seedEvent(owner, "Event", true);
        JTicketTier tier = seedTier(event, "VIP", "40.00", 10);
        HttpResponse<String> registered = register("other@example.com", "secret123", "Other", "other");
        HttpHeaders auth = withCookies(new HttpHeaders(), ACCESS_COOKIE, accessToken(registered));

        HttpResponse<String> response = call(HttpMethod.DELETE, "/api/tiers/" + tier.getId(), null, auth);

        assertThat(response.statusCode()).isEqualTo(HttpStatus.FORBIDDEN.value());
        assertThat(readBody(response, ApiError.class).error()).isEqualTo("NOT_AN_ORGANIZER");
    }

    @Test
    void deleteTier_returnsNotFoundForUnknownTier() {
        HttpResponse<String> registered = register("owner@example.com", "secret123", "Owner", "owner");
        HttpHeaders auth = withCookies(new HttpHeaders(), ACCESS_COOKIE, accessToken(registered));

        HttpResponse<String> response = call(HttpMethod.DELETE, "/api/tiers/" + UUID.randomUUID(), null, auth);

        assertThat(response.statusCode()).isEqualTo(HttpStatus.NOT_FOUND.value());
        assertThat(readBody(response, ApiError.class).error()).isEqualTo("TIER_NOT_FOUND");
    }

    @Test
    void deleteTier_requiresAuthentication() {
        JUsers owner = seedUser("owner@example.com", "owner", "secret123");
        JEvents event = seedEvent(owner, "Event", true);
        JTicketTier tier = seedTier(event, "VIP", "40.00", 10);

        HttpResponse<String> response = call(HttpMethod.DELETE, "/api/tiers/" + tier.getId(), null,
                new HttpHeaders());

        assertThat(response.statusCode()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
    }

    // ------------------------------------------------------------------
    // fixtures
    // ------------------------------------------------------------------

    private JEvents seedEvent(JUsers owner, String title, boolean isPublic) {
        JEvents event = new JEvents();
        event.setTitle(title);
        event.setStartTime(START);
        event.setEndTime(START.plus(Duration.ofHours(4)));
        event.setPublic(isPublic);
        event.setStatus(EventStatus.OK);
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
