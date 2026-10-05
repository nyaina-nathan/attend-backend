package mg.improve.attend.security;

import java.util.UUID;
import mg.improve.attend.repository.JUsersRepository;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor 
public class CustomUserDetailsService implements UserDetailsService {

    private final JUsersRepository users;

    /**
     * Login resolves the account by email, matching the {@code POST /auth/login} contract.
     */
    @Override
    public UserDetails loadUserByUsername(String email) throws UsernameNotFoundException {
        return users.findByEmail(email)
                .map(CustomUserDetails::new)
                .orElseThrow(() -> new UsernameNotFoundException("No user with email: " + email));
    }

    /**
     * Used by the JWT filter, where the access token subject is the user id.
     */
    public CustomUserDetails loadUserById(UUID id) {
        return users.findById(id)
                .map(CustomUserDetails::new)
                .orElseThrow(() -> new UsernameNotFoundException("No user with id: " + id));
    }
}
