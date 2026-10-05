package mg.improve.attend.repository;

import java.util.UUID;
import mg.improve.attend.repository.entities.JEvents;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface JEventsRepository extends JpaRepository<JEvents, UUID>, JpaSpecificationExecutor<JEvents> {
}
