package mg.improve.attend.domain.entity;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Users {

    private UUID id;
    private String email;
    private String passwordHash;
    private String fullName;
    private String username;
    private LocalDate dateBirth;
    private Instant createdAt;
    private Instant updatedAt;
}
