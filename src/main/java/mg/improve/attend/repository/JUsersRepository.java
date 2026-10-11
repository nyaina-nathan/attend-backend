package mg.improve.attend.repository;

import java.util.Optional;
import java.util.UUID;
import mg.improve.attend.repository.entities.JUsers;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface JUsersRepository extends JpaRepository<JUsers, UUID> {

    Optional<JUsers> findByEmail(String email);

    Optional<JUsers> findByUsername(String username);

    /**
     * Case-insensitive partial search by {@code username} and/or {@code email}. Each argument is
     * an already-lowercased {@code %value%} pattern, or {@code null} to ignore it; when both are
     * present the user matches either (union). Callers guarantee at least one is non-null.
     */
    @Query("select u from JUsers u where "
            + "(:username is not null and lower(u.username) like :username) "
            + "or (:email is not null and lower(u.email) like :email)")
    Page<JUsers> search(@Param("username") String username,
            @Param("email") String email,
            Pageable pageable);
}
