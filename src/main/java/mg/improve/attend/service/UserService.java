package mg.improve.attend.service;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import mg.improve.attend.domain.dto.request.ChangePasswordRequest;
import mg.improve.attend.domain.dto.request.UserUpdateRequest;
import mg.improve.attend.domain.dto.response.PageUserResponse;
import mg.improve.attend.domain.dto.response.UserMeResponse;
import mg.improve.attend.domain.dto.response.UserResponse;
import mg.improve.attend.domain.entity.Users;
import mg.improve.attend.domain.enums.EventRole;
import mg.improve.attend.domain.mapper.UsersMapper;
import mg.improve.attend.exceptions.BadRequestException;
import mg.improve.attend.exceptions.ConflictException;
import mg.improve.attend.exceptions.ErrorDetail;
import mg.improve.attend.exceptions.NotFoundException;
import mg.improve.attend.repository.JEventOrganizerRepository;
import mg.improve.attend.repository.JEventsRepository;
import mg.improve.attend.repository.JTicketRepository;
import mg.improve.attend.repository.JTicketTierRepository;
import mg.improve.attend.repository.JUsersRepository;
import mg.improve.attend.repository.entities.JUsers;
import mg.improve.attend.security.ClientInfoResolver;
import mg.improve.attend.validator.UserValidator;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Account-level use cases. Currently the deletion of the current user together with all the
 * data that references them.
 */
@Service
@RequiredArgsConstructor
public class UserService {

    private final JUsersRepository users;
    private final JEventsRepository events;
    private final JEventOrganizerRepository organizers;
    private final JTicketTierRepository tiers;
    private final JTicketRepository tickets;
    private final UsersMapper usersMapper;
    private final UserValidator validator;
    private final PasswordEncoder passwordEncoder;
    private final ClientInfoResolver clientInfoResolver;
    private final RefreshTokenService refreshTokenService;

    /**
     * Hard-deletes the user and everything that depends on them, in foreign-key order.
     *
     * <p>Events they created are removed entirely, including tiers and tickets held by other
     * users. Refresh tokens are removed by the database {@code ON DELETE CASCADE}.
     */
    @Transactional
    public void deleteAccount(UUID userId) {
        // 1. Tickets the user owns, in any event.
        tickets.deleteByOwnerId(userId);

        // 2. Events they created: remove every dependent row before the event itself.
        List<UUID> createdEventIds = organizers.findEventIdsByUserAndRole(userId, EventRole.CREATOR);
        for (UUID eventId : createdEventIds) {
            tickets.deleteByEventId(eventId);
            tiers.deleteByEventId(eventId);
            organizers.deleteByEventId(eventId);
            events.deleteById(eventId);
        }

        // 3. Remaining organizer memberships (ADMIN roles) on events that survive.
        organizers.deleteByUserId(userId);

        // 4. Scan marker on surviving tickets; status is kept.
        tickets.clearCheckInByUserId(userId);

        // 5. The user. refresh_token rows go with them via the cascading FK.
        users.findById(userId).ifPresent(users::delete);
    }

    @Transactional(readOnly = true)
    public UserMeResponse getCurrentUser(UUID userId) {
        return UserMeResponse.from(load(userId));
    }

    /**
     * Searches users by a case-insensitive partial {@code username} and/or {@code email}. At
     * least one must be provided; when both are, users matching either are returned. Paginated,
     * defaulting to {@code username,asc}.
     */
    @Transactional(readOnly = true)
    public PageUserResponse searchUsers(String username, String email, int page, int size, String sort) {
        Sort resolvedSort = validator.validateSearchQuery(username, email, page, size, sort);
        Pageable pageable = PageRequest.of(page, size, resolvedSort);

        Page<JUsers> result = users.search(likePattern(username), likePattern(email), pageable);

        List<UserResponse> content = result.getContent().stream()
                .map(usersMapper::toDomain)
                .map(UserResponse::from)
                .toList();

        return new PageUserResponse(
                result.getNumber(),
                result.getSize(),
                result.getTotalElements(),
                result.getTotalPages(),
                content);
    }

    private static String likePattern(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return "%" + value.trim().toLowerCase(Locale.ROOT) + "%";
    }

    @Transactional
    public UserMeResponse updateCurrentUser(UUID userId, UserUpdateRequest request) {
        validator.validate(request);
        requireUsernameAvailable(userId, request.username());

        JUsers entity = users.findById(userId)
                .orElseThrow(() -> new NotFoundException("USER_NOT_FOUND", "User not found"));
        if (request.fullName() != null) {
            entity.setFullName(request.fullName());
        }
        if (request.username() != null) {
            entity.setUsername(request.username());
        }
        if (request.dateBirth() != null) {
            entity.setDateBirth(request.dateBirth());
        }

        // saveAndFlush so the @UpdateTimestamp value is generated before the response is built.
        return UserMeResponse.from(usersMapper.toDomain(users.saveAndFlush(entity)));
    }

    private Users load(UUID userId) {
        return users.findById(userId)
                .map(usersMapper::toDomain)
                .orElseThrow(() -> new NotFoundException("USER_NOT_FOUND", "User not found"));
    }

    private void requireUsernameAvailable(UUID userId, String username) {
        if (username == null) {
            return;
        }
        users.findByUsername(username)
                .filter(existing -> !existing.getId().equals(userId))
                .ifPresent(existing -> {
                    throw new ConflictException("USERNAME_TAKEN", "Username already in use");
                });
    }

    /**
     * Changes the password after verifying the current one, then invalidates every existing
     * refresh token and issues a fresh session for the caller so they stay logged in.
     */
    @Transactional
    public SessionTokens changePassword(UUID userId, ChangePasswordRequest request,
            HttpServletRequest httpRequest) {
        validator.validate(request);

        JUsers entity = users.findById(userId)
                .orElseThrow(() -> new NotFoundException("USER_NOT_FOUND", "User not found"));
        if (!passwordEncoder.matches(request.currentPassword(), entity.getPasswordHash())) {
            throw new BadRequestException("VALIDATION_ERROR", "Validation failed",
                    List.of(new ErrorDetail("currentPassword", "is incorrect")));
        }

        entity.setPasswordHash(passwordEncoder.encode(request.newPassword()));
        users.saveAndFlush(entity);

        refreshTokenService.revokeAllActive(userId);

        Users user = usersMapper.toDomain(entity);
        return refreshTokenService.issueSession(user, clientInfoResolver.resolve(httpRequest));
    }
}
