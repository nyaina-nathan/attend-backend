package mg.improve.attend.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import mg.improve.attend.domain.dto.request.OrderCreateRequest;
import mg.improve.attend.domain.dto.request.OrderItemCreateRequest;
import mg.improve.attend.domain.dto.request.OrderRefuseRequest;
import mg.improve.attend.domain.dto.response.OrderResponse;
import mg.improve.attend.domain.dto.response.OrderTicketRef;
import mg.improve.attend.domain.dto.response.PageOrder;
import mg.improve.attend.domain.enums.EventStatus;
import mg.improve.attend.domain.enums.OrderStatus;
import mg.improve.attend.domain.enums.TicketStatus;
import mg.improve.attend.domain.mapper.TicketOrderMapper;
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
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Ticket order ("command") use cases. A buyer creates a {@code PENDING} order; the event's
 * organizers approve it (issuing tickets) or refuse it with a reason. Per-event role checks
 * live here, never in the URL security config.
 */
@Service
@RequiredArgsConstructor
public class TicketOrderService {

    /** Statuses that consume capacity, and therefore block an approval when the tier is full. */
    private static final List<TicketStatus> CAPACITY_STATUSES =
            List.of(TicketStatus.VALID, TicketStatus.USED);

    private final JEventsRepository events;
    private final JEventOrganizerRepository organizers;
    private final JEventPaymentMethodRepository paymentMethods;
    private final JTicketTierRepository tiers;
    private final JTicketOrderRepository orders;
    private final JTicketOrderItemRepository orderItems;
    private final JTicketRepository tickets;
    private final JUsersRepository users;
    private final TicketOrderMapper orderMapper;
    private final OrderValidator validator;

    /**
     * Creates a {@code PENDING} order for the authenticated caller. Every item's tier and the
     * payment method must belong to the event, and the payment method must be active.
     */
    @Transactional
    public OrderResponse createOrder(UUID eventId, UUID userId, OrderCreateRequest request) {
        JEvents event = loadEventOr404(eventId);
        validator.validateCreate(request);

        JEventPaymentMethod paymentMethod = paymentMethods.findById(request.paymentMethodId())
                .filter(method -> method.getEvent().getId().equals(eventId))
                .orElseThrow(() -> new NotFoundException("PAYMENT_METHOD_NOT_FOUND", "Payment method not found"));
        if (!paymentMethod.isActive()) {
            throw new ConflictException("PAYMENT_METHOD_INACTIVE", "Payment method is not active");
        }

        String reference = blankToNull(request.transactionReference());
        if (reference != null && orders.existsByPaymentMethod_IdAndTransactionReference(
                paymentMethod.getId(), reference)) {
            throw new ConflictException("DUPLICATE_TRANSACTION_REFERENCE",
                    "Transaction reference already used for this payment method");
        }

        requireEventOpen(event);
        JUsers owner = loadUserOr404(userId);

        JTicketOrder order = new JTicketOrder();
        order.setOwner(owner);
        order.setEvent(event);
        order.setPaymentMethod(paymentMethod);
        order.setTransactionReference(reference);
        order.setPriceUnit(event.getPriceUnit());
        order.setOrderStatus(OrderStatus.PENDING);

        for (OrderItemCreateRequest item : request.items()) {
            JTicketTier tier = tiers.findById(item.tierId())
                    .filter(found -> found.getEvent().getId().equals(eventId))
                    .orElseThrow(() -> new NotFoundException("TIER_NOT_FOUND", "Tier not found"));
            JTicketOrderItem orderItem = new JTicketOrderItem();
            orderItem.setOrder(order);
            orderItem.setTier(tier);
            orderItem.setQuantity(item.quantity());
            orderItem.setUnitPrice(tier.getPrice());
            order.getItems().add(orderItem);
        }

        JTicketOrder saved = orders.saveAndFlush(order);
        return toResponse(saved, List.of());
    }

