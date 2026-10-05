package mg.improve.attend.repository;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import mg.improve.attend.repository.entities.JRefreshToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface JRefreshTokenRepository extends JpaRepository<JRefreshToken, UUID> {

    /**
     * Looks up a refresh token by its hash, taking a write lock so concurrent refreshes of the
     * same token cannot both rotate it.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from JRefreshToken t where t.tokenHash = :tokenHash")
    Optional<JRefreshToken> findByTokenHashForUpdate(@Param("tokenHash") String tokenHash);
}
