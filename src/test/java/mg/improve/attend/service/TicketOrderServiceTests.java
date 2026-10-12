package mg.improve.attend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import mg.improve.attend.domain.dto.request.OrderCreateRequest;
import mg.improve.attend.domain.dto.request.OrderItemCreateRequest;
import mg.improve.attend.domain.dto.request.OrderRefuseRequest;
import mg.improve.attend.domain.dto.response.OrderResponse;
import mg.improve.attend.domain.dto.response.PageOrder;
import mg.improve.attend.domain.entity.EventPaymentMethod;
import mg.improve.attend.domain.entity.Events;
import mg.improve.attend.domain.entity.TicketOrder;
import mg.improve.attend.domain.entity.TicketOrderItem;
import mg.improve.attend.domain.entity.Users;
import mg.improve.attend.domain.enums.EventStatus;
import mg.improve.attend.domain.enums.OrderStatus;
import mg.improve.attend.domain.enums.TicketStatus;
import mg.improve.attend.domain.mapper.TicketOrderMapper;
import mg.improve.attend.exceptions.BadRequestException;
import mg.improve.attend.exceptions.ConflictException;
import mg.improve.attend.exceptions.ForbiddenException;
import mg.improve.attend.exceptions.NotFoundException;
import mg.improve.attend.repository.JEventOrganizerRepository;
import mg.improve.attend.repository.JEventPaymentMethodRepository;
import mg.improve.attend.repository.JEventsRepository;
import mg.improve.attend.repository.JTicketOrderItemRepository;
import mg.improve.attend.repository.JTicketOrderRepository;
import mg.improve.attend.repository.JTicketRepository;
import mg.improve.attend.repository.JTicketTierRepository;
import mg.improve.attend.repository.JUsersRepository;
import mg.improve.attend.repository.entities.JEventPaymentMethod;
import mg.improve.attend.repository.entities.JEvents;
import mg.improve.attend.repository.entities.JTicket;
import mg.improve.attend.repository.entities.JTicketOrder;
import mg.improve.attend.repository.entities.JTicketOrderItem;
import mg.improve.attend.repository.entities.JTicketTier;
import mg.improve.attend.repository.entities.JUsers;
import mg.improve.attend.validator.OrderValidator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;

@ExtendWith(MockitoExtension.class)
class TicketOrderServiceTests {

    private static final List<TicketStatus> CAPACITY_STATUSES =
            List.of(TicketStatus.VALID, TicketStatus.USED);
    private static final Instant END = Instant.parse("2026-11-20T22:00:00Z");

    @Mock
    private JEventsRepository events;

    @Mock
    private JEventOrganizerRepository organizers;

    @Mock
    private JEventPaymentMethodRepository paymentMethods;

    @Mock
    private JTicketTierRepository tiers;

    @Mock
    private JTicketOrderRepository orders;

    @Mock
    private JTicketOrderItemRepository orderItems;

    @Mock
    private JTicketRepository tickets;

    @Mock
    private JUsersRepository users;

    @Mock
    private TicketOrderMapper orderMapper;

    @Mock
    private OrderValidator validator;

    @InjectMocks
    private TicketOrderService orderService;

    // ------------------------------------------------------------------
    // createOrder
    // ------------------------------------------------------------------

    @Test
    void createOrder_persistsPendingOrderAndSnapshotsUnitPrice() {
        UUID eventId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID methodId = UUID.randomUUID();
        UUID tierId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        JEvents event = eventEntity(eventId, EventStatus.OK, END);
        JEventPaymentMethod method = paymentMethodEntity(methodId, event, true);
        JTicketTier tier = tierEntity(tierId, event, "40.00", 10);
        JTicketOrder saved = orderEntity(orderId, event, userEntity(userId), method, OrderStatus.PENDING);
        OrderCreateRequest request = new OrderCreateRequest(methodId, null,
                List.of(new OrderItemCreateRequest(tierId, 2)));

        when(events.findById(eventId)).thenReturn(Optional.of(event));
        when(paymentMethods.findById(methodId)).thenReturn(Optional.of(method));
        when(users.findById(userId)).thenReturn(Optional.of(userEntity(userId)));
        when(tiers.findById(tierId)).thenReturn(Optional.of(tier));
        when(orders.saveAndFlush(any(JTicketOrder.class))).thenReturn(saved);
        when(orderMapper.toDomain(saved)).thenReturn(orderDomain(orderId, eventId, userId, methodId, List.of()));

        OrderResponse response = orderService.createOrder(eventId, userId, request);

        verify(validator).validateCreate(request);
        ArgumentCaptor<JTicketOrder> captor = ArgumentCaptor.forClass(JTicketOrder.class);
        verify(orders).saveAndFlush(captor.capture());
        JTicketOrder captured = captor.getValue();
        assertThat(captured.getOrderStatus()).isEqualTo(OrderStatus.PENDING);
        assertThat(captured.getTransactionReference()).isNull();
        assertThat(captured.getPriceUnit()).isEqualTo("USD");
        assertThat(captured.getItems()).hasSize(1);
        assertThat(captured.getItems().get(0).getUnitPrice()).isEqualByComparingTo("40.00");
        assertThat(captured.getItems().get(0).getQuantity()).isEqualTo(2);
        assertThat(response.id()).isEqualTo(orderId);
    }

