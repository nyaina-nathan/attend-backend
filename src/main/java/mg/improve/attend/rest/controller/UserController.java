package mg.improve.attend.rest.controller;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import mg.improve.attend.domain.dto.request.ChangePasswordRequest;
import mg.improve.attend.domain.dto.request.UserUpdateRequest;
import mg.improve.attend.domain.dto.response.DashboardResponse;
import mg.improve.attend.domain.dto.response.PageUserResponse;
import mg.improve.attend.domain.dto.response.UserMeResponse;
import mg.improve.attend.security.AuthCookieFactory;
import mg.improve.attend.security.CustomUserDetails;
import mg.improve.attend.service.DashboardService;
import mg.improve.attend.service.SessionTokens;
import mg.improve.attend.service.UserService;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * User endpoints. Like the auth controller, it only delegates to the service, shapes the HTTP
 * response and clears the auth cookies as needed.
 */
@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;
    private final DashboardService dashboardService;
    private final AuthCookieFactory authCookieFactory;

    /**
     * Searches users by a case-insensitive partial username and/or email. At least one of the two
     * filters is required; when both are given, users matching either are returned.
     */
    @GetMapping
    public ResponseEntity<PageUserResponse> searchUsers(
            @RequestParam(name = "username", required = false) String username,
            @RequestParam(name = "email", required = false) String email,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "20") int size,
            @RequestParam(name = "sort", defaultValue = "username,asc") String sort) {
        return ResponseEntity.ok(userService.searchUsers(username, email, page, size, sort));
    }

    @GetMapping("/me")
    public ResponseEntity<UserMeResponse> getCurrentUser(@AuthenticationPrincipal CustomUserDetails principal) {
        return ResponseEntity.ok(userService.getCurrentUser(principal.getId()));
    }

    /**
     * Aggregated organizer and attendee data for the dashboard home page. Per-user, so it must
     * not be cached.
     */
    @GetMapping("/me/dashboard")
    public ResponseEntity<DashboardResponse> getDashboard(@AuthenticationPrincipal CustomUserDetails principal) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(dashboardService.getDashboard(principal.getId()));
    }

    @PatchMapping("/me")
    public ResponseEntity<UserMeResponse> updateCurrentUser(@AuthenticationPrincipal CustomUserDetails principal,
            @RequestBody UserUpdateRequest request) {
        return ResponseEntity.ok(userService.updateCurrentUser(principal.getId(), request));
    }

    @PostMapping("/me/password")
    public ResponseEntity<Void> changePassword(@AuthenticationPrincipal CustomUserDetails principal,
            @RequestBody ChangePasswordRequest request, HttpServletRequest httpRequest) {
        SessionTokens tokens = userService.changePassword(principal.getId(), request, httpRequest);
        return ResponseEntity.noContent()
                .headers(authCookieFactory.headers(tokens.accessToken(), tokens.refreshToken()))
                .build();
    }

    @DeleteMapping("/me")
    public ResponseEntity<Void> deleteMe(@AuthenticationPrincipal CustomUserDetails principal) {
        userService.deleteAccount(principal.getId());
        return ResponseEntity.noContent()
                .headers(authCookieFactory.clearHeaders())
                .build();
    }
}
