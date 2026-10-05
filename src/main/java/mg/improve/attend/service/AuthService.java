package mg.improve.attend.service;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import mg.improve.attend.domain.dto.request.RegisterRequest;
import mg.improve.attend.domain.dto.response.UserResponse;
import mg.improve.attend.domain.entity.RefreshToken;
import mg.improve.attend.domain.entity.Users;
import mg.improve.attend.domain.mapper.RefreshTokenMapper;
import mg.improve.attend.domain.mapper.UsersMapper;
import mg.improve.attend.exceptions.ConflictException;
import mg.improve.attend.repository.JRefreshTokenRepository;
import mg.improve.attend.repository.JUsersRepository;
import mg.improve.attend.security.ClientInfo;
import mg.improve.attend.security.ClientInfoResolver;
import mg.improve.attend.security.IssuedToken;
import mg.improve.attend.security.JwtService;
import mg.improve.attend.validator.RegisterRequestValidator;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Registration use case. Owns the orchestration: validate input, enforce uniqueness, hash the
 * password, persist the user and persist the refresh token, leaving HTTP entirely to the
 * controller.
 */
@Service
@RequiredArgsConstructor
public class AuthService {

    private final JUsersRepository userRepo;
    private final JRefreshTokenRepository refreshTokens;
    private final UsersMapper usersMapper;
    private final RefreshTokenMapper refreshTokenMapper;
    private final JwtService jwtService;
    private final PasswordEncoder passwordEncoder;
    private final RegisterRequestValidator validator;
    private final ClientInfoResolver clientInfoResolver;

    @Transactional
    public AuthResult register(RegisterRequest request, HttpServletRequest httpRequest) {
        validator.validate(request);
        requireEmailAvailable(request.email());
        requireUsernameAvailable(request.username());

        ClientInfo clientInfo = clientInfoResolver.resolve(httpRequest);

        Users user = Users.builder()
                .email(request.email())
                .passwordHash(passwordEncoder.encode(request.password()))
                .fullName(request.fullName())
                .username(request.username())
                .dateBirth(request.dateBirth())
                .build();
        // saveAndFlush (not save) so the @CreationTimestamp value is generated before the
        // user is mapped back for the response.
        Users savedUser = usersMapper.toDomain(userRepo.saveAndFlush(usersMapper.toEntity(user)));

        IssuedToken accessToken = jwtService.issueAccessToken(savedUser);
        IssuedToken refreshToken = jwtService.issueRefreshToken(savedUser);
        persistRefreshToken(savedUser, refreshToken, clientInfo);

        return new AuthResult(UserResponse.from(savedUser), accessToken, refreshToken);
    }

    private void requireEmailAvailable(String email) {
        if (userRepo.findByEmail(email).isPresent()) {
            throw new ConflictException("EMAIL_TAKEN", "Email already in use");
        }
    }

    private void requireUsernameAvailable(String username) {
        if (userRepo.findByUsername(username).isPresent()) {
            throw new ConflictException("USERNAME_TAKEN", "Username already in use");
        }
    }

    private void persistRefreshToken(Users user, IssuedToken refreshToken, ClientInfo clientInfo) {
        RefreshToken token = RefreshToken.builder()
                .user(user)
                .tokenHash(jwtService.hashRefreshToken(refreshToken.token()))
                .expiresAt(refreshToken.expiresAt())
                .userAgent(clientInfo.userAgent())
                .ipAddress(clientInfo.ipAddress())
                .build();
        refreshTokens.save(refreshTokenMapper.toEntity(token));
    }
}
