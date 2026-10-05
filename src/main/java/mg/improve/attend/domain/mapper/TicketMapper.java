package mg.improve.attend.domain.mapper;

import mg.improve.attend.domain.entity.Ticket;
import mg.improve.attend.repository.entities.JTicket;
import org.springframework.stereotype.Component;

@Component
public class TicketMapper {

    private final UsersMapper usersMapper;
    private final TicketTierMapper ticketTierMapper;

    public TicketMapper(UsersMapper usersMapper, TicketTierMapper ticketTierMapper) {
        this.usersMapper = usersMapper;
        this.ticketTierMapper = ticketTierMapper;
    }

    public Ticket toDomain(JTicket entity) {
        if (entity == null) {
            return null;
        }
        Ticket domain = new Ticket();
        domain.setId(entity.getId());
        domain.setOwner(usersMapper.toDomain(entity.getOwner()));
        domain.setCheckedBy(usersMapper.toDomain(entity.getCheckedBy()));
        domain.setTier(ticketTierMapper.toDomain(entity.getTier()));
        domain.setPricePaid(entity.getPricePaid());
        domain.setTicketStatus(entity.getTicketStatus());
        domain.setCheckedInAt(entity.getCheckedInAt());
        domain.setCreatedAt(entity.getCreatedAt());
        domain.setValidUntil(entity.getValidUntil());
        return domain;
    }

    public JTicket toEntity(Ticket domain) {
        if (domain == null) {
            return null;
        }
        JTicket entity = new JTicket();
        entity.setId(domain.getId());
        entity.setOwner(usersMapper.toEntity(domain.getOwner()));
        entity.setCheckedBy(usersMapper.toEntity(domain.getCheckedBy()));
        entity.setTier(ticketTierMapper.toEntity(domain.getTier()));
        entity.setPricePaid(domain.getPricePaid());
        entity.setTicketStatus(domain.getTicketStatus());
        entity.setCheckedInAt(domain.getCheckedInAt());
        entity.setCreatedAt(domain.getCreatedAt());
        entity.setValidUntil(domain.getValidUntil());
        return entity;
    }
}
