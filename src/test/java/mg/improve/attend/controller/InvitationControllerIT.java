package mg.improve.attend.controller;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import mg.improve.attend.TestcontainersConfiguration;
import mg.improve.attend.domain.dto.request.InvitationCreateRequest;
import mg.improve.attend.domain.dto.request.InvitationUpdateRequest;
import mg.improve.attend.domain.dto.response.InvitationResponse;
import mg.improve.attend.domain.dto.response.PageInvitation;
import mg.improve.attend.domain.enums.EventRole;
import mg.improve.attend.domain.enums.EventStatus;
import mg.improve.attend.domain.enums.InvitationStatus;
import mg.improve.attend.exceptions.ApiError;
import mg.improve.attend.repository.JEventOrganizerRepository;
import mg.improve.attend.repository.JEventsRepository;
import mg.improve.attend.repository.JInvitationRepository;
import mg.improve.attend.repository.entities.JEventOrganizer;
import mg.improve.attend.repository.entities.JEvents;
import mg.improve.attend.repository.entities.JInvitation;
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
class InvitationControllerIT extends IntegrationTestSupport {

    @Autowired
    private JEventsRepository events;

    @Autowired
    private JEventOrganizerRepository organizers;

    @Autowired
    private JInvitationRepository invitations;

    // ------------------------------------------------------------------
    // POST /api/events/{eventId}/invitations
    // ------------------------------------------------------------------

    @Test
    void createInvitation_createsPendingInvitationForCreator() {
        HttpResponse<String> creatorReg = register("creator@example.com", "secret123", "Creator", "creator");
        JUsers creator = users.findByEmail("creator@example.com").orElseThrow();
        JEvents event = seedEvent(creator, EventStatus.OK, Instant.now().plus(Duration.ofDays(1)));
        register("bob@example.com", "secret123", "Bob", "bob");
        JUsers bob = users.findByEmail("bob@example.com").orElseThrow();
        HttpHeaders auth = withCookies(new HttpHeaders(), ACCESS_COOKIE, accessToken(creatorReg));

        HttpResponse<String> response = call(HttpMethod.POST, "/api/events/" + event.getId() + "/invitations",
                new InvitationCreateRequest("bob", null), auth);

        assertThat(response.statusCode()).isEqualTo(HttpStatus.CREATED.value());
        InvitationResponse body = readBody(response, InvitationResponse.class);
        assertThat(body.id()).isNotNull();
        assertThat(body.event().id()).isEqualTo(event.getId());
        assertThat(body.sender().id()).isEqualTo(creator.getId());
        assertThat(body.sender().username()).isEqualTo("creator");
        assertThat(body.receiver().id()).isEqualTo(bob.getId());
        assertThat(body.receiver().username()).isEqualTo("bob");
        assertThat(body.invitationStatus()).isEqualTo(InvitationStatus.PENDING);
        assertThat(body.createdAt()).isNotNull();

        JInvitation stored = invitations.findById(body.id()).orElseThrow();
        assertThat(stored.getSender().getId()).isEqualTo(creator.getId());
        assertThat(stored.getReceiver().getId()).isEqualTo(bob.getId());
        assertThat(stored.getInvitationStatus()).isEqualTo(InvitationStatus.PENDING);
    }

