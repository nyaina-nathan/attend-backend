package mg.improve.attend.controller;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import mg.improve.attend.TestcontainersConfiguration;
import mg.improve.attend.domain.dto.request.EventCreateRequest;
import mg.improve.attend.domain.dto.request.EventUpdateRequest;
import mg.improve.attend.domain.dto.response.EventDetail;
import mg.improve.attend.domain.dto.response.EventSummary;
import mg.improve.attend.domain.dto.response.PageEventSummary;
import mg.improve.attend.domain.enums.EventRole;
import mg.improve.attend.domain.enums.EventStatus;
import mg.improve.attend.domain.enums.TicketStatus;
import mg.improve.attend.exceptions.ApiError;
import mg.improve.attend.exceptions.ErrorDetail;
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
class EventControllerIT extends IntegrationTestSupport {

    private static final Instant START = Instant.parse("2026-11-20T18:00:00Z");
    private static final Instant END = Instant.parse("2026-11-20T22:00:00Z");

    @Autowired
    private JEventsRepository events;

    @Autowired
    private JEventOrganizerRepository organizers;

    @Autowired
    private JTicketTierRepository tiers;

    @Autowired
    private JTicketRepository tickets;

    // ------------------------------------------------------------------
    // POST /api/events
    // ------------------------------------------------------------------

    @Test
    void createEvent_createsEventAndCreatorOrganizer() {
        HttpResponse<String> registered = register("owner@example.com", "secret123", "Owner", "owner");
        JUsers owner = users.findByEmail("owner@example.com").orElseThrow();
        HttpHeaders auth = withCookies(new HttpHeaders(), ACCESS_COOKIE, accessToken(registered));

        HttpResponse<String> response = call(HttpMethod.POST, "/api/events",
                new EventCreateRequest("Jazz Night", "desc", START, END, null, "Venue"), auth);

        assertThat(response.statusCode()).isEqualTo(HttpStatus.CREATED.value());
        EventDetail body = readBody(response, EventDetail.class);
        assertThat(body.title()).isEqualTo("Jazz Night");
        assertThat(body.isPublic()).isTrue();
        assertThat(body.status()).isEqualTo(EventStatus.OK);
        assertThat(body.myRole()).isEqualTo(EventRole.CREATOR);
        assertThat(body.createdAt()).isNotNull();

        JEvents persisted = events.findAll().get(0);
        assertThat(organizers.findByEvent_IdAndUser_Id(persisted.getId(), owner.getId()))
                .hasValueSatisfying(organizer ->
                        assertThat(organizer.getEventRole()).isEqualTo(EventRole.CREATOR));
    }

    @Test
    void createEvent_honorsExplicitIsPublicFalse() {
        HttpResponse<String> registered = register("owner@example.com", "secret123", "Owner", "owner");
        HttpHeaders auth = withCookies(new HttpHeaders(), ACCESS_COOKIE, accessToken(registered));

        HttpResponse<String> response = call(HttpMethod.POST, "/api/events",
                new EventCreateRequest("Private", null, START, END, false, null), auth);

        assertThat(readBody(response, EventDetail.class).isPublic()).isFalse();
    }

    @Test
    void createEvent_rejectsInvalidTimeRange() {
        HttpResponse<String> registered = register("owner@example.com", "secret123", "Owner", "owner");
        HttpHeaders auth = withCookies(new HttpHeaders(), ACCESS_COOKIE, accessToken(registered));

        HttpResponse<String> response = call(HttpMethod.POST, "/api/events",
                new EventCreateRequest("Bad", null, END, START, null, null), auth);

        assertThat(response.statusCode()).isEqualTo(HttpStatus.BAD_REQUEST.value());
        ApiError error = readBody(response, ApiError.class);
        assertThat(error.error()).isEqualTo("VALIDATION_ERROR");
        assertThat(error.details()).extracting(ErrorDetail::field).contains("endTime");
        assertThat(events.count()).isZero();
    }

