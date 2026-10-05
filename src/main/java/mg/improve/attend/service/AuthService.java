package mg.improve.attend.service;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import mg.improve.attend.domain.dto.request.LoginRequest;
import mg.improve.attend.domain.dto.request.RegisterRequest;
import mg.improve.attend.domain.dto.response.UserResponse;
import mg.improve.attend.domain.entity.RefreshToken;
import mg.improve.attend.domain.entity.Users;
import mg.improve.attend.domain.mapper.RefreshTokenMapper;
import mg.improve.attend.domain.mapper.UsersMapper;
import mg.improve.attend.exceptions.ConflictException;
import mg.improve.attend.exceptions.UnauthorizedException;
import mg.improve.attend.repository.JRefreshTokenRepository;
import mg.improve.attend.repository.JUsersRepository;
import mg.improve.attend.security.ClientInfo;
import mg.improve.attend.security.ClientInfoResolver;
import mg.improve.attend.security.IssuedToken;
import mg.improve.attend.security.JwtService;
import mg.improve.attend.validator.AuthValidator;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Authentication use cases (register, login). Own the orchestration: validate input, enforce
 * uniqueness and credentials, hash/verify passwords, persist the user and the refresh token,
 * leaving HTTP entirely to the controller.
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
    private final AuthValidator validator;
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

        return startSession(savedUser, clientInfo);
    }

    @Transactional
    public AuthResult login(LoginRequest request, HttpServletRequest httpRequest) {
        validator.validate(request);

        // Unknown email and wrong password are deliberately indistinguishable.
        Users user = userRepo.findByEmail(request.email())
                .map(usersMapper::toDomain)
                .filter(candidate -> passwordEncoder.matches(request.password(), candidate.getPasswordHash()))
                .orElseThrow(AuthService::invalidCredentials);

        ClientInfo clientInfo = clientInfoResolver.resolve(httpRequest);
        return startSession(user, clientInfo);
    }

    private AuthResult startSession(Users user, ClientInfo clientInfo) {
        IssuedToken accessToken = jwtService.issueAccessToken(user);
        IssuedToken refreshToken = jwtService.issueRefreshToken(user);
        persistRefreshToken(user, refreshToken, clientInfo);
        return new AuthResult(UserResponse.from(user), accessToken, refreshToken);
    }

    private static UnauthorizedException invalidCredentials() {
        return new UnauthorizedException("INVALID_CREDENTIALS", "Invalid email or password");
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
