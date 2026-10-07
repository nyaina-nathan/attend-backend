package mg.improve.attend.controller;

import jakarta.servlet.http.Cookie;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDate;
import java.util.List;

import mg.improve.attend.domain.dto.request.LoginRequest;
import mg.improve.attend.domain.dto.request.RegisterRequest;
import mg.improve.attend.repository.JRefreshTokenRepository;
import mg.improve.attend.repository.JUsersRepository;
import mg.improve.attend.repository.entities.JUsers;
import mg.improve.attend.security.JwtService;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockCookie;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.ObjectMapper;

/**
 * Base for the controller integration tests. Real HTTP against a random port, real
 * PostgreSQL supplied by {@link mg.improve.attend.TestcontainersConfiguration} through
 * {@code @ServiceConnection}.
 * The schema is truncated before every test so cases stay independent.
 *
 * <p>The JWT settings are inlined (rather than a test {@code application.yaml}) so they always
 * override the main file's {@code ${...}} placeholders, no matter how the runner orders the
 * main and test resource directories on the classpath.
 */
@TestPropertySource(properties = {
        "spring.jwt.secret=test-secret-test-secret-test-secret-test-secret-1234567890",
        "spring.jwt.expiration.access=15m",
        "spring.jwt.expiration.refresh=7d",
        "app.cookie.secure=false",
        "app.cookie.same-site=Lax",
        "app.cors.allowed-origin=http://localhost:5173"
})
abstract class IntegrationTestSupport {

    static final String ACCESS_COOKIE = "access_token";
    static final String REFRESH_COOKIE = "refresh_token";

    private static final HttpClient HTTP = HttpClient.newHttpClient();

    @LocalServerPort
    private int port;

    @Autowired
    protected ObjectMapper objectMapper;

    @Autowired
    protected JdbcTemplate jdbcTemplate;

    @Autowired
    protected JUsersRepository users;

    @Autowired
    protected JRefreshTokenRepository refreshTokens;

    @Autowired
    protected PasswordEncoder passwordEncoder;

    @Autowired
    protected JwtService jwtService;

    @BeforeEach
    void resetDatabase() {
        jdbcTemplate.execute(
                "TRUNCATE TABLE refresh_token, ticket, ticket_tier, event_organizer, events, users CASCADE");
    }

    // ------------------------------------------------------------------
    // data seeding
    // ------------------------------------------------------------------

    protected JUsers seedUser(String email, String username, String rawPassword) {
        JUsers user = new JUsers();
        user.setEmail(email);
        user.setPasswordHash(passwordEncoder.encode(rawPassword));
        user.setFullName("Seeded User");
        user.setUsername(username);
        user.setDateBirth(LocalDate.of(1990, 1, 1));
        return users.saveAndFlush(user);
    }

    // ------------------------------------------------------------------
    // HTTP
    // ------------------------------------------------------------------

    protected HttpResponse<String> call(HttpMethod method, String path, Object body, HttpHeaders headers) {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
        if (body != null && headers.getContentType() == null) {
            headers.setContentType(MediaType.APPLICATION_JSON);
        }
        headers.forEach((name, values) ->
                values.forEach(value -> request.header(name, value)));

        byte[] payload = body == null ? new byte[0] : objectMapper.writeValueAsBytes(body);
        HttpRequest.BodyPublisher publisher = payload.length == 0
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofByteArray(payload);
        request.method(method.name(), publisher);

        try {
            return HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
        } catch (IOException ex) {
            throw new IllegalStateException("HTTP call failed: " + method + " " + path, ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("HTTP call interrupted: " + method + " " + path, ex);
        }
    }

    protected <T> T readBody(HttpResponse<String> response, Class<T> type) {
        return objectMapper.readValue(response.body(), type);
    }

    protected HttpResponse<String> register(String email, String password, String fullName, String username) {
        RegisterRequest request = new RegisterRequest(
                email, password, fullName, username, LocalDate.of(1990, 1, 1));
        return call(HttpMethod.POST, "/api/auth/register", request, json());
    }

    protected HttpResponse<String> login(String email, String password) {
        return call(HttpMethod.POST, "/api/auth/login", new LoginRequest(email, password), json());
    }

    // ------------------------------------------------------------------
    // headers and cookies
    // ------------------------------------------------------------------

    protected static HttpHeaders json() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    /** Adds a {@code Cookie: name=value} request header. */
    protected static HttpHeaders withCookies(HttpHeaders headers, String name, String value) {
        headers.add(HttpHeaders.COOKIE, name + "=" + value);
        return headers;
    }

    protected static List<MockCookie> cookies(HttpResponse<String> response) {
        return response.headers().allValues(HttpHeaders.SET_COOKIE).stream()
                .map(MockCookie::parse)
                .toList();
    }

    protected static MockCookie cookie(HttpResponse<String> response, String name) {
        return cookies(response).stream()
                .filter(cookie -> name.equals(cookie.getName()))
                .findFirst()
                .orElse(null);
    }

    protected static String cookieValue(HttpResponse<String> response, String name) {
        Cookie cookie = cookie(response, name);
        return cookie == null ? null : cookie.getValue();
    }

    protected static String accessToken(HttpResponse<String> response) {
        return cookieValue(response, ACCESS_COOKIE);
    }

    protected static String refreshToken(HttpResponse<String> response) {
        return cookieValue(response, REFRESH_COOKIE);
    }
}
