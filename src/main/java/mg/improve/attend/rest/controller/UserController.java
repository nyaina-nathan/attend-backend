package mg.improve.attend.rest.controller;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import mg.improve.attend.domain.dto.request.ChangePasswordRequest;
import mg.improve.attend.domain.dto.request.UserUpdateRequest;
import mg.improve.attend.domain.dto.response.DashboardResponse;
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
import org.springframework.web.bind.annotation.RestController;

/**
 * Current-user endpoints. Like the auth controller, it only delegates to the service, shapes
 * the HTTP response and clears the auth cookies as needed.
 */
@RestController
@RequestMapping("/api/users/me")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;
    private final DashboardService dashboardService;
    private final AuthCookieFactory authCookieFactory;

    @GetMapping
    public ResponseEntity<UserMeResponse> getCurrentUser(@AuthenticationPrincipal CustomUserDetails principal) {
        return ResponseEntity.ok(userService.getCurrentUser(principal.getId()));
    }

    /**
     * Aggregated organizer and attendee data for the dashboard home page. Per-user, so it must
     * not be cached.
     */
    @GetMapping("/dashboard")
    public ResponseEntity<DashboardResponse> getDashboard(@AuthenticationPrincipal CustomUserDetails principal) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(dashboardService.getDashboard(principal.getId()));
    }

    @PatchMapping
    public ResponseEntity<UserMeResponse> updateCurrentUser(@AuthenticationPrincipal CustomUserDetails principal,
            @RequestBody UserUpdateRequest request) {
        return ResponseEntity.ok(userService.updateCurrentUser(principal.getId(), request));
    }

    @PostMapping("/password")
    public ResponseEntity<Void> changePassword(@AuthenticationPrincipal CustomUserDetails principal,
            @RequestBody ChangePasswordRequest request, HttpServletRequest httpRequest) {
        SessionTokens tokens = userService.changePassword(principal.getId(), request, httpRequest);
        return ResponseEntity.noContent()
                .headers(authCookieFactory.headers(tokens.accessToken(), tokens.refreshToken()))
                .build();
    }

    @DeleteMapping
    public ResponseEntity<Void> deleteMe(@AuthenticationPrincipal CustomUserDetails principal) {
        userService.deleteAccount(principal.getId());
        return ResponseEntity.noContent()
                .headers(authCookieFactory.clearHeaders())
                .build();
    }
}
