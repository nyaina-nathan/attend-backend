package mg.improve.attend.repository;

import java.util.UUID;
import mg.improve.attend.repository.entities.JTicketOrderItem;
import org.springframework.data.jpa.repository.JpaRepository;

public interface JTicketOrderItemRepository extends JpaRepository<JTicketOrderItem, UUID> {

    boolean existsByTier_Id(UUID tierId);
}
