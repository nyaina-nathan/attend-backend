package mg.improve.attend.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import java.math.BigDecimal;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import mg.improve.attend.TestcontainersConfiguration;
import mg.improve.attend.domain.dto.request.AddOrganizerRequest;
import mg.improve.attend.domain.dto.request.EventCreateRequest;
import mg.improve.attend.domain.dto.request.EventUpdateRequest;
import mg.improve.attend.domain.dto.response.EventDetail;
import mg.improve.attend.domain.dto.response.EventStats;
import mg.improve.attend.domain.dto.response.EventSummary;
import mg.improve.attend.domain.dto.response.Organizer;
import mg.improve.attend.domain.dto.response.PageEventSummary;
import mg.improve.attend.domain.dto.response.TierStats;
import mg.improve.attend.domain.enums.EventRole;
import mg.improve.attend.domain.enums.EventStatus;
import mg.improve.attend.domain.enums.OrderStatus;
import mg.improve.attend.domain.enums.PaymentType;
import mg.improve.attend.domain.enums.TicketStatus;
import mg.improve.attend.exceptions.ApiError;
import mg.improve.attend.exceptions.ErrorDetail;
import mg.improve.attend.repository.JEventOrganizerRepository;
import mg.improve.attend.repository.JEventPaymentMethodRepository;
import mg.improve.attend.repository.JEventsRepository;
import mg.improve.attend.repository.JTicketOrderRepository;
import mg.improve.attend.repository.JTicketRepository;
import mg.improve.attend.repository.JTicketTierRepository;
import mg.improve.attend.repository.entities.JEventOrganizer;
import mg.improve.attend.repository.entities.JEventPaymentMethod;
import mg.improve.attend.repository.entities.JEvents;
import mg.improve.attend.repository.entities.JTicket;
import mg.improve.attend.repository.entities.JTicketOrder;
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

    @Autowired
    private JTicketOrderRepository orders;

    @Autowired
    private JEventPaymentMethodRepository paymentMethods;

    // ------------------------------------------------------------------
    // POST /api/events
    // ------------------------------------------------------------------

    @Test
    void createEvent_createsEventAndCreatorOrganizer() {
        HttpResponse<String> registered = register("owner@example.com", "secret123", "Owner", "owner");
        JUsers owner = users.findByEmail("owner@example.com").orElseThrow();
        HttpHeaders auth = withCookies(new HttpHeaders(), ACCESS_COOKIE, accessToken(registered));

        HttpResponse<String> response = call(HttpMethod.POST, "/api/events",
                new EventCreateRequest("Jazz Night", "desc", START, END, null, "Venue", null, null, null), auth);

        assertThat(response.statusCode()).isEqualTo(HttpStatus.CREATED.value());
        EventDetail body = readBody(response, EventDetail.class);
        assertThat(body.title()).isEqualTo("Jazz Night");
        assertThat(body.isPublic()).isTrue();
        assertThat(body.status()).isEqualTo(EventStatus.OK);
        assertThat(body.myRole()).isEqualTo(EventRole.CREATOR);
        assertThat(body.priceUnit()).isEqualTo("USD");
        assertThat(body.createdAt()).isNotNull();

        JEvents persisted = events.findAll().get(0);
        assertThat(organizers.findByEvent_IdAndUser_Id(persisted.getId(), owner.getId()))
                .hasValueSatisfying(organizer ->
                        assertThat(organizer.getEventRole()).isEqualTo(EventRole.CREATOR));
    }

    @Test
    void createEvent_persistsCoordinates() {
        HttpResponse<String> registered = register("owner@example.com", "secret123", "Owner", "owner");
        HttpHeaders auth = withCookies(new HttpHeaders(), ACCESS_COOKIE, accessToken(registered));

        HttpResponse<String> response = call(HttpMethod.POST, "/api/events",
                new EventCreateRequest("Jazz Night", "desc", START, END, null, "Venue",
                        new BigDecimal("-18.879200"), new BigDecimal("47.507900"), null), auth);

        assertThat(response.statusCode()).isEqualTo(HttpStatus.CREATED.value());
        EventDetail body = readBody(response, EventDetail.class);
        assertThat(body.latitude()).isEqualByComparingTo(new BigDecimal("-18.879200"));
        assertThat(body.longitude()).isEqualByComparingTo(new BigDecimal("47.507900"));

        JEvents persisted = events.findAll().get(0);
        assertThat(persisted.getLatitude()).isEqualByComparingTo(new BigDecimal("-18.879200"));
        assertThat(persisted.getLongitude()).isEqualByComparingTo(new BigDecimal("47.507900"));
    }

    @Test
    void createEvent_rejectsOneSidedCoordinates() {
        HttpResponse<String> registered = register("owner@example.com", "secret123", "Owner", "owner");
        HttpHeaders auth = withCookies(new HttpHeaders(), ACCESS_COOKIE, accessToken(registered));

        HttpResponse<String> response = call(HttpMethod.POST, "/api/events",
                new EventCreateRequest("Jazz", null, START, END, null, "Venue",
                        new BigDecimal("-18.879200"), null, null), auth);

        assertThat(response.statusCode()).isEqualTo(HttpStatus.BAD_REQUEST.value());
        assertThat(readBody(response, ApiError.class).details())
                .extracting(ErrorDetail::field).contains("longitude");
        assertThat(events.count()).isZero();
    }

    @Test
    void createEvent_rejectsOutOfRangeCoordinates() {
        HttpResponse<String> registered = register("owner@example.com", "secret123", "Owner", "owner");
        HttpHeaders auth = withCookies(new HttpHeaders(), ACCESS_COOKIE, accessToken(registered));

        HttpResponse<String> response = call(HttpMethod.POST, "/api/events",
                new EventCreateRequest("Jazz", null, START, END, null, "Venue",
                        new BigDecimal("123.000000"), new BigDecimal("47.507900"), null), auth);

        assertThat(response.statusCode()).isEqualTo(HttpStatus.BAD_REQUEST.value());
        assertThat(readBody(response, ApiError.class).details())
                .extracting(ErrorDetail::field).contains("latitude");
        assertThat(events.count()).isZero();
    }

    @Test
    void createEvent_honorsExplicitIsPublicFalse() {
        HttpResponse<String> registered = register("owner@example.com", "secret123", "Owner", "owner");
        HttpHeaders auth = withCookies(new HttpHeaders(), ACCESS_COOKIE, accessToken(registered));

        HttpResponse<String> response = call(HttpMethod.POST, "/api/events",
                new EventCreateRequest("Private", null, START, END, false, null, null, null, null), auth);

        assertThat(readBody(response, EventDetail.class).isPublic()).isFalse();
    }

    @Test
    void createEvent_rejectsInvalidTimeRange() {
        HttpResponse<String> registered = register("owner@example.com", "secret123", "Owner", "owner");
        HttpHeaders auth = withCookies(new HttpHeaders(), ACCESS_COOKIE, accessToken(registered));

        HttpResponse<String> response = call(HttpMethod.POST, "/api/events",
                new EventCreateRequest("Bad", null, END, START, null, null, null, null, null), auth);

        assertThat(response.statusCode()).isEqualTo(HttpStatus.BAD_REQUEST.value());
        ApiError error = readBody(response, ApiError.class);
        assertThat(error.error()).isEqualTo("VALIDATION_ERROR");
        assertThat(error.details()).extracting(ErrorDetail::field).contains("endTime");
        assertThat(events.count()).isZero();
    }

    @Test
    void createEvent_rejectsTooLongPriceUnit() {
        HttpResponse<String> registered = register("owner@example.com", "secret123", "Owner", "owner");
        HttpHeaders auth = withCookies(new HttpHeaders(), ACCESS_COOKIE, accessToken(registered));

        HttpResponse<String> response = call(HttpMethod.POST, "/api/events",
                new EventCreateRequest("Jazz", null, START, END, null, null, null, null, "USDOLLARSXY"), auth);

        assertThat(response.statusCode()).isEqualTo(HttpStatus.BAD_REQUEST.value());
        assertThat(readBody(response, ApiError.class).details())
                .extracting(ErrorDetail::field).contains("priceUnit");
        assertThat(events.count()).isZero();
    }

    @Test
    void createEvent_requiresAuthentication() {
        HttpResponse<String> response = call(HttpMethod.POST, "/api/events",
                new EventCreateRequest("Jazz", null, START, END, null, null, null, null, null), new HttpHeaders());

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
    void listEvents_setsOrderPendingForOrganizerEventsOnly() {
        HttpResponse<String> registered = register("owner@example.com", "secret123", "Owner", "owner");
        JUsers owner = users.findByEmail("owner@example.com").orElseThrow();
        HttpHeaders auth = withCookies(new HttpHeaders(), ACCESS_COOKIE, accessToken(registered));
        JUsers other = seedUser("other@example.com", "other", "secret123");

        JEvents withPending = seedEvent(owner, "With Pending", true);
        JEventPaymentMethod method = seedPaymentMethod(withPending);
        seedPendingOrder(withPending, owner, method);
        seedEvent(owner, "No Pending", true);
        seedEvent(other, "Not Mine", true);

        PageEventSummary page = readBody(
                call(HttpMethod.GET, "/api/events", null, auth), PageEventSummary.class);

        assertThat(page.content())
                .extracting(EventSummary::title, EventSummary::isOrderPending)
                .containsExactlyInAnyOrder(
                        tuple("With Pending", true),
                        tuple("No Pending", false),
                        tuple("Not Mine", null));
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
        assertThat(body.isOrderPending()).isNull();
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
        EventDetail body = readBody(response, EventDetail.class);
        assertThat(body.myRole()).isEqualTo(EventRole.CREATOR);
        assertThat(body.isOrderPending()).isFalse();
    }

    @Test
    void getEvent_setsOrderPendingForOrganizerWithPendingOrder() {
        HttpResponse<String> registered = register("owner@example.com", "secret123", "Owner", "owner");
        JUsers owner = users.findByEmail("owner@example.com").orElseThrow();
        HttpHeaders auth = withCookies(new HttpHeaders(), ACCESS_COOKIE, accessToken(registered));
        JEvents event = seedEvent(owner, "Event", true);
        JEventPaymentMethod method = seedPaymentMethod(event);
        seedPendingOrder(event, owner, method);

        HttpResponse<String> response = call(HttpMethod.GET, "/api/events/" + event.getId(), null, auth);

        assertThat(response.statusCode()).isEqualTo(HttpStatus.OK.value());
        assertThat(readBody(response, EventDetail.class).isOrderPending()).isTrue();
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
                new EventUpdateRequest("New Title", "updated", null, null, false, "New venue", null, null, "EUR", EventStatus.POSTPONED),
                auth);

        assertThat(response.statusCode()).isEqualTo(HttpStatus.OK.value());
        EventDetail body = readBody(response, EventDetail.class);
        assertThat(body.title()).isEqualTo("New Title");
        assertThat(body.isPublic()).isFalse();
        assertThat(body.status()).isEqualTo(EventStatus.POSTPONED);
        assertThat(body.priceUnit()).isEqualTo("EUR");
        assertThat(body.myRole()).isEqualTo(EventRole.CREATOR);

        JEvents persisted = events.findById(event.getId()).orElseThrow();
        assertThat(persisted.getTitle()).isEqualTo("New Title");
        assertThat(persisted.getStatus()).isEqualTo(EventStatus.POSTPONED);
    }

    @Test
    void patchEvent_updatesCoordinates() {
        HttpResponse<String> registered = register("owner@example.com", "secret123", "Owner", "owner");
        JUsers owner = users.findByEmail("owner@example.com").orElseThrow();
        HttpHeaders auth = withCookies(new HttpHeaders(), ACCESS_COOKIE, accessToken(registered));
        JEvents event = seedEvent(owner, "Old Title", true);

        HttpResponse<String> response = call(HttpMethod.PATCH, "/api/events/" + event.getId(),
                new EventUpdateRequest(null, null, null, null, null, null,
                        new BigDecimal("-18.900000"), new BigDecimal("47.500000"), null, null), auth);

        assertThat(response.statusCode()).isEqualTo(HttpStatus.OK.value());
        EventDetail body = readBody(response, EventDetail.class);
        assertThat(body.latitude()).isEqualByComparingTo(new BigDecimal("-18.900000"));
        assertThat(body.longitude()).isEqualByComparingTo(new BigDecimal("47.500000"));

        JEvents persisted = events.findById(event.getId()).orElseThrow();
        assertThat(persisted.getLatitude()).isEqualByComparingTo(new BigDecimal("-18.900000"));
        assertThat(persisted.getLongitude()).isEqualByComparingTo(new BigDecimal("47.500000"));
    }

    @Test
    void patchEvent_rejectsNonCreator() {
        JUsers owner = seedUser("owner@example.com", "owner", "secret123");
        JEvents event = seedEvent(owner, "Old Title", true);
        HttpResponse<String> registered = register("other@example.com", "secret123", "Other", "other");
        HttpHeaders auth = withCookies(new HttpHeaders(), ACCESS_COOKIE, accessToken(registered));

        HttpResponse<String> response = call(HttpMethod.PATCH, "/api/events/" + event.getId(),
                new EventUpdateRequest("Nope", null, null, null, null, null, null, null, null, null), auth);

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
                new EventUpdateRequest(null, null, END, START, null, null, null, null, null, null), auth);

        assertThat(response.statusCode()).isEqualTo(HttpStatus.BAD_REQUEST.value());
        assertThat(readBody(response, ApiError.class).details())
                .extracting(ErrorDetail::field).contains("endTime");
    }

    @Test
    void patchEvent_requiresAuthentication() {
        JUsers owner = seedUser("owner@example.com", "owner", "secret123");
        JEvents event = seedEvent(owner, "Event", true);

        HttpResponse<String> response = call(HttpMethod.PATCH, "/api/events/" + event.getId(),
                new EventUpdateRequest("Nope", null, null, null, null, null, null, null, null, null), new HttpHeaders());

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
    // GET /api/events/{eventId}/organizers
    // ------------------------------------------------------------------

    @Test
    void listOrganizers_returnsCreatorAndAdminsForOrganizer() {
        HttpResponse<String> registered = register("owner@example.com", "secret123", "Owner", "owner");
        JUsers owner = users.findByEmail("owner@example.com").orElseThrow();
        HttpHeaders auth = withCookies(new HttpHeaders(), ACCESS_COOKIE, accessToken(registered));
        JEvents event = seedEvent(owner, "Event", true);
        JUsers admin = seedUser("admin@example.com", "admin", "secret123");
        seedOrganizer(admin, event, EventRole.ADMIN);

        HttpResponse<String> response = call(HttpMethod.GET, "/api/events/" + event.getId() + "/organizers", null, auth);

        assertThat(response.statusCode()).isEqualTo(HttpStatus.OK.value());
        List<Organizer> body = List.of(readBody(response, Organizer[].class));
        assertThat(body).hasSize(2);
        assertThat(body).extracting(Organizer::username).containsExactlyInAnyOrder("owner", "admin");
        assertThat(body).extracting(Organizer::eventRole)
                .containsExactlyInAnyOrder(EventRole.CREATOR, EventRole.ADMIN);

        Organizer adminEntry = body.stream()
                .filter(organizer -> organizer.eventRole() == EventRole.ADMIN)
                .findFirst().orElseThrow();
        assertThat(adminEntry.userId()).isEqualTo(admin.getId());
        assertThat(adminEntry.fullName()).isEqualTo("Seeded User");
    }

    @Test
    void listOrganizers_rejectsNonOrganizer() {
        JUsers owner = seedUser("owner@example.com", "owner", "secret123");
        JEvents event = seedEvent(owner, "Event", true);
        HttpResponse<String> registered = register("other@example.com", "secret123", "Other", "other");
        HttpHeaders auth = withCookies(new HttpHeaders(), ACCESS_COOKIE, accessToken(registered));

        HttpResponse<String> response = call(HttpMethod.GET, "/api/events/" + event.getId() + "/organizers", null, auth);

        assertThat(response.statusCode()).isEqualTo(HttpStatus.FORBIDDEN.value());
        assertThat(readBody(response, ApiError.class).error()).isEqualTo("NOT_AN_ORGANIZER");
    }

    @Test
    void listOrganizers_requiresAuthentication() {
        JUsers owner = seedUser("owner@example.com", "owner", "secret123");
        JEvents event = seedEvent(owner, "Event", true);

        assertThat(call(HttpMethod.GET, "/api/events/" + event.getId() + "/organizers", null, new HttpHeaders())
                .statusCode()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
    }

    @Test
    void listOrganizers_returnsNotFoundForUnknownEvent() {
        HttpResponse<String> registered = register("owner@example.com", "secret123", "Owner", "owner");
        HttpHeaders auth = withCookies(new HttpHeaders(), ACCESS_COOKIE, accessToken(registered));

        HttpResponse<String> response =
                call(HttpMethod.GET, "/api/events/" + UUID.randomUUID() + "/organizers", null, auth);

        assertThat(response.statusCode()).isEqualTo(HttpStatus.NOT_FOUND.value());
        assertThat(readBody(response, ApiError.class).error()).isEqualTo("EVENT_NOT_FOUND");
    }

    // ------------------------------------------------------------------
    // POST /api/events/{eventId}/organizers
    // ------------------------------------------------------------------

    @Test
    void addOrganizer_addsAdminForCreator() {
        HttpResponse<String> registered = register("owner@example.com", "secret123", "Owner", "owner");
        JUsers owner = users.findByEmail("owner@example.com").orElseThrow();
        HttpHeaders auth = withCookies(new HttpHeaders(), ACCESS_COOKIE, accessToken(registered));
        JEvents event = seedEvent(owner, "Event", true);
        JUsers target = seedUser("admin@example.com", "admin", "secret123");

        HttpResponse<String> response = call(HttpMethod.POST, "/api/events/" + event.getId() + "/organizers",
                new AddOrganizerRequest("admin", null), auth);

        assertThat(response.statusCode()).isEqualTo(HttpStatus.CREATED.value());
        Organizer body = readBody(response, Organizer.class);
        assertThat(body.eventRole()).isEqualTo(EventRole.ADMIN);
        assertThat(body.userId()).isEqualTo(target.getId());
        assertThat(body.username()).isEqualTo("admin");

        assertThat(organizers.findByEvent_IdAndUser_Id(event.getId(), target.getId()))
                .hasValueSatisfying(organizer ->
                        assertThat(organizer.getEventRole()).isEqualTo(EventRole.ADMIN));
    }

    @Test
    void addOrganizer_resolvesByEmail() {
        HttpResponse<String> registered = register("owner@example.com", "secret123", "Owner", "owner");
        JUsers owner = users.findByEmail("owner@example.com").orElseThrow();
        HttpHeaders auth = withCookies(new HttpHeaders(), ACCESS_COOKIE, accessToken(registered));
        JEvents event = seedEvent(owner, "Event", true);
        JUsers target = seedUser("admin@example.com", "admin", "secret123");

        HttpResponse<String> response = call(HttpMethod.POST, "/api/events/" + event.getId() + "/organizers",
                new AddOrganizerRequest(null, "admin@example.com"), auth);

        assertThat(response.statusCode()).isEqualTo(HttpStatus.CREATED.value());
        assertThat(readBody(response, Organizer.class).userId()).isEqualTo(target.getId());
    }

    @Test
    void addOrganizer_rejectsNonCreator() {
        JUsers owner = seedUser("owner@example.com", "owner", "secret123");
        JEvents event = seedEvent(owner, "Event", true);
        seedUser("admin@example.com", "admin", "secret123");
        HttpResponse<String> registered = register("other@example.com", "secret123", "Other", "other");
        HttpHeaders auth = withCookies(new HttpHeaders(), ACCESS_COOKIE, accessToken(registered));

        HttpResponse<String> response = call(HttpMethod.POST, "/api/events/" + event.getId() + "/organizers",
                new AddOrganizerRequest("admin", null), auth);

        assertThat(response.statusCode()).isEqualTo(HttpStatus.FORBIDDEN.value());
        assertThat(readBody(response, ApiError.class).error()).isEqualTo("NOT_THE_CREATOR");
    }

    @Test
    void addOrganizer_rejectsUnknownUser() {
        HttpResponse<String> registered = register("owner@example.com", "secret123", "Owner", "owner");
        JUsers owner = users.findByEmail("owner@example.com").orElseThrow();
        HttpHeaders auth = withCookies(new HttpHeaders(), ACCESS_COOKIE, accessToken(registered));
        JEvents event = seedEvent(owner, "Event", true);

        HttpResponse<String> response = call(HttpMethod.POST, "/api/events/" + event.getId() + "/organizers",
                new AddOrganizerRequest("ghost", null), auth);

        assertThat(response.statusCode()).isEqualTo(HttpStatus.NOT_FOUND.value());
        assertThat(readBody(response, ApiError.class).error()).isEqualTo("USER_NOT_FOUND");
    }

    @Test
    void addOrganizer_rejectsAlreadyOrganizer() {
        HttpResponse<String> registered = register("owner@example.com", "secret123", "Owner", "owner");
        HttpHeaders auth = withCookies(new HttpHeaders(), ACCESS_COOKIE, accessToken(registered));
        JUsers owner = users.findByEmail("owner@example.com").orElseThrow();
        JEvents event = seedEvent(owner, "Event", true);

        HttpResponse<String> response = call(HttpMethod.POST, "/api/events/" + event.getId() + "/organizers",
                new AddOrganizerRequest("owner", null), auth);

        assertThat(response.statusCode()).isEqualTo(HttpStatus.CONFLICT.value());
        assertThat(readBody(response, ApiError.class).error()).isEqualTo("ALREADY_ORGANIZER");
    }

    @Test
    void addOrganizer_rejectsMissingIdentity() {
        HttpResponse<String> registered = register("owner@example.com", "secret123", "Owner", "owner");
        JUsers owner = users.findByEmail("owner@example.com").orElseThrow();
        HttpHeaders auth = withCookies(new HttpHeaders(), ACCESS_COOKIE, accessToken(registered));
        JEvents event = seedEvent(owner, "Event", true);

        HttpResponse<String> response = call(HttpMethod.POST, "/api/events/" + event.getId() + "/organizers",
                new AddOrganizerRequest(null, null), auth);

        assertThat(response.statusCode()).isEqualTo(HttpStatus.BAD_REQUEST.value());
        assertThat(readBody(response, ApiError.class).error()).isEqualTo("VALIDATION_ERROR");
    }

    @Test
    void addOrganizer_rejectsBothIdentityFields() {
        HttpResponse<String> registered = register("owner@example.com", "secret123", "Owner", "owner");
        JUsers owner = users.findByEmail("owner@example.com").orElseThrow();
        HttpHeaders auth = withCookies(new HttpHeaders(), ACCESS_COOKIE, accessToken(registered));
        JEvents event = seedEvent(owner, "Event", true);

        HttpResponse<String> response = call(HttpMethod.POST, "/api/events/" + event.getId() + "/organizers",
                new AddOrganizerRequest("owner", "owner@example.com"), auth);

        assertThat(response.statusCode()).isEqualTo(HttpStatus.BAD_REQUEST.value());
        assertThat(readBody(response, ApiError.class).error()).isEqualTo("VALIDATION_ERROR");
    }

    @Test
    void addOrganizer_requiresAuthentication() {
        JUsers owner = seedUser("owner@example.com", "owner", "secret123");
        JEvents event = seedEvent(owner, "Event", true);

        assertThat(call(HttpMethod.POST, "/api/events/" + event.getId() + "/organizers",
                new AddOrganizerRequest("owner", null), new HttpHeaders()).statusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED.value());
    }

    // ------------------------------------------------------------------
    // DELETE /api/events/{eventId}/organizers/{organizerId}
    // ------------------------------------------------------------------

    @Test
    void removeOrganizer_deletesAdmin() {
        HttpResponse<String> registered = register("owner@example.com", "secret123", "Owner", "owner");
        JUsers owner = users.findByEmail("owner@example.com").orElseThrow();
        HttpHeaders auth = withCookies(new HttpHeaders(), ACCESS_COOKIE, accessToken(registered));
        JEvents event = seedEvent(owner, "Event", true);
        JUsers admin = seedUser("admin@example.com", "admin", "secret123");
        seedOrganizer(admin, event, EventRole.ADMIN);
        UUID organizerId = organizers.findByEvent_IdAndUser_Id(event.getId(), admin.getId()).orElseThrow().getId();

        HttpResponse<String> response = call(HttpMethod.DELETE,
                "/api/events/" + event.getId() + "/organizers/" + organizerId, null, auth);

        assertThat(response.statusCode()).isEqualTo(HttpStatus.NO_CONTENT.value());
        assertThat(organizers.findById(organizerId)).isEmpty();
    }

    @Test
    void removeOrganizer_rejectsCreatorRow() {
        HttpResponse<String> registered = register("owner@example.com", "secret123", "Owner", "owner");
        JUsers owner = users.findByEmail("owner@example.com").orElseThrow();
        HttpHeaders auth = withCookies(new HttpHeaders(), ACCESS_COOKIE, accessToken(registered));
        JEvents event = seedEvent(owner, "Event", true);
        UUID creatorOrganizerId =
                organizers.findByEvent_IdAndUser_Id(event.getId(), owner.getId()).orElseThrow().getId();

        HttpResponse<String> response = call(HttpMethod.DELETE,
                "/api/events/" + event.getId() + "/organizers/" + creatorOrganizerId, null, auth);

        assertThat(response.statusCode()).isEqualTo(HttpStatus.CONFLICT.value());
        assertThat(readBody(response, ApiError.class).error()).isEqualTo("CANNOT_REMOVE_CREATOR");
        assertThat(organizers.findById(creatorOrganizerId)).isPresent();
    }

    @Test
    void removeOrganizer_rejectsNonCreator() {
        JUsers owner = seedUser("owner@example.com", "owner", "secret123");
        JEvents event = seedEvent(owner, "Event", true);
        JUsers admin = seedUser("admin@example.com", "admin", "secret123");
        seedOrganizer(admin, event, EventRole.ADMIN);
        UUID organizerId = organizers.findByEvent_IdAndUser_Id(event.getId(), admin.getId()).orElseThrow().getId();
        HttpResponse<String> registered = register("other@example.com", "secret123", "Other", "other");
        HttpHeaders auth = withCookies(new HttpHeaders(), ACCESS_COOKIE, accessToken(registered));

        HttpResponse<String> response = call(HttpMethod.DELETE,
                "/api/events/" + event.getId() + "/organizers/" + organizerId, null, auth);

        assertThat(response.statusCode()).isEqualTo(HttpStatus.FORBIDDEN.value());
        assertThat(readBody(response, ApiError.class).error()).isEqualTo("NOT_THE_CREATOR");
        assertThat(organizers.findById(organizerId)).isPresent();
    }

    @Test
    void removeOrganizer_rejectsOrganizerOfAnotherEvent() {
        HttpResponse<String> registered = register("owner@example.com", "secret123", "Owner", "owner");
        JUsers owner = users.findByEmail("owner@example.com").orElseThrow();
        HttpHeaders auth = withCookies(new HttpHeaders(), ACCESS_COOKIE, accessToken(registered));
        JEvents event = seedEvent(owner, "Event", true);
        JEvents otherEvent = seedEvent(owner, "Other Event", true);
        JUsers admin = seedUser("admin@example.com", "admin", "secret123");
        seedOrganizer(admin, otherEvent, EventRole.ADMIN);
        UUID organizerId =
                organizers.findByEvent_IdAndUser_Id(otherEvent.getId(), admin.getId()).orElseThrow().getId();

        HttpResponse<String> response = call(HttpMethod.DELETE,
                "/api/events/" + event.getId() + "/organizers/" + organizerId, null, auth);

        assertThat(response.statusCode()).isEqualTo(HttpStatus.NOT_FOUND.value());
        assertThat(readBody(response, ApiError.class).error()).isEqualTo("ORGANIZER_NOT_FOUND");
        assertThat(organizers.findById(organizerId)).isPresent();
    }

    @Test
    void removeOrganizer_requiresAuthentication() {
        JUsers owner = seedUser("owner@example.com", "owner", "secret123");
        JEvents event = seedEvent(owner, "Event", true);
        JUsers admin = seedUser("admin@example.com", "admin", "secret123");
        seedOrganizer(admin, event, EventRole.ADMIN);
        UUID organizerId = organizers.findByEvent_IdAndUser_Id(event.getId(), admin.getId()).orElseThrow().getId();

        assertThat(call(HttpMethod.DELETE,
                "/api/events/" + event.getId() + "/organizers/" + organizerId, null, new HttpHeaders())
                .statusCode()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
    }

    // ------------------------------------------------------------------
    // GET /api/events/{eventId}/stats
    // ------------------------------------------------------------------

    @Test
    void getEventStats_returnsAggregatesForOrganizer() {
        HttpResponse<String> registered = register("owner@example.com", "secret123", "Owner", "owner");
        JUsers owner = users.findByEmail("owner@example.com").orElseThrow();
        HttpHeaders auth = withCookies(new HttpHeaders(), ACCESS_COOKIE, accessToken(registered));
        JEvents event = seedEvent(owner, "Event", true);
        JTicketTier standard = seedTier(event, "Standard", "10.00", 100);
        JTicketTier vip = seedTier(event, "VIP", "40.00", 10);
        JUsers buyer = seedUser("buyer@example.com", "buyer", "secret123");
        seedTicket(buyer, standard, TicketStatus.VALID);
        seedTicket(buyer, standard, TicketStatus.VALID);
        seedTicket(buyer, standard, TicketStatus.USED);
        seedTicket(buyer, standard, TicketStatus.CANCELLED);
        seedTicket(buyer, standard, TicketStatus.REFUNDED);
        seedTicket(buyer, vip, TicketStatus.USED);

        HttpResponse<String> response = call(HttpMethod.GET, "/api/events/" + event.getId() + "/stats", null, auth);

        assertThat(response.statusCode()).isEqualTo(HttpStatus.OK.value());
        EventStats body = readBody(response, EventStats.class);
        assertThat(body.eventId()).isEqualTo(event.getId());
        assertThat(body.priceUnit()).isEqualTo("USD");
        assertThat(body.totalCapacity()).isEqualTo(110);
        assertThat(body.ticketsIssued()).isEqualTo(4);
        assertThat(body.ticketsCheckedIn()).isEqualTo(2);
        assertThat(body.revenue()).isEqualByComparingTo("70.00");

        assertThat(body.byTier()).extracting(TierStats::tierName).containsExactly("Standard", "VIP");
        TierStats standardStats = body.byTier().get(0);
        assertThat(standardStats.tierId()).isEqualTo(standard.getId());
        assertThat(standardStats.issued()).isEqualTo(3);
        assertThat(standardStats.checkedIn()).isEqualTo(1);
        assertThat(standardStats.revenue()).isEqualByComparingTo("30.00");
        TierStats vipStats = body.byTier().get(1);
        assertThat(vipStats.tierId()).isEqualTo(vip.getId());
        assertThat(vipStats.issued()).isEqualTo(1);
        assertThat(vipStats.checkedIn()).isEqualTo(1);
        assertThat(vipStats.revenue()).isEqualByComparingTo("40.00");
    }

    @Test
    void getEventStats_allowsAdminOrganizer() {
        JUsers creator = seedUser("creator@example.com", "creator", "secret123");
        JEvents event = seedEvent(creator, "Event", true);
        seedTier(event, "Standard", "10.00", 10);
        HttpResponse<String> adminReg = register("admin@example.com", "secret123", "Admin", "admin");
        JUsers admin = users.findByEmail("admin@example.com").orElseThrow();
        seedOrganizer(admin, event, EventRole.ADMIN);
        HttpHeaders auth = withCookies(new HttpHeaders(), ACCESS_COOKIE, accessToken(adminReg));

        HttpResponse<String> response = call(HttpMethod.GET, "/api/events/" + event.getId() + "/stats", null, auth);

        assertThat(response.statusCode()).isEqualTo(HttpStatus.OK.value());
        assertThat(readBody(response, EventStats.class).totalCapacity()).isEqualTo(10);
    }

    @Test
    void getEventStats_returnsZeroesWhenNoTickets() {
        HttpResponse<String> registered = register("owner@example.com", "secret123", "Owner", "owner");
        JUsers owner = users.findByEmail("owner@example.com").orElseThrow();
        HttpHeaders auth = withCookies(new HttpHeaders(), ACCESS_COOKIE, accessToken(registered));
        JEvents event = seedEvent(owner, "Event", true);
        JTicketTier tier = seedTier(event, "Standard", "10.00", 25);

        HttpResponse<String> response = call(HttpMethod.GET, "/api/events/" + event.getId() + "/stats", null, auth);

        assertThat(response.statusCode()).isEqualTo(HttpStatus.OK.value());
        EventStats body = readBody(response, EventStats.class);
        assertThat(body.totalCapacity()).isEqualTo(25);
        assertThat(body.ticketsIssued()).isZero();
        assertThat(body.ticketsCheckedIn()).isZero();
        assertThat(body.revenue()).isEqualByComparingTo("0");
        assertThat(body.byTier()).hasSize(1);
        assertThat(body.byTier().get(0).tierId()).isEqualTo(tier.getId());
        assertThat(body.byTier().get(0).issued()).isZero();
        assertThat(body.byTier().get(0).revenue()).isEqualByComparingTo("0");
    }

    @Test
    void getEventStats_returnsEmptyByTierWhenEventHasNoTiers() {
        HttpResponse<String> registered = register("owner@example.com", "secret123", "Owner", "owner");
        JUsers owner = users.findByEmail("owner@example.com").orElseThrow();
        HttpHeaders auth = withCookies(new HttpHeaders(), ACCESS_COOKIE, accessToken(registered));
        JEvents event = seedEvent(owner, "Event", true);

        HttpResponse<String> response = call(HttpMethod.GET, "/api/events/" + event.getId() + "/stats", null, auth);

        assertThat(response.statusCode()).isEqualTo(HttpStatus.OK.value());
        EventStats body = readBody(response, EventStats.class);
        assertThat(body.totalCapacity()).isZero();
        assertThat(body.byTier()).isEmpty();
    }

    @Test
    void getEventStats_rejectsNonOrganizer() {
        JUsers creator = seedUser("creator@example.com", "creator", "secret123");
        JEvents event = seedEvent(creator, "Event", true);
        HttpResponse<String> registered = register("other@example.com", "secret123", "Other", "other");
        HttpHeaders auth = withCookies(new HttpHeaders(), ACCESS_COOKIE, accessToken(registered));

        HttpResponse<String> response = call(HttpMethod.GET, "/api/events/" + event.getId() + "/stats", null, auth);

        assertThat(response.statusCode()).isEqualTo(HttpStatus.FORBIDDEN.value());
        assertThat(readBody(response, ApiError.class).error()).isEqualTo("NOT_AN_ORGANIZER");
    }

    @Test
    void getEventStats_returnsNotFoundForUnknownEvent() {
        HttpResponse<String> registered = register("owner@example.com", "secret123", "Owner", "owner");
        HttpHeaders auth = withCookies(new HttpHeaders(), ACCESS_COOKIE, accessToken(registered));

        HttpResponse<String> response =
                call(HttpMethod.GET, "/api/events/" + UUID.randomUUID() + "/stats", null, auth);

        assertThat(response.statusCode()).isEqualTo(HttpStatus.NOT_FOUND.value());
        assertThat(readBody(response, ApiError.class).error()).isEqualTo("EVENT_NOT_FOUND");
    }

    @Test
    void getEventStats_requiresAuthentication() {
        JUsers creator = seedUser("creator@example.com", "creator", "secret123");
        JEvents event = seedEvent(creator, "Event", true);

        HttpResponse<String> response =
                call(HttpMethod.GET, "/api/events/" + event.getId() + "/stats", null, new HttpHeaders());

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

    private JEventPaymentMethod seedPaymentMethod(JEvents event) {
        JEventPaymentMethod method = new JEventPaymentMethod();
        method.setEvent(event);
        method.setPaymentType(PaymentType.MOBILE_MONEY);
        method.setProviderName("M-Pesa");
        method.setAccountName("Jane Doe");
        method.setAccountIdentifier("+261340000000");
        method.setActive(true);
        return paymentMethods.saveAndFlush(method);
    }

    private void seedPendingOrder(JEvents event, JUsers owner, JEventPaymentMethod method) {
        JTicketOrder order = new JTicketOrder();
        order.setOwner(owner);
        order.setEvent(event);
        order.setPaymentMethod(method);
        order.setPriceUnit("USD");
        order.setOrderStatus(OrderStatus.PENDING);
        orders.saveAndFlush(order);
    }
}
