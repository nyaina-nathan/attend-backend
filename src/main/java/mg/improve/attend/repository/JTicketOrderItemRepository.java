package mg.improve.attend.repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import mg.improve.attend.repository.entities.JTicketOrderItem;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface JTicketOrderItemRepository extends JpaRepository<JTicketOrderItem, UUID> {

    boolean existsByTier_Id(UUID tierId);

    /** Order items of several orders at once, with their tier fetched eagerly. */
    @EntityGraph(attributePaths = "tier")
    List<JTicketOrderItem> findByOrder_IdIn(Collection<UUID> orderIds);
}
