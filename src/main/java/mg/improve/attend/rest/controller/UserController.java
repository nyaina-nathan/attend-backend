package mg.improve.attend.rest.controller;

import lombok.RequiredArgsConstructor;
import mg.improve.attend.domain.dto.request.UserUpdateRequest;
import mg.improve.attend.domain.dto.response.UserMeResponse;
import mg.improve.attend.security.AuthCookieFactory;
import mg.improve.attend.security.CustomUserDetails;
import mg.improve.attend.service.UserService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
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
    private final AuthCookieFactory authCookieFactory;

    @GetMapping
    public ResponseEntity<UserMeResponse> getCurrentUser(@AuthenticationPrincipal CustomUserDetails principal) {
        return ResponseEntity.ok(userService.getCurrentUser(principal.getId()));
    }

    @PatchMapping
    public ResponseEntity<UserMeResponse> updateCurrentUser(@AuthenticationPrincipal CustomUserDetails principal,
            @RequestBody UserUpdateRequest request) {
        return ResponseEntity.ok(userService.updateCurrentUser(principal.getId(), request));
    }

    @DeleteMapping
    public ResponseEntity<Void> deleteMe(@AuthenticationPrincipal CustomUserDetails principal) {
        userService.deleteAccount(principal.getId());
        return ResponseEntity.noContent()
                .headers(authCookieFactory.clearHeaders())
                .build();
    }
}
