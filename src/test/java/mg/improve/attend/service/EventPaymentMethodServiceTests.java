package mg.improve.attend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import mg.improve.attend.domain.dto.request.PaymentMethodCreateRequest;
import mg.improve.attend.domain.dto.request.PaymentMethodUpdateRequest;
import mg.improve.attend.domain.dto.response.PaymentMethodResponse;
import mg.improve.attend.domain.entity.EventPaymentMethod;
import mg.improve.attend.domain.entity.Events;
import mg.improve.attend.domain.enums.EventRole;
import mg.improve.attend.domain.enums.PaymentType;
import mg.improve.attend.domain.mapper.EventPaymentMethodMapper;
import mg.improve.attend.exceptions.BadRequestException;
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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

@ExtendWith(MockitoExtension.class)
class EventPaymentMethodServiceTests {

    @Mock
    private JEventsRepository events;

    @Mock
    private JEventOrganizerRepository organizers;

    @Mock
    private JEventPaymentMethodRepository paymentMethods;

    @Mock
    private JTicketOrderRepository ticketOrders;

    @Mock
    private EventPaymentMethodMapper paymentMethodMapper;

    @Mock
    private PaymentMethodValidator validator;

    @InjectMocks
    private EventPaymentMethodService paymentMethodService;

    // ------------------------------------------------------------------
    // listPaymentMethods
    // ------------------------------------------------------------------

    @Test
    void listPaymentMethods_returnsActiveOnlyForAnonymousOnPublicEvent() {
        UUID eventId = UUID.randomUUID();
        UUID activeId = UUID.randomUUID();
        UUID inactiveId = UUID.randomUUID();
        JEvents event = eventEntity(eventId, true);
        JEventPaymentMethod active = methodEntity(activeId, event, true);
        JEventPaymentMethod inactive = methodEntity(inactiveId, event, false);
        EventPaymentMethod activeDomain = methodDomain(activeId, eventDomain(eventId, true), true);

        when(events.findById(eventId)).thenReturn(Optional.of(event));
        when(paymentMethods.findByEvent_Id(eventId)).thenReturn(List.of(active, inactive));
        when(paymentMethodMapper.toDomain(active)).thenReturn(activeDomain);

        List<PaymentMethodResponse> response = paymentMethodService.listPaymentMethods(eventId, null);

        assertThat(response).hasSize(1);
        assertThat(response.get(0).id()).isEqualTo(activeId);
        assertThat(response.get(0).eventId()).isEqualTo(eventId);
        assertThat(response.get(0).isActive()).isTrue();
        verifyNoInteractions(organizers);
    }

    @Test
    void listPaymentMethods_returnsAllForCreator() {
        UUID eventId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID activeId = UUID.randomUUID();
        UUID inactiveId = UUID.randomUUID();
        JEvents event = eventEntity(eventId, true);
        JEventPaymentMethod active = methodEntity(activeId, event, true);
        JEventPaymentMethod inactive = methodEntity(inactiveId, event, false);

        when(events.findById(eventId)).thenReturn(Optional.of(event));
        when(organizers.existsByEvent_IdAndUser_IdAndEventRole(eventId, userId, EventRole.CREATOR))
                .thenReturn(true);
        when(paymentMethods.findByEvent_Id(eventId)).thenReturn(List.of(active, inactive));
        when(paymentMethodMapper.toDomain(active)).thenReturn(methodDomain(activeId, eventDomain(eventId, true), true));
        when(paymentMethodMapper.toDomain(inactive))
                .thenReturn(methodDomain(inactiveId, eventDomain(eventId, true), false));

        List<PaymentMethodResponse> response = paymentMethodService.listPaymentMethods(eventId, userId);

        assertThat(response).hasSize(2);
    }

    @Test
    void listPaymentMethods_allowsOrganizerOnPrivateEventButHidesInactive() {
        UUID eventId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID activeId = UUID.randomUUID();
        JEvents event = eventEntity(eventId, false);
        JEventPaymentMethod active = methodEntity(activeId, event, true);
        JEventPaymentMethod inactive = methodEntity(UUID.randomUUID(), event, false);

        when(events.findById(eventId)).thenReturn(Optional.of(event));
        when(organizers.existsByEvent_IdAndUser_Id(eventId, userId)).thenReturn(true);
        when(organizers.existsByEvent_IdAndUser_IdAndEventRole(eventId, userId, EventRole.CREATOR))
                .thenReturn(false);
        when(paymentMethods.findByEvent_Id(eventId)).thenReturn(List.of(active, inactive));
        when(paymentMethodMapper.toDomain(active)).thenReturn(methodDomain(activeId, eventDomain(eventId, false), true));

        List<PaymentMethodResponse> response = paymentMethodService.listPaymentMethods(eventId, userId);

        assertThat(response).hasSize(1);
        assertThat(response.get(0).id()).isEqualTo(activeId);
    }