    @Test
    void createEvent_requiresAuthentication() {
        HttpResponse<String> response = call(HttpMethod.POST, "/api/events",
                new EventCreateRequest("Jazz", null, START, END, null, null), new HttpHeaders());

        assertThat(response.statusCode()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
        assertThat(events.count()).isZero();
    }

    // ------------------------------------------------------------------
    // GET /api/events
    // ------------------------------------------------------------------

    @Test
    void listEvents_returnsOnlyPublicEventsWithMinPrice() {
        JUsers owner = seedUser("owner@example.com", "owner", "secret123");
        JEvents publicEvent = seedEvent(owner, "Public Event", true);
        seedEvent(owner, "Private Event", false);
        seedTier(publicEvent, "Standard", "10.00", 100);

        HttpResponse<String> response = call(HttpMethod.GET, "/api/events", null, new HttpHeaders());

        assertThat(response.statusCode()).isEqualTo(HttpStatus.OK.value());
        PageEventSummary page = readBody(response, PageEventSummary.class);
        assertThat(page.totalElements()).isEqualTo(1);
        assertThat(page.content()).extracting(EventSummary::title).containsExactly("Public Event");
        assertThat(page.content().get(0).minPrice()).isEqualByComparingTo("10.00");
    }

    @Test
    void listEvents_filtersByTitleAndStatus() {
        JUsers owner = seedUser("owner@example.com", "owner", "secret123");
        seedEvent(owner, "Jazz Night", true);
        JEvents cancelled = seedEvent(owner, "Rock Night", true);
        cancelled.setStatus(EventStatus.CANCELLED);
        events.saveAndFlush(cancelled);

        PageEventSummary byTitle = readBody(
                call(HttpMethod.GET, "/api/events?q=jazz", null, new HttpHeaders()), PageEventSummary.class);
        assertThat(byTitle.content()).extracting(EventSummary::title).containsExactly("Jazz Night");

        PageEventSummary byStatus = readBody(
                call(HttpMethod.GET, "/api/events?status=CANCELLED", null, new HttpHeaders()), PageEventSummary.class);
        assertThat(byStatus.content()).extracting(EventSummary::title).containsExactly("Rock Night");
    }

    @Test
    void listEvents_isMineReturnsCallerEventsAndSupportsIsPublicFilter() {
        HttpResponse<String> registered = register("owner@example.com", "secret123", "Owner", "owner");
        JUsers owner = users.findByEmail("owner@example.com").orElseThrow();
        HttpHeaders auth = withCookies(new HttpHeaders(), ACCESS_COOKIE, accessToken(registered));
        JUsers other = seedUser("other@example.com", "other", "secret123");

        seedEvent(owner, "Mine Public", true);
        seedEvent(owner, "Mine Private", false);
        seedEvent(other, "Other Public", true);

        PageEventSummary mine = readBody(
                call(HttpMethod.GET, "/api/events?isMine=true", null, auth), PageEventSummary.class);
        assertThat(mine.content()).extracting(EventSummary::title)
                .containsExactlyInAnyOrder("Mine Public", "Mine Private");

        PageEventSummary privateOnly = readBody(
                call(HttpMethod.GET, "/api/events?isMine=true&isPublic=false", null, auth), PageEventSummary.class);
        assertThat(privateOnly.content()).extracting(EventSummary::title).containsExactly("Mine Private");
    }

    @Test
    void listEvents_ignoresIsPublicWithoutMineAndFallsBackForAnonymous() {
        JUsers owner = seedUser("owner@example.com", "owner", "secret123");
        seedEvent(owner, "Public Only", true);
        seedEvent(owner, "Private Only", false);

        PageEventSummary ignored = readBody(
                call(HttpMethod.GET, "/api/events?isPublic=false", null, new HttpHeaders()), PageEventSummary.class);
        assertThat(ignored.content()).extracting(EventSummary::title).containsExactly("Public Only");

        PageEventSummary anonymousMine = readBody(
                call(HttpMethod.GET, "/api/events?isMine=true", null, new HttpHeaders()), PageEventSummary.class);
        assertThat(anonymousMine.content()).extracting(EventSummary::title).containsExactly("Public Only");
    }

    @Test
    void listEvents_rejectsInvalidPagingAndSort() {
        assertThat(call(HttpMethod.GET, "/api/events?size=0", null, new HttpHeaders()).statusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST.value());
        assertThat(call(HttpMethod.GET, "/api/events?sort=bogus", null, new HttpHeaders()).statusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST.value());
    }

    // ------------------------------------------------------------------
    // GET /api/events/{eventId}
    // ------------------------------------------------------------------

    @Test
    void getEvent_returnsPublicEventForAnonymousWithRemaining() {
        JUsers owner = seedUser("owner@example.com", "owner", "secret123");
        JEvents event = seedEvent(owner, "Public Event", true);
        JTicketTier tier = seedTier(event, "Standard", "10.00", 10);
        seedTicket(owner, tier, TicketStatus.VALID);
        seedTicket(owner, tier, TicketStatus.VALID);
        seedTicket(owner, tier, TicketStatus.USED);
        seedTicket(owner, tier, TicketStatus.CANCELLED);

        HttpResponse<String> response = call(HttpMethod.GET, "/api/events/" + event.getId(), null, new HttpHeaders());

        assertThat(response.statusCode()).isEqualTo(HttpStatus.OK.value());
        EventDetail body = readBody(response, EventDetail.class);
        assertThat(body.myRole()).isNull();
        assertThat(body.tiers()).hasSize(1);
        assertThat(body.tiers().get(0).remaining()).isEqualTo(7);
    }

    @Test
    void getEvent_rejectsAnonymousOnPrivateEvent() {
        JUsers owner = seedUser("owner@example.com", "owner", "secret123");
        JEvents event = seedEvent(owner, "Private Event", false);

        assertThat(call(HttpMethod.GET, "/api/events/" + event.getId(), null, new HttpHeaders()).statusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED.value());
    }

    @Test
    void getEvent_rejectsNonOrganizerOnPrivateEvent() {
        JUsers owner = seedUser("owner@example.com", "owner", "secret123");
        JEvents event = seedEvent(owner, "Private Event", false);
        HttpResponse<String> registered = register("other@example.com", "secret123", "Other", "other");
        HttpHeaders auth = withCookies(new HttpHeaders(), ACCESS_COOKIE, accessToken(registered));

        HttpResponse<String> response = call(HttpMethod.GET, "/api/events/" + event.getId(), null, auth);

        assertThat(response.statusCode()).isEqualTo(HttpStatus.FORBIDDEN.value());
        assertThat(readBody(response, ApiError.class).error()).isEqualTo("NOT_AN_ORGANIZER");
    }

    @Test
    void getEvent_returnsPrivateEventForOrganizer() {
        HttpResponse<String> registered = register("owner@example.com", "secret123", "Owner", "owner");
        JUsers owner = users.findByEmail("owner@example.com").orElseThrow();
        HttpHeaders auth = withCookies(new HttpHeaders(), ACCESS_COOKIE, accessToken(registered));
        JEvents event = seedEvent(owner, "Private Event", false);

        HttpResponse<String> response = call(HttpMethod.GET, "/api/events/" + event.getId(), null, auth);

        assertThat(response.statusCode()).isEqualTo(HttpStatus.OK.value());
        assertThat(readBody(response, EventDetail.class).myRole()).isEqualTo(EventRole.CREATOR);
    }

    @Test
    void getEvent_returnsNotFoundForUnknownId() {
        assertThat(call(HttpMethod.GET, "/api/events/" + UUID.randomUUID(), null, new HttpHeaders()).statusCode())
                .isEqualTo(HttpStatus.NOT_FOUND.value());
    }

    // ------------------------------------------------------------------
    // PATCH /api/events/{eventId}
    // ------------------------------------------------------------------

    @Test
    void patchEvent_updatesFieldsForCreator() {
        HttpResponse<String> registered = register("owner@example.com", "secret123", "Owner", "owner");
        JUsers owner = users.findByEmail("owner@example.com").orElseThrow();
        HttpHeaders auth = withCookies(new HttpHeaders(), ACCESS_COOKIE, accessToken(registered));
        JEvents event = seedEvent(owner, "Old Title", true);

        HttpResponse<String> response = call(HttpMethod.PATCH, "/api/events/" + event.getId(),
                new EventUpdateRequest("New Title", "updated", null, null, false, "New venue", EventStatus.POSTPONED),
                auth);

        assertThat(response.statusCode()).isEqualTo(HttpStatus.OK.value());
        EventDetail body = readBody(response, EventDetail.class);
        assertThat(body.title()).isEqualTo("New Title");
        assertThat(body.isPublic()).isFalse();
        assertThat(body.status()).isEqualTo(EventStatus.POSTPONED);
        assertThat(body.myRole()).isEqualTo(EventRole.CREATOR);

        JEvents persisted = events.findById(event.getId()).orElseThrow();
        assertThat(persisted.getTitle()).isEqualTo("New Title");
        assertThat(persisted.getStatus()).isEqualTo(EventStatus.POSTPONED);
    }

    @Test
    void patchEvent_rejectsNonCreator() {
        JUsers owner = seedUser("owner@example.com", "owner", "secret123");
        JEvents event = seedEvent(owner, "Old Title", true);
        HttpResponse<String> registered = register("other@example.com", "secret123", "Other", "other");
        HttpHeaders auth = withCookies(new HttpHeaders(), ACCESS_COOKIE, accessToken(registered));

        HttpResponse<String> response = call(HttpMethod.PATCH, "/api/events/" + event.getId(),
                new EventUpdateRequest("Nope", null, null, null, null, null, null), auth);

        assertThat(response.statusCode()).isEqualTo(HttpStatus.FORBIDDEN.value());
        assertThat(readBody(response, ApiError.class).error()).isEqualTo("NOT_THE_CREATOR");
    }

    @Test
    void patchEvent_rejectsInvalidTimeRange() {
        HttpResponse<String> registered = register("owner@example.com", "secret123", "Owner", "owner");
        JUsers owner = users.findByEmail("owner@example.com").orElseThrow();
        HttpHeaders auth = withCookies(new HttpHeaders(), ACCESS_COOKIE, accessToken(registered));
        JEvents event = seedEvent(owner, "Event", true);

        HttpResponse<String> response = call(HttpMethod.PATCH, "/api/events/" + event.getId(),
                new EventUpdateRequest(null, null, END, START, null, null, null), auth);

        assertThat(response.statusCode()).isEqualTo(HttpStatus.BAD_REQUEST.value());
        assertThat(readBody(response, ApiError.class).details())
                .extracting(ErrorDetail::field).contains("endTime");
    }

    @Test
    void patchEvent_requiresAuthentication() {
        JUsers owner = seedUser("owner@example.com", "owner", "secret123");
        JEvents event = seedEvent(owner, "Event", true);

        HttpResponse<String> response = call(HttpMethod.PATCH, "/api/events/" + event.getId(),
                new EventUpdateRequest("Nope", null, null, null, null, null, null), new HttpHeaders());

        assertThat(response.statusCode()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
    }

    // ------------------------------------------------------------------
    // DELETE /api/events/{eventId}
    // ------------------------------------------------------------------

    @Test
    void deleteEvent_removesEventWhenNoTickets() {
        HttpResponse<String> registered = register("owner@example.com", "secret123", "Owner", "owner");
        JUsers owner = users.findByEmail("owner@example.com").orElseThrow();
        HttpHeaders auth = withCookies(new HttpHeaders(), ACCESS_COOKIE, accessToken(registered));
        JEvents event = seedEvent(owner, "Event", true);

        HttpResponse<String> response = call(HttpMethod.DELETE, "/api/events/" + event.getId(), null, auth);

        assertThat(response.statusCode()).isEqualTo(HttpStatus.NO_CONTENT.value());
        assertThat(events.findById(event.getId())).isEmpty();
        assertThat(organizers.findByEvent_IdAndUser_Id(event.getId(), owner.getId())).isEmpty();
    }

    @Test
    void deleteEvent_rejectsWhenTicketsExist() {
        HttpResponse<String> registered = register("owner@example.com", "secret123", "Owner", "owner");
        JUsers owner = users.findByEmail("owner@example.com").orElseThrow();
        HttpHeaders auth = withCookies(new HttpHeaders(), ACCESS_COOKIE, accessToken(registered));
        JEvents event = seedEvent(owner, "Event", true);
        JTicketTier tier = seedTier(event, "Standard", "10.00", 10);
        seedTicket(owner, tier, TicketStatus.VALID);

        HttpResponse<String> response = call(HttpMethod.DELETE, "/api/events/" + event.getId(), null, auth);

        assertThat(response.statusCode()).isEqualTo(HttpStatus.CONFLICT.value());
        assertThat(readBody(response, ApiError.class).error()).isEqualTo("EVENT_HAS_TICKETS");
        assertThat(events.findById(event.getId())).isPresent();
    }

    @Test
    void deleteEvent_rejectsNonCreator() {
        JUsers owner = seedUser("owner@example.com", "owner", "secret123");
        JEvents event = seedEvent(owner, "Event", true);
        HttpResponse<String> registered = register("other@example.com", "secret123", "Other", "other");
        HttpHeaders auth = withCookies(new HttpHeaders(), ACCESS_COOKIE, accessToken(registered));

        HttpResponse<String> response = call(HttpMethod.DELETE, "/api/events/" + event.getId(), null, auth);

        assertThat(response.statusCode()).isEqualTo(HttpStatus.FORBIDDEN.value());
        assertThat(events.findById(event.getId())).isPresent();
    }

    @Test
    void deleteEvent_requiresAuthentication() {
        JUsers owner = seedUser("owner@example.com", "owner", "secret123");
        JEvents event = seedEvent(owner, "Event", true);

        assertThat(call(HttpMethod.DELETE, "/api/events/" + event.getId(), null, new HttpHeaders()).statusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED.value());
    }

    // ------------------------------------------------------------------
    // POST /api/events/{eventId}/cancel
    // ------------------------------------------------------------------

    @Test
    void cancelEvent_setsCancelledForCreator() {
        HttpResponse<String> registered = register("owner@example.com", "secret123", "Owner", "owner");
        JUsers owner = users.findByEmail("owner@example.com").orElseThrow();
        HttpHeaders auth = withCookies(new HttpHeaders(), ACCESS_COOKIE, accessToken(registered));
        JEvents event = seedEvent(owner, "Event", true);

        HttpResponse<String> response = call(HttpMethod.POST, "/api/events/" + event.getId() + "/cancel", null, auth);

        assertThat(response.statusCode()).isEqualTo(HttpStatus.OK.value());
        assertThat(readBody(response, EventDetail.class).status()).isEqualTo(EventStatus.CANCELLED);
        assertThat(events.findById(event.getId()).orElseThrow().getStatus()).isEqualTo(EventStatus.CANCELLED);
    }

    @Test
    void cancelEvent_rejectsNonCreator() {
        JUsers owner = seedUser("owner@example.com", "owner", "secret123");
        JEvents event = seedEvent(owner, "Event", true);
        HttpResponse<String> registered = register("other@example.com", "secret123", "Other", "other");
        HttpHeaders auth = withCookies(new HttpHeaders(), ACCESS_COOKIE, accessToken(registered));

        HttpResponse<String> response = call(HttpMethod.POST, "/api/events/" + event.getId() + "/cancel", null, auth);

        assertThat(response.statusCode()).isEqualTo(HttpStatus.FORBIDDEN.value());
        assertThat(events.findById(event.getId()).orElseThrow().getStatus()).isEqualTo(EventStatus.OK);
    }

    @Test
    void cancelEvent_requiresAuthentication() {
        JUsers owner = seedUser("owner@example.com", "owner", "secret123");
        JEvents event = seedEvent(owner, "Event", true);

        assertThat(call(HttpMethod.POST, "/api/events/" + event.getId() + "/cancel", null, new HttpHeaders())
                .statusCode()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
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
