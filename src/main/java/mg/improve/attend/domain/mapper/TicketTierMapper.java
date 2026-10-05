package mg.improve.attend.domain.mapper;

import mg.improve.attend.domain.entity.TicketTier;
import mg.improve.attend.repository.entities.JTicketTier;
import org.springframework.stereotype.Component;

@Component
public class TicketTierMapper {

    private final EventsMapper eventsMapper;

    public TicketTierMapper(EventsMapper eventsMapper) {
        this.eventsMapper = eventsMapper;
    }

    public TicketTier toDomain(JTicketTier entity) {
        if (entity == null) {
            return null;
        }
        TicketTier domain = new TicketTier();
        domain.setId(entity.getId());
        domain.setEvent(eventsMapper.toDomain(entity.getEvent()));
        domain.setTierName(entity.getTierName());
        domain.setPrice(entity.getPrice());
        domain.setCapacity(entity.getCapacity());
        return domain;
    }

    public JTicketTier toEntity(TicketTier domain) {
        if (domain == null) {
            return null;
        }
        JTicketTier entity = new JTicketTier();
        entity.setId(domain.getId());
        entity.setEvent(eventsMapper.toEntity(domain.getEvent()));
        entity.setTierName(domain.getTierName());
        entity.setPrice(domain.getPrice());
        entity.setCapacity(domain.getCapacity());
        return entity;
    }
}