    @Test
    void listPaymentMethods_rejectsAnonymousOnPrivateEvent() {
        UUID eventId = UUID.randomUUID();
        JEvents event = eventEntity(eventId, false);
        when(events.findById(eventId)).thenReturn(Optional.of(event));

        assertThatExceptionOfType(UnauthorizedException.class)
                .isThrownBy(() -> paymentMethodService.listPaymentMethods(eventId, null))
                .satisfies(ex -> assertThat(ex.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED));

        verifyNoInteractions(paymentMethods, organizers);
    }

    @Test
    void listPaymentMethods_rejectsNonOrganizerOnPrivateEvent() {
        UUID eventId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        JEvents event = eventEntity(eventId, false);

        when(events.findById(eventId)).thenReturn(Optional.of(event));
        when(organizers.existsByEvent_IdAndUser_Id(eventId, userId)).thenReturn(false);

        assertThatExceptionOfType(ForbiddenException.class)
                .isThrownBy(() -> paymentMethodService.listPaymentMethods(eventId, userId))
                .satisfies(ex -> assertThat(ex.getCode()).isEqualTo("NOT_AN_ORGANIZER"));

        verifyNoInteractions(paymentMethods);
    }

    @Test
    void listPaymentMethods_rejectsUnknownEvent() {
        UUID eventId = UUID.randomUUID();
        when(events.findById(eventId)).thenReturn(Optional.empty());

        assertThatExceptionOfType(NotFoundException.class)
                .isThrownBy(() -> paymentMethodService.listPaymentMethods(eventId, null))
                .satisfies(ex -> assertThat(ex.getCode()).isEqualTo("EVENT_NOT_FOUND"));

        verifyNoInteractions(paymentMethods, organizers);
    }

    // ------------------------------------------------------------------
    // getPaymentMethod
    // ------------------------------------------------------------------

    @Test
    void getPaymentMethod_returnsActiveForAnonymousOnPublicEvent() {
        UUID eventId = UUID.randomUUID();
        UUID methodId = UUID.randomUUID();
        JEvents event = eventEntity(eventId, true);
        JEventPaymentMethod method = methodEntity(methodId, event, true);

        when(paymentMethods.findById(methodId)).thenReturn(Optional.of(method));
        when(paymentMethodMapper.toDomain(method)).thenReturn(methodDomain(methodId, eventDomain(eventId, true), true));

        PaymentMethodResponse response = paymentMethodService.getPaymentMethod(methodId, null);

        assertThat(response.id()).isEqualTo(methodId);
        assertThat(response.eventId()).isEqualTo(eventId);
        verifyNoInteractions(organizers);
    }

    @Test
    void getPaymentMethod_returnsInactiveForCreator() {
        UUID eventId = UUID.randomUUID();
        UUID methodId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        JEvents event = eventEntity(eventId, true);
        JEventPaymentMethod method = methodEntity(methodId, event, false);

        when(paymentMethods.findById(methodId)).thenReturn(Optional.of(method));
        when(organizers.existsByEvent_IdAndUser_IdAndEventRole(eventId, userId, EventRole.CREATOR))
                .thenReturn(true);
        when(paymentMethodMapper.toDomain(method)).thenReturn(methodDomain(methodId, eventDomain(eventId, true), false));

        PaymentMethodResponse response = paymentMethodService.getPaymentMethod(methodId, userId);

        assertThat(response.id()).isEqualTo(methodId);
        assertThat(response.isActive()).isFalse();
    }

