package mg.improve.attend.controller;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.http.Cookie;
import java.math.BigDecimal;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import mg.improve.attend.TestcontainersConfiguration;
import mg.improve.attend.domain.dto.request.ChangePasswordRequest;
import mg.improve.attend.domain.dto.request.LoginRequest;
import mg.improve.attend.domain.dto.request.UserUpdateRequest;
import mg.improve.attend.domain.dto.response.PageUserResponse;
import mg.improve.attend.domain.dto.response.UserMeResponse;
import mg.improve.attend.domain.dto.response.UserResponse;
import mg.improve.attend.domain.enums.EventRole;
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
import org.springframework.mock.web.MockCookie;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class UserControllerIT extends IntegrationTestSupport {

    @Autowired
    private JEventsRepository events;

    @Autowired
    private JTicketTierRepository tiers;

    @Autowired
    private JEventOrganizerRepository organizers;

    @Autowired
    private JTicketRepository tickets;

    // ------------------------------------------------------------------
    // GET /api/users/me
    // ------------------------------------------------------------------

    @Test
    void getCurrentUser_returnsProfileWhenAuthenticated() {
        HttpResponse<String> registered =
                register("user@example.com", "secret123", "Test User", "testuser");
        HttpHeaders headers = withCookies(new HttpHeaders(), ACCESS_COOKIE, accessToken(registered));

        HttpResponse<String> response = call(HttpMethod.GET, "/api/users/me", null, headers);

        assertThat(response.statusCode()).isEqualTo(HttpStatus.OK.value());
        UserMeResponse body = readBody(response, UserMeResponse.class);
        assertThat(body.email()).isEqualTo("user@example.com");
        assertThat(body.username()).isEqualTo("testuser");
        assertThat(body.createdAt()).isNotNull();
        assertThat(body.updatedAt()).isNotNull();
    }

    @Test
    void getCurrentUser_requiresAuthentication() {
        HttpResponse<String> response = call(HttpMethod.GET, "/api/users/me", null, new HttpHeaders());

        assertThat(response.statusCode()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
        assertThat(readBody(response, ApiError.class).error()).isEqualTo("UNAUTHORIZED");
    }

    // ------------------------------------------------------------------
    // GET /api/users
    // ------------------------------------------------------------------

    @Test
    void searchUsers_matchesUsernamePartiallyAndCaseInsensitively() {
        HttpResponse<String> registered =
                register("caller@example.com", "secret123", "Caller", "calleruser");
        seedUser("jane@example.com", "janedoe", "secret123");
        HttpHeaders headers = withCookies(new HttpHeaders(), ACCESS_COOKIE, accessToken(registered));

        HttpResponse<String> response = call(HttpMethod.GET, "/api/users?username=JANE", null, headers);

        assertThat(response.statusCode()).isEqualTo(HttpStatus.OK.value());
        PageUserResponse body = readBody(response, PageUserResponse.class);
        assertThat(body.totalElements()).isEqualTo(1);
        assertThat(body.content()).extracting(UserResponse::username).containsExactly("janedoe");
        assertThat(body.content()).extracting(UserResponse::email).doesNotContain("caller@example.com");
    }

    @Test
    void searchUsers_matchesEmailPartially() {
        HttpResponse<String> registered =
                register("caller@example.com", "secret123", "Caller", "calleruser");
        seedUser("unique-search@example.com", "uniquesearch", "secret123");
        HttpHeaders headers = withCookies(new HttpHeaders(), ACCESS_COOKIE, accessToken(registered));

        HttpResponse<String> response =
                call(HttpMethod.GET, "/api/users?email=unique-search", null, headers);

        assertThat(response.statusCode()).isEqualTo(HttpStatus.OK.value());
        PageUserResponse body = readBody(response, PageUserResponse.class);
        assertThat(body.content()).extracting(UserResponse::email)
                .containsExactly("unique-search@example.com");
    }

    @Test
    void searchUsers_withBothParamsReturnsUnion() {
        HttpResponse<String> registered =
                register("caller@example.com", "secret123", "Caller", "calleruser");
        seedUser("alpha@example.com", "alphauser", "secret123");
        seedUser("beta@other.com", "betauser", "secret123");
        HttpHeaders headers = withCookies(new HttpHeaders(), ACCESS_COOKIE, accessToken(registered));

        HttpResponse<String> response =
                call(HttpMethod.GET, "/api/users?username=alpha&email=beta", null, headers);

        assertThat(response.statusCode()).isEqualTo(HttpStatus.OK.value());
        PageUserResponse body = readBody(response, PageUserResponse.class);
        assertThat(body.totalElements()).isEqualTo(2);
        assertThat(body.content()).extracting(UserResponse::username)
                .containsExactlyInAnyOrder("alphauser", "betauser");
    }

    @Test
    void searchUsers_paginates() {
        HttpResponse<String> registered =
                register("caller@example.com", "secret123", "Caller", "calleruser");
        seedUser("one@example.com", "searcher1", "secret123");
        seedUser("two@example.com", "searcher2", "secret123");
        seedUser("three@example.com", "searcher3", "secret123");
        HttpHeaders headers = withCookies(new HttpHeaders(), ACCESS_COOKIE, accessToken(registered));

        HttpResponse<String> response =
                call(HttpMethod.GET, "/api/users?username=searcher&page=0&size=2", null, headers);

        assertThat(response.statusCode()).isEqualTo(HttpStatus.OK.value());
        PageUserResponse body = readBody(response, PageUserResponse.class);
        assertThat(body.page()).isZero();
        assertThat(body.size()).isEqualTo(2);
        assertThat(body.totalElements()).isEqualTo(3);
        assertThat(body.totalPages()).isEqualTo(2);
        assertThat(body.content()).hasSize(2);
    }

    @Test
    void searchUsers_requiresAtLeastOneFilter() {
        HttpResponse<String> registered =
                register("caller@example.com", "secret123", "Caller", "calleruser");
        HttpHeaders headers = withCookies(new HttpHeaders(), ACCESS_COOKIE, accessToken(registered));

        HttpResponse<String> response = call(HttpMethod.GET, "/api/users", null, headers);

        assertThat(response.statusCode()).isEqualTo(HttpStatus.BAD_REQUEST.value());
        ApiError error = readBody(response, ApiError.class);
        assertThat(error.error()).isEqualTo("VALIDATION_ERROR");
        assertThat(error.details()).extracting(ErrorDetail::field).contains("username");
    }

    @Test
    void searchUsers_rejectsInvalidPagination() {
        HttpResponse<String> registered =
                register("caller@example.com", "secret123", "Caller", "calleruser");
        HttpHeaders headers = withCookies(new HttpHeaders(), ACCESS_COOKIE, accessToken(registered));

        HttpResponse<String> response =
                call(HttpMethod.GET, "/api/users?username=caller&page=-1&size=101", null, headers);

        assertThat(response.statusCode()).isEqualTo(HttpStatus.BAD_REQUEST.value());
        ApiError error = readBody(response, ApiError.class);
        assertThat(error.error()).isEqualTo("VALIDATION_ERROR");
        assertThat(error.details()).extracting(ErrorDetail::field).contains("page", "size");
    }

    @Test
    void searchUsers_requiresAuthentication() {
        HttpResponse<String> response =
                call(HttpMethod.GET, "/api/users?username=anyone", null, new HttpHeaders());

        assertThat(response.statusCode()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
        assertThat(readBody(response, ApiError.class).error()).isEqualTo("UNAUTHORIZED");
    }

    // ------------------------------------------------------------------
    // PATCH /api/users/me
    // ------------------------------------------------------------------

    @Test
    void updateCurrentUser_persistsProvidedFields() {
        HttpResponse<String> registered =
                register("user@example.com", "secret123", "Test User", "testuser");
        HttpHeaders headers = withCookies(new HttpHeaders(), ACCESS_COOKIE, accessToken(registered));

        HttpResponse<String> response = call(HttpMethod.PATCH, "/api/users/me",
                new UserUpdateRequest("Renamed User", "renameduser", LocalDate.of(1995, 5, 5)), headers);

        assertThat(response.statusCode()).isEqualTo(HttpStatus.OK.value());
        UserMeResponse body = readBody(response, UserMeResponse.class);
        assertThat(body.fullName()).isEqualTo("Renamed User");
        assertThat(body.username()).isEqualTo("renameduser");
        assertThat(body.dateBirth()).isEqualTo(LocalDate.of(1995, 5, 5));

        JUsers persisted = users.findByEmail("user@example.com").orElseThrow();
        assertThat(persisted.getFullName()).isEqualTo("Renamed User");
        assertThat(persisted.getUsername()).isEqualTo("renameduser");
    }

    @Test
    void updateCurrentUser_rejectsUsernameAlreadyTaken() {
        HttpResponse<String> registered =
                register("user@example.com", "secret123", "Test User", "testuser");
        seedUser("other@example.com", "takenname", "secret123");
        HttpHeaders headers = withCookies(new HttpHeaders(), ACCESS_COOKIE, accessToken(registered));

        HttpResponse<String> response = call(HttpMethod.PATCH, "/api/users/me",
                new UserUpdateRequest(null, "takenname", null), headers);

        assertThat(response.statusCode()).isEqualTo(HttpStatus.CONFLICT.value());
        assertThat(readBody(response, ApiError.class).error()).isEqualTo("USERNAME_TAKEN");
    }

    @Test
    void updateCurrentUser_rejectsInvalidBody() {
        HttpResponse<String> registered =
                register("user@example.com", "secret123", "Test User", "testuser");
        HttpHeaders headers = withCookies(new HttpHeaders(), ACCESS_COOKIE, accessToken(registered));

        HttpResponse<String> response = call(HttpMethod.PATCH, "/api/users/me",
                new UserUpdateRequest("", null, LocalDate.now().plusDays(1)), headers);

        assertThat(response.statusCode()).isEqualTo(HttpStatus.BAD_REQUEST.value());
        ApiError error = readBody(response, ApiError.class);
        assertThat(error.error()).isEqualTo("VALIDATION_ERROR");
        assertThat(error.details()).extracting(ErrorDetail::field).contains("fullName", "dateBirth");
    }

    // ------------------------------------------------------------------
    // POST /api/users/me/password
    // ------------------------------------------------------------------

    @Test
    void changePassword_revokesOldSessionsAndIssuesANewOne() {
        HttpResponse<String> registered =
                register("user@example.com", "secret123", "Test User", "testuser");
        HttpHeaders headers = withCookies(new HttpHeaders(), ACCESS_COOKIE, accessToken(registered));

        HttpResponse<String> response = call(HttpMethod.POST, "/api/users/me/password",
                new ChangePasswordRequest("secret123", "newsecret123"), headers);

        assertThat(response.statusCode()).isEqualTo(HttpStatus.NO_CONTENT.value());
        assertThat(cookie(response, ACCESS_COOKIE)).isNotNull();
        assertThat(cookie(response, REFRESH_COOKIE)).isNotNull();

        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from refresh_token where revoked_at is null", Integer.class)).isEqualTo(1);

        assertThat(login("user@example.com", "newsecret123").statusCode())
                .isEqualTo(HttpStatus.OK.value());
        HttpResponse<String> oldPassword = call(HttpMethod.POST, "/api/auth/login",
                new LoginRequest("user@example.com", "secret123"), json());
        assertThat(oldPassword.statusCode()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
    }

    @Test
    void changePassword_rejectsWrongCurrentPassword() {
        HttpResponse<String> registered =
                register("user@example.com", "secret123", "Test User", "testuser");
        HttpHeaders headers = withCookies(new HttpHeaders(), ACCESS_COOKIE, accessToken(registered));

        HttpResponse<String> response = call(HttpMethod.POST, "/api/users/me/password",
                new ChangePasswordRequest("wrong-password", "newsecret123"), headers);

        assertThat(response.statusCode()).isEqualTo(HttpStatus.BAD_REQUEST.value());
        ApiError error = readBody(response, ApiError.class);
        assertThat(error.error()).isEqualTo("VALIDATION_ERROR");
        assertThat(error.details()).extracting(ErrorDetail::field).containsExactly("currentPassword");

        // The password is untouched: the original still works.
        assertThat(login("user@example.com", "secret123").statusCode()).isEqualTo(HttpStatus.OK.value());
    }

    // ------------------------------------------------------------------
    // DELETE /api/users/me
    // ------------------------------------------------------------------

    @Test
    void deleteMe_removesUserAndRejectsTheOldAccessToken() {
        HttpResponse<String> registered =
                register("user@example.com", "secret123", "Test User", "testuser");
        HttpHeaders headers = withCookies(new HttpHeaders(), ACCESS_COOKIE, accessToken(registered));

        HttpResponse<String> response = call(HttpMethod.DELETE, "/api/users/me", null, headers);

        assertThat(response.statusCode()).isEqualTo(HttpStatus.NO_CONTENT.value());
        List<MockCookie> cleared = cookies(response);
        assertThat(cleared).extracting(Cookie::getName).contains(ACCESS_COOKIE, REFRESH_COOKIE);
        assertThat(cleared).allSatisfy(c -> assertThat(c.getMaxAge()).isZero());
        assertThat(users.findByEmail("user@example.com")).isEmpty();

        HttpHeaders staleHeaders = withCookies(new HttpHeaders(), ACCESS_COOKIE, accessToken(registered));
        HttpResponse<String> stale = call(HttpMethod.GET, "/api/users/me", null, staleHeaders);
        assertThat(stale.statusCode()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
    }

    @Test
    void deleteMe_cascadesCreatedEventsAndTheirTickets() {
        HttpResponse<String> registered =
                register("owner@example.com", "secret123", "Event Owner", "eventowner");
        JUsers owner = users.findByEmail("owner@example.com").orElseThrow();
        JUsers other = seedUser("other@example.com", "otheruser", "secret123");

        JEvents event = new JEvents();
        event.setTitle("Integration Event");
        event.setStartTime(Instant.now().plus(Duration.ofDays(1)));
        event.setEndTime(Instant.now().plus(Duration.ofDays(2)));
        events.saveAndFlush(event);

        JTicketTier tier = new JTicketTier();
        tier.setEvent(event);
        tier.setTierName("Standard");
        tier.setPrice(BigDecimal.TEN);
        tier.setCapacity(100);
        tiers.saveAndFlush(tier);

        JEventOrganizer organizer = new JEventOrganizer();
        organizer.setUser(owner);
        organizer.setEvent(event);
        organizer.setEventRole(EventRole.CREATOR);
        organizers.saveAndFlush(organizer);

        JTicket ticket = new JTicket();
        ticket.setOwner(other);
        ticket.setTier(tier);
        ticket.setPricePaid(BigDecimal.TEN);
        ticket.setTicketStatus(TicketStatus.VALID);
        tickets.saveAndFlush(ticket);

        HttpHeaders headers = withCookies(new HttpHeaders(), ACCESS_COOKIE, accessToken(registered));
        HttpResponse<String> response = call(HttpMethod.DELETE, "/api/users/me", null, headers);
        assertThat(response.statusCode()).isEqualTo(HttpStatus.NO_CONTENT.value());

        assertThat(events.findById(event.getId())).isEmpty();
        assertThat(tiers.findById(tier.getId())).isEmpty();
        assertThat(organizers.findById(organizer.getId())).isEmpty();
        assertThat(tickets.findById(ticket.getId())).isEmpty();
        assertThat(users.findById(owner.getId())).isEmpty();
        assertThat(users.findById(other.getId())).isPresent();
    }
}
