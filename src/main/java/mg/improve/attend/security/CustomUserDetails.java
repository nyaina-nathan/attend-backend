package mg.improve.attend.security;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import mg.improve.attend.repository.entities.JUsers;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

/**
 * Spring Security principal backed by the persisted {@link JUsers} entity.
 *
 * <p>Global roles do not exist in this application (roles are per event and checked in
 * the service layer), so {@link #getAuthorities()} is always empty. The token itself
 * carries only the user identity.
 */
public class CustomUserDetails implements UserDetails {

    private final JUsers user;

    public CustomUserDetails(JUsers user) {
        this.user = user;
    }

    public JUsers getUser() {
        return user;
    }

    public UUID getId() {
        return user.getId();
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of();
    }

    @Override
    public String getPassword() {
        return user.getPasswordHash();
    }

    @Override
    public String getUsername() {
        return user.getUsername();
    }

    @Override
    public boolean isAccountNonExpired() {
        return true;
    }

    @Override
    public boolean isAccountNonLocked() {
        return true;
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }

    @Override
    public boolean isEnabled() {
        return true;
    }
}
