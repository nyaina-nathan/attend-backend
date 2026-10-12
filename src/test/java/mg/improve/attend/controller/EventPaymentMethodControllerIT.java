package mg.improve.attend.controller;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import mg.improve.attend.TestcontainersConfiguration;
import mg.improve.attend.domain.dto.request.PaymentMethodCreateRequest;
import mg.improve.attend.domain.dto.request.PaymentMethodUpdateRequest;
import mg.improve.attend.domain.dto.response.PaymentMethodResponse;
import mg.improve.attend.domain.enums.EventRole;
import mg.improve.attend.domain.enums.EventStatus;
import mg.improve.attend.domain.enums.PaymentType;
import mg.improve.attend.exceptions.ApiError;
import mg.improve.attend.repository.JEventOrganizerRepository;
import mg.improve.attend.repository.JEventPaymentMethodRepository;
import mg.improve.attend.repository.JEventsRepository;
import mg.improve.attend.repository.entities.JEventOrganizer;
import mg.improve.attend.repository.entities.JEventPaymentMethod;
import mg.improve.attend.repository.entities.JEvents;
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
class EventPaymentMethodControllerIT extends IntegrationTestSupport {

    private static final Instant START = Instant.parse("2026-11-20T18:00:00Z");

    @Autowired
    private JEventsRepository events;

    @Autowired
    private JEventOrganizerRepository organizers;

    @Autowired
    private JEventPaymentMethodRepository paymentMethods;

    // ------------------------------------------------------------------
    // GET /api/events/{eventId}/payment-methods
    // ------------------------------------------------------------------

    @Test
    void listPaymentMethods_returnsActiveOnlyForAnonymousOnPublicEvent() {
        JUsers owner = seedUser("owner@example.com", "owner", "secret123");
        JEvents event = seedEvent(owner, "Public Event", true);
        JEventPaymentMethod active = seedPaymentMethod(event, true);
        seedPaymentMethod(event, false);

        HttpResponse<String> response = call(HttpMethod.GET, "/api/events/" + event.getId() + "/payment-methods",
                null, new HttpHeaders());

        assertThat(response.statusCode()).isEqualTo(HttpStatus.OK.value());
        List<PaymentMethodResponse> body = List.of(readBody(response, PaymentMethodResponse[].class));
        assertThat(body).hasSize(1);
        assertThat(body.get(0).id()).isEqualTo(active.getId());
        assertThat(body.get(0).eventId()).isEqualTo(event.getId());
        assertThat(body.get(0).paymentType()).isEqualTo(PaymentType.MOBILE_MONEY);
        assertThat(body.get(0).providerName()).isEqualTo("M-Pesa");
        assertThat(body.get(0).isActive()).isTrue();
    }

    @Test
    void listPaymentMethods_returnsAllForCreator() {
        HttpResponse<String> registered = register("owner@example.com", "secret123", "Owner", "owner");
        JUsers owner = users.findByEmail("owner@example.com").orElseThrow();
        HttpHeaders auth = withCookies(new HttpHeaders(), ACCESS_COOKIE, accessToken(registered));
        JEvents event = seedEvent(owner, "Public Event", true);
        seedPaymentMethod(event, true);
        seedPaymentMethod(event, false);

        HttpResponse<String> response = call(HttpMethod.GET, "/api/events/" + event.getId() + "/payment-methods",
                null, auth);

        assertThat(response.statusCode()).isEqualTo(HttpStatus.OK.value());
        assertThat(List.of(readBody(response, PaymentMethodResponse[].class))).hasSize(2);
    }

    @Test
    void listPaymentMethods_allowsOrganizerOnPrivateEvent() {
        JUsers owner = seedUser("owner@example.com", "owner", "secret123");
        JEvents event = seedEvent(owner, "Private Event", false);
        seedPaymentMethod(event, true);
        seedPaymentMethod(event, false);
        HttpResponse<String> registered = register("admin@example.com", "secret123", "Admin", "admin");
        JUsers admin = users.findByEmail("admin@example.com").orElseThrow();
        seedOrganizer(admin, event, EventRole.ADMIN);
        HttpHeaders auth = withCookies(new HttpHeaders(), ACCESS_COOKIE, accessToken(registered));

        HttpResponse<String> response = call(HttpMethod.GET, "/api/events/" + event.getId() + "/payment-methods",
                null, auth);

        assertThat(response.statusCode()).isEqualTo(HttpStatus.OK.value());
        assertThat(List.of(readBody(response, PaymentMethodResponse[].class))).hasSize(1);
    }

