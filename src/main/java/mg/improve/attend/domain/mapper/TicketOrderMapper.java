package mg.improve.attend.domain.mapper;

import java.util.List;
import mg.improve.attend.domain.entity.TicketOrder;
import mg.improve.attend.domain.entity.TicketOrderItem;
import mg.improve.attend.repository.entities.JTicketOrder;
import mg.improve.attend.repository.entities.JTicketOrderItem;
import org.springframework.stereotype.Component;

@Component
public class TicketOrderMapper {

    private final UsersMapper usersMapper;
    private final EventsMapper eventsMapper;
    private final EventPaymentMethodMapper paymentMethodMapper;
    private final TicketTierMapper ticketTierMapper;

    public TicketOrderMapper(UsersMapper usersMapper, EventsMapper eventsMapper,
            EventPaymentMethodMapper paymentMethodMapper, TicketTierMapper ticketTierMapper) {
        this.usersMapper = usersMapper;
        this.eventsMapper = eventsMapper;
        this.paymentMethodMapper = paymentMethodMapper;
        this.ticketTierMapper = ticketTierMapper;
    }

    /** Maps an order whose {@code items} collection is already loaded or initialized. */
    public TicketOrder toDomain(JTicketOrder entity) {
        return toDomain(entity, entity == null ? null : entity.getItems());
    }

    /** Maps an order with an explicitly supplied item list, so paginated queries avoid a lazy load. */
    public TicketOrder toDomain(JTicketOrder entity, List<JTicketOrderItem> items) {
        if (entity == null) {
            return null;
        }
        TicketOrder domain = new TicketOrder();
        domain.setId(entity.getId());
        domain.setOwner(usersMapper.toDomain(entity.getOwner()));
        domain.setEvent(eventsMapper.toDomain(entity.getEvent()));
        domain.setPaymentMethod(paymentMethodMapper.toDomain(entity.getPaymentMethod()));
        domain.setReviewedBy(usersMapper.toDomain(entity.getReviewedBy()));
        domain.setTransactionReference(entity.getTransactionReference());
        domain.setPriceUnit(entity.getPriceUnit());
        domain.setOrderStatus(entity.getOrderStatus());
        domain.setRefusalReason(entity.getRefusalReason());
        domain.setReviewedAt(entity.getReviewedAt());
        domain.setCreatedAt(entity.getCreatedAt());
        domain.setUpdatedAt(entity.getUpdatedAt());
        domain.setItems(items == null ? List.of() : items.stream().map(this::toItem).toList());
        return domain;
    }

    public TicketOrderItem toItem(JTicketOrderItem entity) {
        if (entity == null) {
            return null;
        }
        TicketOrderItem domain = new TicketOrderItem();
        domain.setId(entity.getId());
        domain.setTier(ticketTierMapper.toDomain(entity.getTier()));
        domain.setQuantity(entity.getQuantity());
        domain.setUnitPrice(entity.getUnitPrice());
        return domain;
    }

    public JTicketOrder toEntity(TicketOrder domain) {
        if (domain == null) {
            return null;
        }
        JTicketOrder entity = new JTicketOrder();
        entity.setId(domain.getId());
        entity.setOwner(usersMapper.toEntity(domain.getOwner()));
        entity.setEvent(eventsMapper.toEntity(domain.getEvent()));
        entity.setPaymentMethod(paymentMethodMapper.toEntity(domain.getPaymentMethod()));
        entity.setReviewedBy(usersMapper.toEntity(domain.getReviewedBy()));
        entity.setTransactionReference(domain.getTransactionReference());
        entity.setPriceUnit(domain.getPriceUnit());
        entity.setOrderStatus(domain.getOrderStatus());
        entity.setRefusalReason(domain.getRefusalReason());
        entity.setReviewedAt(domain.getReviewedAt());
        entity.setCreatedAt(domain.getCreatedAt());
        entity.setUpdatedAt(domain.getUpdatedAt());
        if (domain.getItems() != null) {
            for (TicketOrderItem item : domain.getItems()) {
                entity.getItems().add(toItemEntity(item, entity));
            }
        }
        return entity;
    }

    private JTicketOrderItem toItemEntity(TicketOrderItem domain, JTicketOrder order) {
        if (domain == null) {
            return null;
        }
        JTicketOrderItem entity = new JTicketOrderItem();
        entity.setId(domain.getId());
        entity.setOrder(order);
        entity.setTier(ticketTierMapper.toEntity(domain.getTier()));
        entity.setQuantity(domain.getQuantity());
        entity.setUnitPrice(domain.getUnitPrice());
        return entity;
    }
}