    @Test
    void createOrder_keepsProvidedTransactionReference() {
        UUID eventId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID methodId = UUID.randomUUID();
        UUID tierId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        JEvents event = eventEntity(eventId, EventStatus.OK, END);
        JEventPaymentMethod method = paymentMethodEntity(methodId, event, true);
        JTicketTier tier = tierEntity(tierId, event, "40.00", 10);
        JTicketOrder saved = orderEntity(orderId, event, userEntity(userId), method, OrderStatus.PENDING);
        OrderCreateRequest request = new OrderCreateRequest(methodId, "REF-1",
                List.of(new OrderItemCreateRequest(tierId, 1)));

        when(events.findById(eventId)).thenReturn(Optional.of(event));
        when(paymentMethods.findById(methodId)).thenReturn(Optional.of(method));
        when(orders.existsByPaymentMethod_IdAndTransactionReference(methodId, "REF-1")).thenReturn(false);
        when(users.findById(userId)).thenReturn(Optional.of(userEntity(userId)));
        when(tiers.findById(tierId)).thenReturn(Optional.of(tier));
        when(orders.saveAndFlush(any(JTicketOrder.class))).thenReturn(saved);
        when(orderMapper.toDomain(saved)).thenReturn(orderDomain(orderId, eventId, userId, methodId, List.of()));

        orderService.createOrder(eventId, userId, request);

        ArgumentCaptor<JTicketOrder> captor = ArgumentCaptor.forClass(JTicketOrder.class);
        verify(orders).saveAndFlush(captor.capture());
        assertThat(captor.getValue().getTransactionReference()).isEqualTo("REF-1");
    }

    @Test
    void createOrder_rejectsUnknownEvent() {
        UUID eventId = UUID.randomUUID();
        when(events.findById(eventId)).thenReturn(Optional.empty());

        assertThatExceptionOfType(NotFoundException.class)
                .isThrownBy(() -> orderService.createOrder(eventId, UUID.randomUUID(),
                        new OrderCreateRequest(UUID.randomUUID(), null,
                                List.of(new OrderItemCreateRequest(UUID.randomUUID(), 1)))))
                .satisfies(ex -> assertThat(ex.getCode()).isEqualTo("EVENT_NOT_FOUND"));

        verifyNoInteractions(paymentMethods, validator, orders);
    }

    @Test
    void createOrder_rejectsPaymentMethodFromAnotherEvent() {
        UUID eventId = UUID.randomUUID();
        UUID methodId = UUID.randomUUID();
        JEvents event = eventEntity(eventId, EventStatus.OK, END);
        JEvents otherEvent = eventEntity(UUID.randomUUID(), EventStatus.OK, END);
        JEventPaymentMethod method = paymentMethodEntity(methodId, otherEvent, true);
        OrderCreateRequest request = new OrderCreateRequest(methodId, null,
                List.of(new OrderItemCreateRequest(UUID.randomUUID(), 1)));

        when(events.findById(eventId)).thenReturn(Optional.of(event));
        when(paymentMethods.findById(methodId)).thenReturn(Optional.of(method));

        assertThatExceptionOfType(NotFoundException.class)
                .isThrownBy(() -> orderService.createOrder(eventId, UUID.randomUUID(), request))
                .satisfies(ex -> assertThat(ex.getCode()).isEqualTo("PAYMENT_METHOD_NOT_FOUND"));

        verifyNoInteractions(orders);
    }