    @Test
    void getPaymentMethod_hidesInactiveFromNonCreator() {
        UUID eventId = UUID.randomUUID();
        UUID methodId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        JEvents event = eventEntity(eventId, true);
        JEventPaymentMethod method = methodEntity(methodId, event, false);

        when(paymentMethods.findById(methodId)).thenReturn(Optional.of(method));
        when(organizers.existsByEvent_IdAndUser_IdAndEventRole(eventId, userId, EventRole.CREATOR))
                .thenReturn(false);

        assertThatExceptionOfType(NotFoundException.class)
                .isThrownBy(() -> paymentMethodService.getPaymentMethod(methodId, userId))
                .satisfies(ex -> assertThat(ex.getCode()).isEqualTo("PAYMENT_METHOD_NOT_FOUND"));

        verifyNoInteractions(paymentMethodMapper);
    }

    @Test
    void getPaymentMethod_rejectsAnonymousOnPrivateEvent() {
        UUID eventId = UUID.randomUUID();
        UUID methodId = UUID.randomUUID();
        JEvents event = eventEntity(eventId, false);
        JEventPaymentMethod method = methodEntity(methodId, event, true);

        when(paymentMethods.findById(methodId)).thenReturn(Optional.of(method));

        assertThatExceptionOfType(UnauthorizedException.class)
                .isThrownBy(() -> paymentMethodService.getPaymentMethod(methodId, null))
                .satisfies(ex -> assertThat(ex.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED));

        verifyNoInteractions(paymentMethodMapper);
    }

    @Test
    void getPaymentMethod_rejectsNonOrganizerOnPrivateEvent() {
        UUID eventId = UUID.randomUUID();
        UUID methodId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        JEvents event = eventEntity(eventId, false);
        JEventPaymentMethod method = methodEntity(methodId, event, true);

        when(paymentMethods.findById(methodId)).thenReturn(Optional.of(method));
        when(organizers.existsByEvent_IdAndUser_Id(eventId, userId)).thenReturn(false);

        assertThatExceptionOfType(ForbiddenException.class)
                .isThrownBy(() -> paymentMethodService.getPaymentMethod(methodId, userId))
                .satisfies(ex -> assertThat(ex.getCode()).isEqualTo("NOT_AN_ORGANIZER"));

        verifyNoInteractions(paymentMethodMapper);
    }

    @Test
    void getPaymentMethod_rejectsUnknownMethod() {
        UUID methodId = UUID.randomUUID();
        when(paymentMethods.findById(methodId)).thenReturn(Optional.empty());

        assertThatExceptionOfType(NotFoundException.class)
                .isThrownBy(() -> paymentMethodService.getPaymentMethod(methodId, UUID.randomUUID()))
                .satisfies(ex -> assertThat(ex.getCode()).isEqualTo("PAYMENT_METHOD_NOT_FOUND"));

        verifyNoInteractions(organizers, paymentMethodMapper);
    }

    // ------------------------------------------------------------------
    // createPaymentMethod
    // ------------------------------------------------------------------

    @Test
    void createPaymentMethod_persistsForCreatorAndDefaultsActive() {
        UUID eventId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID methodId = UUID.randomUUID();
        JEvents event = eventEntity(eventId, true);
        PaymentMethodCreateRequest request = new PaymentMethodCreateRequest(
                PaymentType.MOBILE_MONEY, "M-Pesa", "Jane Doe", "+261340000000", null, "Send then share", null);
        JEventPaymentMethod saved = methodEntity(methodId, event, true);
        EventPaymentMethod domain = methodDomain(methodId, eventDomain(eventId, true), true);

        when(events.findById(eventId)).thenReturn(Optional.of(event));
        when(organizers.existsByEvent_IdAndUser_IdAndEventRole(eventId, userId, EventRole.CREATOR))
                .thenReturn(true);
        when(paymentMethods.saveAndFlush(any(JEventPaymentMethod.class))).thenReturn(saved);
        when(paymentMethodMapper.toDomain(saved)).thenReturn(domain);

        PaymentMethodResponse response = paymentMethodService.createPaymentMethod(eventId, userId, request);

        verify(validator).validateCreate(request);
        ArgumentCaptor<JEventPaymentMethod> captor = ArgumentCaptor.forClass(JEventPaymentMethod.class);
        verify(paymentMethods).saveAndFlush(captor.capture());
        JEventPaymentMethod captured = captor.getValue();
        assertThat(captured.getEvent()).isSameAs(event);
        assertThat(captured.getPaymentType()).isEqualTo(PaymentType.MOBILE_MONEY);
        assertThat(captured.getProviderName()).isEqualTo("M-Pesa");
        assertThat(captured.getAccountName()).isEqualTo("Jane Doe");
        assertThat(captured.getAccountIdentifier()).isEqualTo("+261340000000");
        assertThat(captured.getInstructions()).isEqualTo("Send then share");
        assertThat(captured.isActive()).isTrue();
        assertThat(response.id()).isEqualTo(methodId);
        assertThat(response.eventId()).isEqualTo(eventId);
    }

