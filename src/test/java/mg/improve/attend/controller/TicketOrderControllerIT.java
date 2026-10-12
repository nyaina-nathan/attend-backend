package mg.improve.attend.controller;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import mg.improve.attend.TestcontainersConfiguration;
import mg.improve.attend.domain.dto.request.OrderCreateRequest;
import mg.improve.attend.domain.dto.request.OrderItemCreateRequest;
import mg.improve.attend.domain.dto.request.OrderRefuseRequest;
import mg.improve.attend.domain.dto.response.OrderResponse;
import mg.improve.attend.domain.dto.response.PageOrder;
import mg.improve.attend.domain.enums.EventRole;
import mg.improve.attend.domain.enums.EventStatus;
import mg.improve.attend.domain.enums.OrderStatus;
import mg.improve.attend.domain.enums.PaymentType;
import mg.improve.attend.domain.enums.TicketStatus;
import mg.improve.attend.exceptions.ApiError;
import mg.improve.attend.repository.JEventOrganizerRepository;
import mg.improve.attend.repository.JEventPaymentMethodRepository;
import mg.improve.attend.repository.JEventsRepository;
import mg.improve.attend.repository.JTicketOrderItemRepository;
import mg.improve.attend.repository.JTicketOrderRepository;
import mg.improve.attend.repository.JTicketRepository;
import mg.improve.attend.repository.JTicketTierRepository;
import mg.improve.attend.repository.entities.JEventOrganizer;
import mg.improve.attend.repository.entities.JEventPaymentMethod;
import mg.improve.attend.repository.entities.JEvents;
import mg.improve.attend.repository.entities.JTicket;
import mg.improve.attend.repository.entities.JTicketOrder;
import mg.improve.attend.repository.entities.JTicketOrderItem;
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
class TicketOrderControllerIT extends IntegrationTestSupport {

    private static final Instant END = Instant.parse("2026-11-20T22:00:00Z");

    @Autowired
    private JEventsRepository events;

    @Autowired
    private JEventOrganizerRepository organizers;

    @Autowired
    private JEventPaymentMethodRepository paymentMethods;

    @Autowired
    private JTicketTierRepository tiers;

    @Autowired
    private JTicketOrderRepository orders;

    @Autowired
    private JTicketOrderItemRepository orderItems;

    @Autowired
    private JTicketRepository tickets;

    // ------------------------------------------------------------------
    // POST /api/events/{eventId}/orders
    // ------------------------------------------------------------------

    @Test
    void createOrder_createsPendingOrderForAuthenticatedUser() {
        HttpResponse<String> registered = register("buyer@example.com", "secret123", "Buyer", "buyer");
        JUsers buyer = users.findByEmail("buyer@example.com").orElseThrow();
        HttpHeaders auth = withCookies(new HttpHeaders(), ACCESS_COOKIE, accessToken(registered));
        JEvents event = seedEvent(buyer, "Event", true);
        JTicketTier tier = seedTier(event, "VIP", "40.00", 10);
        JEventPaymentMethod method = seedPaymentMethod(event, true);

        HttpResponse<String> response = call(HttpMethod.POST, "/api/events/" + event.getId() + "/orders",
                new OrderCreateRequest(method.getId(), "REF-1",
                        List.of(new OrderItemCreateRequest(tier.getId(), 2))),
                auth);

        assertThat(response.statusCode()).isEqualTo(HttpStatus.CREATED.value());
        OrderResponse body = readBody(response, OrderResponse.class);
        assertThat(body.id()).isNotNull();
        assertThat(body.eventId()).isEqualTo(event.getId());
        assertThat(body.owner().username()).isEqualTo("buyer");
        assertThat(body.paymentMethodId()).isEqualTo(method.getId());
        assertThat(body.transactionReference()).isEqualTo("REF-1");
        assertThat(body.priceUnit()).isEqualTo("USD");
        assertThat(body.orderStatus()).isEqualTo(OrderStatus.PENDING);
        assertThat(body.total()).isEqualByComparingTo("80.00");
        assertThat(body.items()).hasSize(1);
        assertThat(body.items().get(0).tierName()).isEqualTo("VIP");
        assertThat(body.items().get(0).quantity()).isEqualTo(2);
        assertThat(body.items().get(0).unitPrice()).isEqualByComparingTo("40.00");
        assertThat(body.items().get(0).lineTotal()).isEqualByComparingTo("80.00");
        assertThat(body.tickets()).isEmpty();
        assertThat(body.createdAt()).isNotNull();

        JTicketOrder stored = orders.findById(body.id()).orElseThrow();
        assertThat(stored.getOrderStatus()).isEqualTo(OrderStatus.PENDING);
        assertThat(stored.getTransactionReference()).isEqualTo("REF-1");
        List<JTicketOrderItem> storedItems = orderItems.findByOrder_IdIn(List.of(body.id()));
        assertThat(storedItems).hasSize(1);
        assertThat(storedItems.get(0).getUnitPrice()).isEqualByComparingTo("40.00");
        assertThat(storedItems.get(0).getQuantity()).isEqualTo(2);
    }

    @Test
    void createOrder_defaultsTransactionReferenceToNull() {
        HttpResponse<String> registered = register("buyer@example.com", "secret123", "Buyer", "buyer");
        JUsers buyer = users.findByEmail("buyer@example.com").orElseThrow();
        HttpHeaders auth = withCookies(new HttpHeaders(), ACCESS_COOKIE, accessToken(registered));
        JEvents event = seedEvent(buyer, "Event", true);
        JTicketTier tier = seedTier(event, "VIP", "40.00", 10);
        JEventPaymentMethod method = seedPaymentMethod(event, true);

        HttpResponse<String> response = call(HttpMethod.POST, "/api/events/" + event.getId() + "/orders",
                new OrderCreateRequest(method.getId(), null,
                        List.of(new OrderItemCreateRequest(tier.getId(), 1))),
                auth);

        assertThat(response.statusCode()).isEqualTo(HttpStatus.CREATED.value());
        assertThat(readBody(response, OrderResponse.class).transactionReference()).isNull();
    }

