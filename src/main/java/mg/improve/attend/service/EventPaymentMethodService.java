package mg.improve.attend.service;

import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import mg.improve.attend.domain.dto.request.PaymentMethodCreateRequest;
import mg.improve.attend.domain.dto.request.PaymentMethodUpdateRequest;
import mg.improve.attend.domain.dto.response.PaymentMethodResponse;
import mg.improve.attend.domain.enums.EventRole;
import mg.improve.attend.domain.mapper.EventPaymentMethodMapper;
import mg.improve.attend.exceptions.ConflictException;
import mg.improve.attend.exceptions.ForbiddenException;
import mg.improve.attend.exceptions.NotFoundException;
import mg.improve.attend.exceptions.UnauthorizedException;
import mg.improve.attend.repository.JEventOrganizerRepository;
import mg.improve.attend.repository.JEventPaymentMethodRepository;
import mg.improve.attend.repository.JEventsRepository;
import mg.improve.attend.repository.JTicketOrderRepository;
import mg.improve.attend.repository.entities.JEventPaymentMethod;
import mg.improve.attend.repository.entities.JEvents;
import mg.improve.attend.validator.PaymentMethodValidator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Event payment method use cases. Reads follow the owning event's visibility, except that only
 * the event's creator sees inactive methods; mutations are creator-only. Per-event role checks
 * live here, never in the URL security config, which only answers "is the caller authenticated?".
 */
@Service
@RequiredArgsConstructor
public class EventPaymentMethodService {

    private final JEventsRepository events;
    private final JEventOrganizerRepository organizers;
    private final JEventPaymentMethodRepository paymentMethods;
    private final JTicketOrderRepository ticketOrders;
    private final EventPaymentMethodMapper paymentMethodMapper;
    private final PaymentMethodValidator validator;

    /**
     * Lists the payment methods of an event. Public events are visible to everyone; a private
     * event's methods only to its organizers. Non-creators only see active methods; the creator
     * also sees inactive ones.
     */
    @Transactional(readOnly = true)
    public List<PaymentMethodResponse> listPaymentMethods(UUID eventId, UUID userId) {
        JEvents event = loadEventOr404(eventId);
        requireEventVisible(event, userId);

        boolean creator = isCreator(eventId, userId);
        return paymentMethods.findByEvent_Id(eventId).stream()
                .filter(method -> creator || method.isActive())
                .map(paymentMethodMapper::toDomain)
                .map(PaymentMethodResponse::from)
                .toList();
    }

    /**
     * Returns a single payment method. The event id is resolved from the method. Non-creators
     * can only fetch active methods; an inactive method is hidden ({@code 404}) from them.
     */
    @Transactional(readOnly = true)
    public PaymentMethodResponse getPaymentMethod(UUID paymentMethodId, UUID userId) {
        JEventPaymentMethod method = loadPaymentMethodOr404(paymentMethodId);
        UUID eventId = method.getEvent().getId();
        requireEventVisible(method.getEvent(), userId);

        if (!method.isActive() && !isCreator(eventId, userId)) {
            throw new NotFoundException("PAYMENT_METHOD_NOT_FOUND", "Payment method not found");
        }

        return PaymentMethodResponse.from(paymentMethodMapper.toDomain(method));
    }

    /**
     * Creates a payment method on an event. Creator only.
     */
    @Transactional
    public PaymentMethodResponse createPaymentMethod(UUID eventId, UUID userId,
            PaymentMethodCreateRequest request) {
        JEvents event = loadEventOr404(eventId);
        requireCreator(eventId, userId);
        validator.validateCreate(request);

        JEventPaymentMethod method = new JEventPaymentMethod();
        method.setEvent(event);
        method.setPaymentType(request.paymentType());
        method.setProviderName(request.providerName());
        method.setAccountName(request.accountName());
        method.setAccountIdentifier(request.accountIdentifier());
        method.setRoutingDetails(request.routingDetails());
        method.setInstructions(request.instructions());
        method.setActive(request.isActive() == null || request.isActive());

        JEventPaymentMethod saved = paymentMethods.saveAndFlush(method);
        return PaymentMethodResponse.from(paymentMethodMapper.toDomain(saved));
    }

    /**
     * Updates a payment method. Creator only. Only the non-null fields are applied; the owning
     * event cannot be changed.
     */
    @Transactional
    public PaymentMethodResponse updatePaymentMethod(UUID paymentMethodId, UUID userId,
            PaymentMethodUpdateRequest request) {
        JEventPaymentMethod method = loadPaymentMethodOr404(paymentMethodId);
        requireCreator(method.getEvent().getId(), userId);
        validator.validateUpdate(request);

        if (request.paymentType() != null) {
            method.setPaymentType(request.paymentType());
        }
        if (request.providerName() != null) {
            method.setProviderName(request.providerName());
        }
        if (request.accountName() != null) {
            method.setAccountName(request.accountName());
        }
        if (request.accountIdentifier() != null) {
            method.setAccountIdentifier(request.accountIdentifier());
        }
        if (request.routingDetails() != null) {
            method.setRoutingDetails(request.routingDetails());
        }
        if (request.instructions() != null) {
            method.setInstructions(request.instructions());
        }
        if (request.isActive() != null) {
            method.setActive(request.isActive());
        }

        JEventPaymentMethod saved = paymentMethods.saveAndFlush(method);
        return PaymentMethodResponse.from(paymentMethodMapper.toDomain(saved));
    }

    /**
     * Deletes a payment method. Creator only.
     */
    @Transactional
    public void deletePaymentMethod(UUID paymentMethodId, UUID userId) {
        JEventPaymentMethod method = loadPaymentMethodOr404(paymentMethodId);
        requireCreator(method.getEvent().getId(), userId);

        if (ticketOrders.existsByPaymentMethod_Id(paymentMethodId)) {
            throw new ConflictException("PAYMENT_METHOD_IN_USE",
                    "Payment orders reference this payment method");
        }

        paymentMethods.delete(method);
    }

    /**
     * Enforces the owning event's read visibility: a private event is hidden from anonymous
     * callers ({@code 401}) and from authenticated non-organizers ({@code 403}).
     */
    private void requireEventVisible(JEvents event, UUID userId) {
        if (event.isPublic()) {
            return;
        }
        if (userId == null) {
            throw new UnauthorizedException("UNAUTHORIZED", "Authentication required");
        }
        if (!organizers.existsByEvent_IdAndUser_Id(event.getId(), userId)) {
            throw new ForbiddenException("NOT_AN_ORGANIZER", "Caller is not an organizer of this event");
        }
    }

    private boolean isCreator(UUID eventId, UUID userId) {
        return userId != null
                && organizers.existsByEvent_IdAndUser_IdAndEventRole(eventId, userId, EventRole.CREATOR);
    }

    private void requireCreator(UUID eventId, UUID userId) {
        if (!organizers.existsByEvent_IdAndUser_IdAndEventRole(eventId, userId, EventRole.CREATOR)) {
            throw new ForbiddenException("NOT_THE_CREATOR", "Caller is not the creator of this event");
        }
    }

    private JEvents loadEventOr404(UUID eventId) {
        return events.findById(eventId)
                .orElseThrow(() -> new NotFoundException("EVENT_NOT_FOUND", "Event not found"));
    }

    private JEventPaymentMethod loadPaymentMethodOr404(UUID paymentMethodId) {
        return paymentMethods.findById(paymentMethodId)
                .orElseThrow(() -> new NotFoundException("PAYMENT_METHOD_NOT_FOUND", "Payment method not found"));
    }
}
