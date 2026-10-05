package mg.improve.attend.domain.mapper;

import mg.improve.attend.domain.entity.RefreshToken;
import mg.improve.attend.repository.entities.JRefreshToken;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

@Component
public class RefreshTokenMapper {

    private final UsersMapper usersMapper;
    private final RefreshTokenMapper self;

    public RefreshTokenMapper(UsersMapper usersMapper, @Lazy RefreshTokenMapper self) {
        this.usersMapper = usersMapper;
        this.self = self;
    }

    public RefreshToken toDomain(JRefreshToken entity) {
        if (entity == null) {
            return null;
        }
        RefreshToken domain = new RefreshToken();
        domain.setId(entity.getId());
        domain.setUser(usersMapper.toDomain(entity.getUser()));
        domain.setTokenHash(entity.getTokenHash());
        domain.setExpiresAt(entity.getExpiresAt());
        domain.setCreatedAt(entity.getCreatedAt());
        domain.setRevokedAt(entity.getRevokedAt());
        domain.setReplacedBy(self.toDomain(entity.getReplacedBy()));
        domain.setUserAgent(entity.getUserAgent());
        domain.setIpAddress(entity.getIpAddress());
        return domain;
    }

    public JRefreshToken toEntity(RefreshToken domain) {
        if (domain == null) {
            return null;
        }
        JRefreshToken entity = new JRefreshToken();
        entity.setId(domain.getId());
        entity.setUser(usersMapper.toEntity(domain.getUser()));
        entity.setTokenHash(domain.getTokenHash());
        entity.setExpiresAt(domain.getExpiresAt());
        entity.setCreatedAt(domain.getCreatedAt());
        entity.setRevokedAt(domain.getRevokedAt());
        entity.setReplacedBy(self.toEntity(domain.getReplacedBy()));
        entity.setUserAgent(domain.getUserAgent());
        entity.setIpAddress(domain.getIpAddress());
        return entity;
    }
}
