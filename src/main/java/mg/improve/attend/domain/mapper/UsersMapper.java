package mg.improve.attend.domain.mapper;

import mg.improve.attend.domain.entity.Users;
import mg.improve.attend.repository.entities.JUsers;
import org.springframework.stereotype.Component;

@Component
public class UsersMapper {

    public Users toDomain(JUsers entity) {
        if (entity == null) {
            return null;
        }
        Users domain = new Users();
        domain.setId(entity.getId());
        domain.setEmail(entity.getEmail());
        domain.setPasswordHash(entity.getPasswordHash());
        domain.setFullName(entity.getFullName());
        domain.setUsername(entity.getUsername());
        domain.setDateBirth(entity.getDateBirth());
        domain.setCreatedAt(entity.getCreatedAt());
        domain.setUpdatedAt(entity.getUpdatedAt());
        return domain;
    }

    public JUsers toEntity(Users domain) {
        if (domain == null) {
            return null;
        }
        JUsers entity = new JUsers();
        entity.setId(domain.getId());
        entity.setEmail(domain.getEmail());
        entity.setPasswordHash(domain.getPasswordHash());
        entity.setFullName(domain.getFullName());
        entity.setUsername(domain.getUsername());
        entity.setDateBirth(domain.getDateBirth());
        entity.setCreatedAt(domain.getCreatedAt());
        entity.setUpdatedAt(domain.getUpdatedAt());
        return entity;
    }
}