    @Test
    void createOrder_rejectsInactivePaymentMethod() {
        UUID eventId = UUID.randomUUID();
        UUID methodId = UUID.randomUUID();
        JEvents event = eventEntity(eventId, EventStatus.OK, END);
        JEventPaymentMethod method = paymentMethodEntity(methodId, event, false);
        OrderCreateRequest request = new OrderCreateRequest(methodId, null,
                List.of(new OrderItemCreateRequest(UUID.randomUUID(), 1)));

        when(events.findById(eventId)).thenReturn(Optional.of(event));
        when(paymentMethods.findById(methodId)).thenReturn(Optional.of(method));

        assertThatExceptionOfType(ConflictException.class)
                .isThrownBy(() -> orderService.createOrder(eventId, UUID.randomUUID(), request))
                .satisfies(ex -> {
                    assertThat(ex.getCode()).isEqualTo("PAYMENT_METHOD_INACTIVE");
                    assertThat(ex.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                });

        verifyNoInteractions(orders);
    }

    @Test
    void createOrder_rejectsDuplicateTransactionReference() {
        UUID eventId = UUID.randomUUID();
        UUID methodId = UUID.randomUUID();
        JEvents event = eventEntity(eventId, EventStatus.OK, END);
        JEventPaymentMethod method = paymentMethodEntity(methodId, event, true);
        OrderCreateRequest request = new OrderCreateRequest(methodId, "REF-1",
                List.of(new OrderItemCreateRequest(UUID.randomUUID(), 1)));

        when(events.findById(eventId)).thenReturn(Optional.of(event));
        when(paymentMethods.findById(methodId)).thenReturn(Optional.of(method));
        when(orders.existsByPaymentMethod_IdAndTransactionReference(methodId, "REF-1")).thenReturn(true);

        assertThatExceptionOfType(ConflictException.class)
                .isThrownBy(() -> orderService.createOrder(eventId, UUID.randomUUID(), request))
                .satisfies(ex -> assertThat(ex.getCode()).isEqualTo("DUPLICATE_TRANSACTION_REFERENCE"));

        verify(orders, never()).saveAndFlush(any());
        verifyNoInteractions(users);
    }

    @Test
    void createOrder_rejectsClosedEvent() {
        UUID eventId = UUID.randomUUID();
        UUID methodId = UUID.randomUUID();
        JEvents event = eventEntity(eventId, EventStatus.CANCELLED, END);
        JEventPaymentMethod method = paymentMethodEntity(methodId, event, true);
        OrderCreateRequest request = new OrderCreateRequest(methodId, null,
                List.of(new OrderItemCreateRequest(UUID.randomUUID(), 1)));

        when(events.findById(eventId)).thenReturn(Optional.of(event));
        when(paymentMethods.findById(methodId)).thenReturn(Optional.of(method));

        assertThatExceptionOfType(ConflictException.class)
                .isThrownBy(() -> orderService.createOrder(eventId, UUID.randomUUID(), request))
                .satisfies(ex -> assertThat(ex.getCode()).isEqualTo("EVENT_NOT_OPEN"));

        verifyNoInteractions(users, orders);
    }

    @Test
    void createOrder_rejectsUnknownTier() {
        UUID eventId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID methodId = UUID.randomUUID();
        UUID tierId = UUID.randomUUID();
        JEvents event = eventEntity(eventId, EventStatus.OK, END);
        JEventPaymentMethod method = paymentMethodEntity(methodId, event, true);
        OrderCreateRequest request = new OrderCreateRequest(methodId, null,
                List.of(new OrderItemCreateRequest(tierId, 1)));

        when(events.findById(eventId)).thenReturn(Optional.of(event));
        when(paymentMethods.findById(methodId)).thenReturn(Optional.of(method));
        when(users.findById(userId)).thenReturn(Optional.of(userEntity(userId)));
        when(tiers.findById(tierId)).thenReturn(Optional.empty());

        assertThatExceptionOfType(NotFoundException.class)
                .isThrownBy(() -> orderService.createOrder(eventId, userId, request))
                .satisfies(ex -> assertThat(ex.getCode()).isEqualTo("TIER_NOT_FOUND"));

        verifyNoInteractions(orderMapper);
    }

    @Test
    void createOrder_rejectsTierFromAnotherEvent() {
        UUID eventId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID methodId = UUID.randomUUID();
        UUID tierId = UUID.randomUUID();
        JEvents event = eventEntity(eventId, EventStatus.OK, END);
        JEventPaymentMethod method = paymentMethodEntity(methodId, event, true);
        JTicketTier foreignTier = tierEntity(tierId, eventEntity(UUID.randomUUID(), EventStatus.OK, END),
                "10.00", 5);
        OrderCreateRequest request = new OrderCreateRequest(methodId, null,
                List.of(new OrderItemCreateRequest(tierId, 1)));

        when(events.findById(eventId)).thenReturn(Optional.of(event));
        when(paymentMethods.findById(methodId)).thenReturn(Optional.of(method));
        when(users.findById(userId)).thenReturn(Optional.of(userEntity(userId)));
        when(tiers.findById(tierId)).thenReturn(Optional.of(foreignTier));

        assertThatExceptionOfType(NotFoundException.class)
                .isThrownBy(() -> orderService.createOrder(eventId, userId, request))
                .satisfies(ex -> assertThat(ex.getCode()).isEqualTo("TIER_NOT_FOUND"));

        verify(orders, never()).saveAndFlush(any());
    }

    @Test
    void createOrder_propagatesValidationFailure() {
        UUID eventId = UUID.randomUUID();
        UUID methodId = UUID.randomUUID();
        JEvents event = eventEntity(eventId, EventStatus.OK, END);
        OrderCreateRequest request = new OrderCreateRequest(methodId, null, List.of());

        when(events.findById(eventId)).thenReturn(Optional.of(event));
        doThrow(new BadRequestException("VALIDATION_ERROR", "Validation failed"))
                .when(validator).validateCreate(any(OrderCreateRequest.class));

        assertThatExceptionOfType(BadRequestException.class)
                .isThrownBy(() -> orderService.createOrder(eventId, UUID.randomUUID(), request))
                .satisfies(ex -> assertThat(ex.getCode()).isEqualTo("VALIDATION_ERROR"));

        verifyNoInteractions(paymentMethods, orders);
    }

    // ------------------------------------------------------------------
    // listMyOrders
    // ------------------------------------------------------------------

    @Test
    void listMyOrders_mapsPage() {
        UUID userId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        JTicketOrder order = orderEntity(orderId, eventEntity(UUID.randomUUID(), EventStatus.OK, END),
                userEntity(userId), null, OrderStatus.PENDING);
        Page<JTicketOrder> page = new PageImpl<>(List.of(order));

        when(orders.findMyOrders(any(), any(), any(Pageable.class))).thenReturn(page);
        when(orderItems.findByOrder_IdIn(anyCollection())).thenReturn(List.of());
        when(tickets.findByOrder_IdIn(anyCollection())).thenReturn(List.of());
        when(orderMapper.toDomain(any(JTicketOrder.class), anyList()))
                .thenReturn(orderDomain(orderId, UUID.randomUUID(), userId, UUID.randomUUID(), List.of()));

        PageOrder response = orderService.listMyOrders(userId, null, 0, 20);

        assertThat(response.content()).hasSize(1);
        verify(validator).validateListQuery(0, 20);
    }

    @Test
    void listMyOrders_propagatesListQueryValidation() {
        UUID userId = UUID.randomUUID();
        doThrow(new BadRequestException("VALIDATION_ERROR", "Validation failed"))
                .when(validator).validateListQuery(anyInt(), anyInt());

        assertThatExceptionOfType(BadRequestException.class)
                .isThrownBy(() -> orderService.listMyOrders(userId, null, 0, 0));

        verifyNoInteractions(orders);
    }

    // ------------------------------------------------------------------
    // listEventOrders
    // ------------------------------------------------------------------

    @Test
    void listEventOrders_returnsPageForOrganizer() {
        UUID eventId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        JTicketOrder order = orderEntity(orderId, eventEntity(eventId, EventStatus.OK, END),
                userEntity(UUID.randomUUID()), null, OrderStatus.PENDING);

        when(events.findById(eventId)).thenReturn(Optional.of(eventEntity(eventId, EventStatus.OK, END)));
        when(organizers.existsByEvent_IdAndUser_Id(eventId, userId)).thenReturn(true);
        when(orders.findEventOrders(any(), any(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(order)));
        when(orderItems.findByOrder_IdIn(anyCollection())).thenReturn(List.of());
        when(tickets.findByOrder_IdIn(anyCollection())).thenReturn(List.of());
        when(orderMapper.toDomain(any(JTicketOrder.class), anyList()))
                .thenReturn(orderDomain(orderId, eventId, UUID.randomUUID(), UUID.randomUUID(), List.of()));

        PageOrder response = orderService.listEventOrders(eventId, userId, null, 0, 20);

        assertThat(response.content()).hasSize(1);
    }

    @Test
    void listEventOrders_rejectsUnknownEvent() {
        UUID eventId = UUID.randomUUID();
        when(events.findById(eventId)).thenReturn(Optional.empty());

        assertThatExceptionOfType(NotFoundException.class)
                .isThrownBy(() -> orderService.listEventOrders(eventId, UUID.randomUUID(), null, 0, 20))
                .satisfies(ex -> assertThat(ex.getCode()).isEqualTo("EVENT_NOT_FOUND"));

        verifyNoInteractions(organizers, orders);
    }

    @Test
    void listEventOrders_rejectsNonOrganizer() {
        UUID eventId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        when(events.findById(eventId)).thenReturn(Optional.of(eventEntity(eventId, EventStatus.OK, END)));
        when(organizers.existsByEvent_IdAndUser_Id(eventId, userId)).thenReturn(false);

        assertThatExceptionOfType(ForbiddenException.class)
                .isThrownBy(() -> orderService.listEventOrders(eventId, userId, null, 0, 20))
                .satisfies(ex -> assertThat(ex.getCode()).isEqualTo("NOT_AN_ORGANIZER"));

        verifyNoInteractions(orders, validator);
    }

    // ------------------------------------------------------------------
    // getOrder
    // ------------------------------------------------------------------

    @Test
    void getOrder_returnsForOwner() {
        UUID eventId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        JTicketOrder order = orderEntity(orderId, eventEntity(eventId, EventStatus.OK, END),
                userEntity(userId), null, OrderStatus.PENDING);

        when(orders.findDetailById(orderId)).thenReturn(Optional.of(order));
        when(tickets.findByOrder_Id(orderId)).thenReturn(List.of());
        when(orderMapper.toDomain(order)).thenReturn(orderDomain(orderId, eventId, userId, UUID.randomUUID(), List.of()));

        OrderResponse response = orderService.getOrder(orderId, userId);

        assertThat(response.id()).isEqualTo(orderId);
    }

    @Test
    void getOrder_returnsForOrganizer() {
        UUID eventId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        JTicketOrder order = orderEntity(orderId, eventEntity(eventId, EventStatus.OK, END),
                userEntity(UUID.randomUUID()), null, OrderStatus.PENDING);

        when(orders.findDetailById(orderId)).thenReturn(Optional.of(order));
        when(organizers.existsByEvent_IdAndUser_Id(eventId, userId)).thenReturn(true);
        when(tickets.findByOrder_Id(orderId)).thenReturn(List.of());
        when(orderMapper.toDomain(order)).thenReturn(orderDomain(orderId, eventId, UUID.randomUUID(), UUID.randomUUID(), List.of()));

        assertThat(orderService.getOrder(orderId, userId).id()).isEqualTo(orderId);
    }

    @Test
    void getOrder_rejectsCallerWhoIsNeitherOwnerNorOrganizer() {
        UUID eventId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        JTicketOrder order = orderEntity(orderId, eventEntity(eventId, EventStatus.OK, END),
                userEntity(UUID.randomUUID()), null, OrderStatus.PENDING);

        when(orders.findDetailById(orderId)).thenReturn(Optional.of(order));
        when(organizers.existsByEvent_IdAndUser_Id(eventId, userId)).thenReturn(false);

        assertThatExceptionOfType(ForbiddenException.class)
                .isThrownBy(() -> orderService.getOrder(orderId, userId))
                .satisfies(ex -> assertThat(ex.getCode()).isEqualTo("ORDER_FORBIDDEN"));

        verifyNoInteractions(orderMapper);
    }

    @Test
    void getOrder_rejectsUnknownOrder() {
        UUID orderId = UUID.randomUUID();
        when(orders.findDetailById(orderId)).thenReturn(Optional.empty());

        assertThatExceptionOfType(NotFoundException.class)
                .isThrownBy(() -> orderService.getOrder(orderId, UUID.randomUUID()))
                .satisfies(ex -> assertThat(ex.getCode()).isEqualTo("ORDER_NOT_FOUND"));

        verifyNoInteractions(organizers, orderMapper);
    }

    // ------------------------------------------------------------------
    // approveOrder
    // ------------------------------------------------------------------

    @Test
    void approveOrder_issuesOneValidTicketPerQuantity() {
        UUID eventId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID tierId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        JEvents event = eventEntity(eventId, EventStatus.OK, END);
        JTicketTier tier = tierEntity(tierId, event, "40.00", 10);
        JTicketOrder order = orderEntity(orderId, event, userEntity(UUID.randomUUID()),
                paymentMethodEntity(UUID.randomUUID(), event, true), OrderStatus.PENDING);
        order.getItems().add(orderItemEntity(order, tier, 2, "40.00"));

        when(orders.findByIdForUpdate(orderId)).thenReturn(Optional.of(order));
        when(organizers.existsByEvent_IdAndUser_Id(eventId, userId)).thenReturn(true);
        when(users.findById(userId)).thenReturn(Optional.of(userEntity(userId)));
        when(tiers.findByIdForUpdate(tierId)).thenReturn(Optional.of(tier));
        when(tickets.countByTier_IdAndTicketStatusIn(tierId, CAPACITY_STATUSES)).thenReturn(0L);
        when(tickets.save(any(JTicket.class))).thenAnswer(invocation -> {
            JTicket ticket = invocation.getArgument(0);
            ticket.setId(UUID.randomUUID());
            return ticket;
        });
        when(orders.saveAndFlush(order)).thenReturn(order);
        when(orderMapper.toDomain(order)).thenReturn(orderDomain(orderId, eventId, UUID.randomUUID(), UUID.randomUUID(), List.of()));

        orderService.approveOrder(orderId, userId);

        assertThat(order.getOrderStatus()).isEqualTo(OrderStatus.APPROVED);
        assertThat(order.getReviewedBy()).isNotNull();
        assertThat(order.getReviewedAt()).isNotNull();

        ArgumentCaptor<JTicket> captor = ArgumentCaptor.forClass(JTicket.class);
        verify(tickets, times(2)).save(captor.capture());
        assertThat(captor.getAllValues()).allSatisfy(ticket -> {
            assertThat(ticket.getTicketStatus()).isEqualTo(TicketStatus.VALID);
            assertThat(ticket.getPricePaid()).isEqualByComparingTo("40.00");
            assertThat(ticket.getOrder()).isSameAs(order);
            assertThat(ticket.getValidUntil()).isEqualTo(END);
        });
    }

    @Test
    void approveOrder_rejectsUnknownOrder() {
        UUID orderId = UUID.randomUUID();
        when(orders.findByIdForUpdate(orderId)).thenReturn(Optional.empty());

        assertThatExceptionOfType(NotFoundException.class)
                .isThrownBy(() -> orderService.approveOrder(orderId, UUID.randomUUID()))
                .satisfies(ex -> assertThat(ex.getCode()).isEqualTo("ORDER_NOT_FOUND"));

        verifyNoInteractions(organizers, tickets);
    }

    @Test
    void approveOrder_rejectsNonOrganizer() {
        UUID eventId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        JTicketOrder order = orderEntity(orderId, eventEntity(eventId, EventStatus.OK, END),
                userEntity(UUID.randomUUID()), null, OrderStatus.PENDING);
        when(orders.findByIdForUpdate(orderId)).thenReturn(Optional.of(order));
        when(organizers.existsByEvent_IdAndUser_Id(eventId, userId)).thenReturn(false);

        assertThatExceptionOfType(ForbiddenException.class)
                .isThrownBy(() -> orderService.approveOrder(orderId, userId))
                .satisfies(ex -> assertThat(ex.getCode()).isEqualTo("NOT_AN_ORGANIZER"));

        verifyNoInteractions(tiers, tickets);
    }

    @Test
    void approveOrder_rejectsNonPendingOrder() {
        UUID eventId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        JTicketOrder order = orderEntity(orderId, eventEntity(eventId, EventStatus.OK, END),
                userEntity(UUID.randomUUID()), null, OrderStatus.APPROVED);
        when(orders.findByIdForUpdate(orderId)).thenReturn(Optional.of(order));
        when(organizers.existsByEvent_IdAndUser_Id(eventId, userId)).thenReturn(true);

        assertThatExceptionOfType(ConflictException.class)
                .isThrownBy(() -> orderService.approveOrder(orderId, userId))
                .satisfies(ex -> assertThat(ex.getCode()).isEqualTo("ORDER_NOT_PENDING"));

        verifyNoInteractions(tiers, tickets);
    }

    @Test
    void approveOrder_rejectsClosedEvent() {
        UUID eventId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        JTicketOrder order = orderEntity(orderId, eventEntity(eventId, EventStatus.CANCELLED, END),
                userEntity(UUID.randomUUID()), null, OrderStatus.PENDING);
        when(orders.findByIdForUpdate(orderId)).thenReturn(Optional.of(order));
        when(organizers.existsByEvent_IdAndUser_Id(eventId, userId)).thenReturn(true);

        assertThatExceptionOfType(ConflictException.class)
                .isThrownBy(() -> orderService.approveOrder(orderId, userId))
                .satisfies(ex -> assertThat(ex.getCode()).isEqualTo("EVENT_NOT_OPEN"));

        verifyNoInteractions(tiers, tickets);
    }

    @Test
    void approveOrder_rejectsSoldOutTier() {
        UUID eventId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID tierId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        JEvents event = eventEntity(eventId, EventStatus.OK, END);
        JTicketTier tier = tierEntity(tierId, event, "40.00", 1);
        JTicketOrder order = orderEntity(orderId, event, userEntity(UUID.randomUUID()),
                paymentMethodEntity(UUID.randomUUID(), event, true), OrderStatus.PENDING);
        order.getItems().add(orderItemEntity(order, tier, 2, "40.00"));

        when(orders.findByIdForUpdate(orderId)).thenReturn(Optional.of(order));
        when(organizers.existsByEvent_IdAndUser_Id(eventId, userId)).thenReturn(true);
        when(users.findById(userId)).thenReturn(Optional.of(userEntity(userId)));
        when(tiers.findByIdForUpdate(tierId)).thenReturn(Optional.of(tier));
        when(tickets.countByTier_IdAndTicketStatusIn(tierId, CAPACITY_STATUSES)).thenReturn(0L);

        assertThatExceptionOfType(ConflictException.class)
                .isThrownBy(() -> orderService.approveOrder(orderId, userId))
                .satisfies(ex -> assertThat(ex.getCode()).isEqualTo("SOLD_OUT"));

        verify(tickets, never()).save(any());
        verify(orders, never()).saveAndFlush(any());
    }

    // ------------------------------------------------------------------
    // refuseOrder
    // ------------------------------------------------------------------

    @Test
    void refuseOrder_marksRefusedAndCreatesRefundedPlaceholders() {
        UUID eventId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID tierId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        JEvents event = eventEntity(eventId, EventStatus.OK, END);
        JTicketTier tier = tierEntity(tierId, event, "40.00", 10);
        JTicketOrder order = orderEntity(orderId, event, userEntity(UUID.randomUUID()),
                paymentMethodEntity(UUID.randomUUID(), event, true), OrderStatus.PENDING);
        order.getItems().add(orderItemEntity(order, tier, 2, "40.00"));

        when(orders.findByIdForUpdate(orderId)).thenReturn(Optional.of(order));
        when(organizers.existsByEvent_IdAndUser_Id(eventId, userId)).thenReturn(true);
        when(users.findById(userId)).thenReturn(Optional.of(userEntity(userId)));
        when(tickets.save(any(JTicket.class))).thenAnswer(invocation -> {
            JTicket ticket = invocation.getArgument(0);
            ticket.setId(UUID.randomUUID());
            return ticket;
        });
        when(orders.saveAndFlush(order)).thenReturn(order);
        when(orderMapper.toDomain(order)).thenReturn(orderDomain(orderId, eventId, UUID.randomUUID(), UUID.randomUUID(), List.of()));

        orderService.refuseOrder(orderId, userId, new OrderRefuseRequest("unmatched"));

        assertThat(order.getOrderStatus()).isEqualTo(OrderStatus.REFUSED);
        assertThat(order.getRefusalReason()).isEqualTo("unmatched");
        assertThat(order.getReviewedAt()).isNotNull();

        ArgumentCaptor<JTicket> captor = ArgumentCaptor.forClass(JTicket.class);
        verify(tickets, times(2)).save(captor.capture());
        assertThat(captor.getAllValues()).allSatisfy(ticket -> {
            assertThat(ticket.getTicketStatus()).isEqualTo(TicketStatus.REFUNDED);
            assertThat(ticket.getPricePaid()).isEqualByComparingTo("0.00");
        });
    }

    @Test
    void refuseOrder_rejectsBlankReason() {
        UUID eventId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        JTicketOrder order = orderEntity(orderId, eventEntity(eventId, EventStatus.OK, END),
                userEntity(UUID.randomUUID()), null, OrderStatus.PENDING);
        when(orders.findByIdForUpdate(orderId)).thenReturn(Optional.of(order));
        when(organizers.existsByEvent_IdAndUser_Id(eventId, userId)).thenReturn(true);
        doThrow(new BadRequestException("VALIDATION_ERROR", "Validation failed"))
                .when(validator).validateRefuse(any(OrderRefuseRequest.class));

        assertThatExceptionOfType(BadRequestException.class)
                .isThrownBy(() -> orderService.refuseOrder(orderId, userId, new OrderRefuseRequest(" ")))
                .satisfies(ex -> assertThat(ex.getCode()).isEqualTo("VALIDATION_ERROR"));

        verifyNoInteractions(tickets, users);
    }

    @Test
    void refuseOrder_rejectsNonOrganizer() {
        UUID eventId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        JTicketOrder order = orderEntity(orderId, eventEntity(eventId, EventStatus.OK, END),
                userEntity(UUID.randomUUID()), null, OrderStatus.PENDING);
        when(orders.findByIdForUpdate(orderId)).thenReturn(Optional.of(order));
        when(organizers.existsByEvent_IdAndUser_Id(eventId, userId)).thenReturn(false);

        assertThatExceptionOfType(ForbiddenException.class)
                .isThrownBy(() -> orderService.refuseOrder(orderId, userId, new OrderRefuseRequest("nope")))
                .satisfies(ex -> assertThat(ex.getCode()).isEqualTo("NOT_AN_ORGANIZER"));

        verifyNoInteractions(validator, tickets);
    }

    @Test
    void refuseOrder_rejectsNonPendingOrder() {
        UUID eventId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        JTicketOrder order = orderEntity(orderId, eventEntity(eventId, EventStatus.OK, END),
                userEntity(UUID.randomUUID()), null, OrderStatus.REFUSED);
        when(orders.findByIdForUpdate(orderId)).thenReturn(Optional.of(order));
        when(organizers.existsByEvent_IdAndUser_Id(eventId, userId)).thenReturn(true);

        assertThatExceptionOfType(ConflictException.class)
                .isThrownBy(() -> orderService.refuseOrder(orderId, userId, new OrderRefuseRequest("nope")))
                .satisfies(ex -> assertThat(ex.getCode()).isEqualTo("ORDER_NOT_PENDING"));

        verifyNoInteractions(validator, tickets);
    }

    @Test
    void refuseOrder_rejectsUnknownOrder() {
        UUID orderId = UUID.randomUUID();
        when(orders.findByIdForUpdate(orderId)).thenReturn(Optional.empty());

        assertThatExceptionOfType(NotFoundException.class)
                .isThrownBy(() -> orderService.refuseOrder(orderId, UUID.randomUUID(), new OrderRefuseRequest("nope")))
                .satisfies(ex -> assertThat(ex.getCode()).isEqualTo("ORDER_NOT_FOUND"));

        verifyNoInteractions(organizers, validator, tickets);
    }

    // ------------------------------------------------------------------
    // fixtures
    // ------------------------------------------------------------------

    private static JEvents eventEntity(UUID id, EventStatus status, Instant endTime) {
        JEvents event = new JEvents();
        event.setId(id);
        event.setStatus(status);
        event.setEndTime(endTime);
        event.setPriceUnit("USD");
        return event;
    }

    private static JEventPaymentMethod paymentMethodEntity(UUID id, JEvents event, boolean active) {
        JEventPaymentMethod method = new JEventPaymentMethod();
        method.setId(id);
        method.setEvent(event);
        method.setActive(active);
        return method;
    }

    private static JTicketTier tierEntity(UUID id, JEvents event, String price, int capacity) {
        JTicketTier tier = new JTicketTier();
        tier.setId(id);
        tier.setEvent(event);
        tier.setTierName("Tier");
        tier.setPrice(new BigDecimal(price));
        tier.setCapacity(capacity);
        return tier;
    }

    private static JTicketOrder orderEntity(UUID id, JEvents event, JUsers owner,
            JEventPaymentMethod paymentMethod, OrderStatus status) {
        JTicketOrder order = new JTicketOrder();
        order.setId(id);
        order.setEvent(event);
        order.setOwner(owner);
        order.setPaymentMethod(paymentMethod);
        order.setOrderStatus(status);
        return order;
    }

    private static JTicketOrderItem orderItemEntity(JTicketOrder order, JTicketTier tier, int quantity,
            String unitPrice) {
        JTicketOrderItem item = new JTicketOrderItem();
        item.setOrder(order);
        item.setTier(tier);
        item.setQuantity(quantity);
        item.setUnitPrice(new BigDecimal(unitPrice));
        return item;
    }

    private static JUsers userEntity(UUID id) {
        JUsers user = new JUsers();
        user.setId(id);
        user.setUsername("user");
        return user;
    }

    private static TicketOrder orderDomain(UUID id, UUID eventId, UUID ownerId, UUID paymentMethodId,
            List<TicketOrderItem> items) {
        TicketOrder domain = new TicketOrder();
        domain.setId(id);
        domain.setEvent(eventDomain(eventId));
        domain.setOwner(userDomain(ownerId));
        domain.setPaymentMethod(paymentMethodDomain(paymentMethodId, eventId));
        domain.setOrderStatus(OrderStatus.PENDING);
        domain.setItems(items == null ? List.of() : items);
        return domain;
    }

    private static Users userDomain(UUID id) {
        Users user = new Users();
        user.setId(id);
        user.setUsername("user");
        return user;
    }

    private static Events eventDomain(UUID id) {
        Events event = new Events();
        event.setId(id);
        return event;
    }

    private static EventPaymentMethod paymentMethodDomain(UUID id, UUID eventId) {
        EventPaymentMethod method = new EventPaymentMethod();
        method.setId(id);
        method.setEvent(eventDomain(eventId));
        return method;
    }
}
