package mg.improve.attend.repository;

import java.util.UUID;
import mg.improve.attend.repository.entities.JRefreshToken;
import org.springframework.data.jpa.repository.JpaRepository;

public interface JRefreshTokenRepository extends JpaRepository<JRefreshToken, UUID> {
}