    @Test
    void createOrder_rejectsMissingPaymentMethod() {
        HttpResponse<String> registered = register("buyer@example.com", "secret123", "Buyer", "buyer");
        JUsers buyer = users.findByEmail("buyer@example.com").orElseThrow();
        HttpHeaders auth = withCookies(new HttpHeaders(), ACCESS_COOKIE, accessToken(registered));
        JEvents event = seedEvent(buyer, "Event", true);
        JTicketTier tier = seedTier(event, "VIP", "40.00", 10);

        HttpResponse<String> response = call(HttpMethod.POST, "/api/events/" + event.getId() + "/orders",
                new OrderCreateRequest(null, null, List.of(new OrderItemCreateRequest(tier.getId(), 1))), auth);

        assertThat(response.statusCode()).isEqualTo(HttpStatus.BAD_REQUEST.value());
        assertThat(readBody(response, ApiError.class).error()).isEqualTo("VALIDATION_ERROR");
    }

    @Test
    void createOrder_rejectsEmptyItems() {
        HttpResponse<String> registered = register("buyer@example.com", "secret123", "Buyer", "buyer");
        JUsers buyer = users.findByEmail("buyer@example.com").orElseThrow();
        HttpHeaders auth = withCookies(new HttpHeaders(), ACCESS_COOKIE, accessToken(registered));
        JEvents event = seedEvent(buyer, "Event", true);
        JEventPaymentMethod method = seedPaymentMethod(event, true);

        HttpResponse<String> response = call(HttpMethod.POST, "/api/events/" + event.getId() + "/orders",
                new OrderCreateRequest(method.getId(), null, List.of()), auth);

        assertThat(response.statusCode()).isEqualTo(HttpStatus.BAD_REQUEST.value());
        assertThat(readBody(response, ApiError.class).error()).isEqualTo("VALIDATION_ERROR");
    }

    @Test
    void createOrder_rejectsZeroQuantity() {
        HttpResponse<String> registered = register("buyer@example.com", "secret123", "Buyer", "buyer");
        JUsers buyer = users.findByEmail("buyer@example.com").orElseThrow();
        HttpHeaders auth = withCookies(new HttpHeaders(), ACCESS_COOKIE, accessToken(registered));
        JEvents event = seedEvent(buyer, "Event", true);
        JTicketTier tier = seedTier(event, "VIP", "40.00", 10);
        JEventPaymentMethod method = seedPaymentMethod(event, true);

        HttpResponse<String> response = call(HttpMethod.POST, "/api/events/" + event.getId() + "/orders",
                new OrderCreateRequest(method.getId(), null,
                        List.of(new OrderItemCreateRequest(tier.getId(), 0))),
                auth);

        assertThat(response.statusCode()).isEqualTo(HttpStatus.BAD_REQUEST.value());
        assertThat(readBody(response, ApiError.class).error()).isEqualTo("VALIDATION_ERROR");
    }

    @Test
    void createOrder_rejectsDuplicateTier() {
        HttpResponse<String> registered = register("buyer@example.com", "secret123", "Buyer", "buyer");
        JUsers buyer = users.findByEmail("buyer@example.com").orElseThrow();
        HttpHeaders auth = withCookies(new HttpHeaders(), ACCESS_COOKIE, accessToken(registered));
        JEvents event = seedEvent(buyer, "Event", true);
        JTicketTier tier = seedTier(event, "VIP", "40.00", 10);
        JEventPaymentMethod method = seedPaymentMethod(event, true);

        HttpResponse<String> response = call(HttpMethod.POST, "/api/events/" + event.getId() + "/orders",
                new OrderCreateRequest(method.getId(), null, List.of(
                        new OrderItemCreateRequest(tier.getId(), 1),
                        new OrderItemCreateRequest(tier.getId(), 2))),
                auth);

        assertThat(response.statusCode()).isEqualTo(HttpStatus.BAD_REQUEST.value());
        assertThat(readBody(response, ApiError.class).error()).isEqualTo("VALIDATION_ERROR");
    }

    @Test
    void createOrder_rejectsUnknownEvent() {
        HttpResponse<String> registered = register("buyer@example.com", "secret123", "Buyer", "buyer");
        HttpHeaders auth = withCookies(new HttpHeaders(), ACCESS_COOKIE, accessToken(registered));

        HttpResponse<String> response = call(HttpMethod.POST, "/api/events/" + UUID.randomUUID() + "/orders",
                new OrderCreateRequest(UUID.randomUUID(), null,
                        List.of(new OrderItemCreateRequest(UUID.randomUUID(), 1))),
                auth);

        assertThat(response.statusCode()).isEqualTo(HttpStatus.NOT_FOUND.value());
        assertThat(readBody(response, ApiError.class).error()).isEqualTo("EVENT_NOT_FOUND");
    }

