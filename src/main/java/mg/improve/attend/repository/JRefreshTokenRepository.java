package mg.improve.attend.repository;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import mg.improve.attend.repository.entities.JRefreshToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
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

    /**
     * Revokes every still-active refresh token of a user. Used when the password changes, so
     * all previously issued sessions stop working.
     *
     * @return the number of tokens revoked
     */
    @Modifying
    @Query("update JRefreshToken t set t.revokedAt = :revokedAt "
            + "where t.user.id = :userId and t.revokedAt is null")
    int revokeActiveByUserId(@Param("userId") UUID userId, @Param("revokedAt") Instant revokedAt);
}