    @Test
    void createInvitation_requiresAuthentication() {
        JUsers creator = seedUser("creator@example.com", "creator", "secret123");
        JEvents event = seedEvent(creator, EventStatus.OK, Instant.now().plus(Duration.ofDays(1)));

        HttpResponse<String> response = call(HttpMethod.POST, "/api/events/" + event.getId() + "/invitations",
                new InvitationCreateRequest("creator", null), new HttpHeaders());

        assertThat(response.statusCode()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
    }

    @Test
    void createInvitation_rejectsNonCreator() {
        JUsers creator = seedUser("creator@example.com", "creator", "secret123");
        JEvents event = seedEvent(creator, EventStatus.OK, Instant.now().plus(Duration.ofDays(1)));
        HttpResponse<String> adminReg = register("admin@example.com", "secret123", "Admin", "admin");
        JUsers admin = users.findByEmail("admin@example.com").orElseThrow();
        seedOrganizer(admin, event, EventRole.ADMIN);
        HttpHeaders auth = withCookies(new HttpHeaders(), ACCESS_COOKIE, accessToken(adminReg));

        HttpResponse<String> response = call(HttpMethod.POST, "/api/events/" + event.getId() + "/invitations",
                new InvitationCreateRequest("creator", null), auth);

        assertThat(response.statusCode()).isEqualTo(HttpStatus.FORBIDDEN.value());
        assertThat(readBody(response, ApiError.class).error()).isEqualTo("NOT_THE_CREATOR");
    }

    @Test
    void createInvitation_returnsNotFoundForUnknownEvent() {
        HttpResponse<String> creatorReg = register("creator@example.com", "secret123", "Creator", "creator");
        HttpHeaders auth = withCookies(new HttpHeaders(), ACCESS_COOKIE, accessToken(creatorReg));

        HttpResponse<String> response = call(HttpMethod.POST, "/api/events/" + UUID.randomUUID() + "/invitations",
                new InvitationCreateRequest("creator", null), auth);

        assertThat(response.statusCode()).isEqualTo(HttpStatus.NOT_FOUND.value());
        assertThat(readBody(response, ApiError.class).error()).isEqualTo("EVENT_NOT_FOUND");
    }

    @Test
    void createInvitation_returnsNotFoundForUnknownUser() {
        HttpResponse<String> creatorReg = register("creator@example.com", "secret123", "Creator", "creator");
        JUsers creator = users.findByEmail("creator@example.com").orElseThrow();
        JEvents event = seedEvent(creator, EventStatus.OK, Instant.now().plus(Duration.ofDays(1)));
        HttpHeaders auth = withCookies(new HttpHeaders(), ACCESS_COOKIE, accessToken(creatorReg));

        HttpResponse<String> response = call(HttpMethod.POST, "/api/events/" + event.getId() + "/invitations",
                new InvitationCreateRequest("ghost", null), auth);

        assertThat(response.statusCode()).isEqualTo(HttpStatus.NOT_FOUND.value());
        assertThat(readBody(response, ApiError.class).error()).isEqualTo("USER_NOT_FOUND");
    }

    @Test
    void createInvitation_rejectsInvitingCreator() {
        HttpResponse<String> creatorReg = register("creator@example.com", "secret123", "Creator", "creator");
        JUsers creator = users.findByEmail("creator@example.com").orElseThrow();
        JEvents event = seedEvent(creator, EventStatus.OK, Instant.now().plus(Duration.ofDays(1)));
        HttpHeaders auth = withCookies(new HttpHeaders(), ACCESS_COOKIE, accessToken(creatorReg));

        HttpResponse<String> response = call(HttpMethod.POST, "/api/events/" + event.getId() + "/invitations",
                new InvitationCreateRequest("creator", null), auth);

        assertThat(response.statusCode()).isEqualTo(HttpStatus.CONFLICT.value());
        assertThat(readBody(response, ApiError.class).error()).isEqualTo("CANNOT_INVITE_CREATOR");
    }

    @Test
    void createInvitation_rejectsExistingOrganizer() {
        HttpResponse<String> creatorReg = register("creator@example.com", "secret123", "Creator", "creator");
        JUsers creator = users.findByEmail("creator@example.com").orElseThrow();
        JEvents event = seedEvent(creator, EventStatus.OK, Instant.now().plus(Duration.ofDays(1)));
        register("admin@example.com", "secret123", "Admin", "admin");
        JUsers admin = users.findByEmail("admin@example.com").orElseThrow();
        seedOrganizer(admin, event, EventRole.ADMIN);
        HttpHeaders auth = withCookies(new HttpHeaders(), ACCESS_COOKIE, accessToken(creatorReg));

        HttpResponse<String> response = call(HttpMethod.POST, "/api/events/" + event.getId() + "/invitations",
                new InvitationCreateRequest("admin", null), auth);

        assertThat(response.statusCode()).isEqualTo(HttpStatus.CONFLICT.value());
        assertThat(readBody(response, ApiError.class).error()).isEqualTo("ALREADY_ORGANIZER");
    }

    @Test
    void createInvitation_rejectsDuplicatePendingInvitation() {
        HttpResponse<String> creatorReg = register("creator@example.com", "secret123", "Creator", "creator");
        JUsers creator = users.findByEmail("creator@example.com").orElseThrow();
        JEvents event = seedEvent(creator, EventStatus.OK, Instant.now().plus(Duration.ofDays(1)));
        JUsers bob = seedUser("bob@example.com", "bob", "secret123");
        seedInvitation(creator, bob, event, InvitationStatus.PENDING);
        HttpHeaders auth = withCookies(new HttpHeaders(), ACCESS_COOKIE, accessToken(creatorReg));

        HttpResponse<String> response = call(HttpMethod.POST, "/api/events/" + event.getId() + "/invitations",
                new InvitationCreateRequest("bob", null), auth);

        assertThat(response.statusCode()).isEqualTo(HttpStatus.CONFLICT.value());
        assertThat(readBody(response, ApiError.class).error()).isEqualTo("ALREADY_INVITED");
    }

    @Test
    void createInvitation_rejectsInvalidBody() {
        HttpResponse<String> creatorReg = register("creator@example.com", "secret123", "Creator", "creator");
        JUsers creator = users.findByEmail("creator@example.com").orElseThrow();
        JEvents event = seedEvent(creator, EventStatus.OK, Instant.now().plus(Duration.ofDays(1)));
        HttpHeaders auth = withCookies(new HttpHeaders(), ACCESS_COOKIE, accessToken(creatorReg));

        HttpResponse<String> response = call(HttpMethod.POST, "/api/events/" + event.getId() + "/invitations",
                new InvitationCreateRequest(null, null), auth);

        assertThat(response.statusCode()).isEqualTo(HttpStatus.BAD_REQUEST.value());
        assertThat(readBody(response, ApiError.class).error()).isEqualTo("VALIDATION_ERROR");
    }

    // ------------------------------------------------------------------
    // GET /api/invitations
    // ------------------------------------------------------------------

    @Test
    void listInvitations_defaultsToReceived() {
        JUsers alice = seedUser("alice@example.com", "alice", "secret123");
        JUsers bob = seedUser("bob@example.com", "bob", "secret123");
        JEvents event = seedEvent(alice, EventStatus.OK, Instant.now().plus(Duration.ofDays(1)));
        JInvitation received = seedInvitation(alice, bob, event, InvitationStatus.PENDING);
        seedInvitation(bob, alice, event, InvitationStatus.PENDING);
        HttpHeaders auth = withCookies(new HttpHeaders(), ACCESS_COOKIE, accessToken(login("bob@example.com", "secret123")));

        HttpResponse<String> response = call(HttpMethod.GET, "/api/invitations", null, auth);

        assertThat(response.statusCode()).isEqualTo(HttpStatus.OK.value());
        PageInvitation body = readBody(response, PageInvitation.class);
        assertThat(body.totalElements()).isEqualTo(1);
        assertThat(body.content()).extracting(InvitationResponse::id).containsExactly(received.getId());
        assertThat(body.content().get(0).receiver().username()).isEqualTo("bob");
    }

    @Test
    void listInvitations_listsSent() {
        JUsers alice = seedUser("alice@example.com", "alice", "secret123");
        JUsers bob = seedUser("bob@example.com", "bob", "secret123");
        JEvents event = seedEvent(alice, EventStatus.OK, Instant.now().plus(Duration.ofDays(1)));
        seedInvitation(alice, bob, event, InvitationStatus.PENDING);
        JInvitation sent = seedInvitation(bob, alice, event, InvitationStatus.PENDING);
        HttpHeaders auth = withCookies(new HttpHeaders(), ACCESS_COOKIE, accessToken(login("bob@example.com", "secret123")));

        HttpResponse<String> response = call(HttpMethod.GET, "/api/invitations?direction=sent", null, auth);

        assertThat(response.statusCode()).isEqualTo(HttpStatus.OK.value());
        PageInvitation body = readBody(response, PageInvitation.class);
        assertThat(body.content()).extracting(InvitationResponse::id).containsExactly(sent.getId());
    }

    @Test
    void listInvitations_allReturnsBothSides() {
        JUsers alice = seedUser("alice@example.com", "alice", "secret123");
        JUsers bob = seedUser("bob@example.com", "bob", "secret123");
        JEvents event = seedEvent(alice, EventStatus.OK, Instant.now().plus(Duration.ofDays(1)));
        JInvitation received = seedInvitation(alice, bob, event, InvitationStatus.PENDING);
        JInvitation sent = seedInvitation(bob, alice, event, InvitationStatus.PENDING);
        HttpHeaders auth = withCookies(new HttpHeaders(), ACCESS_COOKIE, accessToken(login("bob@example.com", "secret123")));

        HttpResponse<String> response = call(HttpMethod.GET, "/api/invitations?direction=all", null, auth);

        assertThat(response.statusCode()).isEqualTo(HttpStatus.OK.value());
        PageInvitation body = readBody(response, PageInvitation.class);
        assertThat(body.content()).extracting(InvitationResponse::id)
                .containsExactlyInAnyOrder(received.getId(), sent.getId());
    }

    @Test
    void listInvitations_filtersByStatus() {
        JUsers alice = seedUser("alice@example.com", "alice", "secret123");
        JUsers bob = seedUser("bob@example.com", "bob", "secret123");
        JEvents event = seedEvent(alice, EventStatus.OK, Instant.now().plus(Duration.ofDays(1)));
        seedInvitation(alice, bob, event, InvitationStatus.PENDING);
        JInvitation accepted = seedInvitation(alice, bob, event, InvitationStatus.ACCEPTED);
        HttpHeaders auth = withCookies(new HttpHeaders(), ACCESS_COOKIE, accessToken(login("bob@example.com", "secret123")));

        HttpResponse<String> response = call(HttpMethod.GET, "/api/invitations?status=ACCEPTED", null, auth);

        assertThat(response.statusCode()).isEqualTo(HttpStatus.OK.value());
        PageInvitation body = readBody(response, PageInvitation.class);
        assertThat(body.content()).extracting(InvitationResponse::id).containsExactly(accepted.getId());
    }

    @Test
    void listInvitations_ordersByCreatedAtDescending() {
        JUsers alice = seedUser("alice@example.com", "alice", "secret123");
        JUsers bob = seedUser("bob@example.com", "bob", "secret123");
        JEvents event = seedEvent(alice, EventStatus.OK, Instant.now().plus(Duration.ofDays(1)));
        JInvitation older = seedInvitation(alice, bob, event, InvitationStatus.PENDING);
        JInvitation newer = seedInvitation(alice, bob, event, InvitationStatus.PENDING);
        // Make the ordering explicit; creation timestamps can tie at microsecond resolution.
        jdbcTemplate.update("update invitations set created_at = now() - interval '1 hour' where id = ?",
                older.getId());
        HttpHeaders auth = withCookies(new HttpHeaders(), ACCESS_COOKIE, accessToken(login("bob@example.com", "secret123")));

        HttpResponse<String> response = call(HttpMethod.GET, "/api/invitations", null, auth);

        assertThat(response.statusCode()).isEqualTo(HttpStatus.OK.value());
        PageInvitation body = readBody(response, PageInvitation.class);
        assertThat(body.content()).extracting(InvitationResponse::id)
                .containsExactly(newer.getId(), older.getId());
    }

    @Test
    void listInvitations_paginates() {
        JUsers alice = seedUser("alice@example.com", "alice", "secret123");
        JUsers bob = seedUser("bob@example.com", "bob", "secret123");
        JEvents event = seedEvent(alice, EventStatus.OK, Instant.now().plus(Duration.ofDays(1)));
        seedInvitation(alice, bob, event, InvitationStatus.PENDING);
        seedInvitation(alice, bob, event, InvitationStatus.PENDING);
        HttpHeaders auth = withCookies(new HttpHeaders(), ACCESS_COOKIE, accessToken(login("bob@example.com", "secret123")));

        HttpResponse<String> response = call(HttpMethod.GET, "/api/invitations?page=0&size=1", null, auth);

        assertThat(response.statusCode()).isEqualTo(HttpStatus.OK.value());
        PageInvitation body = readBody(response, PageInvitation.class);
        assertThat(body.page()).isZero();
        assertThat(body.size()).isEqualTo(1);
        assertThat(body.totalElements()).isEqualTo(2);
        assertThat(body.totalPages()).isEqualTo(2);
        assertThat(body.content()).hasSize(1);
    }

    @Test
    void listInvitations_rejectsInvalidDirection() {
        HttpResponse<String> authReg = register("user@example.com", "secret123", "User", "user");
        HttpHeaders auth = withCookies(new HttpHeaders(), ACCESS_COOKIE, accessToken(authReg));

        HttpResponse<String> response = call(HttpMethod.GET, "/api/invitations?direction=sideways", null, auth);

        assertThat(response.statusCode()).isEqualTo(HttpStatus.BAD_REQUEST.value());
        assertThat(readBody(response, ApiError.class).error()).isEqualTo("VALIDATION_ERROR");
    }

    @Test
    void listInvitations_rejectsInvalidPagination() {
        HttpResponse<String> authReg = register("user@example.com", "secret123", "User", "user");
        HttpHeaders auth = withCookies(new HttpHeaders(), ACCESS_COOKIE, accessToken(authReg));

        HttpResponse<String> negativePage = call(HttpMethod.GET, "/api/invitations?page=-1", null, auth);
        HttpResponse<String> zeroSize = call(HttpMethod.GET, "/api/invitations?size=0", null, auth);

        assertThat(negativePage.statusCode()).isEqualTo(HttpStatus.BAD_REQUEST.value());
        assertThat(readBody(negativePage, ApiError.class).error()).isEqualTo("VALIDATION_ERROR");
        assertThat(zeroSize.statusCode()).isEqualTo(HttpStatus.BAD_REQUEST.value());
        assertThat(readBody(zeroSize, ApiError.class).error()).isEqualTo("VALIDATION_ERROR");
    }

    @Test
    void listInvitations_requiresAuthentication() {
        HttpResponse<String> response = call(HttpMethod.GET, "/api/invitations", null, new HttpHeaders());

        assertThat(response.statusCode()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
    }

    // ------------------------------------------------------------------
    // GET /api/invitations/{invitationId}
    // ------------------------------------------------------------------

    @Test
    void getInvitation_returnsForSender() {
        JUsers alice = seedUser("alice@example.com", "alice", "secret123");
        JUsers bob = seedUser("bob@example.com", "bob", "secret123");
        JEvents event = seedEvent(alice, EventStatus.OK, Instant.now().plus(Duration.ofDays(1)));
        JInvitation invitation = seedInvitation(alice, bob, event, InvitationStatus.PENDING);
        HttpHeaders auth = withCookies(new HttpHeaders(), ACCESS_COOKIE, accessToken(login("alice@example.com", "secret123")));

        HttpResponse<String> response = call(HttpMethod.GET, "/api/invitations/" + invitation.getId(), null, auth);

        assertThat(response.statusCode()).isEqualTo(HttpStatus.OK.value());
        InvitationResponse body = readBody(response, InvitationResponse.class);
        assertThat(body.id()).isEqualTo(invitation.getId());
        assertThat(body.sender().username()).isEqualTo("alice");
        assertThat(body.receiver().username()).isEqualTo("bob");
        assertThat(body.invitationStatus()).isEqualTo(InvitationStatus.PENDING);
        assertThat(body.createdAt()).isNotNull();
    }

    @Test
    void getInvitation_returnsForReceiver() {
        JUsers alice = seedUser("alice@example.com", "alice", "secret123");
        JUsers bob = seedUser("bob@example.com", "bob", "secret123");
        JEvents event = seedEvent(alice, EventStatus.OK, Instant.now().plus(Duration.ofDays(1)));
        JInvitation invitation = seedInvitation(alice, bob, event, InvitationStatus.PENDING);
        HttpHeaders auth = withCookies(new HttpHeaders(), ACCESS_COOKIE, accessToken(login("bob@example.com", "secret123")));

        HttpResponse<String> response = call(HttpMethod.GET, "/api/invitations/" + invitation.getId(), null, auth);

        assertThat(response.statusCode()).isEqualTo(HttpStatus.OK.value());
        assertThat(readBody(response, InvitationResponse.class).id()).isEqualTo(invitation.getId());
    }

    @Test
    void getInvitation_rejectsUnrelatedCaller() {
        JUsers alice = seedUser("alice@example.com", "alice", "secret123");
        JUsers bob = seedUser("bob@example.com", "bob", "secret123");
        JEvents event = seedEvent(alice, EventStatus.OK, Instant.now().plus(Duration.ofDays(1)));
        JInvitation invitation = seedInvitation(alice, bob, event, InvitationStatus.PENDING);
        HttpResponse<String> carolReg = register("carol@example.com", "secret123", "Carol", "carol");
        HttpHeaders auth = withCookies(new HttpHeaders(), ACCESS_COOKIE, accessToken(carolReg));

        HttpResponse<String> response = call(HttpMethod.GET, "/api/invitations/" + invitation.getId(), null, auth);

        assertThat(response.statusCode()).isEqualTo(HttpStatus.FORBIDDEN.value());
        assertThat(readBody(response, ApiError.class).error()).isEqualTo("INVITATION_FORBIDDEN");
    }

    @Test
    void getInvitation_returnsNotFoundForUnknownInvitation() {
        HttpResponse<String> authReg = register("user@example.com", "secret123", "User", "user");
        HttpHeaders auth = withCookies(new HttpHeaders(), ACCESS_COOKIE, accessToken(authReg));

        HttpResponse<String> response = call(HttpMethod.GET, "/api/invitations/" + UUID.randomUUID(), null, auth);

        assertThat(response.statusCode()).isEqualTo(HttpStatus.NOT_FOUND.value());
        assertThat(readBody(response, ApiError.class).error()).isEqualTo("INVITATION_NOT_FOUND");
    }

    @Test
    void getInvitation_requiresAuthentication() {
        HttpResponse<String> response = call(HttpMethod.GET, "/api/invitations/" + UUID.randomUUID(), null,
                new HttpHeaders());

        assertThat(response.statusCode()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
    }

    // ------------------------------------------------------------------
    // PATCH /api/invitations/{invitationId}
    // ------------------------------------------------------------------

    @Test
    void respondToInvitation_acceptsAndGrantsAdmin() {
        JUsers alice = seedUser("alice@example.com", "alice", "secret123");
        JUsers bob = seedUser("bob@example.com", "bob", "secret123");
        JEvents event = seedEvent(alice, EventStatus.OK, Instant.now().plus(Duration.ofDays(1)));
        JInvitation invitation = seedInvitation(alice, bob, event, InvitationStatus.PENDING);
        HttpHeaders auth = withCookies(new HttpHeaders(), ACCESS_COOKIE, accessToken(login("bob@example.com", "secret123")));

        HttpResponse<String> response = call(HttpMethod.PATCH, "/api/invitations/" + invitation.getId(),
                new InvitationUpdateRequest(InvitationStatus.ACCEPTED), auth);

        assertThat(response.statusCode()).isEqualTo(HttpStatus.OK.value());
        assertThat(readBody(response, InvitationResponse.class).invitationStatus())
                .isEqualTo(InvitationStatus.ACCEPTED);
        assertThat(invitations.findById(invitation.getId()).orElseThrow().getInvitationStatus())
                .isEqualTo(InvitationStatus.ACCEPTED);

        JEventOrganizer organizer = organizers.findByEvent_IdAndUser_Id(event.getId(), bob.getId()).orElseThrow();
        assertThat(organizer.getEventRole()).isEqualTo(EventRole.ADMIN);
    }

    @Test
    void respondToInvitation_refusesWithoutGranting() {
        JUsers alice = seedUser("alice@example.com", "alice", "secret123");
        JUsers bob = seedUser("bob@example.com", "bob", "secret123");
        JEvents event = seedEvent(alice, EventStatus.OK, Instant.now().plus(Duration.ofDays(1)));
        JInvitation invitation = seedInvitation(alice, bob, event, InvitationStatus.PENDING);
        HttpHeaders auth = withCookies(new HttpHeaders(), ACCESS_COOKIE, accessToken(login("bob@example.com", "secret123")));

        HttpResponse<String> response = call(HttpMethod.PATCH, "/api/invitations/" + invitation.getId(),
                new InvitationUpdateRequest(InvitationStatus.DECLINED), auth);

        assertThat(response.statusCode()).isEqualTo(HttpStatus.OK.value());
        assertThat(readBody(response, InvitationResponse.class).invitationStatus())
                .isEqualTo(InvitationStatus.DECLINED);
        assertThat(organizers.findByEvent_IdAndUser_Id(event.getId(), bob.getId())).isEmpty();
    }

    @Test
    void respondToInvitation_acceptDoesNotDuplicateExistingOrganizer() {
        JUsers alice = seedUser("alice@example.com", "alice", "secret123");
        JUsers bob = seedUser("bob@example.com", "bob", "secret123");
        JEvents event = seedEvent(alice, EventStatus.OK, Instant.now().plus(Duration.ofDays(1)));
        JInvitation invitation = seedInvitation(alice, bob, event, InvitationStatus.PENDING);
        seedOrganizer(bob, event, EventRole.ADMIN);
        HttpHeaders auth = withCookies(new HttpHeaders(), ACCESS_COOKIE, accessToken(login("bob@example.com", "secret123")));

        HttpResponse<String> response = call(HttpMethod.PATCH, "/api/invitations/" + invitation.getId(),
                new InvitationUpdateRequest(InvitationStatus.ACCEPTED), auth);

        assertThat(response.statusCode()).isEqualTo(HttpStatus.OK.value());
        Long count = jdbcTemplate.queryForObject(
                "select count(*) from event_organizer where id_event = ? and id_user = ?",
                Long.class, event.getId(), bob.getId());
        assertThat(count).isEqualTo(1L);
    }

    @Test
    void respondToInvitation_rejectsNonReceiver() {
        JUsers alice = seedUser("alice@example.com", "alice", "secret123");
        JUsers bob = seedUser("bob@example.com", "bob", "secret123");
        JEvents event = seedEvent(alice, EventStatus.OK, Instant.now().plus(Duration.ofDays(1)));
        JInvitation invitation = seedInvitation(alice, bob, event, InvitationStatus.PENDING);
        HttpHeaders auth = withCookies(new HttpHeaders(), ACCESS_COOKIE, accessToken(login("alice@example.com", "secret123")));

        HttpResponse<String> response = call(HttpMethod.PATCH, "/api/invitations/" + invitation.getId(),
                new InvitationUpdateRequest(InvitationStatus.ACCEPTED), auth);

        assertThat(response.statusCode()).isEqualTo(HttpStatus.FORBIDDEN.value());
        assertThat(readBody(response, ApiError.class).error()).isEqualTo("NOT_THE_RECEIVER");
    }

    @Test
    void respondToInvitation_rejectsAlreadyDecidedInvitation() {
        JUsers alice = seedUser("alice@example.com", "alice", "secret123");
        JUsers bob = seedUser("bob@example.com", "bob", "secret123");
        JEvents event = seedEvent(alice, EventStatus.OK, Instant.now().plus(Duration.ofDays(1)));
        JInvitation invitation = seedInvitation(alice, bob, event, InvitationStatus.ACCEPTED);
        HttpHeaders auth = withCookies(new HttpHeaders(), ACCESS_COOKIE, accessToken(login("bob@example.com", "secret123")));

        HttpResponse<String> response = call(HttpMethod.PATCH, "/api/invitations/" + invitation.getId(),
                new InvitationUpdateRequest(InvitationStatus.DECLINED), auth);

        assertThat(response.statusCode()).isEqualTo(HttpStatus.CONFLICT.value());
        assertThat(readBody(response, ApiError.class).error()).isEqualTo("INVITATION_NOT_PENDING");
    }

    @Test
    void respondToInvitation_rejectsPendingDecisionBody() {
        JUsers alice = seedUser("alice@example.com", "alice", "secret123");
        JUsers bob = seedUser("bob@example.com", "bob", "secret123");
        JEvents event = seedEvent(alice, EventStatus.OK, Instant.now().plus(Duration.ofDays(1)));
        JInvitation invitation = seedInvitation(alice, bob, event, InvitationStatus.PENDING);
        HttpHeaders auth = withCookies(new HttpHeaders(), ACCESS_COOKIE, accessToken(login("bob@example.com", "secret123")));

        HttpResponse<String> response = call(HttpMethod.PATCH, "/api/invitations/" + invitation.getId(),
                new InvitationUpdateRequest(InvitationStatus.PENDING), auth);

        assertThat(response.statusCode()).isEqualTo(HttpStatus.BAD_REQUEST.value());
        assertThat(readBody(response, ApiError.class).error()).isEqualTo("VALIDATION_ERROR");
    }

    @Test
    void respondToInvitation_returnsNotFoundForUnknownInvitation() {
        HttpResponse<String> authReg = register("user@example.com", "secret123", "User", "user");
        HttpHeaders auth = withCookies(new HttpHeaders(), ACCESS_COOKIE, accessToken(authReg));

        HttpResponse<String> response = call(HttpMethod.PATCH, "/api/invitations/" + UUID.randomUUID(),
                new InvitationUpdateRequest(InvitationStatus.ACCEPTED), auth);

        assertThat(response.statusCode()).isEqualTo(HttpStatus.NOT_FOUND.value());
        assertThat(readBody(response, ApiError.class).error()).isEqualTo("INVITATION_NOT_FOUND");
    }

    @Test
    void respondToInvitation_requiresAuthentication() {
        JUsers alice = seedUser("alice@example.com", "alice", "secret123");
        JUsers bob = seedUser("bob@example.com", "bob", "secret123");
        JEvents event = seedEvent(alice, EventStatus.OK, Instant.now().plus(Duration.ofDays(1)));
        JInvitation invitation = seedInvitation(alice, bob, event, InvitationStatus.PENDING);

        HttpResponse<String> response = call(HttpMethod.PATCH, "/api/invitations/" + invitation.getId(),
                new InvitationUpdateRequest(InvitationStatus.ACCEPTED), new HttpHeaders());

        assertThat(response.statusCode()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
    }

    // ------------------------------------------------------------------
    // DELETE /api/invitations/{invitationId}
    // ------------------------------------------------------------------

    @Test
    void deleteInvitation_removesForSender() {
        JUsers alice = seedUser("alice@example.com", "alice", "secret123");
        JUsers bob = seedUser("bob@example.com", "bob", "secret123");
        JEvents event = seedEvent(alice, EventStatus.OK, Instant.now().plus(Duration.ofDays(1)));
        JInvitation invitation = seedInvitation(alice, bob, event, InvitationStatus.PENDING);
        HttpHeaders auth = withCookies(new HttpHeaders(), ACCESS_COOKIE, accessToken(login("alice@example.com", "secret123")));

        HttpResponse<String> response = call(HttpMethod.DELETE, "/api/invitations/" + invitation.getId(), null, auth);

        assertThat(response.statusCode()).isEqualTo(HttpStatus.NO_CONTENT.value());
        assertThat(invitations.findById(invitation.getId())).isEmpty();
    }

    @Test
    void deleteInvitation_rejectsNonSender() {
        JUsers alice = seedUser("alice@example.com", "alice", "secret123");
        JUsers bob = seedUser("bob@example.com", "bob", "secret123");
        JEvents event = seedEvent(alice, EventStatus.OK, Instant.now().plus(Duration.ofDays(1)));
        JInvitation invitation = seedInvitation(alice, bob, event, InvitationStatus.PENDING);
        HttpHeaders auth = withCookies(new HttpHeaders(), ACCESS_COOKIE, accessToken(login("bob@example.com", "secret123")));

        HttpResponse<String> response = call(HttpMethod.DELETE, "/api/invitations/" + invitation.getId(), null, auth);

        assertThat(response.statusCode()).isEqualTo(HttpStatus.FORBIDDEN.value());
        assertThat(readBody(response, ApiError.class).error()).isEqualTo("NOT_THE_SENDER");
        assertThat(invitations.findById(invitation.getId())).isPresent();
    }

    @Test
    void deleteInvitation_leavesAcceptedOrganizerInPlace() {
        JUsers alice = seedUser("alice@example.com", "alice", "secret123");
        JUsers bob = seedUser("bob@example.com", "bob", "secret123");
        JEvents event = seedEvent(alice, EventStatus.OK, Instant.now().plus(Duration.ofDays(1)));
        JInvitation invitation = seedInvitation(alice, bob, event, InvitationStatus.ACCEPTED);
        seedOrganizer(bob, event, EventRole.ADMIN);
        HttpHeaders auth = withCookies(new HttpHeaders(), ACCESS_COOKIE, accessToken(login("alice@example.com", "secret123")));

        HttpResponse<String> response = call(HttpMethod.DELETE, "/api/invitations/" + invitation.getId(), null, auth);

        assertThat(response.statusCode()).isEqualTo(HttpStatus.NO_CONTENT.value());
        assertThat(invitations.findById(invitation.getId())).isEmpty();
        assertThat(organizers.findByEvent_IdAndUser_Id(event.getId(), bob.getId())).isPresent();
    }

    @Test
    void deleteInvitation_returnsNotFoundForUnknownInvitation() {
        HttpResponse<String> authReg = register("user@example.com", "secret123", "User", "user");
        HttpHeaders auth = withCookies(new HttpHeaders(), ACCESS_COOKIE, accessToken(authReg));

        HttpResponse<String> response = call(HttpMethod.DELETE, "/api/invitations/" + UUID.randomUUID(), null, auth);

        assertThat(response.statusCode()).isEqualTo(HttpStatus.NOT_FOUND.value());
        assertThat(readBody(response, ApiError.class).error()).isEqualTo("INVITATION_NOT_FOUND");
    }

    @Test
    void deleteInvitation_requiresAuthentication() {
        HttpResponse<String> response = call(HttpMethod.DELETE, "/api/invitations/" + UUID.randomUUID(), null,
                new HttpHeaders());

        assertThat(response.statusCode()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
    }

    // ------------------------------------------------------------------
    // fixtures
    // ------------------------------------------------------------------

    private JEvents seedEvent(JUsers creator, EventStatus status, Instant endTime) {
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
        seedOrganizer(creator, event, EventRole.CREATOR);
        return event;
    }

    private void seedOrganizer(JUsers user, JEvents event, EventRole role) {
        JEventOrganizer organizer = new JEventOrganizer();
        organizer.setUser(user);
        organizer.setEvent(event);
        organizer.setEventRole(role);
        organizers.saveAndFlush(organizer);
    }

    private JInvitation seedInvitation(JUsers sender, JUsers receiver, JEvents event, InvitationStatus status) {
        JInvitation invitation = new JInvitation();
        invitation.setSender(sender);
        invitation.setReceiver(receiver);
        invitation.setEvent(event);
        invitation.setInvitationStatus(status);
        return invitations.saveAndFlush(invitation);
    }
}