    @Test
    void createPaymentMethod_respectsExplicitInactive() {
        UUID eventId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        JEvents event = eventEntity(eventId, true);
        PaymentMethodCreateRequest request = new PaymentMethodCreateRequest(
                PaymentType.CASH, "Cash", "Jane Doe", "Desk", null, null, false);
        JEventPaymentMethod saved = methodEntity(UUID.randomUUID(), event, false);

        when(events.findById(eventId)).thenReturn(Optional.of(event));
        when(organizers.existsByEvent_IdAndUser_IdAndEventRole(eventId, userId, EventRole.CREATOR))
                .thenReturn(true);
        when(paymentMethods.saveAndFlush(any(JEventPaymentMethod.class))).thenReturn(saved);
        when(paymentMethodMapper.toDomain(saved))
                .thenReturn(methodDomain(saved.getId(), eventDomain(eventId, true), false));

        paymentMethodService.createPaymentMethod(eventId, userId, request);

        ArgumentCaptor<JEventPaymentMethod> captor = ArgumentCaptor.forClass(JEventPaymentMethod.class);
        verify(paymentMethods).saveAndFlush(captor.capture());
        assertThat(captor.getValue().isActive()).isFalse();
    }

    @Test
    void createPaymentMethod_rejectsNonCreator() {
        UUID eventId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        JEvents event = eventEntity(eventId, true);
        PaymentMethodCreateRequest request = new PaymentMethodCreateRequest(
                PaymentType.MOBILE_MONEY, "M-Pesa", "Jane Doe", "+261340000000", null, null, null);

        when(events.findById(eventId)).thenReturn(Optional.of(event));
        when(organizers.existsByEvent_IdAndUser_IdAndEventRole(eventId, userId, EventRole.CREATOR))
                .thenReturn(false);

        assertThatExceptionOfType(ForbiddenException.class)
                .isThrownBy(() -> paymentMethodService.createPaymentMethod(eventId, userId, request))
                .satisfies(ex -> assertThat(ex.getCode()).isEqualTo("NOT_THE_CREATOR"));

        verifyNoInteractions(validator);
        verify(paymentMethods, never()).saveAndFlush(any());
    }

    @Test
    void createPaymentMethod_rejectsUnknownEvent() {
        UUID eventId = UUID.randomUUID();
        when(events.findById(eventId)).thenReturn(Optional.empty());

        assertThatExceptionOfType(NotFoundException.class)
                .isThrownBy(() -> paymentMethodService.createPaymentMethod(eventId, UUID.randomUUID(),
                        new PaymentMethodCreateRequest(PaymentType.CARD, "Visa", "Jane", "4111", null, null, null)))
                .satisfies(ex -> assertThat(ex.getCode()).isEqualTo("EVENT_NOT_FOUND"));

        verifyNoInteractions(validator);
    }

    @Test
    void createPaymentMethod_propagatesValidationFailure() {
        UUID eventId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        JEvents event = eventEntity(eventId, true);
        PaymentMethodCreateRequest request = new PaymentMethodCreateRequest(
                null, null, null, null, null, null, null);

        when(events.findById(eventId)).thenReturn(Optional.of(event));
        when(organizers.existsByEvent_IdAndUser_IdAndEventRole(eventId, userId, EventRole.CREATOR))
                .thenReturn(true);
        doThrow(new BadRequestException("VALIDATION_ERROR", "Validation failed"))
                .when(validator).validateCreate(any(PaymentMethodCreateRequest.class));

        assertThatExceptionOfType(BadRequestException.class)
                .isThrownBy(() -> paymentMethodService.createPaymentMethod(eventId, userId, request))
                .satisfies(ex -> assertThat(ex.getCode()).isEqualTo("VALIDATION_ERROR"));

        verify(paymentMethods, never()).saveAndFlush(any());
    }

    // ------------------------------------------------------------------
    // updatePaymentMethod
    // ------------------------------------------------------------------

