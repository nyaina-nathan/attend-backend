package mg.improve.attend.repository;

import java.util.UUID;
import mg.improve.attend.repository.entities.JEvents;
import org.springframework.data.jpa.repository.JpaRepository;

public interface JEventsRepository extends JpaRepository<JEvents, UUID> {
}
