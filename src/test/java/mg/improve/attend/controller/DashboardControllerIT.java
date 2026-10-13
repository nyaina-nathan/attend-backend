package mg.improve.attend.controller;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;

import mg.improve.attend.TestcontainersConfiguration;
import mg.improve.attend.domain.dto.response.AttentionItem;
import mg.improve.attend.domain.dto.response.AttentionType;
import mg.improve.attend.domain.dto.response.DashboardResponse;
import mg.improve.attend.domain.enums.EventRole;
import mg.improve.attend.domain.enums.EventStatus;
import mg.improve.attend.domain.enums.OrderStatus;
import mg.improve.attend.domain.enums.PaymentType;
import mg.improve.attend.domain.enums.TicketStatus;
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
class DashboardControllerIT extends IntegrationTestSupport {

    @Autowired
    private JEventsRepository events;

    @Autowired
    private JTicketTierRepository tiers;

    @Autowired
    private JEventOrganizerRepository organizers;

    @Autowired
    private JTicketRepository tickets;

    @Autowired
    private JTicketOrderRepository orders;

    @Autowired
    private JEventPaymentMethodRepository paymentMethods;

    @Test
    void getDashboard_requiresAuthentication() {
        HttpResponse<String> response =
                call(HttpMethod.GET, "/api/users/me/dashboard", null, new HttpHeaders());

        assertThat(response.statusCode()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
    }

    @Test
    void getDashboard_returnsZeroedWhenUserHasNoActivity() {
        HttpResponse<String> registered =
                register("user@example.com", "secret123", "Test User", "testuser");
        HttpHeaders headers = withCookies(new HttpHeaders(), ACCESS_COOKIE, accessToken(registered));

        HttpResponse<String> response = call(HttpMethod.GET, "/api/users/me/dashboard", null, headers);

        assertThat(response.statusCode()).isEqualTo(HttpStatus.OK.value());
        assertThat(response.headers().firstValue("Cache-Control"))
                .hasValueSatisfying(value -> assertThat(value).contains("no-store"));

        DashboardResponse body = readBody(response, DashboardResponse.class);
        assertThat(body.organizing().eventsTotal()).isZero();
        assertThat(body.organizing().eventsUpcoming()).isZero();
        assertThat(body.organizing().ticketsSold()).isZero();
        assertThat(body.organizing().revenue()).isEqualByComparingTo("0");
        assertThat(body.organizing().nextEvent()).isNull();
        assertThat(body.organizing().attention()).isEmpty();
        assertThat(body.attending().ticketsUpcoming()).isZero();
        assertThat(body.attending().nextTicket()).isNull();
    }

    @Test
    void getDashboard_aggregatesOrganizingAndAttending() {
        HttpResponse<String> registered =
                register("owner@example.com", "secret123", "Owner", "owner");
        JUsers caller = users.findByEmail("owner@example.com").orElseThrow();
        JUsers buyer = seedUser("buyer@example.com", "buyer", "secret123");
        HttpHeaders headers = withCookies(new HttpHeaders(), ACCESS_COOKIE, accessToken(registered));

        Instant now = Instant.now();

        // Organized event: upcoming OK with a tier that ends up low on stock (13 - 10 = 3 left).
        JEvents organized = seedEvent("Tana Jazz Night", now.plus(Duration.ofDays(30)), EventStatus.OK);
        JTicketTier vip = seedTier(organized, "VIP", new BigDecimal("40.00"), 13);
        seedOrganizer(caller, organized, EventRole.CREATOR);
        for (int i = 0; i < 10; i++) {
            seedTicket(buyer, vip, new BigDecimal("40.00"),
                    i < 3 ? TicketStatus.USED : TicketStatus.VALID);
        }
        JEventPaymentMethod method = seedPaymentMethod(organized);
        seedPendingOrder(organized, buyer, method);
        seedPendingOrder(organized, buyer, method);

        // Attending event: upcoming OK, not organized by the caller; the caller holds a VALID ticket.
        JEvents attending = seedEvent("Antsirabe Food Fair", now.plus(Duration.ofDays(5)), EventStatus.OK);
        JTicketTier standard = seedTier(attending, "Standard", new BigDecimal("10.00"), 100);
        seedTicket(caller, standard, new BigDecimal("10.00"), TicketStatus.VALID);

        HttpResponse<String> response = call(HttpMethod.GET, "/api/users/me/dashboard", null, headers);

        assertThat(response.statusCode()).isEqualTo(HttpStatus.OK.value());
        DashboardResponse body = readBody(response, DashboardResponse.class);

        assertThat(body.organizing().eventsTotal()).isEqualTo(1);
        assertThat(body.organizing().eventsUpcoming()).isEqualTo(1);
        assertThat(body.organizing().ticketsSold()).isEqualTo(10);
        assertThat(body.organizing().revenue()).isEqualByComparingTo("400.00");
        assertThat(body.organizing().nextEvent()).isNotNull();
        assertThat(body.organizing().nextEvent().id()).isEqualTo(organized.getId());
        assertThat(body.organizing().nextEvent().myRole()).isEqualTo(EventRole.CREATOR);
        assertThat(body.organizing().nextEvent().totalCapacity()).isEqualTo(13);
        assertThat(body.organizing().nextEvent().ticketsIssued()).isEqualTo(10);
        assertThat(body.organizing().nextEvent().ticketsCheckedIn()).isEqualTo(3);

        assertThat(body.organizing().attention()).hasSize(2);
        AttentionItem item = body.organizing().attention().get(0);
        assertThat(item.type()).isEqualTo(AttentionType.LOW_STOCK);
        assertThat(item.eventId()).isEqualTo(organized.getId());
        assertThat(item.tierName()).isEqualTo("VIP");
        assertThat(item.remaining()).isEqualTo(3);
        assertThat(item.ticketsIssued()).isNull();
        assertThat(item.pendingOrders()).isNull();

        AttentionItem pending = body.organizing().attention().get(1);
        assertThat(pending.type()).isEqualTo(AttentionType.PENDING_ORDERS);
        assertThat(pending.eventId()).isEqualTo(organized.getId());
        assertThat(pending.pendingOrders()).isEqualTo(2);
        assertThat(pending.tierId()).isNull();

        assertThat(body.attending().ticketsUpcoming()).isEqualTo(1);
        assertThat(body.attending().nextTicket()).isNotNull();
        assertThat(body.attending().nextTicket().event().id()).isEqualTo(attending.getId());
        assertThat(body.attending().nextTicket().tier().tierName()).isEqualTo("Standard");
        assertThat(body.attending().nextTicket().ticketStatus()).isEqualTo(TicketStatus.VALID);
    }

    // ------------------------------------------------------------------
    // seeding
    // ------------------------------------------------------------------

    private JEvents seedEvent(String title, Instant start, EventStatus status) {
        JEvents event = new JEvents();
        event.setTitle(title);
        event.setStartTime(start);
        event.setEndTime(start.plus(Duration.ofHours(2)));
        event.setPublic(true);
        event.setStatus(status);
        event.setLocation("Antananarivo");
        return events.saveAndFlush(event);
    }

    private JTicketTier seedTier(JEvents event, String name, BigDecimal price, int capacity) {
        JTicketTier tier = new JTicketTier();
        tier.setEvent(event);
        tier.setTierName(name);
        tier.setPrice(price);
        tier.setCapacity(capacity);
        return tiers.saveAndFlush(tier);
    }

    private void seedOrganizer(JUsers user, JEvents event, EventRole role) {
        JEventOrganizer organizer = new JEventOrganizer();
        organizer.setUser(user);
        organizer.setEvent(event);
        organizer.setEventRole(role);
        organizers.saveAndFlush(organizer);
    }

    private void seedTicket(JUsers owner, JTicketTier tier, BigDecimal price, TicketStatus status) {
        JTicket ticket = new JTicket();
        ticket.setOwner(owner);
        ticket.setTier(tier);
        ticket.setPricePaid(price);
        ticket.setTicketStatus(status);
        ticket.setValidUntil(tier.getEvent().getEndTime());
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