    @Test
    void createOrder_rejectsTierFromAnotherEvent() {
        HttpResponse<String> registered = register("buyer@example.com", "secret123", "Buyer", "buyer");
        JUsers buyer = users.findByEmail("buyer@example.com").orElseThrow();
        HttpHeaders auth = withCookies(new HttpHeaders(), ACCESS_COOKIE, accessToken(registered));
        JEvents event = seedEvent(buyer, "Event", true);
        JEventPaymentMethod method = seedPaymentMethod(event, true);
        JEvents otherEvent = seedEvent(buyer, "Other", true);
        JTicketTier foreignTier = seedTier(otherEvent, "VIP", "40.00", 10);

        HttpResponse<String> response = call(HttpMethod.POST, "/api/events/" + event.getId() + "/orders",
                new OrderCreateRequest(method.getId(), null,
                        List.of(new OrderItemCreateRequest(foreignTier.getId(), 1))),
                auth);

        assertThat(response.statusCode()).isEqualTo(HttpStatus.NOT_FOUND.value());
        assertThat(readBody(response, ApiError.class).error()).isEqualTo("TIER_NOT_FOUND");
    }

    @Test
    void createOrder_rejectsPaymentMethodFromAnotherEvent() {
        HttpResponse<String> registered = register("buyer@example.com", "secret123", "Buyer", "buyer");
        JUsers buyer = users.findByEmail("buyer@example.com").orElseThrow();
        HttpHeaders auth = withCookies(new HttpHeaders(), ACCESS_COOKIE, accessToken(registered));
        JEvents event = seedEvent(buyer, "Event", true);
        JTicketTier tier = seedTier(event, "VIP", "40.00", 10);
        JEvents otherEvent = seedEvent(buyer, "Other", true);
        JEventPaymentMethod foreignMethod = seedPaymentMethod(otherEvent, true);

        HttpResponse<String> response = call(HttpMethod.POST, "/api/events/" + event.getId() + "/orders",
                new OrderCreateRequest(foreignMethod.getId(), null,
                        List.of(new OrderItemCreateRequest(tier.getId(), 1))),
                auth);

        assertThat(response.statusCode()).isEqualTo(HttpStatus.NOT_FOUND.value());
        assertThat(readBody(response, ApiError.class).error()).isEqualTo("PAYMENT_METHOD_NOT_FOUND");
    }

    @Test
    void createOrder_rejectsInactivePaymentMethod() {
        HttpResponse<String> registered = register("buyer@example.com", "secret123", "Buyer", "buyer");
        JUsers buyer = users.findByEmail("buyer@example.com").orElseThrow();
        HttpHeaders auth = withCookies(new HttpHeaders(), ACCESS_COOKIE, accessToken(registered));
        JEvents event = seedEvent(buyer, "Event", true);
        JTicketTier tier = seedTier(event, "VIP", "40.00", 10);
        JEventPaymentMethod method = seedPaymentMethod(event, false);

        HttpResponse<String> response = call(HttpMethod.POST, "/api/events/" + event.getId() + "/orders",
                new OrderCreateRequest(method.getId(), null,
                        List.of(new OrderItemCreateRequest(tier.getId(), 1))),
                auth);

        assertThat(response.statusCode()).isEqualTo(HttpStatus.CONFLICT.value());
        assertThat(readBody(response, ApiError.class).error()).isEqualTo("PAYMENT_METHOD_INACTIVE");
    }

    @Test
    void createOrder_rejectsDuplicateTransactionReference() {
        HttpResponse<String> registered = register("buyer@example.com", "secret123", "Buyer", "buyer");
        JUsers buyer = users.findByEmail("buyer@example.com").orElseThrow();
        HttpHeaders auth = withCookies(new HttpHeaders(), ACCESS_COOKIE, accessToken(registered));
        JEvents event = seedEvent(buyer, "Event", true);
        JTicketTier tier = seedTier(event, "VIP", "40.00", 10);
        JEventPaymentMethod method = seedPaymentMethod(event, true);
        OrderCreateRequest request = new OrderCreateRequest(method.getId(), "DUP",
                List.of(new OrderItemCreateRequest(tier.getId(), 1)));

        HttpResponse<String> first = call(HttpMethod.POST, "/api/events/" + event.getId() + "/orders", request, auth);
        assertThat(first.statusCode()).isEqualTo(HttpStatus.CREATED.value());

        HttpResponse<String> second = call(HttpMethod.POST, "/api/events/" + event.getId() + "/orders", request, auth);

        assertThat(second.statusCode()).isEqualTo(HttpStatus.CONFLICT.value());
        assertThat(readBody(second, ApiError.class).error()).isEqualTo("DUPLICATE_TRANSACTION_REFERENCE");
    }

    @Test
    void createOrder_rejectsEndedEvent() {
        HttpResponse<String> registered = register("buyer@example.com", "secret123", "Buyer", "buyer");
        JUsers buyer = users.findByEmail("buyer@example.com").orElseThrow();
        HttpHeaders auth = withCookies(new HttpHeaders(), ACCESS_COOKIE, accessToken(registered));
        JEvents event = seedEvent(buyer, "Ended", true, EventStatus.OK,
                Instant.now().minus(Duration.ofHours(1)));
        JTicketTier tier = seedTier(event, "VIP", "40.00", 10);
        JEventPaymentMethod method = seedPaymentMethod(event, true);

        HttpResponse<String> response = call(HttpMethod.POST, "/api/events/" + event.getId() + "/orders",
                new OrderCreateRequest(method.getId(), null,
                        List.of(new OrderItemCreateRequest(tier.getId(), 1))),
                auth);

        assertThat(response.statusCode()).isEqualTo(HttpStatus.CONFLICT.value());
        assertThat(readBody(response, ApiError.class).error()).isEqualTo("EVENT_NOT_OPEN");
    }