    @Test
    void updatePaymentMethod_appliesOnlyNonNullFields() {
        UUID eventId = UUID.randomUUID();
        UUID methodId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        JEvents event = eventEntity(eventId, true);
        JEventPaymentMethod method = methodEntity(methodId, event, true);
        method.setAccountName("Jane Doe");
        method.setInstructions("Old instruction");
        PaymentMethodUpdateRequest request = new PaymentMethodUpdateRequest(
                null, "M-Pesa Paybill", null, null, null, null, false);

        when(paymentMethods.findById(methodId)).thenReturn(Optional.of(method));
        when(organizers.existsByEvent_IdAndUser_IdAndEventRole(eventId, userId, EventRole.CREATOR))
                .thenReturn(true);
        when(paymentMethods.saveAndFlush(method)).thenReturn(method);
        when(paymentMethodMapper.toDomain(method)).thenReturn(methodDomain(methodId, eventDomain(eventId, true), false));

        PaymentMethodResponse response = paymentMethodService.updatePaymentMethod(methodId, userId, request);

        verify(validator).validateUpdate(request);
        assertThat(method.getProviderName()).isEqualTo("M-Pesa Paybill");
        assertThat(method.getAccountName()).isEqualTo("Jane Doe");
        assertThat(method.getInstructions()).isEqualTo("Old instruction");
        assertThat(method.isActive()).isFalse();
        assertThat(response.id()).isEqualTo(methodId);
    }

    @Test
    void updatePaymentMethod_rejectsNonCreator() {
        UUID eventId = UUID.randomUUID();
        UUID methodId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        JEvents event = eventEntity(eventId, true);
        JEventPaymentMethod method = methodEntity(methodId, event, true);

        when(paymentMethods.findById(methodId)).thenReturn(Optional.of(method));
        when(organizers.existsByEvent_IdAndUser_IdAndEventRole(eventId, userId, EventRole.CREATOR))
                .thenReturn(false);

        assertThatExceptionOfType(ForbiddenException.class)
                .isThrownBy(() -> paymentMethodService.updatePaymentMethod(methodId, userId,
                        new PaymentMethodUpdateRequest(null, "Nope", null, null, null, null, null)))
                .satisfies(ex -> assertThat(ex.getCode()).isEqualTo("NOT_THE_CREATOR"));

        verifyNoInteractions(validator);
        verify(paymentMethods, never()).saveAndFlush(any());
    }

    @Test
    void updatePaymentMethod_rejectsUnknownMethod() {
        UUID methodId = UUID.randomUUID();
        when(paymentMethods.findById(methodId)).thenReturn(Optional.empty());

        assertThatExceptionOfType(NotFoundException.class)
                .isThrownBy(() -> paymentMethodService.updatePaymentMethod(methodId, UUID.randomUUID(),
                        new PaymentMethodUpdateRequest(null, "Nope", null, null, null, null, null)))
                .satisfies(ex -> assertThat(ex.getCode()).isEqualTo("PAYMENT_METHOD_NOT_FOUND"));

        verifyNoInteractions(organizers, validator);
    }

    @Test
    void updatePaymentMethod_propagatesValidationFailure() {
        UUID eventId = UUID.randomUUID();
        UUID methodId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        JEvents event = eventEntity(eventId, true);
        JEventPaymentMethod method = methodEntity(methodId, event, true);

        when(paymentMethods.findById(methodId)).thenReturn(Optional.of(method));
        when(organizers.existsByEvent_IdAndUser_IdAndEventRole(eventId, userId, EventRole.CREATOR))
                .thenReturn(true);
        doThrow(new BadRequestException("VALIDATION_ERROR", "Validation failed"))
                .when(validator).validateUpdate(any(PaymentMethodUpdateRequest.class));

        assertThatExceptionOfType(BadRequestException.class)
                .isThrownBy(() -> paymentMethodService.updatePaymentMethod(methodId, userId,
                        new PaymentMethodUpdateRequest(null, "", null, null, null, null, null)));

        verify(paymentMethods, never()).saveAndFlush(any());
    }

    // ------------------------------------------------------------------
    // deletePaymentMethod
    // ------------------------------------------------------------------

