package mg.improve.attend.repository;

import java.util.Optional;
import java.util.UUID;
import mg.improve.attend.repository.entities.JUsers;
import org.springframework.data.jpa.repository.JpaRepository;

public interface JUsersRepository extends JpaRepository<JUsers, UUID> {

    Optional<JUsers> findByEmail(String email);

    Optional<JUsers> findByUsername(String username);
}
