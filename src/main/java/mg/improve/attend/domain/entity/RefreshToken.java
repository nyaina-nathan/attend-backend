package mg.improve.attend.domain.entity;

import java.time.Instant;
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
public class RefreshToken {

    private UUID id;
    private Users user;
    private String tokenHash;
    private Instant expiresAt;
    private Instant createdAt;
    private Instant revokedAt;
    private RefreshToken replacedBy;
    private String userAgent;
    private String ipAddress;
}