    @Test
    void deletePaymentMethod_deletesForCreator() {
        UUID eventId = UUID.randomUUID();
        UUID methodId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        JEvents event = eventEntity(eventId, true);
        JEventPaymentMethod method = methodEntity(methodId, event, true);

        when(paymentMethods.findById(methodId)).thenReturn(Optional.of(method));
        when(organizers.existsByEvent_IdAndUser_IdAndEventRole(eventId, userId, EventRole.CREATOR))
                .thenReturn(true);

        paymentMethodService.deletePaymentMethod(methodId, userId);

        verify(paymentMethods).delete(method);
    }

    @Test
    void deletePaymentMethod_rejectsWhenOrdersExist() {
        UUID eventId = UUID.randomUUID();
        UUID methodId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        JEvents event = eventEntity(eventId, true);
        JEventPaymentMethod method = methodEntity(methodId, event, true);

        when(paymentMethods.findById(methodId)).thenReturn(Optional.of(method));
        when(organizers.existsByEvent_IdAndUser_IdAndEventRole(eventId, userId, EventRole.CREATOR))
                .thenReturn(true);
        when(ticketOrders.existsByPaymentMethod_Id(methodId)).thenReturn(true);

        assertThatExceptionOfType(ConflictException.class)
                .isThrownBy(() -> paymentMethodService.deletePaymentMethod(methodId, userId))
                .satisfies(ex -> {
                    assertThat(ex.getCode()).isEqualTo("PAYMENT_METHOD_IN_USE");
                    assertThat(ex.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                });

        verify(paymentMethods, never()).delete(any());
    }

    @Test
    void deletePaymentMethod_rejectsNonCreator() {
        UUID eventId = UUID.randomUUID();
        UUID methodId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        JEvents event = eventEntity(eventId, true);
        JEventPaymentMethod method = methodEntity(methodId, event, true);

        when(paymentMethods.findById(methodId)).thenReturn(Optional.of(method));
        when(organizers.existsByEvent_IdAndUser_IdAndEventRole(eventId, userId, EventRole.CREATOR))
                .thenReturn(false);

        assertThatExceptionOfType(ForbiddenException.class)
                .isThrownBy(() -> paymentMethodService.deletePaymentMethod(methodId, userId))
                .satisfies(ex -> assertThat(ex.getCode()).isEqualTo("NOT_THE_CREATOR"));

        verify(paymentMethods, never()).delete(any());
    }

    @Test
    void deletePaymentMethod_rejectsUnknownMethod() {
        UUID methodId = UUID.randomUUID();
        when(paymentMethods.findById(methodId)).thenReturn(Optional.empty());

        assertThatExceptionOfType(NotFoundException.class)
                .isThrownBy(() -> paymentMethodService.deletePaymentMethod(methodId, UUID.randomUUID()))
                .satisfies(ex -> assertThat(ex.getCode()).isEqualTo("PAYMENT_METHOD_NOT_FOUND"));

        verifyNoInteractions(organizers);
        verify(paymentMethods, never()).delete(any());
    }

    // ------------------------------------------------------------------
    // fixtures
    // ------------------------------------------------------------------

    private static JEvents eventEntity(UUID id, boolean isPublic) {
        JEvents entity = new JEvents();
        entity.setId(id);
        entity.setTitle("Title");
        entity.setPublic(isPublic);
        return entity;
    }

    private static JEventPaymentMethod methodEntity(UUID id, JEvents event, boolean isActive) {
        JEventPaymentMethod entity = new JEventPaymentMethod();
        entity.setId(id);
        entity.setEvent(event);
        entity.setPaymentType(PaymentType.MOBILE_MONEY);
        entity.setProviderName("M-Pesa");
        entity.setAccountName("Jane Doe");
        entity.setAccountIdentifier("+261340000000");
        entity.setActive(isActive);
        return entity;
    }

    private static Events eventDomain(UUID id, boolean isPublic) {
        Events domain = new Events();
        domain.setId(id);
        domain.setPublic(isPublic);
        return domain;
    }

    private static EventPaymentMethod methodDomain(UUID id, Events event, boolean isActive) {
        EventPaymentMethod domain = new EventPaymentMethod();
        domain.setId(id);
        domain.setEvent(event);
        domain.setPaymentType(PaymentType.MOBILE_MONEY);
        domain.setProviderName("M-Pesa");
        domain.setAccountName("Jane Doe");
        domain.setAccountIdentifier("+261340000000");
        domain.setActive(isActive);
        return domain;
    }
}
