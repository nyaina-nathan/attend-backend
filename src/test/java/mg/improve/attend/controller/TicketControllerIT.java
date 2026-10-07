package mg.improve.attend.controller;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import mg.improve.attend.TestcontainersConfiguration;
import mg.improve.attend.domain.dto.request.RefundRequest;
import mg.improve.attend.domain.dto.response.CheckInResponse;
import mg.improve.attend.domain.dto.response.PageTicket;
import mg.improve.attend.domain.dto.response.TicketDetail;
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
    // GET /api/tickets/me
    // ------------------------------------------------------------------

    @Test
    void listMyTickets_returnsOnlyCallerTickets() {
        JUsers owner = seedUser("owner@example.com", "owner", "secret123");
        JEvents event = seedEvent(owner, EventStatus.OK, Instant.now().plus(Duration.ofDays(1)));
        JTicketTier tier = seedTier(event, "VIP", "40.00", 10);

        HttpResponse<String> buyerReg = register("buyer@example.com", "secret123", "Buyer", "buyer");
        JUsers buyer = users.findByEmail("buyer@example.com").orElseThrow();
        JUsers other = seedUser("other@example.com", "other", "secret123");
        JTicket mine = seedTicket(buyer, tier, TicketStatus.VALID);
        seedTicket(other, tier, TicketStatus.VALID);
        HttpHeaders auth = withCookies(new HttpHeaders(), ACCESS_COOKIE, accessToken(buyerReg));

        HttpResponse<String> response = call(HttpMethod.GET, "/api/tickets/me", null, auth);

        assertThat(response.statusCode()).isEqualTo(HttpStatus.OK.value());
        PageTicket body = readBody(response, PageTicket.class);
        assertThat(body.page()).isZero();
        assertThat(body.size()).isEqualTo(20);
        assertThat(body.totalElements()).isEqualTo(1);
        assertThat(body.totalPages()).isEqualTo(1);
        assertThat(body.content()).hasSize(1);
        assertThat(body.content().get(0).id()).isEqualTo(mine.getId());
        assertThat(body.content().get(0).event().id()).isEqualTo(event.getId());
        assertThat(body.content().get(0).tier().tierName()).isEqualTo("VIP");
    }

    @Test
    void listMyTickets_filtersByStatus() {
        JUsers owner = seedUser("owner@example.com", "owner", "secret123");
        JEvents event = seedEvent(owner, EventStatus.OK, Instant.now().plus(Duration.ofDays(1)));
        JTicketTier tier = seedTier(event, "VIP", "40.00", 10);
        HttpResponse<String> buyerReg = register("buyer@example.com", "secret123", "Buyer", "buyer");
        JUsers buyer = users.findByEmail("buyer@example.com").orElseThrow();
        JTicket cancelled = seedTicket(buyer, tier, TicketStatus.CANCELLED);
        seedTicket(buyer, tier, TicketStatus.VALID);
        HttpHeaders auth = withCookies(new HttpHeaders(), ACCESS_COOKIE, accessToken(buyerReg));

        HttpResponse<String> response = call(HttpMethod.GET, "/api/tickets/me?status=CANCELLED", null, auth);

        assertThat(response.statusCode()).isEqualTo(HttpStatus.OK.value());
        PageTicket body = readBody(response, PageTicket.class);
        assertThat(body.content()).hasSize(1);
        assertThat(body.content().get(0).id()).isEqualTo(cancelled.getId());
        assertThat(body.content().get(0).ticketStatus()).isEqualTo(TicketStatus.CANCELLED);
    }

    @Test
    void listMyTickets_filtersUpcoming() {
        JUsers owner = seedUser("owner@example.com", "owner", "secret123");
        JEvents upcomingEvent = seedEvent(owner, EventStatus.OK, Instant.now().plus(Duration.ofDays(1)));
        JEvents pastEvent = seedEvent(owner, EventStatus.OK, Instant.now().minus(Duration.ofHours(1)));
        JTicketTier upcomingTier = seedTier(upcomingEvent, "VIP", "40.00", 10);
        JTicketTier pastTier = seedTier(pastEvent, "VIP", "40.00", 10);
        HttpResponse<String> buyerReg = register("buyer@example.com", "secret123", "Buyer", "buyer");
        JUsers buyer = users.findByEmail("buyer@example.com").orElseThrow();
        JTicket upcoming = seedTicket(buyer, upcomingTier, TicketStatus.VALID);
        seedTicket(buyer, pastTier, TicketStatus.VALID);
        HttpHeaders auth = withCookies(new HttpHeaders(), ACCESS_COOKIE, accessToken(buyerReg));

        HttpResponse<String> response = call(HttpMethod.GET, "/api/tickets/me?upcoming=true", null, auth);

        assertThat(response.statusCode()).isEqualTo(HttpStatus.OK.value());
        PageTicket body = readBody(response, PageTicket.class);
        assertThat(body.content()).hasSize(1);
        assertThat(body.content().get(0).id()).isEqualTo(upcoming.getId());
    }

    @Test
    void listMyTickets_ordersByEventStartTimeAscending() {
        JUsers owner = seedUser("owner@example.com", "owner", "secret123");
        JEvents laterEvent = seedEvent(owner, EventStatus.OK, Instant.now().plus(Duration.ofDays(10)));
        JEvents soonerEvent = seedEvent(owner, EventStatus.OK, Instant.now().plus(Duration.ofDays(2)));
        JTicketTier laterTier = seedTier(laterEvent, "VIP", "40.00", 10);
        JTicketTier soonerTier = seedTier(soonerEvent, "VIP", "40.00", 10);
        HttpResponse<String> buyerReg = register("buyer@example.com", "secret123", "Buyer", "buyer");
        JUsers buyer = users.findByEmail("buyer@example.com").orElseThrow();
        JTicket later = seedTicket(buyer, laterTier, TicketStatus.VALID);
        JTicket sooner = seedTicket(buyer, soonerTier, TicketStatus.VALID);
        HttpHeaders auth = withCookies(new HttpHeaders(), ACCESS_COOKIE, accessToken(buyerReg));

        HttpResponse<String> response = call(HttpMethod.GET, "/api/tickets/me", null, auth);

        assertThat(response.statusCode()).isEqualTo(HttpStatus.OK.value());
        PageTicket body = readBody(response, PageTicket.class);
        assertThat(body.content()).extracting(TicketResponse::id)
                .containsExactly(sooner.getId(), later.getId());
    }

    @Test
    void listMyTickets_paginates() {
        JUsers owner = seedUser("owner@example.com", "owner", "secret123");
        JEvents laterEvent = seedEvent(owner, EventStatus.OK, Instant.now().plus(Duration.ofDays(10)));
        JEvents soonerEvent = seedEvent(owner, EventStatus.OK, Instant.now().plus(Duration.ofDays(2)));
        JTicketTier laterTier = seedTier(laterEvent, "VIP", "40.00", 10);
        JTicketTier soonerTier = seedTier(soonerEvent, "VIP", "40.00", 10);
        HttpResponse<String> buyerReg = register("buyer@example.com", "secret123", "Buyer", "buyer");
        JUsers buyer = users.findByEmail("buyer@example.com").orElseThrow();
        JTicket sooner = seedTicket(buyer, soonerTier, TicketStatus.VALID);
        seedTicket(buyer, laterTier, TicketStatus.VALID);
        HttpHeaders auth = withCookies(new HttpHeaders(), ACCESS_COOKIE, accessToken(buyerReg));

        HttpResponse<String> response = call(HttpMethod.GET, "/api/tickets/me?page=0&size=1", null, auth);

        assertThat(response.statusCode()).isEqualTo(HttpStatus.OK.value());
        PageTicket body = readBody(response, PageTicket.class);
        assertThat(body.page()).isZero();
        assertThat(body.size()).isEqualTo(1);
        assertThat(body.totalElements()).isEqualTo(2);
        assertThat(body.totalPages()).isEqualTo(2);
        assertThat(body.content()).extracting(TicketResponse::id).containsExactly(sooner.getId());
    }

    @Test
    void listMyTickets_requiresAuthentication() {
        HttpResponse<String> response = call(HttpMethod.GET, "/api/tickets/me", null, new HttpHeaders());

        assertThat(response.statusCode()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
    }

    @Test
    void listMyTickets_rejectsInvalidPagination() {
        HttpResponse<String> buyerReg = register("buyer@example.com", "secret123", "Buyer", "buyer");
        HttpHeaders auth = withCookies(new HttpHeaders(), ACCESS_COOKIE, accessToken(buyerReg));

        HttpResponse<String> negativePage = call(HttpMethod.GET, "/api/tickets/me?page=-1", null, auth);
        HttpResponse<String> zeroSize = call(HttpMethod.GET, "/api/tickets/me?size=0", null, auth);

        assertThat(negativePage.statusCode()).isEqualTo(HttpStatus.BAD_REQUEST.value());
        assertThat(readBody(negativePage, ApiError.class).error()).isEqualTo("VALIDATION_ERROR");
        assertThat(zeroSize.statusCode()).isEqualTo(HttpStatus.BAD_REQUEST.value());
        assertThat(readBody(zeroSize, ApiError.class).error()).isEqualTo("VALIDATION_ERROR");
    }

    // ------------------------------------------------------------------
    // GET /api/tickets/{ticketId}
    // ------------------------------------------------------------------

    @Test
    void getTicket_returnsBaseDetailForOwner() {
        HttpResponse<String> ownerReg = register("owner@example.com", "secret123", "Owner", "owner");
        JUsers owner = users.findByEmail("owner@example.com").orElseThrow();
        // A separate creator so the ticket owner is not also an organizer of the event.
        JUsers creator = seedUser("creator@example.com", "creator", "secret123");
        JEvents event = seedEvent(creator, EventStatus.OK, Instant.now().plus(Duration.ofDays(1)));
        JTicketTier tier = seedTier(event, "VIP", "40.00", 10);
        JTicket ticket = seedTicket(owner, tier, TicketStatus.VALID);
        HttpHeaders auth = withCookies(new HttpHeaders(), ACCESS_COOKIE, accessToken(ownerReg));

        HttpResponse<String> response = call(HttpMethod.GET, "/api/tickets/" + ticket.getId(), null, auth);

        assertThat(response.statusCode()).isEqualTo(HttpStatus.OK.value());
        TicketDetail body = readBody(response, TicketDetail.class);
        assertThat(body.id()).isEqualTo(ticket.getId());
        assertThat(body.event().id()).isEqualTo(event.getId());
        assertThat(body.tier().tierName()).isEqualTo("VIP");
        assertThat(body.pricePaid()).isEqualByComparingTo("40.00");
        assertThat(body.owner()).isNull();
        assertThat(body.checkedBy()).isNull();
    }

    @Test
    void getTicket_includesOwnerForOrganizer() {
        HttpResponse<String> ownerReg = register("owner@example.com", "secret123", "Owner", "owner");
        JUsers owner = users.findByEmail("owner@example.com").orElseThrow();
        JEvents event = seedEvent(owner, EventStatus.OK, Instant.now().plus(Duration.ofDays(1)));
        JTicketTier tier = seedTier(event, "VIP", "40.00", 10);
        JTicket ticket = seedTicket(owner, tier, TicketStatus.VALID);

        HttpResponse<String> adminReg = register("admin@example.com", "secret123", "Admin", "admin");
        JUsers admin = users.findByEmail("admin@example.com").orElseThrow();
        seedOrganizer(admin, event, EventRole.ADMIN);
        HttpHeaders auth = withCookies(new HttpHeaders(), ACCESS_COOKIE, accessToken(adminReg));

        HttpResponse<String> response = call(HttpMethod.GET, "/api/tickets/" + ticket.getId(), null, auth);

        assertThat(response.statusCode()).isEqualTo(HttpStatus.OK.value());
        TicketDetail body = readBody(response, TicketDetail.class);
        assertThat(body.owner()).isNotNull();
        assertThat(body.owner().id()).isEqualTo(owner.getId());
        assertThat(body.owner().username()).isEqualTo("owner");
        assertThat(body.checkedBy()).isNull();
    }

    @Test
    void getTicket_includesCheckedByForOrganizer() {
        HttpResponse<String> ownerReg = register("owner@example.com", "secret123", "Owner", "owner");
        JUsers owner = users.findByEmail("owner@example.com").orElseThrow();
        JEvents event = seedEvent(owner, EventStatus.OK, Instant.now().plus(Duration.ofDays(1)));
        JTicketTier tier = seedTier(event, "VIP", "40.00", 10);

        HttpResponse<String> adminReg = register("admin@example.com", "secret123", "Admin", "admin");
        JUsers admin = users.findByEmail("admin@example.com").orElseThrow();
        seedOrganizer(admin, event, EventRole.ADMIN);
        JTicket ticket = seedCheckedInTicket(owner, tier, admin);
        HttpHeaders auth = withCookies(new HttpHeaders(), ACCESS_COOKIE, accessToken(adminReg));

        HttpResponse<String> response = call(HttpMethod.GET, "/api/tickets/" + ticket.getId(), null, auth);

        assertThat(response.statusCode()).isEqualTo(HttpStatus.OK.value());
        TicketDetail body = readBody(response, TicketDetail.class);
        assertThat(body.ticketStatus()).isEqualTo(TicketStatus.USED);
        assertThat(body.checkedBy()).isNotNull();
        assertThat(body.checkedBy().id()).isEqualTo(admin.getId());
        assertThat(body.checkedBy().username()).isEqualTo("admin");
    }

    @Test
    void getTicket_rejectsNonOwnerNonOrganizer() {
        HttpResponse<String> ownerReg = register("owner@example.com", "secret123", "Owner", "owner");
        JUsers owner = users.findByEmail("owner@example.com").orElseThrow();
        JEvents event = seedEvent(owner, EventStatus.OK, Instant.now().plus(Duration.ofDays(1)));
        JTicketTier tier = seedTier(event, "VIP", "40.00", 10);
        JTicket ticket = seedTicket(owner, tier, TicketStatus.VALID);

        HttpResponse<String> otherReg = register("other@example.com", "secret123", "Other", "other");
        HttpHeaders auth = withCookies(new HttpHeaders(), ACCESS_COOKIE, accessToken(otherReg));

        HttpResponse<String> response = call(HttpMethod.GET, "/api/tickets/" + ticket.getId(), null, auth);

        assertThat(response.statusCode()).isEqualTo(HttpStatus.FORBIDDEN.value());
        assertThat(readBody(response, ApiError.class).error()).isEqualTo("TICKET_FORBIDDEN");
    }

    @Test
    void getTicket_returnsNotFoundForUnknownTicket() {
        HttpResponse<String> ownerReg = register("owner@example.com", "secret123", "Owner", "owner");
        HttpHeaders auth = withCookies(new HttpHeaders(), ACCESS_COOKIE, accessToken(ownerReg));

        HttpResponse<String> response = call(HttpMethod.GET, "/api/tickets/" + UUID.randomUUID(), null, auth);

        assertThat(response.statusCode()).isEqualTo(HttpStatus.NOT_FOUND.value());
        assertThat(readBody(response, ApiError.class).error()).isEqualTo("TICKET_NOT_FOUND");
    }

    @Test
    void getTicket_requiresAuthentication() {
        HttpResponse<String> response = call(HttpMethod.GET, "/api/tickets/" + UUID.randomUUID(), null,
                new HttpHeaders());

        assertThat(response.statusCode()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
    }

    // ------------------------------------------------------------------
    // POST /api/tickets/{ticketId}/check-in
    // ------------------------------------------------------------------

    @Test
    void checkInTicket_marksUsedForOrganizer() {
        JUsers creator = seedUser("creator@example.com", "creator", "secret123");
        JEvents event = seedEvent(creator, EventStatus.OK, Instant.now().plus(Duration.ofDays(1)));
        JTicketTier tier = seedTier(event, "VIP", "40.00", 10);
        JTicket ticket = seedTicket(creator, tier, TicketStatus.VALID);

        HttpResponse<String> adminReg = register("admin@example.com", "secret123", "Admin", "admin");
        JUsers admin = users.findByEmail("admin@example.com").orElseThrow();
        seedOrganizer(admin, event, EventRole.ADMIN);
        HttpHeaders auth = withCookies(new HttpHeaders(), ACCESS_COOKIE, accessToken(adminReg));

        HttpResponse<String> response = call(HttpMethod.POST, "/api/tickets/" + ticket.getId() + "/check-in",
                null, auth);

        assertThat(response.statusCode()).isEqualTo(HttpStatus.OK.value());
        CheckInResponse body = readBody(response, CheckInResponse.class);
        assertThat(body.id()).isEqualTo(ticket.getId());
        assertThat(body.ticketStatus()).isEqualTo(TicketStatus.USED);
        assertThat(body.checkedInAt()).isNotNull();
        assertThat(body.checkedBy().id()).isEqualTo(admin.getId());
        assertThat(body.checkedBy().username()).isEqualTo("admin");
        assertThat(body.owner().username()).isEqualTo("creator");
        assertThat(body.tier().tierName()).isEqualTo("VIP");

        JTicket stored = tickets.findById(ticket.getId()).orElseThrow();
        assertThat(stored.getTicketStatus()).isEqualTo(TicketStatus.USED);
        assertThat(stored.getCheckedInAt()).isNotNull();
        assertThat(stored.getCheckedBy().getId()).isEqualTo(admin.getId());
    }

    @Test
    void checkInTicket_requiresAuthentication() {
        JUsers creator = seedUser("creator@example.com", "creator", "secret123");
        JEvents event = seedEvent(creator, EventStatus.OK, Instant.now().plus(Duration.ofDays(1)));
        JTicketTier tier = seedTier(event, "VIP", "40.00", 10);
        JTicket ticket = seedTicket(creator, tier, TicketStatus.VALID);

        HttpResponse<String> response = call(HttpMethod.POST, "/api/tickets/" + ticket.getId() + "/check-in",
                null, new HttpHeaders());

        assertThat(response.statusCode()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
    }

    @Test
    void checkInTicket_rejectsNonOrganizer() {
        JUsers creator = seedUser("creator@example.com", "creator", "secret123");
        JEvents event = seedEvent(creator, EventStatus.OK, Instant.now().plus(Duration.ofDays(1)));
        JTicketTier tier = seedTier(event, "VIP", "40.00", 10);
        JTicket ticket = seedTicket(creator, tier, TicketStatus.VALID);

        HttpResponse<String> otherReg = register("other@example.com", "secret123", "Other", "other");
        HttpHeaders auth = withCookies(new HttpHeaders(), ACCESS_COOKIE, accessToken(otherReg));

        HttpResponse<String> response = call(HttpMethod.POST, "/api/tickets/" + ticket.getId() + "/check-in",
                null, auth);

        assertThat(response.statusCode()).isEqualTo(HttpStatus.FORBIDDEN.value());
        assertThat(readBody(response, ApiError.class).error()).isEqualTo("NOT_AN_ORGANIZER");
    }

    @Test
    void checkInTicket_returnsNotFoundForUnknownTicket() {
        HttpResponse<String> adminReg = register("admin@example.com", "secret123", "Admin", "admin");
        HttpHeaders auth = withCookies(new HttpHeaders(), ACCESS_COOKIE, accessToken(adminReg));

        HttpResponse<String> response = call(HttpMethod.POST, "/api/tickets/" + UUID.randomUUID() + "/check-in",
                null, auth);

        assertThat(response.statusCode()).isEqualTo(HttpStatus.NOT_FOUND.value());
        assertThat(readBody(response, ApiError.class).error()).isEqualTo("TICKET_NOT_FOUND");
    }

    @Test
    void checkInTicket_rejectsAlreadyUsedTicket() {
        JUsers creator = seedUser("creator@example.com", "creator", "secret123");
        JEvents event = seedEvent(creator, EventStatus.OK, Instant.now().plus(Duration.ofDays(1)));
        JTicketTier tier = seedTier(event, "VIP", "40.00", 10);

        HttpResponse<String> adminReg = register("admin@example.com", "secret123", "Admin", "admin");
        JUsers admin = users.findByEmail("admin@example.com").orElseThrow();
        seedOrganizer(admin, event, EventRole.ADMIN);
        JTicket ticket = seedCheckedInTicket(creator, tier, admin);
        HttpHeaders auth = withCookies(new HttpHeaders(), ACCESS_COOKIE, accessToken(adminReg));

        HttpResponse<String> response = call(HttpMethod.POST, "/api/tickets/" + ticket.getId() + "/check-in",
                null, auth);

        assertThat(response.statusCode()).isEqualTo(HttpStatus.CONFLICT.value());
        ApiError error = readBody(response, ApiError.class);
        assertThat(error.error()).isEqualTo("TICKET_ALREADY_USED");
        assertThat(error.details()).hasSize(1);
        assertThat(error.details().get(0).field()).isEqualTo("checkedInAt");
    }

    @Test
    void checkInTicket_rejectsCancelledTicket() {
        JUsers creator = seedUser("creator@example.com", "creator", "secret123");
        JEvents event = seedEvent(creator, EventStatus.OK, Instant.now().plus(Duration.ofDays(1)));
        JTicketTier tier = seedTier(event, "VIP", "40.00", 10);
        JTicket ticket = seedTicket(creator, tier, TicketStatus.CANCELLED);

        HttpResponse<String> adminReg = register("admin@example.com", "secret123", "Admin", "admin");
        JUsers admin = users.findByEmail("admin@example.com").orElseThrow();
        seedOrganizer(admin, event, EventRole.ADMIN);
        HttpHeaders auth = withCookies(new HttpHeaders(), ACCESS_COOKIE, accessToken(adminReg));

        HttpResponse<String> response = call(HttpMethod.POST, "/api/tickets/" + ticket.getId() + "/check-in",
                null, auth);

        assertThat(response.statusCode()).isEqualTo(HttpStatus.CONFLICT.value());
        assertThat(readBody(response, ApiError.class).error()).isEqualTo("TICKET_NOT_VALID");
    }

    @Test
    void checkInTicket_rejectsExpiredTicket() {
        JUsers creator = seedUser("creator@example.com", "creator", "secret123");
        JEvents event = seedEvent(creator, EventStatus.OK, Instant.now().plus(Duration.ofDays(1)));
        JTicketTier tier = seedTier(event, "VIP", "40.00", 10);
        JTicket ticket = seedTicketWithValidUntil(creator, tier, TicketStatus.VALID,
                Instant.now().minus(Duration.ofHours(1)));

        HttpResponse<String> adminReg = register("admin@example.com", "secret123", "Admin", "admin");
        JUsers admin = users.findByEmail("admin@example.com").orElseThrow();
        seedOrganizer(admin, event, EventRole.ADMIN);
        HttpHeaders auth = withCookies(new HttpHeaders(), ACCESS_COOKIE, accessToken(adminReg));

        HttpResponse<String> response = call(HttpMethod.POST, "/api/tickets/" + ticket.getId() + "/check-in",
                null, auth);

        assertThat(response.statusCode()).isEqualTo(HttpStatus.CONFLICT.value());
        assertThat(readBody(response, ApiError.class).error()).isEqualTo("TICKET_EXPIRED");
    }

    @Test
    void checkInTicket_rejectsCancelledEvent() {
        HttpResponse<String> adminReg = register("admin@example.com", "secret123", "Admin", "admin");
        JUsers admin = users.findByEmail("admin@example.com").orElseThrow();
        JUsers creator = seedUser("creator@example.com", "creator", "secret123");
        JEvents event = seedEvent(creator, EventStatus.CANCELLED, Instant.now().plus(Duration.ofDays(1)));
        JTicketTier tier = seedTier(event, "VIP", "40.00", 10);
        seedOrganizer(admin, event, EventRole.ADMIN);
        JTicket ticket = seedTicket(creator, tier, TicketStatus.VALID);
        HttpHeaders auth = withCookies(new HttpHeaders(), ACCESS_COOKIE, accessToken(adminReg));

        HttpResponse<String> response = call(HttpMethod.POST, "/api/tickets/" + ticket.getId() + "/check-in",
                null, auth);

        assertThat(response.statusCode()).isEqualTo(HttpStatus.CONFLICT.value());
        assertThat(readBody(response, ApiError.class).error()).isEqualTo("EVENT_NOT_OPEN");
    }

    // ------------------------------------------------------------------
    // POST /api/tickets/{ticketId}/cancel
    // ------------------------------------------------------------------

    @Test
    void cancelTicket_cancelsOwnTicketAndFreesCapacity() {
        HttpResponse<String> ownerReg = register("owner@example.com", "secret123", "Owner", "owner");
        JUsers owner = users.findByEmail("owner@example.com").orElseThrow();
        JEvents event = seedEvent(owner, EventStatus.OK, Instant.now().plus(Duration.ofDays(1)));
        JTicketTier tier = seedTier(event, "VIP", "40.00", 1);
        JTicket ticket = seedTicket(owner, tier, TicketStatus.VALID);
        HttpHeaders auth = withCookies(new HttpHeaders(), ACCESS_COOKIE, accessToken(ownerReg));

        HttpResponse<String> response = call(HttpMethod.POST, "/api/tickets/" + ticket.getId() + "/cancel",
                null, auth);

        assertThat(response.statusCode()).isEqualTo(HttpStatus.OK.value());
        assertThat(readBody(response, TicketResponse.class).ticketStatus()).isEqualTo(TicketStatus.CANCELLED);
        assertThat(tickets.findById(ticket.getId()).orElseThrow().getTicketStatus())
                .isEqualTo(TicketStatus.CANCELLED);

        HttpResponse<String> issued = call(HttpMethod.POST, "/api/tiers/" + tier.getId() + "/tickets", null, auth);
        assertThat(issued.statusCode()).isEqualTo(HttpStatus.CREATED.value());
    }

    @Test
    void cancelTicket_rejectsNonOwner() {
        JUsers creator = seedUser("creator@example.com", "creator", "secret123");
        JEvents event = seedEvent(creator, EventStatus.OK, Instant.now().plus(Duration.ofDays(1)));
        JTicketTier tier = seedTier(event, "VIP", "40.00", 10);
        JTicket ticket = seedTicket(creator, tier, TicketStatus.VALID);

        HttpResponse<String> otherReg = register("other@example.com", "secret123", "Other", "other");
        HttpHeaders auth = withCookies(new HttpHeaders(), ACCESS_COOKIE, accessToken(otherReg));

        HttpResponse<String> response = call(HttpMethod.POST, "/api/tickets/" + ticket.getId() + "/cancel",
                null, auth);

        assertThat(response.statusCode()).isEqualTo(HttpStatus.FORBIDDEN.value());
        assertThat(readBody(response, ApiError.class).error()).isEqualTo("NOT_THE_OWNER");
    }

    @Test
    void cancelTicket_rejectsUsedTicket() {
        HttpResponse<String> ownerReg = register("owner@example.com", "secret123", "Owner", "owner");
        JUsers owner = users.findByEmail("owner@example.com").orElseThrow();
        JEvents event = seedEvent(owner, EventStatus.OK, Instant.now().plus(Duration.ofDays(1)));
        JTicketTier tier = seedTier(event, "VIP", "40.00", 10);
        JTicket ticket = seedCheckedInTicket(owner, tier, owner);
        HttpHeaders auth = withCookies(new HttpHeaders(), ACCESS_COOKIE, accessToken(ownerReg));

        HttpResponse<String> response = call(HttpMethod.POST, "/api/tickets/" + ticket.getId() + "/cancel",
                null, auth);

        assertThat(response.statusCode()).isEqualTo(HttpStatus.CONFLICT.value());
        assertThat(readBody(response, ApiError.class).error()).isEqualTo("TICKET_ALREADY_USED");
    }

    @Test
    void cancelTicket_requiresAuthentication() {
        JUsers creator = seedUser("creator@example.com", "creator", "secret123");
        JEvents event = seedEvent(creator, EventStatus.OK, Instant.now().plus(Duration.ofDays(1)));
        JTicketTier tier = seedTier(event, "VIP", "40.00", 10);
        JTicket ticket = seedTicket(creator, tier, TicketStatus.VALID);

        HttpResponse<String> response = call(HttpMethod.POST, "/api/tickets/" + ticket.getId() + "/cancel",
                null, new HttpHeaders());

        assertThat(response.statusCode()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
    }

    // ------------------------------------------------------------------
    // POST /api/tickets/{ticketId}/refund
    // ------------------------------------------------------------------

    @Test
    void refundTicket_refundsValidTicketForOrganizer() {
        JUsers creator = seedUser("creator@example.com", "creator", "secret123");
        JEvents event = seedEvent(creator, EventStatus.OK, Instant.now().plus(Duration.ofDays(1)));
        JTicketTier tier = seedTier(event, "VIP", "40.00", 10);
        JTicket ticket = seedTicket(creator, tier, TicketStatus.VALID);

        HttpResponse<String> adminReg = register("admin@example.com", "secret123", "Admin", "admin");
        JUsers admin = users.findByEmail("admin@example.com").orElseThrow();
        seedOrganizer(admin, event, EventRole.ADMIN);
        HttpHeaders auth = withCookies(new HttpHeaders(), ACCESS_COOKIE, accessToken(adminReg));

        HttpResponse<String> response = call(HttpMethod.POST, "/api/tickets/" + ticket.getId() + "/refund",
                new RefundRequest("duplicate purchase"), auth);

        assertThat(response.statusCode()).isEqualTo(HttpStatus.OK.value());
        assertThat(readBody(response, TicketResponse.class).ticketStatus()).isEqualTo(TicketStatus.REFUNDED);
        assertThat(tickets.findById(ticket.getId()).orElseThrow().getTicketStatus())
                .isEqualTo(TicketStatus.REFUNDED);
    }

    @Test
    void refundTicket_rejectsNonOrganizer() {
        JUsers creator = seedUser("creator@example.com", "creator", "secret123");
        JEvents event = seedEvent(creator, EventStatus.OK, Instant.now().plus(Duration.ofDays(1)));
        JTicketTier tier = seedTier(event, "VIP", "40.00", 10);
        JTicket ticket = seedTicket(creator, tier, TicketStatus.VALID);

        HttpResponse<String> otherReg = register("other@example.com", "secret123", "Other", "other");
        HttpHeaders auth = withCookies(new HttpHeaders(), ACCESS_COOKIE, accessToken(otherReg));

        HttpResponse<String> response = call(HttpMethod.POST, "/api/tickets/" + ticket.getId() + "/refund",
                null, auth);

        assertThat(response.statusCode()).isEqualTo(HttpStatus.FORBIDDEN.value());
        assertThat(readBody(response, ApiError.class).error()).isEqualTo("NOT_AN_ORGANIZER");
    }

    @Test
    void refundTicket_rejectsUsedTicket() {
        JUsers creator = seedUser("creator@example.com", "creator", "secret123");
        JEvents event = seedEvent(creator, EventStatus.OK, Instant.now().plus(Duration.ofDays(1)));
        JTicketTier tier = seedTier(event, "VIP", "40.00", 10);

        HttpResponse<String> adminReg = register("admin@example.com", "secret123", "Admin", "admin");
        JUsers admin = users.findByEmail("admin@example.com").orElseThrow();
        seedOrganizer(admin, event, EventRole.ADMIN);
        JTicket ticket = seedCheckedInTicket(creator, tier, admin);
        HttpHeaders auth = withCookies(new HttpHeaders(), ACCESS_COOKIE, accessToken(adminReg));

        HttpResponse<String> response = call(HttpMethod.POST, "/api/tickets/" + ticket.getId() + "/refund",
                null, auth);

        assertThat(response.statusCode()).isEqualTo(HttpStatus.CONFLICT.value());
        assertThat(readBody(response, ApiError.class).error()).isEqualTo("TICKET_NOT_REFUNDABLE");
    }

    @Test
    void refundTicket_requiresAuthentication() {
        JUsers creator = seedUser("creator@example.com", "creator", "secret123");
        JEvents event = seedEvent(creator, EventStatus.OK, Instant.now().plus(Duration.ofDays(1)));
        JTicketTier tier = seedTier(event, "VIP", "40.00", 10);
        JTicket ticket = seedTicket(creator, tier, TicketStatus.VALID);

        HttpResponse<String> response = call(HttpMethod.POST, "/api/tickets/" + ticket.getId() + "/refund",
                null, new HttpHeaders());

        assertThat(response.statusCode()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
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

    private JTicket seedTicket(JUsers owner, JTicketTier tier, TicketStatus status) {
        JTicket ticket = new JTicket();
        ticket.setOwner(owner);
        ticket.setTier(tier);
        ticket.setPricePaid(tier.getPrice());
        ticket.setTicketStatus(status);
        return tickets.saveAndFlush(ticket);
    }

    private JTicket seedTicketWithValidUntil(JUsers owner, JTicketTier tier, TicketStatus status,
            Instant validUntil) {
        JTicket ticket = new JTicket();
        ticket.setOwner(owner);
        ticket.setTier(tier);
        ticket.setPricePaid(tier.getPrice());
        ticket.setTicketStatus(status);
        ticket.setValidUntil(validUntil);
        return tickets.saveAndFlush(ticket);
    }

    private JTicket seedCheckedInTicket(JUsers owner, JTicketTier tier, JUsers scanner) {
        JTicket ticket = new JTicket();
        ticket.setOwner(owner);
        ticket.setTier(tier);
        ticket.setCheckedBy(scanner);
        ticket.setPricePaid(tier.getPrice());
        ticket.setTicketStatus(TicketStatus.USED);
        ticket.setCheckedInAt(Instant.now());
        return tickets.saveAndFlush(ticket);
    }
}
