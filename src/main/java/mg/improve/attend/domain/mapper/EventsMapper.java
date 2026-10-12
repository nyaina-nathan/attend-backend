package mg.improve.attend.domain.mapper;

import mg.improve.attend.domain.entity.Events;
import mg.improve.attend.repository.entities.JEvents;
import org.springframework.stereotype.Component;

@Component
public class EventsMapper {

    public Events toDomain(JEvents entity) {
        if (entity == null) {
            return null;
        }
        Events domain = new Events();
        domain.setId(entity.getId());
        domain.setTitle(entity.getTitle());
        domain.setDescription(entity.getDescription());
        domain.setStartTime(entity.getStartTime());
        domain.setEndTime(entity.getEndTime());
        domain.setPublic(entity.isPublic());
        domain.setLocation(entity.getLocation());
        domain.setLatitude(entity.getLatitude());
        domain.setLongitude(entity.getLongitude());
        domain.setPriceUnit(entity.getPriceUnit());
        domain.setStatus(entity.getStatus());
        domain.setCreatedAt(entity.getCreatedAt());
        domain.setUpdatedAt(entity.getUpdatedAt());
        return domain;
    }

    public JEvents toEntity(Events domain) {
        if (domain == null) {
            return null;
        }
        JEvents entity = new JEvents();
        entity.setId(domain.getId());
        entity.setTitle(domain.getTitle());
        entity.setDescription(domain.getDescription());
        entity.setStartTime(domain.getStartTime());
        entity.setEndTime(domain.getEndTime());
        entity.setPublic(domain.isPublic());
        entity.setLocation(domain.getLocation());
        entity.setLatitude(domain.getLatitude());
        entity.setLongitude(domain.getLongitude());
        entity.setPriceUnit(domain.getPriceUnit());
        entity.setStatus(domain.getStatus());
        entity.setCreatedAt(domain.getCreatedAt());
        entity.setUpdatedAt(domain.getUpdatedAt());
        return entity;
    }
}
