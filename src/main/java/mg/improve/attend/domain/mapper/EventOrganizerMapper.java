package mg.improve.attend.domain.mapper;

import mg.improve.attend.domain.entity.EventOrganizer;
import mg.improve.attend.repository.entities.JEventOrganizer;
import org.springframework.stereotype.Component;

@Component
public class EventOrganizerMapper {

    private final UsersMapper usersMapper;
    private final EventsMapper eventsMapper;

    public EventOrganizerMapper(UsersMapper usersMapper, EventsMapper eventsMapper) {
        this.usersMapper = usersMapper;
        this.eventsMapper = eventsMapper;
    }

    public EventOrganizer toDomain(JEventOrganizer entity) {
        if (entity == null) {
            return null;
        }
        EventOrganizer domain = new EventOrganizer();
        domain.setId(entity.getId());
        domain.setUser(usersMapper.toDomain(entity.getUser()));
        domain.setEvent(eventsMapper.toDomain(entity.getEvent()));
        domain.setEventRole(entity.getEventRole());
        return domain;
    }

    public JEventOrganizer toEntity(EventOrganizer domain) {
        if (domain == null) {
            return null;
        }
        JEventOrganizer entity = new JEventOrganizer();
        entity.setId(domain.getId());
        entity.setUser(usersMapper.toEntity(domain.getUser()));
        entity.setEvent(eventsMapper.toEntity(domain.getEvent()));
        entity.setEventRole(domain.getEventRole());
        return entity;
    }
}
