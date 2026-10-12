package mg.improve.attend.domain.mapper;

import mg.improve.attend.domain.entity.EventPaymentMethod;
import mg.improve.attend.repository.entities.JEventPaymentMethod;
import org.springframework.stereotype.Component;

@Component
public class EventPaymentMethodMapper {

    private final EventsMapper eventsMapper;

    public EventPaymentMethodMapper(EventsMapper eventsMapper) {
        this.eventsMapper = eventsMapper;
    }

    public EventPaymentMethod toDomain(JEventPaymentMethod entity) {
        if (entity == null) {
            return null;
        }
        EventPaymentMethod domain = new EventPaymentMethod();
        domain.setId(entity.getId());
        domain.setEvent(eventsMapper.toDomain(entity.getEvent()));
        domain.setPaymentType(entity.getPaymentType());
        domain.setProviderName(entity.getProviderName());
        domain.setAccountName(entity.getAccountName());
        domain.setAccountIdentifier(entity.getAccountIdentifier());
        domain.setRoutingDetails(entity.getRoutingDetails());
        domain.setInstructions(entity.getInstructions());
        domain.setActive(entity.isActive());
        domain.setCreatedAt(entity.getCreatedAt());
        domain.setUpdatedAt(entity.getUpdatedAt());
        return domain;
    }

    public JEventPaymentMethod toEntity(EventPaymentMethod domain) {
        if (domain == null) {
            return null;
        }
        JEventPaymentMethod entity = new JEventPaymentMethod();
        entity.setId(domain.getId());
        entity.setEvent(eventsMapper.toEntity(domain.getEvent()));
        entity.setPaymentType(domain.getPaymentType());
        entity.setProviderName(domain.getProviderName());
        entity.setAccountName(domain.getAccountName());
        entity.setAccountIdentifier(domain.getAccountIdentifier());
        entity.setRoutingDetails(domain.getRoutingDetails());
        entity.setInstructions(domain.getInstructions());
        entity.setActive(domain.isActive());
        entity.setCreatedAt(domain.getCreatedAt());
        entity.setUpdatedAt(domain.getUpdatedAt());
        return entity;
    }
}