    /** The caller's own orders, newest first. */
    @Transactional(readOnly = true)
    public PageOrder listMyOrders(UUID userId, OrderStatus status, int page, int size) {
        validator.validateListQuery(page, size);
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Order.desc("createdAt")));
        return toPage(orders.findMyOrders(userId, status, pageable));
    }

    /** All orders of an event, for its organizers, newest first. */
    @Transactional(readOnly = true)
    public PageOrder listEventOrders(UUID eventId, UUID userId, OrderStatus status, int page, int size) {
        loadEventOr404(eventId);
        requireOrganizer(eventId, userId);
        validator.validateListQuery(page, size);
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Order.desc("createdAt")));
        return toPage(orders.findEventOrders(eventId, status, pageable));
    }

    /** Returns an order to its owner or to any organizer of its event. */
    @Transactional(readOnly = true)
    public OrderResponse getOrder(UUID orderId, UUID userId) {
        JTicketOrder order = loadOrderDetailOr404(orderId);
        UUID eventId = order.getEvent().getId();

        boolean owner = order.getOwner().getId().equals(userId);
        boolean organizer = organizers.existsByEvent_IdAndUser_Id(eventId, userId);
        if (!owner && !organizer) {
            throw new ForbiddenException("ORDER_FORBIDDEN",
                    "Caller is neither the order owner nor an organizer of this event");
        }

        return toResponse(order, orderTickets(orderId));
    }

    /**
     * Approves a {@code PENDING} order and issues one {@code VALID} ticket per ordered quantity.
     * The order row is locked, and each tier is locked (in id order) and capacity-checked, so
     * concurrent approvals cannot oversell. Organizers only.
     */
    @Transactional
    public OrderResponse approveOrder(UUID orderId, UUID userId) {
        JTicketOrder order = loadOrderForUpdateOr404(orderId);
        JEvents event = order.getEvent();
        requireOrganizer(event.getId(), userId);

        if (order.getOrderStatus() != OrderStatus.PENDING) {
            throw new ConflictException("ORDER_NOT_PENDING", "Order is not pending");
        }
        requireEventOpen(event);
        JUsers reviewer = loadUserOr404(userId);

        List<JTicketOrderItem> items = order.getItems().stream()
                .sorted(Comparator.comparing(item -> item.getTier().getId()))
                .toList();
        for (JTicketOrderItem item : items) {
            JTicketTier tier = tiers.findByIdForUpdate(item.getTier().getId())
                    .orElseThrow(() -> new NotFoundException("TIER_NOT_FOUND", "Tier not found"));
            long issued = tickets.countByTier_IdAndTicketStatusIn(tier.getId(), CAPACITY_STATUSES);
            if (issued + item.getQuantity() > tier.getCapacity()) {
                throw new ConflictException("SOLD_OUT", "No tickets remaining");
            }
        }

        List<OrderTicketRef> issued = new ArrayList<>();
        for (JTicketOrderItem item : items) {
            for (int i = 0; i < item.getQuantity(); i++) {
                JTicket ticket = issueTicket(order, item.getTier(), item.getUnitPrice(),
                        TicketStatus.VALID);
                issued.add(OrderTicketRef.of(ticket.getId(), ticket.getTicketStatus()));
            }
        }

        order.setOrderStatus(OrderStatus.APPROVED);
        order.setReviewedBy(reviewer);
        order.setReviewedAt(Instant.now());
        JTicketOrder saved = orders.saveAndFlush(order);

        return toResponse(saved, issued);
    }

    /**
     * Refuses a {@code PENDING} order with a reason. Records the reason and creates one
     * placeholder {@code REFUNDED} ticket per ordered quantity with a zero amount (a record
     * only; refunded tickets do not consume capacity). Organizers only.
     */
    @Transactional
    public OrderResponse refuseOrder(UUID orderId, UUID userId, OrderRefuseRequest request) {
        JTicketOrder order = loadOrderForUpdateOr404(orderId);
        requireOrganizer(order.getEvent().getId(), userId);

        if (order.getOrderStatus() != OrderStatus.PENDING) {
            throw new ConflictException("ORDER_NOT_PENDING", "Order is not pending");
        }
        validator.validateRefuse(request);
        JUsers reviewer = loadUserOr404(userId);

        List<OrderTicketRef> placeholder = new ArrayList<>();
        for (JTicketOrderItem item : order.getItems()) {
            for (int i = 0; i < item.getQuantity(); i++) {
                JTicket ticket = issueTicket(order, item.getTier(), BigDecimal.ZERO,
                        TicketStatus.REFUNDED);
                placeholder.add(OrderTicketRef.of(ticket.getId(), ticket.getTicketStatus()));
            }
        }

        order.setOrderStatus(OrderStatus.REFUSED);
        order.setRefusalReason(request.reason());
        order.setReviewedBy(reviewer);
        order.setReviewedAt(Instant.now());
        JTicketOrder saved = orders.saveAndFlush(order);

        return toResponse(saved, placeholder);
    }

    private JTicket issueTicket(JTicketOrder order, JTicketTier tier, BigDecimal pricePaid,
            TicketStatus status) {
        JTicket ticket = new JTicket();
        ticket.setOwner(order.getOwner());
        ticket.setTier(tier);
        ticket.setOrder(order);
        ticket.setPricePaid(pricePaid);
        ticket.setTicketStatus(status);
        ticket.setValidUntil(order.getEvent().getEndTime());
        return tickets.save(ticket);
    }

    private OrderResponse toResponse(JTicketOrder order, List<OrderTicketRef> tickets) {
        return OrderResponse.from(orderMapper.toDomain(order), tickets);
    }

    private PageOrder toPage(Page<JTicketOrder> result) {
        List<UUID> orderIds = result.getContent().stream().map(JTicketOrder::getId).toList();

        Map<UUID, List<JTicketOrderItem>> itemsByOrder = orderIds.isEmpty()
                ? Map.of()
                : orderItems.findByOrder_IdIn(orderIds).stream()
                        .collect(Collectors.groupingBy(item -> item.getOrder().getId()));
        Map<UUID, List<OrderTicketRef>> ticketsByOrder = orderIds.isEmpty()
                ? Map.of()
                : tickets.findByOrder_IdIn(orderIds).stream()
                        .collect(Collectors.groupingBy(ticket -> ticket.getOrder().getId(),
                                Collectors.mapping(
                                        ticket -> OrderTicketRef.of(ticket.getId(), ticket.getTicketStatus()),
                                        Collectors.toList())));

        List<OrderResponse> content = result.getContent().stream()
                .map(order -> OrderResponse.from(
                        orderMapper.toDomain(order, itemsByOrder.getOrDefault(order.getId(), List.of())),
                        ticketsByOrder.getOrDefault(order.getId(), List.of())))
                .toList();

        return new PageOrder(
                result.getNumber(),
                result.getSize(),
                result.getTotalElements(),
                result.getTotalPages(),
                content);
    }

    private List<OrderTicketRef> orderTickets(UUID orderId) {
        return tickets.findByOrder_Id(orderId).stream()
                .map(ticket -> OrderTicketRef.of(ticket.getId(), ticket.getTicketStatus()))
                .toList();
    }

    private void requireEventOpen(JEvents event) {
        if (event.getStatus() != EventStatus.OK || event.getEndTime().isBefore(Instant.now())) {
            throw new ConflictException("EVENT_NOT_OPEN", "Event is cancelled or has already ended");
        }
    }

    private void requireOrganizer(UUID eventId, UUID userId) {
        if (!organizers.existsByEvent_IdAndUser_Id(eventId, userId)) {
            throw new ForbiddenException("NOT_AN_ORGANIZER", "Caller is not an organizer of this event");
        }
    }

    private JEvents loadEventOr404(UUID eventId) {
        return events.findById(eventId)
                .orElseThrow(() -> new NotFoundException("EVENT_NOT_FOUND", "Event not found"));
    }

    private JUsers loadUserOr404(UUID userId) {
        return users.findById(userId)
                .orElseThrow(() -> new NotFoundException("USER_NOT_FOUND", "User not found"));
    }

    private JTicketOrder loadOrderDetailOr404(UUID orderId) {
        return orders.findDetailById(orderId)
                .orElseThrow(() -> new NotFoundException("ORDER_NOT_FOUND", "Order not found"));
    }

    private JTicketOrder loadOrderForUpdateOr404(UUID orderId) {
        return orders.findByIdForUpdate(orderId)
                .orElseThrow(() -> new NotFoundException("ORDER_NOT_FOUND", "Order not found"));
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
