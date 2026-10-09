package mg.improve.attend.controller;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.http.Cookie;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import mg.improve.attend.TestcontainersConfiguration;
import mg.improve.attend.domain.dto.request.LoginRequest;
import mg.improve.attend.domain.dto.request.RegisterRequest;
import mg.improve.attend.domain.dto.response.UserResponse;
import mg.improve.attend.exceptions.ApiError;
import mg.improve.attend.exceptions.ErrorDetail;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockCookie;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class AuthControllerIT extends IntegrationTestSupport {

    // ------------------------------------------------------------------
    // POST /api/auth/register
    // ------------------------------------------------------------------

    @Test
    void register_createsUserAndSetsAuthCookies() {
        HttpResponse<String> response =
                register("user@example.com", "secret123", "Test User", "testuser");

        assertThat(response.statusCode()).isEqualTo(HttpStatus.CREATED.value());
        UserResponse body = readBody(response, UserResponse.class);
        assertThat(body.id()).isNotNull();
        assertThat(body.email()).isEqualTo("user@example.com");
        assertThat(body.fullName()).isEqualTo("Test User");
        assertThat(body.username()).isEqualTo("testuser");
        assertThat(body.createdAt()).isNotNull();

        MockCookie access = cookie(response, ACCESS_COOKIE);
        MockCookie refresh = cookie(response, REFRESH_COOKIE);
        assertThat(access).isNotNull();
        assertThat(access.isHttpOnly()).isTrue();
        assertThat(access.getPath()).isEqualTo("/");
        assertThat(refresh).isNotNull();
        assertThat(refresh.isHttpOnly()).isTrue();
        assertThat(refresh.getPath()).isEqualTo("/");

        var persisted = users.findByEmail("user@example.com").orElseThrow();
        assertThat(persisted.getPasswordHash()).isNotEqualTo("secret123").startsWith("$2");

        UUID refreshOwnerId = jdbcTemplate.queryForObject(
                "select id_user from refresh_token where token_hash = ?", UUID.class,
                jwtService.hashRefreshToken(refresh.getValue()));
        assertThat(refreshOwnerId).isEqualTo(persisted.getId());
    }

    @Test
    void register_rejectsDuplicateEmail() {
        seedUser("taken@example.com", "firstuser", "secret123");

        HttpResponse<String> response = call(HttpMethod.POST, "/api/auth/register",
                new RegisterRequest("taken@example.com", "secret123", "Test User", "otheruser", null),
                json());

        assertThat(response.statusCode()).isEqualTo(HttpStatus.CONFLICT.value());
        ApiError error = readBody(response, ApiError.class);
        assertThat(error.error()).isEqualTo("EMAIL_TAKEN");
        assertThat(error.path()).isEqualTo("/api/auth/register");
        assertThat(users.findByUsername("otheruser")).isEmpty();
    }

    @Test
    void register_rejectsDuplicateUsername() {
        seedUser("first@example.com", "takenname", "secret123");

        HttpResponse<String> response = call(HttpMethod.POST, "/api/auth/register",
                new RegisterRequest("other@example.com", "secret123", "Test User", "takenname", null),
                json());

        assertThat(response.statusCode()).isEqualTo(HttpStatus.CONFLICT.value());
        assertThat(readBody(response, ApiError.class).error()).isEqualTo("USERNAME_TAKEN");
        assertThat(users.findByEmail("other@example.com")).isEmpty();
    }

    @Test
    void register_rejectsInvalidBodyWithFieldDetails() {
        HttpResponse<String> response = call(HttpMethod.POST, "/api/auth/register",
                new RegisterRequest("not-an-email", "short", "", "x", null), json());

        assertThat(response.statusCode()).isEqualTo(HttpStatus.BAD_REQUEST.value());
        ApiError error = readBody(response, ApiError.class);
        assertThat(error.error()).isEqualTo("VALIDATION_ERROR");
        assertThat(error.details())
                .extracting(ErrorDetail::field)
                .contains("email", "password", "fullName", "username");
        assertThat(users.count()).isZero();
    }

    // ------------------------------------------------------------------
    // POST /api/auth/login
    // ------------------------------------------------------------------

    @Test
    void login_returnsUserAndCookiesForValidCredentials() {
        seedUser("user@example.com", "testuser", "secret123");

        HttpResponse<String> response = login("user@example.com", "secret123");

        assertThat(response.statusCode()).isEqualTo(HttpStatus.OK.value());
        assertThat(readBody(response, UserResponse.class).email()).isEqualTo("user@example.com");
        assertThat(cookie(response, ACCESS_COOKIE)).isNotNull();
        assertThat(cookie(response, REFRESH_COOKIE)).isNotNull();
    }

    @Test
    void login_rejectsWrongPassword() {
        seedUser("user@example.com", "testuser", "secret123");

        HttpResponse<String> response =
                call(HttpMethod.POST, "/api/auth/login", new LoginRequest("user@example.com", "wrong"), json());

        assertThat(response.statusCode()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
        assertThat(readBody(response, ApiError.class).error()).isEqualTo("INVALID_CREDENTIALS");
    }

    @Test
    void login_rejectsUnknownEmailWithSameErrorAsWrongPassword() {
        HttpResponse<String> response =
                call(HttpMethod.POST, "/api/auth/login", new LoginRequest("nobody@example.com", "secret123"), json());

        assertThat(response.statusCode()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
        assertThat(readBody(response, ApiError.class).error()).isEqualTo("INVALID_CREDENTIALS");
    }

    // ------------------------------------------------------------------
    // POST /api/auth/refresh
    // ------------------------------------------------------------------

    @Test
    void refresh_reusesTokenWhenFarFromExpiry() {
        HttpResponse<String> registered =
                register("user@example.com", "secret123", "Test User", "testuser");
        String originalRefresh = refreshToken(registered);

        HttpResponse<String> response = call(HttpMethod.POST, "/api/auth/refresh", null,
                withCookies(new HttpHeaders(), REFRESH_COOKIE, originalRefresh));

        assertThat(response.statusCode()).isEqualTo(HttpStatus.NO_CONTENT.value());
        assertThat(cookieValue(response, ACCESS_COOKIE)).isNotNull();
        assertThat(cookieValue(response, REFRESH_COOKIE)).isNull();

        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from refresh_token where revoked_at is null", Integer.class)).isEqualTo(1);
    }

    @Test
    void refresh_rotatesNearExpiryTokenAndThenDetectsReuse() {
        HttpResponse<String> registered =
                register("user@example.com", "secret123", "Test User", "testuser");
        String originalRefresh = refreshToken(registered);

        UUID tokenId = jdbcTemplate.queryForObject(
                "select id from refresh_token where token_hash = ?", UUID.class,
                jwtService.hashRefreshToken(originalRefresh));
        jdbcTemplate.update("update refresh_token set expires_at = ? where id = ?",
                java.sql.Timestamp.from(Instant.now().plus(Duration.ofHours(1))), tokenId);

        HttpResponse<String> rotated = call(HttpMethod.POST, "/api/auth/refresh", null,
                withCookies(new HttpHeaders(), REFRESH_COOKIE, originalRefresh));
        assertThat(rotated.statusCode()).isEqualTo(HttpStatus.NO_CONTENT.value());
        assertThat(cookieValue(rotated, REFRESH_COOKIE)).isNotNull();

        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from refresh_token where id = ? and revoked_at is not null",
                Integer.class, tokenId)).isEqualTo(1);
        UUID replacementId = jdbcTemplate.queryForObject(
                "select replaced_by from refresh_token where id = ?", UUID.class, tokenId);
        assertThat(replacementId).isNotNull();

        HttpResponse<String> reuse = call(HttpMethod.POST, "/api/auth/refresh", null,
                withCookies(new HttpHeaders(), REFRESH_COOKIE, originalRefresh));
        assertThat(reuse.statusCode()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
        assertThat(readBody(reuse, ApiError.class).error()).isEqualTo("REFRESH_TOKEN_REUSED");

        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from refresh_token where id = ? and revoked_at is not null",
                Integer.class, replacementId)).isEqualTo(1);
    }

    @Test
    void refresh_rejectsMissingCookie() {
        HttpResponse<String> response =
                call(HttpMethod.POST, "/api/auth/refresh", null, new HttpHeaders());

        assertThat(response.statusCode()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
        assertThat(readBody(response, ApiError.class).error()).isEqualTo("REFRESH_TOKEN_INVALID");
    }

    // ------------------------------------------------------------------
    // POST /api/auth/logout
    // ------------------------------------------------------------------

    @Test
    void logout_revokesRefreshTokenAndClearsCookies() {
        HttpResponse<String> registered =
                register("user@example.com", "secret123", "Test User", "testuser");
        HttpHeaders headers = withCookies(new HttpHeaders(), ACCESS_COOKIE, accessToken(registered));
        headers.add(HttpHeaders.COOKIE, REFRESH_COOKIE + "=" + refreshToken(registered));

        HttpResponse<String> response = call(HttpMethod.POST, "/api/auth/logout", null, headers);

        assertThat(response.statusCode()).isEqualTo(HttpStatus.NO_CONTENT.value());
        List<MockCookie> cleared = cookies(response);
        assertThat(cleared).extracting(Cookie::getName).contains(ACCESS_COOKIE, REFRESH_COOKIE);
        assertThat(cleared).allSatisfy(c -> assertThat(c.getMaxAge()).isZero());

        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from refresh_token where revoked_at is null", Integer.class)).isZero();
    }

    @Test
    void logout_requiresAuthentication() {
        HttpResponse<String> response = call(HttpMethod.POST, "/api/auth/logout", null, json());

        assertThat(response.statusCode()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
        assertThat(readBody(response, ApiError.class).error()).isEqualTo("UNAUTHORIZED");
    }
}
