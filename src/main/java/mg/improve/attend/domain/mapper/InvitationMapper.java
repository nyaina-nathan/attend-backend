package mg.improve.attend.domain.mapper;

import mg.improve.attend.domain.entity.Invitation;
import mg.improve.attend.domain.enums.InvitationStatus;
import mg.improve.attend.repository.entities.JInvitation;
import org.springframework.stereotype.Component;

@Component
public class InvitationMapper {

    private final UsersMapper usersMapper;
    private final EventsMapper eventsMapper;

    public InvitationMapper(UsersMapper usersMapper, EventsMapper eventsMapper) {
        this.usersMapper = usersMapper;
        this.eventsMapper = eventsMapper;
    }

    public Invitation toDomain(JInvitation entity) {
        if (entity == null) {
            return null;
        }
        Invitation domain = new Invitation();
        domain.setId(entity.getId());
        domain.setSender(usersMapper.toDomain(entity.getSender()));
        domain.setReceiver(usersMapper.toDomain(entity.getReceiver()));
        domain.setEvent(eventsMapper.toDomain(entity.getEvent()));
        domain.setInvitationStatus(entity.getInvitationStatus());
        domain.setCreatedAt(entity.getCreatedAt());
        return domain;
    }

    public JInvitation toEntity(Invitation domain) {
        if (domain == null) {
            return null;
        }
        JInvitation entity = new JInvitation();
        entity.setId(domain.getId());
        entity.setSender(usersMapper.toEntity(domain.getSender()));
        entity.setReceiver(usersMapper.toEntity(domain.getReceiver()));
        entity.setEvent(eventsMapper.toEntity(domain.getEvent()));
        entity.setInvitationStatus(
                domain.getInvitationStatus() != null ? domain.getInvitationStatus() : InvitationStatus.PENDING);
        entity.setCreatedAt(domain.getCreatedAt());
        return entity;
    }
}