    @Test
    void listPaymentMethods_rejectsAnonymousOnPrivateEvent() {
        JUsers owner = seedUser("owner@example.com", "owner", "secret123");
        JEvents event = seedEvent(owner, "Private Event", false);

        HttpResponse<String> response = call(HttpMethod.GET, "/api/events/" + event.getId() + "/payment-methods",
                null, new HttpHeaders());

        assertThat(response.statusCode()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
    }

    @Test
    void listPaymentMethods_rejectsNonOrganizerOnPrivateEvent() {
        JUsers owner = seedUser("owner@example.com", "owner", "secret123");
        JEvents event = seedEvent(owner, "Private Event", false);
        HttpResponse<String> registered = register("other@example.com", "secret123", "Other", "other");
        HttpHeaders auth = withCookies(new HttpHeaders(), ACCESS_COOKIE, accessToken(registered));

        HttpResponse<String> response = call(HttpMethod.GET, "/api/events/" + event.getId() + "/payment-methods",
                null, auth);

        assertThat(response.statusCode()).isEqualTo(HttpStatus.FORBIDDEN.value());
        assertThat(readBody(response, ApiError.class).error()).isEqualTo("NOT_AN_ORGANIZER");
    }

    @Test
    void listPaymentMethods_returnsNotFoundForUnknownEvent() {
        HttpResponse<String> response = call(HttpMethod.GET, "/api/events/" + UUID.randomUUID() + "/payment-methods",
                null, new HttpHeaders());

        assertThat(response.statusCode()).isEqualTo(HttpStatus.NOT_FOUND.value());
        assertThat(readBody(response, ApiError.class).error()).isEqualTo("EVENT_NOT_FOUND");
    }

    // ------------------------------------------------------------------
    // POST /api/events/{eventId}/payment-methods
    // ------------------------------------------------------------------

    @Test
    void createPaymentMethod_createsForCreator() {
        HttpResponse<String> registered = register("owner@example.com", "secret123", "Owner", "owner");
        JUsers owner = users.findByEmail("owner@example.com").orElseThrow();
        HttpHeaders auth = withCookies(new HttpHeaders(), ACCESS_COOKIE, accessToken(registered));
        JEvents event = seedEvent(owner, "Event", true);

        HttpResponse<String> response = call(HttpMethod.POST, "/api/events/" + event.getId() + "/payment-methods",
                new PaymentMethodCreateRequest(PaymentType.BANK_TRANSFER, "Bank of America", "Jane Doe",
                        "123456789", "SWIFT123", "Use the reference", null),
                auth);

        assertThat(response.statusCode()).isEqualTo(HttpStatus.CREATED.value());
        PaymentMethodResponse body = readBody(response, PaymentMethodResponse.class);
        assertThat(body.id()).isNotNull();
        assertThat(body.eventId()).isEqualTo(event.getId());
        assertThat(body.paymentType()).isEqualTo(PaymentType.BANK_TRANSFER);
        assertThat(body.providerName()).isEqualTo("Bank of America");
        assertThat(body.accountName()).isEqualTo("Jane Doe");
        assertThat(body.accountIdentifier()).isEqualTo("123456789");
        assertThat(body.routingDetails()).isEqualTo("SWIFT123");
        assertThat(body.instructions()).isEqualTo("Use the reference");
        assertThat(body.isActive()).isTrue();
        assertThat(body.createdAt()).isNotNull();

        assertThat(paymentMethods.findByEvent_Id(event.getId())).hasSize(1);
    }

    @Test
    void createPaymentMethod_defaultsActiveWhenOmitted() {
        HttpResponse<String> registered = register("owner@example.com", "secret123", "Owner", "owner");
        JUsers owner = users.findByEmail("owner@example.com").orElseThrow();
        HttpHeaders auth = withCookies(new HttpHeaders(), ACCESS_COOKIE, accessToken(registered));
        JEvents event = seedEvent(owner, "Event", true);

        HttpResponse<String> response = call(HttpMethod.POST, "/api/events/" + event.getId() + "/payment-methods",
                new PaymentMethodCreateRequest(PaymentType.MOBILE_MONEY, "M-Pesa", "Jane Doe", "+261340000000",
                        null, null, null),
                auth);

        assertThat(response.statusCode()).isEqualTo(HttpStatus.CREATED.value());
        assertThat(readBody(response, PaymentMethodResponse.class).isActive()).isTrue();
        assertThat(paymentMethods.findByEvent_Id(event.getId()).get(0).isActive()).isTrue();
    }

    @Test
    void createPaymentMethod_rejectsAdminOrganizer() {
        JUsers owner = seedUser("owner@example.com", "owner", "secret123");
        JEvents event = seedEvent(owner, "Event", true);
        HttpResponse<String> registered = register("admin@example.com", "secret123", "Admin", "admin");
        JUsers admin = users.findByEmail("admin@example.com").orElseThrow();
        seedOrganizer(admin, event, EventRole.ADMIN);
        HttpHeaders auth = withCookies(new HttpHeaders(), ACCESS_COOKIE, accessToken(registered));

        HttpResponse<String> response = call(HttpMethod.POST, "/api/events/" + event.getId() + "/payment-methods",
                new PaymentMethodCreateRequest(PaymentType.CASH, "Cash", "Jane", "Desk", null, null, null), auth);

        assertThat(response.statusCode()).isEqualTo(HttpStatus.FORBIDDEN.value());
        assertThat(readBody(response, ApiError.class).error()).isEqualTo("NOT_THE_CREATOR");
    }

    @Test
    void createPaymentMethod_rejectsNonOrganizer() {
        JUsers owner = seedUser("owner@example.com", "owner", "secret123");
        JEvents event = seedEvent(owner, "Event", true);
        HttpResponse<String> registered = register("other@example.com", "secret123", "Other", "other");
        HttpHeaders auth = withCookies(new HttpHeaders(), ACCESS_COOKIE, accessToken(registered));

        HttpResponse<String> response = call(HttpMethod.POST, "/api/events/" + event.getId() + "/payment-methods",
                new PaymentMethodCreateRequest(PaymentType.CASH, "Cash", "Jane", "Desk", null, null, null), auth);

        assertThat(response.statusCode()).isEqualTo(HttpStatus.FORBIDDEN.value());
        assertThat(readBody(response, ApiError.class).error()).isEqualTo("NOT_THE_CREATOR");
    }

    @Test
    void createPaymentMethod_rejectsUnknownEvent() {
        HttpResponse<String> registered = register("owner@example.com", "secret123", "Owner", "owner");
        HttpHeaders auth = withCookies(new HttpHeaders(), ACCESS_COOKIE, accessToken(registered));

        HttpResponse<String> response = call(HttpMethod.POST, "/api/events/" + UUID.randomUUID() + "/payment-methods",
                new PaymentMethodCreateRequest(PaymentType.CASH, "Cash", "Jane", "Desk", null, null, null), auth);

        assertThat(response.statusCode()).isEqualTo(HttpStatus.NOT_FOUND.value());
        assertThat(readBody(response, ApiError.class).error()).isEqualTo("EVENT_NOT_FOUND");
    }

    @Test
    void createPaymentMethod_rejectsInvalidBody() {
        HttpResponse<String> registered = register("owner@example.com", "secret123", "Owner", "owner");
        JUsers owner = users.findByEmail("owner@example.com").orElseThrow();
        HttpHeaders auth = withCookies(new HttpHeaders(), ACCESS_COOKIE, accessToken(registered));
        JEvents event = seedEvent(owner, "Event", true);

        HttpResponse<String> response = call(HttpMethod.POST, "/api/events/" + event.getId() + "/payment-methods",
                new PaymentMethodCreateRequest(null, " ", null, null, null, null, null), auth);

        assertThat(response.statusCode()).isEqualTo(HttpStatus.BAD_REQUEST.value());
        ApiError error = readBody(response, ApiError.class);
        assertThat(error.error()).isEqualTo("VALIDATION_ERROR");
        assertThat(error.details()).isNotEmpty();
    }

    @Test
    void createPaymentMethod_requiresAuthentication() {
        JUsers owner = seedUser("owner@example.com", "owner", "secret123");
        JEvents event = seedEvent(owner, "Event", true);

        HttpResponse<String> response = call(HttpMethod.POST, "/api/events/" + event.getId() + "/payment-methods",
                new PaymentMethodCreateRequest(PaymentType.CASH, "Cash", "Jane", "Desk", null, null, null),
                new HttpHeaders());

        assertThat(response.statusCode()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
    }

    @Test
    void createPaymentMethod_rejectsInvalidPaymentType() {
        HttpResponse<String> registered = register("owner@example.com", "secret123", "Owner", "owner");
        JUsers owner = users.findByEmail("owner@example.com").orElseThrow();
        HttpHeaders auth = withCookies(new HttpHeaders(), ACCESS_COOKIE, accessToken(registered));
        JEvents event = seedEvent(owner, "Event", true);

        Map<String, Object> body = Map.of(
                "paymentType", "BITCOIN",
                "providerName", "Satoshi",
                "accountName", "Jane Doe",
                "accountIdentifier", "wallet");

        HttpResponse<String> response = call(HttpMethod.POST, "/api/events/" + event.getId() + "/payment-methods",
                body, auth);

        assertThat(response.statusCode()).isEqualTo(HttpStatus.BAD_REQUEST.value());
        assertThat(readBody(response, ApiError.class).error()).isEqualTo("MALFORMED_REQUEST");
    }

    // ------------------------------------------------------------------
    // GET /api/payment-methods/{paymentMethodId}
    // ------------------------------------------------------------------

    @Test
    void getPaymentMethod_returnsActiveForAnonymousOnPublicEvent() {
        JUsers owner = seedUser("owner@example.com", "owner", "secret123");
        JEvents event = seedEvent(owner, "Public Event", true);
        JEventPaymentMethod method = seedPaymentMethod(event, true);

        HttpResponse<String> response = call(HttpMethod.GET, "/api/payment-methods/" + method.getId(), null,
                new HttpHeaders());

        assertThat(response.statusCode()).isEqualTo(HttpStatus.OK.value());
        PaymentMethodResponse body = readBody(response, PaymentMethodResponse.class);
        assertThat(body.id()).isEqualTo(method.getId());
        assertThat(body.eventId()).isEqualTo(event.getId());
    }

    @Test
    void getPaymentMethod_returnsInactiveForCreator() {
        HttpResponse<String> registered = register("owner@example.com", "secret123", "Owner", "owner");
        JUsers owner = users.findByEmail("owner@example.com").orElseThrow();
        HttpHeaders auth = withCookies(new HttpHeaders(), ACCESS_COOKIE, accessToken(registered));
        JEvents event = seedEvent(owner, "Public Event", true);
        JEventPaymentMethod method = seedPaymentMethod(event, false);

        HttpResponse<String> response = call(HttpMethod.GET, "/api/payment-methods/" + method.getId(), null, auth);

        assertThat(response.statusCode()).isEqualTo(HttpStatus.OK.value());
        assertThat(readBody(response, PaymentMethodResponse.class).isActive()).isFalse();
    }

    @Test
    void getPaymentMethod_hidesInactiveFromAnonymous() {
        JUsers owner = seedUser("owner@example.com", "owner", "secret123");
        JEvents event = seedEvent(owner, "Public Event", true);
        JEventPaymentMethod method = seedPaymentMethod(event, false);

        HttpResponse<String> response = call(HttpMethod.GET, "/api/payment-methods/" + method.getId(), null,
                new HttpHeaders());

        assertThat(response.statusCode()).isEqualTo(HttpStatus.NOT_FOUND.value());
        assertThat(readBody(response, ApiError.class).error()).isEqualTo("PAYMENT_METHOD_NOT_FOUND");
    }

    @Test
    void getPaymentMethod_hidesInactiveFromAdminOrganizer() {
        JUsers owner = seedUser("owner@example.com", "owner", "secret123");
        JEvents event = seedEvent(owner, "Public Event", true);
        JEventPaymentMethod method = seedPaymentMethod(event, false);
        HttpResponse<String> registered = register("admin@example.com", "secret123", "Admin", "admin");
        JUsers admin = users.findByEmail("admin@example.com").orElseThrow();
        seedOrganizer(admin, event, EventRole.ADMIN);
        HttpHeaders auth = withCookies(new HttpHeaders(), ACCESS_COOKIE, accessToken(registered));

        HttpResponse<String> response = call(HttpMethod.GET, "/api/payment-methods/" + method.getId(), null, auth);

        assertThat(response.statusCode()).isEqualTo(HttpStatus.NOT_FOUND.value());
        assertThat(readBody(response, ApiError.class).error()).isEqualTo("PAYMENT_METHOD_NOT_FOUND");
    }

    @Test
    void getPaymentMethod_rejectsAnonymousOnPrivateEvent() {
        JUsers owner = seedUser("owner@example.com", "owner", "secret123");
        JEvents event = seedEvent(owner, "Private Event", false);
        JEventPaymentMethod method = seedPaymentMethod(event, true);

        HttpResponse<String> response = call(HttpMethod.GET, "/api/payment-methods/" + method.getId(), null,
                new HttpHeaders());

        assertThat(response.statusCode()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
    }

    @Test
    void getPaymentMethod_returnsNotFoundForUnknownMethod() {
        HttpResponse<String> response = call(HttpMethod.GET, "/api/payment-methods/" + UUID.randomUUID(), null,
                new HttpHeaders());

        assertThat(response.statusCode()).isEqualTo(HttpStatus.NOT_FOUND.value());
        assertThat(readBody(response, ApiError.class).error()).isEqualTo("PAYMENT_METHOD_NOT_FOUND");
    }

    // ------------------------------------------------------------------
    // PATCH /api/payment-methods/{paymentMethodId}
    // ------------------------------------------------------------------

    @Test
    void updatePaymentMethod_updatesFieldsForCreator() {
        HttpResponse<String> registered = register("owner@example.com", "secret123", "Owner", "owner");
        JUsers owner = users.findByEmail("owner@example.com").orElseThrow();
        HttpHeaders auth = withCookies(new HttpHeaders(), ACCESS_COOKIE, accessToken(registered));
        JEvents event = seedEvent(owner, "Event", true);
        JEventPaymentMethod method = seedPaymentMethod(event, true);

        HttpResponse<String> response = call(HttpMethod.PATCH, "/api/payment-methods/" + method.getId(),
                new PaymentMethodUpdateRequest(PaymentType.CARD, "Visa", "Jane Doe", "4111111111111111",
                        "Routing", "New instruction", false),
                auth);

        assertThat(response.statusCode()).isEqualTo(HttpStatus.OK.value());
        PaymentMethodResponse body = readBody(response, PaymentMethodResponse.class);
        assertThat(body.paymentType()).isEqualTo(PaymentType.CARD);
        assertThat(body.providerName()).isEqualTo("Visa");
        assertThat(body.accountIdentifier()).isEqualTo("4111111111111111");
        assertThat(body.instructions()).isEqualTo("New instruction");
        assertThat(body.isActive()).isFalse();
        assertThat(body.eventId()).isEqualTo(event.getId());

        JEventPaymentMethod stored = paymentMethods.findById(method.getId()).orElseThrow();
        assertThat(stored.getPaymentType()).isEqualTo(PaymentType.CARD);
        assertThat(stored.isActive()).isFalse();
    }

    @Test
    void updatePaymentMethod_partialUpdateLeavesOthersUntouched() {
        HttpResponse<String> registered = register("owner@example.com", "secret123", "Owner", "owner");
        JUsers owner = users.findByEmail("owner@example.com").orElseThrow();
        HttpHeaders auth = withCookies(new HttpHeaders(), ACCESS_COOKIE, accessToken(registered));
        JEvents event = seedEvent(owner, "Event", true);
        JEventPaymentMethod method = seedPaymentMethod(event, true);

        HttpResponse<String> response = call(HttpMethod.PATCH, "/api/payment-methods/" + method.getId(),
                new PaymentMethodUpdateRequest(null, "Updated Provider", null, null, null, null, null), auth);

        assertThat(response.statusCode()).isEqualTo(HttpStatus.OK.value());
        PaymentMethodResponse body = readBody(response, PaymentMethodResponse.class);
        assertThat(body.providerName()).isEqualTo("Updated Provider");
        assertThat(body.accountName()).isEqualTo("Jane Doe");
        assertThat(body.isActive()).isTrue();
    }

    @Test
    void updatePaymentMethod_rejectsNonCreator() {
        JUsers owner = seedUser("owner@example.com", "owner", "secret123");
        JEvents event = seedEvent(owner, "Event", true);
        JEventPaymentMethod method = seedPaymentMethod(event, true);
        HttpResponse<String> registered = register("other@example.com", "secret123", "Other", "other");
        HttpHeaders auth = withCookies(new HttpHeaders(), ACCESS_COOKIE, accessToken(registered));

        HttpResponse<String> response = call(HttpMethod.PATCH, "/api/payment-methods/" + method.getId(),
                new PaymentMethodUpdateRequest(null, "Nope", null, null, null, null, null), auth);

        assertThat(response.statusCode()).isEqualTo(HttpStatus.FORBIDDEN.value());
        assertThat(readBody(response, ApiError.class).error()).isEqualTo("NOT_THE_CREATOR");
    }

    @Test
    void updatePaymentMethod_rejectsUnknownMethod() {
        HttpResponse<String> registered = register("owner@example.com", "secret123", "Owner", "owner");
        HttpHeaders auth = withCookies(new HttpHeaders(), ACCESS_COOKIE, accessToken(registered));

        HttpResponse<String> response = call(HttpMethod.PATCH, "/api/payment-methods/" + UUID.randomUUID(),
                new PaymentMethodUpdateRequest(null, "Nope", null, null, null, null, null), auth);

        assertThat(response.statusCode()).isEqualTo(HttpStatus.NOT_FOUND.value());
        assertThat(readBody(response, ApiError.class).error()).isEqualTo("PAYMENT_METHOD_NOT_FOUND");
    }

    @Test
    void updatePaymentMethod_requiresAuthentication() {
        JUsers owner = seedUser("owner@example.com", "owner", "secret123");
        JEvents event = seedEvent(owner, "Event", true);
        JEventPaymentMethod method = seedPaymentMethod(event, true);

        HttpResponse<String> response = call(HttpMethod.PATCH, "/api/payment-methods/" + method.getId(),
                new PaymentMethodUpdateRequest(null, "Nope", null, null, null, null, null), new HttpHeaders());

        assertThat(response.statusCode()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
    }

    @Test
    void updatePaymentMethod_rejectsInvalidBody() {
        HttpResponse<String> registered = register("owner@example.com", "secret123", "Owner", "owner");
        JUsers owner = users.findByEmail("owner@example.com").orElseThrow();
        HttpHeaders auth = withCookies(new HttpHeaders(), ACCESS_COOKIE, accessToken(registered));
        JEvents event = seedEvent(owner, "Event", true);
        JEventPaymentMethod method = seedPaymentMethod(event, true);

        HttpResponse<String> response = call(HttpMethod.PATCH, "/api/payment-methods/" + method.getId(),
                new PaymentMethodUpdateRequest(null, " ", null, null, null, null, null), auth);

        assertThat(response.statusCode()).isEqualTo(HttpStatus.BAD_REQUEST.value());
        assertThat(readBody(response, ApiError.class).error()).isEqualTo("VALIDATION_ERROR");
    }

    // ------------------------------------------------------------------
    // DELETE /api/payment-methods/{paymentMethodId}
    // ------------------------------------------------------------------

    @Test
    void deletePaymentMethod_deletesForCreator() {
        HttpResponse<String> registered = register("owner@example.com", "secret123", "Owner", "owner");
        JUsers owner = users.findByEmail("owner@example.com").orElseThrow();
        HttpHeaders auth = withCookies(new HttpHeaders(), ACCESS_COOKIE, accessToken(registered));
        JEvents event = seedEvent(owner, "Event", true);
        JEventPaymentMethod method = seedPaymentMethod(event, true);

        HttpResponse<String> response = call(HttpMethod.DELETE, "/api/payment-methods/" + method.getId(), null, auth);

        assertThat(response.statusCode()).isEqualTo(HttpStatus.NO_CONTENT.value());
        assertThat(paymentMethods.findById(method.getId())).isEmpty();
    }

    @Test
    void deletePaymentMethod_rejectsNonCreator() {
        JUsers owner = seedUser("owner@example.com", "owner", "secret123");
        JEvents event = seedEvent(owner, "Event", true);
        JEventPaymentMethod method = seedPaymentMethod(event, true);
        HttpResponse<String> registered = register("other@example.com", "secret123", "Other", "other");
        HttpHeaders auth = withCookies(new HttpHeaders(), ACCESS_COOKIE, accessToken(registered));

        HttpResponse<String> response = call(HttpMethod.DELETE, "/api/payment-methods/" + method.getId(), null, auth);

        assertThat(response.statusCode()).isEqualTo(HttpStatus.FORBIDDEN.value());
        assertThat(readBody(response, ApiError.class).error()).isEqualTo("NOT_THE_CREATOR");
        assertThat(paymentMethods.findById(method.getId())).isPresent();
    }

    @Test
    void deletePaymentMethod_returnsNotFoundForUnknownMethod() {
        HttpResponse<String> registered = register("owner@example.com", "secret123", "Owner", "owner");
        HttpHeaders auth = withCookies(new HttpHeaders(), ACCESS_COOKIE, accessToken(registered));

        HttpResponse<String> response = call(HttpMethod.DELETE, "/api/payment-methods/" + UUID.randomUUID(), null,
                auth);

        assertThat(response.statusCode()).isEqualTo(HttpStatus.NOT_FOUND.value());
        assertThat(readBody(response, ApiError.class).error()).isEqualTo("PAYMENT_METHOD_NOT_FOUND");
    }

    @Test
    void deletePaymentMethod_requiresAuthentication() {
        JUsers owner = seedUser("owner@example.com", "owner", "secret123");
        JEvents event = seedEvent(owner, "Event", true);
        JEventPaymentMethod method = seedPaymentMethod(event, true);

        HttpResponse<String> response = call(HttpMethod.DELETE, "/api/payment-methods/" + method.getId(), null,
                new HttpHeaders());

        assertThat(response.statusCode()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
    }

    // ------------------------------------------------------------------
    // event deletion cascade
    // ------------------------------------------------------------------

    @Test
    void deleteEvent_removesPaymentMethods() {
        HttpResponse<String> registered = register("owner@example.com", "secret123", "Owner", "owner");
        JUsers owner = users.findByEmail("owner@example.com").orElseThrow();
        HttpHeaders auth = withCookies(new HttpHeaders(), ACCESS_COOKIE, accessToken(registered));
        JEvents event = seedEvent(owner, "Event", true);
        seedPaymentMethod(event, true);
        seedPaymentMethod(event, false);

        HttpResponse<String> response = call(HttpMethod.DELETE, "/api/events/" + event.getId(), null, auth);

        assertThat(response.statusCode()).isEqualTo(HttpStatus.NO_CONTENT.value());
        assertThat(paymentMethods.findByEvent_Id(event.getId())).isEmpty();
        assertThat(events.findById(event.getId())).isEmpty();
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

    private JEventPaymentMethod seedPaymentMethod(JEvents event, boolean active) {
        JEventPaymentMethod method = new JEventPaymentMethod();
        method.setEvent(event);
        method.setPaymentType(PaymentType.MOBILE_MONEY);
        method.setProviderName("M-Pesa");
        method.setAccountName("Jane Doe");
        method.setAccountIdentifier("+261340000000");
        method.setInstructions("Send then share the reference.");
        method.setActive(active);
        return paymentMethods.saveAndFlush(method);
    }
}