    @Test
    void createOrder_requiresAuthentication() {
        JUsers owner = seedUser("owner@example.com", "owner", "secret123");
        JEvents event = seedEvent(owner, "Event", true);
        JTicketTier tier = seedTier(event, "VIP", "40.00", 10);
        JEventPaymentMethod method = seedPaymentMethod(event, true);

        HttpResponse<String> response = call(HttpMethod.POST, "/api/events/" + event.getId() + "/orders",
                new OrderCreateRequest(method.getId(), null,
                        List.of(new OrderItemCreateRequest(tier.getId(), 1))),
                new HttpHeaders());

        assertThat(response.statusCode()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
    }

    // ------------------------------------------------------------------
    // reads
    // ------------------------------------------------------------------

    @Test
    void listMyOrders_returnsOwnOrders() {
        HttpResponse<String> registered = register("buyer@example.com", "secret123", "Buyer", "buyer");
        JUsers buyer = users.findByEmail("buyer@example.com").orElseThrow();
        HttpHeaders auth = withCookies(new HttpHeaders(), ACCESS_COOKIE, accessToken(registered));
        JEvents event = seedEvent(buyer, "Event", true);
        JTicketTier tier = seedTier(event, "VIP", "40.00", 10);
        JEventPaymentMethod method = seedPaymentMethod(event, true);
        seedOrder(event, buyer, method, OrderStatus.PENDING, tier, 1);

        HttpResponse<String> response = call(HttpMethod.GET, "/api/orders/me", null, auth);

        assertThat(response.statusCode()).isEqualTo(HttpStatus.OK.value());
        PageOrder body = readBody(response, PageOrder.class);
        assertThat(body.content()).hasSize(1);
        assertThat(body.content().get(0).eventId()).isEqualTo(event.getId());
        assertThat(body.content().get(0).orderStatus()).isEqualTo(OrderStatus.PENDING);
    }

    @Test
    void listMyOrders_filtersByStatus() {
        HttpResponse<String> registered = register("buyer@example.com", "secret123", "Buyer", "buyer");
        JUsers buyer = users.findByEmail("buyer@example.com").orElseThrow();
        HttpHeaders auth = withCookies(new HttpHeaders(), ACCESS_COOKIE, accessToken(registered));
        JEvents event = seedEvent(buyer, "Event", true);
        JTicketTier tier = seedTier(event, "VIP", "40.00", 10);
        JEventPaymentMethod method = seedPaymentMethod(event, true);
        seedOrder(event, buyer, method, OrderStatus.PENDING, tier, 1);
        seedOrder(event, buyer, method, OrderStatus.REFUSED, tier, 1);

        HttpResponse<String> response = call(HttpMethod.GET, "/api/orders/me?status=REFUSED", null, auth);

        assertThat(response.statusCode()).isEqualTo(HttpStatus.OK.value());
        PageOrder body = readBody(response, PageOrder.class);
        assertThat(body.content()).hasSize(1);
        assertThat(body.content().get(0).orderStatus()).isEqualTo(OrderStatus.REFUSED);
    }

    @Test
    void listMyOrders_excludesOtherUsersOrders() {
        JUsers owner = seedUser("owner@example.com", "owner", "secret123");
        JUsers other = seedUser("other@example.com", "other", "secret123");
        JEvents event = seedEvent(owner, "Event", true);
        JTicketTier tier = seedTier(event, "VIP", "40.00", 10);
        JEventPaymentMethod method = seedPaymentMethod(event, true);
        seedOrder(event, other, method, OrderStatus.PENDING, tier, 1);

        HttpResponse<String> registered = register("caller@example.com", "secret123", "Caller", "caller");
        HttpHeaders auth = withCookies(new HttpHeaders(), ACCESS_COOKIE, accessToken(registered));

        HttpResponse<String> response = call(HttpMethod.GET, "/api/orders/me", null, auth);

        assertThat(response.statusCode()).isEqualTo(HttpStatus.OK.value());
        assertThat(readBody(response, PageOrder.class).content()).isEmpty();
    }

    @Test
    void listEventOrders_returnsForOrganizer() {
        HttpResponse<String> registered = register("owner@example.com", "secret123", "Owner", "owner");
        JUsers owner = users.findByEmail("owner@example.com").orElseThrow();
        HttpHeaders auth = withCookies(new HttpHeaders(), ACCESS_COOKIE, accessToken(registered));
        JEvents event = seedEvent(owner, "Event", true);
        JTicketTier tier = seedTier(event, "VIP", "40.00", 10);
        JEventPaymentMethod method = seedPaymentMethod(event, true);
        seedOrder(event, owner, method, OrderStatus.PENDING, tier, 1);

        HttpResponse<String> response = call(HttpMethod.GET, "/api/events/" + event.getId() + "/orders", null, auth);

        assertThat(response.statusCode()).isEqualTo(HttpStatus.OK.value());
        assertThat(readBody(response, PageOrder.class).content()).hasSize(1);
    }

    @Test
    void listEventOrders_rejectsNonOrganizer() {
        JUsers owner = seedUser("owner@example.com", "owner", "secret123");
        JEvents event = seedEvent(owner, "Event", true);
        HttpResponse<String> registered = register("other@example.com", "secret123", "Other", "other");
        HttpHeaders auth = withCookies(new HttpHeaders(), ACCESS_COOKIE, accessToken(registered));

        HttpResponse<String> response = call(HttpMethod.GET, "/api/events/" + event.getId() + "/orders", null, auth);

        assertThat(response.statusCode()).isEqualTo(HttpStatus.FORBIDDEN.value());
        assertThat(readBody(response, ApiError.class).error()).isEqualTo("NOT_AN_ORGANIZER");
    }

    @Test
    void listEventOrders_rejectsAnonymous() {
        JUsers owner = seedUser("owner@example.com", "owner", "secret123");
        JEvents event = seedEvent(owner, "Event", true);

        HttpResponse<String> response = call(HttpMethod.GET, "/api/events/" + event.getId() + "/orders", null,
                new HttpHeaders());

        assertThat(response.statusCode()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
    }

    @Test
    void getOrder_returnsForOwner() {
        HttpResponse<String> registered = register("buyer@example.com", "secret123", "Buyer", "buyer");
        JUsers buyer = users.findByEmail("buyer@example.com").orElseThrow();
        HttpHeaders auth = withCookies(new HttpHeaders(), ACCESS_COOKIE, accessToken(registered));
        JEvents event = seedEvent(buyer, "Event", true);
        JTicketTier tier = seedTier(event, "VIP", "40.00", 10);
        JEventPaymentMethod method = seedPaymentMethod(event, true);
        JTicketOrder order = seedOrder(event, buyer, method, OrderStatus.PENDING, tier, 1);

        HttpResponse<String> response = call(HttpMethod.GET, "/api/orders/" + order.getId(), null, auth);

        assertThat(response.statusCode()).isEqualTo(HttpStatus.OK.value());
        assertThat(readBody(response, OrderResponse.class).id()).isEqualTo(order.getId());
    }

    @Test
    void getOrder_returnsForOrganizer() {
        JUsers owner = seedUser("owner@example.com", "owner", "secret123");
        JUsers buyer = seedUser("buyer@example.com", "buyer", "secret123");
        JEvents event = seedEvent(owner, "Event", true);
        JTicketTier tier = seedTier(event, "VIP", "40.00", 10);
        JEventPaymentMethod method = seedPaymentMethod(event, true);
        JTicketOrder order = seedOrder(event, buyer, method, OrderStatus.PENDING, tier, 1);

        HttpResponse<String> registered = register("admin@example.com", "secret123", "Admin", "admin");
        JUsers admin = users.findByEmail("admin@example.com").orElseThrow();
        seedOrganizer(admin, event, EventRole.ADMIN);
        HttpHeaders auth = withCookies(new HttpHeaders(), ACCESS_COOKIE, accessToken(registered));

        HttpResponse<String> response = call(HttpMethod.GET, "/api/orders/" + order.getId(), null, auth);

        assertThat(response.statusCode()).isEqualTo(HttpStatus.OK.value());
    }

    @Test
    void getOrder_rejectsStranger() {
        JUsers owner = seedUser("owner@example.com", "owner", "secret123");
        JUsers buyer = seedUser("buyer@example.com", "buyer", "secret123");
        JEvents event = seedEvent(owner, "Event", true);
        JTicketTier tier = seedTier(event, "VIP", "40.00", 10);
        JEventPaymentMethod method = seedPaymentMethod(event, true);
        JTicketOrder order = seedOrder(event, buyer, method, OrderStatus.PENDING, tier, 1);

        HttpResponse<String> registered = register("stranger@example.com", "secret123", "Stranger", "stranger");
        HttpHeaders auth = withCookies(new HttpHeaders(), ACCESS_COOKIE, accessToken(registered));

        HttpResponse<String> response = call(HttpMethod.GET, "/api/orders/" + order.getId(), null, auth);

        assertThat(response.statusCode()).isEqualTo(HttpStatus.FORBIDDEN.value());
        assertThat(readBody(response, ApiError.class).error()).isEqualTo("ORDER_FORBIDDEN");
    }

    @Test
    void getOrder_returnsNotFoundForUnknown() {
        HttpResponse<String> registered = register("buyer@example.com", "secret123", "Buyer", "buyer");
        HttpHeaders auth = withCookies(new HttpHeaders(), ACCESS_COOKIE, accessToken(registered));

        HttpResponse<String> response = call(HttpMethod.GET, "/api/orders/" + UUID.randomUUID(), null, auth);

        assertThat(response.statusCode()).isEqualTo(HttpStatus.NOT_FOUND.value());
        assertThat(readBody(response, ApiError.class).error()).isEqualTo("ORDER_NOT_FOUND");
    }

    // ------------------------------------------------------------------
    // POST /api/orders/{orderId}/approve
    // ------------------------------------------------------------------

    @Test
    void approveOrder_issuesOneValidTicketPerQuantity() {
        HttpResponse<String> registered = register("owner@example.com", "secret123", "Owner", "owner");
        JUsers owner = users.findByEmail("owner@example.com").orElseThrow();
        HttpHeaders auth = withCookies(new HttpHeaders(), ACCESS_COOKIE, accessToken(registered));
        JEvents event = seedEvent(owner, "Event", true);
        JTicketTier tier = seedTier(event, "VIP", "40.00", 10);
        JEventPaymentMethod method = seedPaymentMethod(event, true);
        JTicketOrder order = seedOrder(event, owner, method, OrderStatus.PENDING, tier, 2);

        HttpResponse<String> response = call(HttpMethod.POST, "/api/orders/" + order.getId() + "/approve", null, auth);

        assertThat(response.statusCode()).isEqualTo(HttpStatus.OK.value());
        OrderResponse body = readBody(response, OrderResponse.class);
        assertThat(body.orderStatus()).isEqualTo(OrderStatus.APPROVED);
        assertThat(body.reviewedBy()).isNotNull();
        assertThat(body.reviewedAt()).isNotNull();
        assertThat(body.tickets()).hasSize(2);
        assertThat(body.tickets()).allSatisfy(ticket -> assertThat(ticket.ticketStatus()).isEqualTo(TicketStatus.VALID));

        List<JTicket> issued = tickets.findByOrder_Id(order.getId());
        assertThat(issued).hasSize(2);
        assertThat(issued).allSatisfy(ticket -> {
            assertThat(ticket.getTicketStatus()).isEqualTo(TicketStatus.VALID);
            assertThat(ticket.getPricePaid()).isEqualByComparingTo("40.00");
            assertThat(ticket.getValidUntil()).isEqualTo(END);
            assertThat(ticket.getOrder().getId()).isEqualTo(order.getId());
        });
    }

    @Test
    void approveOrder_allowsAdmin() {
        JUsers owner = seedUser("owner@example.com", "owner", "secret123");
        JEvents event = seedEvent(owner, "Event", true);
        JTicketTier tier = seedTier(event, "VIP", "40.00", 10);
        JEventPaymentMethod method = seedPaymentMethod(event, true);
        JTicketOrder order = seedOrder(event, owner, method, OrderStatus.PENDING, tier, 1);
        HttpResponse<String> registered = register("admin@example.com", "secret123", "Admin", "admin");
        JUsers admin = users.findByEmail("admin@example.com").orElseThrow();
        seedOrganizer(admin, event, EventRole.ADMIN);
        HttpHeaders auth = withCookies(new HttpHeaders(), ACCESS_COOKIE, accessToken(registered));

        HttpResponse<String> response = call(HttpMethod.POST, "/api/orders/" + order.getId() + "/approve", null, auth);

        assertThat(response.statusCode()).isEqualTo(HttpStatus.OK.value());
        assertThat(tickets.findByOrder_Id(order.getId())).hasSize(1);
    }

    @Test
    void approveOrder_rejectsWhenSoldOut() {
        HttpResponse<String> registered = register("owner@example.com", "secret123", "Owner", "owner");
        JUsers owner = users.findByEmail("owner@example.com").orElseThrow();
        HttpHeaders auth = withCookies(new HttpHeaders(), ACCESS_COOKIE, accessToken(registered));
        JEvents event = seedEvent(owner, "Event", true);
        JTicketTier tier = seedTier(event, "VIP", "40.00", 1);
        JEventPaymentMethod method = seedPaymentMethod(event, true);
        JTicketOrder order = seedOrder(event, owner, method, OrderStatus.PENDING, tier, 2);

        HttpResponse<String> response = call(HttpMethod.POST, "/api/orders/" + order.getId() + "/approve", null, auth);

        assertThat(response.statusCode()).isEqualTo(HttpStatus.CONFLICT.value());
        assertThat(readBody(response, ApiError.class).error()).isEqualTo("SOLD_OUT");
        assertThat(tickets.findByOrder_Id(order.getId())).isEmpty();
    }

    @Test
    void approveOrder_rejectsAlreadyApproved() {
        HttpResponse<String> registered = register("owner@example.com", "secret123", "Owner", "owner");
        JUsers owner = users.findByEmail("owner@example.com").orElseThrow();
        HttpHeaders auth = withCookies(new HttpHeaders(), ACCESS_COOKIE, accessToken(registered));
        JEvents event = seedEvent(owner, "Event", true);
        JTicketTier tier = seedTier(event, "VIP", "40.00", 10);
        JEventPaymentMethod method = seedPaymentMethod(event, true);
        JTicketOrder order = seedOrder(event, owner, method, OrderStatus.APPROVED, tier, 1);

        HttpResponse<String> response = call(HttpMethod.POST, "/api/orders/" + order.getId() + "/approve", null, auth);

        assertThat(response.statusCode()).isEqualTo(HttpStatus.CONFLICT.value());
        assertThat(readBody(response, ApiError.class).error()).isEqualTo("ORDER_NOT_PENDING");
    }

    @Test
    void approveOrder_rejectsNonOrganizer() {
        JUsers owner = seedUser("owner@example.com", "owner", "secret123");
        JEvents event = seedEvent(owner, "Event", true);
        JTicketTier tier = seedTier(event, "VIP", "40.00", 10);
        JEventPaymentMethod method = seedPaymentMethod(event, true);
        JTicketOrder order = seedOrder(event, owner, method, OrderStatus.PENDING, tier, 1);
        HttpResponse<String> registered = register("other@example.com", "secret123", "Other", "other");
        HttpHeaders auth = withCookies(new HttpHeaders(), ACCESS_COOKIE, accessToken(registered));

        HttpResponse<String> response = call(HttpMethod.POST, "/api/orders/" + order.getId() + "/approve", null, auth);

        assertThat(response.statusCode()).isEqualTo(HttpStatus.FORBIDDEN.value());
        assertThat(readBody(response, ApiError.class).error()).isEqualTo("NOT_AN_ORGANIZER");
    }

    @Test
    void approveOrder_rejectsEndedEvent() {
        HttpResponse<String> registered = register("owner@example.com", "secret123", "Owner", "owner");
        JUsers owner = users.findByEmail("owner@example.com").orElseThrow();
        HttpHeaders auth = withCookies(new HttpHeaders(), ACCESS_COOKIE, accessToken(registered));
        JEvents event = seedEvent(owner, "Ended", true, EventStatus.OK,
                Instant.now().minus(Duration.ofHours(1)));
        JTicketTier tier = seedTier(event, "VIP", "40.00", 10);
        JEventPaymentMethod method = seedPaymentMethod(event, true);
        JTicketOrder order = seedOrder(event, owner, method, OrderStatus.PENDING, tier, 1);

        HttpResponse<String> response = call(HttpMethod.POST, "/api/orders/" + order.getId() + "/approve", null, auth);

        assertThat(response.statusCode()).isEqualTo(HttpStatus.CONFLICT.value());
        assertThat(readBody(response, ApiError.class).error()).isEqualTo("EVENT_NOT_OPEN");
    }

    @Test
    void approveOrder_returnsNotFoundForUnknown() {
        HttpResponse<String> registered = register("owner@example.com", "secret123", "Owner", "owner");
        HttpHeaders auth = withCookies(new HttpHeaders(), ACCESS_COOKIE, accessToken(registered));

        HttpResponse<String> response = call(HttpMethod.POST, "/api/orders/" + UUID.randomUUID() + "/approve", null,
                auth);

        assertThat(response.statusCode()).isEqualTo(HttpStatus.NOT_FOUND.value());
        assertThat(readBody(response, ApiError.class).error()).isEqualTo("ORDER_NOT_FOUND");
    }

    @Test
    void approveOrder_requiresAuthentication() {
        JUsers owner = seedUser("owner@example.com", "owner", "secret123");
        JEvents event = seedEvent(owner, "Event", true);
        JTicketTier tier = seedTier(event, "VIP", "40.00", 10);
        JEventPaymentMethod method = seedPaymentMethod(event, true);
        JTicketOrder order = seedOrder(event, owner, method, OrderStatus.PENDING, tier, 1);

        HttpResponse<String> response = call(HttpMethod.POST, "/api/orders/" + order.getId() + "/approve", null,
                new HttpHeaders());

        assertThat(response.statusCode()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
    }

    // ------------------------------------------------------------------
    // POST /api/orders/{orderId}/refuse
    // ------------------------------------------------------------------

    @Test
    void refuseOrder_refusesAndCreatesRefundedTickets() {
        HttpResponse<String> registered = register("owner@example.com", "secret123", "Owner", "owner");
        JUsers owner = users.findByEmail("owner@example.com").orElseThrow();
        HttpHeaders auth = withCookies(new HttpHeaders(), ACCESS_COOKIE, accessToken(registered));
        JEvents event = seedEvent(owner, "Event", true);
        JTicketTier tier = seedTier(event, "VIP", "40.00", 10);
        JEventPaymentMethod method = seedPaymentMethod(event, true);
        JTicketOrder order = seedOrder(event, owner, method, OrderStatus.PENDING, tier, 2);

        HttpResponse<String> response = call(HttpMethod.POST, "/api/orders/" + order.getId() + "/refuse",
                new OrderRefuseRequest("Transfer could not be matched"), auth);

        assertThat(response.statusCode()).isEqualTo(HttpStatus.OK.value());
        OrderResponse body = readBody(response, OrderResponse.class);
        assertThat(body.orderStatus()).isEqualTo(OrderStatus.REFUSED);
        assertThat(body.refusalReason()).isEqualTo("Transfer could not be matched");
        assertThat(body.reviewedBy()).isNotNull();
        assertThat(body.tickets()).hasSize(2);
        assertThat(body.tickets())
                .allSatisfy(ticket -> assertThat(ticket.ticketStatus()).isEqualTo(TicketStatus.REFUNDED));

        List<JTicket> stored = tickets.findByOrder_Id(order.getId());
        assertThat(stored).hasSize(2);
        assertThat(stored).allSatisfy(ticket -> {
            assertThat(ticket.getTicketStatus()).isEqualTo(TicketStatus.REFUNDED);
            assertThat(ticket.getPricePaid()).isEqualByComparingTo("0.00");
        });
    }

    @Test
    void refuseOrder_rejectsBlankReason() {
        HttpResponse<String> registered = register("owner@example.com", "secret123", "Owner", "owner");
        JUsers owner = users.findByEmail("owner@example.com").orElseThrow();
        HttpHeaders auth = withCookies(new HttpHeaders(), ACCESS_COOKIE, accessToken(registered));
        JEvents event = seedEvent(owner, "Event", true);
        JTicketTier tier = seedTier(event, "VIP", "40.00", 10);
        JEventPaymentMethod method = seedPaymentMethod(event, true);
        JTicketOrder order = seedOrder(event, owner, method, OrderStatus.PENDING, tier, 1);

        HttpResponse<String> response = call(HttpMethod.POST, "/api/orders/" + order.getId() + "/refuse",
                new OrderRefuseRequest(" "), auth);

        assertThat(response.statusCode()).isEqualTo(HttpStatus.BAD_REQUEST.value());
        assertThat(readBody(response, ApiError.class).error()).isEqualTo("VALIDATION_ERROR");
    }

    @Test
    void refuseOrder_rejectsNonOrganizer() {
        JUsers owner = seedUser("owner@example.com", "owner", "secret123");
        JEvents event = seedEvent(owner, "Event", true);
        JTicketTier tier = seedTier(event, "VIP", "40.00", 10);
        JEventPaymentMethod method = seedPaymentMethod(event, true);
        JTicketOrder order = seedOrder(event, owner, method, OrderStatus.PENDING, tier, 1);
        HttpResponse<String> registered = register("other@example.com", "secret123", "Other", "other");
        HttpHeaders auth = withCookies(new HttpHeaders(), ACCESS_COOKIE, accessToken(registered));

        HttpResponse<String> response = call(HttpMethod.POST, "/api/orders/" + order.getId() + "/refuse",
                new OrderRefuseRequest("nope"), auth);

        assertThat(response.statusCode()).isEqualTo(HttpStatus.FORBIDDEN.value());
        assertThat(readBody(response, ApiError.class).error()).isEqualTo("NOT_AN_ORGANIZER");
    }

    @Test
    void refuseOrder_rejectsAlreadyApproved() {
        HttpResponse<String> registered = register("owner@example.com", "secret123", "Owner", "owner");
        JUsers owner = users.findByEmail("owner@example.com").orElseThrow();
        HttpHeaders auth = withCookies(new HttpHeaders(), ACCESS_COOKIE, accessToken(registered));
        JEvents event = seedEvent(owner, "Event", true);
        JTicketTier tier = seedTier(event, "VIP", "40.00", 10);
        JEventPaymentMethod method = seedPaymentMethod(event, true);
        JTicketOrder order = seedOrder(event, owner, method, OrderStatus.APPROVED, tier, 1);

        HttpResponse<String> response = call(HttpMethod.POST, "/api/orders/" + order.getId() + "/refuse",
                new OrderRefuseRequest("nope"), auth);

        assertThat(response.statusCode()).isEqualTo(HttpStatus.CONFLICT.value());
        assertThat(readBody(response, ApiError.class).error()).isEqualTo("ORDER_NOT_PENDING");
    }

    @Test
    void refuseOrder_returnsNotFoundForUnknown() {
        HttpResponse<String> registered = register("owner@example.com", "secret123", "Owner", "owner");
        HttpHeaders auth = withCookies(new HttpHeaders(), ACCESS_COOKIE, accessToken(registered));

        HttpResponse<String> response = call(HttpMethod.POST, "/api/orders/" + UUID.randomUUID() + "/refuse",
                new OrderRefuseRequest("nope"), auth);

        assertThat(response.statusCode()).isEqualTo(HttpStatus.NOT_FOUND.value());
        assertThat(readBody(response, ApiError.class).error()).isEqualTo("ORDER_NOT_FOUND");
    }

    @Test
    void refuseOrder_requiresAuthentication() {
        JUsers owner = seedUser("owner@example.com", "owner", "secret123");
        JEvents event = seedEvent(owner, "Event", true);
        JTicketTier tier = seedTier(event, "VIP", "40.00", 10);
        JEventPaymentMethod method = seedPaymentMethod(event, true);
        JTicketOrder order = seedOrder(event, owner, method, OrderStatus.PENDING, tier, 1);

        HttpResponse<String> response = call(HttpMethod.POST, "/api/orders/" + order.getId() + "/refuse",
                new OrderRefuseRequest("nope"), new HttpHeaders());

        assertThat(response.statusCode()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
    }

    // ------------------------------------------------------------------
    // cross-flow
    // ------------------------------------------------------------------

    @Test
    void approveThenRefuse_rejectsSecondReview() {
        HttpResponse<String> registered = register("owner@example.com", "secret123", "Owner", "owner");
        JUsers owner = users.findByEmail("owner@example.com").orElseThrow();
        HttpHeaders auth = withCookies(new HttpHeaders(), ACCESS_COOKIE, accessToken(registered));
        JEvents event = seedEvent(owner, "Event", true);
        JTicketTier tier = seedTier(event, "VIP", "40.00", 10);
        JEventPaymentMethod method = seedPaymentMethod(event, true);
        JTicketOrder order = seedOrder(event, owner, method, OrderStatus.PENDING, tier, 1);

        HttpResponse<String> approved = call(HttpMethod.POST, "/api/orders/" + order.getId() + "/approve", null, auth);
        assertThat(approved.statusCode()).isEqualTo(HttpStatus.OK.value());

        HttpResponse<String> refused = call(HttpMethod.POST, "/api/orders/" + order.getId() + "/refuse",
                new OrderRefuseRequest("too late"), auth);

        assertThat(refused.statusCode()).isEqualTo(HttpStatus.CONFLICT.value());
        assertThat(readBody(refused, ApiError.class).error()).isEqualTo("ORDER_NOT_PENDING");
    }

    // ------------------------------------------------------------------
    // fixtures
    // ------------------------------------------------------------------

    private JEvents seedEvent(JUsers owner, String title, boolean isPublic) {
        return seedEvent(owner, title, isPublic, EventStatus.OK, END);
    }

    private JEvents seedEvent(JUsers owner, String title, boolean isPublic, EventStatus status, Instant endTime) {
        JEvents event = new JEvents();
        event.setTitle(title);
        event.setStartTime(endTime.minus(Duration.ofHours(4)));
        event.setEndTime(endTime);
        event.setPublic(isPublic);
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

    private JEventPaymentMethod seedPaymentMethod(JEvents event, boolean active) {
        JEventPaymentMethod method = new JEventPaymentMethod();
        method.setEvent(event);
        method.setPaymentType(PaymentType.MOBILE_MONEY);
        method.setProviderName("M-Pesa");
        method.setAccountName("Jane Doe");
        method.setAccountIdentifier("+261340000000");
        method.setActive(active);
        return paymentMethods.saveAndFlush(method);
    }

    private JTicketTier seedTier(JEvents event, String name, String price, int capacity) {
        JTicketTier tier = new JTicketTier();
        tier.setEvent(event);
        tier.setTierName(name);
        tier.setPrice(new BigDecimal(price));
        tier.setCapacity(capacity);
        return tiers.saveAndFlush(tier);
    }

    private JTicketOrder seedOrder(JEvents event, JUsers owner, JEventPaymentMethod method,
            OrderStatus status, JTicketTier tier, int quantity) {
        JTicketOrder order = new JTicketOrder();
        order.setOwner(owner);
        order.setEvent(event);
        order.setPaymentMethod(method);
        order.setPriceUnit("USD");
        order.setOrderStatus(status);

        JTicketOrderItem item = new JTicketOrderItem();
        item.setOrder(order);
        item.setTier(tier);
        item.setQuantity(quantity);
        item.setUnitPrice(tier.getPrice());
        order.getItems().add(item);

        return orders.saveAndFlush(order);
    }
}
